package com.nuvio.app.features.home

import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.catalog.CatalogTarget
import com.nuvio.app.features.catalog.CatalogPage
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.catalog.mergeCatalogItems
import com.nuvio.app.core.poster.CustomPosterUrlRepository
import com.nuvio.app.core.poster.reapplyCustomPosterUrls
import com.nuvio.app.core.poster.withCustomPosterUrls
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionRepository
import com.nuvio.app.features.collection.CollectionSource
import com.nuvio.app.features.collection.TmdbCollectionSourceResolver
import com.nuvio.app.features.collection.catalogRouteKey
import com.nuvio.app.features.collection.findCollectionCatalog
import com.nuvio.app.features.servers.ServerCatalog
import com.nuvio.app.features.trakt.TraktPublicListSourceResolver
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.isDesktop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.random.Random

object HomeRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null
    private var loadedRequestKey: String? = null
    private var currentRequestKey: String? = null
    private var currentDefinitions: List<HomeCatalogDefinition> = emptyList()
    private var cachedSections: Map<String, HomeCatalogSection> = emptyMap()
    private var cachedCollectionHeroItems: List<MetaPreview> = emptyList()
    private var collectionHeroJob: Job? = null
    private var collectionHeroRequestKey: String? = null
    private var lastPublishedCatalogHeroEmpty: Boolean = true
    private var lastErrorMessage: String? = null

    fun refresh(addons: List<ManagedAddon>, force: Boolean = false) {
        val activeAddons = addons.enabledAddons()
        val requests = buildHomeCatalogDefinitions(activeAddons)
        currentDefinitions = requests
        val requestCacheKeys = requests.mapTo(mutableSetOf(), HomeCatalogDefinition::cacheKey)
        cachedSections = cachedSections.filterKeys(requestCacheKeys::contains)
        // Sorted: the server's addon list can come back in another order, which does not change
        // what the home has to load (rows are ordered by the home settings when published).
        val requestKey = requests.map(HomeCatalogDefinition::cacheKey).sorted().joinToString(separator = "|")
        currentRequestKey = requestKey

        if (!force && activeRequestKey == requestKey && _uiState.value.isLoading) return
        if (!force && loadedRequestKey == requestKey && !_uiState.value.isLoading) {
            // Same catalogs as the rows already loaded, for example after an addon was renamed:
            // show the new names without fetching every catalog again.
            publishCurrentState(isLoading = false, requestKey = requestKey)
            return
        }
        loadedRequestKey = null
        activeRequestKey = requestKey

        if (requests.isEmpty()) {
            activeJob?.cancel()
            activeJob = null
            activeRequestKey = null
            cachedSections = emptyMap()
            lastErrorMessage = null
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                forceRefresh = force,
                refreshSources = true,
                requestKey = requestKey,
            )
            return
        }

        activeJob?.cancel()
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        activeJob = scope.launch {
            val settingsSnapshot = HomeCatalogSettingsRepository.snapshot()
            val prioritizedRequests = prioritizeDefinitions(
                definitions = requests,
                snapshot = settingsSnapshot,
            )
            val loadedSections = linkedMapOf<String, HomeCatalogSection>().apply {
                putAll(cachedSections)
            }
            val heroSourceKeys = prioritizedRequests
                .filter { definition ->
                    settingsSnapshot.heroEnabled &&
                        settingsSnapshot.preferences[definition.key]?.heroSourceEnabled != false
                }
                .mapTo(mutableSetOf(), HomeCatalogDefinition::cacheKey)
            val attemptedKeys = mutableSetOf<String>()
            val desktopContinuations = mutableMapOf<String, DesktopRowContinuation>()
            var heroSourcesPublished = false
            var firstErrorMessage: String? = null
            var batchIndex = 0

            prioritizedRequests.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
                if (activeRequestKey != requestKey) return@launch
                val results = batch.map { request ->
                    async {
                        request to runCatching {
                            request.toRow(forceRefresh = force)
                        }
                    }
                }.awaitAll()

                if (activeRequestKey != requestKey) return@launch

                results.mapNotNull { (request, result) ->
                    result.getOrNull()?.let { row -> request.cacheKey to row }
                }.forEach { (cacheKey, row) ->
                    val earlier = loadedSections[cacheKey]
                    if (!force && earlier != null && earlier.continuesWith(row.section)) {
                        desktopContinuations.remove(cacheKey)
                        return@forEach
                    }
                    loadedSections[cacheKey] = row.section
                    val continuation = row.continuation
                    if (continuation != null) {
                        desktopContinuations[cacheKey] = continuation
                    } else {
                        desktopContinuations.remove(cacheKey)
                    }
                }
                if (firstErrorMessage == null) {
                    firstErrorMessage = results.firstNotNullOfOrNull { (_, result) ->
                        result.exceptionOrNull()?.message
                    }
                }
                batch.mapTo(attemptedKeys, HomeCatalogDefinition::cacheKey)
                val heroSourcesResolved = attemptedKeys.containsAll(heroSourceKeys)
                cachedSections = loadedSections.toMap()
                lastErrorMessage = firstErrorMessage
                if (
                    batchIndex == 0 ||
                    (batchIndex + 1) % HOME_CATALOG_PUBLISH_INTERVAL == 0 ||
                    (heroSourcesResolved && !heroSourcesPublished)
                ) {
                    publishCurrentState(
                        isLoading = true,
                        requestKey = requestKey,
                        heroSourcesResolved = heroSourcesResolved,
                    )
                    heroSourcesPublished = heroSourcesResolved
                }
                batchIndex++
            }

            if (activeRequestKey != requestKey) return@launch

            cachedSections = loadedSections.toMap()
            lastErrorMessage = firstErrorMessage
            activeRequestKey = null
            loadedRequestKey = requestKey
            publishCurrentState(
                isLoading = false,
                requestKey = requestKey,
            )
            ensureCollectionHeroFallback(
                addons = activeAddons,
                forceRefresh = force,
                refreshSources = true,
                requestKey = requestKey,
            )
            if (desktopContinuations.isNotEmpty()) {
                completeDesktopRows(
                    definitions = prioritizedRequests,
                    continuations = desktopContinuations,
                    requestKey = requestKey,
                    forceRefresh = force,
                )
            }
        }
    }

    fun applyCurrentSettings() {
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = currentRequestKey,
        )
        ensureCollectionHeroFallback(
            addons = AddonRepository.uiState.value.addons.enabledAddons(),
            forceRefresh = false,
            refreshSources = false,
            requestKey = currentRequestKey,
        )
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        loadedRequestKey = null
        currentRequestKey = null
        currentDefinitions = emptyList()
        cachedSections = emptyMap()
        cachedCollectionHeroItems = emptyList()
        collectionHeroJob?.cancel()
        collectionHeroJob = null
        collectionHeroRequestKey = null
        lastPublishedCatalogHeroEmpty = true
        lastErrorMessage = null
        _uiState.value = HomeUiState()
    }

    private fun publishCurrentState(
        isLoading: Boolean,
        requestKey: String?,
        heroSourcesResolved: Boolean = !isLoading,
        keepHeroItems: Boolean = false,
    ) {
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        val preferences = snapshot.preferences
        val todayIsoDate = if (snapshot.hideUnreleasedContent) CurrentDateProvider.todayIsoDate() else null
        CustomPosterUrlRepository.ensureLoaded()
        val posterPattern = CustomPosterUrlRepository.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.HOME)
        fun HomeCatalogSection.withReleaseFilter(): HomeCatalogSection =
            if (todayIsoDate == null) this else filterReleasedItems(todayIsoDate)
        fun HomeCatalogSection.withPosterOverlay(): HomeCatalogSection =
            copy(items = items.reapplyCustomPosterUrls(posterPattern))

        val sections = currentDefinitions
            .sortedBy { definition -> preferences[definition.key]?.order ?: Int.MAX_VALUE }
            .mapNotNull { definition ->
                val preference = preferences[definition.key]
                if (preference?.enabled == false) return@mapNotNull null

                val section = cachedSections[definition.cacheKey]
                    ?.withPosterOverlay()
                    ?.withReleaseFilter()
                    ?: return@mapNotNull null
                if (section.items.isEmpty()) return@mapNotNull null
                val customTitle = preference?.customTitle.orEmpty()
                section.copy(
                    title = customTitle.ifBlank { definition.titleFor(snapshot.showCatalogType) },
                    // The addon name is not part of the cache key, so a rename shows up here.
                    subtitle = definition.addonName,
                    addonName = definition.addonName,
                )
            }

        if (snapshot.heroEnabled && !heroSourcesResolved) {
            _uiState.value = HomeUiState(
                isLoading = isLoading,
                heroItems = _uiState.value.heroItems,
                sections = sections,
                errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
            )
            return
        }

        val catalogHeroItems = if (snapshot.heroEnabled) {
            val heroRandom = Random((requestKey?.hashCode() ?: 0).absoluteValue + 1)
            currentDefinitions
                .filter { definition -> preferences[definition.key]?.heroSourceEnabled != false }
                .mapNotNull { definition -> cachedSections[definition.cacheKey] }
                .map { section -> section.withPosterOverlay().withReleaseFilter() }
                .flatMap { section -> section.items }
                .distinctBy { item -> "${item.type}:${item.id}" }
                .shuffled(heroRandom)
                .take(HOME_HERO_ITEM_LIMIT)
        } else {
            emptyList()
        }
        lastPublishedCatalogHeroEmpty = snapshot.heroEnabled && catalogHeroItems.isEmpty()
        val currentHeroItems = _uiState.value.heroItems
        val heroItems = when {
            !snapshot.heroEnabled -> emptyList()
            keepHeroItems && currentHeroItems.isNotEmpty() -> currentHeroItems
            else -> catalogHeroItems.ifEmpty { cachedCollectionHeroItems }
        }

        val nextState = HomeUiState(
            isLoading = isLoading,
            heroItems = heroItems,
            sections = sections,
            errorMessage = if (sections.isEmpty()) lastErrorMessage else null,
        )
        if (_uiState.value != nextState) _uiState.value = nextState
    }

    private suspend fun HomeCatalogDefinition.toRow(forceRefresh: Boolean): LoadedHomeRow {
        serverConnectionId?.let { connectionId -> return LoadedHomeRow(toServerSection(connectionId)) }
        CustomPosterUrlRepository.ensureLoaded()
        val pattern = CustomPosterUrlRepository.patternForScreen(com.nuvio.app.core.poster.CustomPosterScreen.HOME)
        val page = fetchCatalogPage(
            manifestUrl = manifestUrl,
            type = type,
            catalogId = catalogId,
            maxItems = if (isDesktop) DESKTOP_HOME_ROW_ITEM_LIMIT else HOME_CATALOG_PREVIEW_FETCH_LIMIT,
            forceRefresh = forceRefresh,
        )
        val items = if (pattern.isNotBlank()) page.items.withCustomPosterUrls(pattern) else page.items
        if (items.isEmpty()) {
            return LoadedHomeRow(HomeCatalogSection(
                key = key,
                title = defaultTitle,
                subtitle = addonName,
                addonName = addonName,
                target = CatalogTarget.Addon(
                    manifestUrl = manifestUrl,
                    contentType = type,
                    catalogId = catalogId,
                    supportsPagination = supportsPagination,
                ),
                items = emptyList(),
                availableItemCount = 0,
                hasMore = false,
            ))
        }

        val section = HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            target = CatalogTarget.Addon(
                manifestUrl = manifestUrl,
                contentType = type,
                catalogId = catalogId,
                supportsPagination = supportsPagination,
            ),
            items = items,
            availableItemCount = page.rawItemCount,
            hasMore = supportsPagination && page.nextSkip != null,
        )
        return LoadedHomeRow(
            section = section,
            continuation = if (isDesktop) {
                desktopRowContinuation(supportsPagination, page, items.size)
            } else {
                null
            },
        )
    }

    /** Fetches the pages of a desktop row after the ones already loaded, up to the row limit. */
    private suspend fun HomeCatalogDefinition.fetchDesktopRowRest(
        continuation: DesktopRowContinuation,
        loadedItemCount: Int,
        forceRefresh: Boolean,
    ): CatalogPage {
        var items = emptyList<MetaPreview>()
        var rawItemCount = 0
        var nextSkip: Int? = continuation.nextSkip
        var pagesFetched = continuation.pagesFetched
        while (
            nextSkip != null &&
            loadedItemCount + items.size < DESKTOP_HOME_ROW_ITEM_LIMIT &&
            pagesFetched < DESKTOP_HOME_ROW_MAX_PAGES
        ) {
            val page = fetchCatalogPage(
                manifestUrl = manifestUrl,
                type = type,
                catalogId = catalogId,
                skip = nextSkip,
                maxItems = DESKTOP_HOME_ROW_ITEM_LIMIT - loadedItemCount - items.size,
                forceRefresh = forceRefresh,
            )
            items = mergeCatalogItems(items, page.items)
            rawItemCount += page.rawItemCount
            nextSkip = page.nextSkip
            pagesFetched++
        }
        return CatalogPage(
            items = items,
            rawItemCount = rawItemCount,
            nextSkip = nextSkip,
        )
    }

    /**
     * Completes desktop rows that were shown with their first page only, a batch at a time,
     * publishing after each batch. Stops when a newer home load has started.
     */
    private suspend fun completeDesktopRows(
        definitions: List<HomeCatalogDefinition>,
        continuations: Map<String, DesktopRowContinuation>,
        requestKey: String,
        forceRefresh: Boolean,
    ) = coroutineScope {
        val pending = definitions.mapNotNull { definition ->
            continuations[definition.cacheKey]?.let { continuation -> definition to continuation }
        }
        pending.chunked(HOME_CATALOG_FETCH_BATCH_SIZE).forEach { batch ->
            if (currentRequestKey != requestKey) return@coroutineScope
            val pages = batch.map { (definition, continuation) ->
                async {
                    val loadedItemCount = cachedSections[definition.cacheKey]?.items?.size
                        ?: return@async null
                    runCatching {
                        definition.fetchDesktopRowRest(continuation, loadedItemCount, forceRefresh)
                    }.getOrNull()?.let { page -> Triple(definition.cacheKey, continuation, page) }
                }
            }.awaitAll().filterNotNull()
            if (currentRequestKey != requestKey) return@coroutineScope

            val pattern = CustomPosterUrlRepository.patternForScreen(
                com.nuvio.app.core.poster.CustomPosterScreen.HOME,
            )
            val updated = cachedSections.toMutableMap()
            var changed = false
            pages.forEach { (cacheKey, continuation, page) ->
                val section = updated[cacheKey] ?: return@forEach
                val laterItems = if (pattern.isNotBlank()) page.items.withCustomPosterUrls(pattern) else page.items
                val extended = section.extendedWith(page.copy(items = laterItems), continuation)
                if (extended != section) {
                    updated[cacheKey] = extended
                    changed = true
                }
            }
            if (changed) {
                cachedSections = updated
                // The hero was picked when the home appeared; growing rows must not reshuffle it.
                publishCurrentState(isLoading = false, requestKey = requestKey, keepHeroItems = true)
            }
        }
    }

    private class LoadedHomeRow(
        val section: HomeCatalogSection,
        /** Set for desktop rows shown with their first page only. */
        val continuation: DesktopRowContinuation? = null,
    )

    private suspend fun HomeCatalogDefinition.toServerSection(connectionId: String): HomeCatalogSection {
        val target = CatalogTarget.Server(connectionId, catalogId, type)
        val page = ServerCatalog.page(target, skip = 0, limit = HOME_CATALOG_PREVIEW_FETCH_LIMIT)
        return HomeCatalogSection(
            key = key,
            title = defaultTitle,
            subtitle = addonName,
            addonName = addonName,
            target = target,
            items = page.items,
            availableItemCount = page.rawItemCount,
            hasMore = page.nextSkip != null,
        )
    }

    private fun ensureCollectionHeroFallback(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean,
        refreshSources: Boolean,
        requestKey: String?,
    ) {
        if (!lastPublishedCatalogHeroEmpty) return
        val snapshot = HomeCatalogSettingsRepository.snapshot()
        if (!snapshot.heroEnabled) return
        val collections = enabledCollectionsForHero(snapshot)
        if (collections.isEmpty()) {
            cachedCollectionHeroItems = emptyList()
            collectionHeroRequestKey = null
            return
        }

        val nextRequestKey = collectionHeroRequestKey(
            collections = collections,
            addons = addons,
            snapshot = snapshot,
            requestKey = requestKey,
        )
        if (!refreshSources && collectionHeroRequestKey == nextRequestKey) return

        collectionHeroJob?.cancel()
        collectionHeroRequestKey = nextRequestKey
        cachedCollectionHeroItems = emptyList()
        publishCurrentState(
            isLoading = _uiState.value.isLoading,
            requestKey = requestKey,
        )

        collectionHeroJob = scope.launch {
            val sources = collectionHeroSources(collections)
            val sourceResults = sources.map { source ->
                async {
                    runCatching {
                        source.resolveCollectionHeroItems(
                            addons = addons,
                            forceRefresh = forceRefresh,
                        )
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            val random = Random((nextRequestKey.hashCode()).absoluteValue + 7)
            cachedCollectionHeroItems = roundRobinCollectionHeroItems(sourceResults)
                .distinctBy { item -> item.stableKey() }
                .shuffled(random)
                .take(HOME_HERO_ITEM_LIMIT)
            publishCurrentState(
                isLoading = _uiState.value.isLoading,
                requestKey = requestKey,
            )
        }
    }

    private fun enabledCollectionsForHero(snapshot: HomeCatalogSettingsSnapshot): List<Collection> {
        val preferences = snapshot.preferences
        return CollectionRepository.collections.value
            .filter { collection ->
                collection.folders.isNotEmpty() &&
                    preferences["collection_${collection.id}"]?.enabled != false
            }
            .sortedBy { collection ->
                preferences["collection_${collection.id}"]?.order ?: Int.MAX_VALUE
            }
    }

    private fun collectionHeroSources(collections: List<Collection>): List<CollectionSource> =
        collections
            .flatMap { collection -> collection.folders }
            .flatMap { folder -> folder.resolvedSources }
            .take(HOME_COLLECTION_HERO_SOURCE_LIMIT)

    private suspend fun CollectionSource.resolveCollectionHeroItems(
        addons: List<ManagedAddon>,
        forceRefresh: Boolean,
    ): List<MetaPreview> {
        val page = when {
            isTmdb -> TmdbCollectionSourceResolver.resolve(source = this, page = 1)
            isTrakt -> TraktPublicListSourceResolver.resolve(source = this, page = 1)
            else -> {
                val catalogSource = addonCatalogSource() ?: return emptyList()
                val resolvedCatalog = addons.findCollectionCatalog(catalogSource) ?: return emptyList()
                fetchCatalogPage(
                    manifestUrl = resolvedCatalog.addon.manifestUrl,
                    type = catalogSource.type,
                    catalogId = catalogSource.catalogId,
                    genre = catalogSource.genre,
                    maxItems = HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT,
                    forceRefresh = forceRefresh,
                )
            }
        }
        val items = page.items
        return if (HomeCatalogSettingsRepository.snapshot().hideUnreleasedContent) {
            items.filterReleasedItems(CurrentDateProvider.todayIsoDate())
        } else {
            items
        }
    }

    private fun roundRobinCollectionHeroItems(sourceResults: List<List<MetaPreview>>): List<MetaPreview> {
        val iterators = sourceResults.filter { it.isNotEmpty() }.map { it.iterator() }
        if (iterators.isEmpty()) return emptyList()
        val merged = mutableListOf<MetaPreview>()
        var hasMore = true
        while (hasMore && merged.size < HOME_COLLECTION_HERO_SOURCE_LIMIT * HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT) {
            hasMore = false
            iterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    merged.add(iterator.next())
                    hasMore = true
                }
            }
        }
        return merged
    }

    private fun collectionHeroRequestKey(
        collections: List<Collection>,
        addons: List<ManagedAddon>,
        snapshot: HomeCatalogSettingsSnapshot,
        requestKey: String?,
    ): String = buildString {
        append(requestKey.orEmpty())
        append("|hideUnreleased=")
        append(snapshot.hideUnreleasedContent)
        append("|collections=")
        collections.forEach { collection ->
            val preference = snapshot.preferences["collection_${collection.id}"]
            append(collection.id)
            append(":")
            append(preference?.order ?: Int.MAX_VALUE)
            append(":")
            collection.folders.forEach { folder ->
                append(folder.id)
                append("[")
                folder.resolvedSources.forEach { source ->
                    append(collectionSourceKey(source))
                    append(",")
                }
                append("]")
            }
            append(";")
        }
        append("|addons=")
        addons.forEach { addon ->
            append(addon.manifest?.id.orEmpty())
            append(":")
            append(addon.manifestUrl)
            append(":")
            append(addon.manifest?.catalogs?.size ?: 0)
            append(";")
        }
    }

    private fun collectionSourceKey(source: CollectionSource): String =
        source.catalogRouteKey()
}

private const val HOME_HERO_ITEM_LIMIT = 8
private const val HOME_COLLECTION_HERO_SOURCE_LIMIT = 6
private const val HOME_COLLECTION_HERO_SOURCE_ITEM_LIMIT = 8
private const val HOME_CATALOG_FETCH_BATCH_SIZE = 4
private const val HOME_CATALOG_PREVIEW_FETCH_LIMIT = 18
private const val HOME_CATALOG_PUBLISH_INTERVAL = 2

private fun prioritizeDefinitions(
    definitions: List<HomeCatalogDefinition>,
    snapshot: HomeCatalogSettingsSnapshot,
): List<HomeCatalogDefinition> {
    val orderedDefinitions = definitions.sortedBy { definition ->
        snapshot.preferences[definition.key]?.order ?: Int.MAX_VALUE
    }
    val (priority, remainder) = orderedDefinitions.partition { definition ->
        val preference = snapshot.preferences[definition.key]
        if (preference == null) {
            true
        } else {
            preference.enabled || (snapshot.heroEnabled && preference.heroSourceEnabled)
        }
    }
    return priority + remainder
}

