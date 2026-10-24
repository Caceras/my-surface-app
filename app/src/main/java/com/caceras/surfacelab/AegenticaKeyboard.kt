package com.caceras.surfacelab

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.BreakIterator
import kotlin.math.abs

/**
 * Ægentica keys: a full keyboard that can be the phone's everyday keyboard, and the genie's home
 * inside every app. It types normally (Swedish or US English, numbers, symbols, emoji, long-press
 * accents, dictation); the Æ key, or the rabbit on the bar, turns the keys into the live genie panel
 * with what you are typing as context, and dragging the panel up opens the full genie sheet over
 * the app. Model text reaches the field only when the owner taps Insert or Replace.
 *
 * Password, card, one-time-code, number and incognito fields get plain keys only: no genie, no
 * reading of the field, no clipboard offers.
 */
class AegenticaKeyboard : InputMethodService() {
    private var panel: KeysPanel? = null

    private val host = object : KeysPanel.Host {
        override fun connection() = currentInputConnection
        override fun editor() = currentInputEditorInfo
        override fun switchKeyboard() {
            if (!switchToPreviousInputMethod() && !switchToNextInputMethod(false)) pickKeyboard()
        }
        override fun pickKeyboard() = getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        override fun open(intent: Intent) { runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        override fun hide() = requestHideSelf(0)
        override fun language(): String = getSystemService(InputMethodManager::class.java).currentInputMethodSubtype
            ?.let { s -> s.languageTag.ifBlank { @Suppress("DEPRECATION") s.locale } }
            ?.substringBefore('_')?.substringBefore('-').orEmpty().ifBlank { "sv" }
    }

    override fun onCreateInputView(): View = KeysPanel(this, host).also { panel = it }.view

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        panel?.start(info)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        panel?.selection(newSelStart, newSelEnd)
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: android.view.inputmethod.InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        currentInputEditorInfo?.let { panel?.start(it) }
    }

    /** The field closed or the keyboard was hidden: stop dictation; a genie request keeps going. */
    override fun onFinishInputView(finishingInput: Boolean) {
        panel?.finish()
        super.onFinishInputView(finishingInput)
    }

    /** Landscape keeps the app visible, like portrait. */
    override fun onEvaluateFullscreenMode() = false

    override fun onDestroy() { panel?.finish(); panel = null; super.onDestroy() }
}

/** The keyboard's views and behaviour, apart from the service so tests can drive it with any text field. */
class KeysPanel(private val context: Context, private val host: Host) {
    interface Host {
        fun connection(): InputConnection?
        fun editor(): EditorInfo?
        /** Back to the previous keyboard (Gboard). */
        fun switchKeyboard()
        fun pickKeyboard()
        fun open(intent: Intent)
        /** The chosen subtype's language: "sv" or "en". */
        fun language(): String
        /** Hide the keyboard (the genie floats instead). */
        fun hide() {}
    }

    enum class Mode { KEYS, GENIE, PROMPT }

    private fun ink(id: Int) = context.getColor(id)
    private fun dp(v: Int) = context.dp(v)
    private val press get() = ColorStateList.valueOf(ink(R.color.keys_pressed))
    private val main = Handler(Looper.getMainLooper())

    val engine = KeysEngine(host::connection, host::editor)
    val keys: KeysView
    val genie: GeniePanel
    private val keyOverlay = KeysOverlay(context)
    private val rabbit: ImageView
    private val ideas: TextView
    /** The quiet ideas line: completions and next words in the owner's own voice. */
    private val suggestions: LinearLayout
    private val words by lazy { WordSuggest(context) }
    private var typedWord = ""
    private var typedPrevious: String? = null
    private var learning = false
    private val float: ImageButton
    private val clip: ImageButton
    private val mic: ImageButton
    private val barKeys: LinearLayout
    private val barGenie: LinearLayout
    private val barPrompt: LinearLayout
    private val status: TextView
    private val promptText: TextView
    val view: FrameLayout

    var mode = Mode.KEYS; private set
    private var locked = false
    /** The owner turned the genie off in this app; the keys and the ideas line still work. */
    private var genieOff = false
    private var page = "letters"
    private val prompt = StringBuilder()
    private var ears: Ears? = null
    private var undo: (() -> Unit)? = null
    private var busyFrame = 0

