package com.nuvio.app.features.plugins.runtime

import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal val pluginDispatcher: CoroutineDispatcher = Dispatchers.Default

internal fun QuickJs.configurePluginRuntime() = Unit
