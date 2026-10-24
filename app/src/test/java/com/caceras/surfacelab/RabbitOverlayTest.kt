package com.caceras.surfacelab

import android.app.NotificationManager
import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.io.ByteArrayInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The genie over every app: its windows, forms, Stop, take-over and Allow prompts, and the live notification. */
@RunWith(AndroidJUnit4::class)
class RabbitOverlayTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun windows(): List<View> = Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun find(tag: String) = windows().flatMap { views(it) }.firstOrNull { it.tag == tag }
    private fun waitUntil(what: String, done: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!done()) { idle(); if (System.currentTimeMillis() > until) fail("Timed out waiting for $what"); Thread.sleep(5) }
        idle()
    }

    private class Listener : Hands.Listener {
        val log = mutableListOf<String>()
        override fun stop() { log += "stop" }
        override fun steer(text: String) { log += "steer $text" }
        override fun handedBack() { log += "back" }
    }

    private lateinit var overlay: RabbitOverlay
    private val listener = Listener()

    @Before fun setUp() {
        Hands.resetForTest()
        context.getSharedPreferences("surfacelab", 0).edit().putBoolean("assistant_listen", false).commit()
        overlay = RabbitOverlay(context)
        Hands.attach(HandsTest.FakeScreen(null), overlay)
        Hands.listener = listener
    }
    @After fun tearDown() { overlay.close(); Hands.resetForTest(); ConnectedAI.httpForTest = null; PhoneAgent.keyForTest = null; PhoneAgent.onlineForTest = null; PhoneAgent.current = null; Backend.resetForTest(); ServerJobs.resetForTest(); idle() }

    @Test fun `the genie opens as a bar over the app, folds into the rabbit and closes without leaving windows`() {
        assertTrue(windows().isEmpty())
        ScreenContexts.begin(); ScreenContexts.text("com.android.settings", "Network & internet")
        overlay.summon(); idle()
        assertEquals("a stage for the moves and a panel for the genie", 2, windows().size)
        assertEquals(RabbitOverlay.Form.BAR, overlay.form)
        assertEquals(View.VISIBLE, find("rabbit-bar")!!.visibility)
        assertEquals(View.VISIBLE, find("rabbit-screen")!!.visibility)
        // The stage never takes a touch; the panel never takes the keyboard unless the owner types.
        val params = windows().map { it.layoutParams as WindowManager.LayoutParams }
        assertTrue(params.all { it.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY })
        assertTrue(params[0].flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertTrue(params[1].flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue("touches elsewhere are seen, to pause the hands", params[1].flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH != 0)
        overlay.collapse(); idle()
        assertEquals(RabbitOverlay.Form.BUBBLE, overlay.form)
        assertEquals(View.GONE, find("rabbit-bar")!!.visibility); assertEquals(View.VISIBLE, find("rabbit-bubble")!!.visibility)
        overlay.expand(content = false); idle()
        assertEquals(RabbitOverlay.Form.BAR, overlay.form)
        // Hidden for a screenshot, then back.
        overlay.hideForCapture(true); assertTrue(windows().all { it.visibility == View.INVISIBLE })
        overlay.hideForCapture(false); assertTrue(windows().all { it.visibility == View.VISIBLE })
        overlay.close(); idle()
        assertTrue(windows().isEmpty())
        assertTrue("closing drops the summoned screen", ScreenContexts.current.empty)
    }

    @Test fun `Stop is on screen while the hands work and stops everything`() {
        overlay.show(); idle()
        Hands.begin(); idle()
        val stop = find("rabbit-stop")!!
        assertEquals(View.VISIBLE, stop.visibility)
        overlay.collapse(); idle()
        assertEquals("folded into the rabbit, Stop stays", View.VISIBLE, find("rabbit-bubble-stop")!!.visibility)
        find("rabbit-bubble-stop")!!.performClick(); idle()
        assertEquals(listOf("stop"), listener.log)
        assertTrue(Hands.stopped)
        assertEquals(View.GONE, find("rabbit-stop")!!.visibility)
        assertEquals("Stopped.", (find("rabbit-status") as TextView).text.toString())
    }

    @Test fun `taking over shows Continue, which hands the screen back`() {
        overlay.show(); Hands.begin(); idle()
        Hands.userTouched(); idle()
        assertEquals("paused", overlay.mode)
        assertTrue((find("rabbit-status") as TextView).text.startsWith("You have the screen"))
        find("rabbit-continue")!!.performClick(); idle()
        assertEquals(listOf("back"), listener.log); assertFalse(Hands.paused)
        // A password field: the rabbit asks the person to type it.
        overlay.handOver(HandsTools.TAKE_OVER); idle()
        assertEquals("Take over to type your password, then tap Continue.", (find("rabbit-status") as TextView).text.toString())
    }

    @Test fun `the rabbit lands beside its target, above when there is room, never off screen`() {
        overlay.show(); idle()
        val density = context.resources.displayMetrics.density
        val size = 56 * density
        val (x, y) = overlay.landing(Rect(400, 600, 600, 700), 1080, 2400)
        assertEquals(500 - size * 0.9f, x, 0.5f); assertEquals(600 - size * 0.85f, y, 0.5f)
        val (_, below) = overlay.landing(Rect(0, 10, 200, 60), 1080, 2400)
        assertEquals("near the top it lands below", 60 + 4 * density, below, 0.5f)
        val (cx, cy) = overlay.landing(Rect(1070, 2390, 1080, 2400), 1080, 2400)
        assertTrue(cx <= 1080 - size && cy <= 2400 - size)
        // It flies there in about 250 ms, then the step runs; then it hops home.
        var ran = false
        overlay.flyTo(Rect(10, 10, 50, 50), "") { ran = true }
        assertFalse(ran)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RabbitOverlay.FLIGHT_MS + 50))
        assertTrue(ran)
        assertEquals(View.VISIBLE, find("rabbit-flyer")!!.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(RabbitOverlay.LINGER_MS + RabbitOverlay.FLIGHT_MS + 200))
        assertEquals(View.INVISIBLE, find("rabbit-flyer")!!.visibility)
    }

    @Test fun `an Allow prompt shows in the bar and the live notification, and Allow lets the step run`() {
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        val responses = LinkedBlockingQueue<String>()
        ConnectedAI.httpForTest = ConnectedAI.Http { _, _, _, onCall ->
            onCall(ConnectedAI.Call {})
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream((responses.poll(5, TimeUnit.SECONDS) ?: "data: [DONE]\n\n").toByteArray())) {}
        }
        fun call(name: String, args: String) = "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls")
            .put("delta", JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", 0).put("id", "c1").put("function", JSONObject().put("name", name).put("arguments", args))))))) + "\n\ndata: [DONE]\n\n"
        responses += call("phone_back", """{"why":"Close the menu"}""")
        responses += "data: {\"choices\":[{\"delta\":{\"content\":\"Klart.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val session = PhoneAgent.session(context, fresh = true)
        Hands.begin()
        overlay.show(); idle()
        PhoneAgent.ask(context, session, "Stäng menyn")
        waitUntil("the Allow prompt") { find("rabbit-allow") != null }
        assertEquals("approval", overlay.mode)
        assertNotNull("the app in front can be allowed always", find("rabbit-always") ?: find("rabbit-deny"))
        val notification = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(AgentNotifications.ID)
        assertNotNull(notification)
        assertEquals(listOf("Stop", "Allow", "Don't"), notification.actions.map { it.title.toString() })
        find("rabbit-allow")!!.performClick()
        waitUntil("the answer") { !session.running }
        assertEquals("Klart.", session.view().answer)
        assertEquals("done", session.view().steps.single().status)
        val done = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification(AgentNotifications.ID)
        assertEquals("Ægentica is done", done.extras.getString(android.app.Notification.EXTRA_TITLE))
    }

    @Test fun `the notification's Stop and reply reach the running agent`() {
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        val hold = java.util.concurrent.CountDownLatch(1)
        val sent = LinkedBlockingQueue<JSONObject>()
        ConnectedAI.httpForTest = ConnectedAI.Http { _, _, body, onCall ->
            onCall(ConnectedAI.Call {}); sent += JSONObject(body.toString(Charsets.UTF_8))
            hold.await(5, TimeUnit.SECONDS)
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream("data: [DONE]\n\n".toByteArray())) {}
        }
        val session = PhoneAgent.session(context, fresh = true)
        PhoneAgent.ask(context, session, "Leta upp något")
        waitUntil("the run") { session.running }
        AgentActionReceiver().onReceive(context, Intent(AgentNotifications.STOP))
        hold.countDown()
        waitUntil("the stop") { !session.running }
        assertTrue(session.view().stopped)
        assertTrue("Stop also stops the hands", Hands.stopped)
    }

    @Test fun `a job on aegentica-se shows in the genie, opens it when it asks, and the bar can talk to the job`() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, JSONObject>>()
        Backend.apiForTest = Backend.Api { tool, args -> calls += tool to args; JSONObject() }
        overlay.summon(); idle()
        overlay.collapse(); idle()
        val job = ServerJobs.jobs.getOrPut("j") { ServerJobs.Job("j", "https://aegentica.se/chat/j", null) }
        ServerJobs.apply(job, "status", JSONObject().put("state", "working").put("title", "Dog breeds"))
        ServerJobs.apply(job, "input", JSONObject("""{"requestId":"r1","kind":"tool-approval","prompt":"Generate 40 pictures","options":[{"id":"approve","label":"Approve"},{"id":"cancel","label":"Cancel"}],"cost":{"usd":1.2,"count":40,"unit":"image"}}"""))
        // What the job sends reaches the genie the way the follower publishes it.
        ServerJobs.watch(context, "j", "https://aegentica.se/chat/j", null); idle()
        assertEquals("the genie opens when the job asks", RabbitOverlay.Form.EXPANDED, overlay.form)
        assertEquals("Approve · $1.20 for 40 images", (find("job-approve") as TextView).text.toString())
        assertTrue((find("rabbit-status") as TextView).text.startsWith("Ægentica asks"))
        assertNull("an approval is answered before the job hears more", find("job-tell"))
        find("job-approve")!!.performClick()
        waitUntil("the approval") { calls.any { it.first == "job_respond" } && find("job-tell") != null }
        find("job-tell")!!.performClick(); idle()
        assertEquals("Tell the job…", (find("rabbit-input") as android.widget.EditText).hint.toString())
        overlay.submit("Only small breeds", aloud = false)
        waitUntil("the message to the job") { calls.any { it.first == "job_message" } }
        assertEquals("Only small breeds", calls.first { it.first == "job_message" }.second.getString("message"))
        assertEquals("the bar asks the agent again after that", "Ask Ægentica", (find("rabbit-input") as android.widget.EditText).hint.toString())
    }

    @Test fun `a door with words starts the floating genie on them, without listening first`() {
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        val sent = LinkedBlockingQueue<JSONObject>()
        ConnectedAI.httpForTest = ConnectedAI.Http { _, _, body, onCall ->
            onCall(ConnectedAI.Call {}); sent += JSONObject(body.toString(Charsets.UTF_8))
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"God morgon.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".toByteArray())) {}
        }
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().putBoolean("float", true).commit()
        Genie.ask(context, "Planera min morgon", Genie.Origin.TAG)
        idle()
        assertEquals(RabbitOverlay.Form.EXPANDED, overlay.form)
        assertFalse("it does not open the microphone", overlay.conversation)
        waitUntil("the answer") { PhoneAgent.current?.running == false && (find("rabbit-answer") as TextView).text.toString() == "God morgon." }
        val messages = sent.poll()!!.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(messages.toString().contains("NFC tag"))
    }
}
