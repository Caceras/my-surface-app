package com.caceras.surfacelab

import android.Manifest
import android.app.Dialog
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
import android.widget.FrameLayout
import android.view.MotionEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A chat window against a model running on this phone.
 *
 * Everything the app can do lives behind one conversation: type or speak a
 * question, watch the answer arrive, hear it back if you asked out loud. The
 * Android entry points share this conversation, with setup in Settings.
 *
 * Every dimension goes through dp(): setPadding takes pixels, and raw
 * numbers make the whole screen shrink as density rises.
 */
class MainActivity : Activity() {

    private lateinit var messages: LinearLayout
    private lateinit var blank: View
    private lateinit var transcript: ScrollView
    private lateinit var input: EditText
    private lateinit var latest: TextView
    private var followReply = true
    private var scrollPosted = false
    private var restoring = false
    private lateinit var composeState: TextView
    private var thinkingMark: PresenceView? = null
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
    private var applyingDictation = false
    private var busy = false
    private var requestId = 0
    private var pendingQuestion = ""
    private var routineRun = ""
    private var pendingAnswer: TextView? = null
    private var listenDraft = ""
    private var speakReplies = false
    private lateinit var playback: TextView
    private var settingsDialog: Dialog? = null
    private var conversationsDialog: Dialog? = null
    private var actionsDialog: Dialog? = null
    private val streamed = StreamUpdates { text ->
        pendingAnswer?.let { Markdown.update(it, text, dp(18)) }
        scrollToEnd()
    }

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
        speakReplies = Chat.speakReplies(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
        }

        root.addView(header(brain), wide())
        root.addView(buildTranscript(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(composer(), wide())
        root.addView(workspaceNavigation("ai"), wide())

        setContentView(AdaptiveFrame(this, root).apply { padForSystemBars() })
        root.isFocusableInTouchMode = true
        root.requestFocus()
        readableSystemBars()

        brain.status(this) { if (!gone && !busy) status.text = if(ConnectedAI.enabled(this)) "Connected AI · " + ConnectedAI.host(this) else it.label }

        restoreHistory()
        askedAloud = savedInstanceState?.getBoolean("aloud") ?: false
        input.setText(savedInstanceState?.getString("draft") ?: Chat.draft(this))
        if (savedInstanceState == null) acceptIntent(intent)
    }

    private fun restoreHistory() {
        restoring = true
        followReply = true
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
        restoring = false
        latest.visibility = View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
    }

    private fun acceptIntent(intent: Intent?) {
        if(intent?.getBooleanExtra("workspaceDraft",false)==true) input.setText(Chat.draft(this))
        when (intent?.action) {
            NativeShortcuts.CAPTURE -> startActivity(WorkspaceActivity.intent(this,"library").putExtra("capture",true))
            NativeShortcuts.HISTORY -> input.post { if (!gone) showConversations() }
            NativeShortcuts.ACTIONS -> input.post { if (!gone) showActions() }
        }
        if (intent?.getBooleanExtra("settings", false) == true) input.post { if (!gone) showSettings(Brains.get()) }
        if (intent?.getBooleanExtra("capture", false) == true) startActivity(WorkspaceActivity.intent(this, "library").putExtra("capture", true))
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
        val compact = resources.configuration.screenWidthDp < 380 || resources.configuration.fontScale > 1.2f
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(getString(R.string.app_name), 23f).apply {
                tag = "brand-title"; medium(); letterSpacing = -0.035f; isAccessibilityHeading = true
            })
            status = label("On device", 11f, true).apply {
                tag = "assistant-status"
                maxLines = 2; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(status)
        }
        val actions = LinearLayout(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(pill(getString(R.string.new_chat)) { newChat() }.apply { tooltipText = "Save this conversation and start a new one" })
            addView(flatButton(getString(R.string.more)) { showSettings(brain) }.apply { padDp(12, 14, 0, 14) })
        }
        return LinearLayout(this).apply {
            tag = "chat-header"
            orientation = if (compact) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            padDp(20, 12, 20, 8)
            addView(titles, if (compact) wide() else LinearLayout.LayoutParams(0, -2, 1f))
            addView(actions, if (compact) wide() else LinearLayout.LayoutParams(-2, -2))
        }
    }

