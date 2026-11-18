package com.nuvio.app.features.simkl

import co.touchlab.kermit.Logger
import com.nuvio.app.isDesktop
import com.nuvio.app.features.tracking.TrackingAuthProvider
import com.nuvio.app.features.tracking.TrackingCapability
import com.nuvio.app.features.tracking.TrackingProviderDescriptor
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingProviderRegistry
import com.nuvio.app.features.tracking.TrackingRefreshIntent
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object SimklAuthRepository : TrackingAuthProvider {
    private val log = Logger.withTag("SimklAuth")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val authorizationMutex = Mutex()

    private val _uiState = MutableStateFlow(SimklAuthUiState())
    val uiState: StateFlow<SimklAuthUiState> = _uiState.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.SIMKL,
        displayName = "Simkl",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE,
            TrackingCapability.RATINGS,
        ),
    )

    private var hasLoaded = false
    private var profileGeneration = 0L
    private var storedState = SimklStoredAuthState()
    private var accessToken: String? = null
    private var pinPollingJob: Job? = null

    init {
        TrackingProviderRegistry.register(this)
    }

    override fun ensureLoaded() {
        if (hasLoaded) return
        loadFromDisk()
    }

    override fun onProfileChanged() {
        loadFromDisk()
    }

    override fun clearLocalState() {
        pinPollingJob?.cancel()
        hasLoaded = false
        profileGeneration += 1L
        storedState = SimklStoredAuthState()
        accessToken = null
        publish()
    }

    override fun removeStoredProfile(profileId: Int) {
        SimklAuthStorage.removeProfile(profileId)
    }

    fun snapshot(): SimklAuthUiState {
        ensureLoaded()
        return uiState.value
    }

    fun hasRequiredCredentials(): Boolean = SimklConfig.CLIENT_ID.isNotBlank()

    fun onConnectRequested(): String? {
        ensureLoaded()
        if (!hasRequiredCredentials()) {
            publish(error = SimklAuthError.MISSING_CLIENT_ID)
            return null
        }

        if (isDesktop) {
            return startPinAuthorization()
        }

        val material = generateSimklPkceMaterial()
        SimklAuthStorage.saveCodeVerifier(material.verifier)
        storedState = storedState.copy(
            pendingAuthorizationState = material.state,
            pendingAuthorizationStartedAtEpochMs = SimklPlatformClock.nowEpochMs(),
        )
        persistMetadata()
        publish(error = null)
        return authorizationUrl(material)
    }

    fun pendingAuthorizationUrl(): String? {
        ensureLoaded()
        if (isDesktop) {
            val verificationUrl = storedState.pendingPinVerificationUrl
                ?.takeIf { storedState.hasPendingPinAuthorization }
            if (verificationUrl == null) return null
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                pinPollingJob?.cancel()
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return null
            }
            startPinPollingIfNeeded()
            return verificationUrl
        }
        val state = storedState.pendingAuthorizationState?.takeIf(String::isNotBlank) ?: return null
        val verifier = SimklAuthStorage.loadCodeVerifier()?.takeIf(String::isNotBlank) ?: run {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        if (isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            clearPendingAuthorization()
            persistMetadata()
            publish(error = SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return authorizationUrl(
            SimklPkceMaterial(
                verifier = verifier,
                challenge = SimklPkceCrypto.sha256(verifier.encodeToByteArray()).base64UrlWithoutPadding(),
                state = state,
            ),
        )
    }

    fun onCancelAuthorization() {
        ensureLoaded()
        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(error = null)
    }

    override fun handleAuthCallback(url: String): Boolean {
        ensureLoaded()
        if (isDesktop) return false
        return when (val callback = parseSimklAuthCallback(url, SimklConfig.REDIRECT_URI)) {
            SimklAuthCallback.NotSimkl -> false
            SimklAuthCallback.Invalid -> {
                clearPendingAuthorization()
                persistMetadata()
                publish(error = SimklAuthError.INVALID_CALLBACK)
                true
            }
            is SimklAuthCallback.AuthorizationCode -> {
                scope.launch { completeAuthorization(callback) }
                true
            }
        }
    }

    fun onDisconnectRequested() {
        ensureLoaded()
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        SimklAuthStorage.saveAccessToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(error = null)
    }

    internal fun authorizedAccessToken(): String? {
        ensureLoaded()
        val token = accessToken?.takeIf(String::isNotBlank) ?: return null
        val expiresAt = storedState.tokenExpiresAtEpochMs
        if (expiresAt != null && SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS) {
            invalidateCredentials(SimklAuthError.AUTHORIZATION_EXPIRED)
            return null
        }
        return token
    }

    internal fun onUnauthorizedResponse() {
        invalidateCredentials(SimklAuthError.AUTHORIZATION_REVOKED)
    }

    suspend fun refreshUserSettings(): String? {
        authorizedAccessToken() ?: return null
        return if (fetchAndStoreUserSettings()) storedState.username else null
    }

    internal suspend fun synchronizeUserSettings(activityWatermark: String?) {
        authorizedAccessToken() ?: return
        when (simklSettingsRefreshAction(storedState, activityWatermark)) {
            SimklSettingsRefreshAction.NONE -> Unit
            SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                storedState = storedState.copy(settingsActivityWatermark = activityWatermark)
                persistMetadata()
            }
            SimklSettingsRefreshAction.FETCH -> {
                fetchAndStoreUserSettings(activityWatermark)
            }
        }
    }

    private fun startPinAuthorization(): String? {
        val existingVerificationUrl = storedState.pendingPinVerificationUrl
            ?.takeIf { storedState.hasPendingPinAuthorization }
            ?.takeUnless {
                isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            }
        if (existingVerificationUrl != null) {
            publish(isLoading = false, error = null)
            startPinPollingIfNeeded()
            return existingVerificationUrl
        }

        pinPollingJob?.cancel()
        profileGeneration += 1L
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = true, error = null)
        val generation = profileGeneration
        scope.launch {
            requestPinAuthorization(generation)
        }
        return null
    }

    private suspend fun requestPinAuthorization(generation: Long) {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/oauth/pin",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to start Simkl PIN authorization: ${error.message}" }
            null
        }
        if (profileGeneration != generation) return

        val now = SimklPlatformClock.nowEpochMs()
        val pending = response
            ?.let { runCatching { json.decodeFromString<SimklPinResponse>(it.body) }.getOrNull() }
            ?.toPendingAuthorization(now)
        if (pending == null) {
            clearPendingAuthorization()
            persistMetadata()
            publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
            return
        }

        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = now,
            pendingPinUserCode = pending.userCode,
            pendingPinVerificationUrl = pending.verificationUrl,
            pendingPinIntervalSeconds = pending.intervalSeconds,
            pendingPinExpiresAtEpochMs = pending.expiresAtEpochMs,
        )
        persistMetadata()
        publish(isLoading = false, error = null)
        startPinPollingIfNeeded()
    }

    private fun startPinPollingIfNeeded() {
        if (!isDesktop || !storedState.hasPendingPinAuthorization) return
        if (isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
        ) {
            expirePinAuthorization()
            return
        }
        if (pinPollingJob?.isActive == true) return
        val userCode = storedState.pendingPinUserCode ?: return
        val generation = profileGeneration
        pinPollingJob = scope.launch {
            pollPinAuthorization(userCode, generation)
        }
    }

    private suspend fun pollPinAuthorization(
        userCode: String,
        generation: Long,
    ) {
        val intervalSeconds = storedState.pendingPinIntervalSeconds?.coerceAtLeast(1) ?: 5
        while (isCurrentPinAuthorization(userCode, generation)) {
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            delay(intervalSeconds * 1_000L)
            if (!isCurrentPinAuthorization(userCode, generation)) return
            if (isSimklPinAuthorizationExpired(
                    expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                    nowEpochMs = SimklPlatformClock.nowEpochMs(),
                )
            ) {
                expirePinAuthorization()
                return
            }

            when (val result = pollPinAuthorizationOnce(userCode)) {
                is SimklPinPollResult.Authorized -> {
                    completePinAuthorization(result.accessToken, generation)
                    return
                }
                SimklPinPollResult.Pending -> publish(isLoading = false, error = null)
                SimklPinPollResult.Gone -> {
                    expirePinAuthorization()
                    return
                }
                SimklPinPollResult.Failed -> {
                    clearPendingAuthorization()
                    persistMetadata()
                    publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                    return
                }
            }
        }
    }

    private suspend fun pollPinAuthorizationOnce(userCode: String): SimklPinPollResult {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.GET,
                    path = "/oauth/pin/${userCode.encodeURLParameter()}",
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to poll Simkl PIN authorization: ${error.message}" }
            return SimklPinPollResult.Failed
        }
        return runCatching { json.decodeFromString<SimklPinResponse>(response.body) }
            .getOrNull()
            ?.toPollResult()
            ?: SimklPinPollResult.Failed
    }

    private suspend fun completePinAuthorization(
        token: String,
        generation: Long,
    ) = authorizationMutex.withLock {
        if (profileGeneration != generation) return@withLock
        publish(isLoading = true, error = null)
        accessToken = token
        SimklAuthStorage.saveAccessToken(token)
        clearPendingAuthorization()
        storedState = storedState.copy(tokenExpiresAtEpochMs = null)
        persistMetadata()
        publish(isLoading = false, error = null)
        fetchAndStoreUserSettings()
        SimklSyncRepository.refreshAsync(
            intent = TrackingRefreshIntent.INVALIDATED,
            origin = SimklRefreshOrigin.AUTHORIZATION,
        )
    }

    private fun isCurrentPinAuthorization(
        userCode: String,
        generation: Long,
    ): Boolean = isDesktop &&
        profileGeneration == generation &&
        storedState.pendingPinUserCode == userCode &&
        storedState.hasPendingPinAuthorization &&
        accessToken.isNullOrBlank()

    private fun expirePinAuthorization() {
        clearPendingAuthorization()
        persistMetadata()
        publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
    }

    private suspend fun completeAuthorization(callback: SimklAuthCallback.AuthorizationCode) =
        authorizationMutex.withLock {
            publish(isLoading = true, error = null)
            val expectedState = storedState.pendingAuthorizationState
            val verifier = SimklAuthStorage.loadCodeVerifier()
            val isExpired = isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            if (expectedState.isNullOrBlank() || verifier.isNullOrBlank() || isExpired) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.AUTHORIZATION_EXPIRED)
                return@withLock
            }
            if (!constantTimeEquals(callback.state, expectedState)) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_CALLBACK_STATE)
                return@withLock
            }

            val request = SimklTokenRequest(
                code = callback.code,
                clientId = SimklConfig.CLIENT_ID,
                codeVerifier = verifier,
                redirectUri = SimklConfig.REDIRECT_URI,
            )
            val response = try {
                SimklApi.client.execute(
                    SimklApiRequest(
                        method = SimklHttpMethod.POST,
                        path = "/oauth/token",
                        body = json.encodeToString(request),
                        requiresAuthentication = false,
                        retryPolicy = SimklRetryPolicy.NEVER,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "Simkl token exchange failed: ${error.message}" }
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.TOKEN_EXCHANGE_FAILED)
                return@withLock
            }
            val token = runCatching { json.decodeFromString<SimklTokenResponse>(response.body) }
                .getOrNull()
                ?.takeIf { it.accessToken.isNotBlank() }
            if (token == null) {
                clearPendingAuthorization()
                persistMetadata()
                publish(isLoading = false, error = SimklAuthError.INVALID_TOKEN_RESPONSE)
                return@withLock
            }

            accessToken = token.accessToken
            SimklAuthStorage.saveAccessToken(token.accessToken)
            clearPendingAuthorization()
            storedState = storedState.copy(
                tokenExpiresAtEpochMs = token.expiresIn
                    ?.takeIf { seconds -> seconds > 0L }
                    ?.let { seconds -> SimklPlatformClock.nowEpochMs() + seconds * 1_000L },
            )
            persistMetadata()
            publish(isLoading = false, error = null)
            fetchAndStoreUserSettings()
            SimklSyncRepository.refreshAsync(
                intent = TrackingRefreshIntent.INVALIDATED,
                origin = SimklRefreshOrigin.AUTHORIZATION,
            )
        }

    private suspend fun fetchAndStoreUserSettings(activityWatermark: String? = null): Boolean {
        val response = try {
            SimklApi.client.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/users/settings",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.w { "Failed to fetch Simkl user settings: ${error.message}" }
            return false
        }
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }
            .getOrNull() ?: return false
        storedState = storedState.copy(
            username = settings.user?.name,
            accountId = settings.account?.id,
            hasFetchedUserSettings = true,
            settingsActivityWatermark = activityWatermark ?: storedState.settingsActivityWatermark,
        )
        persistMetadata()
        publish(error = null)
        return true
    }

    private fun loadFromDisk() {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        hasLoaded = true
        storedState = SimklAuthStorage.loadMetadataPayload()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { payload ->
                runCatching { json.decodeFromString<SimklStoredAuthState>(payload) }
                    .onFailure { error -> log.w { "Failed to parse Simkl auth metadata: ${error.message}" } }
                    .getOrNull()
            }
            ?: SimklStoredAuthState()
        accessToken = SimklAuthStorage.loadAccessToken()?.takeIf(String::isNotBlank)
        if (accessToken != null && storedState.tokenExpiresAtEpochMs?.let { expiresAt ->
                SimklPlatformClock.nowEpochMs() >= expiresAt - TOKEN_EXPIRY_SKEW_MS
            } == true
        ) {
            accessToken = null
            SimklAuthStorage.saveAccessToken(null)
            storedState = SimklStoredAuthState()
            persistMetadata()
        }
        val hasWrongPlatformAuthorization = if (isDesktop) {
            !storedState.pendingAuthorizationState.isNullOrBlank()
        } else {
            storedState.hasPendingPinAuthorization
        }
        val hasExpiredAuthorization = when {
            storedState.hasPendingPinAuthorization -> isSimklPinAuthorizationExpired(
                expiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            !storedState.pendingAuthorizationState.isNullOrBlank() -> isSimklAuthorizationExpired(
                startedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
                nowEpochMs = SimklPlatformClock.nowEpochMs(),
            )
            else -> false
        }
        if (hasWrongPlatformAuthorization || hasExpiredAuthorization) {
            clearPendingAuthorization()
            persistMetadata()
        }
        publish(error = null)
        startPinPollingIfNeeded()
    }

    private fun invalidateCredentials(error: SimklAuthError) {
        pinPollingJob?.cancel()
        profileGeneration += 1L
        accessToken = null
        SimklAuthStorage.saveAccessToken(null)
        clearPendingAuthorization()
        storedState = SimklStoredAuthState()
        persistMetadata()
        SimklSyncRepository.clearLocalState()
        publish(isLoading = false, error = error)
    }

    private fun clearPendingAuthorization() {
        SimklAuthStorage.saveCodeVerifier(null)
        storedState = storedState.copy(
            pendingAuthorizationState = null,
            pendingAuthorizationStartedAtEpochMs = null,
            pendingPinUserCode = null,
            pendingPinVerificationUrl = null,
            pendingPinIntervalSeconds = null,
            pendingPinExpiresAtEpochMs = null,
        )
    }

    private fun persistMetadata() {
        SimklAuthStorage.saveMetadataPayload(json.encodeToString(storedState))
    }

    private fun publish(
        isLoading: Boolean = _uiState.value.isLoading,
        error: SimklAuthError? = _uiState.value.error,
    ) {
        val authenticated = !accessToken.isNullOrBlank()
        _isAuthenticated.value = authenticated
        _uiState.value = SimklAuthUiState(
            mode = when {
                authenticated -> SimklConnectionMode.CONNECTED
                storedState.hasPendingAuthorization -> SimklConnectionMode.AWAITING_APPROVAL
                else -> SimklConnectionMode.DISCONNECTED
            },
            credentialsConfigured = hasRequiredCredentials(),
            isLoading = isLoading,
            username = storedState.username,
            accountId = storedState.accountId,
            tokenExpiresAtEpochMs = storedState.tokenExpiresAtEpochMs,
            pendingAuthorizationStartedAtEpochMs = storedState.pendingAuthorizationStartedAtEpochMs,
            usesPinFlow = isDesktop,
            pendingPinUserCode = storedState.pendingPinUserCode,
            pendingPinVerificationUrl = storedState.pendingPinVerificationUrl,
            pendingPinExpiresAtEpochMs = storedState.pendingPinExpiresAtEpochMs,
            error = error,
        )
    }

    private fun authorizationUrl(material: SimklPkceMaterial): String =
        buildSimklAuthorizationUrl(
            clientId = SimklConfig.CLIENT_ID,
            redirectUri = SimklConfig.REDIRECT_URI,
            appName = SimklConfig.APP_NAME,
            appVersion = simklAppVersion,
            material = material,
        )

    private const val TOKEN_EXPIRY_SKEW_MS = 60_000L
}

@Serializable
private data class SimklTokenRequest(
    val code: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("grant_type") val grantType: String = "authorization_code",
)

@Serializable
private data class SimklTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)

@Serializable
private data class SimklUserSettingsResponse(
    val user: SimklUser? = null,
    val account: SimklAccount? = null,
)

@Serializable
private data class SimklUser(
    val name: String? = null,
)

@Serializable
private data class SimklAccount(
    val id: Long? = null,
)
