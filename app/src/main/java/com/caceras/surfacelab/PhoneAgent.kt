package com.caceras.surfacelab

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** What a tool did, in words for the model, with a picture when it took one. */
class ToolOutcome(val ok: Boolean, val result: String, val image: ByteArray? = null)

/** A call checked before anything is asked or run. */
sealed class Prepared {
    /**
     * Ready to ask about and run. [risk] may be higher than the tool's own (a Send button makes a
     * tap always ask); [reason] says why in plain words ("This sends a message").
     */
    class Ready(
        val spec: ToolSpec,
        val args: JSONObject,
        val risk: Risk,
        val label: String,
        val why: String,
        val app: String,
        val appName: String,
        val reason: String? = null,
        val payload: Any? = null,
    ) : Prepared()

    /** Not run, with the reason for the model. */
    class Refused(val outcome: ToolOutcome) : Prepared()
}

/** What runs the agent's tools: the hands on this phone, and Ægentica's back office on aegentica.se. */
interface AgentToolbox {
    /** Tools the model may call right now. */
    fun offered(): List<ToolSpec>
    fun prepare(spec: ToolSpec, args: JSONObject): Prepared
    /** Runs a ready call; [done] may come on any thread. Must end promptly after [stop]. */
    fun run(call: Prepared.Ready, done: (ToolOutcome) -> Unit)
    /** The person allowed this kind of step always for its app. */
    fun remembered(call: Prepared.Ready): Boolean = false
    fun remember(call: Prepared.Ready) {}
    /** This acting step runs without asking (hands-free on the phone); steps that always ask still ask. */
    fun handsFree(call: Prepared.Ready): Boolean = false
    fun stop() {}
}

object NoTools : AgentToolbox {
    override fun offered() = emptyList<ToolSpec>()
    override fun prepare(spec: ToolSpec, args: JSONObject) = Prepared.Refused(ToolOutcome(false, "${spec.name} is not available."))
    override fun run(call: Prepared.Ready, done: (ToolOutcome) -> Unit) = done(ToolOutcome(false, "Not available."))
}

/** The person's answer to an Allow prompt. */
data class Decision(val allow: Boolean, val always: Boolean = false, val reason: String? = null)

/** An Allow prompt waiting for the person. */
class Approval(val call: AgentToolCall, val prepared: Prepared.Ready) {
    val label get() = prepared.label
    val why get() = prepared.why
    val risk get() = prepared.risk
    val appName get() = prepared.appName
    val reason get() = prepared.reason
    /** "Always for <app>" is offered only for ordinary acting steps in a named app. */
    val rememberable get() = prepared.risk == Risk.ACT && prepared.app.isNotBlank()
}

/** One step as the person sees it. */
data class AgentStep(val id: String, val label: String, val status: String)

/** The latest exchange, as the sheet and the overlay draw it. */
data class AgentView(
    val question: String,
    val steps: List<AgentStep>,
    val answer: String,
    val busy: Boolean,
    val approval: Approval?,
    val error: String?,
    /** The request never reached OpenRouter (no connection). */
    val offline: Boolean,
    val cost: Double?,
    val stopped: Boolean,
    val aloud: Boolean,
)

/**
 * The phone's own agent: a tool loop on OpenRouter, ported from Glimt's background runner
 * (src/background/runner.ts). Each run streams an answer and loops: stream → run tool calls (reads
 * on their own, actions after the person's Allow) → send the results → stream again, until the
 * model answers without tools or reaches the step limit. A message sent while it works steers the
 * run: the model call in progress stops, a pending Allow is set aside, and the next step reads the
 * new instruction. Stop ends everything at once. Model text is never run as a command: every tool
 * call is checked against the playbook and validated by [AgentToolbox] before anything happens.
 */
class AgentSession(private val context: Context, val id: String = UUID.randomUUID().toString()) {
    val thread = CopyOnWriteArrayList<AgentTurn>()
    @Volatile var tools: AgentToolbox = NoTools
    /** Called on the main thread, at most every 60 ms while streaming, at once on each step change. */
    @Volatile var listener: ((AgentSession) -> Unit)? = null
    /** More watchers of the same updates: the rabbit's bar and the notification. */
    private val observers = CopyOnWriteArrayList<(AgentSession) -> Unit>()
    fun addObserver(observer: (AgentSession) -> Unit) { if (observer !in observers) observers += observer }
    fun removeObserver(observer: (AgentSession) -> Unit) { observers -= observer }
    private fun publish() { listener?.invoke(this); observers.forEach { it(this) } }
    @Volatile private var run: Run? = null
    /** Where the current exchange starts: the question that started the latest run. */
    @Volatile private var exchangeStart = 0
    @Volatile var lastError: String? = null; private set
    @Volatile var offline = false; private set
    private val main = Handler(Looper.getMainLooper())
    private var emitQueued = false
    private val playbook by lazy { Playbook.get(context) }

