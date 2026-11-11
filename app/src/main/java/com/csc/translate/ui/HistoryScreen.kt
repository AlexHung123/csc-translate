package com.csc.translate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.csc.translate.R
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.session.ChineseDisplay
import com.csc.translate.ui.theme.Ink
import com.csc.translate.ui.theme.Mute
import com.csc.translate.ui.theme.Pane

@Composable
fun HistoryScreen(
    sessions: List<SessionEntity>,
    inProgress: Boolean,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onDownload: (SessionEntity) -> Unit,
    onDelete: (List<String>) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(setOf<String>()) }

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
                text = stringResource(R.string.history_title),
                modifier = Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink,
            )
            Box(modifier = Modifier.size(44.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Pane)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.history_count, sessions.size),
                    color = Mute,
                    fontSize = 14.sp,
                )
                if (inProgress) {
                    Text(
                        text = stringResource(R.string.history_in_progress),
                        color = Mute,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Ink,
                modifier = Modifier.clickable(role = Role.Button) {
                    if (editing && selected.isNotEmpty()) {
                        onDelete(selected.toList())
                        selected = emptySet()
                    }
                    editing = !editing
                    if (!editing) selected = emptySet()
                },
            ) {
                val label = when {
                    editing && selected.isNotEmpty() -> stringResource(R.string.delete_n, selected.size)
                    editing -> stringResource(R.string.done_edit)
                    else -> stringResource(R.string.edit)
                }
                Text(
                    text = label,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.empty_history), color = Mute, fontSize = 15.sp)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(sessions, key = { it.id }) { item ->
                    HistoryRow(
                        item = item,
                        editing = editing,
                        checked = item.id in selected,
                        onToggle = {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        },
                        onOpen = { onOpen(item.id) },
                        onDownload = { onDownload(item) },
                    )
                    HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 1.dp)
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(
    item: SessionEntity,
    editing: Boolean,
    checked: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = if (editing) onToggle else onOpen)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = Ink),
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = ChineseDisplay.toTraditional(item.title),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = Mute, modifier = Modifier.size(14.dp))
                    Text(
                        text = formatHistoryDate(item.startedAt),
                        color = Mute,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Mute, modifier = Modifier.size(14.dp))
                    Text(
                        text = formatHistoryDuration(item.durationMs),
                        color = Mute,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
        if (!editing) {
            IconButtonCircle(onClick = onDownload, label = stringResource(R.string.download)) {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = null,
                    tint = Ink,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = Mute,
        )
    }
}
