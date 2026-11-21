package com.nuvio.app.controller

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamInputControllerTest {

    @Test
    fun parseVirtualGamepadInfoReadsMultipleSlots() {
        val sample = """
            [slot 0]
            name=DualSense Wireless Controller
            VID=0x054c
            PID=0x0ce6
            type=PS5
            handle=12345

            [slot 1]
            name=Nintendo Switch Pro Controller
            VID=1406
            PID=8201
            type=SwitchPro
        """.trimIndent()

        val slots = SteamEnvironment.parseVirtualGamepadInfo(sample)
        assertEquals(2, slots.size)

        assertEquals(0, slots[0].slot)
        assertEquals("DualSense Wireless Controller", slots[0].name)
        assertEquals(0x054C, slots[0].vendor)
        assertEquals(0x0CE6, slots[0].product)
        assertEquals("PS5", slots[0].type)

        assertEquals(1, slots[1].slot)
        assertEquals("Nintendo Switch Pro Controller", slots[1].name)
        assertEquals(1406, slots[1].vendor)
        assertEquals(8201, slots[1].product)
        assertEquals("SwitchPro", slots[1].type)
    }

    @Test
    fun virtualSlotFromNameExtractsSlotIndex() {
        assertEquals(0, SteamEnvironment.virtualSlotFromName("Microsoft X-Box 360 pad 0"))
        assertEquals(2, SteamEnvironment.virtualSlotFromName("Steam Virtual Gamepad pad 2"))
        assertNull(SteamEnvironment.virtualSlotFromName("Xbox Wireless Controller"))
        assertNull(SteamEnvironment.virtualSlotFromName(null))
    }

    @Test
    fun isSteamVirtualGamepadDetectsValveVirtualPadAndName() {
        assertTrue(
            SteamEnvironment.isSteamVirtualGamepad(
                SteamEnvironment.VALVE_VENDOR_ID,
                SteamEnvironment.STEAM_VIRTUAL_GAMEPAD_PRODUCT_ID,
                "Microsoft X-Box 360 pad 0",
            ),
        )
        assertTrue(SteamEnvironment.isSteamVirtualGamepad(0, 0, "Steam Virtual Gamepad"))
        assertFalse(SteamEnvironment.isSteamVirtualGamepad(0x045E, 0x02EA, "Xbox One S Controller"))
    }

    @Test
    fun classifyMapsVendorsTypesAndNamesToFamilies() {
        assertEquals(
            ControllerFamily.SteamDeck,
            SteamEnvironment.classify(
                SteamEnvironment.VALVE_VENDOR_ID,
                SteamEnvironment.STEAM_DECK_PRODUCT_ID,
                -1,
                "Steam Deck",
            ),
        )
        assertEquals(
            ControllerFamily.SteamController,
            SteamEnvironment.classify(SteamEnvironment.VALVE_VENDOR_ID, 0x1102, -1, "Steam Controller"),
        )
        assertEquals(
            ControllerFamily.PlayStation,
            SteamEnvironment.classify(0x054C, 0x0CE6, SteamEnvironment.SDL_TYPE_PS5, "DualSense"),
        )
        assertEquals(
            ControllerFamily.Nintendo,
            SteamEnvironment.classify(0x057E, 0x2009, SteamEnvironment.SDL_TYPE_SWITCH_PRO, "Pro Controller"),
        )
        assertEquals(
            ControllerFamily.Xbox,
            SteamEnvironment.classify(0x045E, 0x0B12, SteamEnvironment.SDL_TYPE_XBOXONE, "Xbox Series X"),
        )
        assertEquals(
            ControllerFamily.PlayStation,
            SteamEnvironment.classify(0, 0, -1, "Virtual Pad", typeName = "PS4"),
        )
        assertEquals(
            ControllerFamily.Generic,
            SteamEnvironment.classify(0x1234, 0x5678, -1, "Generic USB Gamepad"),
        )
    }

    @Test
    fun backPaddlesAliasToCanonicalButtons() {
        assertEquals(XboxButton.LB, ControllerButtonAliases.canonical(XboxButton.L4))
        assertEquals(XboxButton.RB, ControllerButtonAliases.canonical(XboxButton.R4))
        assertEquals(XboxButton.LT, ControllerButtonAliases.canonical(XboxButton.L5))
        assertEquals(XboxButton.RT, ControllerButtonAliases.canonical(XboxButton.R5))
        assertEquals(XboxButton.A, ControllerButtonAliases.canonical(XboxButton.A))
    }

    @Test
    fun glyphPreferenceAndResolveGlyphFamilyRespectOverridesAndDefaults() {
        ControllerManager.defaultControllerFamily = ControllerFamily.SteamDeck
        val psPad = ControllerInfo("DualSense", ControllerFamily.PlayStation, viaSteamInput = true)
        val genericPad = ControllerInfo("USB Pad", ControllerFamily.Generic, viaSteamInput = false)

        assertEquals(
            ControllerFamily.PlayStation,
            ControllerManager.resolveGlyphFamily(ControllerGlyphPreference.Auto, psPad),
        )
        assertEquals(
            ControllerFamily.SteamDeck,
            ControllerManager.resolveGlyphFamily(ControllerGlyphPreference.Auto, genericPad),
        )
        assertEquals(
            ControllerFamily.Nintendo,
            ControllerManager.resolveGlyphFamily(ControllerGlyphPreference.Nintendo, psPad),
        )

        ControllerManager.defaultControllerFamily = ControllerFamily.Xbox
    }

    @Test
    fun controllerSettingsPersistsPreferences() {
        val backing = HashMap<String, Any>()
        val storage = object : ControllerSettings.Storage {
            override fun getString(key: String): String? = backing[key] as? String
            override fun putString(key: String, value: String) {
                backing[key] = value
            }
            override fun getBoolean(key: String): Boolean? = backing[key] as? Boolean
            override fun putBoolean(key: String, value: Boolean) {
                backing[key] = value
            }
        }

        ControllerSettings.install(
            storage = storage,
            environment = ControllerSettings.Environment(
                steamDetected = true,
                steamDeck = true,
                steamKeyboardAvailable = true,
            ),
        )
        assertTrue(ControllerSettings.isSupported)
        assertEquals(ControllerGlyphPreference.Auto, ControllerSettings.glyphPreference.value)
        assertTrue(ControllerSettings.steamKeyboardEnabled.value)
        assertFalse(ControllerSettings.ignoreBackgroundInput.value)

        ControllerSettings.setGlyphPreference(ControllerGlyphPreference.PlayStation)
        ControllerSettings.setSteamKeyboardEnabled(false)
        ControllerSettings.setIgnoreBackgroundInput(true)

        assertEquals(ControllerGlyphPreference.PlayStation, ControllerSettings.glyphPreference.value)
        assertFalse(ControllerSettings.steamKeyboardEnabled.value)
        assertTrue(ControllerSettings.ignoreBackgroundInput.value)
        assertEquals("playstation", backing["glyph_style"])
        assertEquals(false, backing["steam_keyboard"])
        assertEquals(true, backing["ignore_background_input"])
    }
}
