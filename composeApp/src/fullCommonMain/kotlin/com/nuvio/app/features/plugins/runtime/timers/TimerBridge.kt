package com.nuvio.app.features.plugins.runtime.timers

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.nuvio.app.features.plugins.runtime.host.HostModule
import kotlinx.coroutines.delay

// Plugins bundled for the mobile app rely on setTimeout/setInterval (mostly for
// fetch timeouts and retry backoff). QuickJS provides no timers by itself, so
// without this bridge those plugins throw ReferenceError and silently return
// zero streams. The JS-side polyfill lives in JsBindings.timerPolyfill().
private const val MAX_DELAY_MS = 60_000L

internal class TimerBridge : HostModule {
    override fun register(runtime: QuickJs) {
        runtime.asyncFunction("__native_delay") { args ->
            val ms = (args.getOrNull(0) as? Number)?.toLong() ?: 0L
            delay(ms.coerceIn(0L, MAX_DELAY_MS))
            null
        }
    }
}
