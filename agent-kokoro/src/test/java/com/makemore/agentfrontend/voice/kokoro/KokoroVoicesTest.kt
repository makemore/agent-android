package com.makemore.agentfrontend.voice.kokoro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroVoicesTest {
    // speaker_names from kokoro-int8-multi-lang-v1_0/model.int8.onnx metadata.
    private val upstreamOrder = (
        "af_alloy,af_aoede,af_bella,af_heart,af_jessica,af_kore,af_nicole,af_nova,af_river,af_sarah," +
            "af_sky,am_adam,am_echo,am_eric,am_fenrir,am_liam,am_michael,am_onyx,am_puck,am_santa," +
            "bf_alice,bf_emma,bf_isabella,bf_lily,bm_daniel,bm_fable,bm_george,bm_lewis,ef_dora,em_alex," +
            "ff_siwis,hf_alpha,hf_beta,hm_omega,hm_psi,if_sara,im_nicola,jf_alpha,jf_gongitsune,jf_nezumi," +
            "jf_tebukuro,jm_kumo,pf_dora,pm_alex,pm_santa,zf_xiaobei,zf_xiaoni,zf_xiaoxiao,zf_xiaoyi," +
            "zm_yunjian,zm_yunxi,zm_yunxia,zm_yunyang,em_santa"
        ).split(',')

    @Test
    fun `catalogue matches the model's speaker table`() {
        assertEquals(upstreamOrder, KokoroVoices.all.map { it.id })
        KokoroVoices.all.forEachIndexed { index, voice -> assertEquals(index, voice.speakerId) }
    }

    @Test
    fun `default voice is af_heart`() {
        assertEquals("af_heart", KokoroVoices.DEFAULT_VOICE_ID)
        assertEquals(3, KokoroVoices.default.speakerId)
        assertEquals("Heart (American English, female)", KokoroVoices.default.label)
    }

    @Test
    fun `find accepts kokoro ids only`() {
        assertEquals(16, KokoroVoices.find("am_michael")?.speakerId)
        assertEquals(26, KokoroVoices.find(" BM_George ")?.speakerId)
        assertNull(KokoroVoices.find("21m00Tcm4TlvDq8ikWAM")) // an ElevenLabs id
        assertNull(KokoroVoices.find("en-us-x-sfg-local"))   // an Android voice name
        assertNull(KokoroVoices.find(null))
    }

    @Test
    fun `descriptors carry human labels and structured metadata`() {
        val emma = KokoroVoices.find("bf_emma")!!.toDescriptor()
        assertEquals("bf_emma", emma.id)
        assertEquals("Emma (British English, female)", emma.name)
        assertEquals("kokoro", emma.labels?.get("engine"))
        assertEquals("en-GB", emma.labels?.get("language"))
        assertEquals("female", emma.labels?.get("gender"))
        assertEquals("George (British English, male)", KokoroVoices.find("bm_george")!!.label)
    }

    @Test
    fun `espeak language follows the voice prefix`() {
        fun lang(id: String) = KokoroVoices.espeakLanguage(KokoroVoices.find(id)!!)
        assertEquals("en-us", lang("af_bella"))
        assertEquals("en", lang("bm_george")) // espeak-ng name for en-GB
        assertEquals("es", lang("ef_dora"))
        assertEquals("fr", lang("ff_siwis"))
        assertEquals("pt-br", lang("pm_alex"))
        assertNull(lang("zf_xiaoxiao"))
        assertTrue(KokoroVoices.all.all { it.gender == "female" || it.gender == "male" })
    }
}
