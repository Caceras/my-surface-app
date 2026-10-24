package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * One element on the screen, as the hands see it. [HandsService] adapts Android's
 * AccessibilityNodeInfo; tests stand in their own.
 */
interface UiNode {
    /** Identity across snapshots: the same element gives an equal key. */
    val key: Any
    val className: String
    val text: String?
    val description: String?
    val hint: String?
    val viewId: String?
    /** Bounds in screen pixels. */
    val bounds: Rect
    val clickable: Boolean
    val longClickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val checkable: Boolean
    val checked: Boolean
    val enabled: Boolean
    val selected: Boolean
    val password: Boolean
    val inputType: Int
    val visible: Boolean
    /** The field shows its hint, not text the person typed. */
    val showingHint: Boolean
    val packageName: String
    val children: List<UiNode>
    val parent: UiNode?
    /** Re-reads the element; false when it is gone. */
    fun refresh(): Boolean
    fun click(): Boolean
    fun longClick(): Boolean
    fun focus(): Boolean
    fun setText(text: String): Boolean
    /** Scrolls this container: "up", "down", "left" or "right" (what comes into view). */
    fun scroll(direction: String): Boolean
    /** The keyboard's Enter or Send, where the field offers it. */
    fun imeEnter(): Boolean
}

/** The phone's side of the hands: the screen and Android's gestures. [HandsService] is the real one. */
interface Screen {
    /** The app in front (never Ægentica's own sheet or overlay), or null when Android shows none. */
    fun root(): UiNode?
    val width: Int
    val height: Int
    fun tap(x: Int, y: Int, done: (Boolean) -> Unit)
    fun longPress(x: Int, y: Int, done: (Boolean) -> Unit)
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, done: (Boolean) -> Unit)
    /** Back, Home or Recents. */
    fun global(action: String): Boolean
    /** A screenshot with Ægentica's own windows hidden; null with the reason when Android refuses. */
    fun screenshot(done: (Bitmap?, String?) -> Unit)
    fun launch(intent: Intent): Boolean
    /** Apps with a launcher icon: name, package and the intent that opens them. */
    fun apps(): List<Triple<String, String, Intent>>
    fun appName(pkg: String): String
}

/** What a snapshot line says about one element. */
data class SnapshotItem(val ref: String, val role: String, val name: String, val value: String?, val state: List<String>, val bounds: Rect, val secret: Boolean)

/**
 * The hands' rules, from the shared playbook: which fields are secrets, which steps always ask,
 * and how an element is named. Nothing here takes words from the model.
 */
class HandsPolicy(private val playbook: Playbook) {
    private fun words(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFC)
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** View ids like "btnSend" or "send_button" read as words too. */
    private fun idWords(id: String?): List<String> =
        id.orEmpty().substringAfter(":id/").replace(Regex("([a-z])([A-Z])"), "$1 $2").let(::words)

    /** A phrase matches as whole words, in order. */
    private fun contains(haystack: List<String>, phrase: String): Boolean {
        val needle = words(phrase)
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
    }

    /**
     * Passwords (also after "show password" turns them into text), card and one-time-code fields.
     * Hiding a harmless value by over-matching is the safe mistake.
     */
    fun secret(node: UiNode): Boolean {
        if (node.password || ScreenContexts.secret(node.inputType)) return true
        if (!node.editable) return false
        val label = words(listOfNotNull(node.hint, node.description, if (node.showingHint) node.text else null).joinToString(" ")) + idWords(node.viewId)
        return playbook.secretFields.any { field ->
            val parts = words(field)
            if (parts.size == 1) label.any { it.startsWith(parts[0]) } else contains(label, field)
        }
    }

    /** Why a step on this element always asks, or null. Kinds: send, call, pay, delete, account. */
    fun sensitive(node: UiNode?, app: String): String? {
        if (app in playbook.sensitiveApps) return "This is a banking, payment or store app"
        if (node == null) return null
        val label = words(listOfNotNull(name(node), node.description).joinToString(" ")) + idWords(node.viewId)
        val kind = playbook.sensitiveWords.entries.firstOrNull { (_, list) -> list.any { contains(label, it) } }?.key ?: return null
        return REASONS.getValue(kind)
    }

    fun role(node: UiNode): String {
        val c = node.className.substringAfterLast('.')
        return when {
            node.editable || c.contains("EditText") -> "field"
            c.contains("Switch") -> "switch"
            c.contains("CheckBox") -> "checkbox"
            c.contains("RadioButton") -> "radio"
            c.contains("ToggleButton") -> "toggle"
            c.contains("SeekBar") || c.contains("Slider") -> "slider"
            c.contains("Spinner") -> "dropdown"
            c.contains("WebView") -> "web"
            c.contains("Tab") -> "tab"
            c.contains("Button") -> "button"
            node.scrollable || c.contains("RecyclerView") || c.contains("ListView") || c.contains("ScrollView") || c.contains("GridView") || c.contains("ViewPager") -> "list"
            node.checkable -> "checkbox"
            node.clickable || node.longClickable -> "button"
            c.contains("Image") -> "image"
            else -> "text"
        }
    }