    private fun iconButton(icon: Int, description: String, tag: String, run: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon); imageTintList = ColorStateList.valueOf(ink(R.color.keys_ink)); scaleType = ImageView.ScaleType.CENTER
        contentDescription = description; tooltipText = description; this.tag = tag; background = RippleDrawable(press, null, null)
        setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); run() }
    }

    init {
        keys = KeysView(context, object : KeysView.Listener {
            override fun key(key: Key) = pressed(key)
            override fun text(text: String) = type(text)
            override fun cursor(steps: Int) { if (mode != Mode.PROMPT) engine.cursor(steps) }
            override fun spaceHeld(): Boolean = holdToSpeak()
            override fun spaceReleased() { ears?.stop() }
            override fun longPress(key: Key): Boolean = when (key.code) {
                Keys.SPACE -> { host.pickKeyboard(); true }
                Keys.GENIE -> { workspace(); true }
                else -> false
            }
        }).apply { tag = "keys"; previews = keyOverlay; padDp(3, 0, 3, 6) }
        genie = GeniePanel(context, object : GeniePanel.Host {
            override fun ask(label: String, request: String) = this@KeysPanel.ask(request, label)
            override fun prompt() = setMode(Mode.PROMPT)
            override fun insert(text: String) = this@KeysPanel.insert(text)
            override fun replace(text: String) = this@KeysPanel.replace(text)
            override fun canReplace() = KeyboardLive.sameField(host.editor()) && KeyboardLive.ask?.field != null
            override fun decide(approval: Approval, allow: Boolean, always: Boolean) { KeyboardLive.work?.decide(approval, allow, always) }
            override fun listen() = listenToGenie()
            override fun open(intent: Intent) = host.open(intent)
            override fun offHere() = setGenieOff(true)
            override fun useScreen() {
                // The keyboard steps aside first, so the assistant sees the app and not the keys.
                setMode(Mode.KEYS, animate = false); host.hide()
                main.postDelayed({ AssistantService.onScreen() }, 350)
            }
        })
        rabbit = ImageView(context).apply {
            tag = "keys-rabbit"; setImageResource(R.drawable.mascot_sitting); scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Ægentica"; padDp(8, 6, 4, 6); isClickable = true; isFocusable = true
            background = RippleDrawable(press, null, null)
            setOnClickListener { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); if (mode == Mode.KEYS) openGenie() else setMode(Mode.KEYS) }
        }
        ideas = context.label("", 14f).apply {
            tag = "keys-ideas"; setTextColor(ink(R.color.keys_ink_dim)); gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        suggestions = LinearLayout(context).apply { tag = "keys-suggestions"; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        repeat(3) { i ->
            suggestions.addView(context.label("", 15f).apply {
                tag = "keys-suggestion"; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setTextColor(ink(R.color.keys_ink)); if (i == 0) medium(); buttonSemantics()
                background = RippleDrawable(press, null, null); setPadding(dp(6), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, -1, 1f))
        }
        clip = iconButton(R.drawable.ic_clipboard, "Clipboard", "keys-clip") { offerClip() }
        mic = iconButton(R.drawable.ic_mic_line, "Dictate", "keys-mic") { dictate() }
        val globe = iconButton(R.drawable.ic_globe, "Switch keyboard", "keys-globe") { host.switchKeyboard() }.apply {
            setOnLongClickListener { host.pickKeyboard(); true }
        }
        barKeys = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(context).apply { addView(ideas, FrameLayout.LayoutParams(-1, -1)); addView(suggestions, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(0, -1, 1f))
            listOf(clip, mic, globe).forEach { addView(it, LinearLayout.LayoutParams(dp(44), dp(44))) }
        }
        status = context.label("", 14f).apply {
            tag = "keys-status"; setTextColor(ink(R.color.keys_ink)); medium(); gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        barGenie = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(status, LinearLayout.LayoutParams(0, -1, 1f))
            addView(iconButton(R.drawable.ic_float, "Float the genie over the app", "keys-float") { floatGenie() }.also { float = it }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_expand, "Open the full genie", "keys-expand") { workspace() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(iconButton(R.drawable.ic_keys, "Back to the keys", "keys-close") { setMode(Mode.KEYS) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        promptText = context.label("", 15f).apply {
            tag = "keys-prompt"; setTextColor(ink(R.color.keys_ink)); gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.START
            background = GradientDrawable().apply { setColor(ink(R.color.keys_key)); cornerRadius = dp(20).toFloat(); setStroke(dp(1), ink(R.color.keys_edge)) }
            setPadding(dp(14), 0, dp(14), 0)
        }
        barPrompt = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(0, dp(4), dp(4), dp(4))
            addView(promptText, LinearLayout.LayoutParams(0, -1, 1f))
            addView(iconButton(R.drawable.ic_arrow_up, "Send to Ægentica", "keys-send") { sendPrompt() }.apply {
                imageTintList = ColorStateList.valueOf(ink(R.color.keys_on_action))
                background = RippleDrawable(press, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(R.color.keys_action)) }, null)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6) })
        }
        val bar = LinearLayout(context).apply {
            tag = "keys-bar"; gravity = Gravity.CENTER_VERTICAL
            addView(rabbit, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(FrameLayout(context).apply { addView(barKeys); addView(barGenie); addView(barPrompt) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        // The keys set the keyboard's height; the genie panel takes exactly that, so the app never jumps.
        val body = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                keys.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(keys.measuredHeight, MeasureSpec.EXACTLY))
            }
        }.apply {
            addView(keys, FrameLayout.LayoutParams(-1, -2))
            addView(genie.view.apply { visibility = View.GONE }, FrameLayout.LayoutParams(-1, -1))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
            addView(body, LinearLayout.LayoutParams(-1, -2))
        }
        view = FrameLayout(context).apply {
            tag = "keys-panel"; setBackgroundColor(ink(R.color.keys_board))
            addView(column, FrameLayout.LayoutParams(-1, -2))
            addView(keyOverlay, FrameLayout.LayoutParams(-1, -1))
        }
        // Drag the genie panel (or its bar) up: the full genie sheet opens over the app.
        val drag = DragUp(dp(56)) { workspace() }
        bar.setOnTouchListener(drag)
        genie.handle.setOnTouchListener(drag)
        KeyboardLive.listener = { state -> render(state) }
        start(host.editor())
    }

    // ------------------------------------------------------------------ the field

    /** A new field (or the keyboard shown again): the right keys, nothing carried over. */
    fun start(info: EditorInfo?) {
        stopDictation()
        locked = KeyboardText.locked(context, info)
        engine.locked = locked
        engine.selStart = info?.initialSelStart ?: -1
        engine.selEnd = info?.initialSelEnd ?: -1
        val fieldMode = Keys.mode(info)
        page = if (fieldMode.numeric) "numbers" else "letters"
        showPage()
        keys.enter = Keys.enter(info)
        genieOff = !locked && KeyboardPrefs.off(context, info?.packageName)
        keys.genieOff = locked || genieOff
        learning = !locked && !fieldMode.email && !fieldMode.url && ((info?.inputType ?: 0) and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0
        if (learning) words.language(host.language())
        typedWord = ""; typedPrevious = null; showIdeas(emptyList(), "")
        clip.visibility = if (locked) View.GONE else View.VISIBLE
        mic.visibility = if (locked) View.GONE else View.VISIBLE
        undo = null
        setMode(Mode.KEYS, animate = false)
        engine.caps()
        if (locked) { ideas.text = ""; return }
        // The genie's Replace from the full sheet lands here, in the field it came from.
        val ready = KeyboardInbox.take(info)
        if (ready != null) { applyInbox(ready); return }
        KeyboardInbox.waiting()?.let { waiting -> notice("Insert Ægentica's text", "keys-inbox-insert") { insert(waiting); KeyboardInbox.clear() }; return }
        ideas.text = if (KeyboardLive.unseen()) "Ægentica's answer is ready · tap the rabbit" else ""
        rabbitPose()
        refreshIdeas()
    }

    fun selection(start: Int, end: Int) { engine.selStart = start; engine.selEnd = end; if (mode != Mode.PROMPT) engine.caps() }

    fun finish() { stopDictation(); keys.reset(); if (learning) words.save() }

    private fun showPage() {
        val info = host.editor()
        val fieldMode = Keys.mode(info)
        keys.layout = when (page) {
            "numbers" -> Keys.numbers(info)
            "symbols" -> Keys.symbols(fieldMode)
            "more" -> Keys.more(fieldMode)
            "emoji" -> Keys.emoji(fieldMode)
            else -> if (host.language() == "en") Keys.english(fieldMode) else Keys.swedish(fieldMode)
        }
        keys.spaceLabel = if (page == "letters") (if (host.language() == "en") "English" else "Svenska") else ""
        keys.shift = if (page == "letters") engine.shift else KeysView.Shift.OFF
    }

    internal fun pressed(key: Key) {
        when (key.code) {
            Keys.TEXT -> type(key.text)
            Keys.SPACE -> type(" ")
            Keys.SHIFT -> { engine.shiftTap(); keys.shift = engine.shift }
            Keys.BACKSPACE -> if (mode == Mode.PROMPT) { if (prompt.isNotEmpty()) prompt.setLength(prompt.length - Character.charCount(prompt.codePointBefore(prompt.length))); showPrompt() } else { engine.backspace(); scheduleIdeas() }
            Keys.ENTER -> if (mode == Mode.PROMPT) sendPrompt() else engine.enter()
            Keys.SYMBOLS -> { page = "symbols"; showPage() }
            Keys.MORE -> { page = "more"; showPage() }
            Keys.EMOJI -> { page = "emoji"; showPage() }
            Keys.LETTERS -> { page = "letters"; showPage() }
            Keys.GENIE -> if (mode == Mode.PROMPT) setMode(Mode.GENIE) else openGenie()
        }
        if (key.code != Keys.SHIFT) keys.shift = if (page == "letters") engine.shift else KeysView.Shift.OFF
    }

    internal fun type(text: String) {
        undo = null
        if (mode == Mode.PROMPT) {
            // The request starts with a capital, like a sentence.
            val capital = engine.shift != KeysView.Shift.OFF || prompt.isEmpty() || prompt.endsWith(". ")
            prompt.append(if (capital && text.length == 1 && text[0].isLetter()) text.uppercase() else text)
            if (engine.shift == KeysView.Shift.ONCE) engine.shiftTap(off = true)
            showPrompt()
            return
        }
        // A space or punctuation ends a word: it is learned (where learning is allowed).
        if (learning && typedWord.length > 1 && text.length == 1 && !text[0].isLetter() && text[0] != '\'') words.learn(typedWord, typedPrevious)
        engine.type(text)
        if (mode == Mode.KEYS && ideas.text.isNotEmpty() && !KeyboardLive.unseen()) clearNotice()
        scheduleIdeas()
    }

    // ------------------------------------------------------------------ the genie

    private fun fieldAsk(request: String): GenieAsk? {
        val info = host.editor() ?: return null
        val connection = host.connection() ?: return null
        val pkg = info.packageName.orEmpty()
        // Without extracted text the selection's place comes from the last selection update.
        val read = KeyboardText.read(connection)?.let { if (it.selected && it.start < 0 && engine.selStart >= 0) it.copy(start = engine.selStart, end = engine.selStart + it.text.length) else it }
        return GenieAsk(request, read, pkg, KeyboardText.appName(context, pkg),
            KeyboardText.kind(info), info.hintText?.toString().orEmpty(), KeyboardText.clip(context))
    }

    /** The Æ key: the keys become the genie panel, about what is in the field. */
    fun openGenie() {
        if (locked) { notice("Ægentica is off in this field.", null, null); return }
        if (genieOff) { offNotice(); return }
        if (KeyboardLive.ask != null && (KeyboardLive.state.busy || KeyboardLive.unseen()) && KeyboardLive.sameField(host.editor())) {
            KeyboardLive.seen(); setMode(Mode.GENIE); render(KeyboardLive.state); return
        }
        val base = fieldAsk("") ?: return
        KeyboardLive.forget()
        val what = (when { base.field == null -> "Empty ${base.kind} field"; base.field.selected -> "Your selection"; else -> "Your text" }) +
            (if (base.appName.isNotBlank()) " in ${base.appName}" else "") + (if (base.clip != null) " · copied text" else "")
        genie.idle(what, offers(base), base.appName)
        status.text = "Ægentica"
        setMode(Mode.GENIE)
    }

    /** General starting points; anything else is typed or spoken. */
    private fun offers(base: GenieAsk): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (base.clip != null) out += "Reply to copied" to "Write my reply to the text I copied, ready to send."
        if (base.field != null) {
            out += "Improve" to "Make my text read clearly and naturally. Keep its meaning, tone and language."
            out += "Fix" to "Correct spelling, grammar and punctuation. Keep my wording and language."
            out += "Shorter" to "Make my text shorter. Keep its meaning and language."
            out += "Continue" to "Continue my text naturally, in my voice and language."
        } else out += "Write it for me" to "Write what belongs in this field for me."
        return out
    }

    private fun ask(request: String, shown: String) {
        if (locked) return
        val ask = fieldAsk(request) ?: return
        KeyboardLive.start(context, ask, shown, host.editor())
        setMode(Mode.GENIE)
        render(KeyboardLive.state)
    }

    private fun render(state: GenieState) {
        if (mode == Mode.GENIE || mode == Mode.PROMPT) {
            genie.show(state, KeyboardLive.asked)
            KeyboardLive.seen()
        }
        val step = state.steps.lastOrNull { it.status == "running" || it.status == "awaiting-approval" }?.label
        status.text = when {
            state.approval != null -> "Waiting for you"
            state.busy -> step ?: if (state.answer.isBlank()) "Thinking…" else "Writing…"
            state.error != null -> state.error
            else -> state.who.ifBlank { "Ægentica" }
        }
        if (mode == Mode.KEYS && !state.busy && KeyboardLive.unseen()) notice("Ægentica's answer is ready · tap the rabbit", null, null)
        rabbitPose()
    }

    private fun insert(text: String) {
        val connection = host.connection() ?: return
        connection.commitText(text, 1)
        KeyboardLive.used()
        setMode(Mode.KEYS)
        notice("Inserted.", null, null)
    }

    private fun replace(text: String): Boolean {
        val connection = host.connection() ?: return false
        val source = KeyboardLive.ask?.field ?: return false
        if (!KeyboardText.replace(connection, source, text)) { status.text = "The text changed. Insert it instead."; return false }
        KeyboardLive.used()
        setMode(Mode.KEYS)
        val start = source.start
        notice("Replaced · Undo", "keys-undo") { KeyboardText.undo(connection, start, text, source.text); clearNotice() }
        return true
    }

    /** The sheet's Replace, back in the field it came from: only when the field still holds what was sent. */
    private fun applyInbox(ticket: KeyboardInbox.Ticket) {
        val connection = host.connection() ?: return
        val answer = ticket.answer ?: return
        val source = ticket.source
        val done = if (source == null) KeyboardText.empty(connection) && connection.commitText(answer, 1) else KeyboardText.replace(connection, source, answer)
        if (done && source != null) notice("Ægentica's text is in · Undo", "keys-undo") { KeyboardText.undo(connection, source.start, answer, source.text); clearNotice() }
        else if (done) notice("Ægentica's text is in.", null, null)
        else notice("Insert Ægentica's text", "keys-inbox-insert") { insert(answer) }
    }

    /** The full genie sheet over the app, with the field's text; its Replace comes back to this field. */
    fun workspace() {
        if (locked) { notice("Ægentica is off in this field.", null, null); return }
        if (genieOff) { offNotice(); return }
        val info = host.editor() ?: return
        // A conversation already going on the phone agent continues in the sheet, and its answer
        // waits back in the keys; otherwise the sheet starts on the field's text, and its Replace comes back here.
        if (KeyboardLive.work?.continues == true && KeyboardLive.sameField(info)) {
            setMode(Mode.KEYS, animate = false)
            // The sheet's "Put it in the field" comes back to this field, like Replace.
            val ticket = KeyboardInbox.open(info, KeyboardLive.ask?.field, KeyboardLive.ask?.app.orEmpty(), KeyboardLive.asked.ifBlank { null })
            host.open(Intent(context, GenieKeyboardBridge::class.java).putExtra(GenieKeyboardBridge.CONTINUE, true).putExtra(GenieKeyboardBridge.TICKET, ticket.id))
            return
        }
        val ask = KeyboardLive.ask?.takeIf { KeyboardLive.sameField(info) } ?: fieldAsk("") ?: return
        val ticket = KeyboardInbox.open(info, ask.field, ask.app, KeyboardLive.asked.ifBlank { null })
        setMode(Mode.KEYS, animate = false)
        host.open(Intent(context, GenieKeyboardBridge::class.java).putExtra(GenieKeyboardBridge.TICKET, ticket.id))
    }

    private fun sendPrompt() {
        val words = prompt.toString().trim()
        if (words.isEmpty()) { setMode(Mode.GENIE); return }
        prompt.setLength(0)
        val work = KeyboardLive.work
        if (work != null && KeyboardLive.state.busy) { work.steer(words); KeyboardLive.asked = words; setMode(Mode.GENIE) }
        else ask(words, words)
    }

    private fun showPrompt() {
        promptText.text = if (prompt.isEmpty()) "Ask Ægentica anything" else prompt
        promptText.setTextColor(ink(if (prompt.isEmpty()) R.color.keys_ink_dim else R.color.keys_ink))
    }

    /** Keys, genie panel or prompt: the shape changes with a short fade and lift (none for reduced motion). */
    fun setMode(next: Mode, animate: Boolean = true) {
        val before = mode
        mode = next
        barKeys.visibility = if (next == Mode.KEYS) View.VISIBLE else View.GONE
        barGenie.visibility = if (next == Mode.GENIE) View.VISIBLE else View.GONE
        barPrompt.visibility = if (next == Mode.PROMPT) View.VISIBLE else View.GONE
        float.visibility = if (Hands.overlay != null) View.VISIBLE else View.GONE
        // The keys keep their height under the panel, so the app never jumps.
        keys.visibility = if (next == Mode.GENIE) View.INVISIBLE else View.VISIBLE
        genie.view.visibility = if (next == Mode.GENIE) View.VISIBLE else View.GONE
        if (next == Mode.PROMPT) { prompt.setLength(0); showPrompt(); page = "letters"; showPage() }
        if (next == Mode.KEYS && before != Mode.KEYS) { page = if (Keys.mode(host.editor()).numeric) "numbers" else "letters"; showPage() }
        if (animate && before != next && ValueAnimator.areAnimatorsEnabled()) {
            val shown = if (next == Mode.GENIE) genie.view else keys
            shown.alpha = 0f; shown.translationY = dp(12).toFloat()
            shown.animate().alpha(1f).translationY(0f).setDuration(HERO_MS).setInterpolator(HERO).start()
            hop()
        }
        rabbitPose()
    }

    // ------------------------------------------------------------------ the rabbit

    private val flip = object : Runnable {
        override fun run() {
            if (!KeyboardLive.state.busy) { rabbitPose(); return }
            busyFrame++
            rabbit.setImageResource(if (busyFrame % 2 == 0) R.drawable.mascot_running else R.drawable.mascot_leaping)
            main.postDelayed(this, 300)
        }
    }

    private fun rabbitPose() {
        val busy = KeyboardLive.state.busy
        main.removeCallbacks(flip)
        when {
            busy && ValueAnimator.areAnimatorsEnabled() -> main.post(flip)
            busy -> rabbit.setImageResource(R.drawable.mascot_running)
            mode != Mode.KEYS -> rabbit.setImageResource(R.drawable.mascot_curious)
            KeyboardLive.unseen() -> rabbit.setImageResource(R.drawable.mascot_waving)
            else -> rabbit.setImageResource(R.drawable.mascot_sitting)
        }
        rabbit.alpha = if (locked) 0.35f else 1f
        rabbit.contentDescription = if (locked) "Ægentica is off in this field" else if (busy) "Ægentica is working" else "Ægentica"
    }

    private fun hop() {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        rabbit.animate().cancel()
        rabbit.translationY = 0f
        rabbit.animate().translationY(-dp(8).toFloat()).setDuration(140).setInterpolator(HERO).withEndAction {
            rabbit.animate().translationY(0f).setDuration(160).setInterpolator(PathInterpolator(0.3f, 1.25f, 0.55f, 1f)).start()
        }.start()
    }

    // ------------------------------------------------------------------ the bar

    private fun notice(text: String, tag: String?, action: (() -> Unit)?) {
        if (text.isNotEmpty()) suggestions.visibility = View.GONE
        ideas.visibility = View.VISIBLE
        ideas.text = text
        ideas.contentDescription = if (action != null) text else null
        ideas.setTextColor(ink(if (action != null) R.color.keys_ink else R.color.keys_ink_dim))
        if (action != null) { ideas.setOnClickListener { action() }; ideas.isClickable = true } else { ideas.setOnClickListener(null); ideas.isClickable = false }
        undo = action
    }

    private fun clearNotice() = notice("", null, null)

    /** The clipboard, offered as one tap; read only when tapped, never in locked fields. */
    private fun offerClip() {
        if (locked) return
        val text = KeyboardText.clip(context, any = true)
        if (text == null) { notice("Nothing copied.", null, null); return }
        notice("Paste “${text.take(60).replace('\n', ' ')}”", "keys-paste") { host.connection()?.commitText(text, 1); clearNotice() }
    }

    /** On-device dictation straight into the field: the owner's own words, not model text. */
    private fun dictate() {
        if (locked) return
        if (mic.isSelected) { stopDictation(); clearNotice(); return }
        val e = listenReady() ?: return
        mic.isSelected = true; mic.imageTintList = ColorStateList.valueOf(ink(R.color.keys_ink_dim))
        notice("Listening…", null, null)
        e.listen(onPartial = { ideas.text = it }, onFinal = { heard ->
            if (heard.isNotBlank()) host.connection()?.commitText(heard.trim() + " ", 1)
        }, onStop = { problem -> stopDictation(); problem?.let { notice(it.message, null, null) } ?: clearNotice() })
    }

    /** Speak a request to the genie: the words go in as the prompt, and it starts. */
    private fun listenToGenie() {
        val e = listenReady() ?: return
        setMode(Mode.PROMPT)
        promptText.text = "Listening…"
        e.listen(onPartial = { promptText.text = it }, onFinal = { heard ->
            if (heard.isNotBlank()) { prompt.setLength(0); prompt.append(heard.trim()); sendPrompt() }
        }, onStop = { problem -> problem?.let { status.text = it.message }; if (mode == Mode.PROMPT && prompt.isEmpty()) showPrompt() })
    }

    private fun listenReady(): Ears? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notice("Allow the microphone for Ægentica, then try again.", null, null)
            host.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            return null
        }
        val e = ears ?: Ears(context).also { ears = it }
        if (!e.available()) { notice("On-device speech recognition is not ready on this phone.", null, null); return null }
        e.cancel()
        return e
    }

    private fun stopDictation() {
        ears?.cancel()
        mic.isSelected = false; mic.imageTintList = ColorStateList.valueOf(ink(R.color.keys_ink))
    }

    // ------------------------------------------------------------------ the ideas line

    private val ideasRun = Runnable { refreshIdeas() }

    /** After the key is handled: the field is read for the word being typed, off the tap's path. */
    private fun scheduleIdeas() { if (learning) { main.removeCallbacks(ideasRun); main.post(ideasRun) } }

    private fun refreshIdeas() {
        if (!learning || mode != Mode.KEYS) { showIdeas(emptyList(), ""); return }
        val before = host.connection()?.getTextBeforeCursor(48, 0)
        val current = WordSuggest.current(before)
        typedWord = current
        typedPrevious = WordSuggest.previous(before?.let { it.subSequence(0, it.length - current.length) })
        val ideas = if (current.isNotEmpty()) words.complete(current) else typedPrevious?.let { words.next(it) }.orEmpty()
        showIdeas(ideas, current)
    }

    /** The first idea shows as ghost text: what is typed, then the rest faded. A tap puts the word in. */
    private fun showIdeas(list: List<String>, typed: String) {
        if (list.isEmpty() || (ideas.text.isNotEmpty() && ideas.isClickable)) { suggestions.visibility = View.GONE; ideas.visibility = View.VISIBLE; return }
        ideas.visibility = View.GONE
        suggestions.visibility = View.VISIBLE
        for (i in 0 until suggestions.childCount) {
            val slot = suggestions.getChildAt(i) as TextView
            val word = list.getOrNull(i)
            slot.visibility = if (word == null) View.INVISIBLE else View.VISIBLE
            if (word == null) continue
            slot.text = if (i == 0 && typed.isNotEmpty() && word.length > typed.length && word.startsWith(typed, ignoreCase = true))
                android.text.SpannableString(word).apply { setSpan(android.text.style.ForegroundColorSpan(ink(R.color.keys_ink_dim)), typed.length, word.length, 0) }
                else word
            slot.contentDescription = word
            slot.setOnClickListener { take(word, typed) }
        }
    }

    private fun take(word: String, typed: String) {
        val connection = host.connection() ?: return
        connection.beginBatchEdit()
        if (typed.isNotEmpty()) connection.deleteSurroundingText(typed.length, 0)
        connection.commitText("$word ", 1)
        connection.endBatchEdit()
        words.learn(word, typedPrevious)
        engine.caps(); keys.shift = engine.shift
        scheduleIdeas()
    }

    // ------------------------------------------------------------------ voice, the switch, the bubble

    /** Hold space and speak: the words go to the genie as a request, which starts when the finger lifts. */
    private fun holdToSpeak(): Boolean {
        if (locked || genieOff || mode == Mode.PROMPT) return false
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val e = ears ?: Ears(context).also { ears = it }
        if (!e.available()) return false
        e.cancel()
        setMode(Mode.PROMPT)
        promptText.text = "Listening… let go to send"
        e.listen(onPartial = { promptText.text = it }, onFinal = { heard ->
            if (heard.isNotBlank()) { prompt.setLength(0); prompt.append(heard.trim()); sendPrompt() }
        }, onStop = { problem -> if (mode == Mode.PROMPT && prompt.isEmpty()) { problem?.let { status.text = it.message }; setMode(Mode.KEYS) } })
        return true
    }

    private fun setGenieOff(off: Boolean) {
        val pkg = host.editor()?.packageName ?: return
        KeyboardPrefs.setOff(context, pkg, off)
        genieOff = off
        keys.genieOff = locked || off
        setMode(Mode.KEYS)
        if (off) offNotice() else notice("Ægentica is on here again.", null, null)
    }

    private fun offNotice() {
        val app = KeyboardText.appName(context, host.editor()?.packageName.orEmpty()).ifBlank { "this app" }
        notice("Ægentica is off in $app · Turn on", "keys-genie-on") { setGenieOff(false) }
    }

    /** The genie floats over the app as the rabbit (Ægentica hands' bubble) and the keyboard steps aside. */
    private fun floatGenie() {
        val genie = Hands.overlay ?: return
        setMode(Mode.KEYS, animate = false)
        genie.summon()
        host.hide()
    }

    /** A vertical drag of at least [distance] pixels upwards. Taps pass through. */
    private class DragUp(private val distance: Int, private val done: () -> Unit) : View.OnTouchListener {
        private var startY = 0f
        private var fired = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; fired = false }
                MotionEvent.ACTION_MOVE -> if (!fired && startY - e.rawY > distance) { fired = true; v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); done() }
            }
            // Only touches no child took arrive here; keep them so the drag can be followed.
            return true
        }
    }

    companion object {
        const val HERO_MS = 200L
        val HERO = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}

