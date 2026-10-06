package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/**
 * Share an audio file into Ægentica (for example a Pixel Recorder export or a
 * voice message). The file is copied into app-private storage as a recording
 * note; nothing is uploaded until the user chooses Transcribe with Gemini.
 */
class AudioShareActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(AdaptiveFrame(this, label("Saving audio on this phone…", 16f, true).apply { padDp(24, 48, 24, 24) }).apply { padForSystemBars() })
        val uri = stream(intent)
        if (state != null) return
        if (uri == null) { done(null, "Nothing to save. Share an audio file."); return }
        val app = applicationContext
        val type = intent.type
        Thread {
            val result = runCatching { import(app, uri, type) }
            runOnUiThread { done(result.getOrNull(), result.exceptionOrNull()?.message) }
        }.start()
    }

    private fun stream(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)

    private fun done(id: String?, problem: String?) {
        if (isDestroyed) return
        if (id != null) { startActivity(WorkspaceActivity.intent(this, "library", id)); Toast.makeText(this, "Audio saved. Choose Transcribe with Gemini for the words.", Toast.LENGTH_LONG).show() }
        else Toast.makeText(this, problem ?: "Could not save that audio.", Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024
        /** Formats Gemini Transcribe documents, mapped from common Android MIME types. */
        private val TYPES = mapOf(
            "audio/mp4" to "audio/m4a", "audio/m4a" to "audio/m4a", "audio/x-m4a" to "audio/m4a", "audio/aac" to "audio/aac",
            "audio/mpeg" to "audio/mpeg", "audio/mp3" to "audio/mp3", "audio/ogg" to "audio/ogg", "audio/opus" to "audio/opus",
            "audio/wav" to "audio/wav", "audio/x-wav" to "audio/wav", "audio/flac" to "audio/flac", "audio/webm" to "audio/webm")
        private val EXTENSIONS = mapOf("m4a" to "audio/m4a", "mp4" to "audio/m4a", "aac" to "audio/aac", "mp3" to "audio/mp3", "ogg" to "audio/ogg",
            "opus" to "audio/opus", "wav" to "audio/wav", "flac" to "audio/flac", "webm" to "audio/webm")

        fun import(context: Context, uri: Uri, declared: String?): String {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                ?: uri.lastPathSegment.orEmpty()
            val extension = name.substringAfterLast('.', "").lowercase()
            val mime = TYPES[(resolver.getType(uri) ?: declared).orEmpty().lowercase()] ?: TYPES[declared.orEmpty().lowercase()] ?: EXTENSIONS[extension]
                ?: error("This audio format cannot be transcribed by Gemini. Share M4A, AAC, MP3, OGG, Opus, WAV, FLAC or WebM.")
            val store = WorkspaceStore(context)
            try {
                val note = store.save(Record(title = "Shared audio · " + name.ifBlank { "recording" }.take(120), source = "Transcript · shared audio · " + name.take(200)))
                // The shared name is untrusted: only a known extension is reused in the private file name.
                val target = File(AudioNotes.dir(context), "${note.id}-1." + (extension.takeIf { it in EXTENSIONS } ?: mime.substringAfter('/')))
                try {
                    resolver.openInputStream(uri)?.use { input -> target.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_BYTES) { "The audio is larger than 300 MB." }; out.write(buffer, 0, n) }
                    } } ?: error("Cannot open that audio.")
                    require(target.length() > 0) { "The shared audio is empty." }
                } catch (e: Exception) { target.delete(); store.save(note.copy(deleted = true)); store.purge(note.id); throw e }
                val ms = AudioNotes.duration(target) ?: 0L
                AudioNotes.add(store, note.id, AudioNotes.Part(target.name, ms, mime))
                return note.id
            } finally { store.close() }
        }
    }
}
