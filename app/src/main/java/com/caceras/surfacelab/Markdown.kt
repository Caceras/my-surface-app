package com.caceras.surfacelab

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan

/**
 * The model answers in Markdown whether or not anyone asked it to, so a
 * TextView shows the raw punctuation:
 *
 *     *   **Ukraine War:** Fighting remains intense
 *
 * That is not a formatting preference, it is the answer looking broken. This
 * renders the small subset the model actually reaches for -- bold, bullets,
 * headings -- and leaves everything else alone.
 *
 * Framework spans only. Pulling in a Markdown library for four rules would
 * cost a dependency the app does not otherwise have.
 */
object Markdown {

    private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val BULLET = Regex("""^\s*[*\-+]\s+""")
    private val HEADING = Regex("""^\s*#{1,6}\s+""")

    fun render(source: String): CharSequence {
        val out = SpannableStringBuilder()

        source.split("\n").forEachIndexed { index, raw ->
            if (index > 0) out.append("\n")

            val heading = HEADING.find(raw)
            val bullet = BULLET.find(raw)

            var line = raw
            if (heading != null) {
                line = raw.substring(heading.value.length)
            } else if (bullet != null) {
                line = "•  " + raw.substring(bullet.value.length)
            }

            val start = out.length
            appendWithBold(out, line)

            // A heading is the whole line in bold, after its hashes are gone.
            if (heading != null && out.length > start) {
                out.setSpan(
                    StyleSpan(Typeface.BOLD), start, out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        return out
    }

    /** Copies [line] into [out], turning **runs** into real bold. */
    private fun appendWithBold(out: SpannableStringBuilder, line: String) {
        var cursor = 0
        for (match in BOLD.findAll(line)) {
            out.append(line, cursor, match.range.first)
            val inner = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val start = out.length
            out.append(inner)
            out.setSpan(
                StyleSpan(Typeface.BOLD), start, out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            cursor = match.range.last + 1
        }
        out.append(line, cursor, line.length)
    }

    /** The same subset as plain text, for anywhere a span cannot go. */
    fun strip(source: String): String =
        source.split("\n").joinToString("\n") { raw ->
            var line = raw
            HEADING.find(line)?.let { line = line.substring(it.value.length) }
            BULLET.find(line)?.let { line = "•  " + line.substring(it.value.length) }
            BOLD.replace(line) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        }
}