/**
 * The genie's work from the keyboard. It lives beyond the keyboard's views, so a request keeps going
 * when the keyboard hides and its answer is waiting the next time the keyboard opens.
 */
object KeyboardLive {
    @Volatile var ask: GenieAsk? = null; private set
    @Volatile var asked = ""
    @Volatile var work: GenieWork? = null; private set
    @Volatile var state = GenieState(); private set
    @Volatile var listener: ((GenieState) -> Unit)? = null
    private var field: Pair<String, Int>? = null
    private var unseen = false
    private var generation = 0
    private val main = Handler(Looper.getMainLooper())

    fun start(context: Context, ask: GenieAsk, shown: String, info: EditorInfo?) {
        work?.stop()
        val token = ++generation
        this.ask = ask; asked = shown
        field = info?.let { (it.packageName.orEmpty()) to it.fieldId }
        state = GenieState(busy = true)
        unseen = false
        val run = KeyboardAgents.get(context)
        val app = context.applicationContext
        work = run.start(context, ask) { s ->
            main.post {
                // Updates from an earlier request (its agent may keep reporting) are ignored.
                if (token != generation) return@post
                state = s
                if (!s.busy) unseen = true
                listener?.invoke(s)
                GenieStatus.changed(app)
            }
        }
    }

    fun sameField(info: EditorInfo?) = info != null && field == (info.packageName.orEmpty() to info.fieldId)
    fun unseen() = unseen && ask != null
    fun seen() { unseen = false }
    /** The answer went into a field: nothing is waiting any more. */
    fun used() { unseen = false }
    fun forget() { generation++; work?.stop(); work = null; ask = null; asked = ""; state = GenieState(); unseen = false; field = null }
}

