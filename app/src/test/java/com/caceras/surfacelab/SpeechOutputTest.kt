package com.caceras.surfacelab

import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech

@RunWith(AndroidJUnit4::class)
class SpeechOutputTest {
    @After fun reset() = ShadowTextToSpeech.reset()

    private fun voice(name: String, locale: Locale, network: Boolean = false,
                      features: Set<String> = emptySet()) =
        Voice(name, locale, 300, 300, network, features)

    private fun ready(mouth: Mouth, locale: Locale = Locale.US): TextToSpeech {
        mouth.begin(locale)
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        return engine
    }

    @Test fun `only installed offline voices matching the requested language are used`() {
        val swedish = Locale.forLanguageTag("sv-SE")
        ShadowTextToSpeech.addVoice(voice("english", Locale.US))
        ShadowTextToSpeech.addVoice(voice("cloud", swedish, network = true))
        ShadowTextToSpeech.addVoice(voice("missing", swedish,
            features = setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
        val local = voice("swedish-local", swedish)
        ShadowTextToSpeech.addVoice(local)
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        val engine = ready(mouth, swedish)
        mouth.finish("Hej.")
        assertEquals(local, shadowOf(engine).currentVoice)
        assertEquals(listOf("Hej."), shadowOf(engine).spokenTextList)
        mouth.close()
    }

    @Test fun `missing offline voice fails visibly without using a network voice`() {
        ShadowTextToSpeech.addVoice(voice("cloud", Locale.US, network = true))
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        var problem: String? = null
        mouth.onProblem = { problem = it }
        val engine = ready(mouth)
        mouth.finish("Private answer.")
        assertNotNull(problem)
        assertTrue(shadowOf(engine).spokenTextList.isEmpty())
        assertFalse(mouth.speaking())
        mouth.close()
    }

    @Test fun `engine initialization failure releases queued speech`() {
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        var idle = false
        var failed = false
        mouth.onIdle = { idle = true }
        mouth.onProblem = { failed = true }
        mouth.begin(Locale.US)
        mouth.finish("Queued before init.")
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(engine).onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(failed)
        assertTrue(idle)
        assertFalse(mouth.speaking())
        mouth.close()
    }

    @Test fun `completion between streamed sentences does not end the conversation`() {
        ShadowTextToSpeech.addVoice(voice("local", Locale.US))
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        var idle = 0
        mouth.onIdle = { idle++ }
        ready(mouth)
        mouth.follow("First sentence.")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, idle)
        mouth.finish("First sentence. Second sentence.")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(idle > 0)
        mouth.close()
    }

    @Test fun `losing audio focus ends playback and keeps future chunks quiet`() {
        ShadowTextToSpeech.addVoice(voice("local", Locale.US))
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        val engine = ready(mouth)
        var reason: String? = null
        var idle = false
        mouth.onProblem = { reason = it }
        mouth.onIdle = { idle = true }
        mouth.finish("First answer.")
        val focus = org.robolectric.util.ReflectionHelpers.getField<android.media.AudioFocusRequest>(mouth, "focus")
        val listener = org.robolectric.util.ReflectionHelpers.callInstanceMethod<android.media.AudioManager.OnAudioFocusChangeListener>(focus, "getOnAudioFocusChangeListener")
        listener.onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS)
        assertNotNull(reason)
        assertTrue(idle)
        assertFalse(mouth.speaking())
        mouth.follow("First answer. Another sentence.")
        assertEquals(listOf("First answer."), shadowOf(engine).spokenTextList)
        mouth.close()
    }

    @Test fun `long speech is split to the engine input limit`() {
        ShadowTextToSpeech.addVoice(voice("local", Locale.US))
        val mouth = Mouth(RuntimeEnvironment.getApplication())
        val engine = ready(mouth)
        val text = "a".repeat(TextToSpeech.getMaxSpeechInputLength() + 30)
        mouth.finish(text)
        val chunks = shadowOf(engine).spokenTextList
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= TextToSpeech.getMaxSpeechInputLength() })
        mouth.close()
    }
}
