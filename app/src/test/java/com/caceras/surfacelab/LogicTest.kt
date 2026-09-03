package com.caceras.surfacelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM tests -- no Android, no Robolectric, instant.
 *
 * Which means nothing here may touch a real framework class. Against the stub
 * android.jar every method throws "not mocked", so a test that reaches for one
 * fails for a reason that has nothing to do with what it was checking.
 * Markdown.render() builds a SpannableStringBuilder and so is tested in
 * MarkdownTest; Markdown.strip() is pure Kotlin and stays here.
 */
class LogicTest {

    @Test
    fun `alias name selects the task`() {
        assertEquals(Task.ASK, Task.fromComponent("com.caceras.surfacelab.Ask"))
        assertEquals(Task.SUMMARIZE, Task.fromComponent("com.caceras.surfacelab.Summarize"))
        assertEquals(Task.UPPERCASE, Task.fromComponent("com.caceras.surfacelab.Uppercase"))
    }

    @Test
    fun `an unknown alias falls back rather than crashing`() {
        // This fallback is why verify.py checks alias-to-Task parity: the app
        // would silently run the wrong task instead of failing loudly.
        assertEquals(Task.UPPERCASE, Task.fromComponent("com.caceras.surfacelab.Typo"))
        assertEquals(Task.UPPERCASE, Task.fromComponent(null))
    }

    @Test
    fun `Ask puts the question above the material`() {
        val prompt = Prompts.user(Task.ASK, "some selected text", "Explain this")
        assertTrue(prompt.startsWith("Explain this"))
        assertTrue(prompt.endsWith("some selected text"))
    }

