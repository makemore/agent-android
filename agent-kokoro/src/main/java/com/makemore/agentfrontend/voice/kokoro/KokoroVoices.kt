package com.makemore.agentfrontend.voice.kokoro

import com.makemore.agentfrontend.voice.VoiceDescriptor

/**
 * One Kokoro v1.0 voice. [id] is Kokoro's own name (`af_heart`, `bm_george`
 * …) — the id shared by the Android, iOS and web clients. The first letter
 * is the language/accent, the second the gender (`f`/`m`).
 */
data class KokoroVoice(
    val id: String,
    /** Speaker index in the sherpa-onnx `voices.bin` table. */
    val speakerId: Int,
    /** Human name, e.g. `"Heart"`. */
    val displayName: String,
    /** BCP-47 language tag, e.g. `"en-US"`. */
    val language: String,
    /** Human language/accent, e.g. `"American English"`. */
    val languageName: String,
    /** `"female"` or `"male"`. */
    val gender: String,
) {
    /** Ready-to-show label, e.g. `"Heart (American English, female)"`. */
    val label: String get() = "$displayName ($languageName, $gender)"

    fun toDescriptor(): VoiceDescriptor = VoiceDescriptor(
        id = id,
        name = label,
        labels = mapOf(
            "engine" to KokoroTTS.ENGINE_NAME,
            "displayName" to displayName,
            "language" to language,
            "languageName" to languageName,
            "gender" to gender,
        ),
    )
}

/** The Kokoro v1.0 voice catalogue, in sherpa-onnx speaker-id order. */
object KokoroVoices {
    /** Default voice on every platform. */
    const val DEFAULT_VOICE_ID = "af_heart"

    private data class Lang(val tag: String, val name: String, val espeak: String?)

    // espeak-ng voice used to phonemise non-Chinese text. These are espeak
    // voice *names* from the bundled espeak-ng-data: British English is "en"
    // ("en-gb" is rejected by espeak-ng and yields no audio). `null` keeps the
    // model default (en-us); Chinese voices read Han characters via lexicon-zh.
    private val languages = mapOf(
        'a' to Lang("en-US", "American English", "en-us"),
        'b' to Lang("en-GB", "British English", "en"),
        'e' to Lang("es", "Spanish", "es"),
        'f' to Lang("fr-FR", "French", "fr"),
        'h' to Lang("hi", "Hindi", "hi"),
        'i' to Lang("it", "Italian", "it"),
        'j' to Lang("ja", "Japanese", "ja"),
        'p' to Lang("pt-BR", "Brazilian Portuguese", "pt-br"),
        'z' to Lang("zh-CN", "Mandarin Chinese", null),
    )

    // Order == speaker id (kokoro-*-multi-lang-v1_0 `voices.bin`). em_santa
    // (53) was appended upstream; older 53-voice archives lack it and the
    // provider falls back to the default voice there.
    private val ids = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole",
        "af_nova", "af_river", "af_sarah", "af_sky",
        "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily",
        "bm_daniel", "bm_fable", "bm_george", "bm_lewis",
        "ef_dora", "em_alex",
        "ff_siwis",
        "hf_alpha", "hf_beta", "hm_omega", "hm_psi",
        "if_sara", "im_nicola",
        "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo",
        "pf_dora", "pm_alex", "pm_santa",
        "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi",
        "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
        "em_santa",
    )

    /** All 54 voices, ordered by speaker id. */
    val all: List<KokoroVoice> = ids.mapIndexed { sid, id ->
        val lang = languages.getValue(id[0])
        KokoroVoice(
            id = id,
            speakerId = sid,
            displayName = id.substringAfter('_').replaceFirstChar { it.uppercase() },
            language = lang.tag,
            languageName = lang.name,
            gender = if (id[1] == 'f') "female" else "male",
        )
    }

    private val byId = all.associateBy { it.id }

    val default: KokoroVoice = byId.getValue(DEFAULT_VOICE_ID)

    /** The voice for [id], or `null` if it is not a Kokoro v1.0 voice id. */
    fun find(id: String?): KokoroVoice? = id?.let { byId[it.trim().lowercase()] }

    /** espeak-ng language for [voice]'s non-Chinese text; `null` = model default. */
    fun espeakLanguage(voice: KokoroVoice): String? = languages.getValue(voice.id[0]).espeak
}
