package com.csc.translate.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.csc.translate.data.db.SessionEntity
import com.csc.translate.session.ChineseDisplay
import com.csc.translate.session.sourceForDisplay
import com.csc.translate.session.targetForDisplay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun historyFileName(session: SessionEntity): String {
    val date = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.US).format(Date(session.startedAt))
    val title = ChineseDisplay.toTraditional(session.title)
        .replace(Regex("""[\\/:*?"<>|\n\r]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(40)
        .ifBlank { "session" }
    return "$date $title.txt"
}

fun historyTranscript(session: SessionEntity): String {
    val labels = directionLabels(session.direction)
    val source = sourceForDisplay(session.sourceText, session.direction).ifBlank { "—" }
    val target = targetForDisplay(session.targetText).ifBlank { "—" }
    return buildString {
        appendLine(ChineseDisplay.toTraditional(session.title))
        append(formatHistoryDate(session.startedAt))
        append("  ·  ")
        append(formatHistoryDuration(session.durationMs))
        append("  ·  ")
        append(labels.first)
        append(" → ")
        appendLine(labels.second)
        appendLine()
        appendLine(labels.first)
        appendLine(source)
        appendLine()
        appendLine(labels.second)
        appendLine(target)
        appendLine()
    }
}

fun saveTranscriptToDownloads(context: Context, session: SessionEntity) {
    check(Build.VERSION.SDK_INT >= 29)
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, historyFileName(session))
        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("downloads insert")
    try {
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(historyTranscript(session).toByteArray(Charsets.UTF_8))
        } ?: error("downloads stream")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    } catch (error: Exception) {
        resolver.delete(uri, null, null)
        throw error
    }
}
