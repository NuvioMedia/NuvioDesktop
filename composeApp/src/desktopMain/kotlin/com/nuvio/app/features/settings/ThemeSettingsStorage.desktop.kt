package com.nuvio.app.features.settings

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

internal fun resolveDesktopAppLocale(
    languageCode: String,
    deviceLocale: Locale,
): Locale = if (languageCode.equals(AppLanguage.DEVICE.code, ignoreCase = true)) {
    deviceLocale
} else {
    Locale.forLanguageTag(languageCode)
}

internal actual object ThemeSettingsStorage {
    private const val selectedThemeKey = "selected_theme"
    private const val customThemeColorsKey = "custom_theme_colors"
    private const val amoledEnabledKey = "amoled_enabled"
    private const val liquidGlassNativeTabBarEnabledKey = "liquid_glass_native_tab_bar_enabled"
    private const val desktopNavigationLayoutKey = "desktop_navigation_layout"
    private const val selectedAppLanguageKey = "selected_app_language"
    private const val navBarStyleKey = "nav_bar_style"
    private const val navBarGlowEnabledKey = "nav_bar_glow_enabled"
    private val profileScopedSyncKeys = listOf(
        selectedThemeKey,
        customThemeColorsKey,
        amoledEnabledKey,
        liquidGlassNativeTabBarEnabledKey,
        desktopNavigationLayoutKey,
        navBarStyleKey,
        navBarGlowEnabledKey,
    )
    private val deviceLocale = Locale.getDefault()
    private val store = DesktopStorage.store("nuvio_theme_settings")

    actual fun loadSelectedTheme(): String? =
        store.getString(ProfileScopedKey.of(selectedThemeKey))

    actual fun saveSelectedTheme(themeName: String) {
        store.putString(ProfileScopedKey.of(selectedThemeKey), themeName)
    }

    actual fun loadCustomThemeColors(): String? =
        store.getString(ProfileScopedKey.of(customThemeColorsKey))

    actual fun saveCustomThemeColors(colors: String) {
        store.putString(ProfileScopedKey.of(customThemeColorsKey), colors)
    }

    actual fun loadAmoledEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(amoledEnabledKey))

    actual fun saveAmoledEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(amoledEnabledKey), enabled)
    }

    actual fun loadLiquidGlassNativeTabBarEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey))

    actual fun saveLiquidGlassNativeTabBarEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(liquidGlassNativeTabBarEnabledKey), enabled)
    }

    actual fun loadDesktopNavigationLayout(): String? =
        store.getString(ProfileScopedKey.of(desktopNavigationLayoutKey))

    actual fun saveDesktopNavigationLayout(layoutName: String) {
        store.putString(ProfileScopedKey.of(desktopNavigationLayoutKey), layoutName)
    }

    actual fun loadSelectedAppLanguage(): String? =
        store.getString(selectedAppLanguageKey)
            ?: Locale.getDefault().toLanguageTag().takeIf { it.isNotBlank() }

    actual fun saveSelectedAppLanguage(languageCode: String) {
        store.putString(selectedAppLanguageKey, languageCode)
    }

    actual fun applySelectedAppLanguage(languageCode: String) {
        Locale.setDefault(resolveDesktopAppLocale(languageCode, deviceLocale))
    }

    actual fun loadNavBarStyle(): String? =
        store.getString(ProfileScopedKey.of(navBarStyleKey))

    actual fun saveNavBarStyle(styleKey: String) {
        store.putString(ProfileScopedKey.of(navBarStyleKey), styleKey)
    }

    actual fun loadNavBarGlowEnabled(): Boolean? =
        store.getBoolean(ProfileScopedKey.of(navBarGlowEnabledKey))

    actual fun saveNavBarGlowEnabled(enabled: Boolean) {
        store.putBoolean(ProfileScopedKey.of(navBarGlowEnabledKey), enabled)
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadSelectedTheme()?.let { put(selectedThemeKey, encodeSyncString(it)) }
        loadCustomThemeColors()?.let { put(customThemeColorsKey, encodeSyncString(it)) }
        loadAmoledEnabled()?.let { put(amoledEnabledKey, encodeSyncBoolean(it)) }
        loadLiquidGlassNativeTabBarEnabled()?.let { put(liquidGlassNativeTabBarEnabledKey, encodeSyncBoolean(it)) }
        loadDesktopNavigationLayout()?.let { put(desktopNavigationLayoutKey, encodeSyncString(it)) }
        loadNavBarStyle()?.let { put(navBarStyleKey, encodeSyncString(it)) }
        loadNavBarGlowEnabled()?.let { put(navBarGlowEnabledKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        store.removeAll(profileScopedSyncKeys.map(ProfileScopedKey::of))
        payload.decodeSyncString(selectedThemeKey)?.let(::saveSelectedTheme)
        payload.decodeSyncString(customThemeColorsKey)?.let(::saveCustomThemeColors)
        payload.decodeSyncBoolean(amoledEnabledKey)?.let(::saveAmoledEnabled)
        payload.decodeSyncBoolean(liquidGlassNativeTabBarEnabledKey)?.let(::saveLiquidGlassNativeTabBarEnabled)
        payload.decodeSyncString(desktopNavigationLayoutKey)?.let(::saveDesktopNavigationLayout)
        payload.decodeSyncString(navBarStyleKey)?.let(::saveNavBarStyle)
        payload.decodeSyncBoolean(navBarGlowEnabledKey)?.let(::saveNavBarGlowEnabled)
        applySelectedAppLanguage(loadSelectedAppLanguage() ?: AppLanguage.ENGLISH.code)
    }
}
