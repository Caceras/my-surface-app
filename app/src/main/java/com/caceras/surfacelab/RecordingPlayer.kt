package com.caceras.surfacelab

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/** Plays a note's audio parts in order. Foreground and owned by one dialog; release() on dismiss. */
class RecordingPlayer(private val files: List<File>, private val onChange: (playing: Boolean, part: Int) -> Unit) {
    private var player: MediaPlayer? = null
    private var index = 0
    var playing = false; private set

    fun toggle() { if (playing) pause() else play() }

    private fun play() {
        val current = player ?: open(index) ?: return
        runCatching { current.start() }.onSuccess { playing = true; onChange(true, index) }
    }

    private fun open(i: Int): MediaPlayer? {
        if (i !in files.indices) return null
        release()
        index = i
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(files[i].path); prepare()
                setOnCompletionListener { next() }
            }
        }.getOrNull().also { player = it }
    }

    private fun next() {
        if (index + 1 < files.size) { open(index + 1)?.let { runCatching { it.start() }; playing = true; onChange(true, index) } }
        else { release(); index = 0; playing = false; onChange(false, 0) }
    }

    fun pause() { runCatching { player?.pause() }; playing = false; onChange(false, index) }
    fun positionMs(): Long = runCatching { player?.currentPosition?.toLong() ?: 0L }.getOrDefault(0L)
    fun release() { runCatching { player?.release() }; player = null; playing = false }
}
