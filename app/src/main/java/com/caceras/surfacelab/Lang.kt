package com.caceras.surfacelab

import java.util.Locale

/**
 * The Prompt API has no declared language list -- unlike the task-shaped
 * GenAI APIs, which are capped at English, Japanese and Korean and would
 * simply refuse anything else.
 *
 * That is not the same as being good in every language. Nano is a small
 * model, and quality outside English degrades in ways that are easy to miss
 * when the output is fluent. So: no blocking, no refusing, one honest note
 * the first time it sees Nordic text.
 */
object Lang {

    private val NORDIC_CHARS = setOf('å', 'ä', 'ö', 'Å', 'Ä', 'Ö', 'ø', 'æ')

    private val NORDIC_WORDS = setOf(
        "och", "att", "det", "som", "inte", "har", "med", "för", "den",
        "till", "jag", "kan", "vi", "är", "på", "men", "detta", "eller"
    )

    fun looksNordic(text: String): Boolean {
        if (text.any { it in NORDIC_CHARS }) return true
        val words = text.lowercase(Locale.ROOT).split(Regex("[^\\p{L}]+"))
        return words.count { it in NORDIC_WORDS } >= 2
    }

    /** A short note to show with the result, or null when there is nothing to say. */
    fun caveat(task: Task, text: String): String? {
        if (task == Task.UPPERCASE) return null
        if (!looksNordic(text)) return null
        return "Note: this looks like Swedish. Nano will answer, but it is a " +
            "small model trained mostly on English -- check anything that matters."
    }
}
