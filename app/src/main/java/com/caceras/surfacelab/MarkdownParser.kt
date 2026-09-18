package com.caceras.surfacelab

/** Pure parsing shared by Android spans, speech and JVM-only callers. */
internal object MarkdownParser {
    enum class Style { BOLD, ITALIC, BOLD_ITALIC, CODE, INDENT }
    data class Span(val style: Style, val start: Int, val end: Int, val indent: Int = 0)
    data class Document(val text: String, val spans: List<Span>)

    private val BULLET = Regex("""^\s*[*\-+]\s+""")
    private val HEADING = Regex("""^\s*#{1,6}\s+""")
    private val FENCE = Regex("""^\s*(`{3,}|~{3,})(.*)$""")

    fun parse(source: String, bulletIndent: Int = 0): Document {
        val out = StringBuilder()
        val spans = mutableListOf<Span>()
        var fence = ""
        var first = true
        for (raw in source.split('\n')) {
            val marker = FENCE.matchEntire(raw)
            if (marker != null) {
                val token = marker.groupValues[1]
                if (fence.isEmpty()) { fence = token; continue }
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
                if (out.length > start) spans += Span(Style.CODE, start, out.length)
                continue
            }
            val heading = HEADING.find(raw)
            val bullet = if (heading == null) BULLET.find(raw) else null
            val line = when {
                heading != null -> raw.substring(heading.value.length)
                bullet != null -> "\u2022  " + raw.substring(bullet.value.length)
                else -> raw
            }
            appendInline(out, spans, line, 0)
            if (out.length > start) {
                if (bullet != null && bulletIndent > 0)
                    spans += Span(Style.INDENT, start, out.length, bulletIndent)
                if (heading != null) spans += Span(Style.BOLD, start, out.length)
            }
        }
        return Document(out.toString(), spans)
    }

    private fun appendInline(out: StringBuilder, spans: MutableList<Span>, line: String, depth: Int) {
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
                    if (out.length > start) spans += Span(Style.CODE, start, out.length)
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
                        appendInline(out, spans, line.substring(after, end), depth + 1)
                        val style = when (count) {
                            1 -> Style.ITALIC
                            2 -> Style.BOLD
                            else -> Style.BOLD_ITALIC
                        }
                        if (out.length > start) spans += Span(style, start, out.length)
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
}
