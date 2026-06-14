/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.mpv.utils

import org.jetbrains.skiko.SkiaLayer
import java.awt.Container
import java.awt.Window
import javax.swing.JComponent

//Find Skia layer in ComposeWindow, fork from https://github.com/MayakaApps/ComposeWindowStyler/blob/02d220cd719eaebaf911bb0acf4d41d4908805c5/window-styler/src/jvmMain/kotlin/com/mayakapps/compose/windowstyler/TransparencyUtils.kt#L38
fun Window.findSkiaLayer() = findComponent<SkiaLayer>()

private fun <T : JComponent> findComponent(
    container: Container,
    klass: Class<T>,
): T? {
    val componentSequence = container.components.asSequence()
    return componentSequence
        .filter { klass.isInstance(it) }
        .ifEmpty {
            componentSequence
                .filterIsInstance<Container>()
                .mapNotNull { findComponent(it, klass) }
        }.map { klass.cast(it) }
        .firstOrNull()
}

private inline fun <reified T : JComponent> Container.findComponent() = findComponent(this, T::class.java)