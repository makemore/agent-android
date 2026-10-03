package com.makemore.agentfrontend.voice.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Unit tests mirroring tools/kokoro-assets/tests/test_reference.py (no assets needed). */
class KokoroTextTest {
    private val n = KokoroNormalizer

    @Test
    fun cardinal() {
        assertEquals("zero", n.cardinal(0))
        assertEquals("fifteen", n.cardinal(15))
        assertEquals("one hundred one", n.cardinal(101))
        assertEquals("one million one", n.cardinal(1_000_001))
        assertEquals(listOf("nine", "hundred", "ninety"), n.cardinal(999_999_999_999_999).split(" ").take(3))
        assertTrue(runCatching { n.cardinal(1_000_000_000_000_000) }.isFailure)
    }

    @Test
    fun year() {
        val cases = mapOf(
            1066 to "ten sixty six", 1900 to "nineteen hundred", 1905 to "nineteen oh five",
            2000 to "two thousand", 2007 to "two thousand seven", 2010 to "twenty ten",
            1999 to "nineteen ninety nine", 3000 to "three thousand",
        )
        for ((y, words) in cases) assertEquals(words, n.year(y))
    }

    @Test
    fun `ordinal and plural`() {
        assertEquals("twenty first", n.toOrdinal("twenty one"))
        assertEquals("twelfth", n.toOrdinal("twelve"))
        assertEquals("fortieth", n.toOrdinal("forty"))
        assertEquals("nineties", n.toPlural("ninety"))
        assertEquals("sixes", n.toPlural("six"))
    }

    @Test
    fun normalize() {
        val cases = mapOf(
            "It costs \$3.50." to "It costs three dollars and fifty cents.",
            "at 7:45PM." to "at seven forty five p.m.",
            "the 4th of July" to "the fourth of July",
            "pages 10–20" to "pages ten to twenty",
            "COVID-19" to "COVID- nineteen",
            "MP3" to "MP three",
            "Dr. Smith" to "Doctor Smith",
            "on Main St. today" to "on Main Street today",
            "Smith Jr." to "Smith Junior.",
            "No. 5" to "Number five",
            "\$5M" to "five million dollars",
        )
        for ((src, want) in cases) assertEquals(src, want, n.normalize(src, "en-us"))
        assertEquals("the third of April, twenty twenty four", n.normalize("3/4/2024", "en-gb"))
        assertEquals("March fourth, twenty twenty four", n.normalize("3/4/2024", "en-us"))
    }

    @Test
    fun `no digit survives`() {
        for (s in listOf("1,2345", "3.14.15.92", "25:00", "x9y", "٣ 12", "0x1F", "1e10", "99.99.99", "123456789012345678901")) {
            val out = n.normalize(s, "en-us")
            assertFalse("$s -> $out", out.any { it in '0'..'9' })
        }
    }

    @Test
    fun `prepare text`() {
        assertEquals("a b c", prepareText("  a b\n\tc  "))
        assertEquals("don't …", prepareText("don’t …"))
        assertEquals("x2", prepareText("x²"))
    }

    @Test
    fun `apply stress`() {
        assertEquals("həlO", applyStress("həlˈO", -2.0))
        assertEquals("həlˌO", applyStress("həlˈO", -1.0))
        assertEquals("kˈæt", applyStress("kæt", 2.0))
        assertEquals("kˌæt", applyStress("kæt", 0.5))
        assertNull(applyStress(null, 1.0))
    }

    @Test
    fun `grow dictionary`() {
        val g = growDictionary(mapOf("hello" to "x", "Paris" to "y", "a" to "z", "NASA" to "w"))
        assertEquals(mapOf("Hello" to "x", "paris" to "y", "hello" to "x", "Paris" to "y", "a" to "z", "NASA" to "w"), g)
    }

    @Test
    fun `tokenize structure`() {
        val items = tokenize("Hello, (U.S.) world's end.")
        val texts = items.map { if (it is G2PItem.Multi) it.tokens.map { t -> t.text } else it.text }
        assertEquals(listOf("Hello", ",", "(", listOf("U", ".", "S"), ".)", "world's", "end", "."), texts)
    }

    @Test
    fun `style index`() {
        assertEquals(0, KokoroVoicePack.styleIndex(0))
        assertEquals(0, KokoroVoicePack.styleIndex(1))
        assertEquals(99, KokoroVoicePack.styleIndex(100))
        assertEquals(509, KokoroVoicePack.styleIndex(510))
        assertEquals(509, KokoroVoicePack.styleIndex(9999))
    }

