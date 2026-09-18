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

    @Test fun `saved reader selects the higher quality voice and queues rather than flushes sentences`() {
        val locale = Locale.getDefault()
        val high = Voice("high", locale, Voice.QUALITY_HIGH, 300, false, emptySet())
        ShadowTextToSpeech.addVoice(Voice("basic", locale, Voice.QUALITY_LOW, 100, false, emptySet()))
        ShadowTextToSpeech.addVoice(high)
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
        controller.get().onStartCommand(Intent(context, ReadingService::class.java).putExtra("text", "First sentence. Second sentence. Third sentence. Fourth sentence."), 0, 1)
        shadowOf(engine).onInitListener.onInit(TextToSpeech.SUCCESS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(high, shadowOf(engine).currentVoice)
        assertEquals(TextToSpeech.QUEUE_ADD, shadowOf(engine).queueMode)
        assertEquals("First sentence. Second sentence. Third sentence. Fourth sentence.", shadowOf(engine).spokenTextList.joinToString(""))
        controller.destroy()
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
