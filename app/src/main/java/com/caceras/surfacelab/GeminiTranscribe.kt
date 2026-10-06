package com.caceras.surfacelab

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Speaker-labelled transcription of recorded audio with Google's
 * gemini-3.5-transcribe model (Interactions API). Each part is uploaded with
 * the Files API, transcribed with store=false so Google keeps no interaction
 * record (by default it keeps them 55 days on paid keys), and the uploaded
 * file is deleted straight afterwards.
 */
object GeminiTranscribe {
    const val MODEL = "gemini-3.5-transcribe"
    /** Google limits diarized requests to 30 minutes; plain transcription to 60. */
    const val DIARIZE_MAX_MS = 30 * 60_000L
    const val MAX_MS = 60 * 60_000L
    private const val BASE = "https://generativelanguage.googleapis.com"

    data class Response(val code: Int, val headers: Map<String, String>, val body: String)
    interface Http { fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null, file: File? = null): Response }
    @Volatile var httpForTest: Http? = null
    private val http: Http get() = httpForTest ?: UrlHttp

    data class Segment(val speaker: String, val startMs: Long, val text: String)
    class Cancelled : Exception("Transcription cancelled.")

    fun request(uri: String, mime: String, diarize: Boolean): JSONObject = JSONObject()
        .put("model", MODEL).put("store", false)
        .put("input", JSONArray().put(JSONObject().put("type", "audio").put("uri", uri).put("mime_type", mime)))
        .put("generation_config", JSONObject().put("transcription_config", JSONObject()
            // Empty language list: automatic detection, including Swedish/English code-switching.
            .put("language_codes", JSONArray())
            // Speaker labels and word times share Google's 30-minute limit; word times give each turn its [mm:ss] stamp.
            .apply { if (diarize) put("mode", JSONObject().put("type", "verbatim").put("diarization_mode", "speaker").put("timestamp_granularities", JSONArray().put("word"))) }))

    private fun seconds(offset: String) = offset.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }

    /** Groups word annotations into speaker turns; falls back to plain output text. */
    fun parse(json: JSONObject): List<Segment> {
        val words = mutableListOf<Triple<String, Long?, String>>()
        val texts = mutableListOf<String>()
        val steps = json.optJSONArray("steps") ?: JSONArray()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            if (step.optString("type", "model_output") != "model_output") continue
            val content = step.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") != "text") continue
                part.optString("text").takeIf { it.isNotBlank() }?.let { texts += it }
                val notes = part.optJSONArray("annotations") ?: continue
                for (k in 0 until notes.length()) notes.optJSONObject(k)?.takeIf { it.optString("type") == "word_info" }?.let { w ->
                    words += Triple(w.optString("speaker"), seconds(w.optString("start_offset")), w.optString("text"))
                }
            }
        }
        if (words.none { it.first.isNotBlank() }) {
            val text = texts.joinToString("\n").ifBlank { json.optString("output_text") }.trim()
            return if (text.isEmpty()) emptyList() else listOf(Segment("", 0, text))
        }
        val out = mutableListOf<Segment>()
        for ((speaker, start, text) in words) {
            val last = out.lastOrNull()
            if (last != null && last.speaker == speaker) out[out.lastIndex] = last.copy(text = last.text + " " + text)
            else out += Segment(speaker, start ?: last?.startMs ?: 0, text)
        }
        return out.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
    }

    /** "spk_2" becomes "Speaker 2", matching the rename tool. */
    fun format(segments: List<Segment>, offsetMs: Long): String = segments.joinToString("\n") { s ->
        val who = s.speaker.removePrefix("spk_").toIntOrNull()?.let { "Speaker $it: " } ?: if (s.speaker.isBlank()) "" else "${s.speaker}: "
        Transcripts.line(offsetMs + s.startMs, who + s.text)
    }

    private fun headers(key: String) = mapOf("x-goog-api-key" to key)
    private fun check(response: Response, what: String) {
        if (response.code in 200..299) return
        val detail = runCatching { JSONObject(response.body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(200)
        error(when (response.code) {
            400 -> "Gemini could not $what: ${detail.ifBlank { "the request was rejected." }}"
            401, 403 -> "Gemini rejected the key while trying to $what. Check it in Connected AI."
            413 -> "The recording is too large for Gemini."
            429 -> "Gemini's rate or spending limit was reached. Try again later or check AI Studio."
            else -> "Gemini could not $what (HTTP ${response.code}). ${detail}".trim()
        })
    }

    /** Uploads one file and returns (name, uri). */
    fun upload(key: String, file: File, mime: String, cancelled: () -> Boolean = { false }): Pair<String, String> {
        val start = http.send("POST", "$BASE/upload/v1beta/files", headers(key) + mapOf(
            "X-Goog-Upload-Protocol" to "resumable", "X-Goog-Upload-Command" to "start",
            "X-Goog-Upload-Header-Content-Length" to file.length().toString(), "X-Goog-Upload-Header-Content-Type" to mime,
            "Content-Type" to "application/json"), JSONObject().put("file", JSONObject().put("display_name", "aegentica-recording")).toString().toByteArray())
        check(start, "start the upload")
        val url = start.headers.entries.firstOrNull { it.key.equals("x-goog-upload-url", true) }?.value ?: error("Gemini did not return an upload address.")
        if (cancelled()) throw Cancelled()
        val done = http.send("POST", url, mapOf("X-Goog-Upload-Offset" to "0", "X-Goog-Upload-Command" to "upload, finalize"), file = file)
        check(done, "upload the recording")
        val info = JSONObject(done.body).getJSONObject("file")
        var name = info.getString("name"); val uri = info.getString("uri")
        var state = info.optString("state")
        var waited = 0
        while (state == "PROCESSING" && waited < 120) {
            if (cancelled()) { delete(key, name); throw Cancelled() }
            Thread.sleep(2000); waited += 2
            val poll = http.send("GET", "$BASE/v1beta/$name", headers(key)); check(poll, "prepare the recording")
            val polled = JSONObject(poll.body); state = polled.optString("state"); name = polled.optString("name", name)
        }
        if (state == "FAILED") { delete(key, name); error("Gemini could not process this audio file.") }
        return name to uri
    }

    fun delete(key: String, name: String) { runCatching { http.send("DELETE", "$BASE/v1beta/$name", headers(key)) } }

    /**
     * Transcribes parts in order, offsetting time stamps by the preceding
     * parts. Runs on a worker thread; [progress] receives plain status text.
     */
    fun transcribe(key: String, parts: List<Pair<File, AudioNotes.Part>>, cancelled: () -> Boolean, progress: (String) -> Unit): String {
        val lines = mutableListOf<String>()
        var offset = 0L
        parts.forEachIndexed { index, (file, part) ->
            if (cancelled()) throw Cancelled()
            require(file.isFile && file.length() > 0) { "Part ${index + 1} of the recording is missing on this phone." }
            require(part.ms <= MAX_MS) { "Part ${index + 1} is longer than 60 minutes; Gemini cannot transcribe it in one request." }
            val label = if (parts.size > 1) " part ${index + 1} of ${parts.size}" else ""
            progress("Uploading$label…")
            val (name, uri) = upload(key, file, part.mime, cancelled)
            try {
                if (cancelled()) throw Cancelled()
                progress("Transcribing$label with Gemini…")
                val response = http.send("POST", "$BASE/v1beta/interactions", headers(key) + ("Content-Type" to "application/json"),
                    request(uri, part.mime, diarize = part.ms <= DIARIZE_MAX_MS).toString().toByteArray())
                check(response, "transcribe the recording")
                val segments = parse(JSONObject(response.body))
                if (segments.isNotEmpty()) lines += format(segments, offset)
            } finally { delete(key, name) }
            offset += part.ms
        }
        return lines.joinToString("\n").ifBlank { error("Gemini returned no words for this recording.") }
    }

    fun cost(ms: Long) = String.format(java.util.Locale.ROOT, "%.2f", ms / 60000.0 * 0.005)

    /** Framework HTTPS; redirects refused, as for connected AI. */
    private object UrlHttp : Http {
        override fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray?, file: File?): Response {
            val conn = URL(url).openConnection() as HttpsURLConnection
            try {
                conn.instanceFollowRedirects = false; conn.requestMethod = method
                conn.connectTimeout = 20000; conn.readTimeout = if (url.contains("/interactions")) 600000 else 120000
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val length = file?.length() ?: body?.size?.toLong()
                if (length != null) {
                    conn.doOutput = true; conn.setFixedLengthStreamingMode(length)
                    conn.outputStream.use { out -> if (file != null) file.inputStream().use { it.copyTo(out) } else out.write(body!!) }
                }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.use { it.readBounded(8_000_000).toString(Charsets.UTF_8) }.orEmpty()
                val names = conn.headerFields.filterKeys { it != null }.mapValues { it.value.firstOrNull().orEmpty() }
                return Response(code, names, text)
            } finally { conn.disconnect() }
        }
    }
}
