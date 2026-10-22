package com.caceras.surfacelab

import android.app.Notification
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.URL

/**
 * One end of a LiveKit room, as the phone sees it. The Nano build implements
 * it with LiveKit's WebRTC SDK; the core build has none. Main code only sees
 * this seam, so it stays framework-only and testable.
 */
interface AgentRoom {
    fun setMicrophone(on: Boolean)
    /** Starts sharing with the MediaProjection consent [permission], or stops when it is null. */
    fun shareScreen(permission: Intent?, notification: Notification?, onStop: () -> Unit)
    fun disconnect()
}

interface AgentRoomListener {
    fun connected()
    /** The agent's `lk.agent.state`: initializing, idle, listening, thinking or speaking. */
    fun agentState(state: String)
    /** A transcript segment from `lk.transcription`; interim text is replaced when [final] arrives. */
    fun transcript(segment: String, text: String, final: Boolean, fromAgent: Boolean)
    /** An RPC from the agent; runs on a worker thread and returns at most [AgentTools.MAX_REPLY] bytes. */
    fun call(method: String, payload: String): String
    fun ended(reason: String)
}

object AgentRooms {
    @Volatile var forTest: ((Context, String, String, AgentRoomListener) -> AgentRoom)? = null
    fun available() = forTest != null || AgentRoomFactory.available
    fun connect(context: Context, url: String, token: String, listener: AgentRoomListener): AgentRoom =
        (forTest ?: AgentRoomFactory::connect)(context.applicationContext, url, token, listener)
}

/**
 * Where the owner's agent lives: a token endpoint (the small server that holds
 * the LiveKit secret) and an access code the owner chose. The code is kept
 * with Android Keystore; the LiveKit secret never reaches the phone.
 */
object AgentConfig {
    private fun prefs(context: Context) = context.getSharedPreferences("agent-voice", 0)
    @Volatile var codeForTest: String? = null
    @Volatile var httpForTest: ConnectedAI.Http? = null

    fun endpoint(context: Context) = prefs(context).getString("endpoint", "").orEmpty()
    fun configured(context: Context) = endpoint(context).isNotBlank() && (codeForTest != null || prefs(context).getString("code", "").orEmpty().isNotBlank())
    fun host(context: Context) = runCatching { URL(endpoint(context)).host }.getOrDefault("")

    fun save(context: Context, endpoint: String, code: String) {
        val url = ConnectedAI.validateEndpoint(endpoint.trim())
        val sealed = if (code.isNotBlank()) ConnectedAI.seal(code.trim()) else prefs(context).getString("code", "").orEmpty()
        require(sealed.isNotBlank()) { "Enter the access code you set on your agent server." }
        prefs(context).edit().putString("endpoint", url.toString()).putString("code", sealed).apply()
    }
    fun forget(context: Context) = prefs(context).edit().clear().apply()
    private fun code(context: Context) = codeForTest ?: ConnectedAI.unseal(prefs(context).getString("code", "").orEmpty())

    data class Ticket(val serverUrl: String, val token: String)

