package com.caceras.surfacelab

import android.Manifest
import android.app.Notification
import android.content.Intent
import android.os.Looper
import android.provider.AlarmClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Real-time voice with the owner's LiveKit agent: tickets, tools, transcripts, proposals and ending. */
@RunWith(AndroidJUnit4::class)
class AgentTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun waitFor(condition: () -> Boolean) { var n = 0; while (!condition() && n < 100) { Thread.sleep(20); idle(); n++ } }

    class TokenServer(private val code: Int = 201, private val body: String = """{"server_url":"wss://lk.example.se","participant_token":"jwt"}""") : ConnectedAI.Http {
        val headers = mutableListOf<Map<String, String>>()
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, onCall: (ConnectedAI.Call) -> Unit): ConnectedAI.Response {
            this.headers += headers
            return ConnectedAI.Response(code, "application/json", ByteArrayInputStream(this.body.toByteArray())) {}
        }
    }

    class FakeRoom : AgentRoom {
        var url = ""; var token = ""; var listener: AgentRoomListener? = null
        var disconnected = 0; var shared: Intent? = null; var stopShare: (() -> Unit)? = null
        override fun setMicrophone(on: Boolean) {}
        override fun shareScreen(permission: Intent?, notification: Notification?, onStop: () -> Unit) { shared = permission; stopShare = onStop }
        override fun disconnect() { disconnected++ }
    }

    private val rooms = mutableListOf<FakeRoom>()

    @Before fun setUp() {
        context.deleteDatabase("aegentica.db")
        context.getSharedPreferences("agent-voice", 0).edit().clear().putString("endpoint", "https://agent.example.se/token").commit()
        context.getSharedPreferences("surfacelab", 0).edit().clear().putBoolean("agent_consent", true).commit()
        AgentConfig.codeForTest = "hemlig"
        AgentConfig.httpForTest = TokenServer()
        AgentRooms.forTest = { _, url, token, listener -> FakeRoom().also { it.url = url; it.token = token; it.listener = listener; rooms += it } }
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun tearDown() {
        AgentService.conversation?.end(); AgentService.clear(); idle()
        AgentConfig.codeForTest = null; AgentConfig.httpForTest = null; AgentRooms.forTest = null
        context.deleteDatabase("aegentica.db")
    }

    @Test fun `the phone asks its own server for a short-lived ticket and never holds the LiveKit secret`() {
        val server = TokenServer(); AgentConfig.httpForTest = server
        val ticket = AgentConfig.ticket(context)
        assertEquals("wss://lk.example.se", ticket.serverUrl); assertEquals("jwt", ticket.token)
        assertEquals("Bearer hemlig", server.headers.single()["Authorization"])
        AgentConfig.httpForTest = TokenServer(401, "{}")
        assertTrue(assertThrows(IllegalStateException::class.java) { AgentConfig.ticket(context) }.message!!.contains("refused the access code"))
        AgentConfig.httpForTest = TokenServer(201, """{"server_url":"ws://evil.example.com","participant_token":"jwt"}""")
        assertThrows(IllegalArgumentException::class.java) { AgentConfig.ticket(context) }
        AgentConfig.httpForTest = TokenServer(201, """{"server_url":"ws://lk.tail.ts.net:7880","participant_token":"jwt"}""")
        assertEquals("ws://lk.tail.ts.net:7880", AgentConfig.ticket(context).serverUrl)
    }

    @Test fun `tools read notes and only ever propose strictly validated actions`() {
        WorkspaceStore(context).use { Transcripts.append(it, null, 1_790_000_000_000, 1, 2_000, "vi bestämde att lansera onboarding på fredag", "sv-SE") }
        val consulted = mutableSetOf<String>(); val proposed = mutableListOf<AgentProposal>()
        val found = JSONObject(AgentTools.handle(context, "search_notes", """{"query":"onboarding"}""", consulted) { proposed += it })
        assertTrue(found.getJSONArray("matches").getJSONObject(0).getString("excerpt").contains("onboarding")); assertEquals(1, consulted.size)
        val shown = JSONObject(AgentTools.handle(context, "propose_action", """{"kind":"timer","minutes":10}""", consulted) { proposed += it })
        assertEquals("shown", shown.getString("status")); assertEquals("Set a 10-minute timer", proposed.single().label)
        assertEquals(AlarmClock.ACTION_SET_TIMER, proposed.single().intent!!.action)
        for (bad in listOf("""{"kind":"timer","minutes":5000}""", """{"kind":"dial","number":"rm -rf"}""", """{"kind":"install_app","value":"x"}""", """not json"""))
            assertTrue(bad, JSONObject(AgentTools.handle(context, "propose_action", bad, consulted) { proposed += it }).has("error"))
        assertEquals(1, proposed.size)
        assertTrue(JSONObject(AgentTools.handle(context, "delete_everything", "{}", consulted) {}).has("error"))
        val note = AgentTools.proposal(JSONObject().put("kind", "note").put("text", "Ring Lina om utkastet"))!!
        assertEquals("Ring Lina om utkastet", note.note!!.body); assertNull(note.intent)
        assertTrue(JSONObject(AgentTools.reply(JSONObject().put("text", "x".repeat(20_000)))).has("error"))
    }

    @Test fun `a conversation keeps transcripts in order, replaces interim words and saves only final words`() {
        var changes = 0
        val conversation = AgentConversation(context, object : AgentConversation.Listener { override fun changed(conversation: AgentConversation) { changes++ } })
        conversation.start()
        waitFor { rooms.isNotEmpty() }
        val room = rooms.single(); val listener = room.listener!!
        assertEquals("wss://lk.example.se", room.url); assertEquals("jwt", room.token)
        listener.connected(); idle()
        assertEquals(AgentConversation.State.LISTENING, conversation.state)
        listener.transcript("u1", "Vad bestämde", false, fromAgent = false); idle()
        listener.transcript("u1", "Vad bestämde vi om onboarding?", true, fromAgent = false); idle()
        listener.transcript("u1", "late interim must not win", false, fromAgent = false); idle()
        listener.agentState("speaking"); idle()
        assertEquals(AgentConversation.State.SPEAKING, conversation.state)
        listener.transcript("a1", "Ni bestämde att lansera på fredag.", true, fromAgent = true); idle()
        listener.transcript("a2", "Och sen", false, fromAgent = true); idle()
        assertEquals(listOf(LiveLine(true, "Vad bestämde vi om onboarding?"), LiveLine(false, "Ni bestämde att lansera på fredag."), LiveLine(false, "Och sen")), conversation.lines)
        val reply = JSONObject(listener.call("propose_action", """{"kind":"alarm","hour":7,"minute":30}""")); idle()
        assertEquals("shown", reply.getString("status")); assertEquals("Set an alarm for 07:30", conversation.proposals.single().label)
        assertEquals(AlarmClock.ACTION_SET_ALARM, conversation.accept(conversation.proposals.single())!!.action)
        assertTrue(conversation.proposals.isEmpty())
        conversation.end("Ended."); conversation.end("twice")
        assertEquals(1, room.disconnected)
        val saved = WorkspaceStore(context).use { it.get(conversation.saved!!.id)!! }
        assertEquals("You: Vad bestämde vi om onboarding?\nÆgentica: Ni bestämde att lansera på fredag.", saved.original)
        assertEquals(saved.body, saved.original); assertTrue(saved.source.startsWith("LiveKit agent · agent.example.se"))
        listener.transcript("a3", "after the end", true, fromAgent = true); idle()
        assertEquals(3, conversation.lines.size)
        assertTrue(changes > 0)
    }

    @Test fun `the service shows a stop notification and its stop action ends and saves the conversation`() {
        val service = Robolectric.buildService(AgentService::class.java).create()
        service.get().onStartCommand(Intent(context, AgentService::class.java), 0, 1)
        waitFor { rooms.isNotEmpty() }
        assertNotNull(shadowOf(service.get()).lastForegroundNotification)
        rooms.single().listener!!.connected(); rooms.single().listener!!.transcript("u1", "Hej", true, false); idle()
        service.get().onStartCommand(Intent(context, AgentService::class.java).setAction(AgentService.STOP), 0, 2); idle()
        val ended = AgentService.conversation!!
        assertTrue(ended.ended); assertNotNull(ended.saved); assertEquals(1, rooms.single().disconnected)
        assertTrue(shadowOf(service.get()).isStoppedBySelf)
        service.destroy()
    }

    @Test fun `the screen shows the transcript and a proposal runs only when tapped`() {
        val activity = Robolectric.buildActivity(AgentActivity::class.java, AgentActivity.intent(context, start = true)).setup()
        val started = shadowOf(context).nextStartedService
        assertEquals(AgentService::class.java.name, started.component!!.className)
        val service = Robolectric.buildService(AgentService::class.java, started).create().startCommand(0, 1)
        waitFor { rooms.isNotEmpty() }
        val listener = rooms.single().listener!!
        listener.connected(); listener.transcript("u1", "Sätt en timer på tio minuter", true, false); idle()
        listener.call("propose_action", """{"kind":"timer","minutes":10}"""); idle()
        val root = activity.get().window.decorView
        assertTrue((root.findViewWithTag<TextView>("agent-text")).text.contains("Sätt en timer"))
        assertEquals(View.VISIBLE, root.findViewWithTag<View>("agent-share").visibility)
        assertNull("nothing runs by itself", shadowOf(activity.get()).nextStartedActivity)
        root.findViewWithTag<View>("agent-accept").performClick(); idle()
        assertEquals(AlarmClock.ACTION_SET_TIMER, shadowOf(activity.get()).nextStartedActivity.action)
        assertNull(root.findViewWithTag<View>("agent-proposal"))
        activity.pause().stop().destroy(); service.destroy()
    }
}
