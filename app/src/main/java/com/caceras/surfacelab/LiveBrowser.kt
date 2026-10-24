package com.caceras.surfacelab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject

/**
 * The server's real browser, live, while a job uses it (aegentica.se's Live browser,
 * aegentica-app lib/browser/routes.ts): its picture several times a second, what the agent does
 * and where, and taking over by touch. The stream and the input routes take this phone's access
 * token. Pictures are only shown; nothing in them is read or run on the phone. The stream is open
 * only while a live view is on screen.
 */
class LiveBrowser(context: Context, private val base: String = "https://aegentica.se") {
    private val context = context.applicationContext
    data class Status(val mode: String, val url: String?, val title: String?, val tabId: String?, val width: Int, val height: Int, val busy: String?)
    /** What the agent does in the browser; [x] and [y] are fractions of the picture, when known. */
    data class Action(val phase: String, val label: String, val x: Float?, val y: Float?)

    sealed class Update {
        class Frame(val tabId: String?, val jpeg: ByteArray) : Update()
        class Now(val status: Status) : Update()
        class Did(val action: Action) : Update()
        class HandOver(val tabId: String?) : Update()
    }

    @Volatile var frame: Bitmap? = null; private set
    @Volatile var status: Status? = null; private set
    @Volatile var action: Action? = null; private set
    @Volatile var connected = false; private set
    /** The agent asked the owner to take over (a password or a code). */
    @Volatile var handOver = false; private set
    @Volatile var problem: String? = null; private set
    /** Called on the main thread after each change; pictures at most every [FRAME_MS]. */
    @Volatile var listener: (() -> Unit)? = null
    @Volatile private var running = false
    @Volatile private var reader: java.io.Reader? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastPicture = 0L