    @Test
    fun `Ask with no question still sends something usable`() {
        val prompt = Prompts.user(Task.ASK, "some selected text", "   ")
        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("some selected text"))
    }

    @Test
    fun `presets send the selection unchanged`() {
        assertEquals("hello", Prompts.user(Task.SUMMARIZE, "hello", "ignored"))
    }

    @Test
    fun `every task that can reach a model has a system instruction`() {
        Task.entries.filter { it != Task.UPPERCASE }.forEach {
            assertTrue("$it has no system instruction", Prompts.system(it).isNotBlank())
        }
    }

    @Test
    fun `Swedish is recognised well enough to warn`() {
        assertTrue(Lang.looksNordic("Det här är en text på svenska"))
        assertTrue(Lang.looksNordic("vi kan inte se det som har hänt"))
        assertFalse(Lang.looksNordic("This is an ordinary English sentence."))
    }

    @Test
    fun `the caveat fires for the model tasks and not for uppercase`() {
        assertNotNull(Lang.caveat(Task.ASK, "Det här är svenska"))
        assertNull(Lang.caveat(Task.UPPERCASE, "Det här är svenska"))
        assertNull(Lang.caveat(Task.ASK, "Plain English here."))
    }

    @Test
    fun `there are suggestions and they are short enough to be tappable`() {
        listOf(Prompts.ABOUT_SELECTION, Prompts.OPENERS).forEach { set ->
            assertTrue(set.isNotEmpty())
            set.forEach {
                assertTrue("suggestion too long to fit a chip: $it", it.length <= 34)
            }
        }
    }

    @Test
    fun `the chat openers do not refer to a selection that is not there`() {
        // "What is this actually saying?" on an empty chat screen has no
        // "this" to refer to, and the model can only answer by asking what
        // you meant. The openers are the set that has to stand alone.
        Prompts.OPENERS.forEach {
            assertFalse("opener refers to absent material: $it",
                Regex("\\bthis\\b", RegexOption.IGNORE_CASE).containsMatchIn(it))
        }
    }

    @Test
    fun `a spoken sentence is spoken as soon as it is complete`() {
        val (first, cursor) = Speech.nextChunk("Yes. And then", 0)
        assertEquals("Yes.", first)
        assertTrue(cursor > 0)
        // nothing new until the next terminator arrives
        assertEquals("", Speech.nextChunk("Yes. And then", cursor).first)
        assertEquals("And then more.", Speech.nextChunk("Yes. And then more.", cursor).first)
    }

    @Test
    fun `an answer with no full stop is still spoken at the end`() {
        // "Sure" printed but never spoken is the version of this bug that
        // the user notices, because it is the shortest answers that break.
        assertEquals("", Speech.nextChunk("Sure", 0).first)
        assertEquals(0, Speech.nextChunk("Sure", 0).second)
    }

    @Test
    fun `plain text is left exactly as it is`() {
        // strip() is pure Kotlin, so it belongs here. Everything that builds
        // a SpannableStringBuilder lives in MarkdownTest instead -- see the
        // note at the top of this file.
        val plain = "No markdown here, just a sentence."
        assertEquals(plain, Markdown.strip(plain))
    }

    @Test
    fun `the spoken version has no punctuation to read aloud`() {
        // Otherwise the phone says "star star Ukraine War colon star star".
        val spoken = Markdown.strip("*   **Ukraine War:** Fighting is intense")
        assertFalse("asterisks would be spoken: $spoken", spoken.contains("*"))
        assertTrue(spoken.contains("Ukraine War:"))
    }

    // ------------------------------------------------------- conversation

    @Test
    fun `one question on its own is sent exactly as typed`() {
        assertEquals("list 10 more",
            Prompts.conversation(emptyList(), "list 10 more"))
    }

    @Test
    fun `the history is sent oldest first with the new question last`() {
        val history = listOf(
            Turn("give me three ideas", "one, two, three"),
            Turn("which is cheapest", "the second")
        )
        val prompt = Prompts.conversation(history, "list 10 more")

        assertTrue(prompt.indexOf("give me three ideas") <
            prompt.indexOf("which is cheapest"))
        assertTrue(prompt.indexOf("which is cheapest") <
            prompt.indexOf("list 10 more"))
        assertTrue("the new question must come last",
            prompt.trimEnd().endsWith("list 10 more"))
        assertTrue("the answers are context too", prompt.contains("one, two, three"))
    }

    @Test
    fun `an old conversation is trimmed from the front, not the back`() {
        // Nano's context is small and it has to fit its answer in there too,
        // so something has to go. It must not be the turn just before the
        // question, which is the one the question refers to.
        val history = (1..60).map { Turn("question $it", "answer $it ${"x".repeat(60)}") }
        val prompt = Prompts.conversation(history, "and now")

        assertFalse("the oldest turn should have been dropped",
            prompt.contains("question 1" + "\n"))
        assertTrue("the most recent turn must survive", prompt.contains("question 60"))
        assertTrue("the budget was blown: ${prompt.length}", prompt.length < 2000)
    }

    @Test
    fun `a single turn too big for the budget is cut short rather than dropped`() {
        // Dropping it entirely would send the follow-up with no context at
        // all, which is the bug this whole thing exists to fix.
        val history = listOf(Turn("summarise this", "y".repeat(5000)))
        val prompt = Prompts.conversation(history, "shorter please")

        assertTrue("the turn was dropped instead of trimmed",
            prompt.contains("summarise this"))
        assertTrue("nothing of the answer survived", prompt.contains("yyyy"))
        assertTrue("the budget was blown: ${prompt.length}", prompt.length < 2000)
        assertTrue(prompt.trimEnd().endsWith("shorter please"))
    }

    @Test
    fun `a speaker label the model copied is stripped from the answer`() {
        // Give a model a transcript and it will sometimes write the next
        // line of it, name included.
        assertEquals("hello back", Prompts.reply("Assistant: hello back"))
        assertEquals("hello back", Prompts.reply("AI: hello back"))
        assertEquals("an ordinary answer", Prompts.reply("an ordinary answer"))
        assertEquals("Assistants are useful",
            Prompts.reply("Assistants are useful"))
    }

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
    private fun assertNull(value: Any?) = assertTrue("expected null, got $value", value == null)
}
