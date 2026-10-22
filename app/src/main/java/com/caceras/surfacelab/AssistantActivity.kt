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
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
import android.widget.FrameLayout
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
    private lateinit var suggestions: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var where: TextView
    private lateinit var halt: TextView
    private lateinit var asked: TextView
    private lateinit var input: EditText
    private lateinit var reopen: TextView
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
    private val sendFill = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var actions: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var unlisten: (() -> Unit)? = null
    private var brain: SurfaceBrain? = null
    private var ears: Ears? = null
    private var mouth: Mouth? = null
    private var request = 0
    private var question = ""
    private var reply = ""
    private var settled = false
    private var minimised = false
    private var invocation = 0

    private fun prefs() = getSharedPreferences("surfacelab", 0)
    /** The owner can leave the screen out of a question with one tap on the context chip. */
    private fun screenOn() = prefs().getBoolean("assistant_screen", true)

    // The look follows the owner's awesome-SEO ask bar: square glass panels in a
    // neutral ink palette, one round send button, compact type.
    private fun press() = ColorStateList.valueOf(ink(R.color.ask_press))
    private fun box(fill: Int, stroke: Int? = null) = GradientDrawable().apply { setColor(fill); stroke?.let { setStroke(dp(1), it) } }
    /** A 48dp touch target that draws only its middle 32dp, like the web bar's compact controls. */
    private fun compact(fill: Drawable?) = InsetDrawable(RippleDrawable(press(), fill, ColorDrawable(Color.WHITE)), 0, dp(8), 0, dp(8))

    /**
     * Strong glass: near-white fill, a hairline ring and a soft wide shadow. The
     * web bar blurs the page behind it; Android cannot blur another app behind
     * one view, so the fill is a little more opaque to keep answers legible.
     */
    private fun View.glass() {
        background = box(ink(R.color.ask_glass), ink(R.color.ask_ring))
        elevation = dp(12).toFloat()
        outlineSpotShadowColor = 0x33111114; outlineAmbientShadowColor = 0x0A111114
    }

    private fun icon(drawable: Int, description: String, tagName: String, action: () -> Unit) = ImageButton(this).apply {
        setImageResource(drawable); imageTintList = ColorStateList.valueOf(ink(R.color.ask_ink_3)); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = description; tag = tagName
        background = RippleDrawable(press(), null, ColorDrawable(Color.WHITE)); padDp(10, 12, 10, 12)
        setOnClickListener { action() }
    }

    private fun action(title: String, tagName: String, primary: Boolean, run: () -> Unit) = label(title, 12.5f).apply {
        medium(); tag = tagName; gravity = Gravity.CENTER; maxLines = 1; setLineSpacing(0f, 1f); isFocusable = true; buttonSemantics()
        setTextColor(ink(if (primary) R.color.ask_ink else R.color.ask_ink_2))
        background = compact(if (primary) box(ink(R.color.ask_surface), ink(R.color.ask_line_strong)) else null)
        val side = dp(if (primary) 12 else 10); setPadding(side, 0, side, 0)
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); run() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val metrics = resources.displayMetrics
        val column = minOf(metrics.widthPixels - dp(24), dp(680))
        // Conversation panel: where it was asked, then the question, the streamed answer and follow-ups.
        where = label("", 12.5f).apply { tag = "assistant-where"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(ink(R.color.ask_ink_3)) }
        halt = label("Stop", 12f).apply {
            medium(); tag = "assistant-halt"; gravity = Gravity.CENTER; setLineSpacing(0f, 1f); setTextColor(ink(R.color.ask_ink_2)); visibility = View.GONE
            val square = getDrawable(R.drawable.ic_stop)!!.mutate().apply { setTint(ink(R.color.ask_ink_2)); setBounds(0, 0, dp(18), dp(18)) }
            setCompoundDrawablesRelative(square, null, null, null); compoundDrawablePadding = dp(2)
            background = RippleDrawable(press(), null, ColorDrawable(Color.WHITE)); padDp(6, 0, 8, 0); buttonSemantics()
            setOnClickListener { stop(); status.text = "Stopped." }
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; padDp(16, 0, 6, 0)
            addView(where, LinearLayout.LayoutParams(0, -2, 1f))
            addView(halt, LinearLayout.LayoutParams(-2, dp(40)))
            addView(icon(R.drawable.ic_chevron_down, "Hide the answer", "assistant-minimise") { minimise(true) }, LinearLayout.LayoutParams(dp(36), dp(40)))
            addView(icon(R.drawable.ic_close, "Clear the answer", "assistant-close") { clearThread() }, LinearLayout.LayoutParams(dp(36), dp(40)))
        }
        asked = label("", 13.5f).apply {
            tag = "assistant-question"; setTextColor(ink(R.color.ask_ink)); background = box(ink(R.color.ask_sunken))
            padDp(14, 8, 14, 8); maxWidth = ((column - dp(32)) * 0.85f).toInt()
        }
        answer = label("", 14f).apply { tag = "assistant-answer"; setTextColor(ink(R.color.ask_ink_2)); setTextIsSelectable(true); setLineSpacing(0f, 1.38f); padDp(0, 12, 0, 0) }
        status = label("", 12.5f).apply { tag = "assistant-status"; setTextColor(ink(R.color.ask_ink_4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; padDp(0, 10, 0, 0) }
        actions = LinearLayout(this).apply { tag = "assistant-actions"; gravity = Gravity.CENTER_VERTICAL }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; padDp(16, 16, 16, 6)
            addView(asked, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            addView(answer, LinearLayout.LayoutParams(-1, -2))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(HorizontalScrollView(this@AssistantActivity).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-card"; visibility = View.GONE; isClickable = true
            glass()
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@AssistantActivity).apply { setBackgroundColor(ink(R.color.ask_divider)) }, LinearLayout.LayoutParams(-1, dp(1)))
            addView(CappedScroll(this@AssistantActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
        }
        // Suggestions panel, shown before the first question.
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-suggestions"; isClickable = true
            glass(); padDp(6, 6, 6, 6)
        }
        // The bar: context chip with the Æ mark, prompt, microphone and a round send button.
        val signum = ImageView(this).apply {
            setImageResource(R.drawable.ic_surface); imageTintList = ColorStateList.valueOf(ink(R.color.ask_on_ink)); contentDescription = null; tag = "assistant-signum"
            background = GradientDrawable().apply { setColor(ink(R.color.ask_ink)); cornerRadius = dp(4).toFloat() }; padDp(3, 3, 3, 3)
        }
        seen = label("", 12.5f).apply {
            tag = "assistant-seen"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setLineSpacing(0f, 1f); setTextColor(ink(R.color.ask_ink_2))
            maxWidth = (column * 0.46f).toInt() - dp(40)
        }
        chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "assistant-screen"; isClickable = true; isFocusable = true; buttonSemantics()
            background = compact(box(ink(R.color.ask_sunken))); padDp(6, 0, 10, 0)
            addView(signum, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(6) })
            addView(seen, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { chipTapped() }
        }
        input = EditText(this).apply {
            tag = "assistant-input"; hint = HINT; textSize = 16f; background = null; isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
            setTextColor(ink(R.color.ask_ink)); setHintTextColor(ink(R.color.ask_ink_4)); padDp(2, 0, 2, 0)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { ask(text.toString(), false); true } else false }
            afterChange { updateSend(); showSuggestions() }
        }
        reopen = label("Answer", 12f).apply {
            tag = "assistant-show"; gravity = Gravity.CENTER; setLineSpacing(0f, 1f); setTextColor(ink(R.color.ask_ink_3)); visibility = View.GONE
            contentDescription = "Show the answer"; background = compact(null); padDp(8, 0, 8, 0); buttonSemantics()
            setOnClickListener { minimise(false) }
        }
        mic = icon(R.drawable.ic_mic, "Speak your question", "assistant-mic") { listen() }.apply { padDp(10, 14, 10, 14) }
        send = ImageButton(this).apply {
            tag = "assistant-ask"; contentDescription = "Ask"; scaleType = ImageView.ScaleType.FIT_CENTER
            // A 36dp circle centred in a 44 x 48dp touch target.
            background = InsetDrawable(RippleDrawable(press(), sendFill, null), dp(4), dp(6), dp(4), dp(6))
            setOnClickListener { if (brain != null) { stop(); status.text = "Stopped." } else ask(input.text.toString(), false) }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; tag = "assistant-bar"; isClickable = true
            glass(); padDp(8, 0, 2, 0)
            addView(chip, LinearLayout.LayoutParams(-2, -1))
            addView(input, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            addView(reopen, LinearLayout.LayoutParams(-2, -1))
            addView(mic, LinearLayout.LayoutParams(dp(40), -1))
            addView(send, LinearLayout.LayoutParams(dp(44), -1))
        }
        val stack = CappedWidth(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-sheet"
            addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(suggestions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(bar, LinearLayout.LayoutParams(-1, dp(48)))
        }
        val root = FrameLayout(this).apply {
            tag = "assistant-scrim"; isClickable = true; contentDescription = "Close the assistant"; setOnClickListener { finish() }
            addView(stack, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { _, insets ->
                // Bars and keyboard: the glass stays above both, and the app above stays uncovered.
                val (left, top, right, bottom) = if (android.os.Build.VERSION.SDK_INT >= 30)
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime()).let { listOf(it.left, it.top, it.right, it.bottom) }
                else @Suppress("DEPRECATION") listOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                setPadding(left + dp(12), top + dp(48), right + dp(12), maxOf(dp(14), bottom + dp(8))); insets
            }
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else @Suppress("DEPRECATION") { root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION }
        unlisten = ScreenContexts.listen { showContext() }
        start(intent)
    }

    private fun updateSend() {
        val busy = brain != null
        val filled = busy || input.text.isNotBlank()
        send.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_arrow_up)
        send.imageTintList = ColorStateList.valueOf(ink(if (filled) R.color.ask_on_ink else R.color.ask_ink_4))
        sendFill.setColor(ink(if (filled) R.color.ask_ink else R.color.ask_sunken))
        // The arrow draws at 16dp, the stop square at 12dp.
        if (busy) send.padDp(10, 12, 10, 12) else send.padDp(14, 16, 14, 16)
        send.contentDescription = if (busy) "Stop" else "Ask"
        halt.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun showSuggestions() {
        suggestions.removeAllViews()
        val visible = question.isEmpty() && input.text.isBlank()
        suggestions.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val app = appName(ScreenContexts.current.app)
        suggestions.addView(label(if (app.isNotBlank() && screenOn()) "Ask about $app" else "Ask Ægentica", 12f).apply { setTextColor(ink(R.color.ask_ink_4)); padDp(12, 8, 12, 4) })
        SUGGESTIONS.forEach { s ->
            suggestions.addView(label(s, 13.5f).apply {
                setTextColor(ink(R.color.ask_ink_2)); setLineSpacing(0f, 1f); minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL; padDp(12, 8, 12, 8)
                isFocusable = true; buttonSemantics(); background = RippleDrawable(press(), null, ColorDrawable(Color.WHITE))
                setOnClickListener { input.setText(s); ask(s, false) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** The chevron tucks the answer away behind the bar; "Answer" in the bar brings it back. */
    private fun minimise(hide: Boolean) {
        minimised = hide && question.isNotEmpty()
        card.visibility = if (question.isNotEmpty() && !minimised) View.VISIBLE else View.GONE
        reopen.visibility = if (minimised) View.VISIBLE else View.GONE
    }

    /** The cross ends the current question and returns to the suggestions. */
    private fun clearThread() {
        stop()
        question = ""; reply = ""; answer.text = ""; asked.text = ""; status.text = ""; actions.removeAllViews()
        minimise(false); showSuggestions()
    }

    private fun chipTapped() {
        val c = ScreenContexts.current
        if (c.empty) { if (settled) setup(this); return }
        prefs().edit().putBoolean("assistant_screen", !screenOn()).apply()
        showContext()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); stop(); start(intent) }

    private fun start(intent: Intent?) {
        invocation = ScreenContexts.invocation
        settled = intent?.getBooleanExtra(DIRECT, false) == true
        if (!settled) main.postDelayed({ settled = true; showContext() }, 2500)
        question = ""; reply = ""; answer.text = ""; asked.text = ""; status.text = ""; actions.removeAllViews(); input.setText("")
        minimise(false)
        showContext()
    }

    private fun modelLabel(): String = if (ConnectedAI.forVoice(this)) ConnectedAI.label(this) else "Gemini Nano · on this phone"

    private fun appName(pkg: String): String = if (pkg.isBlank()) "" else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.substringAfterLast('.').replaceFirstChar(Char::uppercase) }

    /** The chip says what the next question will include. */
    private fun showContext() {
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
        chip.alpha = if (!c.empty && !on) 0.6f else 1f
        updateSend(); showSuggestions()
    }

    private fun ask(raw: String, aloud: Boolean) {
        val screen = ScreenContexts.current.takeIf { screenOn() } ?: ScreenContext()
        val question = raw.trim().ifEmpty { if (screen.empty) { toast("Type or speak a question."); return } else "Explain what is on this screen." }
        stop()
        val chosen = ConnectedAI.assistantBrain(this)
        brain = chosen
        val connected = chosen === ConnectedAI.brain
        val text = screen.text.take(if (connected) ScreenContexts.MAX_TEXT else ScreenContexts.NANO_TEXT)
        val image = screen.screenshot?.takeIf { chosen.seesImages(this) }
        val token = ++request
        this.question = question; reply = ""; answer.text = ""; actions.removeAllViews()
        asked.text = question; input.setText("")
        minimise(false); showSuggestions(); updateSend()
        where.text = if (!screen.empty) "Asked from " + appName(screen.app).ifBlank { "this screen" } else "Asked without the screen"
        status.text = (if (screen.empty) "Thinking" else "Reading the screen") + " · " + modelLabel() + if (image != null) " · sees the screenshot" else ""
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
        actions.removeAllViews()
        fun add(title: String, tagName: String, primary: Boolean = false, run: () -> Unit) =
            actions.addView(action(title, tagName, primary, run), LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        add("Copy", "assistant-copy", primary = true) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ægentica", Markdown.strip(reply)))
            toast("Copied. Paste it where you need it.")
        }
        add("Save to notes", "assistant-save", primary = true) {
            val app = appName(ScreenContexts.current.app)
            WorkspaceStore(this).use { it.save(Record(title = ("Assistant · " + question).take(120), body = Markdown.strip(reply),
                source = "Assistant · ${modelLabel()}" + if (app.isNotBlank()) " · about $app" else "")) }
            toast("Saved to Notes.")
        }
        AssistantActions.detect(reply).forEach { (title, intent) -> add(title, "assistant-follow") { open(intent) } }
        add("Share", "assistant-share") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.strip(reply)), "Share answer")) }
        add("Read aloud", "assistant-read") { (mouth ?: Mouth(this).also { mouth = it }).apply { begin(Ears(this@AssistantActivity).locale()); finish(Markdown.strip(reply)) } }
        if (Live.available() && ConnectedAI.geminiConfigured(this)) add("Talk live", "assistant-live") {
            Live.pendingScreen = ScreenContexts.current.takeIf { !it.empty && screenOn() }
            startActivity(LiveActivity.intent(this, start = true)); finish()
        }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) } catch (_: Exception) { toast("No app on this phone can open that.") }
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST); return }
        val e = ears ?: Ears(this).also { ears = it }
        if (!e.available()) { toast("Offline speech is not ready on this phone. Type instead, or set up voice in Settings."); return }
        stop()
        mic.setColorFilter(ink(R.color.listening)); input.hint = "Listening…"
        e.listen(onLevel = { mic.speechLevel(it) }, onPartial = { input.setText(it) },
            onFinal = { heard -> input.setText(heard); if (heard.isNotBlank()) ask(heard, true) },
            onStop = { problem -> mic.setColorFilter(null); mic.speechLevel(0f); input.hint = HINT; problem?.let { toast(it.message) } })
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

    /** Full width on a phone, at most 680dp on a tablet or unfolded screen, like the owner's web ask bar. */
    private class CappedWidth(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = (680 * resources.displayMetrics.density).toInt()
            val width = MeasureSpec.getSize(widthSpec)
            super.onMeasure(if (width > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthSpec, heightSpec)
        }
    }

    /** A scroll area that grows with the answer up to 56% of the screen (520dp at most), keeping the bar in view. */
    private class CappedScroll(context: Context) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = minOf((resources.displayMetrics.heightPixels * 0.56f).toInt(), (520 * resources.displayMetrics.density).toInt())
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        const val DIRECT = "direct"
        private const val HINT = "Ask about this screen"
        private const val REQUEST = 46
        val SUGGESTIONS = listOf("Explain this", "Summarize", "Fill in what's missing", "Draft a reply", "Translate to English")
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