/** Typing on an input connection: letters, shift and caps lock, delete, Enter's action and the cursor. */
class KeysEngine(private val connection: () -> InputConnection?, private val editor: () -> EditorInfo?) {
    var shift = KeysView.Shift.OFF; private set
    var selStart = -1
    var selEnd = -1
    /** Plain keys: nothing in the field is read (no grapheme-aware delete, no double-space full stop). */
    var locked = false
    private var shiftAt = 0L
    private var autoShift = false
    /** When the last space was typed; -1 when the last key was not a space. */
    private var spaceAt = -1L

    private fun now() = SystemClock.uptimeMillis()
    private fun raw() = editor()?.inputType == InputType.TYPE_NULL

    fun type(text: String) {
        val ic = connection() ?: return
        val out = if (shift != KeysView.Shift.OFF && text.length == 1 && text[0].isLetter()) text.uppercase() else text
        if (out == " " && !locked && fullStop(ic)) return
        ic.commitText(out, 1)
        spaceAt = if (out == " ") now() else -1L
        if (shift == KeysView.Shift.ONCE) { shift = KeysView.Shift.OFF; autoShift = false }
        caps()
    }

    /** Two spaces after a word: a full stop and one space. */
    private fun fullStop(ic: InputConnection): Boolean {
        if (spaceAt < 0 || now() - spaceAt > 600) return false
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: return false
        if (before.length != 2 || before[1] != ' ' || !before[0].isLetterOrDigit()) return false
        ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); ic.endBatchEdit()
        spaceAt = -1L
        caps()
        return true
    }

    fun backspace() {
        val ic = connection() ?: return
        spaceAt = -1L
        when {
            raw() -> key(ic, KeyEvent.KEYCODE_DEL)
            selStart >= 0 && selEnd > selStart -> ic.commitText("", 1)
            locked -> ic.deleteSurroundingTextInCodePoints(1, 0)
            else -> {
                // A whole character, even an emoji made of several code points.
                val before = ic.getTextBeforeCursor(16, 0)?.toString()
                if (before.isNullOrEmpty()) key(ic, KeyEvent.KEYCODE_DEL)
                else {
                    val it = BreakIterator.getCharacterInstance().apply { setText(before) }
                    val last = it.last(); val previous = it.previous()
                    ic.deleteSurroundingText(if (previous == BreakIterator.DONE) before.length else last - previous, 0)
                }
            }
        }
        caps()
    }

    fun enter() {
        val ic = connection() ?: return
        val info = editor()
        val enter = Keys.enter(info)
        when {
            enter != Keys.Enter.NEWLINE -> ic.performEditorAction(if (info?.actionLabel != null && info.actionId != 0) info.actionId else Keys.action(enter))
            raw() || !Keys.mode(info).multiLine -> key(ic, KeyEvent.KEYCODE_ENTER)
            else -> ic.commitText("\n", 1)
        }
        caps()
    }

    fun cursor(steps: Int) {
        val ic = connection() ?: return
        repeat(abs(steps)) { key(ic, if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT) }
    }

    /** Tap: shift for one letter; tap again quickly: caps lock; tap in caps lock: off. */
    fun shiftTap(off: Boolean = false) {
        val t = now()
        shift = when {
            off -> KeysView.Shift.OFF
            shift == KeysView.Shift.OFF -> KeysView.Shift.ONCE
            shift == KeysView.Shift.ONCE && !autoShift && t - shiftAt < 350 -> KeysView.Shift.LOCKED
            else -> KeysView.Shift.OFF
        }
        autoShift = false
        shiftAt = t
    }

    /** A capital at the start of a sentence, where the field asks for one. */
    fun caps() {
        if (shift == KeysView.Shift.LOCKED) return
        val info = editor() ?: return
        if (!Keys.mode(info).autoCaps) { if (autoShift) { shift = KeysView.Shift.OFF; autoShift = false }; return }
        val on = connection()?.getCursorCapsMode(info.inputType)?.let { it != 0 } ?: false
        if (on && shift == KeysView.Shift.OFF) { shift = KeysView.Shift.ONCE; autoShift = true }
        else if (!on && shift == KeysView.Shift.ONCE && autoShift) { shift = KeysView.Shift.OFF; autoShift = false }
    }

    private fun key(ic: InputConnection, code: Int) {
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }
}

