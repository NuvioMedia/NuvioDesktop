package com.nuvio.app.core.ui

object DesktopBackHandlers {
    private val backStack = ArrayList<() -> Unit>()

    fun pushBack(callback: () -> Unit) {
        backStack.add(callback)
    }

    fun removeBack(callback: () -> Unit) {
        val index = backStack.indexOfLast { it === callback }
        if (index >= 0) {
            backStack.removeAt(index)
        }
    }

    fun handleBack() {
        backStack.lastOrNull()?.invoke()
    }
}
