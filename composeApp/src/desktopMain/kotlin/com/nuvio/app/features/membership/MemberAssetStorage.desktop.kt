package com.nuvio.app.features.membership

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File

internal actual object MemberAssetStorage {
    private const val accessPayloadKey = "access_payload"
    private const val backgroundCatalogPayloadKey = "profile_background_catalog_payload"

    private val store = DesktopStorage.store("nuvio_member_access")
    private val backgroundDirectory: File by lazy {
        DesktopStorage.cacheDir.resolve("member_profile_backgrounds").toFile()
    }
    private val avatarDirectory: File by lazy {
        DesktopStorage.cacheDir.resolve("member_profile_avatars").toFile()
    }

    actual fun loadAccessPayload(): String? = store.getString(accessPayloadKey)

    actual fun saveAccessPayload(payload: String) {
        store.putString(accessPayloadKey, payload)
    }

    actual fun loadProfileBackgroundCatalogPayload(): String? = store.getString(backgroundCatalogPayloadKey)

    actual fun saveProfileBackgroundCatalogPayload(payload: String) {
        store.putString(backgroundCatalogPayloadKey, payload)
    }

    actual fun loadProfileBackground(cacheKey: String): ByteArray? =
        backgroundFile(cacheKey).takeIf { it.isFile && it.length() > 0L }?.readBytes()

    actual fun saveProfileBackground(cacheKey: String, bytes: ByteArray) {
        saveFile(backgroundFile(cacheKey), bytes)
    }

    actual fun loadProfileAvatar(cacheKey: String): String? =
        avatarFile(cacheKey).takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()

    actual fun saveProfileAvatar(cacheKey: String, bytes: ByteArray): String? {
        val file = avatarFile(cacheKey)
        saveFile(file, bytes)
        return file.takeIf { it.isFile && it.length() > 0L }?.toURI()?.toString()
    }

    actual fun clearAccess() {
        store.remove(accessPayloadKey)
    }

    private fun backgroundFile(cacheKey: String): File =
        backgroundDirectory.resolve("${safeKey(cacheKey)}.png")

    private fun avatarFile(cacheKey: String): File = avatarDirectory.resolve(safeKey(cacheKey))

    private fun saveFile(file: File, bytes: ByteArray) {
        val directory = file.parentFile ?: return
        directory.mkdirs()
        val temporary = directory.resolve(".${file.name}.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) {
            temporary.copyTo(file, overwrite = true)
            temporary.delete()
        }
    }

    private fun safeKey(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
