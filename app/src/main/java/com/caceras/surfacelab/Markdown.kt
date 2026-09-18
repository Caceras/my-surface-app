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

/** Android presentation of the shared, framework-independent Markdown parser. */
object Markdown {
    private const val FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    // UI-thread only; weak keys do not retain screens after navigation.
    private val streamSources = WeakHashMap<TextView, String>()

    fun render(source: String, bulletIndent: Int = 0): CharSequence {
        val document = MarkdownParser.parse(source, bulletIndent)
        return SpannableStringBuilder(document.text).apply {
            for (span in document.spans) {
                val style = when (span.style) {
                    MarkdownParser.Style.BOLD -> StyleSpan(Typeface.BOLD)
                    MarkdownParser.Style.ITALIC -> StyleSpan(Typeface.ITALIC)
                    MarkdownParser.Style.BOLD_ITALIC -> StyleSpan(Typeface.BOLD_ITALIC)
                    MarkdownParser.Style.CODE -> TypefaceSpan("monospace")
                    MarkdownParser.Style.INDENT -> LeadingMarginSpan.Standard(0, span.indent)
                }
                setSpan(style, span.start, span.end, FLAGS)
            }
        }
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

    /** No Android calls: speech and pure JVM callers share the exact parser used by render. */
    fun strip(source: String): String = MarkdownParser.parse(source).text
}
