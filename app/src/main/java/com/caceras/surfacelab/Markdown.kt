package com.caceras.surfacelab

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.text.style.QuoteSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan

/** Native formatting; speech uses the same parser without allocating Android spans. */
object Markdown {
    fun render(source: String, bulletIndent: Int = 0, streaming: Boolean = false): CharSequence {
        val parsed = MarkdownText.parse(source, streaming)
        val out = SpannableStringBuilder(parsed.text)
        for (mark in parsed.marks) {
            val span: Any = when (mark.kind) {
                MarkdownText.Kind.BOLD -> StyleSpan(Typeface.BOLD)
                MarkdownText.Kind.ITALIC -> StyleSpan(Typeface.ITALIC)
                MarkdownText.Kind.BOTH -> StyleSpan(Typeface.BOLD_ITALIC)
                MarkdownText.Kind.CODE -> TypefaceSpan("monospace")
                MarkdownText.Kind.BULLET -> {
                    if (bulletIndent <= 0) continue
                    LeadingMarginSpan.Standard(0, bulletIndent)
                }
                MarkdownText.Kind.QUOTE -> QuoteSpan()
                MarkdownText.Kind.LINK -> URLSpan(mark.target)
            }
            out.setSpan(span, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    fun strip(source: String, streaming: Boolean = false): String = MarkdownText.parse(source, streaming).text
}
