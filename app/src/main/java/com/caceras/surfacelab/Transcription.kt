package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Verbatim transcripts are ordinary notes whose source starts with [SOURCE].
 * Recognised segments are appended to the original and the working text in one
 * transaction; the original is never rewritten afterwards, so AI polish always
 * has the spoken words to fall back to.
 */
object Transcripts {
    const val SOURCE = "Transcript"
    /** Below the 200,000-character record limit, leaving room for one more segment. */
    const val LIMIT = 190_000
    private val STAMP = Regex("^\\[\\d{1,2}(?::\\d{2}){1,2}] ?")

    fun isTranscript(record: Record) = record.kind == "note" && record.source.startsWith(SOURCE)
    fun stamp(elapsed: Long): String {
        val seconds = (elapsed / 1000).coerceAtLeast(0)
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }
    fun line(elapsed: Long, text: String) = "[${stamp(elapsed)}] ${text.trim()}"
    /** Spoken words without time stamps, for reading, polishing and AI context. */
    fun words(text: String) = text.lineSequence().map { it.replaceFirst(STAMP, "") }.joinToString("\n")
    fun title(started: Long, part: Int) = "Transcript · " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(started)) + if (part > 1) " · part $part" else ""

    /**
     * Saves one recognised segment exactly as the recogniser returned it and
     * returns the record now receiving lines. A full transcript continues in a
     * linked part rather than truncating speech.
     */
    fun append(store: WorkspaceStore, current: String?, started: Long, part: Int, elapsed: Long, text: String, language: String, limit: Int = LIMIT): Record {
        require(text.isNotBlank())
        val line = line(elapsed, text)
        val existing = current?.let(store::get)?.takeIf { !it.deleted }
        if (existing == null) return store.save(Record(title = title(started, part), body = line, original = line, created = started,
            source = "$SOURCE · $language · on this phone"))
        if (existing.original.length + line.length + 1 > limit) {
            val next = store.save(Record(title = title(started, part + 1), body = line, original = line, created = started, source = existing.source))
            store.link(next.id, existing.id, "related")
            return next
        }
        return store.appendVerbatim(existing.id, line)
    }
}

/** One continuous recogniser. Callbacks arrive on the main thread. */
interface SpeechEngine {
    val label: String
    fun start(locale: Locale, listener: Listener)
    /** Stop listening, deliver words still being recognised, then call [done]. */
    fun finish(done: () -> Unit)
    fun cancel()

    interface Listener {
        fun partial(text: String)
        fun final(text: String)
        fun level(rms: Float) {}
        /** The engine switched mid-session, for example to Android's recogniser; [note] explains why. */
        fun engine(label: String, note: String) {}
        /** Capture cannot continue. [resumable] false means settings must change first. */
        fun stopped(message: String, resumable: Boolean)
    }
}

/**
 * Android's on-device recogniser ends a session at each pause. This engine
 * starts the next session immediately, keeps the last partial if a session
 * ends without a final result, and gives up visibly instead of looping when
 * the microphone or recogniser stays unavailable (for example during a call).
 */
class PlatformSpeechEngine(private val context: Context) : SpeechEngine {
    override val label = "Android on-device speech"
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var session = 0
    private var listener: SpeechEngine.Listener? = null
    private var locale: Locale = Locale.getDefault()
    private var running = false
    private var finished: (() -> Unit)? = null
    private var partial = ""
    private var failures = 0
    private var runStarted = 0L
    private var restart: Runnable? = null
    private var finishTimeout: Runnable? = null

    override fun start(locale: Locale, listener: SpeechEngine.Listener) {
        cancel(); this.locale = locale; this.listener = listener; running = true; failures = 0
        listen()
    }

    private fun listen() {
        restart = null
        if (!running) return
        release()
        val token = ++session
        if (android.os.Build.VERSION.SDK_INT < 31) { halt("Transcription needs Android 12 or newer for on-device speech.", false); return }
        val client = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) } catch (_: Exception) {
            halt("On-device speech could not start. Open Voice setup to check offline speech.", true); return
        }
        recognizer = client; partial = ""; runStarted = SystemClock.elapsedRealtime()
        client.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (token == session) listener?.level(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                if (token != session) return
                first(partialResults)?.takeIf { it.isNotBlank() }?.let { partial = it; listener?.partial(it) }
            }
            override fun onResults(results: Bundle?) {
                if (token != session) return
                val text = first(results)?.takeIf { it.isNotBlank() } ?: partial
                deliver(text); ended()
            }
            override fun onError(error: Int) { if (token == session) failed(error) }
        })
        try { client.startListening(intent()) } catch (_: Exception) { failed(SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun deliver(text: String) {
        partial = ""
        if (text.isBlank()) return
        failures = 0; listener?.final(text.trim())
    }

    private fun ended() {
        release()
        if (finished != null) complete() else if (running) schedule(RESTART_MS)
    }

    private fun failed(error: Int) {
        val heard = partial
        release()
        if (finished != null) { deliver(heard); complete(); return }
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> halt("Microphone permission is needed to transcribe.", false)
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                halt("Offline speech for ${locale.displayName} is not installed. Open Voice setup to download it, or choose another language.", false)
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                deliver(heard)
                // Silence is normal. A session that ends instantly again and again is not.
                if (heard.isBlank() && SystemClock.elapsedRealtime() - runStarted < QUICK_MS) failures++
                if (failures >= MAX_FAILURES) halt(BLOCKED, true) else schedule(if (failures == 0) RESTART_MS else backoff())
            }
            else -> {
                deliver(heard); failures++
                if (failures >= MAX_FAILURES) halt(BLOCKED, true) else schedule(backoff())
            }
        }
    }

    private fun backoff() = (400L shl (failures - 1).coerceIn(0, 4)).coerceAtMost(6400L)
    private fun schedule(delay: Long) {
        if (!running) return
        restart?.let(main::removeCallbacks)
        restart = Runnable { listen() }.also { main.postDelayed(it, delay) }
    }

    private fun halt(message: String, resumable: Boolean) {
        running = false; release()
        restart?.let(main::removeCallbacks); restart = null
        listener?.stopped(message, resumable)
    }

    override fun finish(done: () -> Unit) {
        restart?.let(main::removeCallbacks); restart = null
        val wasRunning = running
        running = false
        val client = recognizer
        if (!wasRunning || client == null || partial.isBlank()) { release(); done(); return }
        finished = done
        finishTimeout = Runnable { deliver(partial); release(); complete() }.also { main.postDelayed(it, FINISH_MS) }
        try { client.stopListening() } catch (_: Exception) { finishTimeout?.let(main::removeCallbacks); deliver(partial); release(); complete() }
    }

    private fun complete() {
        finishTimeout?.let(main::removeCallbacks); finishTimeout = null
        val done = finished; finished = null
        done?.invoke()
    }

    override fun cancel() {
        running = false; finished = null
        restart?.let(main::removeCallbacks); restart = null
        finishTimeout?.let(main::removeCallbacks); finishTimeout = null
        release()
    }

    private fun release() {
        session++
        recognizer?.destroy(); recognizer = null
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

    private fun first(bundle: Bundle?) = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        const val RESTART_MS = 120L
        const val QUICK_MS = 1500L
        const val FINISH_MS = 1500L
        const val MAX_FAILURES = 6
        const val BLOCKED = "Transcription paused: the microphone or speech service is busy, for example during a call. Tap Resume when it is free."
    }
}