    private fun showSettings(brain: SurfaceBrain) {
        if (settingsDialog?.isShowing == true) return
        pauseForNavigation()
        val dialog = Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        settingsDialog = dialog
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
            padDp(24, 16, 24, 12)
            addView(sheetHeader("Settings") { dialog.dismiss() })
            addView(ScrollView(this@MainActivity).apply {
                tag = "settings"
                addView(morePanel(brain), wide())
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        dialog.setContentView(AdaptiveFrame(this, body).apply { padForSystemBars() })
        dialog.window?.setBackgroundDrawableResource(R.color.chat_bg)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        dialog.window?.let { readableSystemBars(it) }
    }

    /**
     * The template's own reference material: what this app registered with
     * the system, and the two buttons that act on it. Useful, and not what
     * you came to this screen for.
     */
    private fun morePanel(brain: SurfaceBrain): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(0, 18, 0, 24)
        }

        fun line(title: String, hint: String) {
            panel.addView(TextView(this).apply {
                text = title
                textSize = 17f
                medium()
                setTextColor(color(R.color.text_primary))
                padDp(0, 16, 0, 4)
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

        panel.addView(label("AI", 11f, true).apply { isAccessibilityHeading = true; letterSpacing = 0.12f; padDp(0, 22, 0, 8) })
        val modelState = label(status.text.toString(), 14f, true).apply {
            tag = "model-state"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(modelState, wide())
        panel.addView(label("AI can make mistakes. Check important answers.", 13f, true).apply { padDp(0, 8, 0, 0) })
        panel.addView(flatButton(getString(R.string.prepare_model)) {
            status.text = getString(R.string.working)
            modelState.text = status.text
            brain.prepare(this) { if (!gone) { status.text = it.label; modelState.text = it.label } }
        })

        speakReplies = Chat.speakReplies(this)
        panel.addView(preferenceSwitch(getString(R.string.speak_replies), speakReplies) { checked ->
            speakReplies = checked
            Chat.setSpeakReplies(this@MainActivity, checked)
            if (!checked) hushPlayback()
        })
        panel.addView(pill("Set up voice & test playback") {
            settingsDialog?.dismiss()
            startActivity(Intent(this, VoiceActivity::class.java).putExtra("setup", true))
        })
        panel.addView(flatButton(getString(R.string.voice_settings)) {
            runCatching { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                .onFailure { Toast.makeText(this, getString(R.string.settings_unavailable), Toast.LENGTH_LONG).show() }
        })
        panel.addView(flatButton(getString(R.string.default_assistant)) {
            runCatching {
                val roles = getSystemService(RoleManager::class.java)
                if (roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && !roles.isRoleHeld(RoleManager.ROLE_ASSISTANT))
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT), 2)
                else startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }
                .onFailure { Toast.makeText(this, getString(R.string.settings_unavailable), Toast.LENGTH_LONG).show() }
        })
        panel.addView(label("Privacy", 11f, true).apply { letterSpacing = 0.12f; padDp(0, 28, 0, 8); isAccessibilityHeading = true })
        panel.addView(preferenceSwitch("Show last answer on widget", NativePrivacy.widgetPreview(this)) { checked ->
            NativePrivacy.setWidgetPreview(this, checked)
        })
        panel.addView(label("Off by default. People who can see your home screen can read an enabled preview.", 13f, true))
        panel.addView(preferenceSwitch("Private screen", NativePrivacy.privateScreen(this)) { checked ->
            NativePrivacy.setPrivateScreen(this, checked)
            NativePrivacy.apply(this, window)
            NativePrivacy.apply(this, settingsDialog?.window)
        })
        panel.addView(label("Hide app previews and block screenshots or screen sharing. Widgets have their own setting above.", 13f, true))
        panel.addView(label("Shortcuts", 11f, true).apply { isAccessibilityHeading = true; letterSpacing = 0.12f; padDp(0, 28, 0, 4) })
        line("Home screen widget", "Type or Talk from your home screen.")
        panel.addView(flatButton("Add home screen widget") {
            val widgets = getSystemService(android.appwidget.AppWidgetManager::class.java)
            if (widgets.isRequestPinAppWidgetSupported) widgets.requestPinAppWidget(
                ComponentName(this, SurfaceWidgetProvider::class.java), null, null)
            else Toast.makeText(this, "Long-press your home screen, then choose Widgets.", Toast.LENGTH_LONG).show()
        })
        line("App shortcuts", "Long-press the app icon for Chat, Voice, History and Actions.")
        panel.addView(flatButton("Pin chat shortcut") { NativeShortcuts.pin(this, false) })
        if (ears.available()) panel.addView(flatButton("Pin voice shortcut") { NativeShortcuts.pin(this, true) })
        line("Quick Settings tile", "Add Voice to the shade, or use Android’s tile editor.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            panel.addView(flatButton(getString(R.string.add_tile)) {
                getSystemService(StatusBarManager::class.java)
                    .requestAddTileService(
                        ComponentName(this, SurfaceTileService::class.java),
                        getString(R.string.app_name),
                        Icon.createWithResource(this, R.drawable.ic_surface),
                        mainExecutor
                    ) { result ->
                        val message = when (result) {
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Voice tile added"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Voice tile is already available"
                            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Tile was not added. You can try again or use Android’s tile editor."
                            else -> "Could not add the tile. Try Android’s tile editor."
                        }
                        if (!gone) Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
            })
        }

        line("Share sheet & Text selection", "Choose Ægentica AI when sharing text, or select text in a supporting app for " + brain.tasks.joinToString(", ") { it.alias } + ".")

        panel.addView(flatButton("Workspace · notes, connections & backups") {
            startActivity(WorkspaceActivity.intent(this, "library"))
        })
        panel.addView(flatButton("Connected AI · optional") { ConnectedAI.settings(this) { updateSend(); status.text=if(ConnectedAI.enabled(this)) "Connected AI · " + ConnectedAI.host(this) else "Gemini Nano · On device" } })
        panel.addView(flatButton("AI sources") { KnowledgeContext.choose(this) { composeState.text=KnowledgeContext.label(this) } })
        panel.addView(label("Conversation", 11f, true).apply { isAccessibilityHeading = true; letterSpacing = 0.12f; padDp(0, 28, 0, 8) })
        panel.addView(label("Saved on this phone. Export before reinstalling to keep your conversation.", 14f, true))
        panel.addView(flatButton("Export conversation") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, "aegentica-conversation.json"), EXPORT_CHAT)
        })
        panel.addView(flatButton("Restore conversation") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json"), IMPORT_CHAT)
        })
        panel.addView(label("Help", 11f, true).apply { isAccessibilityHeading = true; letterSpacing = 0.12f; padDp(0, 28, 0, 8) })
        panel.addView(flatButton("Copy app info") {
            NativePrivacy.copy(this, "Ægentica AI app info", AppInfo.summary(this))
            Toast.makeText(this, "App info copied. Paste it with the steps that went wrong.", Toast.LENGTH_LONG).show()
        })
        panel.addView(label("Build, phone, Android and language only. No conversations or recordings.", 13f, true))

        return panel
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || requestCode !in listOf(EXPORT_CHAT, IMPORT_CHAT)) return
        val uri = data?.data ?: return
        val snapshot = if (requestCode == EXPORT_CHAT) {
            Chat.saveDraft(this, input.text.toString())
            try { Chat.backup(this) } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "Could not create the backup.", Toast.LENGTH_LONG).show()
                return
            }
        } else null
        Thread {
            try {
                if (snapshot != null) {
                    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the file.")
                    stream.bufferedWriter().use { it.write(snapshot) }
                    runOnUiThread { if (!gone) Toast.makeText(this, "Conversation exported", Toast.LENGTH_SHORT).show() }
                } else {
                    val stream = contentResolver.openInputStream(uri) ?: error("Could not open the file.")
                    val bytes = stream.use { source ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (out.size() <= Chat.MAX_BACKUP_BYTES) {
                            val count = source.read(buffer, 0, minOf(buffer.size, Chat.MAX_BACKUP_BYTES + 1 - out.size()))
                            if (count < 0) break
                            out.write(buffer, 0, count)
                        }
                        out.toByteArray()
                    }
                    require(bytes.size <= Chat.MAX_BACKUP_BYTES) { "This backup is too large." }
                    val backup = bytes.toString(Charsets.UTF_8)
                    val (turns, draft) = Chat.readBackup(backup)
                    runOnUiThread {
                        if (!gone) AlertDialog.Builder(this).setTitle("Restore conversation?")
                            .setMessage("Restore " + turns.size + " exchanges and any saved conversations in this backup? Your current chat will be saved in History.")
                            .setPositiveButton("Restore") { _, _ ->
                                newChat()
                                Chat.restoreArchives(this, backup)
                                Chat.save(this, turns)
                                Chat.saveDraft(this, draft)
                                restoreHistory()
                                input.setText(draft)
                                playback.visibility = View.GONE
                                turns.lastOrNull()?.let { ResultStore.save(this, Task.ASK, it.reply) }
                                settingsDialog?.dismiss()
                            }.setNegativeButton("Cancel", null).showProtected(this)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { if (!gone) Toast.makeText(this, e.message ?: "Could not read this backup.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------------------------------------------------------- the chat

    private fun buildTranscript(): View {
        messages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(20, 6, 20, 16)
            // Messages sit at the bottom, against the composer, the way every
            // chat does. Top-aligned they floated above a screenful of empty
            // grey -- the single thing that made this look unfinished.
            gravity = Gravity.BOTTOM
            addView(emptyState(), wide())
        }
        transcript = object : ScrollView(this) {
            // Text reflow and a newly focusable answer can scroll during layout,
            // independently of our streaming callbacks. Keep the reader anchored.
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                val preserve = !followReply
                val position = scrollY
                super.onLayout(changed, l, t, r, b)
                if (preserve) scrollTo(0, position)
            }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                val preserve = !followReply
                val position = scrollY
                super.onSizeChanged(w, h, oldw, oldh)
                if (preserve) scrollTo(0, position)
            }
        }.apply {
            tag = "conversation"
            isFillViewport = true
            clipToPadding = false
            addView(messages, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
            // Tapping the conversation stops the answer being read aloud.
            // Not being able to shut it up is what makes a talking app feel
            // like an appliance.
            setOnClickListener { hushPlayback() }
        }
        latest = pill("Latest reply") {
            followReply = true
            latest.visibility = View.GONE
            scrollToEnd(animated = true)
        }.apply {
            tag = "latest-reply"
            visibility = View.GONE
            elevation = dp(4).toFloat()
        }
        transcript.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                followReply = false
                latest.visibility = if (history.isNotEmpty() || busy) View.VISIBLE else View.GONE
            }
            false
        }
        transcript.setOnScrollChangeListener { _, _, y, _, oldY ->
            if (y < oldY) followReply = false
            // Use the position delivered by this callback, not an independently
            // sampled scrollY (test shadows can update it after the callback).
            val end = (messages.height - transcript.height + transcript.paddingTop + transcript.paddingBottom).coerceAtLeast(0)
            if (y >= end) followReply = true
            latest.visibility = if (followReply || (history.isEmpty() && !busy)) View.GONE else View.VISIBLE
        }
        return FrameLayout(this).apply {
            addView(transcript, FrameLayout.LayoutParams(-1, -1))
            addView(latest, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(12) })
        }
    }

    /** A quiet empty state: no slogan, subtitle or promotional starter cards. */
    private fun emptyState(): View {
        blank = LinearLayout(this).apply {
            tag = "welcome"
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            padDp(4, 24, 4, 24)
            addView(presence(48), LinearLayout.LayoutParams(dp(48), dp(48)).apply { bottomMargin = dp(16) })
            addView(label("Type a message or use Voice.", 15f, true).apply {
                gravity = Gravity.CENTER
            }, wide())
        }
        return blank
    }

    private fun stagePrompt(prompt: String) {
        input.setText(prompt)
        input.setSelection(input.length())
        input.requestFocus()
        input.post { getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    }

    /** Centre the greeting when there is nothing else; otherwise sit low. */
    private fun showBlank(empty: Boolean) {
        blank.visibility = if (empty) View.VISIBLE else View.GONE
        messages.gravity = if (empty) Gravity.CENTER else Gravity.BOTTOM
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
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
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
                    if(listening && !applyingDictation) {
                        listening=false; ears.cancel(); setMicActive(false); input.hint=getString(R.string.chat_hint)
                    }
                    if (::send.isInitialized) updateSend()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            padDp(18, 16, 12, 16)
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
            orientation = LinearLayout.VERTICAL
            padDp(4, 2, 6, 6)
            background = getDrawable(R.drawable.composer_bg)
            addView(input, wide())
        }

        val tools = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        composeState = label("On device", 12f, true).apply {
            tag = "compose-state"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            padDp(8, 0, 8, 0)
            setOnClickListener { pauseForNavigation(); KnowledgeContext.choose(this@MainActivity) { composeState.text=KnowledgeContext.label(this@MainActivity) } }; isFocusable=true; buttonSemantics()
        }
        if (ears.available()) {
            val button = ImageButton(this).apply {
                setImageResource(R.drawable.ic_mic)
                background = null
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                contentDescription = getString(R.string.mic)
                tooltipText = "Dictate into your draft, then review and send"
                val pad = dp(10)
                setPadding(pad, pad, pad, pad)
                setOnClickListener { toggleListening() }
            }
            mic = button
            tools.addView(button)
            composeState.text = "Dictate a message"
        }
        tools.addView(composeState, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(pill("Save") {
            val draft = input.text.toString()
            if (draft.isNotBlank()) {
                pauseForNavigation()
                val record = WorkspaceStore(this@MainActivity).use { it.save(Record(body=draft, title=draft.lineSequence().first().take(80))) }
                input.setText(""); Chat.saveDraft(this@MainActivity, "")
                startActivity(WorkspaceActivity.intent(this@MainActivity, "library", record.id))
            } else startActivity(WorkspaceActivity.intent(this@MainActivity, "library").putExtra("capture", true))
        }.apply { contentDescription="Save draft as a note"; padDp(10,10,10,10) })
        tools.addView(send)
        bar.addView(tools, wide())
        playback = flatButton(getString(R.string.stop_speaking)) { hushPlayback() }.apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            visibility = View.GONE
        }
        updateSend()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            padDp(16, 0, 16, 8)
            followKeyboardMotion()
            addView(bar, wide())
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(flatButton("Voice") {
                    startActivity(Intent(this@MainActivity, VoiceActivity::class.java))
                }.apply { tag = "voice-entry"; gravity = Gravity.CENTER; padDp(4, 12, 4, 12) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(flatButton("History") { showConversations() }.apply { tag = "history-entry"; contentDescription = "Saved conversations"; textSize = 15f; gravity = Gravity.CENTER; padDp(2, 12, 2, 12) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(flatButton("Actions") { showActions() }.apply { gravity = Gravity.CENTER; padDp(4, 12, 4, 12) }, LinearLayout.LayoutParams(0, -2, 1f))
            }, wide())
            addView(playback, wide())
        }
    }

    private fun hushPlayback() {
        mouth?.hush()
        playback.visibility = View.GONE
    }

    /** No microphone, speech or hidden stream continues behind a navigation sheet. */
    private fun pauseForNavigation() {
        stopAnswer()
        ears.cancel()
        listening = false
        setMicActive(false)
        hushPlayback()
        input.hint = getString(R.string.chat_hint)
        updateSend()
        Chat.saveDraft(this, input.text.toString())
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun showActions() {
        if (actionsDialog?.isShowing == true) return
        pauseForNavigation()
        actionsDialog = NativeActions.show(this, input.text.toString())
    }

    override fun onKeyShortcut(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (event.isCtrlPressed) when (keyCode) {
            android.view.KeyEvent.KEYCODE_ENTER -> { submitDraft(); return true }
            android.view.KeyEvent.KEYCODE_N -> { newChat(); return true }
            android.view.KeyEvent.KEYCODE_L -> { input.requestFocus(); getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(input, 0); return true }
            android.view.KeyEvent.KEYCODE_M -> if (event.isShiftPressed) { startActivity(Intent(this, VoiceActivity::class.java)); return true }
        }
        return super.onKeyShortcut(keyCode, event)
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<android.view.KeyboardShortcutGroup>, menu: android.view.Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
        val ctrl = android.view.KeyEvent.META_CTRL_ON
        data.add(android.view.KeyboardShortcutGroup(getString(R.string.app_name), listOf(
            android.view.KeyboardShortcutInfo("Send draft", android.view.KeyEvent.KEYCODE_ENTER, ctrl),
            android.view.KeyboardShortcutInfo("New conversation", android.view.KeyEvent.KEYCODE_N, ctrl),
            android.view.KeyboardShortcutInfo("Focus composer", android.view.KeyEvent.KEYCODE_L, ctrl),
            android.view.KeyboardShortcutInfo("Open voice", android.view.KeyEvent.KEYCODE_M, ctrl or android.view.KeyEvent.META_SHIFT_ON)
        )))
    }

    private fun showConversations() {
        if (conversationsDialog?.isShowing == true) return
        pauseForNavigation()
        conversationsDialog = ConversationSheet(this) { id ->
            stopAnswer()
            ears.cancel()
            listening = false
            setMicActive(false)
            hushPlayback()
            askedAloud = false
            input.hint = getString(R.string.chat_hint)
            Chat.saveDraft(this, input.text.toString())
            if (Chat.openArchive(this, id)) {
                restoreHistory()
                input.setText(Chat.draft(this))
                history.lastOrNull()?.let { ResultStore.save(this, Task.ASK, it.reply) }
                    ?: ResultStore.clear(this)
            }
        }.also { it.show() }
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
        if (::composeState.isInitialized && !listening) {
            composeState.text = if (busy) (if(ConnectedAI.enabled(this)) "Thinking · Connected AI" else "Thinking on your phone…") else if (askedAloud) "Review your words, then send" else KnowledgeContext.label(this)
        }
    }

    private fun stopAnswer() {
        if(routineRun.isNotBlank()) { KnowledgeContext.completeRoutine(this,routineRun,"","cancelled"); routineRun="" }
        streamed.cancel()
        if (!busy) return
        requestId++
        Brains.get().cancel()
        ConnectedAI.brain.cancel()
        busy = false
        thinkingMark?.let { it.show(PresenceView.Mode.REST); it.visibility = View.GONE }
        hushPlayback()
        pendingAnswer?.append("\n\n" + getString(R.string.response_stopped))
        pendingAnswer = null
        if (input.text.isBlank()) input.setText(pendingQuestion)
        pendingQuestion = ""
        updateSend()
    }

    private fun ask(question: String, aloud: Boolean) {
        val text = question.trim()
        if (text.isEmpty() || busy) return

        ReadingService.pauseForCapture()
        streamed.cancel()
        val token = ++requestId
        routineRun = KnowledgeContext.startRoutine(this)
        busy = true
        playback.visibility = View.GONE
        followReply = true
        pendingQuestion = text
        input.setText("")
        Chat.saveDraft(this, "")
        updateSend()
        hushPlayback()

        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
        addBubble(text, fromUser = true)
        thinkingMark = presence(28).also {
            it.show(PresenceView.Mode.THINKING)
            messages.addView(it, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(14) })
        }
        val answer = addBubble(getString(R.string.working), fromUser = false)
        pendingAnswer = answer

        if (aloud) {
            playback.text = getString(R.string.stop_speaking)
            playback.visibility = View.VISIBLE
            speaker().begin(ears.locale())
        }

        val brain = if (ConnectedAI.enabled(this)) ConnectedAI.brain else Brains.get()
        brain.run(
            context = this,
            task = Task.ASK,
            input = "",
            instruction = KnowledgeContext.prompt(this, Prompts.conversation(history, text)),
            onPartial = { partial ->
                if (gone || token != requestId) return@run
                // Never paint the instruction. On a device that cannot take a
                // separate system prompt it is pasted above the question as
                // ordinary text, and a small model asked "??" recites it back.
                if (Prompts.isEcho(partial, Task.ASK)) return@run
                thinkingMark?.let { mark -> mark.show(PresenceView.Mode.REST); mark.visibility = View.GONE }
                streamed.offer(Prompts.reply(partial))
                if (aloud) mouth?.follow(Markdown.strip(Prompts.reply(partial)))
            }
        ) { result ->
            if (gone || token != requestId) return@run
            streamed.cancel()
            busy = false
            thinkingMark?.let { it.show(PresenceView.Mode.REST); it.visibility = View.GONE }
            pendingQuestion = ""
            pendingAnswer = null
            updateSend()
            val said = Prompts.reply(result.text)
            val echoed = result.ok && Prompts.isEcho(said, Task.ASK)
            val note = when {
                !result.ok || echoed -> null
                else -> result.note
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
            if(!result.ok || echoed) { KnowledgeContext.completeRoutine(this,routineRun,"","failed"); routineRun="" }
            if (result.ok && !echoed) {
                answerActions(answer, said)
                remember(Turn(text, said))
                ResultStore.save(this, Task.ASK, said)
                KnowledgeContext.completeRoutine(this, routineRun, said, "completed"); routineRun=""
                if (aloud) mouth?.finish(Markdown.strip(said))
            } else {
                hushPlayback()
                if (input.text.isBlank()) input.setText(text)
                answer.setOnClickListener(null)
                messages.addView(LinearLayout(this).apply {
                    addView(pill("Edit question") { editQuestion(text) })
                }, messages.indexOfChild(answer) + 1, LinearLayout.LayoutParams(-2, -2))
            }
            scrollToEnd()
        }
    }

    private fun editQuestion(question: String) {
        val current = input.text.toString()
        if (current.isBlank() || current == question) stagePrompt(question)
        else AlertDialog.Builder(this).setTitle("Replace your draft?")
            .setMessage("You have a different message in the composer. Keep it, or replace it with this question.")
            .setNegativeButton("Keep draft", null)
            .setPositiveButton("Use question") { _, _ -> stagePrompt(question) }.showProtected(this)
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
        hushPlayback()
        WorkspaceStore(this).use { it.put("ai-context", ""); it.put("ai-routine", "") }
        val saved = Chat.archiveCurrent(this, input.text.toString())
        followReply = true
        latest.visibility = View.GONE
        history.clear()
        Chat.clear(this)
        Chat.saveDraft(this, "")
        ResultStore.clear(this)
        playback.visibility = View.GONE
        messages.removeAllViews()
        messages.addView(emptyState(), wide())
        showBlank(true)
        input.setText("")
        if (saved) Toast.makeText(this, "Saved in History", Toast.LENGTH_SHORT).show()
    }

    private fun addBubble(text: String, fromUser: Boolean): TextView {
        showBlank(false)
        val bubble = TextView(this).apply {
            tag = if (fromUser) "user-message" else "assistant-message"
            this.text = text
            textSize = 17f
            setLineSpacing(dp(3).toFloat(), 1.12f)
            background = getDrawable(
                if (fromUser) R.drawable.bubble_you else R.drawable.bubble_ai
            )
            setTextColor(color(
                if (fromUser) R.color.bubble_you_text else R.color.bubble_ai_text
            ))
            padDp(if (fromUser) 18 else 4, 14, if (fromUser) 18 else 4, 14)
            // Tapping an answer stops it being spoken.
            if (!fromUser) setOnClickListener { hushPlayback() }
        }

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(6)
            bottomMargin = dp(6)
            // A bubble that runs the full width stops reading as a bubble.
            if (fromUser) leftMargin = dp(42) else rightMargin = dp(8)
        }

        messages.addView(bubble, params)
        if (!restoring) bubble.arrive()
        scrollToEnd()
        return bubble
    }

    private fun scrollToEnd(animated: Boolean = false) {
        if (!followReply || scrollPosted) return
        scrollPosted = true
        transcript.post {
            scrollPosted = false
            if (!gone && followReply) {
                if (animated) transcript.smoothScrollTo(0, messages.height)
                else transcript.scrollTo(0, messages.height)
            }
        }
    }

    // ----------------------------------------------------------- speaking

    private fun speaker(): Mouth = mouth ?: Mouth(this).also { voice ->
        mouth = voice
        voice.onProblem = { if (!gone) { status.text = it; playback.visibility = View.GONE } }
        voice.onIdle = { if (!gone) playback.visibility = View.GONE }
    }

    private fun readAloud(text: String) {
        stopAnswer()
        ears.cancel()
        listening = false
        setMicActive(false)
        updateSend()
        ReadingService.start(this, text)
    }

    private fun answerActions(bubble: TextView, text: String) {
        // Keep the answer itself readable by TalkBack; actions are a hint.
        bubble.contentDescription = text + ". " + getString(R.string.answer_actions)
        bubble.isFocusable = true
        val parent = bubble.parent as? LinearLayout
        if (parent != null && parent.findViewWithTag<View>(bubble) == null) {
            parent.addView(LinearLayout(this).apply {
                tag = bubble
                orientation = if (resources.configuration.fontScale > 1.3f || resources.configuration.screenWidthDp < 360)
                    LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                addView(flatButton("Listen") { readAloud(text) }.apply { padDp(4, 10, 18, 10) })
                addView(flatButton("Copy") {
                    NativePrivacy.copy(this@MainActivity, "Answer", text)
                    Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                }.apply { padDp(4, 10, 18, 10) })
                addView(flatButton("Save") {
                    val question = history.lastOrNull { it.reply == text }?.you.orEmpty()
                    val saved = WorkspaceStore(this@MainActivity).use { it.save(Record(title=question.take(80), body=text, source="AI answer\nQuestion: $question")) }
                    startActivity(WorkspaceActivity.intent(this@MainActivity,"library",saved.id))
                }.apply { padDp(4,10,12,10) })
                addView(flatButton("Share") {
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share_answer)))
                }.apply { padDp(4, 10, 12, 10) })
            }, parent.indexOfChild(bubble) + 1)
        }
        bubble.setOnLongClickListener {
            hushPlayback()
            AlertDialog.Builder(this).setItems(arrayOf(
                getString(R.string.copy), getString(R.string.read_aloud), getString(R.string.share_answer)
            )) { _, which ->
                when (which) {
                    0 -> NativePrivacy.copy(this@MainActivity, "Answer", text)
                    1 -> readAloud(text)
                    2 -> startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                        .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), getString(R.string.share_answer)))
                }
            }.showProtected(this)
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
        ReadingService.pauseForCapture()
        stopAnswer()
        hushPlayback()
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
                applyingDictation=true
                input.setText(listOf(listenDraft, partial).filter { it.isNotBlank() }.joinToString(" "))
                input.setSelection(input.text.length)
                applyingDictation=false
            },
            onFinal = { text ->
                applyingDictation=true
                input.setText(listOf(listenDraft, text).filter { it.isNotBlank() }.joinToString(" "))
                input.setSelection(input.text.length)
                applyingDictation=false
                // The transcript stays editable: a misheard word is a fix,
                // not a redo. Send speaks the answer back, because this
                // question was asked out loud.
                askedAloud = true
                composeState.text = "Review your words, then send"
            },
            onStop = { problem ->
                listening = false
                updateSend()
                setMicActive(false)
                if (askedAloud) composeState.text = "Review your words, then send"
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
        AlertDialog.Builder(this).setTitle("Voice setup")
            .setMessage(problem.message + " Your draft is safe. Open voice setup to choose a language, download speech or test the speaker.")
            .setPositiveButton("Voice setup") { _, _ ->
                startActivity(Intent(this, VoiceActivity::class.java).putExtra("setup", true))
            }.setNegativeButton("Keep typing", null).showProtected(this)
    }

    private fun setMicActive(active: Boolean) {
        mic?.setColorFilter(
            color(if (active) R.color.listening else R.color.text_dim)
        )
        mic?.contentDescription = getString(if (active) R.string.finish_dictation else R.string.mic)
        if (::composeState.isInitialized) composeState.text = if (active) "Listening… tap mic to finish" else "Dictate a message"
        if (!active) level(0f)
    }

    /**
     * The microphone button is the level meter. rmsdB runs from roughly -2 to
     * 10, and only the loud half of that is worth showing -- the point is
     * that the screen is never blank while you are talking.
     */
    private fun level(rms: Float) {
        mic?.speechLevel(rms)
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
        NativePrivacy.apply(this, window)
        Reminders.reschedule(this)
        if (::messages.isInitialized && !busy && Chat.load(this) != history) {
            restoreHistory()
            playback.visibility = View.GONE
        }
        if (::composeState.isInitialized) composeState.text = KnowledgeContext.label(this)
        if (ConnectedAI.enabled(this) && ::status.isInitialized) status.text = "Connected AI · " + ConnectedAI.host(this)
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
        hushPlayback()
        updateSend()
    }

    override fun onDestroy() {
        gone = true
        settingsDialog?.dismiss()
        conversationsDialog?.dismiss()
        actionsDialog?.dismiss()
        streamed.cancel()
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
            buttonSemantics()
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(color(R.color.outline)), null, surface(R.color.chip_bg, 16))
            textSize = 15f
            medium()
            setTextColor(color(R.color.accent_text))
            padDp(0, 12, 0, 4)
            setOnClickListener { onTap() }
        }

    private companion object {
        const val MIC_REQUEST = 1
        const val EXPORT_CHAT = 10
        const val IMPORT_CHAT = 11
    }
}
