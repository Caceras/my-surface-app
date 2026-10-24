package com.caceras.surfacelab

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Ægentica hands: what it reads, how it names things, what it refuses, how it acts, and Stop and take-over. */
@RunWith(AndroidJUnit4::class)
class HandsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val playbook get() = Playbook.get(context)
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** A screen element; [key] stays the same across snapshots as Android's node identity does. */
    class Node(
        override val key: Any, override val className: String = "android.widget.TextView", override val text: String? = null,
        override val description: String? = null, override val hint: String? = null, override val viewId: String? = null,
        override var bounds: Rect = Rect(0, 0, 100, 50), override val clickable: Boolean = false, override val longClickable: Boolean = false,
        override val editable: Boolean = false, override val scrollable: Boolean = false, override val checkable: Boolean = false,
        override val checked: Boolean = false, override val enabled: Boolean = true, override val selected: Boolean = false,
        override val password: Boolean = false, override val inputType: Int = 0, override var visible: Boolean = true,
        override val showingHint: Boolean = false, override val packageName: String = "com.android.settings",
        children: List<Node> = emptyList(),
    ) : UiNode {
        override var parent: UiNode? = null
        override val children: List<UiNode> = children.onEach { it.parent = this }
        var gone = false
        val log = mutableListOf<String>()
        var typed: String? = null
        override fun refresh() = !gone
        override fun click(): Boolean { log += "click"; return clickable }
        override fun longClick(): Boolean { log += "long"; return longClickable }
        override fun focus(): Boolean { log += "focus"; return true }
        override fun setText(text: String): Boolean { typed = text; log += "text:$text"; return editable }
        override fun scroll(direction: String): Boolean { log += "scroll:$direction"; return scrollable }
        override fun imeEnter(): Boolean { log += "enter"; return editable }
    }

    /** A phone screen whose tree a test sets, and that records gestures and launches. */
    class FakeScreen(var tree: Node?) : Screen {
        override val width = 1080
        override val height = 2400
        val gestures = mutableListOf<String>()
        val launched = mutableListOf<Intent>()
        var shot: Bitmap? = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        var afterGesture: (() -> Unit)? = null
        override fun root(): UiNode? = tree
        override fun tap(x: Int, y: Int, done: (Boolean) -> Unit) { gestures += "tap $x,$y"; afterGesture?.invoke(); done(true) }
        override fun longPress(x: Int, y: Int, done: (Boolean) -> Unit) { gestures += "hold $x,$y"; done(true) }
        override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, done: (Boolean) -> Unit) { gestures += "swipe $x1,$y1>$x2,$y2"; done(true) }
        override fun global(action: String): Boolean { gestures += action; return true }
        override fun screenshot(done: (Bitmap?, String?) -> Unit) = done(shot, if (shot == null) "This screen is protected." else null)
        override fun launch(intent: Intent): Boolean { launched += intent; return true }
        override fun apps() = listOf(
            Triple("Settings", "com.android.settings", Intent().setClassName("com.android.settings", "Main")),
            Triple("Gmail", "com.google.android.gm", Intent().setClassName("com.google.android.gm", "Main")),
            Triple("Google Maps", "com.google.android.apps.maps", Intent().setClassName("com.google.android.apps.maps", "Main")),
            Triple("Google Photos", "com.google.android.apps.photos", Intent().setClassName("com.google.android.apps.photos", "Main")),
            Triple("Swish", "se.bankgirot.swish", Intent().setClassName("se.bankgirot.swish", "Main")))
        override fun appName(pkg: String) = when (pkg) { "com.android.settings" -> "Settings"; "com.google.android.gm" -> "Gmail"; "se.bankgirot.swish" -> "Swish"; else -> pkg }
    }

    /** Records what the rabbit was asked to do. */
    class FakeOverlay : HandsOverlay {
        val calls = mutableListOf<String>()
        override fun summon() { calls += "summon" }
        override fun show() { calls += "show" }
        override fun flyTo(bounds: Rect, label: String, then: () -> Unit) { calls += "fly ${bounds.centerX()},${bounds.centerY()}"; then() }
        override fun tapped(x: Int, y: Int) { calls += "tap $x,$y" }
        override fun typing(bounds: Rect) { calls += "typing" }
        override fun scrolled(bounds: Rect, direction: String) { calls += "bob $direction" }
        override fun highlight(bounds: Rect) { calls += "ring ${bounds.centerX()},${bounds.centerY()}" }
        override fun poof() { calls += "poof" }
        override fun status(text: String) { calls += "status $text" }
        override fun handOver(text: String) { calls += "handover $text" }
        override fun paused() { calls += "paused" }
        override fun stopped() { calls += "stopped" }
        override fun done(answer: String?) { calls += "done" }
        override fun hideForCapture(hidden: Boolean) { calls += "hide $hidden" }
        override fun close() { calls += "close" }
    }

    private class Listener : Hands.Listener {
        val log = mutableListOf<String>()
        override fun stop() { log += "stop" }
        override fun steer(text: String) { log += "steer $text" }
        override fun handedBack() { log += "back" }
    }

    // A Settings screen: a search field, a password field, a Wi-Fi row with its switch, a list, a send button.
    private val search = Node("search", "android.widget.EditText", hint = "Search settings", editable = true, clickable = true, bounds = Rect(40, 200, 1040, 300))
    private val password = Node("pw", "android.widget.EditText", hint = "Wi-Fi password", text = "hunter2", editable = true, password = true, bounds = Rect(40, 320, 1040, 420))
    private val wifiSwitch = Node("switch", "android.widget.Switch", checkable = true, checked = false, clickable = true, description = "Wi-Fi", bounds = Rect(900, 460, 1040, 540))
    private val wifiRow = Node("row", "android.widget.LinearLayout", clickable = true, bounds = Rect(0, 440, 880, 560),
        children = listOf(Node("t1", text = "Wi-Fi", bounds = Rect(40, 460, 400, 500)), Node("t2", text = "Off", bounds = Rect(40, 500, 400, 540))))
    private val send = Node("send", "android.widget.ImageButton", description = "Send", viewId = "com.android.settings:id/btnSend", clickable = true, bounds = Rect(900, 2200, 1040, 2300))
    private val list = Node("list", "androidx.recyclerview.widget.RecyclerView", scrollable = true, bounds = Rect(0, 440, 1080, 2100), children = listOf(wifiRow, wifiSwitch))
    private val hidden = Node("hidden", text = "Offscreen", visible = false)
    private fun settings() = Node("root", "android.widget.FrameLayout", bounds = Rect(0, 0, 1080, 2400), children = listOf(
        Node("title", text = "Network & internet", bounds = Rect(40, 80, 800, 160)), search, password, list, send, hidden))

    private lateinit var screen: FakeScreen
    private lateinit var overlay: FakeOverlay
    private lateinit var listener: Listener
    private lateinit var tools: HandsTools

    @Before fun setUp() {
        Hands.resetForTest()
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().clear().commit()
        screen = FakeScreen(settings()); overlay = FakeOverlay(); listener = Listener()
        Hands.attach(screen, overlay); Hands.listener = listener
        tools = HandsTools(context)
    }
    @After fun tearDown() { Hands.resetForTest(); idle() }

    private fun spec(name: String) = playbook.tool(name)!!
    private fun prepare(name: String, args: String) = tools.prepare(spec(name), JSONObject(args))
    private fun ready(name: String, args: String) = prepare(name, args) as? Prepared.Ready ?: fail("Refused: " + ((prepare(name, args) as Prepared.Refused).outcome.result)) as Nothing
    private fun refused(name: String, args: String) = (prepare(name, args) as? Prepared.Refused ?: fail("Not refused: $name $args") as Nothing).outcome.result
    private fun run(call: Prepared.Ready): ToolOutcome {
        val result = AtomicReference<ToolOutcome>()
        val latch = CountDownLatch(1)
        // Steps run on the agent's thread, never the main one.
        Thread { tools.run(call) { result.set(it); latch.countDown() } }.start()
        val until = System.currentTimeMillis() + 5000
        while (!latch.await(5, TimeUnit.MILLISECONDS)) { idle(); if (System.currentTimeMillis() > until) fail("The step did not finish") }
        idle()
        return result.get()
    }
    private fun snapshot() = run(ready("phone_snapshot", "{}")).result

    @Test fun `a snapshot lists what is on screen with stable refs, roles, bounds and masked secrets`() {
        val text = snapshot()
        val lines = text.lines()
        assertEquals("Settings · com.android.settings · screen 1080×2400", lines[0])
        assertEquals("e1 text “Network & internet” @[40,80,800,160]", lines[1])
        assertEquals("e2 field “Search settings” value=“” [tap, type] @[40,200,1040,300]", lines[2])
        assertEquals("e3 field “Wi-Fi password” value=“••••” [type, secret] @[40,320,1040,420]", lines[3])
        assertTrue(lines[4].startsWith("e4 list “” [scroll]"))
        assertEquals("a tappable row is named by the words inside it, which are not listed again", "e5 button “Wi-Fi · Off” [tap] @[0,440,880,560]", lines[5])
        assertEquals("e6 switch “Wi-Fi” [off, tap] @[900,460,1040,540]", lines[6])
        assertEquals("e7 button “Send” [tap] @[900,2200,1040,2300]", lines[7])
        assertFalse("hidden elements are left out", text.contains("Offscreen"))
        assertFalse("the password never reaches the model", text.contains("hunter2"))
        // The same element keeps its ref when the screen is read again, and "@e6" works too.
        screen.tree = settings()
        assertTrue(snapshot().contains("e6 switch “Wi-Fi”"))
        assertTrue(prepare("phone_tap", """{"ref":"@e6","why":"Turn on Wi-Fi"}""") is Prepared.Ready)
        // Find matches names and values, never a secret's value.
        assertEquals("e6 switch “Wi-Fi” [off, tap] @[900,460,1040,540]", run(ready("phone_find", """{"query":"wi-fi"}""")).result.lines().last())
        assertEquals("Nothing on the screen contains “hunter2”.", run(ready("phone_find", """{"query":"hunter2"}""")).result)
        // A new conversation starts again at e1.
        Hands.newQuestion(fresh = true)
        assertTrue(snapshot().lines()[1].startsWith("e1 "))
    }

    @Test fun `secrets are found by type, flags and words in Swedish and English`() {
        val policy = HandsPolicy(playbook)
        fun field(hint: String? = null, id: String? = null, type: Int = 0, password: Boolean = false) =
            Node(hint.orEmpty() + id, "android.widget.EditText", hint = hint, viewId = id, inputType = type, password = password, editable = true)
        assertTrue(policy.secret(field(password = true)))
        assertTrue(policy.secret(field(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(policy.secret(field(type = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)))
        assertTrue(policy.secret(field("Lösenord"))); assertTrue(policy.secret(field("Card number"))); assertTrue(policy.secret(field("Kortnummer")))
        assertTrue(policy.secret(field("Engångskod"))); assertTrue(policy.secret(field("Verification code"))); assertTrue(policy.secret(field("CVC")))
        assertTrue(policy.secret(field(id = "com.bank:id/otp_input"))); assertTrue(policy.secret(field("PIN")))
        assertFalse(policy.secret(field("Postal code"))); assertFalse(policy.secret(field("Search"))); assertFalse(policy.secret(field("Message")))
        // Steps that always ask, from what the element says.
        assertEquals(HandsPolicy.REASONS["send"], policy.sensitive(send, "com.google.android.gm"))
        assertEquals(HandsPolicy.REASONS["send"], policy.sensitive(Node("s", viewId = "app:id/send_button", clickable = true), "x"))
        assertEquals(HandsPolicy.REASONS["delete"], policy.sensitive(Node("d", text = "Ta bort", clickable = true), "x"))
        assertEquals(HandsPolicy.REASONS["pay"], policy.sensitive(Node("p", text = "Köp nu", clickable = true), "x"))
        assertEquals(HandsPolicy.REASONS["call"], policy.sensitive(Node("c", description = "Call Anna", clickable = true), "x"))
        assertEquals(HandsPolicy.REASONS["account"], policy.sensitive(Node("a", text = "Sign out", clickable = true), "x"))
        assertNotNull(policy.sensitive(null, "se.bankgirot.swish"))
        assertNull(policy.sensitive(wifiSwitch, "com.android.settings")); assertNull(policy.sensitive(Node("x", text = "Sender: Bob"), "x"))
    }

    @Test fun `every call is validated before anything is asked or run`() {
        snapshot()
        assertTrue(refused("phone_tap", """{"ref":"e6"}""").startsWith("Give `why`"))
        assertTrue(refused("phone_tap", """{"ref":"e6","why":"ok"}""").startsWith("Give `why`"))
        assertTrue(refused("phone_tap", """{"ref":"e6","why":"${"x".repeat(161)}"}""").startsWith("Give `why`"))
        assertTrue(refused("phone_tap", """{"ref":"button","why":"Tap it now"}""").contains("is not a ref"))
        assertTrue(refused("phone_tap", """{"ref":"e99","why":"Tap it now"}""").startsWith("No element e99"))
        wifiSwitch.gone = true
        assertTrue(refused("phone_tap", """{"ref":"e6","why":"Turn on Wi-Fi"}""").contains("any more"))
        wifiSwitch.gone = false
        assertTrue(refused("phone_tap", """{"x":2000,"y":10,"why":"Tap off screen"}""").contains("outside the screen"))
        assertTrue(refused("phone_tap", """{"why":"Tap nothing"}""").startsWith("Give a ref"))
        assertTrue(refused("phone_scroll", """{"direction":"sideways"}""").startsWith("direction must be"))
        assertTrue(refused("phone_swipe", """{"x1":0,"y1":0,"x2":5000,"y2":10,"why":"Swipe far away"}""").contains("stay on the screen"))
        assertTrue(refused("phone_open_url", """{"url":"javascript:alert(1)","why":"Open a page"}""").startsWith("Only https"))
        assertTrue(refused("phone_open_url", """{"url":"intent://x#Intent;end","why":"Open a page"}""").startsWith("Only https"))
        assertTrue(refused("phone_type", """{"ref":"e6","text":"hej","why":"Type a name"}""").contains("not a text field"))
        assertTrue(refused("phone_type", """{"ref":"e2","text":"${"x".repeat(2001)}","why":"Type a lot"}""").contains("up to 2000"))
        // Ægentica's own controls are never tapped, by ref or by point.
        val own = Node("own", "android.widget.Button", text = "Allow", clickable = true, packageName = context.packageName, bounds = Rect(0, 2300, 500, 2400))
        screen.tree = Node("root2", bounds = Rect(0, 0, 1080, 2400), children = listOf(own))
        snapshot()
        assertTrue(refused("phone_tap", """{"x":100,"y":2350,"why":"Tap Allow myself"}""").contains("Ægentica's own control"))
    }

    @Test fun `passwords, card and code fields are never typed into, and the person is asked to take over`() {
        snapshot()
        val why = refused("phone_type", """{"ref":"e3","text":"hunter2","why":"Fill in the password"}""")
        assertTrue(why.contains("password, card or one-time-code field")); assertTrue(why.contains("Take over to type your password"))
        idle()
        assertTrue(overlay.calls.contains("handover ${HandsTools.TAKE_OVER}"))
        assertNull(password.typed)
    }

    @Test fun `tiers come from the playbook and what the screen says, never from the model`() {
        snapshot()
        assertEquals(Risk.READ, ready("phone_scroll", """{"direction":"down"}""").risk)
        val tap = ready("phone_tap", """{"ref":"e6","why":"Turn on Wi-Fi"}""")
        assertEquals(Risk.ACT, tap.risk); assertEquals("Turn on Wi-Fi", tap.label); assertEquals("com.android.settings", tap.app); assertEquals("Settings", tap.appName)
        val sending = ready("phone_tap", """{"ref":"e7","why":"Just a harmless tap"}""")
        assertEquals("a Send button always asks, whatever the why says", Risk.ALWAYS, sending.risk); assertEquals(HandsPolicy.REASONS["send"], sending.reason)
        val submit = ready("phone_type", """{"ref":"e2","text":"wifi","submit":true,"why":"Search for Wi-Fi"}""")
        assertEquals(Risk.ALWAYS, submit.risk); assertTrue(submit.reason!!.contains("Enter or Send"))
        assertEquals(Risk.ACT, ready("phone_open_url", """{"url":"https://example.se","why":"Open the page"}""").risk)
        val call = ready("phone_open_url", """{"url":"tel:+46701234567","why":"Call the shop"}""")
        assertEquals(Risk.ALWAYS, call.risk); assertTrue(call.reason!!.contains("call"))
        assertEquals(Risk.ALWAYS, ready("phone_open_url", """{"url":"sms:+46701234567","why":"Text the shop"}""").risk)
        // Every acting step in a banking or payment app always asks.
        screen.tree = Node("swish", bounds = Rect(0, 0, 1080, 2400), packageName = "se.bankgirot.swish",
            children = listOf(Node("b", "android.widget.Button", text = "Fortsätt", clickable = true, packageName = "se.bankgirot.swish", bounds = Rect(0, 0, 500, 100))))
        snapshot()
        assertEquals(Risk.ALWAYS, ready("phone_tap", """{"ref":"e1","why":"Continue"}""").risk)
        // Opening apps by name: exact first, then a single match, else a list.
        val maps = ready("phone_open_app", """{"name":"google maps","why":"Find the route"}""")
        assertEquals("com.google.android.apps.maps", maps.app); assertEquals("Google Maps", maps.appName)
        assertTrue(refused("phone_open_app", """{"name":"Google","why":"Open Google"}""").startsWith("Several apps match"))
        assertTrue(refused("phone_open_app", """{"name":"Spotify","why":"Play music"}""").startsWith("No app called “Spotify”"))
        // "Always for this app" is per app and only for ordinary acting steps.
        assertFalse(tools.remembered(tap)); tools.remember(tap); assertTrue(tools.remembered(tap))
        assertFalse(tools.remembered(sending)); tools.remember(sending)
        assertEquals(setOf("com.android.settings"), HandsTools.always(context))
    }

    @Test fun `steps run with the rabbit flying there first, and say where the agent landed`() {
        snapshot()
        val tap = run(ready("phone_tap", """{"ref":"e6","why":"Turn on Wi-Fi"}"""))
        assertTrue(tap.ok); assertEquals("Tapped switch “Wi-Fi”.", tap.result)
        assertEquals(listOf("click"), wifiSwitch.log)
        assertEquals(listOf("show", "fly 970,500", "tap 970,500"), overlay.calls.take(3))
        // A point gets a real touch; a screen that changes is reported.
        screen.afterGesture = { screen.tree = Node("wifi", bounds = Rect(0, 0, 1080, 2400), children = listOf(Node("w", text = "Wi-Fi networks", bounds = Rect(0, 0, 500, 80)))) }
        val point = run(ready("phone_tap", """{"x":500,"y":250,"why":"Tap the search box"}"""))
        assertEquals(listOf("tap 500,250"), screen.gestures)
        assertTrue(point.result, point.result.endsWith("The screen changed. Take a new phone_snapshot before the next step."))
        assertTrue(overlay.calls.contains("poof"))
        screen.afterGesture = null; screen.tree = settings(); Hands.newQuestion(fresh = true); snapshot()
        // Typing appends unless clear, then presses Enter when asked.
        assertTrue(run(ready("phone_type", """{"ref":"e2","text":"wifi","clear":true,"submit":true,"why":"Search for Wi-Fi"}""")).result.startsWith("Typed 4 characters into field “Search settings” and pressed Enter."))
        assertEquals(listOf("focus", "text:wifi", "enter"), search.log)
        assertTrue(overlay.calls.contains("typing"))
        // Scrolling uses the list's own scroll; Back, Home and Recents are Android's own actions.
        assertEquals("Scrolled down.", run(ready("phone_scroll", """{"direction":"down","ref":"e4"}""")).result.substringBefore(" The"))
        assertEquals(listOf("scroll:down"), list.log)
        assertTrue(run(ready("phone_back", """{"why":"Close the search"}""")).ok)
        assertTrue(run(ready("phone_home", """{"why":"Go home first"}""")).ok)
        assertEquals(listOf("tap 500,250", "back", "home"), screen.gestures)
        val opened = run(ready("phone_open_app", """{"name":"Gmail","why":"Read the mail"}"""))
        assertEquals("Opened Gmail.", opened.result.substringBefore(" The"))
        assertEquals("com.google.android.gm", screen.launched.single().component!!.packageName)
        // The screenshot hides Ægentica's own windows and goes to the model as a picture.
        val shot = run(ready("phone_screenshot", "{}"))
        assertTrue(shot.ok); assertNotNull(shot.image)
        assertTrue(overlay.calls.containsAll(listOf("hide true", "hide false")))
        screen.shot = null
        assertEquals("This screen is protected.", run(ready("phone_screenshot", "{}")).result)
        // Waiting returns satisfied: false, not an error, when the text never comes.
        assertTrue(run(ready("phone_wait_for", """{"text":"Wi-Fi"}""")).result.startsWith("satisfied: true."))
        assertTrue(run(ready("phone_wait_for", """{"text":"Bluetooth","timeoutMs":300}""")).result.startsWith("satisfied: false"))
        assertTrue(run(ready("phone_wait_for", """{"text":"Bluetooth","gone":true}""")).result.startsWith("satisfied: true."))
    }

    @Test fun `Stop ends everything at once and refuses every step until the next question`() {
        snapshot()
        val call = ready("phone_tap", """{"ref":"e6","why":"Turn on Wi-Fi"}""")
        Hands.begin()
        Hands.stop()
        idle()
        assertEquals(listOf("stop"), listener.log)
        assertTrue(overlay.calls.contains("stopped"))
        assertFalse(Hands.active)
        assertEquals(AgentSession.STOPPED, refused("phone_snapshot", "{}"))
        assertEquals(AgentSession.STOPPED, run(call).result)
        assertTrue(wifiSwitch.log.isEmpty())
        // Pressing Stop again does nothing more; the next question frees the hands.
        Hands.stop(); assertEquals(listOf("stop"), listener.log)
        Hands.newQuestion(fresh = false)
        assertTrue(prepare("phone_snapshot", "{}") is Prepared.Ready)
        // Without the service, nothing is offered and nothing runs.
        Hands.detach(); idle()
        assertTrue(tools.offered().isEmpty())
        assertTrue(refused("phone_snapshot", "{}").contains("not connected"))
    }

    @Test fun `touching the screen while it works pauses the hands until the person hands it back`() {
        snapshot()
        Hands.end(null)
        Hands.userTouched()
        assertFalse("a touch when nothing runs is just a touch", Hands.paused)
        Hands.begin()
        Hands.injecting(10_000)
        Hands.userTouched()
        assertFalse("the hands' own gestures are not the person", Hands.paused)
        Hands.resetForTest(); Hands.attach(screen, overlay); Hands.listener = listener
        Hands.begin()
        Hands.userTouched(); idle()
        assertTrue(Hands.paused); assertTrue(overlay.calls.contains("paused"))
        assertEquals(HandsTools.TOOK_OVER, refused("phone_tap", """{"ref":"e6","why":"Turn on Wi-Fi"}"""))
        Hands.handBack()
        assertFalse(Hands.paused); assertEquals(listOf("back"), listener.log)
        assertTrue(prepare("phone_snapshot", "{}") is Prepared.Ready)
    }

    @Test fun `the agent gets the hands only when the service is on and the owner allows it`() {
        assertEquals(playbook.tools.filter { it.target == "phone" }.map { it.name }, tools.offered().map { it.name })
        assertEquals(tools.offered().map { it.name }, PhoneTools.forAgent(context).offered().map { it.name })
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().putBoolean("enabled", false).commit()
        assertTrue(tools.offered().isEmpty()); assertTrue(PhoneTools.forAgent(context).offered().isEmpty())
        context.getSharedPreferences(HandsTools.PREFS, 0).edit().putBoolean("enabled", true).putBoolean("hands_free", true).commit()
        assertTrue(tools.handsFree(prepare("phone_back", """{"why":"Close the menu"}""") as Prepared.Ready))
        assertTrue(HandsTools.floats(context))
    }
}