    /** LiveKit's standard token endpoint: POST, 201 with server_url and participant_token. Worker thread only. */
    fun ticket(context: Context): Ticket {
        require(configured(context)) { "Set up your agent first: its token address and access code." }
        val url = ConnectedAI.validateEndpoint(endpoint(context)).toString()
        val body = JSONObject().put("participant_name", "Phone").toString().toByteArray()
        val response = (httpForTest ?: TokenHttp).post(url, mapOf("Content-Type" to "application/json", "Authorization" to "Bearer ${code(context)}"), body) {}
        try {
            val text = response.body.readBounded(100_000).toString(Charsets.UTF_8)
            when (response.code) {
                200, 201 -> {}
                401, 403 -> error("The agent server refused the access code. Check it in Agent voice settings.")
                429 -> error("The agent server is limiting requests. Wait a minute and try again.")
                else -> error("The agent server answered HTTP ${response.code}. Check that it is running.")
            }
            val json = JSONObject(text)
            val server = json.optString("server_url"); val token = json.optString("participant_token")
            val parsed = runCatching { URL(server.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://")) }.getOrNull()
            require(parsed != null && (server.startsWith("wss://") || (server.startsWith("ws://") && ConnectedAI.tailnet(parsed.host))) && token.isNotBlank()) { "The agent server sent an unusable answer." }
            return Ticket(server, token)
        } finally { response.close() }
    }

    private object TokenHttp : ConnectedAI.Http {
        override fun post(url: String, headers: Map<String, String>, body: ByteArray, onCall: (ConnectedAI.Call) -> Unit): ConnectedAI.Response {
            val conn = URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false; conn.requestMethod = "POST"; conn.connectTimeout = 15000; conn.readTimeout = 20000; conn.doOutput = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = (if (code in 200..299) conn.inputStream else conn.errorStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
            return ConnectedAI.Response(code, conn.contentType.orEmpty(), stream) { runCatching { stream.close() }; conn.disconnect() }
        }
    }
}

/** Something the agent suggests doing on the phone. Nothing happens until the owner taps it. */
data class AgentProposal(val label: String, val intent: Intent? = null, val note: Record? = null)

/**
 * The phone-side tools the agent may call over RPC. Reading notes reuses the
 * read-only Gemini Live tools. Actions are only ever proposals with strict
 * validation; the agent cannot run anything on the phone by itself.
 */
object AgentTools {
    /** LiveKit RPC payloads are limited to 15 KiB. */
    const val MAX_REPLY = 14_000

    fun reply(json: JSONObject): String {
        val text = json.toString()
        if (text.toByteArray().size <= MAX_REPLY) return text
        return JSONObject().put("error", "The result was too large; ask for something narrower.").toString()
    }

    fun proposal(args: JSONObject): AgentProposal? = runCatching {
        fun text(key: String, limit: Int) = args.optString(key).trim().take(limit)
        when (args.optString("kind")) {
            "timer" -> args.optInt("minutes", -1).let { m -> AgentProposal("Set a $m-minute timer", NativeActions.timer(m)) }
            "alarm" -> { val h = args.optInt("hour", -1); val m = args.optInt("minute", 0); AgentProposal("Set an alarm for %02d:%02d".format(h, m), NativeActions.alarm(h, m)) }
            "calendar" -> text("title", 200).takeIf { it.isNotEmpty() }?.let { AgentProposal("Add “$it” to the calendar", NativeActions.calendar(it)) }
            "maps" -> text("query", 300).takeIf { it.isNotEmpty() }?.let { AgentProposal("Find “$it” in Maps", NativeActions.maps(it)) }
            "dial" -> text("number", 40).let { AgentProposal("Call $it", NativeActions.dial(it)) }
            "web" -> text("query", 300).takeIf { it.isNotEmpty() }?.let { AgentProposal("Search the web for “$it”", Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, it)) }
            "note" -> text("text", 20_000).takeIf { it.isNotEmpty() }?.let { body ->
                val title = text("title", 120).ifBlank { body.lineSequence().first().take(60) }
                AgentProposal("Save a note: $title", note = Record(title = title, body = body, source = "LiveKit agent · proposed, saved by you"))
            }
            else -> null
        }
    }.getOrNull()

    /** Runs one tool call. [propose] receives validated proposals on the calling thread. */
    fun handle(context: Context, method: String, payload: String, consulted: MutableSet<String>, propose: (AgentProposal) -> Unit): String {
        val args = runCatching { JSONObject(payload.ifBlank { "{}" }) }.getOrElse { return reply(JSONObject().put("error", "Arguments must be a JSON object.")) }
        return when (method) {
            "search_notes", "read_note", "list_tasks" -> reply(runCatching { LiveNotes.run(context, method, args, consulted) }.getOrElse { JSONObject().put("error", "The notes could not be read.") })
            "propose_action" -> {
                val p = proposal(args) ?: return reply(JSONObject().put("error", "Unsupported or invalid action. Supported kinds: timer, alarm, calendar, maps, dial, web, note."))
                propose(p)
                reply(JSONObject().put("status", "shown").put("note", "The owner sees \"${p.label}\" on the phone and decides; it has not happened."))
            }
            else -> reply(JSONObject().put("error", "Unknown tool $method."))
        }
    }
}

/**
 * One conversation with the owner's LiveKit agent. It owns the room, keeps
 * the transcript in order (interim segments are replaced by final ones),
 * answers tool calls and saves the final words verbatim when it ends.
 */
class AgentConversation(private val context: Context, private val listener: Listener) {
    enum class State { CONNECTING, LISTENING, THINKING, SPEAKING, ENDED }
    interface Listener {
        fun changed(conversation: AgentConversation)
    }
    private class Segment(val fromAgent: Boolean, var text: String, var final: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val segments = LinkedHashMap<String, Segment>()
    private var room: AgentRoom? = null
    private var generation = 0
    var state = State.CONNECTING; private set
    var message = ""; private set
    var sharing = false; private set
    var ended = false; private set
    var saved: Record? = null; private set
    val consulted = linkedSetOf<String>()
    val proposals = mutableListOf<AgentProposal>()
    val lines: List<LiveLine> get() = segments.values.filter { it.text.isNotBlank() }.map { LiveLine(!it.fromAgent, it.text.trim()) }

    private fun post(token: Int, block: () -> Unit) { main.post { if (token == generation && !ended) { block(); listener.changed(this) } } }

    fun start() {
        val token = ++generation
        state = State.CONNECTING; message = "Connecting to your agent…"; listener.changed(this)
        Thread {
            val ticket = runCatching { AgentConfig.ticket(context) }
            main.post {
                if (token != generation || ended) return@post
                ticket.onFailure { end(it.message ?: "Could not reach your agent.") }.onSuccess { t ->
                    room = runCatching { AgentRooms.connect(context, t.serverUrl, t.token, roomListener(token)) }.getOrElse { end("Could not join the room: ${it.message}"); return@post }
                }
            }
        }.start()
    }

    private fun roomListener(token: Int) = object : AgentRoomListener {
        override fun connected() = post(token) { state = State.LISTENING; message = "" }
        override fun agentState(state: String) = post(token) {
            this@AgentConversation.state = when (state) { "speaking" -> State.SPEAKING; "thinking" -> State.THINKING; "listening", "idle" -> State.LISTENING; else -> this@AgentConversation.state }
        }
        override fun transcript(segment: String, text: String, final: Boolean, fromAgent: Boolean) = post(token) {
            val s = segments.getOrPut(segment) { Segment(fromAgent, "", false) }
            if (!s.final) { s.text = text; s.final = final }
        }
        override fun call(method: String, payload: String): String =
            AgentTools.handle(context, method, payload, consulted) { p -> post(token) { proposals += p } }
        override fun ended(reason: String) = post(token) { end(reason.ifBlank { "The agent left." }) }
    }

    fun shareScreen(permission: Intent?, notification: Notification?) {
        val r = room ?: return
        sharing = permission != null
        r.shareScreen(permission, notification) { main.post { sharing = false; listener.changed(this) } }
        listener.changed(this)
    }

    /** Runs a proposal the owner tapped: an intent for the activity to start, or a note saved here. */
    fun accept(p: AgentProposal): Intent? {
        proposals.remove(p)
        p.note?.let { n -> WorkspaceStore(context).use { it.save(n) } }
        listener.changed(this)
        return p.intent
    }
    fun dismiss(p: AgentProposal) { proposals.remove(p); listener.changed(this) }

    /** Leaves the room and saves the final words once. Safe to call more than once. */
    fun end(message: String = "") {
        if (ended) return
        ended = true; generation++
        runCatching { room?.disconnect() }; room = null; sharing = false
        state = State.ENDED; this.message = message
        saved = runCatching { WorkspaceStore(context).use { save(it) } }.getOrNull()
        listener.changed(this)
    }

    fun save(store: WorkspaceStore): Record? {
        val words = segments.values.filter { it.final && it.text.isNotBlank() }
        if (words.isEmpty()) return null
        val text = words.joinToString("\n") { (if (it.fromAgent) "Ægentica: " else "You: ") + it.text.trim() }.take(200000)
        val title = "Agent conversation · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date())
        return store.save(Record(title = title, body = text, original = text, source = "LiveKit agent · ${AgentConfig.host(context)}" + if (consulted.isEmpty()) "" else " · notes used: " + consulted.joinToString(", ").take(400)))
    }
}
