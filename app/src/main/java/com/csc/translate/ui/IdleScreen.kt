package com.csc.translate.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csc.translate.R
import com.csc.translate.data.HearSource
import com.csc.translate.data.UserSettings
import com.csc.translate.ui.theme.AccentBlue
import com.csc.translate.ui.theme.AccentBlueFill
import com.csc.translate.ui.theme.Hairline
import com.csc.translate.session.LiveUiState
import com.csc.translate.session.Phase
import com.csc.translate.ui.theme.Chip
import com.csc.translate.ui.theme.Ink
import com.csc.translate.ui.theme.Mute

@Composable
fun IdleScreen(
    settings: UserSettings,
    live: LiveUiState,
    healthError: String?,
    onBack: () -> Unit,
    onSwap: () -> Unit,
    onHistory: () -> Unit,
    onStart: () -> Unit,
    onPickAudio: () -> Unit,
    onSettings: () -> Unit,
    onHearSource: (HearSource) -> Unit,
) {
    val labels = directionLabels(settings.direction)
    val connecting = live.phase == Phase.Connecting
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.White),
    ) {
        TranslateTopBar(
            sourceLabel = labels.first,
            targetLabel = labels.second,
            swapEnabled = !live.inProgress,
            onBack = onBack,
            onSwap = onSwap,
            onHistory = onHistory,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        connecting && live.fromFile -> stringResource(R.string.uploading_file)
                        connecting -> stringResource(R.string.connecting)
                        settings.hearSource == HearSource.System -> stringResource(R.string.tap_to_start_system)
                        else -> stringResource(R.string.tap_to_start)
                    },
                    fontSize = 18.sp,
                    color = Ink,
                )
                if (healthError != null && !connecting) {
                    Text(
                        text = stringResource(R.string.connection_down, healthError),
                        fontSize = 13.sp,
                        color = Mute,
                        modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (Build.VERSION.SDK_INT >= 29) {
                HearSourcePicker(
                    selected = settings.hearSource,
                    enabled = !live.inProgress,
                    onSelect = onHearSource,
                )
            }
            Row(
                modifier = Modifier.padding(top = 18.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SideAction(
                    icon = Icons.Outlined.AudioFile,
                    contentDescription = stringResource(R.string.choose_audio),
                    enabled = !connecting,
                    onClick = onPickAudio,
                    modifier = Modifier.padding(end = 20.dp),
                )
                StartButton(
                    enabled = !connecting,
                    icon = if (settings.hearSource == HearSource.System) {
                        Icons.Outlined.GraphicEq
                    } else {
                        Icons.Filled.Mic
                    },
                    onClick = onStart,
                )
                SideAction(
                    icon = Icons.Outlined.Tune,
                    contentDescription = stringResource(R.string.settings),
                    onClick = onSettings,
                    modifier = Modifier.padding(start = 20.dp),
                )
            }
        }
    }
}

@Composable
private fun HearSourcePicker(
    selected: HearSource,
    enabled: Boolean,
    onSelect: (HearSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HearChip(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.hear_mic),
            selected = selected == HearSource.Mic,
            enabled = enabled,
            onClick = { onSelect(HearSource.Mic) },
        )
        HearChip(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.hear_system),
            selected = selected == HearSource.System,
            enabled = enabled,
            onClick = { onSelect(HearSource.System) },
        )
    }
}

@Composable
private fun HearChip(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(48.dp)
            .semantics { role = Role.RadioButton },
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) AccentBlueFill else androidx.compose.ui.graphics.Color.White,
        border = BorderStroke(1.5.dp, if (selected) AccentBlue else Hairline),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Ink,
            )
        }
    }
}

@Composable
private fun SideAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = CircleShape, color = Chip, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = contentDescription, tint = Ink)
            }
        }
    }
}

@Composable
private fun StartButton(enabled: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(88.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        shape = CircleShape,
        color = Ink,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = stringResource(R.string.start),
                color = androidx.compose.ui.graphics.Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
