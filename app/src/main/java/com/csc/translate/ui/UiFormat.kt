package com.csc.translate.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

fun formatHistoryDate(epochMs: Long): String {
    return SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US).format(Date(epochMs))
}

fun formatHistoryDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

fun directionLabels(direction: String): Pair<String, String> {
    return if (direction == "zh-en") "Cantonese" to "English" else "English" to "Cantonese"
}

fun paneStatus(
    connecting: Boolean,
    paused: Boolean,
    finishing: Boolean,
    fromFile: Boolean = false,
): String {
    return when {
        connecting && fromFile -> "Uploading audio…"
        connecting -> "Connecting…"
        finishing -> "Finishing…"
        paused -> "Paused"
        fromFile -> "Translating file…"
        else -> "Listening"
    }
}
