package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** Ægentica keys: typing that can replace Gboard, plain keys where it must, and the genie that writes only on a tap. */
@RunWith(AndroidJUnit4::class)
class KeyboardTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun byTag(root: View, tag: String) = views(root).first { it.tag == tag }
    /** Visible along the whole chain to the panel's root (the panel is not in a window here). */
    private fun shown(v: View) = generateSequence(v) { it.parent as? View }.all { it.visibility == View.VISIBLE }
    private fun text(root: View, value: String) = views(root).first { it is TextView && it.text.toString() == value && shown(it) }

    /** Counts every way an input method can read a field. */
    private class Recording(target: InputConnection) : InputConnectionWrapper(target, true) {
        var reads = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextBeforeCursor(n, flags) }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextAfterCursor(n, flags) }
        override fun getSelectedText(flags: Int): CharSequence? { reads++; return super.getSelectedText(flags) }
        override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? { reads++; return super.getExtractedText(request, flags) }
    }

    private class Host(val connection: InputConnection, val info: EditorInfo, val lang: String = "sv") : KeysPanel.Host {
        val opened = mutableListOf<Intent>()
        override fun connection() = connection
        override fun editor() = info
        override fun switchKeyboard() = Unit
        override fun pickKeyboard() = Unit
        override fun open(intent: Intent) { opened += intent }
        override fun language() = lang
    }

    /** Stands in for the phone agent: records what it was asked and lets the test stream its work. */
    private class FakeAgent : GenieAgent {
        var ask: GenieAsk? = null
        var update: ((GenieState) -> Unit)? = null
        val steered = mutableListOf<String>()
        val decisions = mutableListOf<Boolean>()
        override fun start(context: Context, ask: GenieAsk, update: (GenieState) -> Unit): GenieWork {
            this.ask = ask; this.update = update
            return object : GenieWork {
                override fun steer(text: String) { steered += text }
                override fun stop() = Unit
                override fun decide(approval: Approval, allow: Boolean, always: Boolean) { decisions += allow }
            }
        }
    }

    private fun field(text: String = "", type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0) =
        EditText(context).apply { inputType = type; imeOptions = options; setText(text); setSelection(this.text.length) }

    private fun panel(field: EditText, lang: String = "sv", wrap: (InputConnection) -> InputConnection = { it }): Pair<KeysPanel, Host> {
        val info = EditorInfo()
        val connection = wrap(field.onCreateInputConnection(info))
        info.packageName = "com.example.chat"; info.fieldId = 7
        val host = Host(connection, info, lang)
        return KeysPanel(context, host) to host
    }

    private fun KeysPanel.key(label: String) = keys.layout!!.keys.first { it.label == label }
    private fun KeysPanel.code(code: Int) = keys.layout!!.keys.first { it.code == code }
    private fun KeysPanel.typeWord(word: String) = word.forEach { c -> pressed(if (c == ' ') code(Keys.SPACE) else key(c.lowercaseChar().toString())) }

    @After fun tearDown() { KeyboardAgents.forTest = null; KeyboardLive.forget(); KeyboardInbox.clear() }

    @Test fun `it types like a phone keyboard - sentence capitals, caps lock, whole-emoji delete, full stop on double space`() {
        val field = field(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        val (panel, _) = panel(field)
        panel.typeWord("hej"); panel.pressed(panel.code(Keys.SPACE))
        assertEquals("Hej ", field.text.toString())
        panel.pressed(panel.code(Keys.SHIFT)); panel.pressed(panel.code(Keys.SHIFT))
        assertEquals(KeysView.Shift.LOCKED, panel.engine.shift)
        panel.typeWord("ok")
        panel.pressed(panel.code(Keys.SHIFT))
        panel.typeWord("då")
        assertEquals("Hej OKdå", field.text.toString())
        panel.type("👍🏽")
        panel.pressed(panel.code(Keys.BACKSPACE))
        assertEquals("the skin-toned thumb goes in one delete", "Hej OKdå", field.text.toString())
        panel.pressed(panel.code(Keys.SPACE)); panel.pressed(panel.code(Keys.SPACE))
        assertEquals("Hej OKdå. ", field.text.toString())
    }

    @Test fun `layouts follow the language and the field, and Enter does what the field asks`() {
        val (sv, _) = panel(field())
        assertTrue(listOf("å", "ä", "ö").all { l -> sv.keys.layout!!.keys.any { it.label == l } })
        val (en, _) = panel(field(), lang = "en")
        assertTrue(en.keys.layout!!.keys.none { it.label == "å" })
        val (phone, _) = panel(field(type = InputType.TYPE_CLASS_PHONE))
        assertEquals("numbers", phone.keys.layout!!.name)

        var action = -1
        val send = field(options = EditorInfo.IME_ACTION_SEND).apply { setOnEditorActionListener { _, a, _ -> action = a; true } }
        val (chat, _) = panel(send)
        assertEquals(Keys.Enter.SEND, chat.keys.enter)
        chat.pressed(chat.code(Keys.ENTER))
        assertEquals(EditorInfo.IME_ACTION_SEND, action)

        val notes = field("Rad ett", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        val (multi, _) = panel(notes)
        multi.pressed(multi.code(Keys.ENTER))
        assertEquals("a chat box that wants new lines gets one", "Rad ett\n", notes.text.toString())
    }

    @Test fun `password, card, code, number and incognito fields get plain keys and are never read`() {
        fun locked(type: Int = InputType.TYPE_CLASS_TEXT, options: Int = 0, hint: String? = null, name: String? = null) =
            KeyboardText.locked(context, EditorInfo().apply { inputType = type; imeOptions = options; hintText = hint; fieldName = name })
        assertTrue(locked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(locked(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(locked(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
        assertTrue("one-time codes and card numbers are number fields", locked(InputType.TYPE_CLASS_NUMBER))
        assertTrue("incognito", locked(options = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        assertTrue(locked(hint = "Card number"))
        assertTrue(locked(hint = "Ange engångskod"))
        assertTrue(locked(name = "otp_input"))
        assertFalse(locked(hint = "Write a message"))
        assertFalse(locked(hint = "Shopping list"))

        val agent = FakeAgent(); KeyboardAgents.forTest = agent
        var recording: Recording? = null
        val secret = field(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val (panel, _) = panel(secret) { Recording(it).also { r -> recording = r } }
        panel.typeWord("hunter"); panel.pressed(panel.code(Keys.SPACE)); panel.pressed(panel.code(Keys.SPACE)); panel.pressed(panel.code(Keys.BACKSPACE))
        panel.pressed(panel.code(Keys.GENIE))
        panel.workspace()
        idle()
        assertEquals("hunter ", secret.text.toString())
        assertEquals("nothing in the field is read", 0, recording!!.reads)
        assertNull("the genie is never asked", agent.ask)
        assertEquals(KeysPanel.Mode.KEYS, panel.mode)
        assertEquals(View.GONE, byTag(panel.view, "keys-clip").visibility)
    }

    @Test fun `the genie works on the field and nothing reaches it without a tap`() {
        val agent = FakeAgent(); KeyboardAgents.forTest = agent
        val field = field("hej hur mår du")
        val (panel, _) = panel(field)
        panel.pressed(panel.code(Keys.GENIE)); idle()
        assertEquals(KeysPanel.Mode.GENIE, panel.mode)
        text(panel.view, "Improve").performClick(); idle()
        assertEquals("hej hur mår du", agent.ask!!.field!!.text)
        assertEquals("com.example.chat", agent.ask!!.app)
        assertTrue(agent.ask!!.request.contains("clearly"))

        agent.update!!(GenieState(steps = listOf(AgentStep("s1", "Reading your text", "done")), answer = "Here it is:\n```field\nHej, hur mår", busy = true, who = "Ægentica")); idle()
        assertEquals("streaming changes nothing in the field", "hej hur mår du", field.text.toString())
        agent.update!!(GenieState(answer = "Here it is:\n```field\nHej, hur mår du?\n```", who = "Ægentica")); idle()
        assertEquals("hej hur mår du", field.text.toString())
        byTag(panel.view, "genie-replace").performClick(); idle()
        assertEquals("Hej, hur mår du?", field.text.toString())
        assertEquals(KeysPanel.Mode.KEYS, panel.mode)
        byTag(panel.view, "keys-ideas").performClick()
        assertEquals("Undo puts the owner's words back", "hej hur mår du", field.text.toString())
    }

    @Test fun `typed requests steer the work, actions wait for Allow, and Insert goes at the cursor`() {
        val agent = FakeAgent(); KeyboardAgents.forTest = agent
        val field = field("")
        val (panel, _) = panel(field)
        panel.openGenie()
        byTag(panel.view, "genie-prompt").performClick()
        assertEquals(KeysPanel.Mode.PROMPT, panel.mode)
        panel.typeWord("svara ja"); panel.pressed(panel.code(Keys.ENTER)); idle()
        assertEquals("the keys typed the request, not into the field", "", field.text.toString())
        assertEquals("Svara ja", agent.ask!!.request)
        assertNull(agent.ask!!.field)

        val spec = ToolSpec("phone_tap", "Tap", Risk.ACT, "phone", "Tap {ref}", JSONObject("""{"properties":{"ref":{},"why":{}},"required":["ref","why"]}"""))
        val approval = Approval(AgentToolCall("c1", "phone_tap"), Prepared.Ready(spec, JSONObject(), Risk.ACT, "Tap Send", "Sends your reply", "com.example.chat", "Chat"))
        agent.update!!(GenieState(approval = approval, busy = true, who = "Ægentica")); idle()
        assertTrue(shown(byTag(panel.view, "genie-approval")))
        byTag(panel.view, "genie-allow").performClick()
        assertEquals(listOf(true), agent.decisions)

        byTag(panel.view, "genie-prompt").performClick()
        panel.typeWord("kortare"); panel.pressed(panel.code(Keys.ENTER)); idle()
        assertEquals("words sent while it works steer it", listOf("Kortare"), agent.steered)

        agent.update!!(GenieState(answer = "Ja, det låter bra!", who = "Ægentica")); idle()
        assertTrue("an empty field has nothing to replace", views(panel.view).none { it.tag == "genie-replace" && shown(it) })
        byTag(panel.view, "genie-insert").performClick()
        assertEquals("Ja, det låter bra!", field.text.toString())
    }

    @Test fun `the full genie sheet's Replace lands only in the field it came from, and only if unchanged`() {
        val field = field("ses imorgon")
        val (panel, host) = panel(field)
        panel.workspace()
        val bridge = host.opened.single()
        assertEquals(GenieKeyboardBridge::class.java.name, bridge.component!!.className)
        val activity = Robolectric.buildActivity(GenieKeyboardBridge::class.java, bridge).create().get()
        val sheet = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(AssistantActivity::class.java.name, sheet.component!!.className)
        assertEquals("ses imorgon", sheet.getStringExtra(Intent.EXTRA_PROCESS_TEXT))
        assertFalse(sheet.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true))
        assertEquals("ses imorgon", field.text.toString())

        val ticket = bridge.getStringExtra(GenieKeyboardBridge.TICKET)!!
        KeyboardInbox.answer(ticket, "Vi ses i morgon!")
        val other = EditorInfo().apply { packageName = "com.example.mail"; fieldId = 3; inputType = InputType.TYPE_CLASS_TEXT }
        panel.start(other)
        assertEquals("another field never gets it by itself", "ses imorgon", field.text.toString())
        assertEquals("Insert Ægentica's text", (byTag(panel.view, "keys-ideas") as TextView).text.toString())
        panel.start(host.info)
        assertEquals("Vi ses i morgon!", field.text.toString())

        panel.workspace()
        val second = host.opened.last().getStringExtra(GenieKeyboardBridge.TICKET)!!
        field.setText("ses imorgon kl 3")
        KeyboardInbox.answer(second, "Vi ses i morgon!")
        panel.start(host.info)
        assertEquals("text that changed meanwhile is never overwritten", "ses imorgon kl 3", field.text.toString())
    }
}
