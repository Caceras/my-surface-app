package com.caceras.surfacelab

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock

data class TranscriptionState(
    val active: Boolean = false, val paused: Boolean = false, val record: String = "", val started: Long = 0,
    val startedClock: Long = 0, val partial: String = "", val segments: Int = 0, val message: String = "",
    val engine: String = "", val language: String = "", val level: Float = 0f
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

    override fun onBind(intent: Intent?) = null
    override fun onCreate() { super.onCreate(); active = this }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSE -> pause("")
            RESUME -> resume()
            STOP -> stop()
            else -> begin()
        }
        // A notification action can arrive after the session ended; never leave an idle started service.
        if (!state.active && !stopping) stopSelf()
        return START_NOT_STICKY
    }

    private fun begin() {
        if (state.active) { foreground(); return }
        val language = Ears(this).locale()
        state = TranscriptionState(active = true, started = System.currentTimeMillis(), startedClock = SystemClock.elapsedRealtime(), language = language.toLanguageTag())
        if (!foreground()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission is needed to transcribe."); return }
        ReadingService.pauseForCapture()
        part = 1
        listen()
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
        val current = engine
        state = state.copy(paused = true, message = message); publish()
        current?.finish { if (engine === current) engine = null; state = state.copy(partial = ""); publish() }
    }

    private fun resume() {
        if (!state.active || !state.paused || stopping) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { end("Microphone permission is needed to transcribe."); return }
        ReadingService.pauseForCapture()
        engine?.cancel(); engine = null
        listen()
    }

    private fun stop() {
        if (stopping) return
        if (!state.active) { stopSelf(); return }
        stopping = true
        val current = engine
        if (current == null) { end(""); return }
        current.finish { end("") }
    }

    private fun end(message: String) {
        engine?.cancel(); engine = null
        state = TranscriptionState(record = state.record, segments = state.segments, message = message, language = state.language)
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
            .setContentTitle(if (paused) "Transcription paused" else "Transcribing on this phone")
            .setContentText(if (paused) "Tap Resume to continue or Stop to save" else "Verbatim lines save as you speak")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(true)
            .setWhen(state.started).setShowWhen(true).setUsesChronometer(!paused)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause), if (paused) "Resume" else "Pause", command(if (paused) RESUME else PAUSE)).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_save), "Stop & save", command(STOP)).build())
            .build()
    }

    override fun onDestroy() {
        engine?.cancel(); engine = null
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
        private var active: TranscriptionService? = null
        @Volatile var state = TranscriptionState(); private set

        /** Call only from a visible screen after microphone permission is granted. */
        fun start(context: Context) { context.startForegroundService(Intent(context, TranscriptionService::class.java)) }
        fun command(context: Context, action: String) { if (active != null) context.startService(Intent(context, TranscriptionService::class.java).setAction(action)) }
        /** Another capture in this app needs the microphone: pause, never resume by itself. */
        fun yieldMicrophone() { active?.pause("Paused while you dictate elsewhere. Tap Resume to continue.") }
        /** Acknowledge a finished session so its message is not shown again. */
        fun clearFinished() { if (!state.active) state = TranscriptionState() }
    }
}
