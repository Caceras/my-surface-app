package com.caceras.surfacelab

import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.widget.TextView

/** Keep the same editable buffer during streaming; replace only changed paragraphs. */
object ReplyRenderer {
    fun paint(view: TextView, source: String, indent: Int) {
        val rendered = Markdown.render(source, indent, streaming = true) as Spanned
        val existing = view.editableText
        if (existing == null) {
            view.setText(rendered, TextView.BufferType.EDITABLE)
            return
        }
        var common = 0
        while (common < minOf(existing.length, rendered.length) && existing[common] == rendered[common]) common++
        // Formatting can change before the text does (for example a closing emphasis marker).
        val oldMarks = signatures(existing)
        val newMarks = signatures(rendered)
        val changed = (oldMarks - newMarks.toSet()) + (newMarks - oldMarks.toSet())
        changed.minOfOrNull { it.start }?.let { common = minOf(common, it) }
        val start = existing.toString().lastIndexOf('\n', common - 1) + 1
        view.beginBatchEdit()
        try {
            existing.getSpans(start, existing.length, Any::class.java).filter { formatting(it) && existing.getSpanEnd(it) > start }
                .forEach { existing.removeSpan(it) }
            existing.replace(start, existing.length, rendered, start, rendered.length)
        } finally {
            view.endBatchEdit()
        }
    }

    private data class Signature(val start: Int, val end: Int, val kind: String)
    private fun signatures(text: Spanned): List<Signature> = text.getSpans(0, text.length, Any::class.java)
        .filter(::formatting).map { span ->
            val kind = when (span) {
                is StyleSpan -> "style:${span.style}"
                is TypefaceSpan -> "font:${span.family}"
                is URLSpan -> "link:${span.url}"
                is LeadingMarginSpan -> "margin:${span.getLeadingMargin(false)}"
                else -> span.javaClass.name
            }
            Signature(text.getSpanStart(span), text.getSpanEnd(span), kind)
        }
    private fun formatting(span: Any) = span is StyleSpan || span is TypefaceSpan ||
        span is URLSpan || span is LeadingMarginSpan || span is QuoteSpan
}
