package com.makemore.agentfrontend.voice.kokoro

import java.util.regex.Pattern

/*
 * English text -> Kokoro phonemes. A function-by-function port of
 * tools/kokoro-assets/kokoro_ref/g2p.py, which is itself derived from misaki
 * (https://github.com/hexgrad/misaki, misaki/en.py at commit
 * fba1236595f2d2bf21d414ba6e57d25256afada3, Copyright hexgrad, Apache-2.0).
 * The Python reference and the golden vectors are the spec; keep this file
 * in step with them.
 */

// ----------------------------------------------------------------- constants

private fun cpSet(s: String): Set<Int> = s.codePoints().toArray().toHashSet()

private val DIPHTHONGS = cpSet("AIOQWYʤʧ")
internal val SUBTOKEN_JUNKS = cpSet("',-._‘’/")
private val PUNCTS = cpSet(";:,.!?—…\"“”")
private val NON_QUOTE_PUNCTS = PUNCTS - cpSet("\"“”")
private val CONSONANTS = cpSet("bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ")
private val US_TAUS = cpSet("AIOWYiuæɑəɛɪɹʊʌ")
private val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
private const val PRIMARY_STRESS = "ˈ"
private const val SECONDARY_STRESS = "ˌ"
private val STRESS_CPS = cpSet("ˌˈ")
private val VOWELS = cpSet("AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ")

private val SUBTOKEN_REGEX: Pattern = Pattern.compile(
    "^['‘’]+|\\p{Lu}(?=\\p{Lu}\\p{Ll})|(?:^-)?(?:[0-9]?[,.]?[0-9])+|[-_]+|['‘’]{2,}" +
        "|\\p{L}*?(?:['‘’]\\p{L})*?\\p{Ll}(?=\\p{Lu})|\\p{L}+(?:['‘’]\\p{L})*" +
        "|[^-_\\p{L}'‘’0-9]|['‘’]+$",
)
private val DASHES_ONLY: Pattern = Pattern.compile("-{2,}")
private val VS_RE: Pattern = Pattern.compile("vs\\.?", Pattern.CASE_INSENSITIVE)
private val ING_DOUBLE_RE: Pattern = Pattern.compile("([bcdgklmnprstvxz])\\1ing$|cking$")
private val LOWER_RE: Pattern = Pattern.compile("\\p{Ll}")
private val LETTER_RE: Pattern = Pattern.compile("\\p{L}")

private val JUNK_CHARS = cpSet("-_'/")
private val DASH_CHUNKS = cpSet("-–—")
private val OPEN_BRACKETS = cpSet("([{")
private val CLOSE_BRACKETS = cpSet(")]}")

private val NOUN_TRIGGERS = setOf(
    "the", "a", "an", "this", "that", "these", "those", "my", "your", "his",
    "her", "its", "our", "their", "no", "every", "each", "some", "any",
    "another", "which", "whose",
)
private val VERB_TRIGGERS = setOf(
    "to", "will", "would", "can", "could", "should", "must", "might", "may",
    "shall", "do", "does", "did", "don't", "doesn't", "didn't", "won't",
    "wouldn't", "can't", "cannot", "couldn't", "shouldn't", "mustn't",
    "let's", "please", "i'll", "you'll", "we'll", "they'll", "he'll", "she'll",
)
private val PERFECT_TRIGGERS = setOf(
    "have", "has", "had", "having", "i've", "you've", "we've", "they've",
    "haven't", "hasn't", "hadn't",
)
private val PRESENT_TRIGGERS = mapOf(
    "i" to "VBP", "you" to "VBP", "we" to "VBP", "they" to "VBP",
    "he" to "VBZ", "she" to "VBZ", "it" to "VBZ",
)
private val SENTENCE_END = cpSet(".!?…")
private val SUBJECT_STARTERS = setOf(
    "i", "you", "he", "she", "it", "we", "they", "the", "a", "an", "this", "these",
    "those", "my", "your", "his", "her", "its", "our", "their", "there", "someone",
    "something", "everyone", "everything", "nobody", "no",
)

private fun String.allCps(pred: (Int) -> Boolean): Boolean {
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        if (!pred(cp)) return false
        i += Character.charCount(cp)
    }
    return true
}

