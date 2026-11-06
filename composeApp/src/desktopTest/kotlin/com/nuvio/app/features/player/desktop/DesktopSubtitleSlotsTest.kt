package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerSubtitleSelection
import com.nuvio.app.features.player.SubtitleSlot
import com.nuvio.app.features.player.SubtitleTrack
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSubtitleSlotsTest {
    @Test
    fun dualModeRejectsBitmapPrimaryWithoutChangingSelection() = runTest {
        val native = FakeNative()
        native.tracks[1] = native.tracks[1].copy(codec = "hdmv_pgs_subtitle")
        val slots = native.slots()
        slots.setEnabled(true)
        assertFalse(slots.select(SubtitleSlot.Primary, PlayerSubtitleSelection.BuiltIn(1)))
        assertEquals("1", native.properties["sid"])
    }

    @Test
    fun enablingDualModeClearsAnAlreadySelectedBitmapPrimary() {
        val native = FakeNative()
        native.tracks[0] = native.tracks[0].copy(codec = "hdmv_pgs_subtitle")
        native.slots().setEnabled(true)
        assertEquals("no", native.properties["sid"])
    }

    @Test
    fun bitmapPrimaryRemainsAvailableWithDualModeOff() = runTest {
        val native = FakeNative()
        native.tracks[1] = native.tracks[1].copy(codec = "hdmv_pgs_subtitle")
        assertTrue(native.slots().select(SubtitleSlot.Primary, PlayerSubtitleSelection.BuiltIn(1)))
        assertEquals("2", native.properties["sid"])
    }

    @Test
    fun bothCuesAreCommittedAsOneStackedBlock() {
        val native = FakeNative()
        native.properties["secondary-sid"] = "2"
        native.slots().setEnabled(true)
        assertTrue(native.overlay().endsWith("Translated\\NPrimary"))
        assertEquals("no", native.properties["sub-visibility"])
        assertEquals("no", native.properties["secondary-sub-visibility"])
    }

    @Test
    fun growingPrimaryUpdatesBothCuesInTheSameCommand() {
        val native = FakeNative()
        native.properties["secondary-sid"] = "2"
        val slots = native.slots()
        slots.setEnabled(true)
        val before = native.overlays.size
        native.properties["sub-text"] = "Primary first line\nPrimary second line"
        slots.updatePosition()
        assertEquals(before + 1, native.overlays.size)
        assertTrue(native.overlay().endsWith("Translated\\NPrimary first line\\NPrimary second line"))
    }

    @Test
    fun offSlotsDoNotLeaveStaleTextInTheStack() {
        val native = FakeNative()
        native.properties["sid"] = "no"
        native.properties["secondary-sid"] = "2"
        native.slots().setEnabled(true)
        assertTrue(native.overlay().endsWith("}Translated"))
        assertFalse(native.overlay().contains("Primary"))
    }

    @Test
    fun subtitleTextCannotInjectAssPositioningTags() {
        val native = FakeNative()
        native.properties["sub-text"] = "Literal {braces} and \\N"
        native.slots().setEnabled(true)
        assertTrue(native.overlay().contains("\\{braces}"))
        assertTrue(native.overlay().contains("\\\u2060N"))
    }

    @Test
    fun disablingDualModeRestoresNativeSubtitleVisibilityAndOsdStyle() {
        val native = FakeNative()
        native.properties += mapOf("sub-visibility" to "yes", "secondary-sub-visibility" to "no", "osd-font" to "Original", "sub-font" to "Arial")
        val slots = native.slots()
        slots.setEnabled(true)
        assertEquals("Arial", native.properties["osd-font"])
        slots.setEnabled(false)
        assertEquals("Original", native.properties["osd-font"])
        assertEquals("yes", native.properties["sub-visibility"])
        assertEquals("no", native.properties["secondary-sub-visibility"])
        assertEquals("none", native.commands.last { it[0] == "osd-overlay" }[2])
    }

    @Test
    fun changingFontRecreatesTheOverlayFontProvider() {
        val native = FakeNative()
        native.properties["sub-font"] = "Arial"
        val slots = native.slots()
        slots.setEnabled(true)
        val before = native.commands.size
        native.properties["sub-font"] = "Imported font"
        native.properties["sub-fonts-dir"] = "/fonts"
        slots.updatePosition()
        assertEquals(listOf("none", "ass-events"), native.commands.drop(before).map { it[2] })
        assertEquals("Imported font", native.properties["osd-font"])
        assertEquals("/fonts", native.properties["osd-fonts-dir"])
    }

    @Test
    fun unchangedCuesAndDisabledDualModeDoNotRedrawTheStack() {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        val draws = native.overlays.size
        slots.updatePosition()
        assertEquals(draws, native.overlays.size)
        slots.setEnabled(false)
        native.properties["sub-text"] = "Different cue"
        slots.updatePosition()
        assertEquals(draws, native.overlays.size)
    }

    @Test
    fun addingSecondaryRestoresPrimaryEvenIfMpvAutoSelectsTheNewTrack() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        assertTrue(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.External("/secondary.srt")))
        assertEquals("1", native.properties["sid"])
        assertEquals("3", native.properties["secondary-sid"])
    }

    @Test
    fun replacingEitherAddonRemovesOnlyThatSlotsTrack() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        assertTrue(slots.select(SubtitleSlot.Primary, PlayerSubtitleSelection.External("/primary.srt")))
        assertTrue(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.External("/secondary.srt")))
        val secondaryId = native.properties["secondary-sid"]
        assertTrue(slots.select(SubtitleSlot.Primary, PlayerSubtitleSelection.External("/replacement.srt")))
        assertEquals(secondaryId, native.properties["secondary-sid"])
        assertTrue(native.tracks.any { it.id == secondaryId })
        assertEquals(listOf("3"), native.removedIds)
    }

    @Test
    fun turningDualOffRemovesSecondaryAndPreservesPrimary() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        slots.select(SubtitleSlot.Primary, PlayerSubtitleSelection.External("/primary.srt"))
        slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.External("/secondary.srt"))
        val primaryId = native.properties["sid"]
        slots.setEnabled(false)
        assertEquals(primaryId, native.properties["sid"])
        assertEquals("no", native.properties["secondary-sid"])
        assertEquals(listOf("4"), native.removedIds)
    }

    @Test
    fun builtInTracksAreNeverRemovedBySlotCleanup() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.BuiltIn(1))
        slots.select(SubtitleSlot.Secondary, null)
        assertTrue(native.removedIds.isEmpty())
        assertEquals(2, native.tracks.size)
        assertEquals("1", native.properties["sid"])
    }

    @Test
    fun sameTrackCannotOccupyBothSlots() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        assertFalse(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.BuiltIn(0)))
        assertEquals("1", native.properties["sid"])
        assertEquals("no", native.properties["secondary-sid"])
    }

    @Test
    fun bitmapTrackCannotBeSelectedAsSecondary() = runTest {
        val native = FakeNative()
        native.tracks[1] = native.tracks[1].copy(codec = "hdmv_pgs_subtitle")
        val slots = native.slots()
        slots.setEnabled(true)
        assertFalse(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.BuiltIn(1)))
        assertEquals("1", native.properties["sid"])
    }

    @Test
    fun sameExternalSourceCannotOccupyBothSlots() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        slots.select(SubtitleSlot.Primary, PlayerSubtitleSelection.External("/primary.srt"))
        assertFalse(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.External("/primary.srt")))
        assertEquals("3", native.properties["sid"])
        assertEquals("no", native.properties["secondary-sid"])
    }

    @Test
    fun stableTrackIdWinsOverAnIndexThatShiftedDuringLoading() = runTest {
        val native = FakeNative()
        val slots = native.slots()
        slots.setEnabled(true)
        assertTrue(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.BuiltIn(99, "2")))
        assertEquals("2", native.properties["secondary-sid"])
        assertEquals("1", native.properties["sid"])
    }

    @Test
    fun selectingBuiltInSecondaryKeepsNativeCaptionsHidden() = runTest {
        val native = FakeNative()
        native.properties["secondary-sub-visibility"] = "no"
        val slots = native.slots()
        slots.setEnabled(true)
        assertTrue(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.BuiltIn(1)))
        assertEquals("2", native.properties["secondary-sid"])
        assertEquals("no", native.properties["secondary-sub-visibility"])
        assertEquals("1", native.properties["sid"])
    }

    @Test
    fun selectingAddonSecondaryKeepsNativeCaptionsHidden() = runTest {
        val native = FakeNative()
        native.properties["secondary-sub-visibility"] = "no"
        val slots = native.slots()
        slots.setEnabled(true)
        assertTrue(slots.select(SubtitleSlot.Secondary, PlayerSubtitleSelection.External("/secondary.srt")))
        assertEquals("3", native.properties["secondary-sid"])
        assertEquals("no", native.properties["secondary-sub-visibility"])
        assertEquals("1", native.properties["sid"])
    }

    private class FakeNative {
        val properties = mutableMapOf("sid" to "1", "secondary-sid" to "no", "sub-text" to "Primary", "secondary-sub-text" to "Translated", "sub-font-size" to "42")
        val tracks = mutableListOf(
            SubtitleTrack(0, "1", "English", "en", codec = "subrip"),
            SubtitleTrack(1, "2", "French", "fr", codec = "subrip"),
        )
        val removedIds = mutableListOf<String>()
        val commands = mutableListOf<List<String>>()
        val overlays get() = commands.filter { it[0] == "osd-overlay" && it[2] == "ass-events" }
        private var nextId = 3

        fun overlay() = overlays.last()[3]

        fun slots() = DesktopSubtitleSlots(Any(), { 1L }, { tracks.toList() },
            readProperty = { _, name -> properties[name].orEmpty() },
            writeProperty = { _, name, value ->
                properties[name] = value
            },
            command = { _, args ->
                commands += args.toList()
                when (args[0]) {
                    "osd-overlay" -> Unit
                    "sub-add" -> {
                        val id = (nextId++).toString()
                        tracks += SubtitleTrack(tracks.size, id, args[1], codec = "subrip")
                        properties["sid"] = id
                    }
                    "sub-remove" -> {
                        removedIds += args[1]
                        tracks.removeAll { it.id == args[1] }
                    }
                    else -> error("Unexpected command ${args[0]}")
                }
            })
    }
}