    val running get() = run != null

    private class Run {
        @Volatile var aborted = false
        @Volatile var steered = false
        val call = AtomicReference<ConnectedAI.Call?>()
        val approvals = java.util.concurrent.ConcurrentHashMap<String, (Decision) -> Unit>()
        @Volatile var approval: Approval? = null
    }

    /** Ask, or steer the run in progress with a new instruction. */
    fun send(turn: AgentTurn.User) {
        thread += turn
        val current = run
        if (current != null) {
            current.steered = true
            current.call.get()?.cancel()
            setAside(current, SET_ASIDE)
            emit(now = true)
            return
        }
        exchangeStart = thread.size - 1
        start()
    }

    /** Stop everything: the model call, a pending Allow and the tools. */
    fun stop() {
        val current = run ?: return
        if (current.aborted) return
        current.aborted = true
        current.call.get()?.cancel()
        setAside(current, STOPPED)
        tools.stop()
        emit(now = true)
    }

    /** The person's answer to the pending Allow prompt. */
    fun decide(callId: String, decision: Decision) {
        run?.approvals?.remove(callId)?.invoke(decision)
    }

    private fun setAside(run: Run, reason: String) {
        run.approvals.keys.toList().forEach { id -> run.approvals.remove(id)?.invoke(Decision(false, reason = reason)) }
    }

    private fun start() {
        val current = Run()
        run = current
        lastError = null; offline = false
        emit(now = true)
        Thread({
            try { loop(current) } catch (e: Exception) { lastError = e.message ?: "The agent stopped unexpectedly." }
            finally { if (run === current) run = null; emit(now = true) }
        }, "phone-agent").start()
    }

    private fun loop(run: Run) {
        var budget = MAX_STEPS
        val ceiling = MAX_STEPS * 3
        var step = 0
        var total = 0
        while (true) {
            if (run.steered) {
                run.steered = false
                step = 0
                // A new instruction gives the agent a fresh step budget, up to three in all.
                budget = minOf(MAX_STEPS, maxOf(1, ceiling - total))
            }
            // Only tools offered this step ever run, whatever name the model (or text injected into it) asks for.
            val offered = tools.offered()
            val reply = AgentReply(PhoneAgent.model(context))
            thread += AgentTurn.Assistant(reply)
            emit(now = true)
            stream(run, reply, offered, final = false)
            if (run.aborted) return
            val calls = reply.tools.filter { it.name.isNotEmpty() && it.status == "pending" }
            if (run.steered) {
                // Interrupted by a new instruction: nothing it was about to do runs.
                calls.forEach { it.status = "denied"; it.result = SET_ASIDE }
                emit(now = true)
                continue
            }
            calls.filter { !AgentStream.argsComplete(it.args) }.forEach { it.status = "error"; it.result = "Not run: the call was cut off at the output limit." }
            if (reply.status != "done" || calls.none { it.status == "pending" }) {
                // An instruction that arrived while the answer was being written still gets its turn.
                if (run.steered) continue
                return
            }
            if (step + 1 >= budget) {
                calls.forEach { it.status = "error"; it.result = "Step limit reached. Summarise what you found and what is left." }
                emit(now = true)
                val last = AgentReply(PhoneAgent.model(context))
                thread += AgentTurn.Assistant(last)
                stream(run, last, offered, final = true)
                if (run.steered && !run.aborted) continue
                return
            }
            for (call in calls) {
                if (call.status != "pending") continue
                if (run.aborted || run.steered) { call.status = "denied"; call.result = if (run.aborted) STOPPED else SET_ASIDE; continue }
                exec(run, call, offered)
                emit(now = true)
            }
            if (run.aborted) return
            step++; total++
        }
    }

