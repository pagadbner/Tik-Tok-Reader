package com.myclivebot.core

/**
 * Validates a raw cookie value without decoding or rewriting it.
 * Cookie values may contain percent-encoded or otherwise RFC-compatible punctuation.
 */
object TtwidCookieParser {
    fun validate(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.length in 8..1024 } ?: return null
        if (value.any { it.isWhitespace() || it == ';' || it == ',' || it.isISOControl() }) return null
        return value
    }

    fun fromCookieHeader(header: String?): String? =
        header.orEmpty()
            .split(';')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("ttwid=") }
            ?.substringAfter("ttwid=")
            ?.let(::validate)
}