/** Reading and writing the focused field, and deciding where the genie may not look. */
object KeyboardText {
    const val MAX = 6000

    /** What the genie read: the selection ([start], [end]) or the whole field (start 0). */
    data class Source(val text: String, val selected: Boolean, val start: Int, val end: Int)

    fun secret(info: EditorInfo?): Boolean = info != null && secret(info.inputType)

    /** Password variations of text and number fields. */
    fun secret(type: Int): Boolean {
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /**
     * Plain keys only: passwords, PINs, numbers (one-time codes and card numbers are number fields),
     * incognito fields (IME_FLAG_NO_PERSONALIZED_LEARNING) and fields whose hint or name says card,
     * code or password (the playbook's secret-field words). The genie is not offered and the field
     * is never read.
     */
    fun locked(context: Context, info: EditorInfo?): Boolean {
        if (info == null) return false
        if (secret(info.inputType)) return true
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) return true
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        val words = runCatching { Playbook.get(context).secretFields }.getOrDefault(FALLBACK_SECRET_WORDS)
        val said = listOfNotNull(info.hintText?.toString(), info.label?.toString(), info.fieldName, info.privateImeOptions).joinToString(" ").lowercase()
        if (said.isBlank()) return false
        val tokens = said.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return words.any { word -> if (word.contains(' ') || word.contains('-')) said.contains(word) else tokens.any { it.startsWith(word) } }
    }

