package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.VoiceDescriptor

/**
 * One Kokoro v1.0 English voice. [id] is Kokoro's own name (`af_heart`,
 * `bm_george` …), shared by the Android, iOS and web clients. The first
 * letter is the accent (`a` = en-us, `b` = en-gb), the second the gender.
 */
data class KokoroVoice(
    val id: String,
    /** Human name, e.g. `"Heart"`. */
    val name: String,
    /** `"en-us"` or `"en-gb"`: the G2P language this voice must be used with. */
    val language: String,
    /** `"female"` or `"male"`. */
    val gender: String,
    /** True for the voice voices.json suggests for its language (af_heart, bf_emma). */
    val suggested: Boolean = false,
    /** Kokoro's overall quality grade (A best), when known. */
    val grade: String? = null,
) {
    /** Ready-to-show label, e.g. `"Heart (American English, female)"`. */
    val label: String
        get() = "$name (${if (language == KokoroVoices.EN_GB) "British English" else "American English"}, $gender)"

    fun toDescriptor(): VoiceDescriptor = VoiceDescriptor(
        id = id,
        name = label,
        labels = buildMap {
            put("engine", KokoroTTS.ENGINE_NAME)
            put("displayName", name)
            put("language", language)
            put("gender", gender)
            put("suggested", suggested.toString())
            grade?.let { put("grade", it) }
        },
    )
}

/** The voice catalogue (voices.json of the v1 asset set, also built in for offline use). */
object KokoroVoices {
    const val DEFAULT_VOICE_ID = "af_heart"
    const val EN_US = "en-us"
    const val EN_GB = "en-gb"

    /** Language of a voice id from its prefix (`a*` en-us, `b*` en-gb), or null. */
    fun languageOf(voiceId: String): String? = when (voiceId.firstOrNull()) {
        'a' -> EN_US
        'b' -> EN_GB
        else -> null
    }

    private val GRADES = linkedMapOf(
        "af_heart" to "A", "af_alloy" to "C", "af_aoede" to "C+", "af_bella" to "A-", "af_jessica" to "D",
        "af_kore" to "C+", "af_nicole" to "B-", "af_nova" to "C", "af_river" to "D", "af_sarah" to "C+",
        "af_sky" to "C-", "am_adam" to "F+", "am_echo" to "D", "am_eric" to "D", "am_fenrir" to "C+",
        "am_liam" to "D", "am_michael" to "C+", "am_onyx" to "D", "am_puck" to "C+", "am_santa" to "D-",
        "bf_alice" to "D", "bf_emma" to "B-", "bf_isabella" to "C", "bf_lily" to "D", "bm_daniel" to "D",
        "bm_fable" to "C", "bm_george" to "C", "bm_lewis" to "D+",
    )
    private val SUGGESTED = setOf("af_heart", "bf_emma")

    private fun make(id: String, lang: String, gender: String, grade: String?, suggested: Boolean) = KokoroVoice(
        id = id,
        name = id.substringAfter('_').replaceFirstChar { it.uppercase() },
        language = lang,
        gender = gender,
        suggested = suggested,
        grade = grade,
    )

    /** The 28 English voices of the v1 asset set, in voices.json order. */
    val all: List<KokoroVoice> = GRADES.map { (id, grade) ->
        make(id, languageOf(id)!!, if (id[1] == 'f') "female" else "male", grade, id in SUGGESTED)
    }

    private val byId = all.associateBy { it.id }

    val default: KokoroVoice = byId.getValue(DEFAULT_VOICE_ID)

    /** The built-in voice for [id], or null if it is not a Kokoro v1 English voice id. */
    fun find(id: String?): KokoroVoice? = id?.let { byId[it.trim().lowercase()] }

    /** Parse `voices/voices.json` ("kokoro-voices/1"). */
    internal fun parse(json: String): List<KokoroVoice> {
        val root = KokoroJson.parseObject(json)
        val defaults = (root["default"] as? Map<*, *>)?.values?.filterIsInstance<String>()?.toSet() ?: emptySet()
        return root.list("voices").mapNotNull { raw ->
            @Suppress("UNCHECKED_CAST")
            val v = raw as? Map<String, Any?> ?: return@mapNotNull null
            val id = v["id"] as? String ?: return@mapNotNull null
            val lang = v["lang"] as? String ?: languageOf(id) ?: return@mapNotNull null
            if (lang != EN_US && lang != EN_GB) return@mapNotNull null
            make(id, lang, v["gender"] as? String ?: if (id.getOrNull(1) == 'f') "female" else "male", v["grade"] as? String, id in defaults)
        }
    }
}