    @Test
    fun `code points not UTF-16 units`() {
        val s = "a𝐀b" // a, U+1D400 (one code point, two chars), b
        assertEquals(3, Py.len(s))
        assertEquals("𝐀b", Py.drop(s, 1))
        assertEquals("a", Py.dropLast(s, 2))
        assertEquals('b'.code, Py.fromEnd(s, 1))
        assertEquals(0x1D400, Py.fromEnd(s, 2))
    }

    @Test
    fun `case mapping ignores the default locale`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("ti", Py.lower("TI"))
            assertEquals("TI", Py.upper("ti"))
            assertEquals("Indigo", Py.capitalize("indigo"))
            assertEquals("the fourth of July", n.normalize("the 4th of July", "en-us"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `bart argmax keeps the lowest index on ties and slices long words`() {
        val vocab = BartVocab(
            graphemeToId = mapOf('a'.code to 4),
            phonemeTokens = listOf("<pad>", "<s>", "</s>", "<unk>", "x", "y"),
            maxPositions = 64,
        )
        val seen = ArrayList<Int>()
        val bart = BartG2P(vocab) { input, dec ->
            seen += input.size
            // Ties between 4 and 5 on the first step, then end.
            if (dec.size == 1) floatArrayOf(0f, 0f, 0f, 0f, 1f, 1f) else floatArrayOf(0f, 0f, 9f, 0f, 0f, 0f)
        }
        assertEquals("x", bart.phonemize("a"))
        assertEquals("xx", bart.phonemize("a".repeat(63))) // 62 + 1 code points
        assertEquals(listOf(3, 3, 64, 64, 3, 3), seen)
    }

    @Test
    fun `chunker splits long text at sentence ends within 510`() {
        val tokens = ArrayList<G2PToken>()
        repeat(60) { i ->
            tokens += G2PToken("word$i", whitespace = " ", phonemes = "wˈɜɹdz")
            if (i % 10 == 9) tokens += G2PToken(".", whitespace = " ", phonemes = ".")
        }
        val chunks = KokoroChunker.chunk(tokens)
        assertTrue(chunks.size >= 1)
        assertTrue(chunks.all { Py.len(it.phonemes) <= KokoroVocab.MAX_PHONEMES })
        val pieces = KokoroChunker.speechPieces(chunks)
        assertTrue("sentence pieces", pieces.size >= 6)
        assertTrue(pieces.all { it.phonemes.endsWith(".") })
        assertEquals(
            chunks.joinToString(" ") { it.phonemes },
            pieces.joinToString(" ") { it.phonemes },
        )
    }

    private fun toks(vararg parts: Pair<String, String>): List<G2PToken> =
        parts.mapIndexed { i, (text, ps) -> G2PToken(text, whitespace = if (i < parts.size - 1 && parts[i + 1].second.first().isLetter()) " " else "", phonemes = ps) }

    @Test
    fun `first piece of an utterance is cut early, later ones only at sentence ends`() {
        val reply = toks("Sure" to "ʃˈʊɹ", "!" to "!", "Here" to "hˈɪɹ", "is" to "ɪz", "what" to "wˌʌt", "I" to "ˌI", "found" to "fˈWnd", "." to ".")
        val p1 = KokoroChunker.speechPieces(KokoroChunker.chunk(reply))
        assertEquals(listOf("ʃˈʊɹ!", "hˈɪɹ ɪz wˌʌt ˌI fˈWnd."), p1.map { it.phonemes })
        // Not at the start of an utterance: no short pieces.
        assertEquals(1, KokoroChunker.speechPieces(KokoroChunker.chunk(reply), startsUtterance = false).size)

        val clause = toks(
            "The" to "ðə", "meeting" to "mˈiTɪŋ", "moved" to "mˈuvd", "to" to "tə", "Thursday" to "θˈɜɹzdA", "," to ",",
            "in" to "ɪn", "room" to "ɹˈum", "four" to "fˈɔɹ", "today" to "tədˈA", "." to ".",
        )
        val p2 = KokoroChunker.speechPieces(KokoroChunker.chunk(clause))
        assertEquals(listOf("ðə mˈiTɪŋ mˈuvd tə θˈɜɹzdA,", "ɪn ɹˈum fˈɔɹ tədˈA."), p2.map { it.phonemes })
    }
}
