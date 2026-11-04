package com.csc.translate.session

internal const val MAX_UPLOAD_BYTES = 100L * 1024 * 1024

internal fun mimeFromFileName(name: String, resolverType: String?): String {
    val reported = resolverType?.trim().orEmpty()
    if (reported.isNotEmpty() && reported != "application/octet-stream" && '/' in reported) {
        return reported
    }
    return when (name.substringAfterLast('.', "").lowercase()) {
        "wav", "wave" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "aac", "mp4" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "webm" -> "audio/webm"
        "amr" -> "audio/amr"
        "3gp", "3gpp" -> "audio/3gpp"
        else -> reported.ifBlank { "application/octet-stream" }
    }
}

internal fun safeUploadFileName(name: String): String {
    val cleaned = buildString(name.length.coerceAtMost(80)) {
        for (c in name.trim()) {
            when {
                length >= 80 -> return@buildString
                c == '/' || c == '\\' || c == '\u0000' || c.isISOControl() -> append('_')
                else -> append(c)
            }
        }
    }
    return cleaned.ifBlank { "audio" }
}
