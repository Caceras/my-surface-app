package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Switch
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The hands-free surface: open it, talk, hear the answer.
 *
 * Reached from the Quick Settings tile, an app shortcut and the widget --
 * places that have no keyboard and, in the tile's case, no way to ask for a
 * permission either. That is the whole reason this class exists rather than
 * the entry points opening the chat screen.
 *
 * Silence is the send button here, and only here. There is no screen worth
 * looking at, so onResults goes straight into the brain: one tap in total, at
 * the start. The launcher and the selection dialog put the transcript in the
 * box you were already looking at instead, because there the promise is that
 * a misheard word is a fix rather than a redo.
 *
 * Five states, one enum, no settings. See docs/voice.md.
 */
class VoiceActivity : Activity() {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private lateinit var card: View
    private lateinit var dot: PresenceView
    private lateinit var status: TextView
    private lateinit var guidance: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var action: TextView
    private lateinit var scroller: ReadingScrollView
    private lateinit var language: TextView
    private var setupMode = false
    private val streamed = StreamUpdates { text ->
        answer.text = Markdown.render(text, dp(18))
        scrollToEnd()
    }
    private lateinit var setupTools: LinearLayout

    private val ears by lazy { Ears(this) }
    private var mouth: Mouth? = null

    private var state = State.IDLE
        set(value) {
            field = value
            if (::guidance.isInitialized) {
                guidance.isClickable = value == State.SPEAKING
                guidance.isFocusable = value == State.SPEAKING
                guidance.setTextColor(color(if (value == State.SPEAKING) R.color.accent else R.color.text_dim))
                guidance.background = if (value == State.SPEAKING) surface(R.color.chip_bg, 24) else null
            }
            if (::dot.isInitialized) dot.show(when (value) {
                State.IDLE -> PresenceView.Mode.REST
                State.LISTENING -> PresenceView.Mode.LISTENING
                State.THINKING -> PresenceView.Mode.THINKING
                State.SPEAKING -> PresenceView.Mode.SPEAKING
            })
        }

    /**
     * Set on the way out, and checked by every brain callback.
     *
     * SurfaceBrain.run returns nothing and takes no token, so it cannot be
     * cancelled: the callbacks arrive later whether or not anyone is still
     * here. Without this flag a chunk lands on a dead view, speak() is called
     * on an engine already shut down, and an answer the user walked away from
     * is saved to the home screen widget.
     */
    private var gone = false

    private var resumed = false
    private var requestId = 0
    private var generating = false
    private var lastQuestion = ""
    private var speechProblem: String? = null
    private var continuous = false
    private lateinit var continuousSwitch: Switch
    private val main = Handler(Looper.getMainLooper())
    private val nextListen = Runnable {
        if (resumed && continuous && !generating && state == State.IDLE) requestListen()
    }

    /**
     * A microphone that should start as soon as this screen is in front.
     *
     * onRequestPermissionsResult can arrive either side of onResume depending
     * on the version, and here that is not a small thing: on a screen that is
     * nothing but a microphone, starting one that onPause then immediately
     * cancels leaves a dead screen. The other two microphones in the app are
     * buttons you can simply tap again.
     */
    private var pendingListen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
        readableSystemBars()
        setupMode = intent.getBooleanExtra("setup", false)

