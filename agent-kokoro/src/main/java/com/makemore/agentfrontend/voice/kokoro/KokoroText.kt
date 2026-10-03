package com.makemore.agentfrontend.voice.kokoro

import java.text.Normalizer

/**
 * Python-compatible string helpers for the G2P port.
 *
 * The reference (tools/kokoro-assets/kokoro_ref) is Python, where strings are
 * sequences of Unicode code points. Every length, slice and per-character
 * test here works on code points (never UTF-16 units), and case mapping is
 * locale-independent (Kotlin `lowercase()`/`uppercase()` use Locale.ROOT).
 */
internal object Py {
    /** Python `str.isalpha` for one code point: general category L*. */
    fun isAlpha(cp: Int): Boolean = Character.isLetter(cp)

    /** Python `str.isalnum` for one code point: L* or N*. */
    fun isAlnum(cp: Int): Boolean {
        if (Character.isLetter(cp)) return true
        return when (Character.getType(cp)) {
            Character.DECIMAL_DIGIT_NUMBER.toInt(),
            Character.LETTER_NUMBER.toInt(),
            Character.OTHER_NUMBER.toInt() -> true
            else -> false
        }
    }

    /** Python `str.isalpha`: non-empty and every code point is a letter. */
    fun isAlpha(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!Character.isLetter(cp)) return false
            i += Character.charCount(cp)
        }
        return true
    }

    fun isAscii(s: String): Boolean = s.all { it.code < 128 }

    /** Code points of [s]. */
    fun cps(s: String): IntArray = s.codePoints().toArray()

    fun len(s: String): Int = s.codePointCount(0, s.length)

    /** Python `s[a:b]` with non-negative indices in code points (b clamped). */
    fun slice(s: String, from: Int, to: Int = Int.MAX_VALUE): String {
        val n = len(s)
        val a = from.coerceIn(0, n)
        val b = to.coerceIn(a, n)
        if (a == 0 && b == n) return s
        val start = s.offsetByCodePoints(0, a)
        val end = s.offsetByCodePoints(start, b - a)
        return s.substring(start, end)
    }

    /** Python `s[:-k]`. */
    fun dropLast(s: String, k: Int): String = slice(s, 0, len(s) - k)

    /** Python `s[k:]`. */
    fun drop(s: String, k: Int): String = slice(s, k)

    /** Python `s[-k]` as a code point (k >= 1). */
    fun fromEnd(s: String, k: Int): Int {
        var end = s.length
        var cp = 0
        repeat(k) {
            cp = s.codePointBefore(end)
            end -= Character.charCount(cp)
        }
        return cp
    }

    fun lower(s: String): String = s.lowercase()
    fun upper(s: String): String = s.uppercase()

    /** Python `str.capitalize` (keys are ASCII): first upper, rest lower. */
    fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        val first = s.codePointAt(0)
        val n = Character.charCount(first)
        return String(Character.toChars(first)).uppercase() + s.substring(n).lowercase()
    }

    fun cpString(cp: Int): String = String(Character.toChars(cp))

    /** Exactly the characters Python `str.split()` treats as whitespace. */
    fun isPyWhitespace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ',
        '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　' -> true
        else -> c in ' '..' '
    }

    /** Python `str.strip()` (no arguments). */
    fun strip(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isPyWhitespace(s[a])) a++
        while (b > a && isPyWhitespace(s[b - 1])) b--
        return s.substring(a, b)
    }

    fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

    /** café -> cafe: NFKD, drop U+0300..U+036F, NFC. */
    fun foldDiacritics(word: String): String {
        if (isAscii(word)) return word
        val d = Normalizer.normalize(word, Normalizer.Form.NFKD)
        val sb = StringBuilder(d.length)
        for (ch in d) if (ch.code !in 0x300..0x36F) sb.append(ch)
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }
}

/** Stage 1 of the pipeline (`prepare_text` in kokoro_ref/g2p.py). */
internal fun prepareText(text: String): String {
    // NFKC, but keep U+2026 (NFKC would turn it into "..."; it is a Kokoro token).
    val parts = text.split('…')
    var t = parts.joinToString("…") { Py.nfkc(it) }
    t = t.replace('‘', '\'').replace('’', '\'').replace('ʼ', '\'')
    // Collapse every whitespace run to one ASCII space, strip the ends.
    val sb = StringBuilder(t.length)
    var inWs = false
    for (c in t) {
        if (Py.isPyWhitespace(c)) {
            if (!inWs) sb.append(' ')
            inWs = true
        } else {
            sb.append(c)
            inWs = false
        }
    }
    return sb.toString().trim(' ')
}
