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

    @Test
    fun `new saves a recoverable conversation and switching preserves the next draft`() {
        val activity = launch().get()
        exchange(activity, "Plan a weekend", "Take a walk.")
        descendants(content(activity)).filterIsInstance<TextView>().first { it.text == "New" }.performClick()
        val saved = Chat.archives(activity).single()
        assertTrue(Chat.load(activity).isEmpty())
        Chat.saveDraft(activity, "A different thought")
        assertTrue(Chat.openArchive(activity, saved.id))
        assertEquals(listOf(Turn("Plan a weekend", "Take a walk.")), Chat.load(activity))
        assertEquals("A different thought", Chat.archives(activity).single().draft)
    }

    @Test
    fun `backup includes archived conversations and remains compatible with older backups`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        Chat.save(context, listOf(Turn("Earlier", "Answer")))
        Chat.archiveCurrent(context, "Follow up")
        Chat.clear(context)
        Chat.saveDraft(context, "Current draft")
        val backup = Chat.backup(context)
        assertEquals("Current draft", Chat.readBackup(backup).second)
        context.getSharedPreferences("surfacelab", 0).edit().remove("conversations").apply()
        Chat.restoreArchives(context, backup)
        assertEquals("Follow up", Chat.archives(context).single().draft)
        assertEquals(emptyList<Turn>(), Chat.readBackup("""{"format":"surface-chat-v1","turns":[],"draft":""}""").first)
    }

    @Test
    fun `streaming leaves the reading position alone until latest reply is requested`() {
        val activity = launch().get()
        exchange(activity, "Earlier question", "Earlier answer. ".repeat(400))
        val decor = activity.window.decorView
        fun layout() {
            decor.measure(View.MeasureSpec.makeMeasureSpec(activity.dp(411), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(activity.dp(914), View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, activity.dp(411), activity.dp(914))
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        layout()
        composer(activity).setText("Continue")
        send(activity)
        layout()
        val scroll = descendants(content(activity)).filterIsInstance<ScrollView>().first { it.tag == "conversation" }
        scroll.scrollTo(0, scroll.getChildAt(0).height)
        assertTrue("fixture needs a scrollable conversation", scroll.scrollY > 30)
        scroll.scrollTo(0, 30)
        val before = scroll.scrollY
        brain.emit("A new paragraph. ".repeat(100))
        layout()
        brain.complete("A new paragraph. ".repeat(100))
        layout()
        assertEquals("streaming moved the reader", before, scroll.scrollY)
        val latest = descendants(content(activity)).first { it.tag == "latest-reply" }
        assertEquals(View.VISIBLE, latest.visibility)
        latest.performClick()
        assertEquals(View.GONE, latest.visibility)
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
        val scroll = descendants(content(activity)).filterIsInstance<ScrollView>().first { it.tag == "conversation" }
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
    fun `the system prompt is never shown as an answer`() {
        // Exactly what the phone showed: a contentless question, and Nano
        // reciting its instructions back. The screen printed the lot.
        val activity = launch().get()
        composer(activity).setText("??")
        send(activity)
        brain.emit(Prompts.system(Task.ASK))
        brain.complete(Prompts.system(Task.ASK))

        val shown = bubbles(activity).last()
        assertFalse("the instruction reached the screen: $shown",
            shown.contains("concise assistant running on the user"))
        assertEquals(activity.getString(R.string.echoed), shown)
    }

    @Test
    fun `an echoed instruction is not remembered as a real turn`() {
        val activity = launch().get()
        composer(activity).setText("??")
        send(activity)
        brain.complete(Prompts.system(Task.ASK))

        composer(activity).setText("hello")
        send(activity)
        assertEquals("the echo was sent back as context", "hello", brain.instruction)
    }

    @Test
    fun `an answer that ran out of room says so`() {
        val activity = launch().get()
        composer(activity).setText("write me 2000 words")
        send(activity)
        brain.complete("The tapestry of football is woven with legends. ".repeat(8) +
            "His influence wasn")

        val shown = bubbles(activity).last()
        assertTrue("nothing told the reader it was cut off: $shown",
            shown.contains("stopped here"))
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
    @Test
    fun `New during streaming cannot resurrect cleared history`() {
        val activity = launch().get()
        composer(activity).setText("old question")
        send(activity)
        descendants(content(activity)).filterIsInstance<TextView>()
            .first { it.text == activity.getString(R.string.new_chat) }.performClick()
        brain.emit("late partial")
        brain.complete("late answer")
        assertTrue(bubbles(activity).isEmpty())
        assertTrue(Chat.load(activity).isEmpty())
        assertEquals(null, ResultStore.lastText(activity))
        composer(activity).setText("new question")
        send(activity)
        assertEquals("new question", brain.instruction)
    }

    @Test
    fun `stopping restores a question and ignores later callbacks`() {
        val activity = launch().get()
        composer(activity).setText("please explain")
        send(activity)
        brain.emit("Partial")
        descendants(content(activity)).filterIsInstance<android.widget.ImageButton>()
            .first { it.contentDescription == activity.getString(R.string.stop_response) }
            .performClick()
        brain.complete("Should not be saved")
        assertEquals("please explain", composer(activity).text.toString())
        assertTrue(Chat.load(activity).isEmpty())
        assertFalse(bubbles(activity).contains("Should not be saved"))
    }

    @Test
    fun `failed request restores the prompt without destroying a newer draft`() {
        val activity = launch().get()
        composer(activity).setText("first prompt")
        send(activity)
        composer(activity).setText("next draft")
        brain.fail("Unavailable")
        assertEquals("next draft", composer(activity).text.toString())
    }

    @Test
    fun `draft survives leaving the app`() {
        val controller = launch()
        composer(controller.get()).setText("unfinished thought")
        controller.pause().stop().destroy()
        assertEquals("unfinished thought", composer(launch().get()).text.toString())
    }

    @Test
    fun `shared text is staged and does not send or replace a draft`() {
        val controller = launch()
        val activity = controller.get()
        composer(activity).setText("my draft")
        controller.newIntent(android.content.Intent(android.content.Intent.ACTION_SEND)
            .putExtra(android.content.Intent.EXTRA_TEXT, "selected material"))
        assertEquals("my draft\n\nselected material", composer(activity).text.toString())
        assertEquals(0, brain.runs)
    }

    @Test
    fun `backups round trip conversation and draft and reject unrelated JSON`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        Chat.save(context, listOf(Turn("Hello", "Hi there")))
        Chat.saveDraft(context, "Next thought")
        val restored = Chat.readBackup(Chat.backup(context))
        assertEquals(listOf(Turn("Hello", "Hi there")), restored.first)
        assertEquals("Next thought", restored.second)
        org.junit.Assert.assertThrows(Exception::class.java) { Chat.readBackup("{}") }
        assertEquals(listOf(Turn("Hello", "Hi there")), Chat.load(context))
    }

}
