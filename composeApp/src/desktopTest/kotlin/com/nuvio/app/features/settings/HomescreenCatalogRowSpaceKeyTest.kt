package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import org.junit.Rule
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Reproduces the desktop bug where typing a space into the "Display name" field of an
 * expanded Home Layout catalog row collapses the row (and the field disappears).
 */
@OptIn(ExperimentalTestApi::class)
class HomescreenCatalogRowSpaceKeyTest {
    @get:Rule
    val compose = createComposeRule()

    private var expanded by mutableStateOf(true)
    private var item by mutableStateOf(
        HomeCatalogSettingsItem(
            key = "addon.movie.popular",
            defaultTitle = "Popular Movies",
            addonName = "Test Addon",
        ),
    )
    private val expandedChanges = mutableListOf<Boolean>()
    private val titleChanges = mutableListOf<String>()

    @Test
    fun spaceKeyInDisplayNameFieldDoesNotCollapseRow() {
        setContent()
        focusDisplayNameField()

        displayNameField().performKeyInput { pressKey(Key.Spacebar) }

        compose.runOnIdle {
            assertTrue(
                expandedChanges.isEmpty(),
                "Space typed into the Display name field must not toggle the row; " +
                    "onExpandedChange calls=$expandedChanges, expanded=$expanded, titleChanges=$titleChanges",
            )
            assertTrue(expanded, "Row collapsed after Space in the Display name field")
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun letterKeyInDisplayNameFieldDoesNotCollapseRow() {
        setContent()
        focusDisplayNameField()

        displayNameField().performKeyInput { pressKey(Key.A) }

        compose.runOnIdle {
            assertTrue(
                expandedChanges.isEmpty(),
                "Letter typed into the Display name field must not toggle the row; " +
                    "onExpandedChange calls=$expandedChanges, expanded=$expanded, titleChanges=$titleChanges",
            )
            assertTrue(expanded, "Row collapsed after a letter key in the Display name field")
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun awtSpaceKeystrokeInsertsSpaceWithoutCollapsingRow() {
        setContent()
        focusDisplayNameField()

        sendAwtKeystroke(Key.Spacebar, AwtKeyEvent.VK_SPACE, ' ')

        compose.runOnIdle {
            assertEquals(listOf(" "), titleChanges, "KEY_TYPED Space must still insert a space")
            assertTrue(
                expandedChanges.isEmpty(),
                "Space keystroke must not toggle the row; " +
                    "onExpandedChange calls=$expandedChanges, expanded=$expanded, titleChanges=$titleChanges",
            )
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun spaceInsertedThroughTextInputDoesNotCollapseRow() {
        setContent()
        focusDisplayNameField()

        displayNameField().performTextInput(" ")

        compose.runOnIdle {
            assertTrue(
                expandedChanges.isEmpty(),
                "Space committed as text must not toggle the row; " +
                    "onExpandedChange calls=$expandedChanges, expanded=$expanded, titleChanges=$titleChanges",
            )
            assertTrue(expanded)
            assertEquals(listOf(" "), titleChanges)
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun clickingRowHeaderStillTogglesExpansion() {
        setContent()

        header().performMouseInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(false), expandedChanges)
            assertEquals(false, expanded)
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)

        header().performMouseInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(false, true), expandedChanges)
            assertEquals(true, expanded)
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun spaceOnFocusedRowStillTogglesExpansion() {
        setContent()
        // The row's clickable Column, as one merged node carrying its descendants' texts.
        val row = compose.onNode(hasClickAction() and hasText("Test Addon"))
        row.requestFocus()
        row.assertIsFocused()

        row.performKeyInput { pressKey(Key.Spacebar) }

        compose.runOnIdle {
            assertEquals(
                listOf(false),
                expandedChanges,
                "Space on the focused row must toggle it once; expanded=$expanded, titleChanges=$titleChanges",
            )
            assertEquals(false, expanded)
        }
        compose.onAllNodes(hasSetTextAction(), useUnmergedTree = true).assertCountEquals(0)
    }

    /**
     * Guards the ordering the fix relies on: a key handler in OutlinedTextField's `modifier` runs
     * after the field's own key handling, so it only sees events the field left unconsumed.
     */
    @Test
    fun textFieldModifierKeyHandlerOnlySeesEventsTheFieldLeftUnconsumed() {
        val seenTypes = mutableListOf<KeyEventType>()
        var text by mutableStateOf("")
        compose.setContent {
            NuvioTheme {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().onKeyEvent { event ->
                        seenTypes += event.type
                        false
                    },
                    singleLine = true,
                )
            }
        }
        displayNameField().performMouseInput { click() }
        displayNameField().assertIsFocused()

        sendAwtKeystroke(Key.X, AwtKeyEvent.VK_X, 'x')

        compose.runOnIdle {
            assertEquals("x", text)
            assertFalse(KeyEventType.Unknown in seenTypes, "Outer handler saw the typed event: $seenTypes")
            assertTrue(KeyEventType.KeyUp in seenTypes, "Outer handler saw no bubbling events: $seenTypes")
        }
    }

    /**
     * Sends the KEY_PRESSED / KEY_TYPED / KEY_RELEASED sequence AWT delivers for one keystroke,
     * shaped like the desktop window's own conversion (KEY_TYPED arrives as type Unknown carrying
     * the AWT event, which is what the text field inserts characters from).
     */
    @OptIn(InternalComposeUiApi::class)
    private fun sendAwtKeystroke(key: Key, keyCode: Int, keyChar: Char) {
        val source = JPanel()
        val time = System.currentTimeMillis()
        fun send(composeKey: Key, type: KeyEventType, awtEvent: AwtKeyEvent) {
            displayNameField().performKeyPress(
                KeyEvent(key = composeKey, type = type, codePoint = keyChar.code, nativeEvent = awtEvent),
            )
        }
        send(
            key,
            KeyEventType.KeyDown,
            AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, time, 0, keyCode, keyChar, AwtKeyEvent.KEY_LOCATION_STANDARD),
        )
        send(
            Key.Unknown,
            KeyEventType.Unknown,
            AwtKeyEvent(
                source,
                AwtKeyEvent.KEY_TYPED,
                time,
                0,
                AwtKeyEvent.VK_UNDEFINED,
                keyChar,
                AwtKeyEvent.KEY_LOCATION_UNKNOWN,
            ),
        )
        send(
            key,
            KeyEventType.KeyUp,
            AwtKeyEvent(source, AwtKeyEvent.KEY_RELEASED, time, 0, keyCode, keyChar, AwtKeyEvent.KEY_LOCATION_STANDARD),
        )
    }

    private fun displayNameField(): SemanticsNodeInteraction =
        compose.onNode(hasSetTextAction(), useUnmergedTree = true)

    private fun header(): SemanticsNodeInteraction =
        compose.onNode(hasText("Test Addon"), useUnmergedTree = true)

    private fun focusDisplayNameField() {
        displayNameField().performMouseInput { click() }
        displayNameField().assertIsFocused()
        compose.runOnIdle {
            assertTrue(
                expandedChanges.isEmpty(),
                "Clicking the Display name field must not toggle the row; onExpandedChange calls=$expandedChanges",
            )
        }
    }

    private fun setContent() {
        compose.setContent {
            NuvioTheme {
                val lazyListState = rememberLazyListState()
                val reorderableState = rememberReorderableLazyListState(lazyListState) { _, _ -> }
                LazyColumn(modifier = Modifier.fillMaxWidth(), state = lazyListState) {
                    items(listOf(item), key = { it.key }) { current ->
                        ReorderableItem(reorderableState, key = current.key) {
                            HomescreenCatalogRow(
                                item = current,
                                isTablet = false,
                                expanded = expanded,
                                onExpandedChange = {
                                    expandedChanges += it
                                    expanded = it
                                },
                                onTitleChange = {
                                    titleChanges += it
                                    item = item.copy(customTitle = it)
                                },
                                onEnabledChange = {},
                                dragHandleScope = this@ReorderableItem,
                            )
                        }
                    }
                }
            }
        }
    }
}
