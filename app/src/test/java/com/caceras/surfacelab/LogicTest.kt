package com.caceras.surfacelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM tests -- no Android, no Robolectric, instant. */
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

    private fun assertNotNull(value: Any?) = assertTrue(value != null)
    private fun assertNull(value: Any?) = assertTrue("expected null, got $value", value == null)
}