private fun String.anyCp(pred: (Int) -> Boolean): Boolean = !allCps { !pred(it) }

private fun String.hasAnyOf(set: Set<Int>): Boolean = anyCp { it in set }

// ----------------------------------------------------------------- tokens

internal class G2PToken(
    var text: String,
    var tag: String? = null,
    var whitespace: String = "",
    var phonemes: String? = null,
    var stress: Double? = null,
    var isHead: Boolean = true,
    var prespace: Boolean = false,
) {
    override fun toString(): String = "G2PToken($text, tag=$tag, ws='$whitespace', ps=$phonemes)"
}

internal class TokenContext(val futureVowel: Boolean? = null, val futureTo: Boolean = false)

/** misaki's `words` item: a single token (punctuation or one-subtoken word) or a multi-subtoken word. */
internal sealed class G2PItem {
    class Single(val token: G2PToken) : G2PItem()
    class Multi(val tokens: MutableList<G2PToken>) : G2PItem()

    val text: String
        get() = when (this) {
            is Single -> token.text
            is Multi -> tokens.joinToString("") { it.text }
        }

    /** A word (not punctuation): a list, or a token without phonemes. */
    val isWord: Boolean get() = this is Multi || (this as Single).token.phonemes == null

    val allTokens: List<G2PToken> get() = if (this is Single) listOf(token) else (this as Multi).tokens
}

internal fun mergeTokens(tokens: List<G2PToken>, unk: String? = null): G2PToken {
    val stress = LinkedHashSet<Double>()
    for (tk in tokens) tk.stress?.let { stress += it }
    val phonemes: String? = if (unk == null) {
        null
    } else {
        val sb = StringBuilder()
        for (tk in tokens) {
            if (tk.prespace && sb.isNotEmpty() && !Py.isPyWhitespace(sb[sb.length - 1]) && !tk.phonemes.isNullOrEmpty()) {
                sb.append(' ')
            }
            sb.append(tk.phonemes ?: unk)
        }
        sb.toString()
    }
    // misaki: tag of the token with the most "capital weight"; first wins ties.
    var best = tokens[0]
    var bestWeight = Int.MIN_VALUE
    for (tk in tokens) {
        var w = 0
        var i = 0
        while (i < tk.text.length) {
            val cp = tk.text.codePointAt(i)
            val s = Py.cpString(cp)
            w += if (s == s.lowercase()) 1 else 2
            i += Character.charCount(cp)
        }
        if (w > bestWeight) {
            best = tk
            bestWeight = w
        }
    }
    val text = StringBuilder()
    for (tk in tokens.dropLast(1)) text.append(tk.text).append(tk.whitespace)
    text.append(tokens.last().text)
    return G2PToken(
        text = text.toString(),
        tag = best.tag,
        whitespace = tokens.last().whitespace,
        phonemes = phonemes,
        stress = if (stress.size == 1) stress.first() else null,
        isHead = tokens[0].isHead,
        prespace = tokens[0].prespace,
    )
}

internal fun stressWeight(ps: String?): Int {
    if (ps.isNullOrEmpty()) return 0
    var sum = 0
    var i = 0
    while (i < ps.length) {
        val cp = ps.codePointAt(i)
        sum += if (cp in DIPHTHONGS) 2 else 1
        i += Character.charCount(cp)
    }
    return sum
}

/** Move the single leading stress mark to just before the first vowel (misaki's `restress`). */
private fun restress(ps: String): String {
    val cps = Py.cps(ps)
    // ps = mark + phonemes with no other marks, and at least one vowel.
    val markIndex = cps.indexOfFirst { it in STRESS_CPS }
    val vowelIndex = (markIndex until cps.size).first { cps[it] in VOWELS }
    // Sort by position, the mark taking position vowelIndex - 0.5.
    val out = StringBuilder()
    for (i in cps.indices) {
        if (i == markIndex) continue
        if (i == vowelIndex) out.appendCodePoint(cps[markIndex])
        out.appendCodePoint(cps[i])
    }
    return out.toString()
}

