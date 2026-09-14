package com.caceras.surfacelab

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.app.StatusBarManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.provider.Settings
import android.widget.Switch
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A chat window against a model running on this phone.
 *
 * Everything the app can do lives behind one conversation: type or speak a
 * question, watch the answer arrive, hear it back if you asked out loud. The
 * other five surfaces still exist and still work; they are simply not what
 * this screen is about, so they sit behind "More" instead of filling it.
 *
 * Every dimension goes through dp(): setPadding takes pixels, and raw
 * numbers make the whole screen shrink as density rises.
 */
class MainActivity : Activity() {

    private lateinit var messages: LinearLayout
    private lateinit var blank: View
    private lateinit var transcript: ScrollView
    private lateinit var input: EditText
    private lateinit var openers: HorizontalScrollView
    private lateinit var send: ImageButton
    private lateinit var status: TextView
    private var mic: ImageButton? = null

    private val ears by lazy { Ears(this) }
    private var mouth: Mouth? = null

    /**
     * Every exchange on this screen, oldest first.
     *
     * Held here and on disk, because it is both what gets drawn and what
     * gets sent: a chat screen that shows the conversation but does not
     * send it is the bug this fixes, and one that sends it but forgets on
     * rotation is the same bug with extra steps.
     */
    private val history = mutableListOf<Turn>()

    /** True while the pending question came from the microphone. */
    private var askedAloud = false
    private var listening = false
    private var busy = false
    private var requestId = 0
    private var pendingQuestion = ""
    private var pendingAnswer: TextView? = null
    private var listenDraft = ""
    private var speakReplies = false
    private lateinit var playback: TextView

