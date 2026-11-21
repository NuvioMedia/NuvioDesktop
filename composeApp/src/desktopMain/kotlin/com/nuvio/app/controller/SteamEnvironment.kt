/*
 * Steam / Steam Input runtime detection for the desktop controller backend.
 *
 * Nuvio is not a Steamworks title, so it never talks to ISteamInput directly. When it is launched
 * from Steam (as a non-Steam shortcut, in Big Picture or in the Steam Deck's Game Mode), Steam
 * Input remaps the physical controller onto a virtual Xbox-style gamepad and tells SDL which
 * devices to hide through environment variables. This object reads that environment so the
 * controller backend can cooperate with Steam Input instead of fighting it.
 *
 * Reference: https://partner.steamgames.com/doc/features/steam_controller/steam_input_gamepad_emulation_bestpractices
 */
package com.nuvio.app.controller

import java.io.File

internal object SteamEnvironment {
    const val VALVE_VENDOR_ID = 0x28DE
    const val STEAM_VIRTUAL_GAMEPAD_PRODUCT_ID = 0x11FF
    const val STEAM_DECK_PRODUCT_ID = 0x1205
    private val STEAM_CONTROLLER_PRODUCT_IDS = setOf(0x1102, 0x1142)

    private val env: Map<String, String> = runCatching { System.getenv() }.getOrDefault(emptyMap())

    private fun env(name: String): String? = env[name]?.takeIf { it.isNotBlank() }

    /** Nuvio was started by the Steam client (library shortcut, Big Picture or Game Mode). */
    val launchedBySteam: Boolean by lazy {
        env("SteamGameId") != null ||
            env("SteamAppId") != null ||
            env("SteamClientLaunch") == "1" ||
            env("SteamEnv") == "1"
    }

    /**
     * Steam Input is handling controllers for this process: Steam passes the physical devices it
     * virtualises in SDL_GAMECONTROLLER_IGNORE_DEVICES and its own bindings in
     * SDL_GAMECONTROLLERCONFIG. SDL reads both variables itself.
     */
    val steamInputActive: Boolean by lazy {
        env("SDL_GAMECONTROLLER_IGNORE_DEVICES") != null ||
            env("SDL_GAMECONTROLLER_ALLOW_STEAM_VIRTUAL_GAMEPAD") == "1" ||
            env("SteamVirtualGamepadInfo") != null ||
            launchedBySteam
    }

    /** Running inside gamescope (Steam Deck Game Mode / SteamOS session). */
    val gamescope: Boolean by lazy {
        env("GAMESCOPE_WAYLAND_DISPLAY") != null ||
            env("XDG_CURRENT_DESKTOP").equals("gamescope", ignoreCase = true) ||
            env("SteamGamepadUI") == "1"
    }

    /** Steam Deck (LCD "Jupiter" or OLED "Galileo") hardware. */
    val steamDeck: Boolean by lazy {
        if (env("SteamDeck") == "1") return@lazy true
        val vendor = readSysFile("/sys/devices/virtual/dmi/id/board_vendor")
            ?: readSysFile("/sys/devices/virtual/dmi/id/sys_vendor")
        val product = readSysFile("/sys/devices/virtual/dmi/id/product_name")
            ?: readSysFile("/sys/devices/virtual/dmi/id/board_name")
        vendor.equals("Valve", ignoreCase = true) &&
            (product.equals("Jupiter", ignoreCase = true) || product.equals("Galileo", ignoreCase = true))
    }

    /** The Steam on-screen keyboard can be summoned through the steam:// URL handler. */
    val steamKeyboardAvailable: Boolean
        get() = launchedBySteam || gamescope

