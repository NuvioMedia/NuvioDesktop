package com.nuvio.app.core.auth

import com.nuvio.app.core.storage.DesktopStorage
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSessionManagerTest {

    private lateinit var tempDir: Path
    private lateinit var authStore: DesktopStorage.Store
    private lateinit var sessionManager: DesktopSessionManager

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("nuvio_auth_test")
        authStore = DesktopStorage.Store(tempDir.resolve("test_auth.properties"))
        sessionManager = DesktopSessionManager(authStore)
    }

    @AfterTest
    fun cleanup() {
        if (::tempDir.isInitialized) {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun save_load_and_delete_session() = runBlocking {
        val testSession = UserSession(
            accessToken = "test_access_token",
            refreshToken = "test_refresh_token",
            expiresIn = 3600L,
            tokenType = "bearer",
            user = UserInfo(
                id = "test-user-id",
                aud = "authenticated",
                email = "test@example.com",
            ),
        )

        assertNull(sessionManager.loadSession())

        sessionManager.saveSession(testSession)

        val loaded = sessionManager.loadSession()
        assertEquals(testSession.accessToken, loaded?.accessToken)
        assertEquals(testSession.refreshToken, loaded?.refreshToken)
        assertEquals(testSession.expiresIn, loaded?.expiresIn)
        assertEquals(testSession.tokenType, loaded?.tokenType)
        assertEquals(testSession.user?.id, loaded?.user?.id)
        assertEquals(testSession.user?.email, loaded?.user?.email)

        sessionManager.deleteSession()
        assertNull(sessionManager.loadSession())
    }

    @Test
    fun load_corrupted_session_returns_null_and_cleans_up() = runBlocking {
        authStore.putString("supabase_session", "invalid json")

        assertNull(sessionManager.loadSession())
        assertNull(authStore.getString("supabase_session"))
    }
}