    private fun stream(run: Run, reply: AgentReply, offered: List<ToolSpec>, final: Boolean) {
        val key = PhoneAgent.key(context)
        if (key == null) { reply.status = "error"; reply.error = PhoneAgent.NO_KEY; lastError = reply.error; return }
        val history = thread.toList().dropLast(1)
        val hands = offered.any { it.target == "phone" }
        val backend = offered.any { it.target == "backend" }
        val body = AgentRequest.body(history, playbook, reply.model, id, offered, PhoneAgent.vision(context), hands, final, backend = backend)
        val payload = body.toString().toByteArray()
        try {
            require(payload.size <= 12_000_000) { "The request is too large to send. Start a new question." }
            val headers = mapOf("Content-Type" to "application/json", "Accept" to "text/event-stream, application/json",
                "Authorization" to "Bearer $key", "X-Title" to "Ægentica phone", "HTTP-Referer" to "https://aegentica.se")
            val response = ConnectedAI.http.post(PhoneAgent.ENDPOINT, headers, payload) { run.call.set(it) }
            try {
                if (run.aborted || run.steered) { reply.status = "stopped"; return }
                if (response.code !in 200..299) {
                    reply.status = "error"
                    reply.error = ConnectedAI.problem(response.code, response.body.readBounded(200_000).toString(Charsets.UTF_8), "OpenRouter")
                    lastError = reply.error
                    return
                }
                if (response.contentType.contains("event-stream")) readSse(run, reply, InputStreamReader(response.body, Charsets.UTF_8))
                else synchronized(reply) { AgentStream.apply(reply, JSONObject(response.body.readBounded(4_000_000).toString(Charsets.UTF_8))) }
                synchronized(reply) {
                    when {
                        run.aborted || run.steered -> reply.status = "stopped"
                        reply.status == "error" -> lastError = reply.error
                        else -> reply.status = "done"
                    }
                }
            } finally { response.close(); run.call.set(null) }
        } catch (e: IOException) {
            synchronized(reply) {
                if (run.aborted || run.steered) reply.status = "stopped"
                else { reply.status = "error"; offline = !online(); reply.error = if (offline) OFFLINE else (e.message ?: "The connection to OpenRouter broke."); lastError = reply.error }
            }
        } catch (e: Exception) {
            synchronized(reply) { reply.status = if (run.aborted || run.steered) "stopped" else "error"; reply.error = e.message; if (reply.status == "error") lastError = e.message }
        } finally { emit(now = true) }
    }

    private fun readSse(run: Run, reply: AgentReply, reader: InputStreamReader) {
        val parser = SseParser()
        val buffer = CharArray(8192)
        fun handle(events: List<SseParser.Event>): Boolean {
            for (event in events) {
                if (event !is SseParser.Event.Data) continue
                if (event.data.trim() == "[DONE]") return false
                val chunk = runCatching { JSONObject(event.data) }.getOrNull() ?: continue
                synchronized(reply) { AgentStream.apply(reply, chunk) }
                if (reply.text.length > 400_000) { reply.status = "error"; reply.error = "The answer is too long."; return false }
                emit()
                if (reply.status == "error") return false
            }
            return true
        }
        while (!run.aborted && !run.steered) {
            val n = reader.read(buffer)
            if (n < 0) { handle(parser.end()); return }
            if (!handle(parser.push(String(buffer, 0, n)))) return
        }
    }

    private fun exec(run: Run, call: AgentToolCall, offered: List<ToolSpec>) {
        val spec = offered.firstOrNull { it.name == call.name }
        if (spec == null) {
            call.status = "error"
            call.result = when (playbook.tool(call.name)?.target) {
                "phone" -> "${call.name} is not available now: Ægentica hands is off or not connected."
                "backend" -> "${call.name} is not available now: this phone is not connected to Ægentica."
                else -> "There is no tool called ${call.name}."
            }
            return
        }
        val args = AgentStream.parseArgs(call.args)
        call.label = spec.label(args)
        val prepared = when (val p = tools.prepare(spec, args)) {
            is Prepared.Refused -> { call.status = "error"; call.result = p.outcome.result; return }
            is Prepared.Ready -> p
        }
        call.label = prepared.label
        val asks = prepared.risk == Risk.ALWAYS || (prepared.risk == Risk.ACT && !tools.handsFree(prepared) && !tools.remembered(prepared))
        if (asks) {
            call.status = "awaiting-approval"
            val approval = Approval(call, prepared)
            val decision = await<Decision>(run) { resolve -> run.approvals[call.id] = resolve; run.approval = approval; emit(now = true) }
                ?: Decision(false, reason = if (run.aborted) STOPPED else SET_ASIDE)
            run.approval = null
            run.approvals.remove(call.id)
            if (!decision.allow) { call.status = "denied"; call.result = decision.reason ?: "The person declined this action."; return }
            if (decision.always && approval.rememberable) tools.remember(prepared)
        }
        if (run.aborted || run.steered) { call.status = "denied"; call.result = if (run.aborted) STOPPED else SET_ASIDE; return }
        call.status = "running"
        emit(now = true)
        val outcome = await<ToolOutcome>(run, abortable = false) { resolve -> tools.run(prepared) { resolve(it) } }
            ?: ToolOutcome(false, STOPPED)
        call.status = if (outcome.ok) "done" else "error"
        call.result = outcome.result
        call.image = outcome.image
    }

