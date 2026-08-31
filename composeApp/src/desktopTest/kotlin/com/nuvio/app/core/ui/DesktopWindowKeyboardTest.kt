package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopWindowKeyboardTest {
    @Test
    fun `escape dispatches normal back after native fullscreen handling declines it`() {
        assertEquals(
            DesktopWindowKeyAction.NavigateBack,
            resolveDesktopWindowKeyAction(
                key = DesktopWindowNavigationKey.Escape,
                isKeyDown = true,
                hasModifier = false,
                canNavigateBack = true,
            ),
        )
    }

    @Test
    fun `backspace dispatches history back`() {
        assertEquals(
            DesktopWindowKeyAction.NavigateHistoryBack,
            resolveDesktopWindowKeyAction(
                key = DesktopWindowNavigationKey.Backspace,
                isKeyDown = true,
                hasModifier = false,
                canNavigateBack = true,
            ),
        )
    }

    @Test
    fun `keys are ignored without a back target`() {
        listOf(
            DesktopWindowNavigationKey.Escape,
            DesktopWindowNavigationKey.Backspace,
        ).forEach { key ->
            assertEquals(
                DesktopWindowKeyAction.None,
                resolveDesktopWindowKeyAction(
                    key = key,
                    isKeyDown = true,
                    hasModifier = false,
                    canNavigateBack = false,
                ),
            )
        }
    }

    @Test
    fun `modified keys releases and unrelated keys are ignored`() {
        val ignoredInputs = listOf(
            Triple(DesktopWindowNavigationKey.Backspace, true, true),
            Triple(DesktopWindowNavigationKey.Escape, false, false),
            Triple(DesktopWindowNavigationKey.Other, true, false),
        )
        ignoredInputs.forEach { (key, isKeyDown, hasModifier) ->
            assertEquals(
                DesktopWindowKeyAction.None,
                resolveDesktopWindowKeyAction(
                    key = key,
                    isKeyDown = isKeyDown,
                    hasModifier = hasModifier,
                    canNavigateBack = true,
                ),
            )
        }
    }

    @Test
    fun `dispatcher distinguishes normal and history callbacks`() {
        val dispatched = mutableListOf<String>()
        val unregister = DesktopBackHandlerDispatcher.register(
            isEnabled = { true },
            onBack = { dispatched += "back" },
            onHistoryBack = { dispatched += "history" },
        )

        try {
            assertTrue(DesktopBackHandlerDispatcher.dispatchBack())
            assertTrue(DesktopBackHandlerDispatcher.dispatchBack(useHistory = true))
            assertEquals(listOf("back", "history"), dispatched)
        } finally {
            unregister()
        }
    }
}
