package com.nuvio.app

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.InternalTestApi
import androidx.compose.ui.test.junit4.DesktopComposeTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nuvio.app.core.ui.DialogSurface
import com.nuvio.app.core.ui.Menu
import com.nuvio.app.core.ui.MenuItem
import com.nuvio.app.core.ui.NuvioModalBottomSheet
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.platformTouchScroll
import com.nuvio.app.core.ui.PlatformTouchScrollState
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.clickableIncludingFlingStop
import java.awt.Canvas
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(InternalTestApi::class, InternalComposeUiApi::class, ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class WindowsTouchRootsTest {
    @get:Rule
    val compose = createComposeRule()

    private val background = ScrollState(0)
    private var clicks = 0
    private var backgroundUsesFlingStopClick = false
    private val scene get() = (compose as DesktopComposeTestRule).scene
    private val touchField = MouseEvent::class.java.getDeclaredField("causedByTouchEvent").apply { isAccessible = true }

    // Feed AWT-marked mouse input through the real production modifier and
    // wheel queue. Only the window/rendering host is replaced by the test scene.
    private val host = object : Canvas() {
        override fun getLocationOnScreen(): Point = Point(0, 0)
    }.apply {
        setSize(1024, 768)
        addMouseWheelListener { wheel ->
            val delta = wheel.preciseWheelRotation.toFloat()
            scene.sendPointerEvent(
                eventType = PointerEventType.Scroll,
                position = Offset(wheel.x.toFloat(), wheel.y.toFloat()),
                scrollDelta = if (wheel.isShiftDown) Offset(delta, 0f) else Offset(0f, delta),
                timeMillis = wheel.`when`,
                buttons = PointerButtons(isPrimaryPressed = wheel.modifiersEx and InputEvent.BUTTON1_DOWN_MASK != 0),
                keyboardModifiers = PointerKeyboardModifiers(isShiftPressed = wheel.isShiftDown),
                nativeEvent = wheel,
            )
        }
    }

    @Before
    fun installWindowsInputQueue() {
        assumeTrue(DesktopHostOs.current == DesktopHostOs.WINDOWS)
        compose.runOnUiThread { installWindowsTouchScroll() }
    }

    @Test
    fun dialogScrollDoesNotScrollBackgroundOrClickRows() {
        val dialog = ScrollState(0)
        show {
            DialogSurface(onDismissRequest = {}) { list(dialog, "dialog") }
        }
        drag("dialog")
        compose.runOnIdle {
            assertTrue(dialog.value > 0, "The dialog list must scroll")
            assertEquals(0, background.value)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun popupScrollDoesNotReachBackgroundAtItsEnd() {
        val popup = ScrollState(0)
        show {
            Popup(properties = PopupProperties(focusable = true)) {
                Box(Modifier.platformTouchScroll()) { list(popup, "popup") }
            }
        }
        compose.runOnIdle { popup.dispatchRawDelta(popup.maxValue.toFloat()) }
        drag("popup")
        compose.runOnIdle {
            assertEquals(popup.maxValue, popup.value)
            assertEquals(0, background.value)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun horizontalPopupScrollsWithoutScrollingBackground() {
        val popup = ScrollState(0)
        show {
            Popup(properties = PopupProperties(focusable = true)) {
                Box(Modifier.platformTouchScroll()) {
                    Row(Modifier.size(280.dp, 160.dp).horizontalScroll(popup).testTag("popup")) {
                        repeat(20) { Box(Modifier.size(100.dp).clickable { clicks++ }) }
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("popup").fetchSemanticsNode().boundsInWindow
        stroke(
            Offset(bounds.left + bounds.width * 0.8f, bounds.center.y),
            Offset(bounds.left + bounds.width * 0.2f, bounds.center.y),
        )
        compose.runOnIdle {
            assertTrue(popup.value > 0)
            assertEquals(0, background.value)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun touchTapInDialogActivatesOnce() {
        show {
            DialogSurface(onDismissRequest = {}) { list(ScrollState(0), "dialog") }
        }
        val center = compose.onNodeWithTag("dialog-row-0").fetchSemanticsNode().boundsInWindow.center
        pointer(PointerEventType.Press, center)
        pointer(PointerEventType.Release, center)
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun dropdownMenuScrollsWithoutSelectingAnItem() {
        show {
            Menu(
                expanded = true,
                onDismissRequest = {},
                modifier = Modifier.heightIn(max = 240.dp).testTag("menu"),
            ) {
                repeat(30) { index ->
                    MenuItem("Option $index", { clicks++ }, Modifier.testTag("option-$index"))
                }
            }
        }
        val before = compose.onNodeWithTag("option-0").fetchSemanticsNode().positionInWindow.y
        drag("menu")
        val after = compose.onNodeWithTag("option-0").fetchSemanticsNode().positionInWindow.y
        compose.runOnIdle {
            assertTrue(after < before, "The dropdown must scroll")
            assertEquals(0, background.value)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun nestedPopupAndDialogKeepSeparateGesturesAfterDismissal() {
        val dialog = ScrollState(0)
        val popup = ScrollState(0)
        val popupOpen = mutableStateOf(true)
        val dialogOpen = mutableStateOf(true)
        show {
            if (dialogOpen.value) {
                DialogSurface(onDismissRequest = { dialogOpen.value = false }) {
                    list(dialog, "dialog")
                    if (popupOpen.value) {
                        Popup(properties = PopupProperties(focusable = true)) {
                            Box(Modifier.platformTouchScroll()) { list(popup, "popup") }
                        }
                    }
                }
            }
        }
        drag("popup")
        compose.runOnIdle {
            assertTrue(popup.value > 0)
            assertEquals(0, dialog.value)
            assertEquals(0, background.value)
            popupOpen.value = false
        }
        drag("dialog")
        compose.runOnIdle {
            assertTrue(dialog.value > 0)
            assertEquals(0, background.value)
            dialogOpen.value = false
        }
        drag("background")
        compose.runOnIdle { assertTrue(background.value > 0) }
    }

    @Test
    fun dismissingPopupDuringDragDoesNotActivateOrScrollBackground() {
        backgroundUsesFlingStopClick = true
        val popupOpen = mutableStateOf(true)
        show {
            if (popupOpen.value) {
                Popup(properties = PopupProperties(focusable = true)) {
                    Box(
                        Modifier.platformTouchScroll().onPointerEvent(PointerEventType.Scroll) { event ->
                            event.changes.forEach { it.consume() }
                            popupOpen.value = false
                        },
                    ) {
                        Box(Modifier.size(280.dp, 240.dp).clickable { clicks++ }.testTag("popup"))
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("popup").fetchSemanticsNode().boundsInWindow
        val from = Offset(bounds.center.x, bounds.top + bounds.height * 0.8f)
        val to = Offset(bounds.center.x, bounds.top + bounds.height * 0.25f)
        pointer(PointerEventType.Press, from)
        pointer(PointerEventType.Move, (from + to) / 2f)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(!popupOpen.value)
            assertTrue(PlatformTouchScrollState.isDragging, "Closing a root must retain ownership until release")
        }
        pointer(PointerEventType.Move, to)
        pointer(PointerEventType.Release, to)
        compose.runOnIdle {
            assertEquals(0, background.value)
            assertEquals(0, clicks)
        }
        val center = compose.onNodeWithTag("background").fetchSemanticsNode().boundsInWindow.center
        pointer(PointerEventType.Press, center, touch = false)
        pointer(PointerEventType.Release, center, touch = false)
        compose.runOnIdle { assertEquals(1, clicks, "A new physical mouse press must work after cancellation") }
        pointer(PointerEventType.Press, center)
        pointer(PointerEventType.Release, center)
        compose.runOnIdle { assertEquals(2, clicks, "A new touch tap must work after cancellation") }
        drag("background")
        compose.runOnIdle { assertTrue(background.value > 0) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun bottomSheetContentScrollsWithoutMovingOrDismissingSheet() {
        val content = ScrollState(0)
        lateinit var sheet: SheetState
        var dismissals = 0
        show {
            sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            NuvioModalBottomSheet(onDismissRequest = { dismissals++ }, sheetState = sheet) {
                list(content, "sheet")
            }
        }
        drag("sheet")
        compose.runOnIdle {
            assertTrue(content.value > 0, "The sheet content must scroll")
            assertTrue(sheet.isVisible)
            assertEquals(0, dismissals)
            assertEquals(0, background.value)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun downwardDragScrollsSheetContentInsteadOfDismissingSheet() {
        val content = ScrollState(0)
        lateinit var sheet: SheetState
        var dismissals = 0
        show {
            sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            NuvioModalBottomSheet(onDismissRequest = { dismissals++ }, sheetState = sheet) {
                list(content, "sheet")
            }
        }
        compose.runOnIdle { content.dispatchRawDelta(400f) }
        val bounds = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInWindow
        val offset = compose.runOnIdle { sheet.requireOffset() }
        stroke(
            Offset(bounds.center.x, bounds.top + bounds.height * 0.2f),
            Offset(bounds.center.x, bounds.top + bounds.height * 0.8f),
        )
        compose.runOnIdle {
            assertTrue(content.value < 400)
            assertEquals(offset, sheet.requireOffset(), 1f)
            assertEquals(0, dismissals)
            assertEquals(0, background.value)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun bottomSheetHandleStillMovesSheet() {
        lateinit var sheet: SheetState
        var dismissals = 0
        show {
            sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            NuvioModalBottomSheet(onDismissRequest = { dismissals++ }, sheetState = sheet) {
                list(ScrollState(0), "sheet")
            }
        }
        val bounds = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInWindow
        val handle = Offset(bounds.center.x, bounds.top - 6f)
        val offset = compose.runOnIdle { sheet.requireOffset() }
        pointer(PointerEventType.Press, handle)
        pointer(PointerEventType.Move, handle + Offset(0f, bounds.height * 0.6f))
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(sheet.requireOffset() > offset, "The handle must retain the sheet drag")
            assertEquals(0, background.value)
        }
        pointer(PointerEventType.Release, handle + Offset(0f, bounds.height * 0.6f))
    }

    private fun show(overlay: @Composable () -> Unit) {
        compose.setContent {
            NuvioTheme {
                Box(Modifier.fillMaxSize().platformTouchScroll()) {
                    list(background, "background", Modifier.fillMaxSize())
                    overlay()
                }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun list(state: ScrollState, tag: String, modifier: Modifier = Modifier.size(280.dp, 240.dp)) {
        Column(modifier.verticalScroll(state).testTag(tag)) {
            repeat(30) { index ->
                val clickable = if (tag == "background" && backgroundUsesFlingStopClick) {
                    Modifier.clickableIncludingFlingStop { clicks++ }
                } else {
                    Modifier.clickable { clicks++ }
                }
                Box(Modifier.fillMaxWidth().height(48.dp).then(clickable).testTag("$tag-row-$index")) {
                    Text("Row $index")
                }
            }
        }
    }

    private fun drag(tag: String) {
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        val from = Offset(bounds.center.x, bounds.top + bounds.height * 0.8f)
        val to = Offset(bounds.center.x, bounds.top + bounds.height * 0.25f)
        stroke(from, to)
    }

    private fun stroke(from: Offset, to: Offset) {
        pointer(PointerEventType.Press, from)
        pointer(PointerEventType.Move, (from + to) / 2f)
        pointer(PointerEventType.Move, to)
        pointer(PointerEventType.Release, to)
        compose.waitForIdle()
    }

    private fun pointer(type: PointerEventType, position: Offset, touch: Boolean = true) {
        compose.runOnUiThread {
            val released = type == PointerEventType.Release
            val id = when (type) {
                PointerEventType.Press -> MouseEvent.MOUSE_PRESSED
                PointerEventType.Release -> MouseEvent.MOUSE_RELEASED
                else -> MouseEvent.MOUSE_DRAGGED
            }
            val event = MouseEvent(
                host, id, System.currentTimeMillis(), if (released) 0 else InputEvent.BUTTON1_DOWN_MASK,
                position.x.toInt(), position.y.toInt(), position.x.toInt(), position.y.toInt(),
                1, false, if (type == PointerEventType.Move) MouseEvent.NOBUTTON else MouseEvent.BUTTON1,
            )
            if (type == PointerEventType.Press && touch) touchField.setBoolean(event, true)
            scene.sendPointerEvent(
                eventType = type,
                position = position,
                buttons = PointerButtons(isPrimaryPressed = !released),
                nativeEvent = event,
                button = if (type == PointerEventType.Move) null else PointerButton.Primary,
            )
        }
    }
}