    /**
     * Set on the way out, and checked by every brain callback.
     *
     * SurfaceBrain.run takes no cancellation token, so its callbacks arrive
     * later whether or not this screen is still here -- landing text on a
     * dead view and saving an answer nobody is waiting for.
     */
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val brain = Brains.get()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
        }

        root.addView(header(brain), wide())
        root.addView(buildTranscript(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(buildOpeners(), wide())
        root.addView(composer(), wide())

        setContentView(root)
        root.padForSystemBars()

        brain.status(this) { if (!gone && !busy) status.text = it.label }

        restoreHistory()
        input.setText(savedInstanceState?.getString("draft") ?: Chat.draft(this))
        askedAloud = savedInstanceState?.getBoolean("aloud") ?: false
        if (savedInstanceState == null) acceptIntent(intent)
    }

    private fun restoreHistory() {
        history.clear()
        history.addAll(Chat.load(this))
        messages.removeAllViews()
        messages.addView(emptyState(), wide())
        history.forEach { turn ->
            addBubble(turn.you, fromUser = true)
            addBubble(turn.reply, fromUser = false).also {
                it.text = Markdown.render(turn.reply, dp(18))
                answerActions(it, turn.reply)
            }
        }
        showBlank(history.isEmpty())
        openers.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().trim()
            if (shared.isNotEmpty()) {
                // Sharing stages a draft, never sends material without review.
                val current = input.text.toString()
                input.setText(if (current.isBlank()) shared else "$current\n\n$shared")
                input.setSelection(input.length())
            }
        }
    }

    // ------------------------------------------------------------- chrome

    private fun header(brain: SurfaceBrain): View {
        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 20f
            setTextColor(color(R.color.text_primary))
        }

        status = TextView(this).apply {
            text = getString(R.string.working)
            textSize = 13f
            setTextColor(color(R.color.text_dim))
        }

        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(status)
        }

        val more = TextView(this).apply {
            text = getString(R.string.more)
            textSize = 14f
            setTextColor(color(R.color.text_dim))
            minHeight = dp(48)
            gravity = Gravity.CENTER
            isFocusable = true
            padDp(12, 8, 4, 8)
        }

        val panel = ScrollView(this).apply {
            tag = "settings"
            addView(morePanel(brain), wide())
            visibility = View.GONE
        }
        more.setOnClickListener {
            if (panel.visibility == View.GONE) {
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    .hideSoftInputFromWindow(input.windowToken, 0)
                input.clearFocus()
            }
            panel.visibility =
                if (panel.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        // Now that the screen remembers, it needs a way to stop remembering:
        // stale context makes later answers worse, not better.
        val fresh = TextView(this).apply {
            text = getString(R.string.new_chat)
            textSize = 14f
            setTextColor(color(R.color.text_dim))
            minHeight = dp(48)
            gravity = Gravity.CENTER
            isFocusable = true
            padDp(4, 8, 4, 8)
            setOnClickListener { newChat() }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titles, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(fresh)
            addView(more)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 14, 20, 6)
            addView(row, wide())
            addView(panel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(320), resources.displayMetrics.heightPixels / 3)
            ))
        }
    }

    /**
     * The template's own reference material: what this app registered with
     * the system, and the two buttons that act on it. Useful, and not what
     * you came to this screen for.
     */
    private fun morePanel(brain: SurfaceBrain): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(0, 10, 0, 4)
        }

        fun line(title: String, hint: String) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                padDp(0, 8, 0, 0)
            })
            panel.addView(TextView(this).apply {
                text = hint
                textSize = 13f
                setTextColor(color(R.color.text_dim))
            })
        }

        panel.addView(TextView(this).apply {
            text = getString(R.string.build_label, buildLabel())
            textSize = 13f
            setTextColor(color(R.color.text_dim))
        })

        panel.addView(flatButton(getString(R.string.prepare_model)) {
            status.text = getString(R.string.working)
            brain.prepare(this) { if (!gone) status.text = it.label }
        })

        speakReplies = Chat.speakReplies(this)
        panel.addView(Switch(this).apply {
            text = getString(R.string.speak_replies)
            isChecked = speakReplies
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                speakReplies = checked
                Chat.setSpeakReplies(this@MainActivity, checked)
                if (!checked) mouth?.hush()
            }
        })
        panel.addView(flatButton(getString(R.string.speech_language)) {
            AlertDialog.Builder(this).setTitle(R.string.speech_language)
                .setItems(arrayOf("System language", "English", "Svenska")) { _, which ->
                    ears.cancel()
                    listening = false
                    setMicActive(false)
                    updateSend()
                    mouth?.hush()
                    getSharedPreferences("surfacelab", MODE_PRIVATE).edit()
                        .putString("speech_language", arrayOf<String?>(null, "en-US", "sv-SE")[which]).apply()
                    status.text = getString(R.string.language_selected, ears.locale().displayLanguage)
                }.show()
        })
        panel.addView(flatButton(getString(R.string.voice_settings)) {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { status.text = getString(R.string.settings_unavailable) }
        })
        panel.addView(flatButton(getString(R.string.default_assistant)) {
            runCatching {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 2)
                else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
                .onFailure { status.text = getString(R.string.settings_unavailable) }
        })
        panel.addView(flatButton(getString(R.string.open_voice)) {
            startActivity(Intent(this, VoiceActivity::class.java))
        })
        line("Digital assistant", "Choose Pixel Surface Lab in Android settings to use the assistant gesture. Availability depends on your device settings.")
        line("Text selection", "Select text anywhere: " +
            brain.tasks.joinToString(", ") { it.alias })
        line("Quick Settings tile", "Shade, Edit tiles, or the button below.")
        line("Home screen widget", "Long-press home, Widgets. Shows the last answer.")
        line("App shortcuts", "Long-press the app icon in the launcher.")
        line("Share sheet", "Share any text into this app.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            panel.addView(flatButton(getString(R.string.add_tile)) {
                getSystemService(StatusBarManager::class.java)
                    .requestAddTileService(
                        ComponentName(this, SurfaceTileService::class.java),
                        getString(R.string.app_name),
                        Icon.createWithResource(this, R.drawable.ic_surface),
                        mainExecutor
                    ) { /* result code, ignored for a prototype */ }
            })
        }

        return panel
    }

    // ---------------------------------------------------------- the chat

    private fun buildTranscript(): View {
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(14, 6, 14, 6)
            // Messages sit at the bottom, against the composer, the way every
            // chat does. Top-aligned they floated above a screenful of empty
            // grey -- the single thing that made this look unfinished.
            gravity = Gravity.BOTTOM
            addView(emptyState(), wide())
        }
        transcript = ScrollView(this).apply {
            tag = "conversation"
            isFillViewport = true
            clipToPadding = false
            addView(messages, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // Tapping the conversation stops the answer being read aloud.
            // Not being able to shut it up is what makes a talking app feel
            // like an appliance.
            setOnClickListener { mouth?.hush() }
        }
        return transcript
    }

    /**
     * What the screen says before anyone has said anything.
     *
     * It used to say nothing: header, then eight hundred pixels of grey, then
     * the composer. An empty chat is the first thing anyone sees and it was
     * the least finished screen in the app.
     */
    private fun emptyState(): View {
        val hello = TextView(this).apply {
            text = getString(R.string.empty_title)
            textSize = 22f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(color(R.color.text_primary))
        }
        val why = TextView(this).apply {
            text = getString(R.string.empty_body)
            textSize = 15f
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.2f)
            setTextColor(color(R.color.text_dim))
            padDp(24, 8, 24, 0)
        }
        blank = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            padDp(0, 0, 0, 40)
            addView(hello, wide())
            addView(why, wide())
        }
        return blank
    }

    /** Centre the greeting when there is nothing else; otherwise sit low. */
    private fun showBlank(empty: Boolean) {
        blank.visibility = if (empty) View.VISIBLE else View.GONE
        messages.gravity = if (empty) Gravity.CENTER else Gravity.BOTTOM
    }

    private fun buildOpeners(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            padDp(14, 0, 14, 8)
        }
        Prompts.OPENERS.forEach { opener ->
            row.addView(chip(opener) {
                input.setText("$opener ")
                input.setSelection(input.text.length)
                input.requestFocus()
            })
        }
        openers = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        return openers
    }

    private fun composer(): View {
        input = EditText(this).apply {
            hint = getString(R.string.chat_hint)
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_dim))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 5
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, event ->
                val submit = actionId == EditorInfo.IME_ACTION_SEND ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.isCtrlPressed &&
                        event.action == KeyEvent.ACTION_DOWN)
                if (submit) submitDraft()
                submit
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (::send.isInitialized) updateSend()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            padDp(16, 12, 8, 12)
        }

        send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            background = getDrawable(R.drawable.send_bg)
            contentDescription = getString(R.string.send)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            setOnClickListener { if (busy) stopAnswer() else submitDraft() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            background = getDrawable(R.drawable.composer_bg)
            addView(input, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        // No on-device recogniser means no microphone at all, rather than a
        // button that quietly sends audio somewhere. See docs/voice.md.
        if (ears.available()) {
            val button = ImageButton(this).apply {
                setImageResource(R.drawable.ic_mic)
                background = null
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                contentDescription = getString(R.string.mic)
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { toggleListening() }
            }
            mic = button
            bar.addView(button)
        }
        bar.addView(send)
        playback = flatButton(getString(R.string.read_last)) {
            if (busy || mouth?.speaking() == true) {
                mouth?.hush()
                playback.text = getString(R.string.read_last)
            } else history.lastOrNull()?.let { readAloud(it.reply) }
        }
        playback.gravity = Gravity.CENTER
        playback.minHeight = dp(48)
        playback.visibility = if (Chat.load(this).isEmpty()) View.GONE else View.VISIBLE
        updateSend()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(12, 0, 12, 12)
            addView(bar, wide())
            addView(playback, wide())
        }
    }

    // ------------------------------------------------------------ asking

    private fun submitDraft() {
        if (listening || busy) return
        val aloud = askedAloud || speakReplies
        askedAloud = false
        ask(input.text.toString(), aloud)
    }

    private fun updateSend() {
        send.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send)
        send.contentDescription = getString(if (busy) R.string.stop_response else R.string.send)
        send.isEnabled = busy || (!listening && input.text.toString().isNotBlank())
        send.alpha = if (send.isEnabled) 1f else 0.4f
    }

    private fun stopAnswer() {
        if (!busy) return
        requestId++
        Brains.get().cancel()
        busy = false
        mouth?.hush()
        pendingAnswer?.append("\n\n" + getString(R.string.response_stopped))
        pendingAnswer = null
        if (input.text.isBlank()) input.setText(pendingQuestion)
        pendingQuestion = ""
        updateSend()
    }

    private fun ask(question: String, aloud: Boolean) {
        val text = question.trim()
        if (text.isEmpty() || busy) return

        val token = ++requestId
        busy = true
        playback.visibility = View.VISIBLE
        pendingQuestion = text
        input.setText("")
        Chat.saveDraft(this, "")
        updateSend()
        openers.visibility = View.GONE
        mouth?.hush()

        addBubble(text, fromUser = true)
        val answer = addBubble(getString(R.string.working), fromUser = false)
        pendingAnswer = answer

        if (aloud) {
            playback.text = getString(R.string.stop_speaking)
            speaker().begin(ears.locale())
        }

        val brain = Brains.get()
        brain.run(
            context = this,
            task = Task.ASK,
            input = "",
            instruction = Prompts.conversation(history, text),
            onPartial = { partial ->
                if (gone || token != requestId) return@run
                // Never paint the instruction. On a device that cannot take a
                // separate system prompt it is pasted above the question as
                // ordinary text, and a small model asked "??" recites it back.
                if (Prompts.isEcho(partial, Task.ASK)) return@run
                val atBottom = !transcript.canScrollVertically(1)
                answer.text = Markdown.render(Prompts.reply(partial), dp(18))
                if (aloud) mouth?.follow(Markdown.strip(Prompts.reply(partial)))
                if (atBottom) scrollToEnd()
            }
        ) { result ->
            if (gone || token != requestId) return@run
            busy = false
            pendingQuestion = ""
            pendingAnswer = null
            updateSend()
            val said = Prompts.reply(result.text)
            val echoed = result.ok && Prompts.isEcho(said, Task.ASK)
            val note = when {
                !result.ok || echoed -> null
                else -> Lang.caveat(Task.ASK, text)
                    ?: getString(R.string.truncated).takeIf { Prompts.looksTruncated(said) }
            }

            // Rendered, not raw. The streaming path above already rendered
            // each partial, and this line used to hand back the unrendered
            // string at the end -- so every finished answer on the phone
            // showed its own asterisks no matter what the partials did.
            answer.text = when {
                echoed -> Markdown.render(getString(R.string.echoed))
                result.ok && note == null -> Markdown.render(said, dp(18))
                result.ok -> Markdown.render(said + "\n\n" + note, dp(18))
                else -> Markdown.render(result.note ?: getString(R.string.failed))
            }
            if (result.ok && !echoed) {
                answerActions(answer, said)
                remember(Turn(text, said))
                ResultStore.save(this, Task.ASK, said)
                if (aloud) mouth?.finish(Markdown.strip(said))
            } else {
                mouth?.hush()
                if (input.text.isBlank()) input.setText(text)
                answer.setOnClickListener {
                    input.setText(text)
                    input.setSelection(input.length())
                    input.requestFocus()
                }
            }
            scrollToEnd()
        }
    }

    private fun remember(turn: Turn) {
        Chat.append(this, turn)
        history.clear()
        history.addAll(Chat.load(this))
    }

    /** Forget the conversation and start over, on screen and on disk. */
    private fun newChat() {
        stopAnswer()
        ears.cancel()
        listening = false
        askedAloud = false
        setMicActive(false)
        input.hint = getString(R.string.chat_hint)
        mouth?.hush()
        history.clear()
        Chat.clear(this)
        Chat.saveDraft(this, "")
        ResultStore.clear(this)
        playback.visibility = View.GONE
        messages.removeAllViews()
        messages.addView(emptyState(), wide())
        showBlank(true)
        openers.visibility = View.VISIBLE
        input.setText("")
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        showBlank(false)
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 16f
            background = getDrawable(
                if (fromUser) R.drawable.bubble_you else R.drawable.bubble_ai
            )
            setTextColor(color(
                if (fromUser) R.color.bubble_you_text else R.color.bubble_ai_text
            ))
            padDp(16, 12, 16, 12)
            // Tapping an answer stops it being spoken.
            if (!fromUser) setOnClickListener { mouth?.hush() }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(6)
            // A bubble that runs the full width stops reading as a bubble.
            if (fromUser) leftMargin = dp(48) else rightMargin = dp(48)
        }

        messages.addView(bubble, params)
        scrollToEnd()
        return bubble
    }

    private fun scrollToEnd() =
        transcript.post { transcript.fullScroll(ScrollView.FOCUS_DOWN) }

    // ----------------------------------------------------------- speaking

    private fun speaker(): Mouth = mouth ?: Mouth(this).also { voice ->
        mouth = voice
        voice.onProblem = { if (!gone) { status.text = it; playback.text = getString(R.string.read_last) } }
        voice.onIdle = { if (!gone) playback.text = getString(R.string.read_last) }
    }

    private fun readAloud(text: String) {
        stopAnswer()
        ears.cancel()
        listening = false
        setMicActive(false)
        updateSend()
        playback.text = getString(R.string.stop_speaking)
        speaker().apply { begin(ears.locale()); finish(Markdown.strip(text)) }
    }

    private fun answerActions(bubble: TextView, text: String) {
        // Keep the answer itself readable by TalkBack; actions are a hint.
        bubble.contentDescription = text + ". " + getString(R.string.answer_actions)
        bubble.isFocusable = true
        bubble.setOnLongClickListener {
            mouth?.hush()
            AlertDialog.Builder(this).setItems(arrayOf(
                getString(R.string.copy), getString(R.string.read_aloud), getString(R.string.share_answer)
            )) { _, which ->
                when (which) {
                    0 -> getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Answer", text))
                    1 -> readAloud(text)
                    2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share_answer)))
                }
            }.show()
            true
        }
    }

    private fun toggleListening() {
        if (listening) {
            ears.stop()
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        startListening()
    }

    private fun startListening() {
        stopAnswer()
        mouth?.hush()
        listenDraft = input.text.toString().trim()
        askedAloud = false
        listening = true
        updateSend()
        setMicActive(true)
        input.hint = getString(R.string.listening)

        ears.listen(
            onLevel = { level(it) },
            onPartial = { partial ->
                // Words appear as they are recognised, so the screen is
                // never blank while you are talking.
                input.setText(listOf(listenDraft, partial).filter { it.isNotBlank() }.joinToString(" "))
                input.setSelection(input.text.length)
            },
            onFinal = { text ->
                input.setText(listOf(listenDraft, text).filter { it.isNotBlank() }.joinToString(" "))
                input.setSelection(input.text.length)
                // The transcript stays editable: a misheard word is a fix,
                // not a redo. Send speaks the answer back, because this
                // question was asked out loud.
                askedAloud = true
            },
            onStop = { problem ->
                listening = false
                updateSend()
                setMicActive(false)
                input.hint = getString(R.string.chat_hint)
                if (problem != null) report(problem)
            }
        )
    }

    /**
     * The status line doubles as the voice status line -- there is only one,
     * and both answer the same question. The missing-language case is the
     * only speech failure with something to do about it, so it is the only
     * one that leaves something to tap.
     */
    private fun report(problem: VoiceProblem) {
        status.text = problem.message
        if (!problem.languageMissing || !ears.canFetchLanguage()) {
            status.setOnClickListener(null)
            return
        }
        status.text = problem.message + " " + getString(R.string.get_offline_speech)
        status.setOnClickListener {
            status.setOnClickListener(null)
            status.text = getString(R.string.working)
            ears.fetchLanguage { outcome -> if (!gone) status.text = outcome }
        }
    }

    private fun setMicActive(active: Boolean) {
        mic?.setColorFilter(
            color(if (active) R.color.listening else R.color.text_dim)
        )
        mic?.contentDescription = getString(if (active) R.string.finish_dictation else R.string.mic)
        if (!active) level(0f)
    }

    /**
     * The microphone button is the level meter. rmsdB runs from roughly -2 to
     * 10, and only the loud half of that is worth showing -- the point is
     * that the screen is never blank while you are talking.
     */
    private fun level(rms: Float) {
        val scale = 1f + (rms.coerceIn(0f, 10f) / 20f)
        mic?.scaleX = scale
        mic?.scaleY = scale
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            status.text = getString(R.string.mic_denied)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::messages.isInitialized && !busy && Chat.load(this) != history) {
            restoreHistory()
            playback.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
        }
        if (::input.isInitialized && input.text.isBlank() && Chat.draft(this).isNotBlank()) {
            input.setText(Chat.draft(this))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", input.text.toString().ifBlank { pendingQuestion })
        outState.putBoolean("aloud", askedAloud)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        super.onPause()
        // The microphone is not held across a trip to another app, and the
        // phone does not keep talking to an empty room.
        ears.cancel()
        listening = false
        input.hint = getString(R.string.chat_hint)
        setMicActive(false)
        stopAnswer()
        Chat.saveDraft(this, input.text.toString())
        mouth?.hush()
        updateSend()
    }

    override fun onDestroy() {
        gone = true
        ears.cancel()
        mouth?.close()
        mouth = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ helpers

    /**
     * Added without this, a child of a vertical LinearLayout defaults to
     * WRAP_CONTENT width -- so the header would not span the screen and the
     * "More" button would sit next to the title instead of opposite it.
     */
    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun color(id: Int) = resources.getColor(id, theme)

    /**
     * The version this APK was built from. On screen because the loop is
     * change it, push it, install it, look -- and that last step needs
     * something to look at.
     */
    private fun buildLabel(): String =
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: PackageManager.NameNotFoundException) {
            "unknown"
        }

    private fun flatButton(text: String, onTap: () -> Unit) =
        TextView(this).apply {
            this.text = text
            minHeight = dp(48)
            isFocusable = true
            textSize = 14f
            setTextColor(color(R.color.accent))
            padDp(0, 12, 0, 4)
            setOnClickListener { onTap() }
        }

    private fun chip(text: String, onTap: () -> Unit) =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(color(R.color.text_primary))
            background = getDrawable(R.drawable.chip_bg)
            padDp(14, 8, 14, 8)
            setOnClickListener { onTap() }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.rightMargin = dp(8)
            layoutParams = params
        }

    private companion object {
        const val MIC_REQUEST = 1
    }
}
