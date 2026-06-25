package com.makemore.agentfrontend.voice

import com.makemore.agentfrontend.configuration.APIPaths
import com.makemore.agentfrontend.configuration.ChatWidgetConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class VoicePolicyTest {
    @Test
    fun `privateOnly automatic selects local voice and no remote plan`() {
        val config = ChatWidgetConfig(privateOnly = true, enableTTS = true)

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.LOCAL, plan.kind)
        assertEquals(VoiceMode.Local, plan.mode)
    }

    @Test
    fun `localOnly selects local voice even when api client is present`() {
        val config = ChatWidgetConfig(
            enableTTS = true,
            ttsProviderPolicy = TTSProviderPolicy.LOCAL_ONLY,
        )

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.LOCAL, plan.kind)
        assertEquals(VoiceMode.Local, plan.mode)
    }

    @Test
    fun `remote policy preserves proxy behavior outside private mode`() {
        val config = ChatWidgetConfig(
            enableTTS = true,
            ttsProviderPolicy = TTSProviderPolicy.REMOTE,
        )

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.REMOTE, plan.kind)
        assertEquals(VoiceMode.Remote, plan.mode)
    }

    @Test
    fun `disabled policy creates no voice provider plan`() {
        val config = ChatWidgetConfig(
            enableTTS = true,
            ttsProviderPolicy = TTSProviderPolicy.DISABLED,
        )

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.NONE, plan.kind)
        assertEquals(VoiceMode.Disabled, plan.mode)
    }

    @Test
    fun `automatic non private remains remote when voice endpoint configured`() {
        val config = ChatWidgetConfig(enableTTS = true)

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.REMOTE, plan.kind)
        assertEquals(VoiceMode.Remote, plan.mode)
    }

    @Test
    fun `automatic non private falls back local when voice endpoint unset`() {
        val config = ChatWidgetConfig(
            enableTTS = true,
            apiPaths = APIPaths(voiceToken = null),
        )

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.LOCAL, plan.kind)
        assertEquals(VoiceMode.Local, plan.mode)
    }

    @Test
    fun `local voice selector rejects network required voices`() {
        val network = AndroidVoiceCandidate("network-en", Locale.US, quality = 500, isNetworkConnectionRequired = true)
        val local = AndroidVoiceCandidate("local-en", Locale.US, quality = 300, isNetworkConnectionRequired = false)

        val selected = LocalVoiceSelector.chooseLocalVoice(listOf(network, local), preferredVoiceId = "network-en")

        assertEquals("local-en", selected?.name)
    }

    @Test
    fun `android config defaults local voice gender preference to male`() {
        assertEquals(LocalVoiceGenderPreference.MALE, ChatWidgetConfig().localVoiceGenderPreference)
    }

    @Test
    fun `local voice selector defaults to male preference`() {
        val female = AndroidVoiceCandidate("en-gb-x-fis-local", Locale.UK, quality = 500, isNetworkConnectionRequired = false)
        val male = AndroidVoiceCandidate("en-gb-x-rjs-local", Locale.UK, quality = 400, isNetworkConnectionRequired = false)

        val selected = LocalVoiceSelector.chooseLocalVoice(listOf(female, male), preferredLocale = Locale.UK)

        assertEquals("en-gb-x-rjs-local", selected?.name)
    }

    @Test
    fun `local voice selector returns null when only network voices exist`() {
        val selected = LocalVoiceSelector.chooseLocalVoice(
            listOf(AndroidVoiceCandidate("network-en", Locale.US, isNetworkConnectionRequired = true)),
        )

        assertNull(selected)
    }

    @Test
    fun `local voice selector prefers uk male voice when available`() {
        val usMale = AndroidVoiceCandidate("en-us-x-sfg#male_1-local", Locale.US, quality = 500, isNetworkConnectionRequired = false)
        val ukFemale = AndroidVoiceCandidate("en-gb-x-fis-local", Locale.UK, quality = 500, isNetworkConnectionRequired = false)
        val ukMale = AndroidVoiceCandidate("en-gb-x-rjs-local", Locale.UK, quality = 400, isNetworkConnectionRequired = false)

        val selected = LocalVoiceSelector.chooseLocalVoice(
            listOf(usMale, ukFemale, ukMale),
            preferredLocale = Locale.UK,
            genderPreference = LocalVoiceGenderPreference.MALE,
        )

        assertEquals("en-gb-x-rjs-local", selected?.name)
    }

    @Test
    fun `local voice selector falls back to uk voice when male unavailable`() {
        val usMale = AndroidVoiceCandidate("en-us-x-sfg#male_1-local", Locale.US, quality = 500, isNetworkConnectionRequired = false)
        val ukFemale = AndroidVoiceCandidate("en-gb-x-fis-local", Locale.UK, quality = 500, isNetworkConnectionRequired = false)

        val selected = LocalVoiceSelector.chooseLocalVoice(
            listOf(usMale, ukFemale),
            preferredLocale = Locale.UK,
            genderPreference = LocalVoiceGenderPreference.MALE,
        )

        assertEquals("en-gb-x-fis-local", selected?.name)
    }

    @Test
    fun `privateOnly remote policy fails closed instead of planning remote`() {
        val config = ChatWidgetConfig(
            privateOnly = true,
            enableTTS = true,
            ttsProviderPolicy = TTSProviderPolicy.REMOTE,
        )

        val plan = VoiceFactory.plan(config, apiClientAvailable = true)

        assertEquals(VoiceProviderKind.NONE, plan.kind)
        assertTrue(plan.mode is VoiceMode.Unavailable)
    }
}