internal fun applyStress(ps: String?, stress: Double?): String? {
    if (stress == null || ps == null) return ps
    val hasPrimary = ps.contains(PRIMARY_STRESS)
    val hasSecondary = ps.contains(SECONDARY_STRESS)
    if (stress < -1) return ps.replace(PRIMARY_STRESS, "").replace(SECONDARY_STRESS, "")
    if (stress == -1.0 || ((stress == 0.0 || stress == -0.5) && hasPrimary)) {
        return ps.replace(SECONDARY_STRESS, "").replace(PRIMARY_STRESS, SECONDARY_STRESS)
    }
    if ((stress == 0.0 || stress == 0.5 || stress == 1.0) && !hasPrimary && !hasSecondary) {
        if (!ps.hasAnyOf(VOWELS)) return ps
        return restress(SECONDARY_STRESS + ps)
    }
    if (stress >= 1 && !hasPrimary && hasSecondary) return ps.replace(SECONDARY_STRESS, PRIMARY_STRESS)
    if (stress > 1 && !hasPrimary && !hasSecondary) {
        if (!ps.hasAnyOf(VOWELS)) return ps
        return restress(PRIMARY_STRESS + ps)
    }
    return ps
}

// ----------------------------------------------------------------- lexicon

/**
 * misaki `Lexicon.grow_dictionary`, applied when loading gold/silver. Keys are
 * ASCII. Originals win over grown keys.
 */
internal fun growDictionary(d: Map<String, Any?>): HashMap<String, Any?> {
    val out = HashMap<String, Any?>(d.size * 2 + 16)
    for ((k, v) in d) {
        if (k.length < 2) continue
        val lower = k.lowercase()
        if (k == lower) {
            val cap = Py.capitalize(k)
            if (k != cap) out[cap] = v
        } else if (k == Py.capitalize(lower)) {
            out[lower] = v
        }
    }
    out.putAll(d)
    return out
}

internal fun getParentTag(tag: String?): String? {
    if (tag == null) return null
    if (tag.startsWith("VB")) return "VERB"
    if (tag.startsWith("NN")) return "NOUN"
    if (tag.startsWith("ADV") || tag.startsWith("RB")) return "ADV"
    if (tag.startsWith("ADJ") || tag.startsWith("JJ")) return "ADJ"
    return tag
}

/**
 * Dictionary values: a phoneme `String`, `null` (known, no pronunciation at
 * this level), or (gold only) a `Map<String, String?>` of tag -> phonemes.
 */