    private val FALLBACK_SECRET_WORDS = listOf("pass", "pin", "otp", "cvc", "cvv", "kortnummer", "card number")

    /** The selection if there is one, otherwise the whole field; null when the field is empty. */
    fun read(connection: InputConnection): Source? {
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX; hintMaxLines = 2000 }, 0)
        val selected = connection.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            val start = extracted?.let { it.startOffset + minOf(it.selectionStart, it.selectionEnd) } ?: -1
            return Source(selected.take(MAX), selected = true, start = start, end = start + selected.length)
        }
        val all = whole(connection, extracted) ?: return null
        return all.takeIf { it.isNotBlank() }?.let { Source(it.take(MAX), selected = false, start = 0, end = it.length) }
    }

    private fun whole(connection: InputConnection, extracted: android.view.inputmethod.ExtractedText? = null): String? {
        val et = extracted ?: connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX; hintMaxLines = 2000 }, 0)
        et?.text?.let { if (et.startOffset == 0) return it.toString() }
        val before = connection.getTextBeforeCursor(MAX, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(MAX, 0)?.toString() ?: return null
        return before + after
    }

    fun empty(connection: InputConnection) =
        connection.getTextBeforeCursor(1, 0)?.isEmpty() == true && connection.getTextAfterCursor(1, 0)?.isEmpty() == true

    /** Swap [source] for [text], only while the field still holds exactly what the genie read. */
    fun replace(connection: InputConnection, source: Source, text: String): Boolean {
        connection.beginBatchEdit()
        try {
            if (source.selected) {
                if (source.start < 0) return false
                connection.setSelection(source.start, source.end)
                if (connection.getSelectedText(0)?.toString() != source.text) return false
            } else {
                val now = whole(connection) ?: return false
                if (now != source.text) return false
                connection.setSelection(0, now.length)
            }
            connection.commitText(text, 1)
            return true
        } finally { connection.endBatchEdit() }
    }

    /** Put [original] back where [inserted] went, if it is still there. */
    fun undo(connection: InputConnection, start: Int, inserted: String, original: String) {
        connection.beginBatchEdit()
        try {
            connection.setSelection(start, start + inserted.length)
            if (connection.getSelectedText(0)?.toString() == inserted) connection.commitText(original, 1)
        } finally { connection.endBatchEdit() }
    }

    /** What kind of field, in words for the genie. */
    fun kind(info: EditorInfo?): String {
        val type = info?.inputType ?: return "text"
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) when (variation) {
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return "email address"
            InputType.TYPE_TEXT_VARIATION_URI -> return "web address"
            InputType.TYPE_TEXT_VARIATION_PERSON_NAME -> return "name"
            InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS -> return "postal address"
            InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT -> return "email subject"
            InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE, InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE -> return "message"
            InputType.TYPE_TEXT_VARIATION_FILTER -> return "search box"
        }
        return when (Keys.enter(info)) {
            Keys.Enter.SEARCH -> "search box"
            Keys.Enter.SEND -> "message"
            else -> "text"
        }
    }

    fun appName(context: Context, pkg: String): String = if (pkg.isBlank()) "" else runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) }

    /**
     * Text copied in the last five minutes ([any]: whenever), never a clip the copying app marked
     * sensitive (a password manager's).
     */
    fun clip(context: Context, any: Boolean = false): String? {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val description = manager.primaryClipDescription ?: return null
        if (Build.VERSION.SDK_INT >= 33 && description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return null
        if (!any && description.timestamp > 0 && System.currentTimeMillis() - description.timestamp > 5 * 60_000L) return null
        return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.take(MAX)?.takeIf { it.isNotEmpty() }
    }
}

