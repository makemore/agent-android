package com.makemore.agentfrontend.voice.kokoro

import java.math.BigInteger
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Number and abbreviation normalisation: a line-by-line port of
 * tools/kokoro-assets/kokoro_ref/normalize.py (the canonical spec; the golden
 * vectors pin its output). Turns numbers, money, times, dates, percentages,
 * fractions and ranges into words, so no ASCII digit reaches the G2P.
 *
 * Java regex differences from Python handled here: group names cannot
 * contain `_` (renamed to camelCase), and "match at position p" is
 * `region(p, n)` + transparent bounds + `lookingAt()`.
 */
internal object KokoroNormalizer {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
        "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = listOf(
        1_000_000_000_000L to "trillion", 1_000_000_000L to "billion",
        1_000_000L to "million", 1_000L to "thousand",
    )
    const val MAX_CARDINAL = 999_999_999_999_999L
    private val MAX_CARDINAL_BIG = BigInteger.valueOf(MAX_CARDINAL)

    private val MONTHS = listOf(
        "January", "February", "March", "April", "May", "June", "July",
        "August", "September", "October", "November", "December",
    )
    // Insertion order matters only for building the alternation (as in Python).
    private val MONTH_ABBR = linkedMapOf(
        "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4, "Jun" to 6, "Jul" to 7, "Aug" to 8,
        "Sep" to 9, "Sept" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
    )
    private val SCALE_SUFFIX = mapOf("k" to "thousand", "m" to "million", "b" to "billion", "bn" to "billion")

    private class Currency(val major1: String, val majorN: String, val minor1: String, val minorN: String)
    private val CURRENCIES = mapOf(
        "$" to Currency("dollar", "dollars", "cent", "cents"),
        "£" to Currency("pound", "pounds", "pence", "pence"),
        "€" to Currency("euro", "euros", "cent", "cents"),
    )
    private val ORDINAL_WORDS = mapOf(
        "one" to "first", "two" to "second", "three" to "third", "five" to "fifth",
        "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
    )

    // ------------------------------------------------------------ number words

    fun below100(n: Int): String {
        if (n < 20) return ONES[n]
        val t = n / 10
        val o = n % 10
        return if (o == 0) TENS[t] else "${TENS[t]} ${ONES[o]}"
    }

    fun below1000(n: Int): String {
        val h = n / 100
        val r = n % 100
        val parts = ArrayList<String>(2)
        if (h != 0) parts += "${ONES[h]} hundred"
        if (r != 0) parts += below100(r)
        return parts.joinToString(" ")
    }

    fun cardinal(value: Long): String {
        require(value in 0..MAX_CARDINAL) { "out of range" }
        if (value == 0L) return "zero"
        var n = value
        val parts = ArrayList<String>()
        for ((scale, name) in SCALES) {
            if (n >= scale) {
                val q = n / scale
                n %= scale
                parts += "${below1000(q.toInt())} $name"
            }
        }
        if (n != 0L) parts += below1000(n.toInt())
        return parts.joinToString(" ")
    }

    fun digits(s: String): String = s.map { ONES[it - '0'] }.joinToString(" ")

    private fun rpartitionSpace(words: String): Pair<String, String> {
        val i = words.lastIndexOf(' ')
        return if (i < 0) "" to words else words.substring(0, i) to words.substring(i + 1)
    }

    fun toOrdinal(words: String): String {
        val (head, lastIn) = rpartitionSpace(words)
        val last = when {
            lastIn in ORDINAL_WORDS -> ORDINAL_WORDS.getValue(lastIn)
            lastIn.endsWith("y") -> lastIn.dropLast(1) + "ieth"
            else -> lastIn + "th"
        }
        return if (head.isNotEmpty()) "$head $last" else last
    }

    fun toPlural(words: String): String {
        val (head, lastIn) = rpartitionSpace(words)
        val last = when {
            lastIn.endsWith("y") -> lastIn.dropLast(1) + "ies"
            lastIn.endsWith("s") || lastIn.endsWith("x") -> lastIn + "es"
            else -> lastIn + "s"
        }
        return if (head.isNotEmpty()) "$head $last" else last
    }

    fun year(n: Int): String {
        val hi = n / 100
        val lo = n % 100
        if (hi % 10 == 0 && lo < 10) return cardinal(n.toLong())
        if (lo == 0) return "${below100(hi)} hundred"
        if (lo < 10) return "${below100(hi)} oh ${ONES[lo]}"
        return "${below100(hi)} ${below100(lo)}"
    }

    fun integer(s: String): String {
        if (',' in s) return plainInteger(s)
        if (s.length > 1 && s[0] == '0') return digits(s)
        if (s.length == 4) return year(s.toInt())
        if (s.length > 15) return digits(s)
        return cardinal(s.toLong())
    }

    fun plainInteger(s: String): String {
        if (',' in s) {
            val stripped = s.replace(",", "")
            val v = BigInteger(stripped)
            return if (v <= MAX_CARDINAL_BIG) cardinal(v.toLong()) else digits(stripped)
        }
        if (s.length > 1 && s[0] == '0') return digits(s)
        if (s.length > 15) return digits(s)
        return cardinal(s.toLong())
    }

    fun decimal(intPart: String, frac: String, yearOk: Boolean = false): String {
        val head = if (intPart.isNotEmpty()) (if (yearOk) integer(intPart) else plainInteger(intPart)) else ""
        val tail = "point " + digits(frac)
        return if (head.isNotEmpty()) "$head $tail" else tail
    }

    fun number(intPart: String, frac: String?, yearOk: Boolean = true): String {
        if (frac != null) return decimal(intPart, frac)
        return if (yearOk) integer(intPart) else plainInteger(intPart)
    }

    // ------------------------------------------------------------ the rules

    private const val W = "A-Za-z0-9_"
    private const val B = "(?<![$W])"
    private const val E = "(?![$W])"
    private fun neg(name: String) = "(?:(?<![$W.])(?<$name>[-−]))?"
    private const val INT = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)"
    private val MONTH_NAMES = MONTHS.joinToString("|")
    private val MONTH_ABBRS = MONTH_ABBR.keys.sortedByDescending { it.length }.joinToString("|")
    private val MONTH_RE = "(?:$MONTH_NAMES|(?:$MONTH_ABBRS)\\.?)"
    private const val AMPM_RE = "(?:[aApP]\\.?[mM]$E)"

    private class UnitWords(val one: String, val many: String)
    private val UNITS = mapOf(
        "km/h" to UnitWords("kilometer per hour", "kilometers per hour"),
        "mph" to UnitWords("mile per hour", "miles per hour"),
        "km" to UnitWords("kilometer", "kilometers"), "cm" to UnitWords("centimeter", "centimeters"),
        "mm" to UnitWords("millimeter", "millimeters"),
        "kg" to UnitWords("kilogram", "kilograms"), "mg" to UnitWords("milligram", "milligrams"),
        "lbs" to UnitWords("pound", "pounds"), "lb" to UnitWords("pound", "pounds"), "oz" to UnitWords("ounce", "ounces"),
        "ml" to UnitWords("milliliter", "milliliters"), "mL" to UnitWords("milliliter", "milliliters"),
        "kWh" to UnitWords("kilowatt hour", "kilowatt hours"),
        "KB" to UnitWords("kilobyte", "kilobytes"), "MB" to UnitWords("megabyte", "megabytes"),
        "GB" to UnitWords("gigabyte", "gigabytes"), "TB" to UnitWords("terabyte", "terabytes"),
        "GHz" to UnitWords("gigahertz", "gigahertz"), "MHz" to UnitWords("megahertz", "megahertz"),
        "kHz" to UnitWords("kilohertz", "kilohertz"), "Hz" to UnitWords("hertz", "hertz"),
        "°C" to UnitWords("degree Celsius", "degrees Celsius"), "°F" to UnitWords("degree Fahrenheit", "degrees Fahrenheit"),
    )
    // Longest first, ties in code point order (all BMP, so String order is code point order).
    private val UNITS_RE = UNITS.keys
        .sortedWith(compareBy<String>({ -it.codePointCount(0, it.length) }, { it }))
        .joinToString("|") { Pattern.quote(it) }
    private const val NUM_END = "(?![0-9])(?!\\.[0-9])(?!,[0-9]{3})"

    private enum class Kind { ISO_DATE, SLASH_DATE, MONTH_DAY, DAY_MONTH, TIME, HOUR_AMPM, CURRENCY, PERCENT, UNIT, PLURAL, ORDINAL, FRACTION, GROUPS, DOTTED, TWO, NUMBER, DIGITS }

    private val RULES: List<Pair<Kind, Pattern>> = listOf(
        Kind.ISO_DATE to "$B(?<isoY>[0-9]{4})-(?<isoM>[0-9]{2})-(?<isoD>[0-9]{2})$E",
        Kind.SLASH_DATE to "$B(?<sdA>[0-9]{1,2})/(?<sdB>[0-9]{1,2})/(?<sdY>[0-9]{4}|[0-9]{2})$E",
        Kind.MONTH_DAY to "$B(?<mdM>$MONTH_RE) (?<mdD>[0-9]{1,2})(?:st|nd|rd|th)?$E(?![:0-9])",
        Kind.DAY_MONTH to "$B(?<dmD>[0-9]{1,2})(?:st|nd|rd|th)? (?:of )?(?<dmM>$MONTH_RE)(?![A-Za-z])",
        Kind.TIME to "$B(?<tH>[0-9]{1,2}):(?<tM>[0-9]{2})(?::(?<tS>[0-9]{2}))?(?:(?: ?(?<tAp>$AMPM_RE))|$E(?!:[0-9]))",
        Kind.HOUR_AMPM to "$B(?<hH>[0-9]{1,2}) ?(?<hAp>$AMPM_RE)",
        Kind.CURRENCY to neg("cNeg") + "(?<cSym>[\$£€])(?<cInt>$INT)(?:\\.(?<cFrac>[0-9]+))?$NUM_END" +
            "(?: (?<cScale>thousand|million|billion|trillion)$E|(?<cSfx>bn|[kKmMbB])$E)?",
        Kind.PERCENT to neg("pNeg") + "(?<pInt>$INT)?(?:\\.(?<pFrac>[0-9]+))? ?%",
        Kind.UNIT to neg("uNeg") + "(?<uInt>$INT)(?:\\.(?<uFrac>[0-9]+))?$NUM_END ?(?<u>$UNITS_RE)$E",
        Kind.PLURAL to "(?:'|$B)(?<pl>[0-9]+)'?s$E",
        Kind.ORDINAL to "$B(?<oN>$INT)(?:st|nd|rd|th|ST|ND|RD|TH)$E",
        Kind.FRACTION to "$B(?<![0-9]/)(?<fN>[0-9]+)/(?<fD>[0-9]+)$E(?!/)",
        Kind.GROUPS to "$B(?<g>[0-9]+(?:[-–][0-9]+)+)$E",
        Kind.DOTTED to "$B(?<dt>[0-9]+(?:\\.[0-9]+){2,})$E",
        Kind.TWO to "(?<=[A-Za-z])2(?=[A-Za-z])",
        Kind.NUMBER to neg("nNeg") + "(?:(?<nInt>$INT)(?:\\.(?<nFrac>[0-9]+))?|\\.(?<nLfrac>[0-9]+))$NUM_END",
        Kind.DIGITS to "(?<dg>[0-9]+)",
    ).map { (k, rx) -> k to Pattern.compile(rx) }

    private fun monthFrom(token: String): Int {
        val t = token.trimEnd('.')
        val i = MONTHS.indexOf(t)
        return if (i >= 0) i + 1 else MONTH_ABBR.getValue(t)
    }

    /** "the ", unless the 4 code points before the match are "the " (any case). */
    private fun the(text: String, start: Int): String {
        val back = minOf(4, text.codePointCount(0, start))
        val from = text.offsetByCodePoints(start, -back)
        return if (text.substring(from, start).lowercase() == "the ") "" else "the "
    }

    private fun date(month: Int, day: Int, yearStr: String?, lang: String, the: String): String {
        val m = MONTHS[month - 1]
        val d = toOrdinal(cardinal(day.toLong()))
        val y = yearStr?.let { if (it.length == 4) year(it.toInt()) else twoDigitYear(it) }
        val out = if (lang == "en-gb") "$the$d of $m" else "$m $d"
        return if (!y.isNullOrEmpty()) "$out, $y" else out
    }

    private fun twoDigitYear(s: String): String =
        if (s[0] == '0') "oh ${ONES[s[1] - '0']}" else below100(s.toInt())

    private fun validMd(m: Int, d: Int) = m in 1..12 && d in 1..31

    private fun ampm(s: String): String = if (s[0] == 'a' || s[0] == 'A') "a.m" else "p.m"

    private fun big(s: String) = BigInteger(s.replace(",", ""))

    private fun Matcher.req(name: String): String = group(name) ?: error("group $name did not match")
    private fun Matcher.opt(name: String): String? = group(name)

    private fun unit(m: Matcher, lang: String): String {
        val u = UNITS.getValue(m.req("u"))
        var one = u.one
        var many = u.many
        if (lang == "en-gb") {
            one = one.replace("meter", "metre").replace("liter", "litre")
            many = many.replace("meter", "metre").replace("liter", "litre")
        }
        val neg = if (m.opt("uNeg") != null) "minus " else ""
        val singular = m.req("uInt") == "1" && m.opt("uFrac") == null
        return "$neg${number(m.req("uInt"), m.opt("uFrac"), yearOk = false)} ${if (singular) one else many}"
    }

    private fun currency(m: Matcher): String {
        val c = CURRENCIES.getValue(m.req("cSym"))
        val intS = m.req("cInt")
        val frac: String? = m.opt("cFrac")
        var scale: String? = m.opt("cScale")
        m.opt("cSfx")?.let { scale = SCALE_SUFFIX.getValue(it.lowercase()) }
        val neg = if (m.opt("cNeg") != null) "minus " else ""
        if (scale != null) {
            return "$neg${number(intS, frac, yearOk = false)} $scale ${c.majorN}"
        }
        if (frac != null && frac.length <= 2) {
            val major = big(intS)
            val minor = frac.padEnd(2, '0').toInt()
            val parts = ArrayList<String>(2)
            if (major.signum() != 0 || minor == 0) {
                parts += "${plainInteger(intS)} ${if (major == BigInteger.ONE) c.major1 else c.majorN}"
            }
            if (minor != 0) parts += "${cardinal(minor.toLong())} ${if (minor == 1) c.minor1 else c.minorN}"
            return neg + parts.joinToString(" and ")
        }
        if (frac != null) return "$neg${decimal(intS, frac)} ${c.majorN}"
        val major = big(intS)
        return "$neg${plainInteger(intS)} ${if (major == BigInteger.ONE) c.major1 else c.majorN}"
    }

    private fun groups(s: String): String {
        val parts = s.replace('–', '-').split("-")
        val phoneLike = parts.size >= 3 ||
            parts.any { it.length > 1 && it[0] == '0' } ||
            (parts[0].length == 3 && parts[1].length == 4)
        if (phoneLike) return parts.joinToString(", ") { digits(it) }
        return "${integer(parts[0])} to ${integer(parts[1])}"
    }

    /** Words for this match, or null to decline (the scanner tries the next rule). */
    private fun replace(kind: Kind, m: Matcher, text: String, lang: String): String? {
        when (kind) {
            Kind.ISO_DATE -> {
                val y = m.req("isoY").toInt()
                val mo = m.req("isoM").toInt()
                val d = m.req("isoD").toInt()
                if (!validMd(mo, d) || y < 1000) return null
                return date(mo, d, m.req("isoY"), lang, the(text, m.start()))
            }
            Kind.SLASH_DATE -> {
                val a = m.req("sdA").toInt()
                val b = m.req("sdB").toInt()
                val orders = if (lang == "en-gb") listOf(b to a, a to b) else listOf(a to b, b to a)
                for ((mo, d) in orders) {
                    if (validMd(mo, d)) return date(mo, d, m.req("sdY"), lang, the(text, m.start()))
                }
                return null
            }
            Kind.MONTH_DAY -> {
                val d = m.req("mdD").toInt()
                if (d !in 1..31) return null
                return "${MONTHS[monthFrom(m.req("mdM")) - 1]} ${toOrdinal(cardinal(d.toLong()))}"
            }
            Kind.DAY_MONTH -> {
                val d = m.req("dmD").toInt()
                if (d !in 1..31) return null
                return "${the(text, m.start())}${toOrdinal(cardinal(d.toLong()))} of ${MONTHS[monthFrom(m.req("dmM")) - 1]}"
            }
            Kind.TIME -> {
                val h = m.req("tH").toInt()
                val mi = m.req("tM").toInt()
                val ap: String? = m.opt("tAp")
                val sec: Int? = m.opt("tS")?.toInt()
                if (h > 23 || mi > 59 || (sec != null && sec > 59)) return null
                var words = below100(h)
                if (mi == 0) {
                    if (sec != null) words += " hundred" else if (ap.isNullOrEmpty()) words += " o'clock"
                } else if (mi < 10) {
                    words += " oh ${ONES[mi]}"
                } else {
                    words += " ${below100(mi)}"
                }
                if (sec != null && sec != 0) words += " and ${below100(sec)} ${if (sec == 1) "second" else "seconds"}"
                return if (!ap.isNullOrEmpty()) "$words ${ampm(ap)}" else words
            }
            Kind.HOUR_AMPM -> {
                val h = m.req("hH").toInt()
                if (h !in 1..12) return null
                return "${below100(h)} ${ampm(m.req("hAp"))}"
            }
            Kind.CURRENCY -> return currency(m)
            Kind.UNIT -> return unit(m, lang)
            Kind.PERCENT -> {
                val pInt: String? = m.opt("pInt")
                val pFrac: String? = m.opt("pFrac")
                if (pInt == null && pFrac == null) return null
                val neg = if (m.opt("pNeg") != null) "minus " else ""
                return "$neg${number(pInt ?: "", pFrac, yearOk = false)} percent"
            }
            Kind.PLURAL -> return toPlural(integer(m.req("pl")))
            Kind.ORDINAL -> {
                val n = big(m.req("oN"))
                if (n > MAX_CARDINAL_BIG) return null
                return toOrdinal(cardinal(n.toLong()))
            }
            Kind.FRACTION -> {
                val n = BigInteger(m.req("fN"))
                val d = BigInteger(m.req("fD"))
                if (d.signum() == 0 || n > MAX_CARDINAL_BIG || d > MAX_CARDINAL_BIG) return null
                val num = cardinal(n.toLong())
                val dl = d.toLong()
                val one = n == BigInteger.ONE
                val den = when {
                    dl == 2L -> if (one) "half" else "halves"
                    dl == 4L -> if (one) "quarter" else "quarters"
                    dl in 3L..10L -> toOrdinal(cardinal(dl)).let { if (one) it else it + "s" }
                    else -> return "$num over ${cardinal(dl)}"
                }
                return "$num $den"
            }
            Kind.GROUPS -> return groups(m.req("g"))
            Kind.DOTTED -> return m.req("dt").split(".").joinToString(" point ") { plainInteger(it) }
            Kind.TWO -> return "to"
            Kind.NUMBER -> {
                val neg = if (m.opt("nNeg") != null) "minus " else ""
                m.opt("nLfrac")?.let { return "${neg}point ${digits(it)}" }
                return neg + number(m.req("nInt"), m.opt("nFrac"))
            }
            Kind.DIGITS -> return integer(m.req("dg"))
        }
    }

    /** One left-to-right scan; the first rule (in order) that matches at p and yields words wins. */
    fun normalizeNumbers(text: String, lang: String = "en-us"): String {
        require(lang == "en-us" || lang == "en-gb") { "unsupported language" }
        val n = text.length
        val matchers = RULES.map { (kind, rx) ->
            kind to rx.matcher(text).useTransparentBounds(true).useAnchoringBounds(false)
        }
        val out = StringBuilder(n + 16)
        var p = 0
        outer@ while (p < n) {
            for ((kind, m) in matchers) {
                m.region(p, n)
                if (!m.lookingAt() || m.end() == m.start()) continue
                var words = replace(kind, m, text, lang) ?: continue
                if (p > 0) {
                    val before = text.codePointBefore(p)
                    if (Py.isAlnum(before)) {
                        words = " $words"
                    } else if (before == '-'.code && p >= 2) {
                        val beforeDash = text.codePointBefore(p - 1)
                        if (Py.isAlpha(beforeDash)) words = " $words" // COVID-19 -> COVID- nineteen
                    }
                }
                val end = m.end()
                if (end < n && Py.isAlnum(text.codePointAt(end))) words = "$words "
                out.append(words)
                p = end
                continue@outer
            }
            val cp = text.codePointAt(p)
            out.appendCodePoint(cp)
            p += Character.charCount(cp)
        }
        return out.toString()
    }

    // ------------------------------------------------------------ abbreviations

    private val TITLES = mapOf(
        "Mr" to "Mister", "Mrs" to "Mrs", "Ms" to "Mizz", "Dr" to "Doctor", "Prof" to "Professor",
        "St" to "Saint", "Mt" to "Mount", "Ft" to "Fort", "Gen" to "General", "Sgt" to "Sergeant",
        "Capt" to "Captain", "Lt" to "Lieutenant", "Col" to "Colonel", "Rev" to "Reverend",
        "Hon" to "Honorable", "Gov" to "Governor", "Sen" to "Senator", "Rep" to "Representative",
        "Pres" to "President",
    )
    private val TITLE_ELSE = mapOf("St" to "Street", "Dr" to "Drive")
    private val SUFFIX_ABBR = mapOf(
        "Jr" to "Junior", "Sr" to "Senior", "Inc" to "Incorporated", "Ltd" to "Limited",
        "Corp" to "Corporation", "Co" to "Company", "Bros" to "Brothers", "Ave" to "Avenue",
        "Blvd" to "Boulevard", "Rd" to "Road", "approx" to "approximately",
        "dept" to "department", "Dept" to "Department", "est" to "established",
        "fig" to "figure", "Fig" to "Figure", "vs" to "versus", "etc" to "etc",
    )
    private val ABBR_RE: Pattern = Pattern.compile(
        "(?<![A-Za-z0-9_.])(?<a>" +
            (TITLES.keys + SUFFIX_ABBR.keys).sortedByDescending { it.length }.joinToString("|") + ")\\.",
    )
    private val NUMBER_ABBR_RE: Pattern = Pattern.compile("(?<![A-Za-z0-9_.])(?<a>No|no)\\. ?(?=[0-9])")

    private inline fun replaceAll(pattern: Pattern, text: String, repl: (Matcher) -> String): String {
        val m = pattern.matcher(text)
        val sb = StringBuilder(text.length + 16)
        var last = 0
        while (m.find()) {
            sb.append(text, last, m.start())
            sb.append(repl(m))
            last = m.end()
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }

    fun expandAbbreviations(input: String): String {
        val text = replaceAll(NUMBER_ABBR_RE, input) { m -> if (m.req("a") == "No") "Number " else "number " }
        return replaceAll(ABBR_RE, text) { m ->
            val a = m.req("a")
            val atEnd = m.end() == text.length
            val nextCap = m.end() + 1 < text.length && text[m.end()] == ' ' && text[m.end() + 1] in 'A'..'Z'
            if (a in TITLES) {
                val word = if (nextCap || a !in TITLE_ELSE) TITLES.getValue(a) else TITLE_ELSE.getValue(a)
                word + if (atEnd) "." else ""
            } else {
                SUFFIX_ABBR.getValue(a) + if (atEnd || nextCap) "." else ""
            }
        }
    }

    /** Stage 2 of the pipeline: abbreviations, then numbers. */
    fun normalize(text: String, lang: String = "en-us"): String =
        normalizeNumbers(expandAbbreviations(text), lang)
}
