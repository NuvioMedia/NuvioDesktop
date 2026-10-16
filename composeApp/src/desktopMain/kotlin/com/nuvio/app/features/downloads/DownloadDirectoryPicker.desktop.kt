package com.nuvio.app.features.downloads

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser

internal actual suspend fun pickDownloadDirectory(initialPath: String?): String? =
    withContext(Dispatchers.IO) {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose download folder"
            initialPath?.takeIf { it.isNotBlank() }?.let { setCurrentDirectory(java.io.File(it)) }
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile?.absolutePath
        } else {
            null
        }
    }
