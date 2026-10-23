package com.caceras.surfacelab

import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import java.time.Duration
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class ReaderRepairTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @After fun reset() = ShadowTextToSpeech.reset()

    @Test fun `emphasis code and escapes retain meaning on screen and in speech`() {
        val source = "**Bold *inside***, *italic*, `*literal*`, snake_case, 2 * 3 and \\*escaped\\*."
        val rendered = Markdown.render(source) as Spanned
        assertEquals("Bold inside, italic, *literal*, snake_case, 2 * 3 and *escaped*.", rendered.toString())
        assertEquals(rendered.toString(), Markdown.strip(source))
        assertTrue(rendered.getSpans(0, rendered.length, StyleSpan::class.java).any { it.style == Typeface.ITALIC })
        assertTrue(rendered.getSpans(0, rendered.length, StyleSpan::class.java).any { it.style == Typeface.BOLD })
        assertEquals(1, rendered.getSpans(0, rendered.length, TypefaceSpan::class.java).size)
    }

    @Test fun `fenced code is not parsed as emphasis and links retain their destination`() {
        val source = "```kotlin\nval value = \"*literal*\"\n```\n[Android](https://developer.android.com)"
        val rendered = Markdown.render(source) as Spanned
        assertEquals("val value = \"*literal*\"\nAndroid", rendered.toString())
        assertEquals("https://developer.android.com", rendered.getSpans(0, rendered.length, android.text.style.URLSpan::class.java).single().url)
        assertEquals(rendered.toString(), Markdown.strip(source))
    }

    @Test fun `partial emphasis stays readable and literal unmatched final text is preserved`() {
        assertEquals("An answer", Markdown.render("An **answer", streaming = true).toString())
        assertEquals("An answer", Markdown.render("An **answer**", streaming = true).toString())
        assertEquals("An **answer", Markdown.render("An **answer").toString())
        assertEquals("2 * 3", Markdown.render("2 * 3", streaming = true).toString())
    }

    @Test fun `streamed paragraphs keep their text buffer and final style without duplicating spans`() {
        val view = TextView(context)
        ReplyRenderer.paint(view, "**First** paragraph.\n\nAn *ans", 18)
        val buffer = view.editableText
        repeat(50) { ReplyRenderer.paint(view, "**First** paragraph.\n\nAn *answer* $it", 18) }
        assertSame(buffer, view.editableText)
        assertEquals("First paragraph.\n\nAn answer 49", view.text.toString())
        val spans = buffer.getSpans(0, buffer.length, StyleSpan::class.java)
        assertEquals(2, spans.size)
        assertTrue(spans.any { it.style == Typeface.BOLD && buffer.getSpanStart(it) == 0 })
        assertTrue(spans.any { it.style == Typeface.ITALIC })
        ReplyRenderer.paint(view, "First paragraph.\n\nA replacement", 18)
        assertEquals("First paragraph.\n\nA replacement", view.text.toString())
        assertEquals(0, buffer.getSpans(0, buffer.length, StyleSpan::class.java).size)
    }

    @Test fun `burst input is coalesced before the old fixed timer and cancellation wins`() {
        val output = mutableListOf<String>()
        val updates = StreamUpdates { output.add(it) }
        updates.offer("First")
        repeat(100) { updates.offer("First $it") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertEquals(listOf("First", "First 99"), output)
        updates.offer("stale")
        updates.cancel()
        updates.offer("New")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        assertEquals("New", output.last())
        assertFalse(output.contains("stale"))
    }

    @Test fun `voice ranking prefers quality while excluding network and uninstalled voices`() {
        val basic = Voice("basic", Locale.US, Voice.QUALITY_LOW, 100, false, emptySet())
        val high = Voice("high", Locale.UK, Voice.QUALITY_HIGH, 300, false, emptySet())
        val cloud = Voice("cloud", Locale.US, Voice.QUALITY_VERY_HIGH, 100, true, emptySet())
        val missing = Voice("missing", Locale.US, Voice.QUALITY_VERY_HIGH, 100, false, setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED))
        val voices = listOf(basic, cloud, missing, high)
        assertEquals(high, SpeechVoices.choose(voices, Locale.US))
        assertEquals(basic, SpeechVoices.choose(voices, Locale.US, "basic"))
        assertEquals(high, SpeechVoices.choose(voices, Locale.US, "cloud"))
        assertNull(SpeechVoices.choose(voices, Locale.forLanguageTag("sv-SE")))
    }

    @Test fun `voices reported with three-letter language codes count, and an engine without a voice list still reads`() {
        val iso3 = Voice("hayai-en", Locale("eng", "USA"), Voice.QUALITY_NORMAL, 300, false, emptySet())
        assertEquals(iso3, SpeechVoices.choose(listOf(iso3), Locale.US))
        assertTrue(SpeechVoices.sameLanguage(Locale("en_us"), Locale.UK))
        assertFalse(SpeechVoices.sameLanguage(Locale("swe"), Locale.US))
        // No voices listed, but the engine speaks the language: use its own voice.
        ShadowTextToSpeech.addLanguageAvailability(Locale.US)
        val engine = TextToSpeech(context) {}
        assertTrue(SpeechVoices.apply(context, engine, Locale.US))
        assertFalse(SpeechVoices.apply(context, engine, Locale.forLanguageTag("sv-SE")))
    }

    private fun startReader(text: String): Pair<org.robolectric.android.controller.ServiceController<ReadingService>, TextToSpeech> {
        ShadowTextToSpeech.addVoice(Voice("basic", Locale.getDefault(), Voice.QUALITY_LOW, 100, false, emptySet()))
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        controller.get().onStartCommand(Intent(context, ReadingService::class.java).putExtra("text", text), 0, 1)
        return controller to engine
    }

    @Test fun `saved reader selects the higher quality voice and renders sentences ahead instead of speaking one at a time`() {
        val high = Voice("high", Locale.getDefault(), Voice.QUALITY_HIGH, 300, false, emptySet())
        ShadowTextToSpeech.addVoice(high)
        val (controller, engine) = startReader("First sentence. Second sentence. Third sentence. Fourth sentence. Fifth sentence.")
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(high, shadowOf(engine).currentVoice)
        // Four sentences are requested as audio files while the first plays; nothing waits on the speak queue.
        assertEquals("Fourth sentence. ", shadowOf(engine).lastSynthesizeToFileText)
        assertTrue(shadowOf(engine).spokenTextList.isEmpty())
        assertTrue(ReadingService.snapshot.playing)
        controller.destroy()
    }

    @Test fun `an engine that cannot render files falls back to the queued speech`() {
        val (controller, engine) = startReader("First sentence. Second sentence. Third sentence. Fourth sentence.")
        shadowOf(engine).simulateSynthesizeToFileResult(TextToSpeech.ERROR)
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(TextToSpeech.QUEUE_ADD, shadowOf(engine).queueMode)
        assertEquals("First sentence. Second sentence. Third sentence. Fourth sentence.", shadowOf(engine).spokenTextList.joinToString(""))
        assertEquals("", ReadingService.snapshot.error)
        controller.destroy()
    }

    private fun installEngine(name: String) {
        val engine = android.content.pm.ResolveInfo().apply {
            serviceInfo = android.content.pm.ServiceInfo().apply {
                packageName = name; this.name = "TtsService"
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = name }
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), engine)
    }

    @Test fun `when one open-source voice app cannot start, reading moves on to the next and never to Google`() {
        installEngine(SpeechEngines.GOOGLE)
        installEngine("dev.ahmedmohamed.hayaitts")
        installEngine("com.k2fsa.sherpa.onnx.tts.engine")
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        controller.get().onStartCommand(Intent(context, ReadingService::class.java).putExtra("text", "First sentence. Second sentence."), 0, 1)
        val failing = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(failing).onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        val fallback = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(failing, fallback)
        assertTrue(shadowOf(failing).isShutdown)
        ShadowTextToSpeech.addVoice(Voice("kokoro#3#af_heart", Locale.getDefault(), Voice.QUALITY_NORMAL, 100, false, emptySet()))
        shadowOf(fallback).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", ReadingService.snapshot.error)
        assertTrue(ReadingService.snapshot.playing)
        assertEquals("Second sentence.", shadowOf(fallback).lastSynthesizeToFileText)
        controller.destroy()
    }

    @Test fun `when every open-source voice app fails, reading stops with a message instead of using Google`() {
        installEngine(SpeechEngines.GOOGLE)
        installEngine("dev.ahmedmohamed.hayaitts")
        installEngine("com.k2fsa.sherpa.onnx.tts.engine")
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        controller.get().onStartCommand(Intent(context, ReadingService::class.java).putExtra("text", "Keep this text."), 0, 1)
        val first = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(first).onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        val second = ShadowTextToSpeech.getLastTextToSpeechInstance()
        assertNotSame(first, second)
        shadowOf(second).onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        // No third engine (Google's) was opened.
        assertSame(second, ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertFalse(ReadingService.snapshot.playing)
        assertTrue(ReadingService.snapshot.error.isNotBlank())
        controller.destroy()
    }

    @Test fun `engine order puts the chosen and open-source engines first and uses Google only when chosen or alone`() {
        val hayai = "dev.ahmedmohamed.hayaitts"
        val sherpa = "com.k2fsa.sherpa.onnx.tts.engine"
        assertEquals(listOf(hayai, sherpa), SpeechEngines.order(listOf(SpeechEngines.GOOGLE, hayai, sherpa), null))
        assertEquals(listOf(sherpa, hayai), SpeechEngines.order(listOf(SpeechEngines.GOOGLE, hayai, sherpa), sherpa))
        assertEquals(listOf(SpeechEngines.GOOGLE, hayai), SpeechEngines.order(listOf(SpeechEngines.GOOGLE, hayai), SpeechEngines.GOOGLE))
        assertEquals(listOf(SpeechEngines.GOOGLE), SpeechEngines.order(listOf(SpeechEngines.GOOGLE), null))
        assertEquals(emptyList<String>(), SpeechEngines.order(emptyList(), null))
    }

    @Test fun `among equally rated open-source voices the stronger model family is the default`() {
        val piper = Voice("en_US-amy-medium", Locale.US, Voice.QUALITY_NORMAL, 300, false, emptySet())
        val kokoro = Voice("kokoro-multi-lang-v1_0#3#af_heart", Locale.US, Voice.QUALITY_NORMAL, 300, false, emptySet())
        assertEquals(kokoro, SpeechVoices.choose(listOf(piper, kokoro), Locale.US))
        assertEquals(piper, SpeechVoices.choose(listOf(piper, kokoro), Locale.US, "en_US-amy-medium"))
    }

    @Test fun `voice names show the speaker and model`() {
        assertEquals("af_heart \u00b7 kokoro multi lang v1 0", SpeechVoices.displayName(Voice("kokoro-multi-lang-v1_0#3#af_heart", Locale.US, 300, 300, false, emptySet())))
        assertEquals("en us x tpf local", SpeechVoices.displayName(Voice("en-us-x-tpf-local", Locale.US, 300, 300, false, emptySet())))
    }

    @Test fun `failed reader initialization stays stopped on play instead of appearing to play silently`() {
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        controller.get().onStartCommand(Intent(context, ReadingService::class.java).putExtra("text", "Keep this text."), 0, 1)
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        shadowOf(engine).onInitListener.onInit(TextToSpeech.ERROR)
        shadowOf(Looper.getMainLooper()).idle()
        ReadingService.toggle(context)
        assertFalse(ReadingService.snapshot.playing)
        assertTrue(ReadingService.snapshot.error.isNotBlank())
        assertEquals("Keep this text.", ReadingService.snapshot.text)
        assertTrue(shadowOf(engine).spokenTextList.isEmpty())
        controller.destroy()
    }
}
