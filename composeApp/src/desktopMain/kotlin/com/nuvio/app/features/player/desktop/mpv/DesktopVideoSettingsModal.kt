package com.nuvio.app.features.player.desktop.mpv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.player.PlayerHardwareDecoderMode
import com.nuvio.app.features.player.IosTargetPrimaries
import com.nuvio.app.features.player.IosTargetTransfer
import com.nuvio.app.features.player.IosToneMappingMode
import com.nuvio.app.features.player.IosVideoOutputPreset
import com.nuvio.app.features.player.OptionGroup
import com.nuvio.app.features.player.PictureSlider
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.ToggleRow

@Composable
internal fun DesktopVideoSettingsModal(
    visible: Boolean,
    settings: PlayerSettingsUiState,
    onSettingsChanged: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val hwdecVersion by DesktopMpvPlaybackSettingsSignal.version.collectAsState()

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
    ) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                )
                .background(colorScheme.scrim.copy(alpha = 0.56f)),
            contentAlignment = Alignment.Center,
        ) {
            val maxH = maxHeight
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(tween(300)) { it / 3 } + fadeIn(tween(300)),
                exit = slideOutVertically(tween(250)) { it / 3 } + fadeOut(tween(250)),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 460.dp)
                        .fillMaxWidth(0.92f)
                        .heightIn(max = maxH * 0.95f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(colorScheme.surface)
                        .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.8f), RoundedCornerShape(24.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = {},
                        ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Desktop Video",
                            color = colorScheme.onSurface,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            PlayerSettingsRepository.resetIosVideoOutputTuning()
                            onSettingsChanged()
                        }) {
                            Text("Reset tuning")
                        }
                    }

                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OptionGroup(
                            title = "Output preset",
                            options = IosVideoOutputPreset.entries,
                            selected = settings.iosVideoOutputPreset,
                            label = { it.label },
                            description = { it.description },
                            onSelect = {
                                PlayerSettingsRepository.setIosVideoOutputPreset(it)
                                onSettingsChanged()
                            },
                        )

                        OptionGroup(
                            title = "Hardware decoding",
                            options = PlayerHardwareDecoderMode.entries,
                            selected = loadHardwareDecoderMode(),
                            label = { it.label },
                            onSelect = {
                                storeDesktopHardwareDecoderMode(it)
                                onSettingsChanged()
                            },
                        )

                        ToggleRow(
                            title = "HDR peak detection",
                            description = "Estimate HDR peak brightness when metadata is bad or missing.",
                            checked = settings.iosHdrComputePeakEnabled,
                            onCheckedChange = {
                                PlayerSettingsRepository.setIosHdrComputePeakEnabled(it)
                                onSettingsChanged()
                            },
                        )

                        OptionGroup(
                            title = "Tone mapping",
                            options = IosToneMappingMode.entries,
                            selected = settings.iosToneMappingMode,
                            label = { it.label },
                            onSelect = {
                                PlayerSettingsRepository.setIosToneMappingMode(it)
                                onSettingsChanged()
                            },
                        )

                        OptionGroup(
                            title = "Target primaries",
                            options = IosTargetPrimaries.entries,
                            selected = settings.iosTargetPrimaries,
                            label = { it.label },
                            onSelect = {
                                PlayerSettingsRepository.setIosTargetPrimaries(it)
                                onSettingsChanged()
                            },
                        )

                        OptionGroup(
                            title = "Target transfer",
                            options = IosTargetTransfer.entries,
                            selected = settings.iosTargetTransfer,
                            label = { it.label },
                            onSelect = {
                                PlayerSettingsRepository.setIosTargetTransfer(it)
                                onSettingsChanged()
                            },
                        )

                        ToggleRow(
                            title = "Deband",
                            description = "Reduce color banding at a small performance cost.",
                            checked = settings.iosDebandEnabled,
                            onCheckedChange = {
                                PlayerSettingsRepository.setIosDebandEnabled(it)
                                onSettingsChanged()
                            },
                        )
                        ToggleRow(
                            title = "Frame interpolation",
                            description = "Smooth motion when mpv can use display sync cleanly.",
                            checked = settings.iosInterpolationEnabled,
                            onCheckedChange = {
                                PlayerSettingsRepository.setIosInterpolationEnabled(it)
                                onSettingsChanged()
                            },
                        )

                        PictureSlider(
                            title = "Brightness",
                            value = settings.iosBrightness,
                            onValueChanged = {
                                PlayerSettingsRepository.setIosBrightness(it)
                                onSettingsChanged()
                            },
                        )
                        PictureSlider(
                            title = "Contrast",
                            value = settings.iosContrast,
                            onValueChanged = {
                                PlayerSettingsRepository.setIosContrast(it)
                                onSettingsChanged()
                            },
                        )
                        PictureSlider(
                            title = "Saturation",
                            value = settings.iosSaturation,
                            onValueChanged = {
                                PlayerSettingsRepository.setIosSaturation(it)
                                onSettingsChanged()
                            },
                        )
                        /*  Gamma setting temporarily disabled
                        PictureSlider(
                            title = "Gamma",
                            value = settings.iosGamma,
                            onValueChanged = {
                                 PlayerSettingsRepository.setIosGamma(it)
                                 onSettingsChanged()
                            },
                        )
                        */
                    }
                }
            }
        }
    }
}