    /** What a person would call it: its text, label or hint, the words inside a tappable row, else its id. */
    fun name(node: UiNode): String {
        fun clean(s: String?) = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        val own = clean(if (node.showingHint) null else node.text) ?: clean(node.description) ?: clean(node.hint)
        if (own != null) return own.take(80)
        if (node.clickable || node.longClickable || node.checkable) {
            val inside = mutableListOf<String>()
            fun visit(n: UiNode, depth: Int) {
                if (inside.size >= 2 || depth > 6) return
                (clean(n.text) ?: clean(n.description))?.let { if (it !in inside) inside += it }
                n.children.forEach { visit(it, depth + 1) }
            }
            node.children.forEach { visit(it, 0) }
            if (inside.isNotEmpty()) return inside.joinToString(" · ").take(80)
        }
        return node.viewId?.substringAfter(":id/")?.replace('_', ' ')?.take(80).orEmpty()
    }

    companion object {
        val REASONS = mapOf(
            "send" to "This sends or shares something",
            "call" to "This starts a call",
            "pay" to "This may pay, buy or book something",
            "delete" to "This deletes or removes something",
            "account" to "This changes an account or a permission",
        )
    }
}

/**
 * Element refs ("e12") that stay the same for the same element across snapshots, as in Glimt. A
 * new conversation starts again at e1. "@e12", as agent-browser writes them, is accepted too.
 */
class HandsRefs {
    private val byKey = HashMap<Any, String>()
    private val byRef = HashMap<String, UiNode>()
    private var next = 1

    fun refFor(node: UiNode): String {
        val ref = byKey.getOrPut(node.key) { "e${next++}" }
        byRef[ref] = node
        return ref
    }

    fun node(ref: String): UiNode? = byRef[normal(ref)]

    fun clear() { byKey.clear(); byRef.clear(); next = 1 }

    companion object {
        private val REF = Regex("@?e(\\d{1,6})")
        fun valid(ref: String) = REF.matches(ref.trim())
        fun normal(ref: String) = ref.trim().removePrefix("@")
    }
}

/** The compact screen listing the model reads, ported from Glimt's snapshot format. */
object HandsSnapshot {
    const val LIMIT = 150

