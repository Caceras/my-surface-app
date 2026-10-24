package com.caceras.surfacelab

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
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
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The browser inside the sheet: what may open, the agent's steps there with their tiers, and the sheet showing the pages. */
@RunWith(AndroidJUnit4::class)
class WebBrowserTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun waitUntil(what: String, timeoutMs: Long = 5000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) { idle(); if (System.currentTimeMillis() > until) fail("Timed out waiting for $what"); Thread.sleep(5) }
        idle()
    }

    /** The page script's answers, by call. */
    private val calls = CopyOnWriteArrayList<String>()
    private val answers = HashMap<String, String>()

    @Before fun setUp() {
        SheetBrowser.resetForTest(); idle()
        context.getSharedPreferences(WebTools.PREFS, 0).edit().clear().commit()
        context.getSharedPreferences("surfacelab", 0).edit().putBoolean("assistant_listen", false).commit()
        WebGuard.resolverForTest = { host -> listOf(InetAddress.getByName(if (host == "router.example") "192.168.1.1" else "93.184.216.34")) }
        SheetBrowser.jsForTest = { _, call -> calls += call; answers.entries.firstOrNull { call.startsWith(it.key) }?.value }
    }

    @After fun tearDown() {
        SheetBrowser.resetForTest(); ConnectedAI.httpForTest = null; PhoneAgent.keyForTest = null; PhoneAgent.onlineForTest = null; PhoneAgent.current = null; idle()
    }

    @Test fun `only public web pages open, never the phone's own network, a password in the address or Ægentica's own sites`() {
        assertNull(WebGuard.refuse("https://www.example.com/shop?q=1"))
        assertNull(WebGuard.refuse("http://example.com"))
        assertEquals("Only web pages can be opened.", WebGuard.refuse("javascript:alert(1)"))
        assertEquals("Only web pages can be opened.", WebGuard.refuse("file:///sdcard/a.html"))
        assertEquals("Only web pages can be opened.", WebGuard.refuse("intent://x#Intent;end"))
        assertEquals("Addresses with a password in them are not opened.", WebGuard.refuse("https://me:secret@example.com/"))
        assertEquals("Only pages on the standard web ports can be opened.", WebGuard.refuse("https://example.com:8443/"))
        listOf("http://localhost/", "http://127.0.0.1/", "http://192.168.1.1/admin", "http://10.0.0.5/", "http://172.20.1.1/", "http://100.70.1.1/",
            "http://169.254.169.254/latest", "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:192.168.0.1]/", "http://printer.local/", "http://router/", "http://0.0.0.0/")
            .forEach { assertEquals(it, "Pages on this phone's own network are not opened.", WebGuard.refuse(it)) }
        assertEquals("Ægentica's own sites open in the app, not in the agent's browser.", WebGuard.refuse("https://aegentica.se/settings/security"))
        assertEquals("Ægentica's own sites open in the app, not in the agent's browser.", WebGuard.refuse("https://www.aegentica.se/"))
        BarActivity.SITE.hosts.forEach { assertNotNull(WebGuard.refuse("https://$it/")) }
        // A name that points at the phone's network is refused once looked up.
        assertEquals("Pages on this phone's own network are not opened.", WebGuard.refuseResolved("https://router.example/"))
        assertNull(WebGuard.refuseResolved("https://shop.example/"))
    }

    @Test fun `the agent's steps in the browser follow the tiers, never type a password, and pay or submit always asks`() {
        val playbook = Playbook.get(context)
        val tools = WebTools(context)
        fun prepare(name: String, args: String) = tools.prepare(playbook.tool(name)!!, JSONObject(args))
        assertEquals("No page is open. Use web_open first.", (prepare("web_snapshot", "{}") as Prepared.Refused).outcome.result)
        assertEquals("Pages on this phone's own network are not opened.", (prepare("web_open", """{"url":"https://router.example/","why":"Open the router"}""") as Prepared.Refused).outcome.result)
        val open = prepare("web_open", """{"url":"https://shop.example/","why":"Open the shop"}""") as Prepared.Ready
        assertEquals(Risk.ACT, open.risk); assertEquals("shop.example", open.app)
        answers["read(0)"] = """{"title":"Shop","url":"https://shop.example/","text":"Welcome to the shop","next":null,"total":19}"""
        val result = LinkedBlockingQueue<ToolOutcome>()
        // The browser works on the main thread, so the test keeps it turning while it waits.
        fun next(): ToolOutcome { waitUntil("the step") { result.isNotEmpty() }; return result.poll()!! }
        tools.run(open) { result += it }
        val opened = next()
        assertTrue(opened.result, opened.ok && opened.result.contains("Welcome to the shop"))
        idle()
        assertEquals(1, SheetBrowser.pages.size)
        assertEquals("https://shop.example/", shadowOf(SheetBrowser.active!!.web).lastLoadedUrl)
        // Reading runs on its own.
        answers["snapshot()"] = """{"title":"Shop","url":"https://shop.example/","lines":["e3 button “Köp nu”","e4 field “Lösenord” value=“••••” [secret]","e5 link “Mer om oss” → https://shop.example/om"],"more":0}"""
        val snap = prepare("web_snapshot", "{}") as Prepared.Ready
        assertEquals(Risk.READ, snap.risk)
        tools.run(snap) { result += it }
        assertTrue(next().result.contains("e5 link “Mer om oss”"))
        // Buying always asks, whatever the model says it is.
        answers["about(\"e3\")"] = """{"found":true,"role":"button","name":"Köp nu","secret":false,"id":"buy","type":"","form":true}"""
        val buy = prepare("web_click", """{"ref":"e3","why":"Look at the product"}""") as Prepared.Ready
        assertEquals(Risk.ALWAYS, buy.risk); assertNotNull(buy.reason)
        // A password field is never typed into.
        answers["about(\"e4\")"] = """{"found":true,"role":"field","name":"Lösenord","secret":true,"id":"pw","type":"password","form":true}"""
        assertEquals(WebTools.TAKE_OVER, (prepare("web_type", """{"ref":"e4","text":"hunter2","why":"Log in"}""") as Prepared.Refused).outcome.result)
        // Typing with submit always asks; an ordinary click waits for Allow, and can be allowed always on the site.
        answers["about(\"e6\")"] = """{"found":true,"role":"field","name":"Sök","secret":false,"id":"q","type":"search","form":true}"""
        assertEquals(Risk.ALWAYS, (prepare("web_type", """{"ref":"e6","text":"hundsäng","submit":true,"why":"Search for dog beds"}""") as Prepared.Ready).risk)
        answers["about(\"e5\")"] = """{"found":true,"role":"link","name":"Mer om oss","secret":false,"id":"","type":"","form":false}"""
        val more = prepare("web_click", """{"ref":"e5","why":"Read about the shop"}""") as Prepared.Ready
        assertEquals(Risk.ACT, more.risk)
        assertTrue(Approval(AgentToolCall("c", "web_click", "{}"), more).rememberable)
        tools.remember(more); assertTrue(tools.remembered(more))
        answers["mark("] = "true"; answers["click(\"e5\")"] = """{"ok":true}"""
        tools.run(more) { result += it }
        assertTrue(next().ok)
        assertTrue("the element is outlined before the click", calls.indexOfFirst { it.startsWith("mark(") } < calls.indexOfFirst { it.startsWith("click(") })
        assertEquals("There is no e9 on the page now. Take a new snapshot.", (prepare("web_click", """{"ref":"e9","why":"Click it"}""").let { answers["about(\"e9\")"] = """{"found":false}"""; prepare("web_click", """{"ref":"e9","why":"Click it"}""") } as Prepared.Refused).outcome.result)
    }

    @Test fun `the sheet shows the pages the agent opens, and the agent gets the browser's instructions`() {
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        answers["read(0)"] = """{"title":"Hundar","url":"https://dogs.example/","text":"Små hundraser","next":null,"total":13}"""
        val sent = LinkedBlockingQueue<JSONObject>()
        val responses = LinkedBlockingQueue<String>()
        ConnectedAI.httpForTest = ConnectedAI.Http { _, _, body, onCall ->
            onCall(ConnectedAI.Call {}); sent += JSONObject(body.toString(Charsets.UTF_8))
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream((responses.poll(5, TimeUnit.SECONDS) ?: "data: [DONE]\n\n").toByteArray())) {}
        }
        val call = JSONObject().put("index", 0).put("id", "c1").put("function", JSONObject().put("name", "web_open").put("arguments", """{"url":"https://dogs.example/","why":"Open a page about small dogs"}"""))
        responses += "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("delta", JSONObject().put("tool_calls", JSONArray().put(call))))) + "\n\ndata: [DONE]\n\n"
        responses += "data: {\"choices\":[{\"delta\":{\"content\":\"Sidan är öppen.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val activity = Robolectric.buildActivity(AssistantActivity::class.java, AssistantActivity.intent(context, direct = true)).setup()
        val root = activity.get().window.decorView
        assertEquals(View.GONE, views(root).first { it.tag == "assistant-browser" }.visibility)
        (views(root).first { it.tag == "assistant-input" } as TextView).text = "Öppna en sida om små hundar"
        views(root).first { it.tag == "assistant-ask" }.performClick()
        waitUntil("the Allow prompt") { PhoneAgent.current?.view()?.approval != null }
        val first = sent.poll()!!
        assertTrue(first.getJSONArray("messages").getJSONObject(0).getString("content").contains("You have a browser inside Ægentica's sheet"))
        assertTrue(first.getJSONArray("tools").toString().contains("web_snapshot"))
        views(root).first { it.tag == "assistant-allow" }.performClick()
        waitUntil("the answer") { PhoneAgent.current?.running == false }
        idle()
        assertEquals(View.VISIBLE, views(root).first { it.tag == "assistant-browser" }.visibility)
        assertTrue("the page is in the sheet", views(views(root).first { it.tag == "assistant-browser-page" }).any { it is WebView })
        assertEquals(1, views(root).count { it.tag == "assistant-browser-tab" })
        views(root).first { it.tag == "assistant-browser-close" }.performClick(); idle()
        assertTrue(SheetBrowser.pages.isEmpty())
        assertEquals(View.GONE, views(root).first { it.tag == "assistant-browser" }.visibility)
        activity.pause().stop().destroy()
    }
}