internal class KokoroLexicon(
    private val british: Boolean,
    private val golds: Map<String, Any?>,
    private val silvers: Map<String, Any?>,
) {
    private val capStresses = doubleArrayOf(0.5, 2.0)

    @Suppress("UNCHECKED_CAST")
    private fun goldTagged(word: String, tag: String): String? = (golds[word] as Map<String, String?>)[tag]

    private fun goldString(word: String): String? = golds[word] as String?

    fun getNNP(word: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            if (!Py.isAlpha(cp)) continue
            val ps = golds[Py.cpString(cp).uppercase()] as? String ?: return null
            sb.append(ps)
        }
        val stressed = applyStress(sb.toString(), 0.0)!!
        val last = stressed.lastIndexOf(SECONDARY_STRESS)
        return if (last < 0) stressed else stressed.substring(0, last) + PRIMARY_STRESS + stressed.substring(last + 1)
    }

    fun getSpecialCase(word: String, tag: String?, stress: Double?, ctx: TokenContext): String? {
        SYMBOLS[word]?.let { return lookup(it, null, null, ctx) }
        if (word.trim('.').contains('.') && Py.isAlpha(word.replace(".", "")) &&
            word.split(".").maxOf { Py.len(it) } < 3
        ) {
            return getNNP(word)
        }
        if (word == "a" || word == "A") return if (tag == "DT") "ɐ" else "ˈA"
        if (word == "am" || word == "Am" || word == "AM") {
            if (tag != null && tag.startsWith("NN")) return getNNP(word)
            if (ctx.futureVowel == null || word != "am" || (stress != null && stress > 0)) return goldString("am")
            return "ɐm"
        }
        if (word == "an" || word == "An" || word == "AN") {
            if (word == "AN" && tag != null && tag.startsWith("NN")) return getNNP(word)
            return "ɐn"
        }
        if (word == "I" && tag == "PRP") return "${SECONDARY_STRESS}I"
        if (word == "to" || word == "To" || (word == "TO" && (tag == "TO" || tag == "IN"))) {
            return when (ctx.futureVowel) {
                null -> goldString("to")
                false -> "tə"
                true -> "tʊ"
            }
        }
        if (word == "in" || word == "In" || (word == "IN" && tag != "NNP")) {
            val mark = if (ctx.futureVowel == null || tag != "IN") PRIMARY_STRESS else ""
            return mark + "ɪn"
        }
        if (word == "the" || word == "The" || (word == "THE" && tag == "DT")) {
            return if (ctx.futureVowel == true) "ði" else "ðə"
        }
        if (tag == "IN" && VS_RE.matcher(word).matches()) return lookup("versus", null, null, ctx)
        if (word == "used" || word == "Used" || word == "USED") {
            if ((tag == "VBD" || tag == "JJ") && ctx.futureTo) return goldTagged("used", "VBD")
            return goldTagged("used", "DEFAULT")
        }
        return null
    }

    fun isKnown(word: String, tag: String?): Boolean {
        if (golds.containsKey(word) || SYMBOLS.containsKey(word) || silvers.containsKey(word)) return true
        if (!Py.isAlpha(word) || !word.all { it in 'A'..'Z' || it in 'a'..'z' || it == '\'' || it == '-' }) return false
        if (word.length == 1) return true
        if (word == word.uppercase() && golds.containsKey(word.lowercase())) return true
        val rest = Py.drop(word, 1)
        return rest == rest.uppercase()
    }

    fun lookup(wordIn: String, tagIn: String?, stress: Double?, ctx: TokenContext?): String? {
        var word = wordIn
        var tag = tagIn
        var isNNP: Boolean? = null
        if (word == word.uppercase() && !golds.containsKey(word)) {
            word = word.lowercase()
            isNNP = tag == "NNP"
        }
        var ps: Any? = golds[word]
        if (ps == null && isNNP != true) ps = silvers[word]
        if (ps is Map<*, *>) {
            if (ctx != null && ctx.futureVowel == null && ps.containsKey("None")) {
                tag = "None"
            } else if (tag == null || !ps.containsKey(tag)) {
                tag = getParentTag(tag)
            }
            ps = if (tag != null && ps.containsKey(tag)) ps[tag] else ps["DEFAULT"]
        }
        val str = ps as String?
        if (str == null || (isNNP == true && !str.contains(PRIMARY_STRESS))) {
            getNNP(word)?.let { return it }
        }
        return applyStress(str, stress)
    }

    private fun s(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = Py.fromEnd(stem, 1)
        if (last in cpSet("ptkfθ")) return stem + "s"
        if (last in cpSet("szʃʒʧʤ")) return stem + (if (british) "ɪ" else "ᵻ") + "z"
        return stem + "z"
    }

    fun stemS(word: String, tag: String?, stress: Double?, ctx: TokenContext): String? {
        val n = Py.len(word)
        if (n < 3 || !word.endsWith("s")) return null
        val stem = when {
            !word.endsWith("ss") && isKnown(Py.dropLast(word, 1), tag) -> Py.dropLast(word, 1)
            (word.endsWith("'s") || (n > 4 && word.endsWith("es") && !word.endsWith("ies"))) &&
                isKnown(Py.dropLast(word, 2), tag) -> Py.dropLast(word, 2)
            n > 4 && word.endsWith("ies") && isKnown(Py.dropLast(word, 3) + "y", tag) -> Py.dropLast(word, 3) + "y"
            else -> return null
        }
        return s(lookup(stem, tag, stress, ctx))
    }

    private fun ed(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        val last = Py.fromEnd(stem, 1)
        if (last in cpSet("pkfθʃsʧ")) return stem + "t"
        if (last == 'd'.code) return stem + (if (british) "ɪ" else "ᵻ") + "d"
        if (last != 't'.code) return stem + "d"
        if (british || Py.len(stem) < 2) return stem + "ɪd"
        if (Py.fromEnd(stem, 2) in US_TAUS) return Py.dropLast(stem, 1) + "ɾᵻd"
        return stem + "ᵻd"
    }

    fun stemEd(word: String, tag: String?, stress: Double?, ctx: TokenContext): String? {
        val n = Py.len(word)
        if (n < 4 || !word.endsWith("d")) return null
        val stem = when {
            !word.endsWith("dd") && isKnown(Py.dropLast(word, 1), tag) -> Py.dropLast(word, 1)
            n > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(Py.dropLast(word, 2), tag) ->
                Py.dropLast(word, 2)
            else -> return null
        }
        return ed(lookup(stem, tag, stress, ctx))
    }

    private fun ing(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        if (british) {
            val last = Py.fromEnd(stem, 1)
            if (last == 'ə'.code || last == 'ː'.code) return null
        } else if (Py.len(stem) > 1 && Py.fromEnd(stem, 1) == 't'.code && Py.fromEnd(stem, 2) in US_TAUS) {
            return Py.dropLast(stem, 1) + "ɾɪŋ"
        }
        return stem + "ɪŋ"
    }

    fun stemIng(word: String, tag: String?, stress: Double?, ctx: TokenContext): String? {
        val n = Py.len(word)
        if (n < 5 || !word.endsWith("ing")) return null
        val stem = when {
            n > 5 && isKnown(Py.dropLast(word, 3), tag) -> Py.dropLast(word, 3)
            isKnown(Py.dropLast(word, 3) + "e", tag) -> Py.dropLast(word, 3) + "e"
            n > 5 && ING_DOUBLE_RE.matcher(word).find() && isKnown(Py.dropLast(word, 4), tag) -> Py.dropLast(word, 4)
            else -> return null
        }
        return ing(lookup(stem, tag, stress, ctx))
    }

    fun getWord(wordIn: String, tag: String?, stress: Double?, ctx: TokenContext): String? {
        getSpecialCase(wordIn, tag, stress, ctx)?.let { return it }
        var word = wordIn
        val wl = word.lowercase()
        if (Py.len(word) > 1 && Py.isAlpha(word.replace("'", "")) && word != wl &&
            (tag != "NNP" || Py.len(word) > 7) && !golds.containsKey(word) && !silvers.containsKey(word) &&
            (word == word.uppercase() || Py.drop(word, 1) == Py.drop(word, 1).lowercase()) &&
            (golds.containsKey(wl) || silvers.containsKey(wl) ||
                !stemS(wl, tag, stress, ctx).isNullOrEmpty() ||
                !stemEd(wl, tag, stress, ctx).isNullOrEmpty() ||
                !stemIng(wl, tag, stress, ctx).isNullOrEmpty())
        ) {
            word = wl
        }
        if (isKnown(word, tag)) return lookup(word, tag, stress, ctx)
        if (word.endsWith("s'") && isKnown(Py.dropLast(word, 2) + "'s", tag)) {
            return lookup(Py.dropLast(word, 2) + "'s", tag, stress, ctx)
        }
        if (word.endsWith("'") && isKnown(Py.dropLast(word, 1), tag)) {
            return lookup(Py.dropLast(word, 1), tag, stress, ctx)
        }
        stemS(word, tag, stress, ctx)?.let { return it }
        stemEd(word, tag, stress, ctx)?.let { return it }
        stemIng(word, tag, stress ?: 0.5, ctx)?.let { return it }
        return null
    }

    operator fun invoke(tk: G2PToken, ctx: TokenContext): String? {
        var word = tk.text.replace('‘', '\'').replace('’', '\'')
        word = Py.foldDiacritics(Py.nfkc(word))
        val stress: Double? = if (word == word.lowercase()) null else capStresses[if (word == word.uppercase()) 1 else 0]
        val ps = getWord(word, tk.tag, stress, ctx) ?: return null
        return applyStress(ps, tk.stress)
    }
}