    fun items(root: UiNode, policy: HandsPolicy, refs: HandsRefs, screen: Rect): List<SnapshotItem> {
        val out = mutableListOf<SnapshotItem>()
        fun visit(node: UiNode, depth: Int) {
            if (depth > 60) return
            if (!node.visible) return
            val box = node.bounds
            val onScreen = box.width() > 0 && box.height() > 0 && Rect.intersects(box, screen)
            val interactive = node.clickable || node.longClickable || node.editable || node.scrollable || node.checkable
            val name = policy.name(node)
            val hasText = !node.text.isNullOrBlank() || !node.description.isNullOrBlank()
            if (onScreen && (interactive || hasText)) {
                val secret = policy.secret(node)
                val role = policy.role(node)
                val state = mutableListOf<String>()
                if (node.checkable) state += if (node.checked) "on" else "off"
                if (!node.enabled) state += "disabled"
                if (node.selected) state += "selected"
                if (node.clickable) state += "tap"
                if (node.longClickable) state += "hold"
                if (node.editable) state += "type"
                if (node.scrollable) state += "scroll"
                if (secret) state += "secret"
                val value = if (node.editable) {
                    val typed = if (node.showingHint) "" else node.text.orEmpty()
                    if (secret && typed.isNotEmpty()) "••••" else typed.take(80)
                } else null
                // A tappable row names itself from the text inside it; those texts are not listed again.
                val label = if (node.editable) (node.hint?.takeIf { it.isNotBlank() } ?: node.description ?: name) else name
                if (interactive || label.isNotBlank()) out += SnapshotItem(refs.refFor(node), role, label.replace(Regex("\\s+"), " ").trim().take(80), value, state, Rect(box), secret)
                if ((node.clickable || node.checkable) && !node.editable && node.children.none { it.clickable || it.editable || it.scrollable }) return
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out
    }

    fun line(item: SnapshotItem): String {
        val value = item.value?.let { " value=“$it”" }.orEmpty()
        val state = if (item.state.isEmpty()) "" else " [" + item.state.joinToString(", ") + "]"
        val b = item.bounds
        return "${item.ref} ${item.role} “${item.name}”$value$state @[${b.left},${b.top},${b.right},${b.bottom}]"
    }

    fun text(header: String, items: List<SnapshotItem>): String {
        val shown = items.take(LIMIT)
        val more = if (items.size > shown.size) "\n…and ${items.size - shown.size} more. Scroll or use phone_find." else ""
        return header + "\n" + (if (shown.isEmpty()) "(Nothing readable on this screen. Try phone_screenshot.)" else shown.joinToString("\n") { line(it) }) + more
    }
}

/**
 * What the rabbit overlay does for the hands ([RabbitOverlay] draws it over every app). Called on
 * the main thread. Animations are short; with Android's animations off they are skipped.
 */
interface HandsOverlay {
    /** The assistant gesture: the genie's bar opens over the app with the screen Android shared. */
    fun summon()
    /** The hands start working: the rabbit appears with the Stop button. */
    fun show()
    /** Fly to an element before acting on it; [then] runs when the rabbit is there (about 250 ms). */
    fun flyTo(bounds: Rect, label: String, then: () -> Unit)
    fun tapped(x: Int, y: Int)
    fun typing(bounds: Rect)
    fun scrolled(bounds: Rect, direction: String)
    fun highlight(bounds: Rect)
    /** A new screen opened. */
    fun poof()
    fun status(text: String)
    /** Ask the person to do a step themselves ("Take over to type your password"). */
    fun handOver(text: String)
    fun paused()
    fun stopped()
    /** The run ended; the answer's first words in the bubble, then the rabbit leaves. */
    fun done(answer: String?)
    /** Hide Ægentica's own windows for a screenshot. */
    fun hideForCapture(hidden: Boolean)
    fun close()
}

/**
 * Ægentica hands: the opt-in accessibility service's state, and the one door every brain uses to
 * reach it ([HandsTools]). The service attaches the screen and the overlay when Android binds it;
 * Stop, the person touching the screen and a new question move it between working, paused and
 * stopped. Model text never reaches Android directly: every step is a validated tool call.
 */
object Hands {
    @Volatile var screen: Screen? = null; private set
    @Volatile var overlay: HandsOverlay? = null; private set
    val connected get() = screen != null
    /** Stop was pressed: every step is refused until the next question. */
    @Volatile var stopped = false; private set
    /** The person touched the screen: steps are refused until they hand it back. */
    @Volatile var paused = false; private set
    /** The hands are working on a question (the overlay shows). */
    @Volatile var active = false; private set
    @Volatile private var injectingUntil = 0L
    val refs = HandsRefs()
    private val main = Handler(Looper.getMainLooper())

    /** The brain's side: Stop, a steer from the rabbit, and the screen handed back. */
    interface Listener {
        fun stop()
        fun steer(text: String)
        fun handedBack()
    }
    @Volatile var listener: Listener? = null

    fun attach(screen: Screen, overlay: HandsOverlay?) { this.screen = screen; this.overlay = overlay }

    fun detach() {
        val o = overlay
        screen = null; overlay = null; active = false; paused = false
        main.post { o?.close() }
    }

    /** A new question: the hands may work again, with fresh refs for a fresh conversation. */
    fun newQuestion(fresh: Boolean) {
        stopped = false; paused = false
        if (fresh) synchronized(refs) { refs.clear() }
    }

    /** Called when the hands start working, so the sheet can step aside for the rabbit. */
    @Volatile var onActive: (() -> Unit)? = null

    /** The first step of a run: the rabbit appears and the sheet steps aside. */
    fun begin() {
        if (active) return
        active = true
        main.post { overlay?.show(); onActive?.invoke() }
    }

    fun end(answer: String?) {
        if (!active) return
        active = false; paused = false
        main.post { overlay?.done(answer) }
    }

    /** Stop: from the overlay's button or the sheet. Everything stops at once. */
    fun stop() {
        if (stopped && !active) return
        stopped = true; paused = false; active = false
        main.post { overlay?.stopped() }
        listener?.stop()
    }

    /** The person touched the screen while the hands work: they have it now. Our own gestures do not count. */
    fun userTouched() {
        if (!active || paused || stopped || SystemClock.uptimeMillis() < injectingUntil) return
        paused = true
        main.post { overlay?.paused() }
    }

    /** "Continue" on the rabbit: the agent gets the screen back. */
    fun handBack() {
        if (!paused) return
        paused = false
        listener?.handedBack()
    }

    fun steer(text: String) { if (text.isNotBlank()) listener?.steer(text.trim()) }

    /** Our own gesture is on its way: touches until then are not the person's. */
    fun injecting(ms: Long) { injectingUntil = maxOf(injectingUntil, SystemClock.uptimeMillis() + ms) }

    internal fun resetForTest() { screen = null; overlay = null; stopped = false; paused = false; active = false; injectingUntil = 0; listener = null; refs.clear() }
}

/** Where a ready call acts: the element (when it has one), a point, and the screen before it. */
class HandsTarget(val node: UiNode?, val x: Int, val y: Int, val bounds: Rect, val before: String)

/**
 * The hands as the agent's toolbox: every phone_* call from the playbook is checked here before
 * anything is asked or run (refs, bounds, text size, link schemes, secrets), its tier is raised
 * when the screen says the step sends, calls, pays, deletes or changes an account, and then it
 * runs through [Screen] with the rabbit showing each move. Ported from Glimt's hands
 * (src/bar/hands.ts) and page actions (src/page/extract.ts).
 */
class HandsTools(private val context: Context, private val playbook: Playbook = Playbook.get(context)) : AgentToolbox {
    private val policy = HandsPolicy(playbook)
    private val prefs get() = context.getSharedPreferences(PREFS, 0)
    private val refs get() = Hands.refs
    private val main = Handler(Looper.getMainLooper())

    override fun offered(): List<ToolSpec> = if (Hands.connected && enabled(context)) playbook.tools.filter { it.target == "phone" } else emptyList()

    override fun handsFree() = prefs.getBoolean("hands_free", false)
    override fun remembered(call: Prepared.Ready) = call.risk == Risk.ACT && call.app in always(context)
    override fun remember(call: Prepared.Ready) { if (call.risk == Risk.ACT && call.app.isNotBlank()) prefs.edit().putStringSet("always", always(context) + call.app).apply() }
    override fun stop() = Hands.stop()

    private fun refused(text: String) = Prepared.Refused(ToolOutcome(false, text))
    private fun screenRect(screen: Screen) = Rect(0, 0, screen.width, screen.height)

    /** A one-line signature of the screen, to tell whether a step opened a new one. */
    private fun signature(root: UiNode?): String = root?.let { r -> r.packageName + "|" + HandsSnapshot.items(r, policy, HandsRefs(), Rect(0, 0, 100_000, 100_000)).take(8).joinToString("|") { it.role + it.name } }.orEmpty()

    override fun prepare(spec: ToolSpec, args: JSONObject): Prepared {
        val screen = Hands.screen ?: return refused("Ægentica hands is not connected. Ask the person to turn it on in Settings → Accessibility.")
        if (Hands.stopped) return refused(AgentSession.STOPPED)
        if (Hands.paused) return refused(TOOK_OVER)
        val why = args.optString("why").oneLine()
        if (spec.needsWhy && (why.length < playbook.whyMin || why.length > playbook.whyMax))
            return refused("Give `why`: one short sentence (${playbook.whyMin} to ${playbook.whyMax} characters) saying what this step does.")
        val root = screen.root()
        val app = root?.packageName.orEmpty()
        val appName = if (app.isBlank()) "" else screen.appName(app)
        fun ready(risk: Risk, target: HandsTarget?, reason: String? = null, label: String = spec.label(args), forApp: String = app, forName: String = appName): Prepared {
            val sensitive = policy.sensitive(target?.node, forApp)
            val final = when {
                spec.risk == Risk.READ -> Risk.READ
                risk == Risk.ALWAYS || sensitive != null -> Risk.ALWAYS
                else -> risk
            }
            return Prepared.Ready(spec, args, final, label, why, forApp, forName, reason ?: sensitive.takeIf { final == Risk.ALWAYS }, target)
        }
        fun element(): Pair<UiNode?, String?> {
            val ref = args.optString("ref").trim()
            if (ref.isEmpty()) return null to null
            if (!HandsRefs.valid(ref)) return null to "“$ref” is not a ref. Use one like e12 from phone_snapshot."
            val node = synchronized(refs) { refs.node(ref) } ?: return null to "No element $ref on this screen. Take a new phone_snapshot."
            if (!node.refresh() || !node.visible) return null to "No element $ref on the screen any more. Take a new phone_snapshot."
            if (node.packageName == context.packageName) return null to "That is Ægentica's own control; it does not tap its own buttons."
            return node to null
        }
        fun point(): Pair<HandsTarget?, String?> {
            val (node, problem) = element()
            if (problem != null) return null to problem
            if (node != null) { val b = node.bounds; return HandsTarget(node, b.centerX(), b.centerY(), Rect(b), signature(root)) to null }
            if (!args.has("x") || !args.has("y")) return null to "Give a ref from phone_snapshot, or x and y."
            val x = args.optInt("x", -1); val y = args.optInt("y", -1)
            if (x !in 0 until screen.width || y !in 0 until screen.height) return null to "($x, $y) is outside the screen (${screen.width}×${screen.height})."
            val hit = root?.let { hitTest(it, x, y) }
            if (hit?.packageName == context.packageName) return null to "That spot is Ægentica's own control; it does not tap its own buttons."
            return HandsTarget(hit, x, y, hit?.bounds?.let(::Rect) ?: Rect(x - 24, y - 24, x + 24, y + 24), signature(root)) to null
        }
        return when (spec.name) {
            "phone_snapshot", "phone_screenshot", "phone_find" -> {
                if (spec.name == "phone_find" && args.optString("query").isBlank()) return refused("Give words to find.")
                ready(Risk.READ, null)
            }
            "phone_highlight" -> {
                val (node, problem) = element()
                if (problem != null || node == null) refused(problem ?: "Give a ref.")
                else ready(Risk.READ, HandsTarget(node, node.bounds.centerX(), node.bounds.centerY(), Rect(node.bounds), ""))
            }
            "phone_wait_for" -> {
                val text = args.optString("text").trim()
                if (text.isEmpty() || text.length > 200) refused("Give the text to wait for (up to 200 characters).") else ready(Risk.READ, null)
            }
            "phone_scroll" -> {
                val direction = args.optString("direction")
                if (direction !in DIRECTIONS) return refused("direction must be up, down, left or right.")
                val (node, problem) = element()
                if (problem != null) return refused(problem)
                val box = node?.bounds ?: screenRect(screen)
                ready(Risk.READ, HandsTarget(node, box.centerX(), box.centerY(), Rect(box), signature(root)))
            }
            "phone_tap", "phone_long_press" -> {
                val (target, problem) = point()
                if (problem != null || target == null) refused(problem ?: "Nothing to tap.") else ready(Risk.ACT, target, label = why)
            }
            "phone_type" -> {
                val (node, problem) = element()
                if (problem != null || node == null) return refused(problem ?: "Give the ref of a field.")
                if (!node.editable) return refused("${args.optString("ref")} is not a text field.")
                if (policy.secret(node)) {
                    main.post { Hands.overlay?.handOver(TAKE_OVER) }
                    return refused("${args.optString("ref")} is a password, card or one-time-code field. Ægentica never types there: tell the person \"$TAKE_OVER, then tell me to continue\" and wait.")
                }
                val text = args.optString("text")
                if (text.isEmpty() || text.length > MAX_TEXT) return refused("Give the text to type (up to $MAX_TEXT characters).")
                val submit = args.optBoolean("submit")
                ready(if (submit) Risk.ALWAYS else Risk.ACT, HandsTarget(node, node.bounds.centerX(), node.bounds.centerY(), Rect(node.bounds), signature(root)),
                    reason = if (submit) "This presses Enter or Send, which may send what was typed" else null, label = why)
            }
            "phone_swipe" -> {
                val xs = listOf("x1", "y1", "x2", "y2").map { args.optInt(it, -1) }
                if (xs[0] !in 0 until screen.width || xs[2] !in 0 until screen.width || xs[1] !in 0 until screen.height || xs[3] !in 0 until screen.height)
                    return refused("The swipe must stay on the screen (${screen.width}×${screen.height}).")
                val hit = root?.let { hitTest(it, xs[0], xs[1]) }
                if (hit?.packageName == context.packageName) return refused("That spot is Ægentica's own control.")
                ready(Risk.ACT, HandsTarget(hit, xs[0], xs[1], Rect(minOf(xs[0], xs[2]), minOf(xs[1], xs[3]), maxOf(xs[0], xs[2]) + 1, maxOf(xs[1], xs[3]) + 1), signature(root)), label = why)
            }
            "phone_back", "phone_home", "phone_recents" -> ready(Risk.ACT, HandsTarget(null, screen.width / 2, screen.height - 1, Rect(0, screen.height - 1, screen.width, screen.height), signature(root)), label = why)
            "phone_open_app" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty() || name.length > 80) return refused("Give the app's name.")
                val found = findApp(screen.apps(), name)
                when {
                    found.isEmpty() -> refused("No app called “$name”. Apps on this phone include: " + screen.apps().map { it.first }.sorted().take(30).joinToString(", ") + ".")
                    found.size > 1 && found.none { normal(it.first) == normal(name) } -> refused("Several apps match “$name”: " + found.take(8).joinToString(", ") { it.first } + ". Use the full name.")
                    else -> {
                        val app2 = found.firstOrNull { normal(it.first) == normal(name) } ?: found.first()
                        ready(Risk.ACT, HandsTarget(null, screen.width / 2, screen.height / 2, screenRect(screen), signature(root)), label = why, forApp = app2.second, forName = app2.first)
                    }
                }
            }
            "phone_open_url" -> {
                val raw = args.optString("url").trim()
                val uri = runCatching { Uri.parse(raw) }.getOrNull()
                val scheme = uri?.scheme?.lowercase().orEmpty()
                when {
                    raw.length > 2000 || uri == null -> refused("Give a full web address.")
                    scheme == "https" || scheme == "http" -> if (uri.host.isNullOrBlank()) refused("Give a full web address, like https://example.se.")
                        else ready(Risk.ACT, HandsTarget(null, screen.width / 2, screen.height / 2, screenRect(screen), signature(root)), label = why)
                    scheme in playbook.sensitiveSchemes -> ready(Risk.ALWAYS, HandsTarget(null, screen.width / 2, screen.height / 2, screenRect(screen), signature(root)),
                        reason = if (scheme == "tel") "This opens the phone app to call ${uri.schemeSpecificPart.take(30)}" else "This opens a message or email to send", label = why)
                    else -> refused("Only https, tel:, sms: and mailto: links can be opened.")
                }
            }
            else -> refused("There is no tool called ${spec.name}.")
        }
    }

