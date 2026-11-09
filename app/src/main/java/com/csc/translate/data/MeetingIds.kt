package com.csc.translate.data

import java.security.SecureRandom
import java.util.Random

object Passcodes {
    private val pattern = Regex("^[A-Za-z0-9]{4,12}$")

    fun normalize(raw: String): String? {
        val cleaned = raw.trim()
        if (cleaned.isEmpty()) return ""
        if (!pattern.matches(cleaned)) return null
        return cleaned
    }
}

object MeetingIds {
    const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    const val LENGTH = 8

    fun generate(random: Random = SecureRandom()): String = buildString(LENGTH) {
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    fun isValid(raw: String): Boolean = canonical(raw)?.length == LENGTH

    fun canonical(raw: String): String? {
        val cleaned = raw.trim().lowercase().replace("-", "")
        if (cleaned.length != LENGTH) return null
        if (cleaned.any { it !in ALPHABET }) return null
        return cleaned
    }

    fun format(raw: String): String {
        val id = canonical(raw) ?: return raw.trim()
        return id.take(4) + "-" + id.drop(4)
    }
}
