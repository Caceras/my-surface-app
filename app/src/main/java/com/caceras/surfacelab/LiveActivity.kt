package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Real-time voice with Gemini Live, as a foreground conversation only: leaving
 * the screen ends it, and the verbatim exchange is saved as a note. Gemini can
 * search the user's notes through read-only tools; it cannot change anything.
 */
class LiveActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var text: TextView
    private lateinit var scroller: ReadingScrollView
    private lateinit var sources: TextView
    private lateinit var primary: TextView
    private lateinit var hold: android.widget.Switch
    private lateinit var holdHint: TextView
    private var session: LiveSession? = null
    private var saved: Record? = null
    private var focus: AudioFocusRequest? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(20, 12, 20, 12); setBackgroundColor(ink(R.color.chat_bg)) }
        root.addView(sheetHeader("Live · Gemini") { finish() })
        status = label("", 14f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "live-status"; padDp(0, 10, 0, 8) }
        root.addView(status)
        text = label("", 18f).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1.1f); padDp(0, 8, 0, 16); tag = "live-text" }
        scroller = ReadingScrollView(this).apply { addView(text) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        sources = label("", 12f, true).apply { visibility = View.GONE; tag = "live-sources"; padDp(0, 4, 0, 8) }
        root.addView(sources)
        hold = preferenceSwitch("Pause my mic while Gemini speaks", prefs().getBoolean("live_hold_mic", false)) {
            prefs().edit().putBoolean("live_hold_mic", it).apply(); session?.holdMicWhileSpeaking = it
        }.apply { tag = "live-hold" }
        root.addView(hold)
        holdHint = label("Turn this on if Gemini interrupts itself on the loudspeaker. Headphones avoid the echo.", 12f, true).apply { padDp(0, 0, 0, 8) }
        root.addView(holdHint)
        primary = pill("Start talking", true) { primaryAction() }.apply { tag = "live-primary" }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
        })
        setContentView(AdaptiveFrame(this, root).apply { padForSystemBars() }); readableSystemBars()
        status.text = intro()
        if (state == null && intent.getBooleanExtra(START, false)) primaryAction()
    }

    private fun prefs() = getSharedPreferences("surfacelab", 0)

    private fun intro() = when {
        !Live.available() -> "Live voice needs the Nano build of Ægentica."
        !ConnectedAI.geminiConfigured(this) && Live.keyForTest == null -> "Connect Gemini first: Settings → Connected AI → Use Google Gemini."
        else -> "Talk naturally and interrupt any time. Gemini can search your notes (read-only). Leaving this screen ends the conversation; it is saved to Notes."
    }

    private fun primaryAction() {
        val current = session
        when {
            current != null && !current.ended -> stopAndSave("")
            saved != null -> { startActivity(WorkspaceActivity.intent(this, "library", saved!!.id)); finish() }
            else -> begin()
        }
    }

    private fun begin() {
        if (!Live.available()) { status.text = intro(); return }
        val key = Live.keyForTest ?: ConnectedAI.geminiKey(this)
        if (key == null) {
            AlertDialog.Builder(this).setTitle("Connect Gemini").setMessage("Live voice uses your own Gemini API key. Open Connected AI, tap Use Google Gemini, paste your AI Studio key and save.")
                .setNegativeButton("Not now", null).setPositiveButton("Connected AI") { _, _ -> ConnectedAI.settings(this) { status.text = intro() } }.showProtected(this)
            return
        }
        if (!prefs().getBoolean("live_consent", false)) {
            AlertDialog.Builder(this).setTitle("Talk live with Gemini?")
                .setMessage("While this screen is open, your microphone audio streams to Google's Gemini Live API using your key. When Gemini searches your notes, the matching titles and excerpts are sent too. Google bills roughly US$0.005 per minute you speak and US$0.018 per minute Gemini speaks (October 2026 prices). Set a spending limit in AI Studio. The conversation is saved as a note on this phone when it ends.")
                .setNegativeButton("Cancel", null).setPositiveButton("Start") { _, _ -> prefs().edit().putBoolean("live_consent", true).apply(); begin() }.showProtected(this)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST); return }
        ReadingService.pauseForCapture()
        TranscriptionService.yieldMicrophone()
        saved = null; text.text = ""; sources.visibility = View.GONE
        requestFocus()
        val live = LiveSession(this, key, Live.audioForTest ?: LiveAudioDevice(), object : LiveSession.Listener {
            override fun state(state: LiveSession.State, detail: String) { show(state, detail) }
            override fun transcript(lines: List<LiveLine>, current: String) { render(lines, current) }
            override fun level(rms: Float) { primary.speechLevel(rms) }
        })
        live.holdMicWhileSpeaking = prefs().getBoolean("live_hold_mic", false)
        session = live
        live.start()
    }

    private fun show(state: LiveSession.State, detail: String) {
        status.text = detail.ifBlank {
            when (state) {
                LiveSession.State.CONNECTING -> "Connecting to Gemini Live…"
                LiveSession.State.RECONNECTING -> "Reconnecting…"
                LiveSession.State.LISTENING -> "Listening · speak naturally"
                LiveSession.State.SPEAKING -> "Gemini is speaking · talk to interrupt"
                LiveSession.State.SEARCHING -> "Searching your notes…"
                LiveSession.State.ENDED -> "Ended"
            }
        }
        val active = state != LiveSession.State.ENDED
        primary.text = if (active) "End & save" else if (saved != null) "Open saved note" else "Start talking"
        hold.visibility = if (active) View.GONE else View.VISIBLE
        holdHint.visibility = hold.visibility
        if (active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val used = session?.consulted.orEmpty()
        sources.text = "Notes used: " + used.joinToString(" · ")
        sources.visibility = if (used.isEmpty()) View.GONE else View.VISIBLE
        if (state == LiveSession.State.ENDED) { primary.speechLevel(0f); finishSession(detail) }
    }

    private fun render(lines: List<LiveLine>, current: String) {
        val out = SpannableStringBuilder()
        lines.forEach { line ->
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length; out.append(if (line.you) "You" else "Gemini")
            out.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n").append(line.text)
        }
        if (current.isNotBlank()) {
            if (out.isNotEmpty()) out.append("\n\n")
            val start = out.length; out.append(current)
            out.setSpan(ForegroundColorSpan(ink(R.color.text_dim)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = out; scroller.contentChanged()
    }

    private fun stopAndSave(message: String) { session?.end(message) }

    /** Saves the verbatim conversation once and releases audio focus. */
    private fun finishSession(message: String) {
        abandonFocus()
        val live = session ?: return
        if (saved == null) saved = runCatching { WorkspaceStore(this).use { live.save(it) } }.getOrNull()
        if (saved != null) status.text = listOf(message, "Saved to Notes.").filter(String::isNotBlank).joinToString(" ")
        primary.text = if (saved != null) "Open saved note" else "Start talking"
    }

    private fun requestFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) runOnUiThread { stopAndSave("Ended because another app needed audio.") } }
            .build()
        focus = request
        manager.requestAudioFocus(request)
    }

    private fun abandonFocus() { focus?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }; focus = null }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else status.text = "Microphone access is off. Enable it in Android settings to talk live."
    }

    override fun onResume() { super.onResume(); NativePrivacy.apply(this, window) }
    // Never keep hidden capture running: leaving the screen ends and saves the conversation.
    override fun onPause() { if (session?.ended == false) stopAndSave("Ended when you left the screen."); super.onPause() }
    override fun onDestroy() { session?.end(); abandonFocus(); super.onDestroy() }

    companion object {
        const val START = "start"
        private const val REQUEST = 45
        fun intent(context: Context, start: Boolean = false) = Intent(context, LiveActivity::class.java).putExtra(START, start)
    }
}
