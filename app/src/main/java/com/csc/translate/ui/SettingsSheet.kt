package com.csc.translate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csc.translate.R
import com.csc.translate.data.DisplayMode
import com.csc.translate.data.HearSource
import com.csc.translate.data.Pipeline
import com.csc.translate.data.TypeScale
import com.csc.translate.data.UserSettings
import com.csc.translate.net.Health
import com.csc.translate.net.ServiceConfig
import com.csc.translate.ui.theme.AccentBlue
import com.csc.translate.ui.theme.AccentBlueFill
import com.csc.translate.ui.theme.Hairline
import com.csc.translate.ui.theme.Ink
import com.csc.translate.ui.theme.Mute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: UserSettings,
    health: Health?,
    config: ServiceConfig?,
    healthError: String?,
    liveLocked: Boolean,
    onDismiss: () -> Unit,
    onSaveUrl: (String) -> Unit,
    onSelectPipeline: (Pipeline, String) -> Unit,
    onChunkMode: (String) -> Unit,
    onVad: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onDisplayMode: (DisplayMode) -> Unit,
    onTypeScale: (TypeScale) -> Unit,
    onHearSource: (HearSource) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf(settings.activeUrl()) }
    LaunchedEffect(settings.pipeline, settings.baseUrl, settings.tomoruUrl) {
        url = settings.activeUrl()
    }

    ModalBottomSheet(
        onDismissRequest = {
            onSaveUrl(url)
            onDismiss()
        },
        sheetState = sheetState,
        dragHandle = null,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 22.dp, bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.settings),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.display_mode))
            ChoiceRow(
                options = listOf(
                    Choice(stringResource(R.string.display_split), settings.displayMode == DisplayMode.Split) {
                        onDisplayMode(DisplayMode.Split)
                    },
                    Choice(stringResource(R.string.display_translation), settings.displayMode == DisplayMode.Translation) {
                        onDisplayMode(DisplayMode.Translation)
                    },
                    Choice(stringResource(R.string.display_source), settings.displayMode == DisplayMode.Source) {
                        onDisplayMode(DisplayMode.Source)
                    },
                ),
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.font_size))
            ChoiceRow(
                options = listOf(
                    Choice(stringResource(R.string.font_small), settings.typeScale == TypeScale.Small) {
                        onTypeScale(TypeScale.Small)
                    },
                    Choice(stringResource(R.string.font_medium), settings.typeScale == TypeScale.Medium) {
                        onTypeScale(TypeScale.Medium)
                    },
                    Choice(stringResource(R.string.font_large), settings.typeScale == TypeScale.Large) {
                        onTypeScale(TypeScale.Large)
                    },
                ),
            )

            if (Build.VERSION.SDK_INT >= 29) {
                Spacer(Modifier.height(22.dp))
                SectionLabel(stringResource(R.string.hear_source))
                ChoiceRow(
                    options = listOf(
                        Choice(stringResource(R.string.hear_mic), settings.hearSource == HearSource.Mic) {
                            if (!liveLocked) onHearSource(HearSource.Mic)
                        },
                        Choice(stringResource(R.string.hear_system), settings.hearSource == HearSource.System) {
                            if (!liveLocked) onHearSource(HearSource.System)
                        },
                    ),
                )
                if (settings.hearSource == HearSource.System) {
                    Text(
                        text = stringResource(R.string.system_audio_note),
                        color = Mute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            SectionLabel(stringResource(R.string.pipeline))
            ChoiceRow(
                options = listOf(
                    Choice(stringResource(R.string.pipeline_simul), settings.pipeline == Pipeline.Simul) {
                        if (!liveLocked) onSelectPipeline(Pipeline.Simul, url)
                    },
                    Choice(stringResource(R.string.pipeline_tomoru), settings.pipeline == Pipeline.Tomoru) {
                        if (!liveLocked) onSelectPipeline(Pipeline.Tomoru, url)
                    },
                ),
            )

            Spacer(Modifier.height(22.dp))
            SectionLabel(
                stringResource(
                    if (settings.pipeline == Pipeline.Tomoru) R.string.asr_url else R.string.server_url,
                ),
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                placeholder = {
                    if (settings.pipeline == Pipeline.Tomoru) {
                        Text(stringResource(R.string.tomoru_url_hint), color = Mute)
                    }
                },
                singleLine = true,
                enabled = !liveLocked,
            )
            Button(
                onClick = {
                    onSaveUrl(url)
                    onRefresh()
                },
                modifier = Modifier.padding(top = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ink,
                    contentColor = Color.White,
                    disabledContainerColor = Ink.copy(alpha = 0.38f),
                    disabledContentColor = Color.White.copy(alpha = 0.7f),
                ),
                enabled = !liveLocked,
            ) {
                Text(stringResource(R.string.test_connection), color = Color.White)
            }
            val statusText = when {
                settings.activeUrl().isBlank() -> stringResource(R.string.server_address_missing)
                healthError != null -> stringResource(R.string.connection_down, healthError)
                settings.pipeline == Pipeline.Tomoru && health != null -> stringResource(R.string.asr_ready)
                health != null -> stringResource(
                    R.string.connection_ok,
                    health.activeSessions,
                    health.maxSessions,
                )
                else -> stringResource(R.string.connecting)
            }
            Text(
                text = statusText,
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (settings.pipeline == Pipeline.Tomoru) {
                Text(
                    text = stringResource(R.string.asr_pipeline_note),
                    color = Mute,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (settings.pipeline == Pipeline.Simul) {
                Spacer(Modifier.height(22.dp))
                SectionLabel(stringResource(R.string.chunk_mode))
                ModeRow(
                    selected = settings.responseMode == "standard",
                    label = stringResource(R.string.chunk_standard),
                    enabled = !liveLocked,
                    onClick = { onChunkMode("standard") },
                )
                ModeRow(
                    selected = settings.responseMode == "short_chunk",
                    label = stringResource(R.string.chunk_short),
                    enabled = !liveLocked,
                    onClick = { onChunkMode("short_chunk") },
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.vad),
                        modifier = Modifier.weight(1f),
                        color = Ink,
                        fontSize = 16.sp,
                    )
                    Switch(
                        checked = settings.vad,
                        onCheckedChange = onVad,
                        enabled = !liveLocked && (config?.vadAvailable ?: true),
                        colors = SwitchDefaults.colors(checkedTrackColor = Ink),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tts), color = Ink, fontSize = 16.sp)
                    Text(stringResource(R.string.tts_unavailable), color = Mute, fontSize = 12.sp)
                }
                Switch(checked = false, onCheckedChange = {}, enabled = false)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, color = Mute, fontSize = 14.sp)
}

private data class Choice(
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
)

@Composable
private fun ChoiceRow(options: List<Choice>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        options.forEach { choice ->
            ChoiceChip(
                modifier = Modifier.weight(1f),
                label = choice.label,
                selected = choice.selected,
                onClick = choice.onClick,
            )
        }
    }
}

@Composable
private fun ChoiceChip(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .semantics { role = Role.Button },
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AccentBlueFill else Color.White,
        border = BorderStroke(1.5.dp, if (selected) AccentBlue else Hairline),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 4.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Ink,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ModeRow(
    selected: Boolean,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(selectedColor = Ink),
        )
        Text(label, color = Ink, fontSize = 16.sp)
    }
}