        when {
            setupMode -> showSetup()
            // Opening on an honest status beats opening on a microphone that
            // cannot be used. A shortcut promising "tap, talk" on a phone
            // with no on-device recogniser is worse than no shortcut.
            !ears.available() -> {
                state = State.IDLE
                status.text = "Voice needs setup"
                answer.text = getString(R.string.voice_unavailable)
                setupTools.visibility = View.VISIBLE
                continuousSwitch.visibility = View.GONE
                dot.visibility = View.VISIBLE
                action.visibility = View.GONE
            }
            granted() -> pendingListen = true
            else -> {
                status.text = "Your voice, your space"
                dot.visibility = View.VISIBLE
                answer.text = "Allow microphone access to speak. Audio is transcribed on your phone. You can also choose Type below."
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            }
        }
    }

    // ------------------------------------------------------------- chrome

    private fun build(): View {
        status = label("Ready when you are", 28f).apply {
            gravity = Gravity.CENTER
            letterSpacing = -0.03f
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        dot = presence(96).apply { visibility = View.INVISIBLE }
        heard = label("", 23f).apply { padDp(4, 20, 4, 4) }
        answer = label("", 17f, true).apply { padDp(4, 12, 4, 20); setLineSpacing(dp(3).toFloat(), 1.15f) }
        scroller = ReadingScrollView(this).apply {
            tag = "voice-transcript"
            addView(LinearLayout(this@VoiceActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(heard, wide())
                addView(answer, wide())
                setupTools = LinearLayout(this@VoiceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = View.GONE
                    language = pill("Choose speaking language") { chooseLanguage() }
                    addView(language, wide().apply { bottomMargin = dp(8) })
                    addView(pill("Download offline speech") { downloadLanguage() }, wide().apply { bottomMargin = dp(8) })
                    addView(pill("Test speaker") { testSpeaker() }, wide().apply { bottomMargin = dp(8) })
                    addView(label("Android settings", 14f).apply {
                        minHeight = dp(48)
                        gravity = Gravity.CENTER
                        isFocusable = true
                        setOnClickListener { voiceOptions() }
                    }, wide())
                }
                addView(setupTools, wide())
            }, wide())
        }
        action = pill(getString(R.string.talk_again), true) { requestListen() }
        continuousSwitch = Switch(this).apply {
            text = getString(R.string.keep_talking)
            minHeight = dp(52)
            setTextColor(color(R.color.text_primary))
            setOnCheckedChangeListener { _, checked ->
                continuous = checked
                if (!checked) main.removeCallbacks(nextListen)
            }
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.chat_bg))
            padDp(24, 12, 24, 12)
            addView(LinearLayout(this@VoiceActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label("Surface / voice", 18f).apply { medium() }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(pill(getString(R.string.close)) { finish() })
            }, wide())
            addView(dot, LinearLayout.LayoutParams(dp(96), dp(96)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(28)
                bottomMargin = dp(24)
            })
            addView(status, wide())
            guidance = label("Speak, pause, and hear a reply.", 14f, true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                setOnClickListener { if (state == State.SPEAKING) quietVoice() }
                padDp(0, 10, 0, 10)
            }
            addView(guidance, wide())
            addView(android.widget.FrameLayout(this@VoiceActivity).apply {
                addView(scroller, android.widget.FrameLayout.LayoutParams(-1, -1))
                val latest = pill("Latest reply") { scroller.latest() }.apply {
                    tag = "voice-latest"
                    visibility = View.GONE
                    elevation = dp(4).toFloat()
                }
                addView(latest, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                scroller.onFollowingChanged = { latest.visibility = if (it) View.GONE else View.VISIBLE }
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(continuousSwitch, wide())
            addView(action, wide().apply { bottomMargin = dp(10) })
            addView(LinearLayout(this@VoiceActivity).apply {
                addView(pill(getString(R.string.type_instead)) {
                    startActivity(Intent(this@VoiceActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
                addView(pill("Voice setup") { stopForSetup(); showSetup() }, LinearLayout.LayoutParams(0, -2, 1f))
            }, wide())
            addView(label("On-device speech · Microphone stops when you leave", 11f, true).apply {
                gravity = Gravity.CENTER
                padDp(0, 14, 0, 4)
            }, wide())
            padForSystemBars()
        }
        return card
    }

    private fun stopForSetup() {
        main.removeCallbacks(nextListen)
        continuousSwitch.isChecked = false
        pendingListen = false
        streamed.cancel()
        requestId++
        if (generating) {
            Brains.get().cancel()
            if (Chat.draft(this).isBlank()) Chat.saveDraft(this, lastQuestion)
        }
        generating = false
        ears.cancel()
        mouth?.hush()
        state = State.IDLE
    }

    private fun showSetup() {
        setupMode = true
        dot.visibility = View.VISIBLE
        status.text = "Make yourself heard"
        guidance.text = "A one-time setup. A more natural conversation."
        heard.text = "A quick voice check"
        heard.textSize = 20f
        answer.text = "Choose your language, prepare offline speech, then test the speaker. Initial downloads need a connection."
        continuousSwitch.visibility = View.GONE
        idleAction()
        setupTools.visibility = View.VISIBLE
    }

    private fun testSpeaker() {
        stopForSetup()
        status.text = "Testing your speaker"
        val voice = speaker()
        voice.onProblem = { if (!gone) {
            status.text = "Speaker needs setup"
            answer.text = it + " Tap Voice setup, then Android settings to install a playback voice."
            action.text = "Android speech settings"
            action.setOnClickListener { voiceOptions() }
        } }
        voice.onIdle = { if (!gone && status.text == "Testing your speaker") idle() }
        voice.begin(ears.locale())
        voice.finish(if (ears.locale().language == "sv") "Hej! Jag är redo att hjälpa dig." else "Hello. I am ready to help. This voice is running on your phone.")
    }

    private fun voiceOptions() {
        AlertDialog.Builder(this).setTitle("Voice · " + ears.locale().displayName)
            .setItems(arrayOf("Choose language", "Download offline speech", "Test speaker", "Android speech settings", "Talk now")) { _, which ->
                when (which) {
                    0 -> chooseLanguage()
                    1 -> downloadLanguage()
                    2 -> testSpeaker()
                    3 -> AlertDialog.Builder(this).setTitle("Android speech settings")
                        .setItems(arrayOf("Spoken replies", "Voice input", "Microphone permission")) { _, option ->
                            val target = when (option) {
                                0 -> Intent("com.android.settings.TTS_SETTINGS")
                                1 -> Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                else -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:$packageName"))
                            }
                            runCatching { startActivity(target) }.onFailure { answer.text = getString(R.string.settings_unavailable) }
                        }.show()
                    4 -> requestListen()
                }
            }.setNegativeButton("Done", null).show()
    }

    private fun chooseLanguage() {
        val tags = arrayOf("", "en-US", "en-GB", "sv-SE", "es-ES", "fr-FR", "de-DE", "it-IT", "ja-JP", "ko-KR")
        val names = tags.map { if (it.isBlank()) "System language" else java.util.Locale.forLanguageTag(it).displayName }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Speaking language")
            .setItems(names) { _, which ->
                stopForSetup()
                getSharedPreferences("surfacelab", MODE_PRIVATE).edit().putString("speech_language", tags[which].takeIf { it.isNotBlank() }).apply()
                language.text = ears.locale().displayName
                status.text = "Language selected"
                answer.text = names[which] + ". Download offline speech if it is not installed, then tap Talk."
                idleAction()
                AlertDialog.Builder(this).setTitle(names[which])
                    .setMessage("Prepare offline speech for this language? The initial download needs an internet connection.")
                    .setPositiveButton("Download") { _, _ -> downloadLanguage() }
                    .setNegativeButton("Try talking") { _, _ -> requestListen() }.show()
            }.setNegativeButton("Cancel", null).show()
    }

    private fun downloadLanguage() {
        stopForSetup()
        status.text = "Preparing offline speech"
        answer.text = "Keep an internet connection for the initial download. You can type while Android prepares the language."
        action.visibility = View.GONE
        ears.fetchLanguage { outcome ->
            if (!gone && resumed) {
                answer.text = outcome
                status.text = "Voice setup"
                idleAction()
            }
        }
    }

    // ---------------------------------------------------------- listening

    private fun granted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestListen() {
        if (!granted()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
        } else if (resumed) listen() else pendingListen = true
    }

    private fun listen() {
        if (gone || !resumed || !granted() || !ears.available() || generating) return
        ears.cancel()
        mouth?.hush()

        setupMode = false
        setupTools.visibility = View.GONE
        continuousSwitch.visibility = View.VISIBLE
        heard.textSize = 23f
        guidance.text = "Pause to send. Tap Finish speaking when you’re done."
        state = State.LISTENING
        status.text = getString(R.string.listening_hint)
        heard.text = ""
        answer.text = ""
        dot.visibility = View.VISIBLE
        action.visibility = View.VISIBLE
        action.text = "Finish speaking"
        action.setOnClickListener { ears.stop() }

        ears.listen(
            onLevel = { level(it) },
            onPartial = { partial -> heard.text = partial },
            onFinal = { spoken -> ask(spoken) },
            onStop = { problem -> stopped(problem) }
        )
    }

    /** rmsdB runs from roughly -2 to 10, and only the loud half is useful. */
    private fun level(rms: Float) = dot.level(rms)

    private fun stopped(problem: VoiceProblem?) {
        dot.scaleX = 1f
        dot.scaleY = 1f

        if (problem == null) {
            // Saying nothing is the ordinary way a session ends, not an
            // error. Back to idle, showing nothing, unless the answer is
            // already on its way.
            if (state == State.LISTENING) { continuousSwitch.isChecked = false; idle() }
            return
        }

        continuousSwitch.isChecked = false
        state = State.IDLE
        dot.visibility = View.VISIBLE
        setupMode = true
        continuousSwitch.visibility = View.GONE
        setupTools.visibility = View.VISIBLE
        status.text = "Let’s get voice ready"
        guidance.text = "You can keep typing while voice gets ready."
        val nextStep = if (ears.locale().language == "en" && ears.locale().toLanguageTag() != "en-US")
            "Try English (United States), or choose your speaking language below."
            else "Download the language below, or choose another supported speaking language."
        answer.text = problem.message + "\n\n" + nextStep

        if (problem.languageMissing && ears.canFetchLanguage()) {
            // The recogniser is here, the language pack is not. That is the
            // one speech failure with something to do about it -- and it is
            // a download, so it is offered rather than started.
            action.text = getString(R.string.get_offline_speech)
            action.visibility = View.VISIBLE
            action.setOnClickListener {
                downloadLanguage()
            }
        } else {
            idleAction()
        }
    }

    private fun idle() = idleWith(getString(R.string.tap_to_talk))

    private fun idleWith(line: String) {
        state = State.IDLE
        dot.visibility = View.VISIBLE
        status.text = line
        guidance.text = "Pick up whenever you’re ready."
        idleAction()
    }

    private fun idleAction() {
        action.text = getString(R.string.talk_again)
        action.visibility = if (ears.available()) View.VISIBLE else View.GONE
        action.setOnClickListener { requestListen() }
    }

    // ------------------------------------------------------------- asking

    private fun ask(spoken: String) {
        if (gone || !resumed || generating || spoken.isBlank()) return
        streamed.cancel()
        val token = ++requestId
        generating = true
        lastQuestion = spoken
        speechProblem = null

        scroller.latest()
        heard.text = spoken
        state = State.THINKING
        status.text = getString(R.string.working)
        guidance.text = "Thinking it through, on your phone."
        dot.visibility = View.VISIBLE

        action.text = getString(R.string.stop_response)
        action.visibility = View.VISIBLE
        action.setOnClickListener {
            streamed.cancel()
            requestId++
            Brains.get().cancel()
            if (Chat.draft(this).isBlank()) Chat.saveDraft(this, lastQuestion)
            generating = false
            continuousSwitch.isChecked = false
            mouth?.hush()
            answer.append("\n\n" + getString(R.string.response_stopped))
            idle()
        }
        val voice = speaker()
        voice.onIdle = {
            if (!gone && !generating && state == State.SPEAKING) {
                idleWith(speechProblem ?: getString(R.string.tap_to_talk))
                if (continuous && speechProblem == null) {
                    main.removeCallbacks(nextListen)
                    main.postDelayed(nextListen, 500)
                }
            }
        }
        voice.onProblem = { problem ->
            speechProblem = problem
            if (!gone) status.text = problem
        }
        voice.begin(ears.locale())

        Brains.get().run(
            context = this,
            task = Task.ASK,
            input = "",
            instruction = Prompts.conversation(Chat.load(this), spoken),
            onPartial = { partial ->
                if (!gone && resumed && token == requestId && !Prompts.isEcho(partial, Task.ASK)) {
                    streamed.offer(Prompts.reply(partial))
                    if (state == State.THINKING) {
                        state = State.SPEAKING
                        status.text = speechProblem ?: getString(R.string.answering)
                        guidance.text = "Quiet voice · Keep reading"
                    }
                    // Speech starts at the first finished sentence, not at
                    // the end of the answer. This is the whole difference
                    // between immediate and slow.
                    voice.follow(Markdown.strip(Prompts.reply(partial)))
                }
            }
        ) { result ->
            if (!gone && resumed && token == requestId) {
                streamed.cancel()
                generating = false
                finished(spoken, result, voice)
            }
        }
    }

    private fun finished(spoken: String, result: BrainResult, voice: Mouth) {
        state = State.SPEAKING

        if (!result.ok) {
            voice.hush()
            answer.text = result.note ?: getString(R.string.failed)
            idle()
            return
        }

        val said = Prompts.reply(result.text)

        // The instruction is not an answer, and it is certainly not something
        // to read out loud to someone who is not looking at the screen.
        if (Prompts.isEcho(said, Task.ASK)) {
            voice.hush()
            answer.text = getString(R.string.echoed)
            idle()
            return
        }

        val note = Lang.caveat(Task.ASK, spoken)
            ?: getString(R.string.truncated).takeIf { Prompts.looksTruncated(said) }
        answer.text = Markdown.render(
            if (note == null) said else said + "\n\n" + note, dp(18)
        )
        status.text = speechProblem ?: getString(R.string.answering)
        guidance.text = "Quiet voice · Keep reading"

        // What you asked in the kitchen is on the home screen afterwards.
        Chat.append(this, Turn(spoken, said))
        ResultStore.save(this, Task.ASK, said)

        // The tail that never got a full stop. Without this, "Sure" and every
        // short answer is printed and never spoken.
        voice.finish(Markdown.strip(said))
        scrollToEnd()

        // Nothing was queued -- no engine, muted by a barge-in, or an answer
        // already spoken in full -- so no onIdle is coming to end the turn.
        if (!voice.speaking()) idleWith(speechProblem ?: getString(R.string.tap_to_talk))
        else {
            action.text = getString(R.string.stop_speaking)
            action.setOnClickListener { quietVoice() }
        }
    }

    private fun speaker(): Mouth = mouth ?: Mouth(this).also { mouth = it }

    private fun scrollToEnd() = scroller.contentChanged()

    // ------------------------------------------------------------ plumbing

    /** An explicit control avoids changing a button's action halfway through a tap. */
    private fun quietVoice() {
        main.removeCallbacks(nextListen)
        continuousSwitch.isChecked = false
        mouth?.hush()
        if (generating) {
            guidance.text = "Continuing in text…"
            guidance.isClickable = false
            guidance.isFocusable = false
            guidance.background = null
            guidance.setTextColor(color(R.color.text_dim))
            dot.show(PresenceView.Mode.THINKING)
        } else idle()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MIC_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (resumed) listen() else pendingListen = true
        } else {
            status.text = "Microphone is off"
            answer.text = getString(R.string.mic_denied)
            setupTools.visibility = View.VISIBLE
            idleAction()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        stopForSetup()
        if (intent.getBooleanExtra("setup", false)) {
            showSetup()
        } else {
            setupMode = false
            pendingListen = true
            if (resumed) { pendingListen = false; requestListen() }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pendingListen) {
            pendingListen = false
            listen()
        } else if (state == State.IDLE && ears.available() && !setupMode) idleAction()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        main.removeCallbacks(nextListen)
        continuousSwitch.isChecked = false
        streamed.cancel()
        requestId++
        if (generating) {
            Brains.get().cancel()
            if (Chat.draft(this).isBlank()) Chat.saveDraft(this, lastQuestion)
            answer.text = getString(R.string.response_stopped)
        }
        generating = false
        // The microphone is not held while this is off screen, and the phone
        // does not keep talking to an empty room.
        ears.cancel()
        mouth?.hush()
        state = State.IDLE
        if (!setupMode) idleWith("Voice paused")
    }

    override fun onDestroy() {
        gone = true
        streamed.cancel()
        ears.cancel()
        // stop() is barge-in; shutdown() is teardown. Skip it and every trip
        // through the tile leaves another engine connection bound.
        mouth?.close()
        mouth = null
        super.onDestroy()
    }

    private fun wide() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun color(id: Int) = resources.getColor(id, theme)

    private companion object {
        const val MIC_REQUEST = 1
    }
}
