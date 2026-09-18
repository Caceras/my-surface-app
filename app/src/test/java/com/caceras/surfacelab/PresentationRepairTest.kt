package com.caceras.surfacelab

import android.content.Intent
import android.graphics.Typeface
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.util.ReflectionHelpers

@RunWith(AndroidJUnit4::class)
class PresentationRepairTest {
    @After fun resetSpeech() { ShadowTextToSpeech.reset() }

    @Test fun `single and nested emphasis render rather than exposing delimiters`() {
        val result = Markdown.render("*italic* **bold** _under_ __strong__ ***both***") as Spanned
        assertEquals("italic bold under strong both", result.toString())
        val styles = result.getSpans(0, result.length, StyleSpan::class.java).map { it.style }
        assertTrue(Typeface.ITALIC in styles)
        assertTrue(Typeface.BOLD in styles)
        assertTrue(Typeface.BOLD_ITALIC in styles)
        assertEquals("bold italic", Markdown.strip("**bold *italic***"))
        assertEquals("italic bold", Markdown.strip("*italic **bold***"))
        assertEquals("2 * 3 and file_name", Markdown.strip("2 * 3 and file_name"))
        assertEquals("*literal*", Markdown.strip("\\*literal\\*"))
    }

    @Test fun `code keeps literal punctuation and incomplete emphasis remains readable`() {
        val result = Markdown.render("```kotlin\nval x = \"*literal*\"\n```\n**Done**") as Spanned
        assertEquals("val x = \"*literal*\"\nDone", result.toString())
        assertEquals(1, result.getSpans(0, result.length, TypefaceSpan::class.java).size)
        assertEquals("*code*", Markdown.strip("`*code*`"))
        assertEquals("A **partial", Markdown.strip("A **partial"))
    }

    @Test fun `streaming keeps its text buffer and refreshes closing-marker formatting`() {
        val view = TextView(RuntimeEnvironment.getApplication())
        Markdown.update(view, "## Header\nFirst *wor")
        val buffer = view.text
        Markdown.update(view, "## Header\nFirst *word*.")
        assertSame(buffer, view.text)
        assertEquals("Header\nFirst word.", view.text.toString())
        val result = view.text as Spanned
        assertTrue(result.getSpans(0, 6, StyleSpan::class.java).any { it.style == Typeface.BOLD })
        val start = result.toString().indexOf("word")
        assertTrue(result.getSpans(start, start + 4, StyleSpan::class.java).any { it.style == Typeface.ITALIC })
    }

    @Test fun `a stream correction can change an earlier style without changing plain text`() {
        val view = TextView(RuntimeEnvironment.getApplication())
        Markdown.update(view, "Header\nBody")
        Markdown.update(view, "**Header**\nBody")
        assertEquals("Header\nBody", view.text.toString())
        assertTrue((view.text as Spanned).getSpans(0, 6, StyleSpan::class.java).any { it.style == Typeface.BOLD })
    }

    @Test fun `duplicate provider chunks do not repeatedly reflow the answer`() {
        val painted = mutableListOf<String>()
        val updates = StreamUpdates { painted.add(it) }
        updates.offer("First")
        repeat(100) { updates.offer("First") }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(listOf("First"), painted)
        updates.offer("First second")
        updates.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        assertEquals(listOf("First"), painted)
    }

    @Test fun `reader ranks installed voice quality without selecting network or missing voices`() {
        fun voice(name: String, quality: Int, network: Boolean = false, missing: Boolean = false) =
            Voice(name, Locale.US, quality, Voice.LATENCY_NORMAL, network,
                if (missing) setOf(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) else emptySet())
        val best = voice("best-installed", Voice.QUALITY_HIGH)
        val candidates = listOf(voice("first-robotic", Voice.QUALITY_LOW),
            voice("network", Voice.QUALITY_VERY_HIGH, network = true),
            voice("not-installed", Voice.QUALITY_VERY_HIGH, missing = true), best)
        assertEquals(best, ReadingService.chooseVoice(candidates, Locale.US))
        assertNull(ReadingService.chooseVoice(candidates, Locale.forLanguageTag("sv-SE")))
    }

    @Test fun `reader queues bounded lookahead and ignores completion after pause`() {
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        try {
            val service = controller.get()
            // Isolate queue/lifecycle behavior from real engine installation and acoustics.
            ReflectionHelpers.setField(service, "ready", true)
            val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
            val progress = ReflectionHelpers.getField<UtteranceProgressListener>(service, "progress")
            engine.setOnUtteranceProgressListener(progress)
            service.onStartCommand(Intent(service, ReadingService::class.java).putExtra("text",
                "First sentence. Second sentence. Third sentence. Fourth sentence."), 0, 1)
            assertEquals(3, shadowOf(engine).spokenTextList.size)
            val generation = ReflectionHelpers.getField<Int>(service, "generation")
            ReadingService.pauseForCapture()
            val position = ReadingService.snapshot.index
            progress.onStart("$generation:2")
            progress.onDone("$generation:2")
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(ReadingService.snapshot.playing)
            assertEquals(position, ReadingService.snapshot.index)
            assertEquals(3, shadowOf(engine).spokenTextList.size)
        } finally { controller.destroy() }
    }

    @Test fun `failed voice initialization stays visible after pressing play`() {
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        try {
            val service = controller.get()
            service.onStartCommand(Intent(service, ReadingService::class.java).putExtra("text", "Saved text."), 0, 1)
            val engine = ShadowTextToSpeech.getLastTextToSpeechInstance()
            shadowOf(engine).onInitListener.onInit(TextToSpeech.ERROR)
            shadowOf(Looper.getMainLooper()).idle()
            val problem = ReadingService.snapshot.error
            assertTrue(problem.isNotBlank())
            ReadingService.toggle(service)
            assertFalse(ReadingService.snapshot.playing)
            assertEquals(problem, ReadingService.snapshot.error)
            assertTrue(shadowOf(engine).spokenTextList.isEmpty())
        } finally { controller.destroy() }
    }

    @Test fun `long reader chunks preserve text and avoid splitting ordinary words`() {
        val text = "word ".repeat(1000) + "End."
        val parts = ReadingService.segments(text)
        assertEquals(text, parts.joinToString(""))
        assertTrue(parts.all { it.length <= 2000 })
        assertTrue(parts.dropLast(1).all { it.last().isWhitespace() })
    }

    @Test fun `indexed echo detection preserves the original exact substring rule`() {
        for (task in Task.values()) {
            val instruction = Prompts.system(task).lowercase()
            val answers = mutableListOf("Unrelated answer. ".repeat(80), instruction, "Short answer.")
            if (instruction.length >= 50) {
                for (start in listOf(0, 1, 17, instruction.length - 50).distinct().filter { it + 50 <= instruction.length })
                    answers += "prefix ".repeat(60) + instruction.substring(start, start + 50) + " suffix"
            }
            for (answer in answers) {
                val expected = instruction.windowed(50).any { answer.lowercase().contains(it) }
                assertEquals("Echo rule for $task", expected, Prompts.isEcho(answer, task))
            }
        }
    }
}
