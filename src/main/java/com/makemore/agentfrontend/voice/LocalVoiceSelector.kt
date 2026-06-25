package com.makemore.agentfrontend.voice

import java.util.Locale

/** Testable representation of an Android TTS voice. */
data class AndroidVoiceCandidate(
    val name: String,
    val locale: Locale,
    val quality: Int = 0,
    val isNetworkConnectionRequired: Boolean,
    val features: Set<String> = emptySet(),
)

object LocalVoiceSelector {
    fun chooseLocalVoice(
        voices: Iterable<AndroidVoiceCandidate>,
        preferredVoiceId: String? = null,
        preferredLocale: Locale = Locale.getDefault(),
        genderPreference: LocalVoiceGenderPreference = LocalVoiceGenderPreference.MALE,
    ): AndroidVoiceCandidate? {
        val local = voices.filter { !it.isNetworkConnectionRequired }
        if (preferredVoiceId != null) {
            local.firstOrNull { it.name == preferredVoiceId }?.let { return it }
        }

        return local.maxByOrNull { candidate -> candidate.preferenceScore(preferredLocale, genderPreference) }
    }

    private fun AndroidVoiceCandidate.preferenceScore(
        preferredLocale: Locale,
        genderPreference: LocalVoiceGenderPreference,
    ): Int {
        val exactLocale = locale.language == preferredLocale.language &&
            locale.country.equals(preferredLocale.country, ignoreCase = true)
        val languageOnly = locale.language == preferredLocale.language
        val localeScore = if (exactLocale) 2_000 else if (languageOnly) 1_000 else 0

        return quality + localeScore + genderScore(genderPreference)
    }

    private fun AndroidVoiceCandidate.genderScore(preference: LocalVoiceGenderPreference): Int {
        if (preference == LocalVoiceGenderPreference.ANY) return 0
        val tokens = (features + name).map { it.lowercase(Locale.US) }
        val male = tokens.any { token ->
            ("male" in token && "female" !in token) || "#male" in token || "-rjs" in token
        }
        val female = tokens.any { token -> "female" in token || "#female" in token || "-fis" in token }

        return when (preference) {
            LocalVoiceGenderPreference.MALE -> when {
                male -> 500
                female -> -250
                else -> 0
            }
            LocalVoiceGenderPreference.FEMALE -> when {
                female -> 500
                male -> -250
                else -> 0
            }
            LocalVoiceGenderPreference.ANY -> 0
        }
    }
}