package com.caceras.surfacelab

import java.util.Locale

/**
 * The GenAI models are trained on a fixed language list, and Swedish is not
 * on it -- for any of the three tasks. Silently returning nonsense would be
 * the worst outcome, so the app says so up front.
 *
 * This is a heuristic, not language detection. It only has to be right often
 * enough to warn, and it never blocks the call.
 */
object Lang {

    // Straight from the SDK: SummarizerOptions.Language has exactly three
    // constants; Rewriter and Proofreader have seven.
    val SUMMARY_LANGUAGES = listOf("English", "Japanese", "Korean")
    val TEXT_LANGUAGES =
        listOf("English", "Japanese", "Korean", "German", "French", "Italian", "Spanish")

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

    /** A short warning to show with the result, or null when all is well. */
    fun caveat(task: Task, text: String): String? {
        if (task == Task.UPPERCASE) return null
        if (!looksNordic(text)) return null
        val supported =
            if (task == Task.SUMMARIZE) SUMMARY_LANGUAGES else TEXT_LANGUAGES
        return "This looks like Swedish. On-device " +
            task.name.lowercase(Locale.ROOT) +
            " only supports " + supported.joinToString(", ") +
            ", so treat the result with suspicion."
    }
}
