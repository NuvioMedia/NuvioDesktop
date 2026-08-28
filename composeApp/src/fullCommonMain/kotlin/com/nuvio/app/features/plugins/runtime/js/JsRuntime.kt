package com.nuvio.app.features.plugins.runtime.js

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

// Dedicated, isolated thread pool for the QuickJS plugin runtime. QuickJS calls
// native fetch *synchronously* (via runBlocking in FetchBridge), which parks the
// engine thread for the whole request. Running that on Dispatchers.Default lets a
// fan-out of scrapers occupy every shared scheduler thread, starving
// Dispatchers.Default — and on desktop that deadlocks the UI, because Compose's
// stringResource() does a blocking resource load on the EDT (also on Default).
// Keeping the runtime off the shared dispatchers makes that impossible.
// Process-lifetime singleton; never closed.
@OptIn(DelicateCoroutinesApi::class)
private val pluginJsDispatcher: CoroutineDispatcher = newFixedThreadPoolContext(24, "nuvio-plugin")

internal class JsRuntime(
    private val dispatcher: CoroutineDispatcher = pluginJsDispatcher
) {
    suspend fun <T> use(block: suspend QuickJs.() -> T): T {
        return quickJs(dispatcher) {
            block()
        }
    }
}
