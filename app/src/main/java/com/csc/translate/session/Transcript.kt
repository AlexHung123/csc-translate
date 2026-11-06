package com.csc.translate.session

import com.csc.translate.net.ServerEvent

data class Transcript(
    val sourceRaw: String = "",
    val targetRaw: String = "",
) {
    val isEmpty: Boolean get() = sourceRaw.isBlank() && targetRaw.isBlank()
}

fun Transcript.applyEvent(event: ServerEvent): Transcript = when (event.event) {
    "chunk", "flush" -> {
        val nextTarget = when {
            event.fullText != null -> event.fullText
            else -> targetRaw + (event.text ?: "")
        }
        val nextSource = when {
            event.sourceTextFull != null -> event.sourceTextFull
            event.sourceText != null -> sourceRaw + event.sourceText
            else -> sourceRaw
        }
        copy(sourceRaw = nextSource, targetRaw = nextTarget)
    }
    "done" -> copy(
        sourceRaw = event.sourceText ?: sourceRaw,
        targetRaw = event.fullText ?: targetRaw,
    )
    else -> this
}

fun sourceForDisplay(raw: String, direction: String): String {
    val cased = if (direction != "en-zh") {
        raw
    } else if (raw != raw.uppercase() || raw == raw.lowercase()) {
        raw
    } else {
        val lower = raw.lowercase()
        val regex = Regex("(^|[.!?。！？]\\s*|\\n+)([^a-z0-9]*)([a-z])")
        regex.replace(lower) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3].uppercase()
        }
    }
    return ChineseDisplay.toTraditional(cased)
}

object TitleFormatter {
    private val endPunct = Regex("[。！？.!?\\n]")
    const val EMPTY = "No speech detected"

    fun title(source: String, target: String): String {
        firstMeaningful(source)?.let { return it }
        firstMeaningful(target)?.let { return it }
        return EMPTY
    }

    internal fun firstMeaningful(text: String): String? {
        val t = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        if (t.all { it.isWhitespace() || it in "。！？.!?,，、；;:：…—-" }) return null
        val idx = endPunct.find(t)?.range?.first ?: -1
        val sentence = if (idx > 0) t.substring(0, idx).trim() else t
        return sentence.take(40).ifBlank { null }
    }
}
