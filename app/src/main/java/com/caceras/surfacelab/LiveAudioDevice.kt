package com.caceras.surfacelab

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Framework audio for Gemini Live: 100 ms chunks of 16 kHz mono PCM16 from the
 * voice-communication source (platform echo cancellation where available), and
 * a streaming 24 kHz player whose queue can be dropped instantly on barge-in.
 * Callers hold microphone permission and audio focus.
 */
class LiveAudioDevice : LiveAudio {
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private var writer: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()

    @SuppressLint("MissingPermission")
    override fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
        if (running) return true
        val chunk = IN_RATE / 10 * 2
        val input = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), chunk * 4))
        } catch (_: Exception) { return false }
        if (input.state != AudioRecord.STATE_INITIALIZED) { input.release(); return false }
        if (AcousticEchoCanceler.isAvailable()) echo = runCatching { AcousticEchoCanceler.create(input.audioSessionId)?.apply { enabled = true } }.getOrNull()
        if (NoiseSuppressor.isAvailable()) noise = runCatching { NoiseSuppressor.create(input.audioSessionId)?.apply { enabled = true } }.getOrNull()
        val output = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT), OUT_RATE / 5 * 2))
                .build()
        } catch (_: Exception) { input.release(); return false }
        record = input; track = output; running = true; queue.clear()
        try { input.startRecording(); output.play() } catch (_: Exception) { stop(); return false }
        reader = Thread({
            val buffer = ByteArray(chunk)
            while (running) {
                var filled = 0
                while (running && filled < chunk) { val n = input.read(buffer, filled, chunk - filled); if (n <= 0) break; filled += n }
                if (!running || filled <= 0) break
                onChunk(buffer.copyOf(filled)); onLevel(level(buffer, filled))
            }
        }, "live-mic").apply { start() }
        writer = Thread({
            while (running) {
                val pcm = try { queue.take() } catch (_: InterruptedException) { break }
                if (pcm.isEmpty() || !running) continue
                runCatching { output.write(pcm, 0, pcm.size) }
            }
        }, "live-speaker").apply { start() }
        return true
    }

    override fun play(pcm: ByteArray) { if (running) queue.offer(pcm) }

    /** Barge-in: drop queued speech and anything the player has buffered. */
    override fun flush() {
        queue.clear()
        track?.let { runCatching { it.pause(); it.flush(); it.play() } }
    }

    override fun stop() {
        running = false
        queue.clear(); queue.offer(ByteArray(0))
        writer?.interrupt()
        runCatching { record?.stop() }
        runCatching { reader?.join(300) }; runCatching { writer?.join(300) }
        runCatching { echo?.release() }; runCatching { noise?.release() }
        runCatching { record?.release() }
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        record = null; track = null; reader = null; writer = null; echo = null; noise = null
    }

    private fun level(buffer: ByteArray, size: Int): Float {
        var sum = 0.0; var count = 0
        var i = 0
        while (i + 1 < size) { val s = (buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8); sum += s.toDouble() * s; count++; i += 2 }
        if (count == 0) return 0f
        val rms = sqrt(sum / count)
        return if (rms <= 1) 0f else (20 * log10(rms / 32768.0) + 60).toFloat().coerceIn(0f, 10f)
    }

    companion object { const val IN_RATE = 16000; const val OUT_RATE = 24000 }
}
