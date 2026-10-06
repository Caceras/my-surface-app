package com.caceras.surfacelab

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan

/** Word-level comparison of the verbatim original with the working text. */
object TextDiff {
    enum class Kind { SAME, REMOVED, ADDED }
    data class Piece(val kind: Kind, val text: String)
    const val MAX_WORDS = 2000

    private fun words(text: String) = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Longest-common-subsequence diff; null when either side is too long to compare on a phone. */
    fun words(before: String, after: String): List<Piece>? {
        val a = words(before); val b = words(after)
        if (a.size > MAX_WORDS || b.size > MAX_WORDS) return null
        val n = a.size; val m = b.size
        val table = IntArray((n + 1) * (m + 1))
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            table[i * (m + 1) + j] = if (a[i] == b[j]) table[(i + 1) * (m + 1) + j + 1] + 1 else maxOf(table[(i + 1) * (m + 1) + j], table[i * (m + 1) + j + 1])
        val out = mutableListOf<Piece>()
        fun add(kind: Kind, word: String) {
            val last = out.lastOrNull()
            if (last != null && last.kind == kind) out[out.lastIndex] = Piece(kind, last.text + " " + word) else out += Piece(kind, word)
        }
        var i = 0; var j = 0
        while (i < n && j < m) when {
            a[i] == b[j] -> { add(Kind.SAME, a[i]); i++; j++ }
            table[(i + 1) * (m + 1) + j] >= table[i * (m + 1) + j + 1] -> { add(Kind.REMOVED, a[i]); i++ }
            else -> { add(Kind.ADDED, b[j]); j++ }
        }
        while (i < n) add(Kind.REMOVED, a[i++])
        while (j < m) add(Kind.ADDED, b[j++])
        return out
    }

    /** Removed words are struck through and dimmed; added words are highlighted. */
    fun render(context: Context, pieces: List<Piece>): CharSequence {
        val out = SpannableStringBuilder()
        pieces.forEach { piece ->
            if (out.isNotEmpty()) out.append(' ')
            val start = out.length; out.append(piece.text)
            when (piece.kind) {
                Kind.REMOVED -> { out.setSpan(StrikethroughSpan(), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); out.setSpan(ForegroundColorSpan(context.ink(R.color.text_dim)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                Kind.ADDED -> out.setSpan(BackgroundColorSpan(context.ink(R.color.presence_bg)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                Kind.SAME -> {}
            }
        }
        return out
    }
}