    private fun readSysFile(path: String): String? = runCatching {
        File(path).takeIf { it.canRead() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun isSteamVirtualGamepad(vendor: Int, product: Int, name: String?): Boolean =
        (vendor == VALVE_VENDOR_ID && product == STEAM_VIRTUAL_GAMEPAD_PRODUCT_ID) ||
            name?.contains("Steam Virtual Gamepad", ignoreCase = true) == true

    // ------------------------------------------------------------------ virtual gamepad info

    /**
     * One `[slot N]` entry of the file Steam publishes through the `SteamVirtualGamepadInfo`
     * environment variable. It describes the physical controller behind each virtual pad.
     */
    data class VirtualSlot(
        val slot: Int,
        val name: String?,
        val vendor: Int,
        val product: Int,
        val type: String?,
    )

    private var cachedSlots: List<VirtualSlot> = emptyList()
    private var cachedMtime = 0L
    private var lastCheck = 0L

    /** Parsed virtual gamepad info, re-read when Steam updates the file (at most every 3s). */
    fun virtualSlots(now: Long = System.currentTimeMillis()): List<VirtualSlot> {
        val path = env("SteamVirtualGamepadInfo") ?: return emptyList()
        if (now - lastCheck < 3_000L && lastCheck != 0L) return cachedSlots
        lastCheck = now
        val file = File(path)
        val mtime = runCatching { file.lastModified() }.getOrDefault(0L)
        if (mtime == 0L || mtime == cachedMtime) return cachedSlots
        cachedMtime = mtime
        cachedSlots = runCatching { parseVirtualGamepadInfo(file.readText()) }.getOrDefault(emptyList())
        return cachedSlots
    }

    internal fun parseVirtualGamepadInfo(text: String): List<VirtualSlot> {
        val result = ArrayList<VirtualSlot>()
        var slot = -1
        var name: String? = null
        var vendor = 0
        var product = 0
        var type: String? = null
        fun flush() {
            if (slot >= 0) result += VirtualSlot(slot, name, vendor, product, type)
            name = null
            vendor = 0
            product = 0
            type = null
        }
        val slotHeader = Regex("""^\[slot\s+(\d+)]""")
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val header = slotHeader.find(line)
            if (header != null) {
                flush()
                slot = header.groupValues[1].toInt()
                return@forEach
            }
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "name" -> name = value
                "VID" -> vendor = parseNumber(value)
                "PID" -> product = parseNumber(value)
                "type" -> type = value
            }
        }
        flush()
        return result
    }

    private fun parseNumber(value: String): Int {
        val v = value.trim()
        return runCatching {
            if (v.startsWith("0x", ignoreCase = true)) v.substring(2).toInt(16) else v.toInt()
        }.getOrDefault(0)
    }

    /** Virtual pads are named "... pad N" on Linux, where N is the Steam slot. */
    fun virtualSlotFromName(name: String?): Int? {
        if (name == null) return null
        val match = Regex("""pad\s+(\d+)""", RegexOption.IGNORE_CASE).find(name) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    // ------------------------------------------------------------------ classification

    /**
     * Maps vendor/product ids, SDL controller types and names onto a glyph family.
     *
     * @param sdlType SDL_GameControllerType, or -1 when unknown.
     */
    fun classify(vendor: Int, product: Int, sdlType: Int, name: String?, typeName: String? = null): ControllerFamily {
        if (vendor == VALVE_VENDOR_ID) {
            if (product == STEAM_DECK_PRODUCT_ID) return ControllerFamily.SteamDeck
            if (product in STEAM_CONTROLLER_PRODUCT_IDS) return ControllerFamily.SteamController
        }
        when (vendor) {
            0x054C -> return ControllerFamily.PlayStation // Sony
            0x057E -> return ControllerFamily.Nintendo // Nintendo
            0x045E -> if (product != 0) return ControllerFamily.Xbox // Microsoft
        }
        typeName?.lowercase()?.let { t ->
            when {
                t.startsWith("ps") || "playstation" in t || "dualsense" in t -> return ControllerFamily.PlayStation
                "switch" in t || "joycon" in t || "nintendo" in t -> return ControllerFamily.Nintendo
                "deck" in t -> return ControllerFamily.SteamDeck
                "xbox" in t -> return ControllerFamily.Xbox
            }
        }
        when (sdlType) {
            SDL_TYPE_PS3, SDL_TYPE_PS4, SDL_TYPE_PS5 -> return ControllerFamily.PlayStation
            SDL_TYPE_SWITCH_PRO, SDL_TYPE_JOYCON_LEFT, SDL_TYPE_JOYCON_RIGHT, SDL_TYPE_JOYCON_PAIR ->
                return ControllerFamily.Nintendo
        }
        val lower = name?.lowercase().orEmpty()
        return when {
            "steam deck" in lower -> ControllerFamily.SteamDeck
            "steam controller" in lower -> ControllerFamily.SteamController
            "dualsense" in lower || "dualshock" in lower || "playstation" in lower || "ps4" in lower || "ps5" in lower ->
                ControllerFamily.PlayStation
            "nintendo" in lower || "switch" in lower || "joy-con" in lower -> ControllerFamily.Nintendo
            "xbox" in lower || "x-box" in lower || "xinput" in lower -> ControllerFamily.Xbox
            sdlType == SDL_TYPE_XBOX360 || sdlType == SDL_TYPE_XBOXONE -> ControllerFamily.Xbox
            else -> ControllerFamily.Generic
        }
    }

    // SDL_GameControllerType values (SDL 2.0.12+).
    const val SDL_TYPE_XBOX360 = 1
    const val SDL_TYPE_XBOXONE = 2
    const val SDL_TYPE_PS3 = 3
    const val SDL_TYPE_PS4 = 4
    const val SDL_TYPE_SWITCH_PRO = 5
    const val SDL_TYPE_PS5 = 7
    const val SDL_TYPE_JOYCON_LEFT = 11
    const val SDL_TYPE_JOYCON_RIGHT = 12
    const val SDL_TYPE_JOYCON_PAIR = 13
}
