package com.caceras.surfacelab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build

/** Foreground speech transport only. No background service or private answer metadata. */
class SpeechControls(context: Context, private val onStop: (String) -> Unit) {
    private val app = context.applicationContext
    private val session = MediaSession(app, "AegenticaSpeech")
    internal val callback = object : MediaSession.Callback() {
        override fun onPause() { stopRequested("Playback stopped from your audio controls.") }
        override fun onStop() { stopRequested("Playback stopped from your audio controls.") }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                stopRequested("Headphones disconnected. Your answer stays on screen.")
        }
    }
    init {
        session.setCallback(callback)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Ægentica AI · Spoken reply").build())
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") app.registerReceiver(noisy, filter) }
    }
    private fun stopRequested(message: String) { if (session.isActive) onStop(message) }
    fun playing() {
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_STOP or PlaybackState.ACTION_PAUSE)
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        session.isActive = true
    }
    fun stopped() {
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
        session.isActive = false
    }
    fun close() {
        stopped()
        app.unregisterReceiver(noisy)
        session.release()
    }
}
