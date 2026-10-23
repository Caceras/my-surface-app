package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * The assistant panel, opened above the current app by [AssistantSession]
 * (or directly from Settings, without screen context). It shows what Android
 * shared, asks Nano or the connected model about it, and offers explicit
 * follow-ups. Nothing is tapped or typed in the other app, and generated text
 * is never run as a command: every action is a button the owner presses.
 */
class AssistantActivity : Activity() {
    private lateinit var seen: TextView
    private lateinit var chip: LinearLayout
    private lateinit var suggestions: HorizontalScrollView
    private lateinit var chips: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var asked: TextView
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
    private val sendFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    /** The window background: the frosted sheet itself. */
    private val sheet = GradientDrawable()
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var actions: LinearLayout
    private lateinit var follows: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var unlisten: (() -> Unit)? = null
    private var brain: SurfaceBrain? = null
    private var ears: Ears? = null
    private var mouth: Mouth? = null
    private var request = 0
    private var question = ""
    private var reply = ""
    private var settled = false
    private var invocation = 0
    /** Text selected in another app (the selection menu's "Ægentica"); null for the assistant gesture. */
    private var selection: String? = null
    /** The selection came from an editable field, so an answer can replace it. */
    private var editable = false
    private var source = ""

    private fun prefs() = getSharedPreferences("surfacelab", 0)
    /** The owner can leave the screen out of a question with one tap on the context chip. */
    private fun screenOn() = prefs().getBoolean("assistant_screen", true)
    private fun hint() = when {
        selection != null -> "Ask about the selection"
        !ScreenContexts.current.empty && screenOn() -> "Ask about this screen"
        else -> "Ask anything"
    }
    /** What the next question includes: the selection, or the shared screen unless the owner left it out. */
    private fun context(): ScreenContext = selection?.let { ScreenContext(source, it, null, true) }
        ?: ScreenContexts.current.takeIf { screenOn() } ?: ScreenContext()

    private fun press() = ColorStateList.valueOf(ink(R.color.ask_press))
    private fun pill(fill: Int?, radius: Int) = GradientDrawable().apply { fill?.let { setColor(ink(it)) }; cornerRadius = dp(radius).toFloat() }

    /**
     * Frosted glass. Android blurs the app behind this window when cross-window
     * blur is on (the default on Pixel); with blur off, for example in battery
     * saver, the sheet turns nearly opaque so text stays readable.
     */
    private fun frost(blurred: Boolean) {
        sheet.setColor(ink(if (blurred) R.color.ask_glass_blur else R.color.ask_glass_solid))
        if (android.os.Build.VERSION.SDK_INT >= 31) window.setBackgroundBlurRadius(if (blurred) dp(30) else 0)
    }

