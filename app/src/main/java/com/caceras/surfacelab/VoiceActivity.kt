package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
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
    private lateinit var dot: View
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private lateinit var action: TextView
    private lateinit var scroller: ScrollView

    private val ears by lazy { Ears(this) }
    private var mouth: Mouth? = null

    private var state = State.IDLE

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

        when {
            // Opening on an honest status beats opening on a microphone that
            // cannot be used. A shortcut promising "tap, talk" on a phone
            // with no on-device recogniser is worse than no shortcut.
            !ears.available() -> {
                state = State.IDLE
                status.text = getString(R.string.voice_unavailable)
                dot.visibility = View.GONE
                action.visibility = View.GONE
            }
            granted() -> listen()
            else -> {
                status.text = getString(R.string.mic_rationale)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            }
        }
    }

    // ------------------------------------------------------------- chrome

    private fun build(): View {
        status = TextView(this).apply {
            textSize = 17f
            setTextColor(color(R.color.text_primary))
        }

        // One dot, scaled by the microphone level. The point is only that the
        // screen is never silent while it is listening -- a blank spinner is
        // the thing this app does not do anywhere.
        dot = View(this).apply {
            background = getDrawable(R.drawable.dot)
            contentDescription = getString(R.string.listening)
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                rightMargin = dp(12)
            })
            addView(status)
        }

        heard = TextView(this).apply {
            textSize = 20f
            setTextColor(color(R.color.text_primary))
            padDp(0, 14, 0, 0)
        }

        answer = TextView(this).apply {
            textSize = 16f
            setTextColor(color(R.color.text_dim))
            padDp(0, 10, 0, 0)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heard, wide())
            addView(answer, wide())
        }

        scroller = ScrollView(this).apply {
            isFillViewport = false
            addView(column, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        action = TextView(this).apply {
            text = getString(R.string.talk_again)
            textSize = 15f
            setTextColor(color(R.color.accent))
            padDp(0, 16, 24, 4)
            visibility = View.GONE
            setOnClickListener { listen() }
        }

        val close = TextView(this).apply {
            text = getString(R.string.close)
            textSize = 15f
            setTextColor(color(R.color.text_dim))
            padDp(0, 16, 0, 4)
            setOnClickListener { finish() }
        }

        val feet = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(action)
            addView(close)
        }

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bubble_ai)
            padDp(22, 20, 22, 14)
            addView(head, wide())
            addView(scroller, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(feet, wide())
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(color(R.color.scrim))
            padDp(18, 18, 18, 18)
            addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            // Tapping the scrim, rather than the card, closes the session.
            setOnClickListener { finish() }
            padForSystemBars()
        }
    }

    // ---------------------------------------------------------- listening

    private fun granted(): Boolean =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun listen() {
        if (gone || !ears.available()) return
        mouth?.hush()

        state = State.LISTENING
        status.text = getString(R.string.listening_hint)
        heard.text = ""
        answer.text = ""
        dot.visibility = View.VISIBLE
        action.visibility = View.GONE

        ears.listen(
            onLevel = { level(it) },
            onPartial = { partial -> heard.text = partial },
            onFinal = { spoken -> ask(spoken) },
            onStop = { problem -> stopped(problem) }
        )
    }

    /** rmsdB runs from roughly -2 to 10, and only the loud half is useful. */
    private fun level(rms: Float) {
        val scale = 1f + (rms.coerceIn(0f, 10f) / 10f)
        dot.scaleX = scale
        dot.scaleY = scale
    }

    private fun stopped(problem: VoiceProblem?) {
        dot.scaleX = 1f
        dot.scaleY = 1f

        if (problem == null) {
            // Saying nothing is the ordinary way a session ends, not an
            // error. Back to idle, showing nothing, unless the answer is
            // already on its way.
            if (state == State.LISTENING) idle()
            return
        }

        state = State.IDLE
        dot.visibility = View.GONE
        status.text = problem.message

        if (problem.languageMissing && ears.canFetchLanguage()) {
            // The recogniser is here, the language pack is not. That is the
            // one speech failure with something to do about it -- and it is
            // a download, so it is offered rather than started.
            action.text = getString(R.string.get_offline_speech)
            action.visibility = View.VISIBLE
            action.setOnClickListener {
                action.visibility = View.GONE
                status.text = getString(R.string.working)
                ears.fetchLanguage { outcome -> if (!gone) idleWith(outcome) }
            }
        } else {
            idleAction()
        }
    }

    private fun idle() = idleWith(getString(R.string.tap_to_talk))

    private fun idleWith(line: String) {
        state = State.IDLE
        dot.visibility = View.GONE
        status.text = line
        idleAction()
    }

    private fun idleAction() {
        action.text = getString(R.string.talk_again)
        action.visibility = if (ears.available()) View.VISIBLE else View.GONE
        action.setOnClickListener { listen() }
    }

    // ------------------------------------------------------------- asking

    private fun ask(spoken: String) {
        if (gone) return

        heard.text = spoken
        state = State.THINKING
        status.text = getString(R.string.working)
        dot.visibility = View.GONE

        val voice = speaker()
        voice.begin(ears.locale())
        voice.onIdle = { if (!gone && state == State.SPEAKING) idle() }

        Brains.get().run(
            context = this,
            task = Task.ASK,
            input = "",
            instruction = spoken,
            onPartial = { partial ->
                if (!gone && !Prompts.isEcho(partial, Task.ASK)) {
                    answer.text = Markdown.render(partial, dp(18))
                    if (state == State.THINKING) {
                        state = State.SPEAKING
                        status.text = getString(R.string.answering)
                    }
                    // Speech starts at the first finished sentence, not at
                    // the end of the answer. This is the whole difference
                    // between immediate and slow.
                    voice.follow(Markdown.strip(partial))
                    scrollToEnd()
                }
            }
        ) { result ->
            if (!gone) finished(spoken, result, voice)
        }
    }

    private fun finished(spoken: String, result: BrainResult, voice: Mouth) {
        state = State.SPEAKING

        if (!result.ok) {
            answer.text = result.note ?: getString(R.string.failed)
            idle()
            return
        }

        val said = Prompts.reply(result.text)

        // The instruction is not an answer, and it is certainly not something
        // to read out loud to someone who is not looking at the screen.
        if (Prompts.isEcho(said, Task.ASK)) {
            answer.text = getString(R.string.echoed)
            idle()
            return
        }

        val note = Lang.caveat(Task.ASK, spoken)
            ?: getString(R.string.truncated).takeIf { Prompts.looksTruncated(said) }
        answer.text = Markdown.render(
            if (note == null) said else said + "\n\n" + note, dp(18)
        )
        status.text = getString(R.string.answering)

        // What you asked in the kitchen is on the home screen afterwards.
        ResultStore.save(this, Task.ASK, said)

        // The tail that never got a full stop. Without this, "Sure" and every
        // short answer is printed and never spoken.
        voice.finish(Markdown.strip(said))
        scrollToEnd()

        // Nothing was queued -- no engine, muted by a barge-in, or an answer
        // already spoken in full -- so no onIdle is coming to end the turn.
        if (!voice.speaking()) idle()
    }

    private fun speaker(): Mouth = mouth ?: Mouth(this).also { mouth = it }

    private fun scrollToEnd() =
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }

    // ------------------------------------------------------------ plumbing

    /**
     * Barge-in. Any touch stops the phone talking, immediately -- not being
     * able to shut it up is what makes a voice assistant feel like an
     * appliance. Mouth.hush() also mutes the chunker, because the brain is
     * very likely still streaming and stop() alone only clears the queue.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) mouth?.hush()
        return super.dispatchTouchEvent(event)
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
            status.text = getString(R.string.mic_denied)
            idleAction()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (pendingListen) {
            pendingListen = false
            listen()
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // The microphone is not held while this is off screen, and the phone
        // does not keep talking to an empty room.
        ears.cancel()
        mouth?.hush()
        if (state == State.LISTENING) state = State.IDLE
    }

    override fun onDestroy() {
        gone = true
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