    /**
     * Waits on the worker for a callback. An Allow prompt ([abortable]) ends at once on Stop or a
     * new instruction; a running tool finishes first (it is short, and the hands stop on Stop).
     */
    private fun <T> await(run: Run, abortable: Boolean = true, timeoutMs: Long = 5 * 60_000L, begin: ((T) -> Unit) -> Unit): T? {
        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        begin { value -> if (result.compareAndSet(null, value)) latch.countDown() }
        val until = System.nanoTime() / 1_000_000 + timeoutMs
        var stoppedAt = 0L
        while (!latch.await(100, TimeUnit.MILLISECONDS)) {
            val now = System.nanoTime() / 1_000_000
            if (abortable && (run.aborted || run.steered)) return null
            // After Stop a running tool gets three seconds to end, then the run lets go of it.
            if (run.aborted) { if (stoppedAt == 0L) stoppedAt = now else if (now - stoppedAt > 3000) return null }
            if (now > until) return null
        }
        return result.get()
    }

    private fun online(): Boolean = PhoneAgent.online(context)

    private fun emit(now: Boolean = false) {
        if (now) { main.post { publish() }; return }
        synchronized(this) { if (emitQueued) return; emitQueued = true }
        main.postDelayed({ synchronized(this) { emitQueued = false }; publish() }, 60)
    }

    /** The latest exchange: the newest question and everything after it. */
    fun view(): AgentView {
        val turns = thread.toList().drop(exchangeStart)
        val lastUser = turns.lastOrNull { it is AgentTurn.User } as AgentTurn.User?
        val firstUser = turns.firstOrNull { it is AgentTurn.User } as AgentTurn.User?
        val replies = turns.filterIsInstance<AgentTurn.Assistant>().map { it.reply }
        val steps = replies.flatMap { r -> synchronized(r) { r.tools.filter { it.name.isNotEmpty() }.map { AgentStep(it.id, it.label.ifBlank { it.name.replace('_', ' ') }, it.status) } } }
        val answer = replies.lastOrNull { synchronized(it) { it.text.isNotBlank() } }?.let { synchronized(it) { it.text.toString() } }.orEmpty()
        val error = replies.lastOrNull()?.takeIf { it.status == "error" }?.error
        val cost = replies.mapNotNull { it.usage?.cost }.takeIf { it.isNotEmpty() }?.sum()
        return AgentView(
            question = firstUser?.text.orEmpty(),
            steps = steps,
            answer = answer,
            busy = running,
            approval = run?.approval,
            error = error,
            offline = offline,
            cost = cost,
            stopped = replies.lastOrNull()?.status == "stopped",
            aloud = lastUser?.aloud == true,
        )
    }

    companion object {
        const val MAX_STEPS = 15
        const val SET_ASIDE = "Not done: the person sent a new instruction instead. Follow their latest message."
        const val STOPPED = "Stopped by the person."
        const val OFFLINE = "No connection to OpenRouter."
    }
}

/**
 * The phone agent's settings: OpenRouter (the key saved in Connected AI or from the agent's own
 * sign-in) and the model, a strong one that sees screenshots and calls tools by default. Gemini
 * Nano answers only when OpenRouter cannot be reached or no key is saved.
 */
object PhoneAgent {
    const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"
    /** Sees screenshots, calls tools reliably and writes good Swedish (checked against OpenRouter's list, October 2026). */
    const val DEFAULT_MODEL = "anthropic/claude-sonnet-5.5"
    const val NO_KEY = "Sign in to OpenRouter to use the agent."
    private fun prefs(context: Context) = context.getSharedPreferences("phone-agent", 0)

    /** Test-only stand-in for the Keystore-held key. */
    @Volatile var keyForTest: String? = null
    /** Test-only network state. */
    @Volatile var onlineForTest: Boolean? = null

    fun key(context: Context): String? = keyForTest ?: ConnectedAI.openRouterKey(context)
    /** The owner may keep the sheet on Gemini Nano only. */
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun ready(context: Context) = enabled(context) && key(context) != null
    fun model(context: Context) = prefs(context).getString("model", DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    fun vision(context: Context) = prefs(context).getBoolean("vision", true)
    fun label(context: Context) = "Ægentica · " + model(context).substringAfter('/')

    fun choose(context: Context, model: ConnectedAI.ModelInfo) {
        prefs(context).edit().putString("model", model.id).putBoolean("vision", model.vision).apply()
    }
    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("enabled", on).apply()

    fun online(context: Context): Boolean = onlineForTest ?: runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)