// ----------------------------------------------------------------- tokenizer

private fun hasAlnum(s: String): Boolean = s.anyCp { Py.isAlpha(it) || it in '0'.code..'9'.code }

private fun isLetters(s: String): Boolean = s.anyCp { Py.isAlpha(it) }

private class Quotes {
    var count = 0
    fun next(): String {
        count++
        return if (count % 2 == 1) "“" else "”"
    }
}

private fun punctPhonemes(text: String, quotes: Quotes): String {
    val out = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        i += Character.charCount(c)
        when {
            c == '"'.code -> out.append(quotes.next())
            c in PUNCTS -> out.appendCodePoint(c)
            c in OPEN_BRACKETS -> out.append('(')
            c in CLOSE_BRACKETS -> out.append(')')
            c == '–'.code || (c == '-'.code && !(out.isNotEmpty() && out[out.length - 1] == '—')) -> out.append('—')
        }
    }
    return out.toString()
}

private fun findAll(p: Pattern, s: String): List<String> {
    val m = p.matcher(s)
    val out = ArrayList<String>()
    while (m.find()) out += m.group()
    return out
}

/** Whitespace chunks -> misaki subtokens -> words (lists) and punctuation. */
internal fun tokenize(text: String): MutableList<G2PItem> {
    val chunks = ArrayList<String>()
    run {
        val sb = StringBuilder()
        for (c in text) {
            if (Py.isPyWhitespace(c)) {
                if (sb.isNotEmpty()) chunks += sb.toString()
                sb.setLength(0)
            } else {
                sb.append(c)
            }
        }
        if (sb.isNotEmpty()) chunks += sb.toString()
    }
    val quotes = Quotes()
    // Each element: a punctuation G2PToken, or a MutableList<G2PToken> (word).
    val items = ArrayList<Any>()
    for ((ci, chunk) in chunks.withIndex()) {
        val ws = if (ci < chunks.size - 1) " " else ""
        if (chunk.allCps { it in DASH_CHUNKS }) {
            items.add(G2PToken(chunk, whitespace = ws, phonemes = "—"))
            continue
        }
        val subs = findAll(SUBTOKEN_REGEX, chunk)
        val lastChunk = ci == chunks.size - 1
        val kinds = ArrayList<Boolean>(subs.size) // true = word part
        for ((si, s) in subs.withIndex()) {
            kinds += when {
                DASHES_ONLY.matcher(s).matches() -> false
                s in SYMBOLS || hasAlnum(s) || s.allCps { it in JUNK_CHARS } -> true
                s == "." -> {
                    val prevLetter = si > 0 && isLetters(subs[si - 1])
                    val nextLetter = si + 1 < subs.size && isLetters(subs[si + 1])
                    when {
                        prevLetter && nextLetter -> true
                        prevLetter && si == subs.size - 1 && !lastChunk && subs.dropLast(1).contains(".") -> true
                        else -> false
                    }
                }
                else -> false
            }
        }
        val toks = ArrayList<G2PToken>()
        val tkKinds = ArrayList<Boolean>()
        for ((s, isWord) in subs.zip(kinds)) {
            if (!isWord && tkKinds.isNotEmpty() && !tkKinds.last()) {
                toks.last().text += s
                continue
            }
            toks += G2PToken(s)
            tkKinds += isWord
        }
        if (toks.isEmpty()) continue
        toks.last().whitespace = ws
        for ((tk, isWord) in toks.zip(tkKinds)) {
            if (!isWord) {
                tk.phonemes = punctPhonemes(tk.text, quotes)
                items.add(tk)
            } else {
                val prev = items.lastOrNull()
                @Suppress("UNCHECKED_CAST")
                if (prev is MutableList<*> && (prev as MutableList<G2PToken>).last().whitespace.isEmpty()) {
                    tk.isHead = false
                    prev.add(tk)
                } else {
                    items.add(mutableListOf(tk))
                }
            }
        }
    }
    @Suppress("UNCHECKED_CAST")
    return items.mapTo(ArrayList()) { w ->
        when {
            w is G2PToken -> G2PItem.Single(w)
            (w as MutableList<G2PToken>).size == 1 -> G2PItem.Single(w[0])
            else -> G2PItem.Multi(w)
        }
    }
}

