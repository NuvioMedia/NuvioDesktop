package com.nuvio.app.features.downloads

/**
 * Opens a native folder picker. Returns the chosen absolute path, or null
 * when the user cancels or the platform has no picker.
 */
internal expect suspend fun pickDownloadDirectory(initialPath: String?): String?
