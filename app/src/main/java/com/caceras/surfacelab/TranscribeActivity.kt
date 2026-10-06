package com.caceras.surfacelab

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Transcription-first capture: one tap starts a verbatim, time-stamped
 * transcript that keeps saving while the phone is locked. Leaving this screen
 * does not stop it; the notification and this screen both offer Stop & save.
 */
class TranscribeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var transcript: TextView
    private lateinit var scroller: ReadingScrollView
    private lateinit var primary: TextView
    private lateinit var stop: TextView
    private lateinit var hint: TextView
    private lateinit var language: TextView
    private lateinit var engineSwitch: android.widget.Switch
    private lateinit var recent: LinearLayout
    private lateinit var modes: LinearLayout
    private fun recordMode() = getSharedPreferences("surfacelab", 0).getString("capture_mode", "live") == "record"
    private val handler = Handler(Looper.getMainLooper())
    private var shown: TranscriptionState? = null
    private var shownRecord = ""
    private var shownSegments = -1
    private var lines = ""
    private var openWhenSaved = false
    private val refresh = object : Runnable { override fun run() { update(); handler.postDelayed(this, 250) } }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        openWhenSaved = state?.getBoolean("openWhenSaved") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padDp(20, 12, 20, 12); setBackgroundColor(ink(R.color.chat_bg)) }
        root.addView(sheetHeader("Transcribe") { finish() })
        status = label("", 13f, true).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE; tag = "transcribe-status"; padDp(0, 10, 0, 2) }
        timer = label("", 30f).apply { medium(); tag = "transcribe-timer" }
        language = label("", 13f, true).apply { padDp(0, 2, 0, 8) }
        root.addView(status); root.addView(timer); root.addView(language)
        transcript = label("", 18f).apply { setTextIsSelectable(true); setLineSpacing(dp(6).toFloat(), 1.1f); padDp(0, 8, 0, 20); tag = "transcribe-text" }
        recent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "transcribe-recent" }
        scroller = ReadingScrollView(this).apply { addView(LinearLayout(this@TranscribeActivity).apply { orientation = LinearLayout.VERTICAL; addView(transcript); addView(recent) }) }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        hint = label(IDLE_HINT, 13f, true).apply { padDp(0, 8, 0, 8) }
        root.addView(hint)
        engineSwitch = preferenceSwitch("Use Android's recognizer only", getSharedPreferences("surfacelab", 0).getBoolean("transcribe_android", false)) {
            getSharedPreferences("surfacelab", 0).edit().putBoolean("transcribe_android", it).apply(); shown = null; update()
        }.apply { tag = "transcribe-engine" }
        root.addView(engineSwitch)
        modes = LinearLayout(this).apply { gravity = Gravity.CENTER; tag = "transcribe-modes" }
        root.addView(modes, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        primary = pill("Start transcribing", true) { primaryAction() }.apply { tag = "transcribe-primary" }
        stop = pill("Stop & save") { stopAndSave() }.apply { tag = "transcribe-stop" }
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
            addView(stop, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        })
        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(pill("Speech setup") { startActivity(Intent(this@TranscribeActivity, VoiceActivity::class.java).putExtra("setup", true)) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(pill("Notes") { startActivity(Intent(this@TranscribeActivity, HomeActivity::class.java).putExtra("destination", "notes").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)); finish() }, LinearLayout.LayoutParams(0, -2, 1f))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        setContentView(AdaptiveFrame(this, root).apply { padForSystemBars() }); readableSystemBars()
        update()
        if (state == null && intent.getBooleanExtra(START, false) && !TranscriptionService.state.active) begin()
    }

    private fun primaryAction() {
        val s = TranscriptionService.state
        when {
            !s.active -> begin()
            s.paused -> { ReadingService.pauseForCapture(); TranscriptionService.command(this, TranscriptionService.RESUME) }
            else -> TranscriptionService.command(this, TranscriptionService.PAUSE)
        }
        handler.postDelayed({ update() }, 50)
    }

    private fun begin() {
        val missing = buildList {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !getSharedPreferences("surfacelab", 0).getBoolean("transcribe_notice_asked", false)) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // The notification is the stop control outside the app, so ask for it once even when the microphone is already allowed.
        if (missing.isNotEmpty()) {
            // Mark the notification request before asking so an interrupted dialog cannot loop.
            if (Manifest.permission.POST_NOTIFICATIONS in missing) getSharedPreferences("surfacelab", 0).edit().putBoolean("transcribe_notice_asked", true).apply()
            requestPermissions(missing.toTypedArray(), REQUEST); return
        }
        if (!Ears(this).available()) {
            AlertDialog.Builder(this).setTitle("On-device speech").setMessage("Transcription needs Android's on-device speech. Open speech setup to download your language, then start again.")
                .setNegativeButton("Not now", null).setPositiveButton("Speech setup") { _, _ -> startActivity(Intent(this, VoiceActivity::class.java).putExtra("setup", true)) }.showProtected(this)
            return
        }
        TranscriptionService.clearFinished()
        openWhenSaved = false
        shownRecord = ""; shownSegments = -1; lines = ""
        TranscriptionService.start(this, record = recordMode())
        status.text = "Starting…"
    }

    private fun stopAndSave() {
        if (!TranscriptionService.state.active) { finish(); return }
        openWhenSaved = true
        status.text = "Saving…"
        TranscriptionService.command(this, TranscriptionService.STOP)
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code != REQUEST) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else status.text = "Microphone access is off. Enable it in Android settings to transcribe; typing still works."
    }

    private fun update() {
        val s = TranscriptionService.state
        if (openWhenSaved && !s.active) {
            openWhenSaved = false
            if (s.record.isNotBlank()) {
                TranscriptionService.clearFinished()
                startActivity(WorkspaceActivity.intent(this, "library", s.record)); finish(); return
            }
            status.text = s.message.ifBlank { "Nothing was transcribed." }
        }
        if (s.record != shownRecord || s.segments != shownSegments) {
            shownRecord = s.record; shownSegments = s.segments
            lines = if (s.record.isBlank() || s.recording) "" else WorkspaceStore(this).use { it.get(s.record)?.original.orEmpty() }.takeLast(DISPLAY)
        }
        val text = SpannableStringBuilder(lines)
        if (s.active && s.partial.isNotBlank()) {
            if (text.isNotEmpty()) text.append("\n")
            val start = text.length; text.append(s.partial)
            text.setSpan(ForegroundColorSpan(ink(R.color.text_dim)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (text.toString() != transcript.text.toString()) { transcript.text = text; scroller.contentChanged() }
        if (s == shown) { if (s.active && !s.paused) timer.text = Transcripts.stamp(SystemClock.elapsedRealtime() - s.startedClock); return }
        shown = s
        timer.text = if (s.active) Transcripts.stamp(SystemClock.elapsedRealtime() - s.startedClock) else "00:00"
        language.text = listOf(java.util.Locale.forLanguageTag(s.language.ifBlank { Ears(this).locale().toLanguageTag() }).displayName, s.engine.ifBlank { if (recordMode()) "Audio recording on this phone" else SpeechEngineProvider.choice(this) }).joinToString(" · ")
        status.text = when {
            !s.active && openWhenSaved -> "Saving…"
            !s.active -> s.message.ifBlank { "Ready. Speak naturally; pauses are fine." }
            s.paused -> s.message.ifBlank { "Paused" }
            s.recording -> s.message.ifBlank { "Recording audio" + if (s.segments > 1) " · part ${s.segments}" else "" }
            else -> s.message.ifBlank { "Listening · ${s.segments} line${if (s.segments == 1) "" else "s"} saved" }
        }
        primary.text = when { !s.active -> if (recordMode()) "Start recording" else "Start transcribing"; s.paused -> "Resume"; else -> "Pause" }
        stop.text = if (s.active) "Stop & save" else "Close"
        hint.text = when {
            s.active && s.recording -> "Audio is saved on this phone as you record, also with the screen locked. After Stop & save, open the note and choose Transcribe with Gemini for speaker labels."
            s.active -> "Keeps transcribing if you leave or lock the phone. Stop & save ends it."
            recordMode() -> RECORD_HINT
            else -> IDLE_HINT
        }
        engineSwitch.visibility = if (s.active || recordMode()) View.GONE else View.VISIBLE
        renderModes(s.active)
        stop.visibility = if (s.active) View.VISIBLE else View.GONE
        showRecent(!s.active && lines.isBlank())
        // Google's on-device model only runs while the app is on screen, so keep it visible while capturing.
        if (s.active && !s.paused) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Live on-device transcript, or the audio itself as the original for later speaker-labelled transcription. */
    private fun renderModes(active: Boolean) {
        modes.removeAllViews()
        if (active) return
        for ((key, title) in listOf("live" to "Live transcript", "record" to "Record audio")) {
            val selected = (getSharedPreferences("surfacelab", 0).getString("capture_mode", "live") ?: "live") == key
            modes.addView(pill(title, selected) { getSharedPreferences("surfacelab", 0).edit().putString("capture_mode", key).apply(); shown = null; update() }.apply {
                tag = "mode-$key"; isSelected = selected; contentDescription = title + if (selected) ", selected" else ""
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (key == "live") marginEnd = dp(4) else marginStart = dp(4) })
        }
    }

    /** Idle screens show the latest transcripts instead of empty space. */
    private fun showRecent(visible: Boolean) {
        recent.removeAllViews()
        if (!visible) return
        val items = WorkspaceStore(this).use { store -> store.list(kind = "note", limit = 60).filter(Transcripts::isTranscript).take(5) }
        if (items.isEmpty()) return
        recent.addView(label("Recent transcripts", 15f).apply { medium(); padDp(0, 16, 0, 8) })
        items.forEach { r ->
            val words = Transcripts.words(r.body).replace('\n', ' ').trim().take(80)
            recent.addView(pill(r.title.removePrefix("Transcript · ").ifBlank { "Transcript" } + if (words.isBlank()) "" else " · $words") { startActivity(WorkspaceActivity.intent(this, "library", r.id)) }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    override fun onResume() {
        super.onResume(); NativePrivacy.apply(this, window)
        // Audio cut off by a killed process is listed again before the screen shows recent notes.
        if (!TranscriptionService.state.active) runCatching { WorkspaceStore(this).use { AudioNotes.recover(this, it) } }
        shown = null; handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onSaveInstanceState(state: Bundle) { state.putBoolean("openWhenSaved", openWhenSaved); super.onSaveInstanceState(state) }

    companion object {
        const val START = "start"
        private const val RECORD_HINT = "Keeps the audio itself on this phone (about 14 MB per hour), in parts of up to 25 minutes. Transcribe it later with Gemini for speaker labels and Swedish/English detection. Audio is never uploaded unless you ask."
        private const val IDLE_HINT = "Words are transcribed on this phone and saved exactly as recognised, with time stamps. It keeps going with the screen locked; use the notification or this screen to stop."
        private const val REQUEST = 44
        private const val DISPLAY = 40_000
        fun intent(context: Context, start: Boolean = false) = Intent(context, TranscribeActivity::class.java).putExtra(START, start)
    }
}