    @android.annotation.TargetApi(31)
    private fun followBlur() {
        val listener = java.util.function.Consumer<Boolean> { frost(blurForTest ?: it) }
        window.decorView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = windowManager.addCrossWindowBlurEnabledListener(listener)
            override fun onViewDetachedFromWindow(v: View) = windowManager.removeCrossWindowBlurEnabledListener(listener)
        })
    }

    private fun icon(drawable: Int, description: String, tagName: String, size: Int = 18, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(ink(R.color.ask_ink_3)); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description; tag = tagName
        background = RippleDrawable(press(), null, null)
        val side = (dp(40) - dp(size)) / 2; setPadding(side, side, side, side)
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); action() }
    }

    /** A quiet rounded chip: suggestions before the first question, links and numbers after an answer. */
    private fun chipView(title: String, tagName: String?, lead: Int? = null, run: () -> Unit) = label(title, 13.5f).apply {
        tag = tagName; gravity = Gravity.CENTER_VERTICAL; maxLines = 1; setLineSpacing(0f, 1f); isFocusable = true; buttonSemantics()
        setTextColor(ink(R.color.ask_ink_2))
        background = InsetDrawable(RippleDrawable(press(), pill(R.color.ask_fill, 16), null), 0, dp(6), 0, dp(6))
        setPadding(dp(if (lead != null) 10 else 14), 0, dp(14), 0)
        lead?.let { getDrawable(it)!!.mutate().apply { setTint(ink(R.color.ask_ink_3)); setBounds(0, 0, dp(15), dp(15)) } }?.let { setCompoundDrawablesRelative(it, null, null, null); compoundDrawablePadding = dp(6) }
        setOnClickListener { run() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val metrics = resources.displayMetrics
        val width = minOf(metrics.widthPixels - dp(20), dp(680))
        // The conversation: the question, the streamed answer, then quiet icon actions.
        asked = label("", 13f).apply { tag = "assistant-question"; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f); setTextColor(ink(R.color.ask_ink_3)) }
        answer = label("", 15f).apply { tag = "assistant-answer"; setTextColor(ink(R.color.ask_ink)); setTextIsSelectable(true); setLineSpacing(0f, 1.32f); padDp(0, 6, 4, 0) }
        follows = LinearLayout(this).apply { tag = "assistant-follows"; gravity = Gravity.CENTER_VERTICAL }
        status = label("", 12f).apply { tag = "assistant-status"; maxLines = 2; setLineSpacing(0f, 1.05f); setTextColor(ink(R.color.ask_ink_4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        actions = LinearLayout(this).apply { tag = "assistant-actions"; gravity = Gravity.CENTER_VERTICAL }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(asked, LinearLayout.LayoutParams(0, -2, 1f))
            addView(icon(R.drawable.ic_close, "Clear the answer", "assistant-close", 16) { clearThread() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            addView(HorizontalScrollView(this@AssistantActivity).apply { isHorizontalScrollBarEnabled = false; addView(follows) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val foot = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            addView(actions, LinearLayout.LayoutParams(-2, -2))
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-card"; visibility = View.GONE; padDp(14, 4, 4, 0)
            addView(top, LinearLayout.LayoutParams(-1, -2))
            addView(CappedScroll(this@AssistantActivity).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, -2))
            addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(View(this@AssistantActivity).apply { setBackgroundColor(ink(R.color.ask_divider)) }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(2); marginEnd = dp(10) })
        }
        // Suggestions before the first question.
        chips = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; padDp(4, 0, 4, 0) }
        suggestions = HorizontalScrollView(this).apply { tag = "assistant-suggestions"; isHorizontalScrollBarEnabled = false; addView(chips) }
        // The bar: the Æ context chip, the prompt, the microphone and a round send button.
        val signum = ImageView(this).apply {
            setImageResource(R.drawable.ic_surface); imageTintList = ColorStateList.valueOf(ink(R.color.ask_on_ink)); contentDescription = null; tag = "assistant-signum"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ink(R.color.ask_ink)) }; padDp(4, 4, 4, 4)
        }
        seen = label("", 13f).apply {
            tag = "assistant-seen"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f); setTextColor(ink(R.color.ask_ink_2))
            maxWidth = (width * 0.4f).toInt()
        }
        chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "assistant-screen"; isClickable = true; isFocusable = true; buttonSemantics()
            background = InsetDrawable(RippleDrawable(press(), pill(R.color.ask_fill, 18), null), 0, dp(8), 0, dp(8)); padDp(5, 0, 12, 0)
            addView(signum, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(7) })
            addView(seen, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { chipTapped() }
        }
        input = EditText(this).apply {
            tag = "assistant-input"; textSize = 16f; background = null; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setTextColor(ink(R.color.ask_ink)); setHintTextColor(ink(R.color.ask_ink_3)); padDp(10, 0, 4, 0)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { ask(text.toString(), false); true } else false }
            afterChange { updateSend(); showSuggestions() }
        }
        mic = icon(R.drawable.ic_mic_line, "Speak your question", "assistant-mic", 20) { listen() }.apply { imageTintList = ColorStateList.valueOf(ink(R.color.ask_ink_2)) }
        send = ImageButton(this).apply {
            tag = "assistant-ask"; contentDescription = "Ask"; scaleType = ImageView.ScaleType.FIT_CENTER
            // A 36dp circle centred in a 44dp touch target.
            background = InsetDrawable(RippleDrawable(press(), sendFill, null), dp(4), dp(4), dp(4), dp(4))
            setOnClickListener { if (brain != null) { stop(); status.text = "Stopped." } else ask(input.text.toString(), false) }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "assistant-bar"; padDp(4, 0, 0, 0)
            addView(chip, LinearLayout.LayoutParams(-2, dp(52)))
            addView(input, LinearLayout.LayoutParams(0, dp(52), 1f))
            addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(send, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-sheet"; padDp(4, 6, 4, 4)
            addView(card, LinearLayout.LayoutParams(-1, -2))
            addView(suggestions, LinearLayout.LayoutParams(-1, -2))
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        // One floating sheet near the bottom, above the navigation bar and the keyboard.
        sheet.cornerRadius = dp(28).toFloat(); sheet.setStroke(dp(1), ink(R.color.ask_edge))
        window.setBackgroundDrawable(sheet)
        window.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        window.attributes = window.attributes.apply { y = dp(10) }
        setFinishOnTouchOutside(true)
        frost(blurForTest ?: (android.os.Build.VERSION.SDK_INT >= 31 && windowManager.isCrossWindowBlurEnabled))
        if (android.os.Build.VERSION.SDK_INT >= 31) followBlur()
        root.post { root.arrive() }
        unlisten = ScreenContexts.listen { showContext() }
        start(intent)
    }

    private fun updateSend() {
        val busy = brain != null
        val filled = busy || input.text.isNotBlank()
        send.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_arrow_up)
        send.imageTintList = ColorStateList.valueOf(ink(if (filled) R.color.ask_on_ink else R.color.ask_ink_4))
        sendFill.setColor(ink(if (filled) R.color.ask_ink else R.color.ask_fill))
        // The arrow draws at 18dp, the stop square at 12dp.
        if (busy) send.padDp(10, 10, 10, 10) else send.padDp(13, 13, 13, 13)
        send.contentDescription = if (busy) "Stop" else "Ask"
    }

    private fun showSuggestions() {
        chips.removeAllViews()
        val visible = question.isEmpty() && input.text.isBlank()
        suggestions.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        fun add(view: View) = chips.addView(view, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        val picked = selection
        if (!context().empty) add(chipView("Chat about this", "assistant-chat", R.drawable.ic_compose) { chatAbout() }.apply {
            contentDescription = "Chat about this on aegentica.se: a new chat opens with the " + (if (picked != null) "selection" else "screen") + " in the message box"
        })
        if (picked == null) { SUGGESTIONS.forEach { s -> add(chipView(s, null) { input.setText(s); ask(s, false) }) }; return }
        SELECTION_SUGGESTIONS.forEach { (title, request) -> add(chipView(title, null) { ask(request, false, shown = title) }) }
        add(chipView("Listen", "assistant-listen", R.drawable.ic_volume) { ReadingService.start(this, picked); finish() })
    }

    /** The cross ends the current question and returns to the suggestions. */
    private fun clearThread() {
        stop()
        question = ""; reply = ""; answer.text = ""; asked.text = ""; status.text = ""; actions.removeAllViews(); follows.removeAllViews()
        card.visibility = View.GONE; showSuggestions()
    }

    private fun chipTapped() {
        if (selection != null) return
        val c = ScreenContexts.current
        if (c.empty) { if (settled) setup(this); return }
        prefs().edit().putBoolean("assistant_screen", !screenOn()).apply()
        showContext()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); stop(); start(intent) }

    private fun start(intent: Intent?) {
        selection = intent?.takeIf { it.action == Intent.ACTION_PROCESS_TEXT }
            ?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.take(ScreenContexts.MAX_TEXT)
        editable = selection != null && intent?.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false) == false
        source = callingPackage ?: referrer?.host.orEmpty()
        // A selection is not a screen invocation: closing must not clear a shared screen.
        invocation = if (selection != null) -1 else ScreenContexts.invocation
        settled = selection != null || intent?.getBooleanExtra(DIRECT, false) == true
        if (!settled) main.postDelayed({ settled = true; showContext() }, 2500)
        question = ""; reply = ""; answer.text = ""; asked.text = ""; status.text = ""; actions.removeAllViews(); follows.removeAllViews(); input.setText("")
        card.visibility = View.GONE
        showContext()
    }

    private fun modelLabel(): String = if (ConnectedAI.forVoice(this)) ConnectedAI.label(this) else "Gemini Nano · on this phone"

    private fun appName(pkg: String): String = if (pkg.isBlank()) "" else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) }

    /** The chip says what the next question will include. */
    private fun showContext() {
        if (selection != null) {
            seen.text = "Selection"
            chip.contentDescription = "Including the text you selected" + appName(source).let { if (it.isBlank()) "" else " in $it" }
            chip.alpha = 1f
            if (input.hint != "Listening…") input.hint = hint()
            updateSend(); showSuggestions()
            return
        }
        val c = ScreenContexts.current
        val on = screenOn()
        val app = appName(c.app).ifBlank { "Screen" }
        seen.text = when {
            !c.empty && on -> app
            !c.empty -> "Screen off"
            !settled -> "Reading…"
            else -> "No screen"
        }
        chip.contentDescription = when {
            !c.empty && on -> "Including $app: " + listOfNotNull("screenshot".takeIf { c.screenshot != null }, "screen text".takeIf { c.text.isNotBlank() }).joinToString(" and ") + ". Tap to leave the screen out."
            !c.empty -> "The screen is left out. Tap to include it."
            !settled -> "Reading the screen"
            intent?.getBooleanExtra(DIRECT, false) == true -> "No screen shared. Tap to set up the assistant gesture."
            else -> "Android shared nothing from this screen. Tap for assistant settings."
        }
        chip.alpha = if (!c.empty && !on) 0.55f else 1f
        if (input.hint != "Listening…") input.hint = hint()
        updateSend(); showSuggestions()
    }

    private fun ask(raw: String, aloud: Boolean, shown: String? = null) {
        val screen = context()
        val question = raw.trim().ifEmpty { if (screen.empty) { toast("Type or speak a question."); return } else if (selection != null) "Explain this." else "Explain what is on this screen." }
        stop()
        val chosen = ConnectedAI.assistantBrain(this)
        brain = chosen
        val connected = chosen === ConnectedAI.brain
        val text = screen.text.take(if (connected) ScreenContexts.MAX_TEXT else ScreenContexts.NANO_TEXT)
        val image = screen.screenshot?.takeIf { chosen.seesImages(this) }
        val token = ++request
        this.question = question; reply = ""; answer.text = ""; actions.removeAllViews(); follows.removeAllViews()
        asked.text = shown ?: question; input.setText("")
        card.visibility = View.VISIBLE; showSuggestions(); updateSend()
        status.text = (if (screen.empty) "Thinking" else if (selection != null) "Reading the selection" else "Reading the screen") + " · " + modelLabel() + if (image != null) " · sees the screenshot" else ""
        val voice = if (aloud) (mouth ?: Mouth(this).also { mouth = it }).apply { begin(Ears(this@AssistantActivity).locale()) } else null
        val partial = { p: String ->
            if (token == request && !isFinishing) {
                reply = Prompts.reply(p); answer.text = Markdown.render(reply, dp(18), true); status.text = modelLabel()
                voice?.follow(Markdown.strip(reply, streaming = true))
            }
        }
        val done = { r: BrainResult ->
            if (token == request && !isFinishing) {
                brain = null; updateSend()
                if (r.ok) {
                    reply = Prompts.reply(r.text); answer.text = Markdown.render(reply, dp(18))
                    status.text = modelLabel()
                    voice?.finish(Markdown.strip(reply))
                    showActions()
                } else {
                    status.text = r.note ?: "No answer. Try again."
                    voice?.hush()
                }
            }
        }
        if (image != null) chosen.look(this, Task.ASSIST, text, question, image, partial, done)
        else chosen.run(this, Task.ASSIST, text, question, partial, done)
    }

    private fun showActions() {
        actions.removeAllViews(); follows.removeAllViews()
        fun add(icon: Int, description: String, tagName: String, run: () -> Unit) = actions.addView(icon(icon, description, tagName) { run() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        add(R.drawable.ic_copy, "Copy", "assistant-copy") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ægentica", Markdown.strip(reply)))
            toast("Copied. Paste it where you need it.")
        }
        add(R.drawable.ic_bookmark, "Save to notes", "assistant-save") {
            val app = appName(context().app)
            WorkspaceStore(this).use { it.save(Record(title = ("Assistant · " + question).take(120), body = Markdown.strip(reply),
                source = "Assistant · ${modelLabel()}" + if (app.isNotBlank()) " · about $app" else "")) }
            toast("Saved to Notes.")
        }
        add(R.drawable.ic_share_line, "Share", "assistant-share") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.strip(reply)), "Share answer")) }
        add(R.drawable.ic_volume, "Read aloud", "assistant-read") { (mouth ?: Mouth(this).also { mouth = it }).apply { begin(Ears(this@AssistantActivity).locale()); finish(Markdown.strip(reply)) } }
        if (!context().empty) add(R.drawable.ic_compose, "Chat about this on aegentica.se", "assistant-continue") { chatAbout() }
        if (Live.available() && ConnectedAI.geminiConfigured(this)) add(R.drawable.ic_waves, "Talk live about this screen", "assistant-live") {
            Live.pendingScreen = context().takeIf { !it.empty }
            startActivity(LiveActivity.intent(this, start = true)); finish()
        }
        if (editable) follows.addView(chipView("Replace selection", "assistant-replace", R.drawable.ic_arrow_up_right) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, Markdown.strip(reply))); finish()
        }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        AssistantActions.detect(reply).forEach { (title, intent) ->
            follows.addView(chipView(title, "assistant-follow", R.drawable.ic_arrow_up_right) { open(intent) }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("No app on this phone can open that.") }
    }

    /**
     * A new chat on aegentica.se with this screen (its text and a smaller copy of the screenshot)
     * or the selection in the message box. Nothing is sent there until the owner sends it.
     */
    private fun chatAbout() {
        val screen = context()
        if (screen.empty) return
        val app = appName(screen.app).ifBlank { null }
        val picked = selection != null
        val what = ShellActivity.Handed(
            kind = if (picked) "selection" else "screen",
            text = screen.text,
            title = (if (picked) "Selected text" else "My screen") + (app?.let { " in $it" } ?: ""),
            app = app,
            screenshot = if (picked) null else screen.screenshot?.let { ScreenContexts.dataUrl(it) })
        startActivity(ShellActivity.handOver(AegenticaActivity.intent(this), what))
        finish()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST); return }
        val e = ears ?: Ears(this).also { ears = it }
        if (!e.available()) { toast("Offline speech is not ready on this phone. Type instead, or set up voice in Settings."); return }
        stop()
        mic.setColorFilter(ink(R.color.listening)); input.hint = "Listening…"
        e.listen(onLevel = { mic.speechLevel(it) }, onPartial = { input.setText(it) },
            onFinal = { heard -> input.setText(heard); if (heard.isNotBlank()) ask(heard, true) },
            onStop = { problem -> mic.setColorFilter(null); mic.speechLevel(0f); input.hint = hint(); problem?.let { toast(it.message) } })
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == REQUEST && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
    }

    /** Leaving stops listening, speaking and generation; nothing continues out of sight. */
    private fun stop() {
        request++
        brain?.cancel(); brain = null
        ears?.cancel(); mic.speechLevel(0f); mic.setColorFilter(null)
        mouth?.hush()
        if (::send.isInitialized) updateSend()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onResume() { super.onResume(); NativePrivacy.apply(this, window) }
    override fun onPause() {
        if (brain != null) status.text = "Stopped when you left. Ask again to continue."
        stop()
        super.onPause()
    }
    /** Like the system assistant overlay, the panel closes once the owner leaves it. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
    override fun onDestroy() {
        stop(); unlisten?.invoke(); unlisten = null
        main.removeCallbacksAndMessages(null)
        mouth?.close(); mouth = null
        ScreenContexts.clear(invocation)
        super.onDestroy()
    }

    /** A scroll area that grows with the answer up to half the screen (460dp at most), keeping the bar in view. */
    private class CappedScroll(context: Context) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = minOf((resources.displayMetrics.heightPixels * 0.5f).toInt(), (460 * resources.displayMetrics.density).toInt())
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        const val DIRECT = "direct"
        /** Renders force the blurred or the plain sheet; null follows the device. */
        internal var blurForTest: Boolean? = null
        private const val REQUEST = 46
        val SUGGESTIONS = listOf("Explain this", "Summarize", "Fill in what's missing", "Draft a reply", "Translate to English")
        /** Chips for selected text: what the owner sees, then what the model is asked. */
        val SELECTION_SUGGESTIONS = listOf(
            "Translate" to "Translate this text. If it is in Swedish, translate it into English; otherwise translate it into Swedish. Reply with the translation only.",
            "Explain" to "Explain this in plain words.",
            "Summarize" to "Summarize this in a few short lines.",
            "Draft a reply" to "Draft a short, friendly reply to this, ready to send.",
            "Improve" to "Rewrite this so it reads clearly and naturally, keeping its meaning and language. Reply with the rewritten text only.")
        fun intent(context: Context, direct: Boolean = false) = Intent(context, AssistantActivity::class.java).putExtra(DIRECT, direct)

        /** How to make Ægentica the phone's assistant, with a shortcut to the right settings page. */
        fun setup(activity: Activity) {
            AlertDialog.Builder(activity).setTitle("Make Ægentica your phone's assistant")
                .setMessage("1. Open Default apps → Digital assistant app and choose Ægentica AI (this replaces Gemini there).\n" +
                    "2. On the same page, turn on Use text from screen and Use screenshot.\n" +
                    "3. Swipe up from a bottom corner, or hold the power button if it is set to Digital assistant, in any app.\n\n" +
                    "The screen is read only when you invoke the assistant. Answers come from Gemini Nano on the phone unless you chose your connected model for voice and the assistant in Connected AI; then the screen text and screenshot go to that provider.")
                .setNegativeButton("Not now", null)
                .setNeutralButton("Try without screen") { _, _ -> activity.startActivity(intent(activity, direct = true)) }
                .setPositiveButton("Open settings") { _, _ ->
                    val tries = listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_VOICE_INPUT_SETTINGS), Intent(Settings.ACTION_SETTINGS))
                    tries.firstOrNull { runCatching { activity.startActivity(it) }.isSuccess }
                }.showProtected(activity)
        }
    }
}

/** Follow-ups found in an answer. Each opens the target app's own screen; nothing runs by itself. */
object AssistantActions {
    private val PHONE = Regex("(?<![\\w+])\\+?\\d[\\d ()-]{6,18}\\d")
    private val LINK = Regex("https://[\\w.-]+(?:/[^\\s)\\]]*)?")
    fun detect(text: String): List<Pair<String, Intent>> {
        val found = mutableListOf<Pair<String, Intent>>()
        LINK.findAll(text).map { it.value.trimEnd('.', ',', ';') }.distinct().take(2).forEach { url ->
            found += "Open ${Uri.parse(url).host}" to Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        PHONE.findAll(LINK.replace(text, " ")).map { it.value.trim() }.filter { n -> n.count { it.isDigit() } in 7..15 }.distinct().take(2).forEach { number ->
            runCatching { NativeActions.dial(number) }.getOrNull()?.let { found += "Call $number" to it }
        }
        return found
    }
}
