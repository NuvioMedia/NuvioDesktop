package com.nuvio.app

internal class AppTabNavigationHistory {
    private val previousTabs = mutableListOf<AppScreenTab>()

    fun recordTransition(from: AppScreenTab, to: AppScreenTab) {
        if (from != to) previousTabs += from
    }

    fun popPrevious(current: AppScreenTab): AppScreenTab? {
        while (previousTabs.isNotEmpty()) {
            val previous = previousTabs.removeAt(previousTabs.lastIndex)
            if (previous != current) return previous
        }
        return null
    }

    fun clear() {
        previousTabs.clear()
    }
}
