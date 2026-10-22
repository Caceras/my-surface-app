package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private lateinit var asked: TextView
    private lateinit var input: EditText
    private lateinit var mic: ImageButton
    private lateinit var send: ImageButton
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
    private var invocation = 0

    private fun prefs() = getSharedPreferences("surfacelab", 0)
    /** The owner can leave the screen out of a question with one tap on the context chip. */
    private fun screenOn() = prefs().getBoolean("assistant_screen", true)

    /**
     * Frosted glass as in the owner's web ask bars: light fill, bright edge and a
     * soft wide shadow. The web bars blur what is behind them; Android cannot
     * blur another app behind one view, so the fill is nearly opaque to keep
     * answers legible over busy screens.
     */
    private fun View.glass(radius: Int) {
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor((ink(R.color.composer_bg) and 0x00FFFFFF) or (0xF2 shl 24))
            cornerRadius = dp(radius).toFloat(); setStroke(dp(1), ink(R.color.glass_outline))
        }
        elevation = dp(10).toFloat()
        if (android.os.Build.VERSION.SDK_INT >= 28) { outlineSpotShadowColor = 0x40000000; outlineAmbientShadowColor = 0x14000000 }
    }

    private fun textAction(title: String, tagName: String, action: () -> Unit) = label(title, 14f).apply {
        medium(); tag = tagName; gravity = Gravity.CENTER; minHeight = dp(44); padDp(12, 8, 12, 8)
        setTextColor(ink(R.color.accent_text)); isFocusable = true; buttonSemantics()
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ink(R.color.outline)), null, surface(R.color.chip_bg, 14))
        setOnClickListener { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); action() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // Answer card: the question, the streamed answer, the model, then quiet text actions.
        asked = label("", 14f).apply { medium(); tag = "assistant-question" }
        answer = label("", 16f).apply { tag = "assistant-answer"; setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1.06f); padDp(0, 6, 0, 0) }
        status = label("", 12f, true).apply { tag = "assistant-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; padDp(0, 8, 0, 2) }
        actions = LinearLayout(this).apply { tag = "assistant-actions"; gravity = Gravity.CENTER_VERTICAL }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; padDp(18, 16, 12, 10); tag = "assistant-card"; visibility = View.GONE; isClickable = true
            glass(24)
            addView(asked)
            addView(CappedScroll(this@AssistantActivity).apply { addView(answer) }, LinearLayout.LayoutParams(-1, -2))
            addView(status)
            addView(HorizontalScrollView(this@AssistantActivity).apply { isHorizontalScrollBarEnabled = false; addView(actions) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        // Suggestions panel, shown before the first question.
        suggestions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; padDp(6, 8, 6, 6); tag = "assistant-suggestions"; isClickable = true
            glass(24)
        }
        // The bar: context chip, prompt, microphone and a round send button.
        val signum = ImageView(this).apply { setImageResource(R.drawable.ic_surface); contentDescription = null; tag = "assistant-signum" }
        seen = label("", 13f).apply { tag = "assistant-seen"; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(ink(R.color.text_dim)) }
        chip = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; padDp(6, 4, 12, 4); tag = "assistant-screen"; isClickable = true; isFocusable = true; buttonSemantics()
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ink(R.color.outline)), surface(R.color.chip_bg, 18), null)
            addView(signum, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginStart = dp(2); marginEnd = dp(6) })
            addView(seen, LinearLayout.LayoutParams(-2, -2))
            setOnClickListener { chipTapped() }
        }
        input = EditText(this).apply {
            tag = "assistant-input"; hint = "Ask about this screen"; textSize = 16f; background = null; maxLines = 4
            setTextColor(ink(R.color.text_primary)); setHintTextColor(ink(R.color.text_dim)); padDp(10, 12, 4, 12)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { ask(text.toString(), false); true } else false }
            afterChange { updateSend(); showSuggestions() }
        }
        mic = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic); background = null; tag = "assistant-mic"; contentDescription = "Speak your question"
            minimumWidth = dp(44); minimumHeight = dp(44); val pad = dp(10); setPadding(pad, pad, pad, pad)
            setOnClickListener { listen() }
        }
        send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send); tag = "assistant-ask"; contentDescription = "Ask"
            background = surface(R.color.accent, 22); minimumWidth = dp(44); minimumHeight = dp(44); val pad = dp(10); setPadding(pad, pad, pad, pad)
            setOnClickListener { if (brain != null) { stop(); status.text = "Stopped." } else ask(input.text.toString(), false) }
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; padDp(7, 6, 6, 6); tag = "assistant-bar"; isClickable = true
            glass(28)
            addView(chip, LinearLayout.LayoutParams(-2, dp(40)).apply { width = LinearLayout.LayoutParams.WRAP_CONTENT })
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mic, LinearLayout.LayoutParams(-2, -2))
            addView(send, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(2) })
        }
        val column = CappedWidth(this).apply {
            orientation = LinearLayout.VERTICAL; tag = "assistant-sheet"
            addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(suggestions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            addView(bar, LinearLayout.LayoutParams(-1, -2))
        }
        val root = FrameLayout(this).apply {
            tag = "assistant-scrim"; isClickable = true; contentDescription = "Close the assistant"; setOnClickListener { finish() }
            // A soft shade at the bottom only: the app above stays readable, the glass stays legible.
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0x2E000000, 0x0F000000, 0x00000000))
            addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                setPadding(bars.left + dp(12), bars.top + dp(48), bars.right + dp(12), bars.bottom + dp(12)); insets
            }
        }
        setContentView(root)
        window.setDecorFitsSystemWindows(false)
        unlisten = ScreenContexts.listen { showContext() }
        start(intent)
    }

    private fun updateSend() {
        val busy = brain != null
        send.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = if (busy) "Stop" else "Ask"
        send.alpha = if (busy || input.text.isNotBlank() || !ScreenContexts.current.empty) 1f else 0.45f
    }

    private fun showSuggestions() {
        suggestions.removeAllViews()
        val visible = card.visibility != View.VISIBLE && input.text.isBlank()
        suggestions.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val app = appName(ScreenContexts.current.app)
        suggestions.addView(label(if (app.isNotBlank() && screenOn()) "Ask about $app" else "Ask Ægentica", 12f, true).apply { padDp(12, 2, 12, 6) })
        SUGGESTIONS.forEach { s ->
            suggestions.addView(label(s, 15f).apply {
                minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; padDp(12, 8, 12, 8); isFocusable = true; buttonSemantics()
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ink(R.color.outline)), null, surface(R.color.chip_bg, 14))
                setOnClickListener { input.setText(s); ask(s, false) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
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
        question = ""; reply = ""; answer.text = ""; asked.text = ""; actions.removeAllViews(); input.setText("")
        card.visibility = View.GONE
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
        card.visibility = View.VISIBLE; showSuggestions(); updateSend()
        status.text = "Thinking · " + modelLabel() + if (image != null) " · sees the screenshot" else ""
        val voice = if (aloud) (mouth ?: Mouth(this).also { mouth = it }).apply { begin(Ears(this@AssistantActivity).locale()) } else null
        val partial = { p: String ->
            if (token == request && !isFinishing) {
                reply = Prompts.reply(p); answer.text = Markdown.render(reply, dp(18), true)
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
        fun add(title: String, tagName: String, action: () -> Unit) = actions.addView(textAction(title, tagName, action), LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(2) })
        add("Copy", "assistant-copy") {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Ægentica", Markdown.strip(reply)))
            toast("Copied. Paste it where you need it.")
        }
        add("Save", "assistant-save") {
            val app = appName(ScreenContexts.current.app)
            WorkspaceStore(this).use { it.save(Record(title = ("Assistant · " + question).take(120), body = Markdown.strip(reply),
                source = "Assistant · ${modelLabel()}" + if (app.isNotBlank()) " · about $app" else "")) }
            toast("Saved to Notes.")
        }
        add("Share", "assistant-share") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Markdown.strip(reply)), "Share answer")) }
        add("Read aloud", "assistant-read") { (mouth ?: Mouth(this).also { mouth = it }).apply { begin(Ears(this@AssistantActivity).locale()); finish(Markdown.strip(reply)) } }
        if (Live.available() && ConnectedAI.geminiConfigured(this)) add("Talk live", "assistant-live") {
            Live.pendingScreen = ScreenContexts.current.takeIf { !it.empty && screenOn() }
            startActivity(LiveActivity.intent(this, start = true)); finish()
        }
        AssistantActions.detect(reply).forEach { (title, intent) -> add(title, "assistant-follow") { open(intent) } }
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
            onStop = { problem -> mic.setColorFilter(null); mic.speechLevel(0f); input.hint = "Ask about this screen"; problem?.let { toast(it.message) } })
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

    /** Full width on a phone, at most 560dp on a tablet or unfolded screen, like the owner's web ask bars. */
    private class CappedWidth(context: Context) : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = (560 * resources.displayMetrics.density).toInt()
            val width = MeasureSpec.getSize(widthSpec)
            super.onMeasure(if (width > cap) MeasureSpec.makeMeasureSpec(cap, MeasureSpec.EXACTLY) else widthSpec, heightSpec)
        }
    }

    /** A scroll area that grows with the answer up to part of the screen, keeping the question field in view. */
    private class CappedScroll(context: Context) : ScrollView(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val cap = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
        }
    }

    companion object {
        const val DIRECT = "direct"
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
