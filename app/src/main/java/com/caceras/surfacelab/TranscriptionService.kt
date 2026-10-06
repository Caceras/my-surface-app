package com.caceras.surfacelab

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.File

data class TranscriptionState(
    val active: Boolean = false, val paused: Boolean = false, val record: String = "", val started: Long = 0,
    val startedClock: Long = 0, val partial: String = "", val segments: Int = 0, val message: String = "",
    val engine: String = "", val language: String = "", val level: Float = 0f,
    /** Record mode keeps audio parts instead of transcribing live; [segments] then counts parts. */
    val recording: Boolean = false
)

/**
 * Explicit long-form transcription. The user starts it from a visible screen;
 * a microphone foreground service with a persistent notification keeps it
 * going while the phone is locked or another app is open. It never starts
 * itself, never restarts after process death, and yields the microphone to
 * any other capture in this app. Finished segments are durable immediately.
 */
class TranscriptionService : Service() {
    private var engine: SpeechEngine? = null
    private var part = 1
    private var stopping = false
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var partFile: File? = null
    private var partActiveMs = 0L
    private var partResumedAt = 0L
    private val rollover = Runnable { if (state.active && state.recording && !state.paused) { closePart(); openPart() } }
    private val meter = object : Runnable {
        override fun run() {
            val r = recorder ?: return
            if (!state.paused) state = state.copy(level = (runCatching { r.maxAmplitude }.getOrDefault(0) / 3276.8f).coerceIn(0f, 10f))
            if (state.active && state.recording) main.postDelayed(this, 200)
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); active = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> pause("")
            RESUME -> resume()
            STOP -> stop()
            else -> begin(intent?.getBooleanExtra(RECORD, false) == true)
        }
        // A notification action can arrive after the session ended; never leave an idle started service.
        if (!state.active && !stopping) stopSelf()
        return START_NOT_STICKY
    }

    private fun begin(record: Boolean) {
        if (state.active) { foreground(); return }
        val language = Ears(this).locale()
        state = TranscriptionState(active = true, started = System.currentTimeMillis(), startedClock = SystemClock.elapsedRealtime(), language = language.toLanguageTag(), recording = record)
        if (!foreground()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission is needed to transcribe."); return }
        ReadingService.pauseForCapture()
        part = 1
        if (record) beginRecording() else listen()
    }

    /** Record mode: the audio itself is the original; Gemini can transcribe it later with speaker labels. */
    private fun beginRecording() {
        val note = WorkspaceStore(this).use { it.save(Record(title = "Recording · " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(state.started)),
            source = AudioNotes.SOURCE, created = state.started)) }
        state = state.copy(record = note.id, engine = "Audio recording on this phone")
        if (!openPart()) return
        publish(); main.post(meter)
    }

    private fun openPart(): Boolean {
        val file = File(AudioNotes.dir(this), "${state.record}-${state.segments + 1}.${AudioNotes.EXTENSION}")
        val next = try { AudioNotes.recorder(this, file).also { it.prepare(); it.start() } } catch (_: Exception) {
            file.delete(); end("The microphone could not record. Close other recording apps and try again."); return false
        }
        recorder = next; partFile = file; partActiveMs = 0; partResumedAt = SystemClock.elapsedRealtime()
        main.removeCallbacks(rollover); main.postDelayed(rollover, AudioNotes.PART_MS)
        state = state.copy(segments = state.segments + 1, message = "")
        return true
    }

    /** Finalises the current part; an empty or failed part is discarded rather than listed. */
    private fun closePart() {
        main.removeCallbacks(rollover)
        val r = recorder ?: return
        recorder = null
        val ms = partActiveMs + if (!state.paused) SystemClock.elapsedRealtime() - partResumedAt else 0
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val file = partFile ?: return
        partFile = null
        if (ok && file.length() > 0) WorkspaceStore(this).use { AudioNotes.add(it, state.record, AudioNotes.Part(file.name, ms)) } else file.delete()
    }

    /** Required within seconds of startForegroundService; failure must not leave a hidden capture behind. */
    private fun foreground(): Boolean = try {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Transcription", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 30) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, notification())
        true
    } catch (e: Exception) {
        state = TranscriptionState(message = "Android did not allow the microphone here. Open Ægentica and start transcribing from the app.")
        stopSelf(); false
    }

    private fun listen() {
        val locale = java.util.Locale.forLanguageTag(state.language)
        val next = engine ?: SpeechEngineProvider.create(this, locale).also { engine = it }
        state = state.copy(paused = false, message = "", engine = next.label); publish()
        next.start(locale, object : SpeechEngine.Listener {
            override fun partial(text: String) { if (engine === next && state.active) { state = state.copy(partial = text); publish(false) } }
            override fun level(rms: Float) { if (engine === next) state = state.copy(level = rms) }
            override fun final(text: String) { if (engine === next) commit(text) }
            override fun engine(label: String, note: String) { if (engine === next) { state = state.copy(engine = label, message = note); publish(false) } }
            override fun stopped(message: String, resumable: Boolean) {
                if (engine !== next || !state.active) return
                if (resumable) { state = state.copy(paused = true, partial = "", message = message); publish() }
                else end(message)
            }
        })
    }

    private fun commit(text: String) {
        if (!state.active || text.isBlank()) return
        val result = runCatching {
            WorkspaceStore(this).use { Transcripts.append(it, state.record.ifBlank { null }, state.started, part, SystemClock.elapsedRealtime() - state.startedClock, text, state.language) }
        }
        result.onSuccess { saved ->
            if (saved.id != state.record && state.record.isNotBlank()) part++
            state = state.copy(record = saved.id, partial = "", segments = state.segments + 1); publish(false)
        }.onFailure { pause(it.message ?: "Could not save the transcript. Your earlier lines are safe.") }
    }

    private fun pause(message: String) {
        if (!state.active || state.paused) return
        if (state.recording) {
            main.removeCallbacks(rollover)
            partActiveMs += SystemClock.elapsedRealtime() - partResumedAt
            state = state.copy(paused = true, message = message, level = 0f)
            // Paused must mean the microphone is off: a recorder that cannot pause is stopped and resumes as a new part.
            if (runCatching { recorder?.pause() }.isFailure) closePart()
            publish(); return
        }
        val current = engine
        state = state.copy(paused = true, message = message); publish()
        current?.finish { if (engine === current) engine = null; state = state.copy(partial = ""); publish() }
    }

    private fun resume() {
        if (!state.active || !state.paused || stopping) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission is needed to transcribe."); return }
        ReadingService.pauseForCapture()
        if (state.recording) {
            val resumed = recorder?.let { r -> runCatching { r.resume() }.isSuccess } ?: false
            state = state.copy(paused = false, message = "")
            if (resumed) { partResumedAt = SystemClock.elapsedRealtime(); main.postDelayed(rollover, (AudioNotes.PART_MS - partActiveMs).coerceAtLeast(1000)) }
            else { closePart(); if (!openPart()) return }
            publish(); main.removeCallbacks(meter); main.post(meter); return
        }
        engine?.cancel(); engine = null
        listen()
    }

    private fun stop() {
        if (stopping) return
        if (!state.active) { stopSelf(); return }
        stopping = true
        if (state.recording) { closePart(); end(""); return }
        val current = engine
        if (current == null) { end(""); return }
        current.finish { end("") }
    }

    private fun end(message: String) {
        engine?.cancel(); engine = null
        main.removeCallbacks(meter)
        if (state.recording) {
            closePart()
            // A recording that captured nothing leaves no empty note behind.
            WorkspaceStore(this).use { store ->
                if (state.record.isNotBlank() && AudioNotes.parts(store, state.record).isEmpty()) store.get(state.record)?.let { r ->
                    if (r.body.isBlank()) { store.save(r.copy(deleted = true)); store.purge(r.id); state = state.copy(record = "") }
                }
            }
        }
        state = TranscriptionState(record = state.record, segments = state.segments, message = message, language = state.language, recording = state.recording)
        stopping = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(ID)
        TranscribeTileService.refresh(this)
        stopSelf()
    }

    private fun publish(notify: Boolean = true) {
        if (notify && state.active) getSystemService(NotificationManager::class.java).notify(ID, notification())
        if (notify) TranscribeTileService.refresh(this)
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun command(action: String) = PendingIntent.getService(this, action.hashCode(), Intent(this, TranscriptionService::class.java).setAction(action), flags)
        val open = PendingIntent.getActivity(this, ID, Intent(this, TranscribeActivity::class.java), flags)
        val paused = state.paused
        // Generic text only: transcripts never appear on the lock screen.
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_surface)
            .setContentTitle(if (paused) (if (state.recording) "Recording paused" else "Transcription paused") else if (state.recording) "Recording audio on this phone" else "Transcribing on this phone")
            .setContentText(if (paused) "Tap Resume to continue or Stop to save" else if (state.recording) "Audio saves on this phone as you record" else "Verbatim lines save as you speak")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(true)
            .setWhen(state.started).setShowWhen(true).setUsesChronometer(!paused)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause), if (paused) "Resume" else "Pause", command(if (paused) RESUME else PAUSE)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_save), "Stop & save", command(STOP)).build())
            .build()
    }

    override fun onDestroy() {
        engine?.cancel(); engine = null
        main.removeCallbacks(meter); main.removeCallbacks(rollover)
        if (state.active && state.recording) closePart()
        if (state.active) state = TranscriptionState(record = state.record, segments = state.segments, message = "Transcription stopped. Saved lines are in Notes.", language = state.language)
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "transcription"
        private const val ID = 43
        const val PAUSE = "com.caceras.surfacelab.TRANSCRIBE_PAUSE"
        const val RESUME = "com.caceras.surfacelab.TRANSCRIBE_RESUME"
        const val STOP = "com.caceras.surfacelab.TRANSCRIBE_STOP"
        const val RECORD = "record"
        private var active: TranscriptionService? = null
        @Volatile var state = TranscriptionState(); private set

        /** Call only from a visible screen after microphone permission is granted. */
        fun start(context: Context, record: Boolean = false) { context.startForegroundService(Intent(context, TranscriptionService::class.java).putExtra(RECORD, record)) }
        fun command(context: Context, action: String) { if (active != null) context.startService(Intent(context, TranscriptionService::class.java).setAction(action)) }
        /** Another capture in this app needs the microphone: pause, never resume by itself. */
        fun yieldMicrophone() { active?.pause("Paused while you dictate elsewhere. Tap Resume to continue.") }
        /** Acknowledge a finished session so its message is not shown again. */
        fun clearFinished() { if (!state.active) state = TranscriptionState() }
    }
}
