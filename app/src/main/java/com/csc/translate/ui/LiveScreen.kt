package com.csc.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.csc.translate.R
import com.csc.translate.data.DisplayMode
import com.csc.translate.data.HearSource
import com.csc.translate.data.TypeScale
import com.csc.translate.data.UserSettings
import com.csc.translate.session.LiveUiState
import com.csc.translate.session.Phase
import com.csc.translate.ui.theme.Danger
import com.csc.translate.ui.theme.FocusDim
import com.csc.translate.ui.theme.Ink
import com.csc.translate.ui.theme.Mute
import com.csc.translate.ui.theme.Pane

@Composable
fun LiveScreen(
    live: LiveUiState,
    settings: UserSettings,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onCopied: () -> Unit,
) {
    val labels = directionLabels(live.direction)
    val connecting = live.phase == Phase.Connecting
    val finishing = live.phase == Phase.Finishing
    val status = when {
        connecting && live.fromFile -> stringResource(R.string.uploading_file)
        connecting -> stringResource(R.string.connecting)
        finishing -> stringResource(R.string.finishing)
        live.paused -> stringResource(R.string.paused)
        live.fromFile -> stringResource(R.string.translating_file)
        settings.hearSource == HearSource.System -> stringResource(R.string.listening_system)
        else -> stringResource(R.string.listening_en)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        TranslateTopBar(
            sourceLabel = labels.first,
            targetLabel = labels.second,
            swapEnabled = false,
            onBack = onBack,
            onSwap = {},
            onHistory = onHistory,
        )
        if (live.watchUrl.isNotBlank()) {
            WatchLink(url = live.watchUrl, onCopied = onCopied)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (settings.displayMode) {
                DisplayMode.Split -> {
                    Column(Modifier.fillMaxSize()) {
                        TranscriptPane(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            language = labels.first,
                            text = live.sourceText,
                            status = status,
                            background = Color.White,
                            typeScale = settings.typeScale,
                        )
                        TranscriptPane(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(bottom = 96.dp),
                            language = labels.second,
                            text = live.targetText,
                            status = status,
                            background = Pane,
                            typeScale = settings.typeScale,
                        )
                    }
                }
                DisplayMode.Translation -> {
                    TranscriptPane(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 96.dp),
                        language = labels.second,
                        text = live.targetText,
                        status = status,
                        background = Pane,
                        typeScale = settings.typeScale,
                    )
                }
                DisplayMode.Source -> {
                    TranscriptPane(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = 96.dp),
                        language = labels.first,
                        text = live.sourceText,
                        status = status,
                        background = Color.White,
                        typeScale = settings.typeScale,
                    )
                }
            }
            SessionPill(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp),
                elapsedMs = live.elapsedMs,
                paused = live.paused,
                finishing = finishing,
                showPause = !live.fromFile,
                onPause = onPause,
                onStop = onStop,
                onSettings = onSettings,
            )
        }
    }
}

@Composable
private fun WatchLink(url: String, onCopied: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                clipboard.setText(AnnotatedString(url))
                onCopied()
            }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.watch_link),
            color = Mute,
            fontSize = 13.sp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = url,
            modifier = Modifier.weight(1f),
            color = Ink,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TranscriptPane(
    modifier: Modifier,
    language: String,
    text: String,
    status: String,
    background: Color,
    typeScale: TypeScale,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .background(background)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(language, color = Mute, fontSize = 14.sp)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            FollowText(
                text = text,
                fontSizeSp = typeScale.sp,
                lineHeightSp = typeScale.lineHeight,
                onAdvance = {
                    if (text.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            )
        }
        Text(
            text = status,
            color = Mute,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun FollowText(
    text: String,
    fontSizeSp: Int,
    lineHeightSp: Int,
    onAdvance: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    var spans by remember { mutableStateOf<List<FocusSpan>>(emptyList()) }
    var revealed by remember { mutableIntStateOf(0) }
    var primed by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cursorOn by remember { mutableStateOf(true) }

    LaunchedEffect(text) {
        val now = System.currentTimeMillis()
        nowMs = now
        if (!primed) {
            spans = if (text.isEmpty()) emptyList() else listOf(FocusSpan(text, now))
            revealed = text.length
            primed = true
            return@LaunchedEffect
        }
        val previous = spans.joinToString("") { it.text }
        val prefix = commonPrefixLength(previous, text)
        spans = integrateSpans(spans, text, now)
        if (prefix < revealed) revealed = prefix
        if (revealed > text.length) revealed = text.length
    }
    LaunchedEffect(spans) {
        while (true) {
            val full = spans.joinToString("") { it.text }
            if (revealed >= full.length) break
            delay(typeDelayMs(full[revealed], full.length - revealed))
            val latest = spans.joinToString("") { it.text }
            if (revealed >= latest.length) break
            revealed += 1
            nowMs = System.currentTimeMillis()
            onAdvance()
        }
    }
    LaunchedEffect(spans) {
        if (spans.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(250)
            nowMs = System.currentTimeMillis()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            cursorOn = !cursorOn
        }
    }
    val shown = buildAnnotatedString {
        for (piece in focusPieces(spans, revealed, nowMs)) {
            withStyle(SpanStyle(color = if (piece.focused) Ink else FocusDim)) {
                append(piece.text)
            }
        }
        withStyle(SpanStyle(color = if (cursorOn) Ink else Color.Transparent)) {
            append("▍")
        }
    }
    Text(
        text = shown,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeightSp.sp,
        modifier = modifier,
    )
}

@Composable
private fun SessionPill(
    modifier: Modifier,
    elapsedMs: Long,
    paused: Boolean,
    finishing: Boolean,
    showPause: Boolean,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = Color.White,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Danger),
            )
            Text(formatClock(elapsedMs), color = Ink, fontSize = 16.sp)
            if (showPause) {
                Icon(
                    imageVector = if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(if (paused) R.string.resume else R.string.pause),
                    tint = Ink,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(enabled = !finishing, role = Role.Button, onClick = onPause),
                )
            }
            Icon(
                imageVector = Icons.Outlined.Tune,
                contentDescription = stringResource(R.string.settings),
                tint = Ink,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(role = Role.Button, onClick = onSettings),
            )
            Spacer(
                modifier = Modifier
                    .width(1.dp)
                    .height(18.dp)
                    .background(Color(0xFFE5E7EB)),
            )
            Icon(
                imageVector = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.stop),
                tint = Danger,
                modifier = Modifier
                    .size(22.dp)
                    .clickable(enabled = !finishing, role = Role.Button, onClick = onStop),
            )
        }
    }
}