private fun hasLower(s: String): Boolean = LOWER_RE.matcher(s).find()

private fun letterCount(s: String): Int {
    val m = LETTER_RE.matcher(s)
    var n = 0
    while (m.find()) n++
    return n
}

/** Context tagger replacing spaCy (README "Context tags"). First rule that applies wins. */
internal fun assignTags(items: List<G2PItem>) {
    val words = items.filter { it.isWord }.map { it.text }
    val shouting = words.none { hasLower(it) } && words.count { letterCount(it) >= 2 } >= 2
    for ((i, item) in items.withIndex()) {
        if (!item.isWord) continue
        val text = item.text
        val prev = if (i > 0) items[i - 1] else null
        val nxt = if (i + 1 < items.size) items[i + 1] else null
        val prevWord = if (prev != null && prev.isWord) prev.text.lowercase() else null
        val nextWord = if (nxt != null && nxt.isWord) nxt.text else null
        val sentenceStart = prev == null ||
            (!prev.isWord && (prev as G2PItem.Single).token.phonemes!!.hasAnyOf(SENTENCE_END))
        val letters = letterCount(text)
        val tag: String? = when {
            text == "a" -> "DT"
            text == "A" -> if (nextWord != null && hasLower(nextWord)) "DT" else null
            text == "I" -> "PRP"
            text == "in" || text == "In" || text == "IN" -> "IN"
            text == "to" || text == "To" || text == "TO" -> "TO"
            text == "the" || text == "The" || text == "THE" -> "DT"
            VS_RE.matcher(text).matches() -> "IN"
            text == "used" || text == "Used" || text == "USED" -> "VBD"
            text.lowercase() == "that" || text.lowercase() == "that's" ->
                if (nextWord != null && nextWord.lowercase() in SUBJECT_STARTERS) null else "DT"
            !hasLower(text) -> if (shouting || letters < 2) null else "NN"
            sentenceStart && nextWord != null && nextWord.lowercase() in NOUN_TRIGGERS -> "VB"
            prevWord in NOUN_TRIGGERS -> "NN"
            prevWord in VERB_TRIGGERS -> "VB"
            prevWord in PERFECT_TRIGGERS -> "VBN"
            prevWord != null && prevWord in PRESENT_TRIGGERS -> PRESENT_TRIGGERS.getValue(prevWord)
            else -> null
        }
        for (tk in item.allTokens) tk.tag = tag
    }
}

