package com.csc.translate.pipeline

internal const val LIVE_SENTENCE_WINDOW = 10

internal data class CaptionLine(
    val id: Long,
    val src: String,
    val tgt: String,
    val atMs: Long = 0L,
)

internal data class CaptionRow(
    val id: Long,
    val text: String,
    val typing: Boolean,
    val atMs: Long = 0L,
)

internal data class LiveSnapshot(
    val lines: List<CaptionLine>,
    val pending: String,
    val pendingId: Long,
    val translatingId: Long,
)

internal fun liveSourceRows(
    lines: List<CaptionLine>,
    pending: String,
    pendingId: Long,
    window: Int = LIVE_SENTENCE_WINDOW,
): List<CaptionRow> {
    val locked = lines.takeLast(window.coerceAtLeast(0))
        .filter { it.src.isNotBlank() }
        .map { CaptionRow(id = it.id, text = it.src, typing = false, atMs = it.atMs) }
    if (pending.isNotBlank() && pendingId != 0L) {
        return locked + CaptionRow(id = pendingId, text = pending, typing = true)
    }
    if (locked.isEmpty()) return locked
    return locked.dropLast(1) + locked.last().copy(typing = true)
}

internal fun liveTargetRows(
    lines: List<CaptionLine>,
    translatingId: Long,
    window: Int = LIVE_SENTENCE_WINDOW,
): List<CaptionRow> {
    val keep = lines.takeLast(window.coerceAtLeast(0))
    val lastId = keep.lastOrNull()?.id ?: 0L
    return keep.map { line ->
        val typing = line.id == translatingId || (translatingId == 0L && line.id == lastId)
        CaptionRow(id = line.id, text = line.tgt, typing = typing, atMs = line.atMs)
    }.filter { it.text.isNotBlank() || it.typing }
}
