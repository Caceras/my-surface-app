package com.caceras.surfacelab

/** The supported Markdown subset, shared by the native renderer and speech. No HTML. */
internal object MarkdownText {
    enum class Kind { BOLD, ITALIC, BOTH, CODE, BULLET, QUOTE, LINK }
    data class Mark(val start: Int, val end: Int, val kind: Kind, val target: String = "")
    data class Parsed(val text: String, val marks: List<Mark>)
    private data class Inline(val parsed: Parsed, val next: Int, val closed: Boolean)
    private val heading = Regex("^ {0,3}#{1,6}\\s+")
    private val bullet = Regex("^\\s*[*+\\-]\\s+")
    private val ordered = Regex("^\\s*\\d+[.)]\\s+")
    private val quote = Regex("^ {0,3}>\\s?")
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val rule = Regex("^ {0,3}(?:-\\s*){3,}$|^ {0,3}(?:\\*\\s*){3,}$|^ {0,3}(?:_\\s*){3,}$")

    private class Builder {
        val text = StringBuilder()
        val marks = mutableListOf<Mark>()
        fun append(parsed: Parsed) {
            val offset = text.length
            text.append(parsed.text)
            marks += parsed.marks.map { it.copy(start = it.start + offset, end = it.end + offset) }
        }
        fun mark(start: Int, kind: Kind, target: String = "") {
            if (text.length > start) marks += Mark(start, text.length, kind, target)
        }
        fun build() = Parsed(text.toString(), marks.toList())
    }

    fun parse(source: String, streaming: Boolean = false): Parsed {
        val out = Builder()
        var openFence = ""
        var emitted = false
        for (raw in source.split('\n')) {
            val match = fence.matchEntire(raw)
            if (openFence.isNotEmpty()) {
                if (match != null && match.groupValues[1].first() == openFence.first() &&
                    match.groupValues[1].length >= openFence.length && match.groupValues[2].isBlank()) {
                    openFence = ""
                    continue
                }
                if (emitted) out.text.append('\n')
                emitted = true
                val start = out.text.length
                out.text.append(raw)
                out.mark(start, Kind.CODE)
                continue
            }
            if (match != null) { openFence = match.groupValues[1]; continue }
            if (emitted) out.text.append('\n')
            emitted = true
            if (rule.matches(raw)) continue
            var line = raw
            val title = heading.find(line)
            val item = bullet.find(line)
            val number = ordered.find(line)
            val quoted = quote.find(line)
            when {
                title != null -> line = line.substring(title.value.length)
                item != null -> line = "\u2022  " + line.substring(item.value.length)
                quoted != null -> line = line.substring(quoted.value.length)
            }
            val start = out.text.length
            out.append(inline(line, 0, null, streaming, 0).parsed)
            if (title != null) out.mark(start, Kind.BOLD)
            if (item != null || number != null) out.mark(start, Kind.BULLET)
            if (quoted != null) out.mark(start, Kind.QUOTE)
        }
        return out.build()
    }

    private fun inline(s: String, from: Int, closing: String?, streaming: Boolean, depth: Int): Inline {
        val out = Builder()
        var i = from
        while (i < s.length) {
            val char = s[i]
            if (char == '\\' && i + 1 < s.length && s[i + 1] in "\\`*_{}[]()#+-.!>~") {
                out.text.append(s[i + 1]); i += 2; continue
            }
            if (char == '`') {
                var count = 1
                while (i + count < s.length && s[i + count] == '`') count++
                val delimiter = "`".repeat(count)
                val end = s.indexOf(delimiter, i + count)
                if (end >= 0 || streaming) {
                    val start = out.text.length
                    out.text.append(s.substring(i + count, if (end >= 0) end else s.length))
                    out.mark(start, Kind.CODE)
                    i = if (end >= 0) end + count else s.length
                    continue
                }
            }
            if (char == '[' && depth < 8) {
                val labelEnd = s.indexOf("](", i + 1)
                val urlEnd = if (labelEnd >= 0) linkEnd(s, labelEnd + 2) else -1
                if (urlEnd >= 0) {
                    val url = s.substring(labelEnd + 2, urlEnd)
                    if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) {
                        val start = out.text.length
                        out.append(inline(s.substring(i + 1, labelEnd), 0, null, false, depth + 1).parsed)
                        out.mark(start, Kind.LINK, url)
                        i = urlEnd + 1; continue
                    }
                }
            }
            if (char == '*' || char == '_') {
                var run = 1
                while (i + run < s.length && s[i + run] == char) run++
                val before = s.getOrNull(i - 1)
                val after = s.getOrNull(i + run)
                val insideWord = char == '_' && before?.isLetterOrDigit() == true && after?.isLetterOrDigit() == true
                if (closing != null && closing.first() == char && run >= closing.length &&
                    before != null && !before.isWhitespace() && !insideWord) {
                    return Inline(out.build(), i + closing.length, true)
                }
                val mayOpen = !insideWord && (char != '_' || before?.isLetterOrDigit() != true) &&
                    after != null && !after.isWhitespace()
                if (mayOpen && depth < 8) {
                    val count = minOf(run, 3)
                    val delimiter = char.toString().repeat(count)
                    val inner = inline(s, i + count, delimiter, streaming, depth + 1)
                    val start = out.text.length
                    if (!inner.closed && !streaming) out.text.append(delimiter)
                    out.append(inner.parsed)
                    if (inner.closed || streaming) out.mark(start, when (count) {
                        3 -> Kind.BOTH; 2 -> Kind.BOLD; else -> Kind.ITALIC
                    })
                    i = inner.next; continue
                }
                // A token ending in an opening delimiter is incomplete, not visible UI copy.
                if (streaming && i + run == s.length) { i += run; continue }
            }
            out.text.append(char); i++
        }
        return Inline(out.build(), i, false)
    }

    private fun linkEnd(s: String, from: Int): Int {
        var nesting = 0
        for (i in from until s.length) {
            if (s[i].isWhitespace()) return -1
            if (s[i] == '(') nesting++
            if (s[i] == ')') {
                if (nesting == 0) return i
                nesting--
            }
        }
        return -1
    }
}
