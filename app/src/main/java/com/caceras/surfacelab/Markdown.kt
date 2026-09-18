package com.caceras.surfacelab

import android.graphics.Typeface
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.widget.TextView
import java.util.WeakHashMap

/** Framework-only formatting for the Markdown used in assistant replies. */
object Markdown {
    private val BULLET = Regex("""^\s*[*\-+]\s+""")
    private val HEADING = Regex("""^\s*#{1,6}\s+""")
    private val FENCE = Regex("""^\s*(`{3,}|~{3,})(.*)$""")
    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    // UI-thread only. A correction must reformat earlier lines, even when their text is unchanged.
    private val streamSources = WeakHashMap<TextView, String>()

    fun render(source: String, bulletIndent: Int = 0): CharSequence {
        val out = SpannableStringBuilder()
        var fence = ""
        var first = true
        for (raw in source.split('\n')) {
            val marker = FENCE.matchEntire(raw)
            if (marker != null) {
                val token = marker.groupValues[1]
                if (fence.isEmpty()) {
                    fence = token
                    continue
                }
                if (token[0] == fence[0] && token.length >= fence.length && marker.groupValues[2].isBlank()) {
                    fence = ""
                    continue
                }
            }
            if (!first) out.append('\n')
            first = false
            val start = out.length
            if (fence.isNotEmpty()) {
                out.append(raw)
                if (out.length > start) out.setSpan(TypefaceSpan("monospace"), start, out.length, FLAGS)
                continue
            }
            val heading = HEADING.find(raw)
            val bullet = if (heading == null) BULLET.find(raw) else null
            val line = when {
                heading != null -> raw.substring(heading.value.length)
                bullet != null -> "\u2022  " + raw.substring(bullet.value.length)
                else -> raw
            }
            appendInline(out, line, 0)
            if (out.length > start) {
                if (bullet != null && bulletIndent > 0)
                    out.setSpan(LeadingMarginSpan.Standard(0, bulletIndent), start, out.length, FLAGS)
                if (heading != null)
                    out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, FLAGS)
            }
        }
        return out
    }

    /** Keep the TextView buffer and completed lines instead of resetting the entire layout. */
    fun update(view: TextView, source: String, bulletIndent: Int = 0) {
        val previousSource = streamSources.put(view, source)
        val rendered = render(source, bulletIndent)
        val current = view.text as? Editable
        if (current == null || previousSource == null || !source.startsWith(previousSource)) {
            view.setText(rendered, TextView.BufferType.EDITABLE)
            return
        }
        var common = 0
        val limit = minOf(current.length, rendered.length)
        while (common < limit && current[common] == rendered[common]) common++
        // A closing marker can change formatting earlier in the final line.
        var boundary = common
        while (boundary > 0 && current[boundary - 1] != '\n') boundary--
        if (boundary == rendered.length && boundary > 0) {
            boundary--
            while (boundary > 0 && current[boundary - 1] != '\n') boundary--
        }
        current.replace(boundary, current.length, rendered, boundary, rendered.length)
    }

    private fun appendInline(out: SpannableStringBuilder, line: String, depth: Int) {
        if (depth >= 8) { out.append(line); return }
        var i = 0
        while (i < line.length) {
            val char = line[i]
            if (char == '\\' && i + 1 < line.length && line[i + 1] in "\\`*_{}[]()#+-.!>~") {
                out.append(line[i + 1]); i += 2; continue
            }
            if (char == '`') {
                val count = runLength(line, i, char)
                val token = "`".repeat(count)
                val end = line.indexOf(token, i + count)
                if (end >= 0) {
                    val start = out.length
                    out.append(line.substring(i + count, end))
                    if (out.length > start) out.setSpan(TypefaceSpan("monospace"), start, out.length, FLAGS)
                    i = end + count
                    continue
                }
                out.append(token); i += count; continue
            }
            if (char == '*' || char == '_') {
                val run = runLength(line, i, char)
                val count = minOf(run, 3)
                val after = i + count
                val intraword = char == '_' && i > 0 && line[i - 1].isLetterOrDigit()
                if (!intraword && after < line.length && !line[after].isWhitespace()) {
                    val end = closing(line, after, char, count)
                    if (end > after) {
                        val start = out.length
                        appendInline(out, line.substring(after, end), depth + 1)
                        val style = when (count) {
                            1 -> Typeface.ITALIC
                            2 -> Typeface.BOLD
                            else -> Typeface.BOLD_ITALIC
                        }
                        if (out.length > start) out.setSpan(StyleSpan(style), start, out.length, FLAGS)
                        i = end + count
                        continue
                    }
                }
                out.append(line, i, i + run); i += run; continue
            }
            out.append(char)
            i++
        }
    }

    private fun runLength(text: String, from: Int, char: Char): Int {
        var end = from
        while (end < text.length && text[end] == char) end++
        return end - from
    }

    private fun closing(text: String, from: Int, char: Char, count: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] == '`') {
                val ticks = runLength(text, i, '`')
                val close = text.indexOf("`".repeat(ticks), i + ticks)
                if (close >= 0) { i = close + ticks; continue }
            }
            if (text[i] != char) { i++; continue }
            val run = runLength(text, i, char)
            val end = i + run
            val boundary = char != '_' || end == text.length || !text[end].isLetterOrDigit()
            val compatible = run >= count && (count != 1 || run % 2 == 1)
            if (compatible && boundary && i > from && !text[i - 1].isWhitespace()) return end - count
            i = end
        }
        return -1
    }

    /** Use the same parsing rules for speech, widgets and plain-text previews. */
    fun strip(source: String): String = render(source).toString()
}