// ----------------------------------------------------------------- G2P

internal fun tokenContext(ctx: TokenContext, ps: String?, token: G2PToken): TokenContext {
    var vowel = ctx.futureVowel
    if (!ps.isNullOrEmpty()) {
        var i = 0
        while (i < ps.length) {
            val c = ps.codePointAt(i)
            i += Character.charCount(c)
            if (c in VOWELS || c in CONSONANTS || c in NON_QUOTE_PUNCTS) {
                vowel = if (c in NON_QUOTE_PUNCTS) null else c in VOWELS
                break
            }
        }
    }
    val futureTo = token.text == "to" || token.text == "To" ||
        (token.text == "TO" && (token.tag == "TO" || token.tag == "IN"))
    return TokenContext(vowel, futureTo)
}

internal fun resolveTokens(tokens: List<G2PToken>) {
    val text = StringBuilder().apply {
        for (tk in tokens.dropLast(1)) append(tk.text).append(tk.whitespace)
        append(tokens.last().text)
    }.toString()
    val classes = HashSet<Int>()
    var i = 0
    while (i < text.length) {
        val c = text.codePointAt(i)
        i += Character.charCount(c)
        if (c in SUBTOKEN_JUNKS) continue
        classes += if (Py.isAlpha(c)) 0 else if (c in '0'.code..'9'.code) 1 else 2
    }
    val prespace = ' ' in text || '/' in text || classes.size > 1
    for ((idx, tk) in tokens.withIndex()) {
        if (tk.phonemes == null) {
            if (idx == tokens.size - 1 && Py.len(tk.text) == 1 && tk.text.codePointAt(0) in NON_QUOTE_PUNCTS) {
                tk.phonemes = tk.text
            } else if (tk.text.allCps { it in SUBTOKEN_JUNKS }) {
                tk.phonemes = ""
            }
        } else if (idx > 0) {
            tk.prespace = prespace
        }
    }
    if (prespace) return
    data class Idx(val primary: Boolean, val weight: Int, val i: Int)
    var indices = tokens.withIndex()
        .filter { !it.value.phonemes.isNullOrEmpty() }
        .map { Idx(it.value.phonemes!!.contains(PRIMARY_STRESS), stressWeight(it.value.phonemes), it.index) }
    if (indices.size == 2 && Py.len(tokens[indices[0].i].text) == 1) {
        val j = indices[1].i
        tokens[j].phonemes = applyStress(tokens[j].phonemes, -0.5)
        return
    }
    if (indices.size < 2 || indices.count { it.primary } <= (indices.size + 1) / 2) return
    indices = indices.sortedWith(compareBy<Idx>({ it.primary }, { it.weight }, { it.i })).take(indices.size / 2)
    for (x in indices) tokens[x.i].phonemes = applyStress(tokens[x.i].phonemes, -0.5)
}

