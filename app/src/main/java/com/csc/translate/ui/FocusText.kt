package com.csc.translate.ui

internal const val FOCUS_WINDOW_MS = 10_000L
internal const val ENGLISH_SETTLE_MS = 500L

internal data class FollowCursor(
    val spans: List<FocusSpan>,
    val revealed: Int,
    val primed: Boolean,
)

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

/**
 * Moves the typewriter the way the watch page does. English keeps the cursor where it is,
 * so a revised line replaces text already on screen. Other languages retype from the edit.
 */
internal fun followCursor(
    spans: List<FocusSpan>,
    revealed: Int,
    primed: Boolean,
    next: String,
    english: Boolean,
    nowMs: Long,
): FollowCursor {
    if (!primed) {
        return FollowCursor(
            spans = if (next.isEmpty()) emptyList() else listOf(FocusSpan(next, nowMs)),
            revealed = next.length,
            primed = true,
        )
    }
    val previous = spans.joinToString("") { it.text }
    if (next == previous) return FollowCursor(spans, revealed, primed)
    val prefix = commonPrefixLength(previous, next)
    val shown = if (english) {
        minOf(revealed, next.length)
    } else {
        var cursor = revealed
        if (prefix < cursor) cursor = prefix
        if (cursor > next.length) cursor = next.length
        cursor
    }
    return FollowCursor(integrateSpans(spans, next, nowMs), shown, primed)
}

/** English types only a span that has sat unchanged for [ENGLISH_SETTLE_MS]. */
internal fun stableEnd(
    spans: List<FocusSpan>,
    nowMs: Long,
    english: Boolean,
    settleMs: Long = ENGLISH_SETTLE_MS,
): Int {
    if (!english) return spans.sumOf { it.text.length }
    var count = 0
    for (span in spans) {
        if (nowMs - span.arrivedAtMs < settleMs) break
        count += span.text.length
    }
    return count
}

internal fun settleWaitMs(
    spans: List<FocusSpan>,
    revealed: Int,
    nowMs: Long,
    settleMs: Long = ENGLISH_SETTLE_MS,
): Long {
    var seen = 0
    for (span in spans) {
        val end = seen + span.text.length
        if (revealed < end) {
            return (settleMs - (nowMs - span.arrivedAtMs)).coerceAtLeast(16L)
        }
        seen = end
    }
    return settleMs
}

internal fun typeDelayMs(char: Char, backlog: Int, english: Boolean = false): Long {
    val base = when {
        char.isWhitespace() -> 10L
        char.code < 128 -> 16L
        else -> 32L
    }
    if (english) return base
    val paced = when {
        backlog > 48 -> 4L
        backlog > 24 -> base / 2
        else -> base
    }
    return paced.coerceAtLeast(4L)
}

/** How far the cursor moves after [elapsedMs] on the clock, independent of layout. */
internal fun advanceRevealed(
    revealed: Int,
    text: String,
    limit: Int,
    elapsedMs: Long,
    english: Boolean,
): Int {
    var cursor = revealed
    var budget = elapsedMs
    while (cursor < limit && cursor < text.length && budget > 0) {
        val cost = typeDelayMs(text[cursor], text.length - cursor, english)
        if (budget < cost) break
        budget -= cost
        cursor += 1
    }
    return cursor
}

internal fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var i = 0
    while (i < limit && left[i] == right[i]) i++
    return i
}
