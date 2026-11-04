package com.csc.translate.ui

internal const val FOCUS_WINDOW_MS = 10_000L

internal data class FocusSpan(
    val text: String,
    val arrivedAtMs: Long,
)

internal data class FocusPiece(
    val text: String,
    val focused: Boolean,
)

internal fun integrateSpans(spans: List<FocusSpan>, next: String, nowMs: Long): List<FocusSpan> {
    val current = spans.joinToString("") { it.text }
    if (next == current) return spans
    if (current.isNotEmpty() && next.startsWith(current)) {
        return spans + FocusSpan(next.substring(current.length), nowMs)
    }
    val keep = commonPrefixLength(current, next)
    val kept = ArrayList<FocusSpan>(spans.size + 1)
    var consumed = 0
    for (span in spans) {
        if (consumed >= keep) break
        val end = consumed + span.text.length
        if (end <= keep) {
            kept += span
            consumed = end
        } else {
            kept += span.copy(text = span.text.substring(0, keep - consumed))
            consumed = keep
        }
    }
    if (keep < next.length) {
        kept += FocusSpan(next.substring(keep), nowMs)
    }
    return kept
}

internal fun focusPieces(
    spans: List<FocusSpan>,
    revealed: Int,
    nowMs: Long,
    windowMs: Long = FOCUS_WINDOW_MS,
): List<FocusPiece> {
    if (revealed <= 0) return emptyList()
    val out = ArrayList<FocusPiece>()
    var seen = 0
    for (span in spans) {
        if (seen >= revealed) break
        val take = minOf(span.text.length, revealed - seen)
        if (take > 0) {
            val focused = nowMs - span.arrivedAtMs <= windowMs
            val piece = span.text.substring(0, take)
            val last = out.lastOrNull()
            if (last != null && last.focused == focused) {
                out[out.lastIndex] = last.copy(text = last.text + piece)
            } else {
                out += FocusPiece(piece, focused)
            }
        }
        seen += span.text.length
    }
    return out
}

internal fun typeDelayMs(char: Char, backlog: Int): Long {
    val base = when {
        char.isWhitespace() -> 10L
        char.code < 128 -> 16L
        else -> 32L
    }
    val paced = when {
        backlog > 48 -> 4L
        backlog > 24 -> base / 2
        else -> base
    }
    return paced.coerceAtLeast(4L)
}

internal fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var i = 0
    while (i < limit && left[i] == right[i]) i++
    return i
}
