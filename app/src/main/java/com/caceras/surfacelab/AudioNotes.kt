package com.caceras.surfacelab

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Audio is the truest original. Recordings live in app-private storage as
 * AAC parts of at most [PART_MS] (Gemini's speaker labelling is limited to 30
 * minutes per request) and are listed under the note's content key. Parts are
 * ADTS streams, so a part cut off by a crash or a killed process still plays
 * and is listed again by [recover]. They are excluded from backups, exports
 * and the Markdown vault, and are deleted with the note.
 */
object AudioNotes {
    const val PART_MS = 25 * 60_000L
    const val SOURCE = "Transcript · audio recording · on this phone"
    const val MIME = "audio/aac"
    const val EXTENSION = "aac"
    /** About 32 kbps; used only to estimate the length of a recovered part. */
    private const val BYTES_PER_SECOND = 4000L
    data class Part(val file: String, val ms: Long, val mime: String = MIME)

    fun dir(context: Context) = File(context.applicationContext.filesDir, "audio").apply { mkdirs() }
    fun file(context: Context, part: Part) = File(dir(context), part.file)
    private fun key(id: String) = "audio:$id"

    fun parts(store: WorkspaceStore, id: String): List<Part> = runCatching {
        val array = JSONArray(store.value(key(id), "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { p -> Part(p.getString("file"), p.optLong("ms"), p.optString("mime", MIME)) } }
    }.getOrDefault(emptyList())

    fun add(store: WorkspaceStore, id: String, part: Part) {
        val array = JSONArray(store.value(key(id), "[]"))
        array.put(JSONObject().put("file", part.file).put("ms", part.ms).put("mime", part.mime))
        store.put(key(id), array.toString())
    }

    fun totalMs(parts: List<Part>) = parts.sumOf { it.ms }

    /** Removes the files and the list; called when a note is permanently deleted. */
    fun delete(context: Context, store: WorkspaceStore, id: String) {
        parts(store, id).forEach { file(context, it).delete() }
        store.writableDatabase.delete("content", "key=?", arrayOf(key(id)))
    }

    /**
     * Lists recorded parts that were never listed because the app stopped
     * mid-recording. Call only while no recording is active. Unknown files and
     * files of missing notes are left untouched; empty files are removed.
     */
    fun recover(context: Context, store: WorkspaceStore): Int {
        val pattern = Regex("^(.+)-(\\d+)\\.$EXTENSION$")
        var count = 0
        val files = dir(context).listFiles().orEmpty().mapNotNull { f -> pattern.matchEntire(f.name)?.let { Triple(f, it.groupValues[1], it.groupValues[2].toInt()) } }
        for ((f, id, _) in files.sortedWith(compareBy({ it.second }, { it.third }))) {
            if (store.get(id) == null || parts(store, id).any { it.file == f.name }) continue
            if (f.length() == 0L) { f.delete(); continue }
            add(store, id, Part(f.name, duration(f) ?: f.length() * 1000 / BYTES_PER_SECOND)); count++
        }
        return count
    }

    fun duration(file: File): Long? = runCatching {
        android.media.MediaMetadataRetriever().run { try { setDataSource(file.path); extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() } finally { release() } }
    }.getOrNull()?.takeIf { it > 0 }

    /** Speech-tuned AAC: 16 kHz mono at 32 kbps is about 14 MB per hour. ADTS survives an abrupt stop. */
    fun recorder(context: Context, output: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioChannels(1)
        recorder.setAudioSamplingRate(16000)
        recorder.setAudioEncodingBitRate(32000)
        recorder.setOutputFile(output.path)
        return recorder
    }
}

/** Speaker labels from transcription can be renamed in the working text; the verbatim original keeps them. */
object Speakers {
    private val LABEL = Regex("^(\\[[0-9:]+] )?(Speaker \\d+):", RegexOption.MULTILINE)
    fun labels(text: String): List<String> = LABEL.findAll(text).map { it.groupValues[2] }.distinct().toList()
    fun rename(text: String, names: Map<String, String>): String = LABEL.replace(text) { m ->
        val name = names[m.groupValues[2]]?.trim()?.takeIf { it.isNotEmpty() && !it.contains('\n') } ?: m.groupValues[2]
        m.groupValues[1] + name + ":"
    }
}