    override fun run(call: Prepared.Ready, done: (ToolOutcome) -> Unit) {
        val screen = Hands.screen ?: return done(ToolOutcome(false, "Ægentica hands disconnected."))
        if (Hands.stopped) return done(ToolOutcome(false, AgentSession.STOPPED))
        if (Hands.paused) return done(ToolOutcome(false, TOOK_OVER))
        Hands.begin()
        val target = call.payload as? HandsTarget
        val args = call.args
        val overlay = Hands.overlay
        fun onMain(block: () -> Unit) = main.post { if (!Hands.stopped) block() }
        try {
            when (call.spec.name) {
                "phone_snapshot" -> done(ToolOutcome(true, snapshot(screen)))
                "phone_find" -> done(ToolOutcome(true, find(screen, args.optString("query"))))
                "phone_highlight" -> { onMain { overlay?.highlight(target!!.bounds) }; done(ToolOutcome(true, "Pointed at ${args.optString("ref")}.")) }
                "phone_screenshot" -> screenshot(screen, done)
                "phone_wait_for" -> done(waitFor(screen, args))
                "phone_scroll" -> act(target!!, done, show = { overlay?.scrolled(target.bounds, args.optString("direction")) }) { finished ->
                    val direction = args.optString("direction")
                    val node = target.node ?: screen.root()?.let { largestScrollable(it) }
                    if (node != null && node.scroll(direction)) finished(true, "Scrolled $direction.")
                    else {
                        val b = target.node?.bounds ?: screenRect(screen)
                        val (x1, y1, x2, y2) = swipeFor(b, direction)
                        Hands.injecting(600)
                        screen.swipe(x1, y1, x2, y2, 350) { ok -> finished(ok, if (ok) "Scrolled $direction." else "Could not scroll here.") }
                    }
                }
                "phone_tap" -> act(target!!, done, show = { overlay?.tapped(target.x, target.y) }) { finished ->
                    val node = target.node
                    val what = describe(node)
                    // An element's own click is surer than a touch; a point gets a real touch.
                    if (node != null && args.has("ref") && clickable(node)?.click() == true) finished(true, "Tapped $what.")
                    else { Hands.injecting(400); screen.tap(target.x, target.y) { ok -> finished(ok, if (ok) "Tapped $what." else "The tap did not go through.") } }
                }
                "phone_long_press" -> act(target!!, done, show = { overlay?.tapped(target.x, target.y) }) { finished ->
                    val node = target.node
                    if (node != null && args.has("ref") && node.longClickable && node.longClick()) finished(true, "Pressed and held ${describe(node)}.")
                    else { Hands.injecting(1200); screen.longPress(target.x, target.y) { ok -> finished(ok, if (ok) "Pressed and held ${describe(node)}." else "The press did not go through.") } }
                }
                "phone_type" -> act(target!!, done, show = { overlay?.typing(target.bounds) }) { finished ->
                    val node = target.node!!
                    node.focus()
                    val text = args.optString("text")
                    val existing = if (node.showingHint) "" else node.text.orEmpty()
                    val ok = node.setText(if (args.optBoolean("clear")) text else existing + text)
                    if (!ok) { finished(false, "${describe(node)} did not take the text."); return@act }
                    if (args.optBoolean("submit") && !node.imeEnter()) { finished(false, "Typed ${text.length} characters into ${describe(node)}, but it has no Enter or Send key to press."); return@act }
                    finished(true, "Typed ${text.length} characters into ${describe(node)}" + if (args.optBoolean("submit")) " and pressed Enter." else ".")
                }
                "phone_swipe" -> act(target!!, done, show = { overlay?.scrolled(target.bounds, "down") }) { finished ->
                    val d = args.optLong("durationMs", 350).coerceIn(50, 3000)
                    Hands.injecting(d + 400)
                    screen.swipe(args.optInt("x1"), args.optInt("y1"), args.optInt("x2"), args.optInt("y2"), d) { ok -> finished(ok, if (ok) "Swiped." else "The swipe did not go through.") }
                }
                "phone_back", "phone_home", "phone_recents" -> act(target!!, done, show = {}) { finished ->
                    val action = call.spec.name.removePrefix("phone_")
                    finished(screen.global(action), when (action) { "back" -> "Pressed Back."; "home" -> "Went to the home screen."; else -> "Showed recent apps." })
                }
                "phone_open_app" -> act(target!!, done, show = {}) { finished ->
                    val app = screen.apps().firstOrNull { it.second == call.app }
                    if (app == null) finished(false, "${call.appName} is no longer installed.")
                    else finished(screen.launch(Intent(app.third).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)), "Opened ${app.first}.")
                }
                "phone_open_url" -> act(target!!, done, show = {}) { finished ->
                    val uri = Uri.parse(args.optString("url").trim())
                    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (uri.scheme?.lowercase()?.startsWith("http") == true) intent.addCategory(Intent.CATEGORY_BROWSABLE)
                    finished(screen.launch(intent), "Opened ${uri.host ?: uri.scheme}.")
                }
                else -> done(ToolOutcome(false, "There is no tool called ${call.spec.name}."))
            }
        } catch (e: Exception) {
            done(ToolOutcome(false, "Error: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /**
     * An acting step: the rabbit flies to the target first (about 250 ms, so the person sees what
     * will happen and can still stop it), then the step runs, then the screen settles and the
     * result says where the agent landed.
     */
    private fun act(target: HandsTarget, done: (ToolOutcome) -> Unit, show: () -> Unit, step: (finished: (Boolean, String) -> Unit) -> Unit) {
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            val overlay = Hands.overlay
            if (overlay == null) latch.countDown() else overlay.flyTo(target.bounds, "") { latch.countDown() }
        }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (Hands.stopped) return done(ToolOutcome(false, AgentSession.STOPPED))
        if (Hands.paused) return done(ToolOutcome(false, TOOK_OVER))
        main.post { show() }
        // Touches until the step settles are the hands' own, not the person taking over.
        Hands.injecting(1500)
        // Gesture callbacks come on the main thread: wait for the step here, on the agent's thread.
        val result = java.util.concurrent.atomic.AtomicReference<Pair<Boolean, String>?>()
        val finished = java.util.concurrent.CountDownLatch(1)
        step { ok, note -> if (result.compareAndSet(null, ok to note)) finished.countDown() }
        finished.await(10, java.util.concurrent.TimeUnit.SECONDS)
        val (ok, note) = result.get() ?: (false to "The step did not finish in time.")
        if (!ok) return done(ToolOutcome(false, note))
        Thread.sleep(SETTLE_MS)
        val screen = Hands.screen
        val after = screen?.root()
        val moved = after != null && signature(after) != target.before
        if (moved) main.post { Hands.overlay?.poof() }
        val where = when {
            after == null || screen == null -> ""
            moved && target.node != null && after.packageName != target.node.packageName -> " Now in ${screen.appName(after.packageName)}. Take a new phone_snapshot before the next step."
            moved -> " The screen changed. Take a new phone_snapshot before the next step."
            else -> ""
        }
        done(ToolOutcome(true, note + where))
    }

    fun snapshot(screen: Screen): String {
        val root = screen.root() ?: return "Android shows no app window right now (the lock screen or a protected window). Try phone_screenshot."
        val items = synchronized(refs) { HandsSnapshot.items(root, policy, refs, screenRect(screen)) }
        val header = "${screen.appName(root.packageName)} · ${root.packageName} · screen ${screen.width}×${screen.height}"
        return HandsSnapshot.text(header, items)
    }

    fun find(screen: Screen, query: String): String {
        val root = screen.root() ?: return "Android shows no app window right now."
        val q = query.trim().lowercase(Locale.ROOT)
        val items = synchronized(refs) { HandsSnapshot.items(root, policy, refs, screenRect(screen)) }
        val hits = items.filter { it.name.lowercase(Locale.ROOT).contains(q) || (!it.secret && it.value?.lowercase(Locale.ROOT)?.contains(q) == true) }.take(12)
        return if (hits.isEmpty()) "Nothing on the screen contains “$query”." else hits.joinToString("\n") { HandsSnapshot.line(it) }
    }

    private fun screenshot(screen: Screen, done: (ToolOutcome) -> Unit) {
        main.post { Hands.overlay?.hideForCapture(true) }
        Thread.sleep(80)
        screen.screenshot { bitmap, problem ->
            main.post { Hands.overlay?.hideForCapture(false) }
            if (bitmap == null) done(ToolOutcome(false, problem ?: "Android did not give a screenshot."))
            else done(ToolOutcome(true, "Screenshot attached (${bitmap.width}×${bitmap.height}, scaled from the ${screen.width}×${screen.height} screen).", ScreenContexts.jpeg(ScreenContexts.scaled(bitmap, SHOT_SIDE), 80)))
        }
    }

    private fun waitFor(screen: Screen, args: JSONObject): ToolOutcome {
        val text = args.optString("text").trim().lowercase(Locale.ROOT)
        val gone = args.optBoolean("gone")
        val timeout = args.optLong("timeoutMs", 5000).coerceIn(100, 15000)
        // Wall time on the agent's thread (SystemClock is the main thread's clock in tests).
        val until = System.nanoTime() / 1_000_000 + timeout
        while (true) {
            if (Hands.stopped) return ToolOutcome(false, AgentSession.STOPPED)
            if (Hands.paused) return ToolOutcome(false, TOOK_OVER)
            val root = screen.root()
            val hit = root?.let { r -> synchronized(refs) { HandsSnapshot.items(r, policy, refs, screenRect(screen)) }.firstOrNull { it.name.lowercase(Locale.ROOT).contains(text) } }
            if (gone && hit == null) return ToolOutcome(true, "satisfied: true. “${args.optString("text")}” is gone.")
            if (!gone && hit != null) return ToolOutcome(true, "satisfied: true. ${HandsSnapshot.line(hit)}")
            if (System.nanoTime() / 1_000_000 >= until) return ToolOutcome(true, "satisfied: false after ${timeout / 1000.0} s.")
            Thread.sleep(250)
        }
    }

    private fun describe(node: UiNode?): String = if (node == null) "the point" else "${policy.role(node)} “${policy.name(node).ifBlank { "unnamed" }}”"

    /** The element itself when it reacts to a tap, else its nearest tappable parent. */
    private fun clickable(node: UiNode): UiNode? { var n: UiNode? = node; var depth = 0; while (n != null && depth < 8) { if (n.clickable && n.enabled) return n; n = n.parent; depth++ }; return null }

    private fun hitTest(root: UiNode, x: Int, y: Int): UiNode? {
        var best: UiNode? = null
        fun visit(n: UiNode, depth: Int) {
            if (depth > 60 || !n.visible || !n.bounds.contains(x, y)) return
            best = n
            n.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return best
    }

    private fun largestScrollable(root: UiNode): UiNode? {
        var best: UiNode? = null
        fun visit(n: UiNode, depth: Int) {
            if (depth > 60 || !n.visible) return
            if (n.scrollable && (best == null || n.bounds.width() * n.bounds.height() > best!!.bounds.width() * best!!.bounds.height())) best = n
            n.children.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return best
    }

    /** A swipe that moves the content so that what is in [direction] comes into view. */
    private fun swipeFor(b: Rect, direction: String): List<Int> {
        val dx = (b.width() * 0.3f).toInt(); val dy = (b.height() * 0.3f).toInt()
        return when (direction) {
            "down" -> listOf(b.centerX(), b.centerY() + dy, b.centerX(), b.centerY() - dy)
            "up" -> listOf(b.centerX(), b.centerY() - dy, b.centerX(), b.centerY() + dy)
            "right" -> listOf(b.centerX() + dx, b.centerY(), b.centerX() - dx, b.centerY())
            else -> listOf(b.centerX() - dx, b.centerY(), b.centerX() + dx, b.centerY())
        }
    }

    companion object {
        const val PREFS = "hands"
        const val MAX_TEXT = 2000
        const val SETTLE_MS = 400L
        /** Long edge of a screenshot for the model: enough to read text, small enough to send quickly. */
        const val SHOT_SIDE = 1568
        const val TAKE_OVER = "Take over to type your password"
        const val TOOK_OVER = "The person took over the screen. Stop and say you will continue when they tell you to."
        val DIRECTIONS = setOf("up", "down", "left", "right")

        /** The owner can keep the hands off for the agent even while the service is on. */
        fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", true)
        /** The assistant gesture opens the floating genie (on) or the sheet (off), when the hands are on. */
        fun floats(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("float", true)
        fun always(context: Context): Set<String> = context.getSharedPreferences(PREFS, 0).getStringSet("always", emptySet()).orEmpty()
        fun forgetAlways(context: Context) = context.getSharedPreferences(PREFS, 0).edit().remove("always").apply()

        fun normal(name: String) = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

        /** Apps by name: the exact name first, then names that start with it, then names that contain it. */
        fun findApp(apps: List<Triple<String, String, Intent>>, name: String): List<Triple<String, String, Intent>> {
            val n = normal(name)
            if (n.isEmpty()) return emptyList()
            apps.filter { normal(it.first) == n }.takeIf { it.isNotEmpty() }?.let { return it }
            apps.filter { normal(it.first).startsWith(n) }.takeIf { it.isNotEmpty() }?.let { return it }
            return apps.filter { normal(it.first).contains(n) || it.second.lowercase(Locale.ROOT).contains(n.replace(" ", "")) }
        }
    }
}