/**
 * The genie sheet's Replace, waiting for the field it came from. The keyboard opens the sheet with a
 * ticket; the sheet's answer is kept here (in memory, ten minutes at most) and applied when that
 * same field has the keyboard again and still holds the text that was sent. Anywhere else it is
 * only offered, as one tap.
 */
object KeyboardInbox {
    class Ticket(val id: String, val pkg: String, val fieldId: Int, val source: KeyboardText.Source?, val app: String, val request: String?, val at: Long) {
        @Volatile var answer: String? = null
    }

    @Volatile private var current: Ticket? = null
    private const val LIFETIME = 10 * 60_000L

    fun open(info: EditorInfo, source: KeyboardText.Source?, app: String, request: String?): Ticket =
        Ticket(java.util.UUID.randomUUID().toString(), info.packageName.orEmpty(), info.fieldId, source, app, request, SystemClock.elapsedRealtime()).also { current = it }

    fun ticket(id: String?): Ticket? = current?.takeIf { it.id == id && fresh(it) }

    fun answer(id: String, text: String) { current?.takeIf { it.id == id }?.answer = text }

    private fun fresh(t: Ticket) = SystemClock.elapsedRealtime() - t.at < LIFETIME

    /** The answer for this very field, taken once. */
    fun take(info: EditorInfo?): Ticket? {
        val t = current ?: return null
        if (!fresh(t)) { current = null; return null }
        if (t.answer == null || info == null) return null
        if (info.packageName.orEmpty() != t.pkg || info.fieldId != t.fieldId) return null
        current = null
        return t
    }

