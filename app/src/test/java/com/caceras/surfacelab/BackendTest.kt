package com.caceras.surfacelab

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.ImageView
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
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Ægentica's back office from the phone: pairing over the app's private channel, the MCP client,
 * the agent's back-office tools, jobs on aegentica.se with their live events, priced approvals by
 * tap and by voice, the live notification, the live browser, and saving chats to history.
 */
@RunWith(AndroidJUnit4::class)
class BackendTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun all(root: View, tag: String) = views(root).filter { it.tag == tag }
    private fun find(root: View, tag: String) = all(root, tag).firstOrNull()
    private fun text(root: View, tag: String) = (find(root, tag) as TextView).text.toString()
    private fun waitUntil(what: String, timeoutMs: Long = 5000, done: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMs
        while (!done()) { idle(); if (System.currentTimeMillis() > until) fail("Timed out waiting for $what"); Thread.sleep(5) }
        idle()
    }
    private val notifications get() = shadowOf(context.getSystemService(NotificationManager::class.java))

    /** The back office, scripted per tool; every call is recorded. */
    private class FakeApi : Backend.Api {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val answers = HashMap<String, (JSONObject) -> JSONObject>()
        override fun call(tool: String, args: JSONObject): JSONObject {
            calls += tool to args
            return answers[tool]?.invoke(args) ?: JSONObject().put("ok", true)
        }
        fun of(tool: String) = calls.filter { it.first == tool }.map { it.second }
    }

    /** Reversible, but never the token itself: proves only a sealed copy is stored. */
    private object Reverse : Backend.Vault {
        override fun seal(text: String) = "sealed:" + text.reversed()
        override fun unseal(raw: String) = raw.removePrefix("sealed:").reversed()
    }

    private lateinit var api: FakeApi
    private val token = "aeg_pat_" + "a1B2c3D4e5F6g7H8i9J0"

    @Before fun setUp() {
        Backend.resetForTest(); ServerJobs.resetForTest(); Hands.resetForTest()
        context.getSharedPreferences("backend", 0).edit().clear().commit()
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().clear().commit()
        context.getSharedPreferences("surfacelab", 0).edit().putBoolean("assistant_listen", false).commit()
        Backend.vaultForTest = Reverse
        api = FakeApi()
        ServerJobs.pollMs = 20
    }

    @After fun tearDown() {
        Backend.resetForTest(); ServerJobs.resetForTest(); Hands.resetForTest()
        LiveBrowser.streamForTest = null; JobCard.imagesForTest = null
        ConnectedAI.httpForTest = null; PhoneAgent.keyForTest = null; PhoneAgent.onlineForTest = null; PhoneAgent.current = null
        idle()
    }

    private fun sse(vararg events: Pair<String, JSONObject>, ids: Boolean = false) =
        events.mapIndexed { i, (name, data) -> (if (ids) "id: ${i + 1}\n" else "") + "event: $name\ndata: $data\n\n" }.joinToString("")

    // ---------------------------------------------------------------- pairing

    @Test fun `aegentica-se hands over an access token on the private channel, and only a real token from aegentica-se itself is kept, sealed`() {
        val got = mutableListOf<String>()
        val phone = object : ShellBridge.Phone {
            override fun speak(text: String, language: String, done: (String?) -> Unit) {}
            override fun pauseSpeaking() {}
            override fun resumeSpeaking() {}
            override fun stopSpeaking() {}
            override fun listen(words: (String, Boolean) -> Unit, done: (String?) -> Unit) {}
            override fun stopListening() {}
            override fun signIn(challenge: String, done: (String?, String?) -> Unit) {}
            override fun openOutside(url: String) {}
            override fun chooseVoice() {}
            override fun menu() {}
            override fun accessToken(token: String, name: String) { got += "$name:$token" }
        }
        val bridge = ShellBridge({}, phone)
        bridge.receive("""{"t":"access-token","token":"$token","name":"Pixel\nPhone"}""")
        bridge.receive("""{"t":"access-token","token":"sk-or-not-ours","name":"Pixel"}""")
        bridge.receive("""{"t":"access-token","token":"aeg_pat_short","name":"Pixel"}""")
        assertEquals(listOf("Pixel Phone:$token"), got)

        // The site's channel reaches the app's shell; it keeps the token for aegentica.se only.
        val open = Robolectric.buildActivity(AegenticaActivity::class.java, AegenticaActivity.connect(context)).setup()
        val web = open.get().window.decorView.findViewWithTag<WebView>("aegentica")
        assertEquals("Connect opens Settings → Security with this phone's name", Backend.CONNECT, shadowOf(web).lastLoadedUrl)
        open.get().bridge.receive("""{"t":"access-token","token":"$token","name":"Pixel"}""")
        assertEquals(token, Backend.token(context))
        assertEquals("Pixel", Backend.name(context))
        val stored = context.getSharedPreferences("backend", 0).getString("token", null)!!
        assertFalse("never stored in the clear", stored.contains(token))
        assertTrue(Backend.connected(context))
        Backend.disconnect(context)
        assertNull(Backend.token(context))
        // Glimt never keeps one, and a page outside aegentica.se does not open in the shell.
        val glimt = Robolectric.buildActivity(GlimtActivity::class.java).setup()
        glimt.get().bridge.receive("""{"t":"access-token","token":"$token","name":"Pixel"}""")
        assertNull(Backend.token(context))
        val elsewhere = Robolectric.buildActivity(AegenticaActivity::class.java, AegenticaActivity.open(context, "https://evil.example/settings")).setup()
        assertEquals(AegenticaActivity.URL, shadowOf(elsewhere.get().window.decorView.findViewWithTag<WebView>("aegentica")).lastLoadedUrl)
    }

    // ---------------------------------------------------------------- the MCP client

    @Test fun `the MCP client sends one JSON-RPC tool call with the bearer token and reads structured results and refusals`() {
        val requests = LinkedBlockingQueue<Triple<String, Map<String, String>, JSONObject>>()
        val replies = LinkedBlockingQueue<ConnectedAI.Response>()
        ConnectedAI.httpForTest = ConnectedAI.Http { url, headers, body, onCall ->
            onCall(ConnectedAI.Call {}); requests += Triple(url, headers, JSONObject(body.toString(Charsets.UTF_8)))
            replies.poll(5, TimeUnit.SECONDS) ?: error("no reply")
        }
        fun reply(code: Int, type: String, text: String) { replies += ConnectedAI.Response(code, type, ByteArrayInputStream(text.toByteArray())) {} }
        val mcp = Backend.Mcp(token)
        reply(200, "application/json", """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{}"}],"structuredContent":{"tasks":[{"title":"Morning brief"}]}}}""")
        assertEquals("Morning brief", mcp.call("tasks_list", JSONObject()).getJSONArray("tasks").getJSONObject(0).getString("title"))
        val (url, headers, sent) = requests.poll()!!
        assertEquals(Backend.ENDPOINT, url)
        assertEquals("Bearer $token", headers["Authorization"])
        assertEquals("tools/call", sent.getString("method")); assertEquals("tasks_list", sent.getJSONObject("params").getString("name"))
        // One SSE message when the server streams; a refusal is its plain sentence.
        reply(200, "text/event-stream", "event: message\ndata: {\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"open\\\":\\\"https://aegentica.se/c/1\\\"}\"}]}}\n\n")
        assertEquals("https://aegentica.se/c/1", mcp.call("conversation_save", JSONObject()).getString("open"))
        reply(200, "application/json", """{"result":{"isError":true,"content":[{"type":"text","text":"Send Ægentica one message first."}]}}""")
        assertEquals("Send Ægentica one message first.", runCatching { mcp.call("memory_save", JSONObject()) }.exceptionOrNull()?.message)
        reply(401, "application/json", "{}")
        assertTrue(runCatching { mcp.call("tasks_list", JSONObject()) }.exceptionOrNull()!!.message!!.contains("Connect again"))
    }

    // ---------------------------------------------------------------- the agent's tools

    @Test fun `the agent gets the back office only when connected, acting tools ask even hands-free, and results come fenced as data`() {
        val tools = BackendTools(context)
        assertTrue(tools.offered().isEmpty())
        assertSame(NoTools, PhoneTools.forAgent(context))
        Backend.apiForTest = api
        assertEquals(Playbook.get(context).tools.filter { it.target == "backend" }.map { it.name }, tools.offered().map { it.name })
        val playbook = Playbook.get(context)
        fun prepare(name: String, args: String) = tools.prepare(playbook.tool(name)!!, JSONObject(args))
        assertTrue((prepare("aegentica_memory_save", """{"text":"I like oat milk"}""") as Prepared.Refused).outcome.result.startsWith("Give `why`"))
        assertTrue((prepare("aegentica_memory_save", """{"text":"${"x".repeat(501)}","why":"Remember the milk"}""") as Prepared.Refused).outcome.result.contains("at most 500"))
        val save = prepare("aegentica_memory_save", """{"text":"I like oat milk","why":"Remember your milk"}""") as Prepared.Ready
        assertEquals(Risk.ACT, save.risk); assertEquals("Remember your milk", save.label)
        // Hands-free belongs to the hands: with the hands on and hands-free, the back office still asks.
        Hands.attach(HandsTest.FakeScreen(null), HandsTest.FakeOverlay())
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().putBoolean("enabled", true).putBoolean("hands_free", true).commit()
        val both = PhoneTools.forAgent(context) as Toolboxes
        assertFalse(both.handsFree(save))
        assertTrue(both.offered().any { it.name == "phone_tap" } && both.offered().any { it.name == "aegentica_handoff" })
        val result = LinkedBlockingQueue<ToolOutcome>()
        both.run(save) { result += it }
        val outcome = result.poll(5, TimeUnit.SECONDS)!!
        assertTrue(outcome.ok)
        assertTrue(outcome.result.contains("not instructions"))
        val sent = api.of("memory_save").single()
        assertEquals("I like oat milk", sent.getString("text")); assertTrue(sent.getBoolean("confirmed")); assertEquals("Remember your milk", sent.getString("confirmation_summary"))
        val draft = prepare("aegentica_inbox_draft_reply", """{"conversationId":"c1","text":"Ja, gärna!","why":"Draft your yes"}""") as Prepared.Ready
        both.run(draft) { result += it }
        assertTrue(result.poll(5, TimeUnit.SECONDS)!!.result.startsWith("Drafted, not sent"))
    }

    @Test fun `asking on the phone, the agent hands a job over after Allow and the system prompt says how the back office works`() {
        Backend.apiForTest = api
        api.answers["job_start"] = { JSONObject().put("jobId", "job-1").put("open", "https://aegentica.se/chat/job-1").put("status", "working") }
        api.answers["job_get"] = { JSONObject().put("jobId", "job-1").put("state", "completed").put("result", "Klart: 12 raser.").put("links", JSONObject().put("chat", "https://aegentica.se/chat/job-1")) }
        PhoneAgent.keyForTest = "sk-or-test"; PhoneAgent.onlineForTest = true
        val sent = LinkedBlockingQueue<JSONObject>()
        val responses = LinkedBlockingQueue<String>()
        ConnectedAI.httpForTest = ConnectedAI.Http { _, _, body, onCall ->
            onCall(ConnectedAI.Call {}); sent += JSONObject(body.toString(Charsets.UTF_8))
            ConnectedAI.Response(200, "text/event-stream", ByteArrayInputStream((responses.poll(5, TimeUnit.SECONDS) ?: "data: [DONE]\n\n").toByteArray())) {}
        }
        val call = JSONObject().put("index", 0).put("id", "c1").put("function", JSONObject().put("name", "aegentica_handoff")
            .put("arguments", """{"message":"Find twelve dog breeds for small flats, with pictures, as a table.","why":"Let Ægentica research this"}"""))
        responses += "data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("delta", JSONObject().put("tool_calls", JSONArray().put(call))))) + "\n\ndata: [DONE]\n\n"
        responses += "data: {\"choices\":[{\"delta\":{\"content\":\"Ægentica tar det på servern.\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        val session = PhoneAgent.session(context, fresh = true)
        PhoneAgent.ask(context, session, "Hitta hundraser för små lägenheter")
        waitUntil("the Allow prompt") { session.view().approval != null }
        val first = sent.poll()!!
        assertTrue(first.getJSONArray("tools").toString().contains("aegentica_handoff"))
        assertTrue(first.getJSONArray("messages").getJSONObject(0).getString("content").contains("Never answer a job's approvals or questions for the person"))
        assertEquals("Let Ægentica research this", session.view().approval!!.label)
        assertFalse("the back office is not an app to allow always", session.view().approval!!.rememberable)
        session.decide("c1", Decision(true))
        waitUntil("the answer") { !session.running }
        assertEquals("Ægentica tar det på servern.", session.view().answer)
        assertTrue(api.of("job_start").single().getString("message").startsWith("Find twelve dog breeds"))
        waitUntil("the job to end") { ServerJobs.jobs["job-1"]?.finished == true }
        val job = ServerJobs.jobs.getValue("job-1")
        assertEquals("Klart: 12 raser.", job.result)
        waitUntil("the done notification") { notifications.getNotification(AgentNotifications.jobId(job))?.extras?.getString(android.app.Notification.EXTRA_TITLE) == "Ægentica is done" }
        val done = notifications.getNotification(AgentNotifications.jobId(job))
        assertEquals(listOf("Open chat"), done.actions.map { it.title.toString() })
    }

    // ---------------------------------------------------------------- jobs

    @Test fun `job events build the job field by field and ignore what is malformed or not https`() {
        val job = ServerJobs.Job("j", "https://aegentica.se/chat/j", null)
        fun apply(event: String, json: String) = ServerJobs.apply(job, event, JSONObject(json))
        apply("status", """{"state":"working","title":"Dog breeds\nfor flats"}""")
        apply("step", """{"id":"s1","label":"Searching the web","status":"running"}""")
        apply("step", """{"id":"s1","label":"Searching the web","why":"Find breeds that suit flats","status":"done"}""")
        apply("step", """{"label":"no id"}""")
        apply("text", """{"text":"Found eight so far"}""")
        apply("artifact", """{"kind":"table","id":"t1","title":"Breeds","columns":["Breed","Size","Photo"],"rows":[["Pug","Small",{"image":"https://aegentica.se/api/media/p1.webp","alt":"A pug"}]],"append":false}""")
        apply("artifact", """{"kind":"table","id":"t1","rows":[["Havanese","Small","—"],["Bichon","Small","—"]],"append":true,"start":1}""")
        apply("artifact", """{"kind":"table","id":"t1","rows":[["Bichon frisé","Small","—"]],"append":true,"start":2}""")
        apply("artifact", """{"kind":"image","id":"i1","url":"http://insecure.example/x.jpg"}""")
        apply("artifact", """{"kind":"file","id":"f1","name":"breeds.xlsx","url":"https://aegentica.se/api/files/breeds.xlsx","mediaType":"application/vnd.ms-excel"}""")
        apply("cost", """{"usd":0.42}"""); apply("cost", """{"usd":-1}""")
        apply("browser", """{"active":true,"tabId":"t-1","url":"https://www.akc.org/","title":"AKC"}""")
        apply("input", """{"requestId":"r1","kind":"tool-approval","prompt":"Generate 40 pictures","options":[{"id":"approve","label":"Approve"},{"id":"cancel","label":"Cancel"}],"cost":{"usd":1.2,"count":40,"unit":"image"}}""")
        apply("input", """{"requestId":"r2","kind":"weird"}""")
        assertEquals("Dog breeds for flats", job.title)
        assertEquals(listOf(ServerJobs.Step("s1", "Find breeds that suit flats", "done")), job.steps.toList())
        assertEquals("Found eight so far", job.text)
        val table = job.tablesNow().single()
        assertEquals(listOf("Breed", "Size", "Photo"), table.columns)
        assertEquals(listOf("Pug", "Havanese", "Bichon frisé"), table.rows.map { it[0] })
        assertEquals("A pug", table.rows[0][2])
        assertEquals("a picture in a cell is kept as a picture of that row", listOf("https://aegentica.se/api/media/p1.webp"), job.pictures.map { it.url })
        assertEquals(0, job.pictures.single().row)
        assertEquals("breeds.xlsx", job.files.single().name)
        assertEquals(0.42, job.cost!!, 0.0001)
        assertTrue(job.browser!!.active)
        assertEquals("input_required", job.state)
        val input = job.inputs.single()
        assertEquals("About $1.20 for 40 images", ServerJobs.price(input))
        assertEquals("approve", ServerJobs.approveOption(input)!!.id); assertEquals("cancel", ServerJobs.declineOption(input)!!.id)
        assertEquals("Generate 40 pictures. That is about 1.20 dollars for 40 images. Say approve to start, or no to skip it.", ServerJobs.priceWords(input))
        // The server's own wording: the fine print in brackets is not read aloud.
        val batch = ServerJobs.Input("b", "tool-approval", "Make 24 pictures for about $0.96 (24 × $0.04, google/gemini-3.1-flash-image)?", emptyList(), false, 0.96, 24, "image")
        assertEquals("About $0.96 for 24 images", ServerJobs.price(batch))
        assertEquals("Make 24 pictures for about $0.96. That is about 0.96 dollars for 24 images. Say approve to start, or no to skip it.", ServerJobs.priceWords(batch))
        assertEquals("About $0.04 for 1 image", ServerJobs.price(ServerJobs.Input("c", "tool-approval", "One", emptyList(), false, 0.04, 1, "image")))
        apply("input_resolved", """{"requestId":"r1"}""")
        assertEquals("working", job.state)
        apply("done", """{"state":"completed","result":"Twelve breeds.","links":{"chat":"https://aegentica.se/chat/j","files":[{"name":"breeds.xlsx","url":"https://aegentica.se/api/files/breeds.xlsx"},{"name":"bad","url":"javascript:alert(1)"}]}}""")
        assertTrue(job.finished)
        assertEquals(listOf("The chat on aegentica.se", "breeds.xlsx"), job.links.map { it.first })
        assertFalse("the browser is idle once the job ends", job.browser!!.active)

        // job_get's snapshot is the same thing in one piece, with questions under either name.
        val snap = ServerJobs.Job("k", "https://aegentica.se/chat/k", null)
        ServerJobs.applySnapshot(snap, JSONObject("""{"jobId":"k","state":"input_required","steps":[{"id":"a","label":"Reading","status":"done"}],"text":"…","questions":[{"requestId":"q1","kind":"question","prompt":"Which city?","options":[],"allowFreeform":true}],"cost":{"usd":0.05},"lastEventId":"17"}"""))
        assertEquals("Which city?", snap.inputs.single().prompt); assertTrue(snap.inputs.single().freeform)
        assertEquals("17", snap.lastEventId); assertEquals(0.05, snap.cost!!, 0.0001)
    }

    @Test fun `a spoken yes approves a priced step only after its price was read aloud, and only when clear`() {
        assertFalse(ServerJobs.spokenApproval("approve", readBack = false))
        assertTrue(ServerJobs.spokenApproval("Approve.", readBack = true))
        assertTrue(ServerJobs.spokenApproval("ja, kör", readBack = true))
        assertTrue(ServerJobs.spokenApproval("Godkänn", readBack = true))
        assertFalse(ServerJobs.spokenApproval("nej", readBack = true))
        assertFalse(ServerJobs.spokenApproval("yes but not now", readBack = true))
        assertFalse(ServerJobs.spokenApproval("yes please go ahead with all of it", readBack = true))
        assertFalse(ServerJobs.spokenApproval("maybe later", readBack = true))
        assertTrue(ServerJobs.spokenDecline("Nej tack")); assertFalse(ServerJobs.spokenDecline("ja"))

        Backend.apiForTest = api
        val job = ServerJobs.Job("j", "https://aegentica.se/chat/j", null)
        ServerJobs.apply(job, "input", JSONObject("""{"requestId":"r1","kind":"tool-approval","prompt":"Generate 40 pictures","options":[{"id":"approve","label":"Approve"},{"id":"cancel","label":"Cancel"}],"cost":{"usd":1.2,"count":40,"unit":"image"}}"""))
        val lines = mutableListOf<String>()
        val input = job.inputs.single()
        PriceVoice.decide(context, job, input, "yes", lines::add)
        assertTrue("not read back yet", api.calls.isEmpty())
        job.readBack += "r1"
        PriceVoice.decide(context, job, input, "kanske", lines::add)
        assertTrue(api.calls.isEmpty())
        PriceVoice.decide(context, job, input, "ja", lines::add)
        waitUntil("the answer to reach aegentica.se") { api.of("job_respond").isNotEmpty() }
        assertEquals("approve", api.of("job_respond").single().getString("optionId"))
        assertEquals("Approved. About $1.20 for 40 images", lines.last())
    }

    @Test fun `a job is followed on its event stream, resumes with Last-Event-ID, and the live notification offers the priced approval`() {
        Backend.apiForTest = api
        api.answers["job_get"] = { JSONObject().put("jobId", "j1").put("state", "input_required").put("lastEventId", "3") }
        val opened = CopyOnWriteArrayList<String?>()
        val streams = LinkedBlockingQueue<String>()
        streams += sse(
            "status" to JSONObject().put("state", "working").put("title", "Dog breeds"),
            "step" to JSONObject().put("id", "s1").put("label", "Searching").put("status", "running"),
            "input" to JSONObject("""{"requestId":"r1","kind":"tool-approval","prompt":"Generate 40 pictures","options":[{"id":"approve","label":"Approve"},{"id":"cancel","label":"Cancel"}],"cost":{"usd":1.2,"count":40,"unit":"image"}}"""),
            ids = true)
        ServerJobs.streamForTest = ServerJobs.Stream { url, _, last ->
            assertEquals("https://aegentica.se/api/backend/jobs/j1/events", url)
            opened += last; streams.poll(3, TimeUnit.SECONDS)?.let { StringReader(it) }
        }
        val job = ServerJobs.watch(context, "j1", "https://aegentica.se/chat/j1", "https://aegentica.se/api/backend/jobs/j1/events")
        waitUntil("the approval") { job.inputs.isNotEmpty() }
        waitUntil("the notification") { notifications.getNotification(AgentNotifications.jobId(job))?.actions?.size == 3 }
        val live = notifications.getNotification(AgentNotifications.jobId(job))
        assertEquals(listOf("Stop", "Approve · $1.20 for 40 images", "Don't"), live.actions.map { it.title.toString() })
        assertTrue(live.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
        // Approving from the notification sends the button the job offered, for that request.
        val approve = shadowOf(live.actions[1].actionIntent).savedIntent
        AgentActionReceiver().onReceive(context, approve)
        waitUntil("the approval to reach aegentica.se") { api.of("job_respond").isNotEmpty() }
        assertEquals("r1", api.of("job_respond").single().getString("requestId")); assertEquals("approve", api.of("job_respond").single().getString("optionId"))
        // A forged button for an option the job never offered does nothing.
        ServerJobs.apply(job, "input", JSONObject("""{"requestId":"r2","kind":"tool-approval","prompt":"Buy","options":[{"id":"approve","label":"Approve"}]}"""))
        AgentActionReceiver().onReceive(context, Intent(context, AgentActionReceiver::class.java).setAction(AgentNotifications.JOB_APPROVE)
            .putExtra(AgentNotifications.JOB, "j1").putExtra(AgentNotifications.REQUEST, "r2").putExtra(AgentNotifications.OPTION, "always"))
        // Words while an approval waits are not sent (eve would cancel the approval); the notification says so.
        val early = Intent(context, AgentActionReceiver::class.java).setAction(AgentNotifications.JOB_REPLY).putExtra(AgentNotifications.JOB, "j1")
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AgentNotifications.TEXT).build()), early, Bundle().apply { putCharSequence(AgentNotifications.TEXT, "Hurry up") })
        AgentActionReceiver().onReceive(context, early); idle()
        assertTrue(api.of("job_message").isEmpty())
        assertEquals("Answer the approval first: Approve or Don't. Then tell the job.", notifications.getNotification(AgentNotifications.jobId(job)).extras.getString(android.app.Notification.EXTRA_SUB_TEXT))
        // The stream ended: the phone reads the job, then follows from the last event it saw.
        streams += sse("done" to JSONObject().put("state", "completed").put("result", "Klart.").put("links", JSONObject().put("chat", "https://aegentica.se/chat/j1")))
        waitUntil("the end", 6000) { job.finished }
        assertEquals(listOf(null, "3"), opened.toList())
        assertEquals(1, api.of("job_respond").size)
        // A reply from the notification steers the job.
        val reply = Intent(context, AgentActionReceiver::class.java).setAction(AgentNotifications.JOB_REPLY).putExtra(AgentNotifications.JOB, "j1")
        RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder(AgentNotifications.TEXT).build()), reply, Bundle().apply { putCharSequence(AgentNotifications.TEXT, "Lägg till priser") })
        AgentActionReceiver().onReceive(context, reply)
        waitUntil("the message") { api.of("job_message").isNotEmpty() }
        assertEquals("Lägg till priser", api.of("job_message").single().getString("message"))
    }

    // ---------------------------------------------------------------- the card

    private class Host : JobCard.Host {
        val told = mutableListOf<String>()
        override fun tell(job: ServerJobs.Job, input: ServerJobs.Input?) { told += job.id + (input?.let { ":" + it.requestId } ?: "") }
    }

    private fun jpeg(): String {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(16, 10, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    @Test fun `the job card shows steps, the price on the approve button, tables, pictures, files and the live browser`() {
        Backend.apiForTest = api
        JobCard.imagesForTest = { _, _ -> Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        LiveBrowser.streamForTest = ServerJobs.Stream { _, _, _ ->
            StringReader(sse(
                "status" to JSONObject().put("mode", "agent").put("url", "https://www.akc.org/").put("title", "AKC").put("activeTabId", "t-1").put("viewport", JSONObject().put("width", 1280).put("height", 800)),
                "frame" to JSONObject().put("tabId", "t-1").put("data", jpeg()),
                "action" to JSONObject().put("phase", "point").put("command", "click").put("x", 0.5).put("y", 0.25)))
        }
        val job = ServerJobs.Job("j", "https://aegentica.se/chat/j", null)
        listOf(
            "status" to """{"state":"working","title":"Dog breeds"}""",
            "step" to """{"id":"s1","label":"Searching","status":"done"}""",
            "artifact" to """{"kind":"table","id":"t1","title":"Breeds","columns":["Breed","Size"],"rows":[["Pug","Small"],["Havanese","Small"]]}""",
            "artifact" to """{"kind":"image","id":"i1","url":"https://aegentica.se/api/media/p1.webp","alt":"A pug"}""",
            "artifact" to """{"kind":"file","id":"f1","name":"breeds.xlsx","url":"https://aegentica.se/api/files/breeds.xlsx"}""",
            "cost" to """{"usd":0.42}""",
            "browser" to """{"active":true,"tabId":"t-1"}""",
            "input" to """{"requestId":"r1","kind":"tool-approval","prompt":"Generate 40 pictures","options":[{"id":"approve","label":"Approve"},{"id":"cancel","label":"Cancel"}],"cost":{"usd":1.2,"count":40,"unit":"image"}}""",
        ).forEach { (e, d) -> ServerJobs.apply(job, e, JSONObject(d)) }
        val host = Host()
        val card = JobCard(context, host)
        card.show(job); idle()
        val root = card.view
        assertEquals(View.VISIBLE, root.visibility)
        assertEquals("On aegentica.se · Waiting for you · $0.42", text(root, "job-state"))
        assertEquals("Dog breeds", text(root, "job-title"))
        assertEquals("About $1.20 for 40 images", text(root, "job-price"))
        assertEquals("Approve · $1.20 for 40 images", text(root, "job-approve"))
        assertEquals(listOf("Pug  ·  Small", "Havanese  ·  Small"), all(root, "job-row").map { (it as TextView).text.toString() })
        waitUntil("the picture") { (find(card.view, "job-picture") as ImageView?)?.drawable != null }
        assertEquals("breeds.xlsx", text(card.view, "job-file"))
        waitUntil("the live picture") { (find(card.view, "job-live") as ImageView?)?.drawable != null }
        waitUntil("what the agent does") { text(card.view, "job-live-status").startsWith("Clicking") }
        assertNull("while an approval waits it is answered first", find(card.view, "job-tell"))
        find(card.view, "job-approve")!!.performClick(); idle()
        waitUntil("the approval") { api.of("job_respond").isNotEmpty() }
        assertEquals("approve", api.of("job_respond").single().getString("optionId"))
        waitUntil("the card to drop the answered approval") { find(card.view, "job-approve") == null && find(card.view, "job-tell") != null }
        find(card.view, "job-tell")!!.performClick()
        assertEquals(listOf("j"), host.told)
        card.detach()
    }

    // ---------------------------------------------------------------- the live browser

    @Test fun `the live browser's events are checked, and taking over sends taps as fractions with the token`() {
        assertNull(LiveBrowser.parse("frame", JSONObject().put("data", Base64.encodeToString("not a jpeg".toByteArray(), Base64.NO_WRAP)).toString()))
        assertNull(LiveBrowser.parse("thumb", JSONObject().put("data", jpeg()).put("full", false).toString()))
        assertNotNull(LiveBrowser.parse("thumb", JSONObject().put("data", jpeg()).put("full", true).toString()))
        val action = (LiveBrowser.parse("action", """{"phase":"act","command":"fill","x":0.2,"y":1.4}""") as LiveBrowser.Update.Did).action
        assertEquals("Typing", action.label); assertEquals(0.2f, action.x!!, 0.001f); assertNull("outside the page", action.y)
        val status = (LiveBrowser.parse("status", """{"mode":"human","url":"javascript:x","title":"T","activeTabId":null,"viewport":{"width":390,"height":844}}""") as LiveBrowser.Update.Now).status
        assertEquals("human", status.mode); assertNull(status.url); assertNull(status.tabId); assertEquals(844, status.height)
        assertTrue(LiveBrowser.parse("handover", """{"reason":"secret","tabId":"t"}""") is LiveBrowser.Update.HandOver)
        assertNull(LiveBrowser.parse("other", "{}"))

        Backend.pair(context, token, "Pixel")
        val posted = LinkedBlockingQueue<Triple<String, Map<String, String>, JSONObject>>()
        ConnectedAI.httpForTest = ConnectedAI.Http { url, headers, body, onCall ->
            onCall(ConnectedAI.Call {}); posted += Triple(url, headers, JSONObject(body.toString(Charsets.UTF_8)))
            ConnectedAI.Response(200, "application/json", ByteArrayInputStream("{}".toByteArray())) {}
        }
        val browser = LiveBrowser(context)
        browser.takeOver(); browser.tap(0.5f, 1.7f)
        val (controlUrl, headers, control) = posted.poll(5, TimeUnit.SECONDS)!!
        assertEquals("https://aegentica.se/api/browser/control", controlUrl); assertEquals("Bearer $token", headers["Authorization"]); assertEquals("human", control.getString("mode"))
        val (inputUrl, _, tap) = posted.poll(5, TimeUnit.SECONDS)!!
        assertEquals("https://aegentica.se/api/browser/input", inputUrl)
        assertEquals("tap", tap.getString("type")); assertEquals(0.5, tap.getDouble("x"), 0.001); assertEquals("clamped to the page", 1.0, tap.getDouble("y"), 0.001)
    }

    // ---------------------------------------------------------------- chat history

    @Test fun `a finished phone conversation is saved to the chat history on aegentica-se once, again only when it grew, and never when turned off`() {
        Backend.apiForTest = api
        api.answers["conversation_save"] = { JSONObject().put("chatId", "c1").put("open", "https://aegentica.se/chat/c1") }
        val session = AgentSession(context)
        session.thread += AgentTurn.User("Vad betyder det här?", screen = "secret screen text")
        session.thread += AgentTurn.Assistant(AgentReply("m").apply { text.append("Det är en faktura.") })
        val opened = LinkedBlockingQueue<String>()
        Backend.saveConversation(context, session) { it?.let(opened::add) }
        assertEquals("https://aegentica.se/chat/c1", opened.poll(5, TimeUnit.SECONDS))
        val saved = api.of("conversation_save").single()
        assertEquals("Phone · Vad betyder det här?", saved.getString("title"))
        assertEquals(2, saved.getJSONArray("messages").length())
        assertFalse("screens stay on the phone", saved.toString().contains("secret screen text"))
        Backend.saveConversation(context, session); Thread.sleep(50)
        assertEquals("unchanged: not saved again", 1, api.of("conversation_save").size)
        session.thread += AgentTurn.User("Och förfallodagen?")
        session.thread += AgentTurn.Assistant(AgentReply("m").apply { text.append("Den 30 november.") })
        Backend.saveConversation(context, session)
        waitUntil("the second save") { api.of("conversation_save").size == 2 }
        assertTrue(api.of("conversation_save")[1].getString("title").endsWith("(continued)"))
        Backend.setSaving(context, false)
        session.thread += AgentTurn.User("Tack"); session.thread += AgentTurn.Assistant(AgentReply("m").apply { text.append("Varsågod.") })
        Backend.saveConversation(context, session); Thread.sleep(50)
        assertEquals(2, api.of("conversation_save").size)
    }

    // ---------------------------------------------------------------- the sheet

    @Test fun `a job's notification opens the sheet on the job, and Tell the job sends the next words to it`() {
        Backend.apiForTest = api
        val job = ServerJobs.jobs.getOrPut("j9") { ServerJobs.Job("j9", "https://aegentica.se/chat/j9", null) }
        ServerJobs.apply(job, "status", JSONObject().put("state", "working").put("title", "Weekly report"))
        val controller = Robolectric.buildActivity(AssistantActivity::class.java, AssistantActivity.showJob(context, "j9")).setup()
        val root = controller.get().window.decorView
        idle()
        assertEquals(View.VISIBLE, find(root, "job-card")!!.visibility)
        assertEquals("Weekly report", text(root, "job-title"))
        find(root, "job-tell")!!.performClick()
        assertEquals("Tell the job…", (find(root, "assistant-input") as TextView).hint.toString())
        (find(root, "assistant-input") as TextView).text = "Use last month too"
        find(root, "assistant-ask")!!.performClick()
        waitUntil("the message") { api.of("job_message").isNotEmpty() }
        assertEquals("Use last month too", api.of("job_message").single().getString("message"))
        controller.pause().stop().destroy()
    }
}
