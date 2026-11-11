package com.csc.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csc.translate.R
import com.csc.translate.data.DisplayMode
import com.csc.translate.data.TypeScale
import com.csc.translate.data.UserSettings
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.session.sourceForDisplay
import com.csc.translate.session.targetForDisplay
import com.csc.translate.ui.theme.Ink
import com.csc.translate.ui.theme.Mute
import com.csc.translate.ui.theme.Pane

@Composable
fun HistoryDetailScreen(
    session: SessionEntity?,
    settings: UserSettings,
    onBack: () -> Unit,
    onDownload: (SessionEntity) -> Unit,
) {
    val labels = directionLabels(session?.direction ?: "en-zh")
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButtonCircle(onClick = onBack, label = stringResource(R.string.back)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = Ink)
            }
            Text(
                text = session?.title ?: stringResource(R.string.history_title),
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (session != null) {
                IconButtonCircle(onClick = { onDownload(session) }, label = stringResource(R.string.download)) {
                    Icon(Icons.Outlined.Download, contentDescription = null, tint = Ink)
                }
            }
        }
        if (session == null) {
            Text(
                text = stringResource(R.string.empty_history),
                color = Mute,
                modifier = Modifier.padding(24.dp),
            )
        } else {
            Text(
                text = "${formatHistoryDate(session.startedAt)}  ·  ${formatHistoryDuration(session.durationMs)}  ·  ${labels.first} → ${labels.second}",
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = Color(0xFFF0F0F0))
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (settings.displayMode != DisplayMode.Translation) {
                    DetailPane(
                        language = labels.first,
                        text = sourceForDisplay(session.sourceText, session.direction),
                        background = Color.White,
                        typeScale = settings.typeScale,
                    )
                }
                if (settings.displayMode != DisplayMode.Source) {
                    DetailPane(
                        language = labels.second,
                        text = targetForDisplay(session.targetText),
                        background = Pane,
                        typeScale = settings.typeScale,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailPane(language: String, text: String, background: Color, typeScale: TypeScale) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(language, color = Mute, fontSize = 14.sp)
        Text(
            text = text.ifBlank { "—" },
            color = Ink,
            fontSize = typeScale.sp.sp,
            lineHeight = typeScale.lineHeight.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 16.dp),
        )
    }
}
