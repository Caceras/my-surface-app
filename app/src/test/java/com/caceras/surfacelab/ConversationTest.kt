package com.caceras.surfacelab

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The chat screen as a conversation rather than a list of unrelated
 * questions.
 *
 * Both things these guard shipped to a phone and were caught by looking at
 * it, which is the whole reason this file exists:
 *
 *  - every finished answer showed its own Markdown, because the callback
 *    that completes an answer handed back the raw string and threw away what
 *    the streaming path had already rendered. Markdown.render() had tests.
 *    Nothing tested the screen after an answer finished.
 *  - "list 10 more" was answered with fruit, because each message was sent
 *    with no history at all and "more" referred to nothing.
 *
 *     gradle testCoreDebugUnitTest
 */
@RunWith(AndroidJUnit4::class)
class ConversationTest {

    private val brain = StreamingBrain()

    @Before
    fun useStandIn() {
        Brains.useForTest(brain)
    }

    @After
    fun releaseStandIn() {
        Brains.useForTest(null)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Chat.clear(app)
    }

    // --------------------------------------------------------------- rig

    private fun launch(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).setup()

    private fun descendants(view: View): List<View> =
        if (view !is ViewGroup) listOf(view)
        else listOf(view) + (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }

    private fun content(activity: android.app.Activity): View =
        activity.findViewById(android.R.id.content)

    private fun composer(activity: android.app.Activity): EditText =
        descendants(content(activity)).filterIsInstance<EditText>().first()

    private fun send(activity: android.app.Activity) =
        descendants(content(activity))
            .filterIsInstance<android.widget.ImageButton>()
            .first { it.contentDescription == activity.getString(R.string.send) }
            .performClick()

    private fun bubbles(activity: android.app.Activity): List<String> {
        val scroll = descendants(content(activity)).filterIsInstance<ScrollView>().first()
        val column = scroll.getChildAt(0) as ViewGroup
        return (0 until column.childCount)
            .map { column.getChildAt(it) }
            .filterIsInstance<TextView>()
            .map { it.text.toString() }
    }

    /** Ask, and let the stand-in finish the answer. */
    private fun exchange(activity: android.app.Activity, question: String, answer: String) {
        composer(activity).setText(question)
        send(activity)
        brain.complete(answer)
    }

    // ----------------------------------------------------------- markdown

    @Test
    fun `a finished answer is rendered, not just the partials it replaced`() {
        val activity = launch().get()
        composer(activity).setText("three ideas")
        send(activity)

        // Exactly the shape the phone showed: rendered while streaming, then
        // overwritten with the raw string the moment the answer completed.
        brain.emit("1. **More nuanced")
        brain.complete("1. **More nuanced understanding:** better at inferring why")

        val answer = bubbles(activity).last()
        assertFalse("the finished answer still shows its asterisks: $answer",
            answer.contains("*"))
        assertTrue(answer.contains("More nuanced understanding:"))
        assertTrue(answer.contains("better at inferring why"))
    }

    @Test
    fun `a speaker label the model wrote is not shown as part of the answer`() {
        val activity = launch().get()
        exchange(activity, "hello", "Assistant: hello back")
        assertEquals("hello back", bubbles(activity).last())
    }

    // -------------------------------------------------------------- memory

    @Test
    fun `the first question is sent on its own`() {
        val activity = launch().get()
        composer(activity).setText("three ideas please")
        send(activity)
        assertEquals("three ideas please", brain.instruction)
    }

    @Test
    fun `the second question carries the first exchange`() {
        val activity = launch().get()
        exchange(activity, "give me three ideas", "1. one 2. two 3. three")

        composer(activity).setText("list 10 more")
        send(activity)

        val sent = brain.instruction
        assertTrue("the earlier question was not sent: $sent",
            sent.contains("give me three ideas"))
        assertTrue("the earlier answer was not sent: $sent",
            sent.contains("1. one 2. two 3. three"))
        assertTrue("the new question is not last: $sent",
            sent.trimEnd().endsWith("list 10 more"))
    }

    @Test
    fun `New forgets the conversation`() {
        val activity = launch().get()
        exchange(activity, "give me three ideas", "1. one 2. two 3. three")

        descendants(content(activity)).filterIsInstance<TextView>()
            .first { it.text.toString() == activity.getString(R.string.new_chat) }
            .performClick()

        assertTrue("the bubbles survived New", bubbles(activity).isEmpty())

        composer(activity).setText("list 10 more")
        send(activity)
        assertEquals("history survived New", "list 10 more", brain.instruction)
    }

    @Test
    fun `the conversation is still there when the app is opened again`() {
        val first = launch()
        exchange(first.get(), "give me three ideas", "1. one 2. two 3. three")
        first.pause().stop().destroy()

        val second = launch().get()
        val said = bubbles(second)
        assertEquals("the conversation was not restored", 2, said.size)
        assertEquals("give me three ideas", said[0])
        assertEquals("1. one 2. two 3. three", said[1])

        // And it is still context, not just something to look at.
        composer(second).setText("list 10 more")
        send(second)
        assertTrue(brain.instruction.contains("give me three ideas"))
    }

    @Test
    fun `a failed answer is not remembered as if it had worked`() {
        val activity = launch().get()
        composer(activity).setText("something")
        send(activity)
        brain.fail("The model is still downloading.")

        composer(activity).setText("again")
        send(activity)
        assertEquals("a failure was sent back as context", "again", brain.instruction)
    }
}