/** Result of one segment: phonemes, the normalised text, merged tokens (for chunking) and BART words. */
internal class G2PResult(
    val phonemes: String,
    val normalized: String,
    val tokens: List<G2PToken>,
    val fallbackWords: List<String>,
)

/** Unknown-word fallback (the BART G2P). */
internal fun interface G2PFallback {
    fun phonemize(word: String): String
}

/** `G2P` in kokoro_ref/g2p.py. lang: `en-us` or `en-gb`. Not thread-safe. */
internal class KokoroG2P(
    val lang: String,
    golds: Map<String, Any?>,
    silvers: Map<String, Any?>,
    private val unknownWords: G2PFallback,
) {
    init {
        require(lang == "en-us" || lang == "en-gb") { "unsupported language" }
    }

    val lexicon = KokoroLexicon(lang == "en-gb", golds, silvers)

    private fun fallback(text: String, used: MutableList<String>): String {
        if (text.allCps { it in SUBTOKEN_JUNKS }) return ""
        val word = Py.foldDiacritics(Py.nfkc(text.replace('’', '\'').replace('‘', '\'')))
        used += word
        return unknownWords.phonemize(word)
    }

    /** One segment of text (callers split on newlines first). */
    operator fun invoke(input: String): G2PResult {
        val text = prepareText(input)
        val normalized = KokoroNormalizer.normalize(text, lang)
        val items = tokenize(normalized)
        assignTags(items)
        val used = ArrayList<String>()
        var ctx = TokenContext()
        for (item in items.asReversed()) {
            if (item is G2PItem.Single) {
                val w = item.token
                if (w.phonemes == null) w.phonemes = lexicon(w, ctx)
                if (w.phonemes == null) w.phonemes = fallback(w.text, used)
                ctx = tokenContext(ctx, w.phonemes, w)
                continue
            }
            val w = (item as G2PItem.Multi).tokens
            var left = 0
            var right = w.size
            var shouldFallback = false
            while (left < right) {
                val tk = if (w.subList(left, right).any { it.phonemes != null }) null else mergeTokens(w.subList(left, right))
                val ps = if (tk == null) null else lexicon(tk, ctx)
                if (ps != null) {
                    w[left].phonemes = ps
                    for (x in w.subList(left + 1, right)) x.phonemes = ""
                    ctx = tokenContext(ctx, ps, tk!!)
                    right = left
                    left = 0
                } else if (left + 1 < right) {
                    left += 1
                } else {
                    right -= 1
                    val t = w[right]
                    if (t.phonemes == null) {
                        if (t.text.allCps { it in SUBTOKEN_JUNKS }) {
                            t.phonemes = ""
                        } else {
                            shouldFallback = true
                            break
                        }
                    }
                    left = 0
                }
            }
            if (shouldFallback) {
                val tk = mergeTokens(w)
                w[0].phonemes = fallback(tk.text, used)
                for (j in 1 until w.size) w[j].phonemes = ""
            } else {
                resolveTokens(w)
            }
        }
        val flat = items.map { if (it is G2PItem.Multi) mergeTokens(it.tokens, unk = "") else (it as G2PItem.Single).token }
        for (tk in flat) {
            val p = tk.phonemes
            if (!p.isNullOrEmpty()) tk.phonemes = p.replace('ɾ', 'T').replace('ʔ', 't')
        }
        val sb = StringBuilder()
        for (tk in flat) sb.append(tk.phonemes ?: "").append(tk.whitespace)
        return G2PResult(Py.strip(sb.toString()), normalized, flat, used)
    }
}
