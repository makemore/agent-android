package com.makemore.agentfrontend.voice.kokoro

/**
 * A small, strict JSON reader for the Kokoro asset files (manifest, vocab,
 * voices and the multi-megabyte G2P dictionaries).
 *
 * It exists so the pure-Kotlin parts of the engine run identically on the
 * device and in JVM unit tests (Android's `org.json` is a stub on the JVM)
 * without another dependency, and because it parses the dictionaries several
 * times faster than a generic tree parser.
 *
 * Values: `Map<String, Any?>` (insertion ordered), `List<Any?>`, `String`,
 * `Long` or `Double`, `Boolean`, `null`.
 */
internal object KokoroJson {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val value = p.value()
        p.skipWs()
        if (p.i != text.length) throw p.error("trailing data")
        return value
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw ParseException("expected a JSON object")

    private class Parser(val s: String) {
        var i = 0

        fun error(what: String) = ParseException("JSON: $what at offset $i")

        fun skipWs() {
            while (i < s.length) {
                when (s[i]) {
                    ' ', '\n', '\r', '\t' -> i++
                    else -> return
                }
            }
        }

        fun value(): Any? {
            if (i >= s.length) throw error("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw error("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw error("bad literal")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (i < s.length && s[i] == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw error("expected a key")
                val key = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw error("expected ':'")
                i++
                skipWs()
                out[key] = value()
                skipWs()
                if (i >= s.length) throw error("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (i < s.length && s[i] == ']') {
                i++
                return out
            }
            while (true) {
                skipWs()
                out += value()
                skipWs()
                if (i >= s.length) throw error("unexpected end")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        fun str(): String {
            i++ // opening quote
            val start = i
            // Fast path: no escapes.
            while (i < s.length) {
                val c = s[i]
                if (c == '"') {
                    return s.substring(start, i++)
                }
                if (c == '\\') break
                if (c < ' ') throw error("control character in string")
                i++
            }
            val sb = StringBuilder().append(s, start, i)
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw error("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw error("bad \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw error("bad \\u escape")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw error("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw error("control character in string")
                    else -> sb.append(c)
                }
            }
            throw error("unterminated string")
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (t == "-" || t.isEmpty()) throw error("bad number")
            return if (isInt) t.toLongOrNull() ?: t.toDouble() else t.toDouble()
        }
    }
}

// -- typed accessors used by the asset readers ---------------------------

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?> =
    this[key] as? Map<String, Any?> ?: throw KokoroJson.ParseException("missing object '$key'")

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.list(key: String): List<Any?> =
    this[key] as? List<Any?> ?: throw KokoroJson.ParseException("missing list '$key'")

internal fun Map<String, Any?>.string(key: String): String =
    this[key] as? String ?: throw KokoroJson.ParseException("missing string '$key'")

internal fun Map<String, Any?>.long(key: String): Long =
    (this[key] as? Number)?.toLong() ?: throw KokoroJson.ParseException("missing number '$key'")