    val human get() = status?.mode == "human"

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "live-browser").start()
    }

    /** Closes the stream; actions already sent (handing back, say) still go, then the sender ends. */
    fun stop() {
        running = false
        runCatching { reader?.close() }
        connected = false
        queue.shutdown()
    }

    private fun loop() {
        var failures = 0
        while (running) {
            val token = if (streamForTest != null) "test" else Backend.token(context)
            if (token == null) { problem = "Connect this phone to Ægentica to watch its browser."; publish(); break }
            val opened = runCatching { (streamForTest ?: STREAM).open("$base/api/browser/stream", token, null) }.getOrNull()
            if (opened == null) {
                failures++
                if (failures > 5) { problem = "The live browser is not available now."; publish(); break }
                sleep(1500L * failures); continue
            }
            failures = 0; reader = opened; connected = true; problem = null; publish()
            val parser = SseParser()
            val buffer = CharArray(16_384)
            runCatching {
                opened.use {
                    while (running) {
                        val n = it.read(buffer)
                        val events = if (n < 0) parser.end() else parser.push(String(buffer, 0, n))
                        events.filterIsInstance<SseParser.Event.Data>().forEach { e -> parse(e.event, e.data)?.let(::take) }
                        if (n < 0) break
                    }
                }
            }
            connected = false; publish()
            if (running) sleep(1000)
        }
        connected = false; publish()
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    internal fun take(update: Update) {
        when (update) {
            is Update.Frame -> {
                // Only the active tab's picture, decoded at most every FRAME_MS.
                if (update.tabId != null && status?.tabId != null && update.tabId != status?.tabId) return
                val now = System.nanoTime() / 1_000_000
                if (now - lastPicture < FRAME_MS) return
                lastPicture = now
                BitmapFactory.decodeByteArray(update.jpeg, 0, update.jpeg.size)?.let { frame = it; publish() }
            }
            is Update.Now -> { status = update.status; if (update.status.mode == "agent") handOver = false; publish() }
            is Update.Did -> { action = update.action; publish() }
            is Update.HandOver -> { handOver = true; action = Action("handover", "Take over to type your password, then hand it back.", null, null); publish() }
        }
    }

    private fun publish() = main.post { listener?.invoke() }

    private fun post(path: String, body: JSONObject): String? {
        val token = Backend.token(context) ?: return "Connect this phone to Ægentica first."
        return runCatching {
            val response = ConnectedAI.http.post("$base/api/browser/$path", mapOf("Content-Type" to "application/json", "Authorization" to "Bearer $token"), body.toString().toByteArray()) {}
            try {
                if (response.code in 200..299) null
                else runCatching { JSONObject(response.body.readBounded(4096).toString(Charsets.UTF_8)).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 300 }
                    ?: "The live browser couldn't do that."
            } finally { response.close() }
        }.getOrElse { "The live browser could not be reached." }
    }

    /** The owner's actions go one after another, in the order they were made: take over, then tap. */
    private val queue = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "live-browser-input").apply { isDaemon = true } }

    private fun send(path: String, body: JSONObject, done: (String?) -> Unit) {
        val queued = runCatching { queue.execute { val error = post(path, body); main.post { done(error) } } }.isSuccess
        if (!queued) main.post { done("The live view is closed.") }
    }

    /** The owner takes the browser: the agent waits until it is handed back. */
    fun takeOver(done: (String?) -> Unit = {}) = send("control", JSONObject().put("mode", "human"), done)
    fun handBack(done: (String?) -> Unit = {}) = send("control", JSONObject().put("mode", "agent"), done)
    /** A tap at a point of the picture, as fractions. */
    fun tap(x: Float, y: Float, done: (String?) -> Unit = {}) = send("input", JSONObject().put("type", "tap").put("x", x.coerceIn(0f, 1f).toDouble()).put("y", y.coerceIn(0f, 1f).toDouble()), done)
    fun scroll(x: Float, y: Float, dy: Float, done: (String?) -> Unit = {}) =
        send("input", JSONObject().put("type", "scroll").put("x", x.coerceIn(0f, 1f).toDouble()).put("y", y.coerceIn(0f, 1f).toDouble()).put("dy", dy.coerceIn(-5000f, 5000f).toDouble()), done)
    fun type(text: String, done: (String?) -> Unit = {}) { if (text.isNotEmpty()) send("input", JSONObject().put("type", "type").put("text", text.take(2000)), done) }
    fun key(name: String, done: (String?) -> Unit = {}) { if (name in KEYS) send("input", JSONObject().put("type", "key").put("key", name), done) }
    fun back(done: (String?) -> Unit = {}) = send("input", JSONObject().put("type", "back"), done)

    companion object {
        const val FRAME_MS = 120L
        private val KEYS = setOf("Enter", "Backspace", "Tab", "Escape")
        /** Test-only: the stream's reader. */
        @Volatile var streamForTest: ServerJobs.Stream? = null
        private val STREAM = ServerJobs.Stream { url, token, _ ->
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.instanceFollowRedirects = false; conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            conn.setRequestProperty("Authorization", "Bearer $token"); conn.setRequestProperty("Accept", "text/event-stream")
            if (conn.responseCode != 200 || conn.contentType?.contains("event-stream") != true) { conn.disconnect(); null }
            else java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)
        }

        private fun fraction(o: JSONObject, key: String): Float? = o.opt(key).let { if (it is Number && it.toDouble() in 0.0..1.0) it.toFloat() else null }
        private fun id(o: JSONObject, key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }?.take(100)

        /** The agent's browser command in plain words. */
        fun words(command: String): String = when (command.lowercase()) {
            "click", "tap", "dblclick", "check", "uncheck", "select" -> "Clicking"
            "fill", "type", "press", "keyboard" -> "Typing"
            "open", "navigate", "goto" -> "Opening a page"
            "scroll", "scrollintoview" -> "Scrolling"
            "snapshot", "get", "find" -> "Reading the page"
            "screenshot" -> "Looking at the page"
            "back" -> "Going back"
            "wait", "wait_for" -> "Waiting for the page"
            "hover" -> "Pointing"
            else -> command.replace('_', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Working" }
        }

        /** One event from the Live browser's stream, checked field by field; anything else is ignored. */
        fun parse(event: String?, raw: String): Update? {
            val data = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            return when (event) {
                "frame", "thumb" -> {
                    if (event == "thumb" && !data.optBoolean("full")) return null
                    val b64 = data.optString("data")
                    if (b64.isBlank() || b64.length > 4_000_000) return null
                    val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return null
                    // A JPEG starts with FF D8.
                    if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
                    Update.Frame(id(data, "tabId"), bytes)
                }
                "status" -> {
                    val viewport = data.optJSONObject("viewport")
                    Update.Now(Status(data.optString("mode").takeIf { it == "human" || it == "agent" } ?: "agent",
                        data.optString("url").takeIf { it.startsWith("http") }?.take(500), data.optString("title").takeIf { it.isNotBlank() && it != "null" }?.oneLine()?.take(200),
                        id(data, "activeTabId"),
                        (viewport?.optInt("width", 1280) ?: 1280).coerceIn(1, 10_000), (viewport?.optInt("height", 800) ?: 800).coerceIn(1, 10_000),
                        data.optString("busy").takeIf { it.isNotBlank() && it != "null" }?.take(40)))
                }
                "action" -> {
                    val phase = data.optString("phase").takeIf { it in setOf("point", "act", "done", "error") } ?: "act"
                    val label = data.optString("label").oneLine().take(120).ifBlank { words(data.optString("command").ifBlank { data.optString("tool") }.take(40)) }
                    Update.Did(Action(phase, label, fraction(data, "x"), fraction(data, "y")))
                }
                "handover" -> Update.HandOver(id(data, "tabId"))
                else -> null
            }
        }
    }
}
