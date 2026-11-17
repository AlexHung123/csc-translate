package com.csc.translate.ui

internal const val FOCUS_WINDOW_MS = 10_000L
internal const val ENGLISH_SETTLE_MS = 500L

/** The first character waits this long, once, before the queue starts playing. */
internal const val STREAM_PREROLL_MS = 5_000L
internal const val CJK_STREAM_MS = 150L
internal const val LETTER_STREAM_MS = 40L
internal const val SPACE_STREAM_MS = 20L

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
 * The first text starts unrevealed. A revision keeps the cursor where it is, so a character
 * already on screen changes in place. The unrevealed tail is replaced in the queue.
 */
internal fun followCursor(
    spans: List<FocusSpan>,
    revealed: Int,
    primed: Boolean,
    next: String,
    nowMs: Long,
): FollowCursor {
    if (!primed) {
        return FollowCursor(
            spans = if (next.isEmpty()) emptyList() else listOf(FocusSpan(next, nowMs)),
            revealed = 0,
            primed = true,
        )
    }
    val previous = spans.joinToString("") { it.text }
    if (next == previous) return FollowCursor(spans, revealed, primed)
    return FollowCursor(integrateSpans(spans, next, nowMs), minOf(revealed, next.length), primed)
}

/** Arms the one-time preroll when the first character arrives. A later call keeps the same instant. */
internal fun streamReleaseAt(releaseAtMs: Long, nowMs: Long, hasText: Boolean): Long {
    if (releaseAtMs != 0L) return releaseAtMs
    if (!hasText) return 0L
    return nowMs + STREAM_PREROLL_MS
}

internal fun streamReady(releaseAtMs: Long, nowMs: Long): Boolean =
    releaseAtMs != 0L && nowMs >= releaseAtMs

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

/** The unrevealed suffix is the stream queue. */
internal fun queuedText(text: String, revealed: Int): String {
    if (revealed >= text.length) return ""
    return text.substring(revealed.coerceAtLeast(0))
}

/** Han and other non-ASCII characters take [CJK_STREAM_MS]. ASCII letters take [LETTER_STREAM_MS]. */
internal fun typeDelayMs(char: Char): Long = when {
    char.isWhitespace() -> SPACE_STREAM_MS
    char.code < 128 -> LETTER_STREAM_MS
    else -> CJK_STREAM_MS
}

/** Releases one queued character once [elapsedMs] covers that character. A late frame does not catch up. */
internal fun advanceRevealed(
    revealed: Int,
    text: String,
    limit: Int,
    elapsedMs: Long,
): Int {
    if (revealed >= limit || revealed >= text.length) return revealed
    if (elapsedMs < typeDelayMs(text[revealed])) return revealed
    return revealed + 1
}

internal fun commonPrefixLength(left: String, right: String): Int {
    val limit = minOf(left.length, right.length)
    var i = 0
    while (i < limit && left[i] == right[i]) i++
    return i
}