    /** An answer that found no matching field. */
    fun waiting(): String? = current?.takeIf { fresh(it) }?.answer

    fun clear() { current = null }
}

/** Turning the keyboard on: Android lets only the owner enable a keyboard, so this is two taps at most. */
object KeyboardSetup {
    @Volatile private var awaiting = false

    fun enabled(context: Context) = context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
        .any { it.packageName == context.packageName }

    fun chosen(context: Context) = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        ?.substringBefore('/') == context.packageName

    fun show(activity: Activity) {
        val on = enabled(activity)
        val chosen = chosen(activity)
        val text = when {
            chosen -> "Ægentica keys is your keyboard. Tap Æ (or the rabbit) in any app and the genie works on what you are writing; drag it up for the full genie."
            on -> "Ægentica keys is turned on. Choose it as your keyboard, and it opens in every app."
            else -> "A full keyboard with Ægentica inside: type as usual, tap Æ for the genie with what you are writing, drag up for the full genie.\n\n" +
                "1. Turn on Ægentica keys (Android asks once and warns that a keyboard can read what you type).\n2. Choose it as your keyboard."
        } + "\n\nThe field's text leaves the phone only when you tap Æ and ask, and the genie's text goes in only when you tap Insert or Replace. Passwords, cards, codes, numbers and incognito fields get plain keys only."
        val dialog = android.app.AlertDialog.Builder(activity).setTitle("Ægentica keys").setMessage(text).setNegativeButton("Not now", null)
        when {
            !on -> dialog.setPositiveButton("Turn on") { _, _ ->
                awaiting = true
                runCatching { activity.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
            }
            !chosen -> dialog.setPositiveButton("Choose keyboard") { _, _ -> activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
            else -> dialog.setPositiveButton("Choose another keyboard") { _, _ -> activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        }
        // Words learned for the ideas line, and apps where the genie is off, can be cleared here.
        val off = KeyboardPrefs.offApps(activity)
        dialog.setNeutralButton(if (off.isEmpty()) "Forget typed words" else "Forget words, genie on everywhere") { _, _ ->
            WordSuggest.forget(activity); KeyboardPrefs.clearOff(activity)
            android.widget.Toast.makeText(activity, "Done.", android.widget.Toast.LENGTH_SHORT).show()
        }
        dialog.showProtected(activity)
    }

    /** Back from Android's keyboard settings with Ægentica keys turned on: offer the keyboard picker at once. */
    fun resumed(activity: Activity) {
        if (!awaiting || !enabled(activity)) return
        awaiting = false
        Handler(Looper.getMainLooper()).postDelayed({
            if (!activity.isFinishing && !chosen(activity)) activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }, 500)
    }
}

/** Per-app choices: where the owner turned the genie off. */
object KeyboardPrefs {
    private fun prefs(context: Context) = context.getSharedPreferences("keys", 0)
    fun off(context: Context, pkg: String?) = !pkg.isNullOrBlank() && pkg in prefs(context).getStringSet("off", emptySet()).orEmpty()
    fun setOff(context: Context, pkg: String, off: Boolean) {
        val now = prefs(context).getStringSet("off", emptySet()).orEmpty().toMutableSet()
        if (off) now += pkg else now -= pkg
        prefs(context).edit().putStringSet("off", now).apply()
    }
    fun offApps(context: Context): Set<String> = prefs(context).getStringSet("off", emptySet()).orEmpty()
    fun clearOff(context: Context) = prefs(context).edit().remove("off").apply()
}
