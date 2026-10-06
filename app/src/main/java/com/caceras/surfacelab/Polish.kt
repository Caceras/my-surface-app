package com.caceras.surfacelab

import android.content.Context
import java.text.BreakIterator

/** Splits long text at paragraph, line and sentence boundaries so each part fits a model request. */
object Polish {
    const val NANO_CHUNK = 1800
    const val CONNECTED_CHUNK = 6000
    fun chunks(text: String, limit: Int): List<String> {
        val pieces = text.lines().flatMap { line -> if (line.length <= limit) listOf(line) else sentences(line, limit) }
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (piece in pieces) {
            if (current.isNotEmpty() && current.length + 1 + piece.length > limit) { out += current.toString(); current.clear() }
            if (current.isNotEmpty()) current.append('\n')
            current.append(piece)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }
    private fun sentences(line: String, limit: Int): List<String> {
        val iterator = BreakIterator.getSentenceInstance(); iterator.setText(line)
        val out = mutableListOf<String>()
        var begin = iterator.first(); var end = iterator.next()
        while (end != BreakIterator.DONE) {
            var rest = line.substring(begin, end)
            while (rest.length > limit) {
                var cut = rest.lastIndexOf(' ', limit).takeIf { it > limit / 2 } ?: limit
                if (rest[cut - 1].isHighSurrogate()) cut--
                out += rest.substring(0, cut); rest = rest.substring(cut)
            }
            out += rest
            begin = end; end = iterator.next()
        }
        return out
    }
}

/**
 * Sequential foreground polish of a copy. Nothing is stored here: the caller
 * shows the result for review, and the record's verbatim original is untouched.
 */
class PolishRun(private val context: Context, private val brain: SurfaceBrain, text: String) {
    val parts = Polish.chunks(Transcripts.words(text).trim(), if (brain === ConnectedAI.brain) Polish.CONNECTED_CHUNK else Polish.NANO_CHUNK)
    /** True when a part came back far shorter than it went in, which suggests summarising rather than polishing. */
    var shortened = false; private set
    private val out = mutableListOf<String>()
    private var done = false
    private var progress: (Int, Int) -> Unit = { _, _ -> }
    private var finished: (String?, String?) -> Unit = { _, _ -> }

    fun start(onProgress: (Int, Int) -> Unit, onFinished: (String?, String?) -> Unit) { progress = onProgress; finished = onFinished; next() }

    private fun next() {
        if (done) return
        if (out.size == parts.size) { done = true; finished(out.joinToString("\n\n"), null); return }
        progress(out.size, parts.size)
        val part = parts[out.size]
        brain.run(context, Task.POLISH, part) { result ->
            if (done) return@run
            val text = Prompts.reply(result.text).trim()
            if (!result.ok || text.isBlank() || Prompts.isEcho(text, Task.POLISH)) {
                done = true; finished(null, result.note?.let { "Polishing stopped: $it" } ?: "Polishing stopped. Your text is unchanged."); return@run
            }
            if (part.length > 400 && text.length < part.length * 0.45) shortened = true
            out += text
            next()
        }
    }

    fun cancel(reason: String = "Polishing cancelled. Your text is unchanged.") {
        if (done) return
        done = true; brain.cancel(); finished(null, reason)
    }
}
