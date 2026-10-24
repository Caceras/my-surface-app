package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The assistant sheet on the phone agent: screen attached, Nano only offline, steering, and a sheet that stays put. */
@RunWith(AndroidJUnit4::class)
class AssistantSheetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun byTag(root: View, tag: String) = views(root).first { it.tag == tag }
    private fun text(root: View, tag: String) = (byTag(root, tag) as TextView).text.toString()
    private fun waitUntil(what: String, timeoutMs: Long = 5000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) { idle(); if (System.currentTimeMillis() > until) fail("Timed out waiting for $what"); Thread.sleep(5) }
        idle()
    }

    private class StubBrain : SurfaceBrain {
        var asked: String? = null; var finish: ((BrainResult) -> Unit)? = null
        override val tasks = listOf(Task.ASK, Task.ASSIST)
        override fun status(context: Context, onStatus: (BrainStatus) -> Unit) = onStatus(BrainStatus("Stub", true))
        override fun prepare(context: Context, onStatus: (BrainStatus) -> Unit) = status(context, onStatus)
        override fun run(context: Context, task: Task, input: String, instruction: String, onPartial: (String) -> Unit, onResult: (BrainResult) -> Unit) { asked = instruction; finish = onResult }
    }

    private class Server : ConnectedAI.Http {
        val sent = LinkedBlockingQueue<JSONObject>()
        val responses = LinkedBlockingQueue<() -> ConnectedAI.Response>()
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, onCall: (ConnectedAI.Call) -> Unit): ConnectedAI.Response {
            onCall(ConnectedAI.Call {})
            sent += JSONObject(body.toString(Charsets.UTF_8))
            return (responses.poll(5, TimeUnit.SECONDS) ?: error("No scripted response"))()
        }
        fun stream(vararg parts: String) = responses.add {
            val body = parts.joinToString("") { p -> "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", p)))) + "\n\n" } +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"cost\":0.0012}}\n\ndata: [DONE]\n\n"
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream(body.toByteArray())) {}
        }
    }

    private val sheet = "SEO task\nHemsidetyp?\nMålgrupp?"
    private lateinit var server: Server
    private lateinit var brain: StubBrain

    @Before fun setUp() {
        context.getSharedPreferences("surfacelab", 0).edit().clear().putBoolean("assistant_listen", false).commit()
        context.getSharedPreferences("phone-agent", 0).edit().clear().commit()
        context.getSharedPreferences("connected-ai", 0).edit().clear().commit()
        server = Server(); ConnectedAI.httpForTest = server
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        brain = StubBrain(); Brains.useForTest(brain)
        ScreenContexts.begin()
    }
    @After fun tearDown() {
        ConnectedAI.httpForTest = null; PhoneAgent.keyForTest = null; PhoneAgent.onlineForTest = null; PhoneAgent.current = null
        Brains.useForTest(null); ScreenContexts.begin(); idle()
    }

    private fun open() = Robolectric.buildActivity(AssistantActivity::class.java, AssistantActivity.intent(context)).setup().also {
        ScreenContexts.text("com.google.android.apps.docs.editors.sheets", sheet)
        ScreenContexts.screenshot(Bitmap.createBitmap(2400, 1080, Bitmap.Config.ARGB_8888)); idle()
    }

    @Test fun `a question goes to the agent with the screen and its screenshot, and the answer streams in the sheet`() {
        server.stream("Hemsidetyp: ", "E-handel")
        val activity = open()
        val root = activity.get().window.decorView
        assertTrue(byTag(root, "assistant-model").contentDescription.contains("claude-sonnet-5.5"))
        assertNull("signed in: no sign-in chip", views(root).firstOrNull { it.tag == "assistant-sign-in" })
        (byTag(root, "assistant-input") as TextView).text = "Fyll i det som saknas"
        byTag(root, "assistant-ask").performClick()
        waitUntil("the answer") { text(root, "assistant-answer") == "Hemsidetyp: E-handel" && !PhoneAgent.current!!.running }
        val sent = server.sent.poll()!!
        val user = sent.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(user.getJSONObject(0).getString("text").contains("<screen app=\"Sheets\""))
        assertTrue(user.getJSONObject(0).getString("text").contains("Målgrupp?"))
        assertEquals("image_url", user.getJSONObject(1).getString("type"))
        assertTrue(user.getJSONObject(2).getString("text").startsWith("Fyll i det som saknas"))
        assertTrue(text(root, "assistant-status").startsWith("Ægentica · claude-sonnet-5.5"))
        assertNotNull(views(root).firstOrNull { it.tag == "assistant-copy" })
        // A follow-up continues the conversation without sending the screen again.
        server.stream("Målgrupp: småföretag")
        (byTag(root, "assistant-input") as TextView).text = "Och målgrupp?"
        byTag(root, "assistant-ask").performClick()
        waitUntil("the follow-up") { text(root, "assistant-answer") == "Målgrupp: småföretag" && !PhoneAgent.current!!.running }
        val follow = server.sent.poll()!!.getJSONArray("messages")
        assertEquals(4, follow.length())
        assertFalse(follow.getJSONObject(3).toString().contains("image_url"))
        activity.pause().stop().destroy()
    }

    @Test fun `Gemini Nano answers only offline or without a key, and says so`() {
        PhoneAgent.onlineForTest = false
        val activity = open()
        val root = activity.get().window.decorView
        byTag(root, "assistant-ask").performClick(); idle()
        assertEquals("Explain what is on this screen.", brain.asked)
        assertTrue(text(root, "assistant-status").contains("Offline · Gemini Nano on this phone"))
        brain.finish!!(BrainResult("Ett kalkylark.", true)); idle()
        assertEquals(0, server.sent.size)
        activity.pause().stop().destroy()

        // The connection drops during the request: Nano takes the same question, labelled.
        PhoneAgent.onlineForTest = true
        server.responses.add { PhoneAgent.onlineForTest = false; throw IOException("Unable to resolve host openrouter.ai") }
        brain.asked = null
        val second = open()
        val root2 = second.get().window.decorView
        (byTag(root2, "assistant-input") as TextView).text = "Vad står det?"
        byTag(root2, "assistant-ask").performClick()
        waitUntil("the fallback") { brain.asked != null }
        assertEquals("Vad står det?", brain.asked)
        assertTrue(text(root2, "assistant-status").contains("Offline · Gemini Nano"))
        second.pause().stop().destroy()

        // Without a key: Nano answers, and one chip signs in to OpenRouter.
        PhoneAgent.keyForTest = null; PhoneAgent.onlineForTest = true; brain.asked = null
        val third = open()
        val root3 = third.get().window.decorView
        assertNotNull(views(root3).firstOrNull { it.tag == "assistant-sign-in" })
        assertTrue(byTag(root3, "assistant-model").contentDescription.contains("Gemini Nano"))
        byTag(root3, "assistant-ask").performClick(); idle()
        assertEquals("Explain what is on this screen.", brain.asked)
        assertEquals("Gemini Nano · on this phone", text(root3, "assistant-status").substringBefore(" · sees").let { if (it.startsWith("Reading the screen · ")) it.removePrefix("Reading the screen · ") else it })
        third.pause().stop().destroy()
    }

    @Test fun `words sent while it works steer it, and leaving stops it`() {
        val release = CountDownLatch(1)
        server.responses.add {
            release.await(5, TimeUnit.SECONDS)
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"Halv\"}}]}\n\ndata: [DONE]\n\n".toByteArray())) {}
        }
        server.stream("Okej, på engelska.")
        val activity = open()
        val root = activity.get().window.decorView
        (byTag(root, "assistant-input") as TextView).text = "Översätt"
        byTag(root, "assistant-ask").performClick()
        waitUntil("the run") { PhoneAgent.current?.running == true }
        assertEquals("Stop", byTag(root, "assistant-ask").contentDescription)
        (byTag(root, "assistant-input") as TextView).text = "På engelska"
        assertEquals("typing turns Stop into a steer", "Tell the agent", byTag(root, "assistant-ask").contentDescription)
        byTag(root, "assistant-ask").performClick()
        release.countDown()
        waitUntil("the steered answer") { text(root, "assistant-answer") == "Okej, på engelska." && !PhoneAgent.current!!.running }
        server.sent.poll()
        val steered = server.sent.poll()!!.getJSONArray("messages")
        assertTrue(steered.getJSONObject(steered.length() - 1).toString().contains("På engelska"))
        assertEquals("Översätt", text(root, "assistant-question"))

        // Leaving the sheet stops a run: nothing continues out of sight.
        val hold = CountDownLatch(1)
        server.responses.add { hold.await(5, TimeUnit.SECONDS); throw IOException("Socket closed") }
        (byTag(root, "assistant-input") as TextView).text = "En till"
        byTag(root, "assistant-ask").performClick()
        waitUntil("the second run") { PhoneAgent.current?.running == true }
        val session = PhoneAgent.current!!
        activity.pause()
        hold.countDown()
        waitUntil("the stop") { !session.running }
        assertTrue(session.view().stopped)
        activity.stop().destroy()
    }

    @Test fun `the sheet keeps clear of the gesture bar and keyboard and never shrinks while an answer streams`() {
        val activity = open()
        val window = activity.get().window
        val types = window.attributes.fitInsetsTypes
        assertTrue("clear of the navigation bar", types and WindowInsets.Type.navigationBars() != 0)
        assertTrue("clear of the keyboard", types and WindowInsets.Type.ime() != 0)
        assertFalse(window.attributes.isFitInsetsIgnoringVisibility)
        val scroll = byTag(window.decorView, "assistant-scroll") as AssistantActivity.CappedScroll
        val density = context.resources.displayMetrics.density
        // Less room than half the screen, so the room is what caps it.
        val room = (context.resources.displayMetrics.heightPixels * 0.4f).toInt()
        scroll.roomForTest = room
        val answer = byTag(window.decorView, "assistant-answer") as TextView
        val spec = View.MeasureSpec.makeMeasureSpec((400 * density).toInt(), View.MeasureSpec.EXACTLY)
        fun measure(): Int { scroll.measure(spec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)); return scroll.measuredHeight }
        answer.text = (1..6).joinToString("\n") { "Rad $it" }
        val grown = measure()
        answer.text = "Rad 1"
        assertEquals("a shorter re-render keeps the height", grown, measure())
        answer.text = (1..200).joinToString("\n") { "Rad $it" }
        assertEquals("capped by the room left on screen", room, measure())
        scroll.roomForTest = (100 * density).toInt()
        assertEquals("the keyboard opening makes it smaller", (100 * density).toInt(), measure())
        scroll.reset(); scroll.roomForTest = room; answer.text = "Rad 1"
        assertTrue("a new question starts small again", measure() < grown)
        activity.pause().stop().destroy()
    }

    @Test fun `opened from inside the app with text, an answer can go back to whoever asked`() {
        server.stream("See you tomorrow")
        var back: String? = null
        val intent = AssistantActivity.withText(context, "Vi ses i morgon", app = "com.google.android.apps.messaging", editable = true) { back = it }
        assertNull("the handback never rides in the intent", intent.extras!!.keySet().firstOrNull { intent.extras!!.get(it) is Function<*> })
        val activity = Robolectric.buildActivity(AssistantActivity::class.java, intent).setup()
        val root = activity.get().window.decorView
        assertEquals("Selection", text(root, "assistant-seen"))
        views(root).first { it is TextView && it.text.toString() == "Improve" }.performClick()
        waitUntil("the answer") { PhoneAgent.current?.running == false && text(root, "assistant-answer").isNotBlank() }
        val selection = server.sent.poll()!!.getJSONArray("messages").getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(selection, selection.startsWith("<selection app=") && selection.contains("package=\"com.google.android.apps.messaging\">\nVi ses i morgon\n</selection>"))
        byTag(root, "assistant-replace").performClick(); idle()
        assertEquals("See you tomorrow", back)
        assertTrue(activity.get().isFinishing)
        activity.pause().stop().destroy()
    }

    @Test fun `a door with words starts the genie on them at once, and a continued keyboard conversation can put its answer back`() {
        server.stream("Tre punkter.")
        val intent = Genie.intent(context, "Mötet flyttas till fredag", editable = true, origin = Genie.Origin.KEYBOARD, request = "Gör det kortare")
        val activity = Robolectric.buildActivity(AssistantActivity::class.java, intent).setup()
        val root = activity.get().window.decorView
        waitUntil("the answer") { PhoneAgent.current?.running == false && text(root, "assistant-answer") == "Tre punkter." }
        val user = server.sent.poll()!!.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(user.getJSONObject(user.length() - 1).getString("text").startsWith("Gör det kortare"))
        activity.pause().stop().destroy()
        // The keyboard continues the same conversation in the sheet; the answer can go back to its field.
        var back: String? = null
        val resumed = Robolectric.buildActivity(AssistantActivity::class.java, AssistantActivity.resume(context) { back = it }).setup()
        val again = resumed.get().window.decorView
        idle()
        assertEquals("Put it in the field", text(again, "assistant-replace"))
        byTag(again, "assistant-replace").performClick(); idle()
        assertEquals("Tre punkter.", back)
        resumed.pause().stop().destroy()
    }
}