    /**
     * Settings → Phone agent: OpenRouter sign-in, the model, whether the assistant uses the agent
     * at all, and whether it starts listening when it opens.
     */
    fun settings(activity: android.app.Activity, after: () -> Unit = {}) {
        val layout = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL; padDp(20, 8, 20, 8) }
        val signedIn = key(activity) != null
        layout.addView(activity.label(
            (if (signedIn) "Signed in to OpenRouter. The assistant uses ${model(activity).substringAfter('/')}, which sees your screen and can use tools."
            else "Sign in to OpenRouter so the assistant can think with a strong model, see your screen and, with Ægentica hands, use your phone.") +
            " When you ask, the screen's text and a screenshot go to OpenRouter and the model's provider (providers that store or train on prompts are excluded). " +
            "OpenRouter charges your account per question. Without a connection, Gemini Nano answers on the phone.", 14f, true))
        layout.addView(activity.pill(if (signedIn) "Sign in again" else "Sign in to OpenRouter", primary = !signedIn) { OpenRouterSignIn.start(activity) }
            .apply { tag = "agent-sign-in" }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(12) })
        if (signedIn) layout.addView(activity.pill("Choose the model") {
            Thread {
                val result = runCatching { ConnectedAI.fetchModels() }
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    result.onSuccess { list -> ConnectedAI.chooseModel(activity, list.filter { it.vision && it.tools }) { m -> choose(activity, m); after() } }
                        .onFailure { android.widget.Toast.makeText(activity, it.message ?: "Could not load the model list.", android.widget.Toast.LENGTH_LONG).show() }
                }
            }.start()
        }.apply { tag = "agent-model" }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = activity.dp(8) })
        layout.addView(activity.preferenceSwitch("Use the agent in the assistant", enabled(activity)) { setEnabled(activity, it); after() }.apply { tag = "agent-enabled" })
        val listen = activity.getSharedPreferences("surfacelab", 0)
        layout.addView(activity.preferenceSwitch("Start listening when the assistant opens", listen.getBoolean("assistant_listen", true)) {
            listen.edit().putBoolean("assistant_listen", it).apply()
        }.apply { tag = "agent-listen" })
        android.app.AlertDialog.Builder(activity).setTitle("Phone agent").setView(android.widget.ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Done") { _, _ -> after() }.showProtected(activity)
    }

    /**
     * A question from any surface (the sheet, the rabbit's bar, a notification reply): with the
     * screen or selection on the first question of an invocation, the hands when they are on, and
     * the rabbit's Stop, steer and Continue wired to this conversation. Steers when a run is going.
     */
    fun ask(context: Context, session: AgentSession, text: String, screen: ScreenContext? = null, appName: String = "",
            selection: Boolean = false, aloud: Boolean = false, firstOfInvocation: Boolean = false): AgentTurn.User {
        val withScreen = firstOfInvocation && screen != null && !screen.empty
        val turn = AgentTurn.User(text = text, app = screen?.app.orEmpty(), appName = appName,
            screen = if (withScreen) screen!!.text.take(ScreenContexts.MAX_TEXT) else "", selection = selection,
            screenshot = if (withScreen) screen!!.screenshot?.let { ScreenContexts.jpeg(ScreenContexts.scaled(it)) } else null, aloud = aloud)
        session.tools = PhoneTools.forAgent(context)
        Hands.newQuestion(fresh = firstOfInvocation && !session.running)
        Hands.listener = HandsBridge(session)
        AgentNotifications.watch(context, session)
        session.send(turn)
        return turn
    }

    /** The rabbit's buttons, for one conversation. */
    private class HandsBridge(private val session: AgentSession) : Hands.Listener {
        override fun stop() = session.stop()
        override fun steer(text: String) { Hands.newQuestion(fresh = false); session.send(AgentTurn.User(text, notes = listOf("Sent while you worked."))) }
        override fun handedBack() { Hands.newQuestion(fresh = false); session.send(AgentTurn.User("I handed the screen back. Continue where you left off.", notes = listOf("The person handed the screen back."))) }
    }

    /** The session the sheet and the overlay share; a new question on a fresh invocation starts a new one. */
    @Volatile var current: AgentSession? = null

    fun session(context: Context, fresh: Boolean): AgentSession {
        val existing = current
        if (existing != null && (!fresh || existing.running)) return existing
        // The conversation it replaces is over: it goes to Ægentica's chat history when the phone is connected.
        existing?.let { Backend.saveConversation(context.applicationContext, it) }
        return AgentSession(context.applicationContext).also { current = it }
    }
}
