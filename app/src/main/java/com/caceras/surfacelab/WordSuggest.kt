package com.caceras.surfacelab

import android.content.Context
import org.json.JSONObject

/**
 * The keyboard's quiet ideas line: completions for the word being typed and the next word, in the
 * owner's own voice. It starts from a short list of everyday Swedish and English words and learns
 * the words and word pairs the owner types, on this phone only (app-private storage, excluded from
 * backups, at most [MAX_WORDS] words and [MAX_PAIRS] pairs). Locked fields (passwords, cards, codes,
 * numbers, incognito) never reach it; email and web-address fields are not learned from. Settings →
 * Ægentica keys → Forget typed words clears it.
 */
class WordSuggest(private val context: Context) {
    private val base = HashMap<String, Int>()
    private val words = HashMap<String, Int>()
    private val pairs = HashMap<String, Int>()
    private var dirty = false
    private var loadedBase = ""

    private fun prefs() = context.getSharedPreferences(PREFS, 0)

    init { load() }

    private fun load() {
        runCatching { JSONObject(prefs().getString("words", "{}").orEmpty()).let { j -> j.keys().forEach { words[it] = j.getInt(it) } } }
        runCatching { JSONObject(prefs().getString("pairs", "{}").orEmpty()).let { j -> j.keys().forEach { pairs[it] = j.getInt(it) } } }
    }

    /** The everyday list for [language] ("sv" or "en"); earlier lines are commoner. */
    fun language(language: String) {
        if (language == loadedBase) return
        loadedBase = language
        base.clear()
        val id = if (language == "en") R.raw.keys_words_en else R.raw.keys_words_sv
        runCatching {
            context.resources.openRawResource(id).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { i, w -> base.putIfAbsent(w.lowercase(), 1000 - i) }
            }
        }
    }

    /** Up to [limit] words starting with [prefix], the owner's own first, in [prefix]'s case. */
    fun complete(prefix: String, limit: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val scored = HashMap<String, Int>()
        base.forEach { (w, s) -> if (w.length > p.length && w.startsWith(p)) scored[w] = s }
        words.forEach { (w, n) -> if (w.length > p.length && w.startsWith(p)) scored[w] = (scored[w] ?: 0) + 2000 + n * 100 }
        return scored.entries.sortedByDescending { it.value }.take(limit).map { cased(it.key, prefix) }
    }

    /** Words the owner often writes after [previous]. */
    fun next(previous: String, limit: Int = 3): List<String> {
        val key = previous.lowercase() + " "
        return pairs.entries.filter { it.key.startsWith(key) }.sortedByDescending { it.value }.take(limit).map { it.key.removePrefix(key) }
    }

    /** A finished word, after [previous] when there was one. */
    fun learn(word: String, previous: String?) {
        val w = word.lowercase()
        if (w.length !in 2..24 || !w.all { it.isLetter() || it == '\'' || it == '-' }) return
        words[w] = (words[w] ?: 0) + 1
        if (words.size > MAX_WORDS) words.entries.minByOrNull { it.value }?.let { words.remove(it.key) }
        val p = previous?.lowercase()
        if (p != null && p.length in 1..24 && p.all { it.isLetter() }) {
            val key = "$p $w"
            pairs[key] = (pairs[key] ?: 0) + 1
            if (pairs.size > MAX_PAIRS) pairs.entries.minByOrNull { it.value }?.let { pairs.remove(it.key) }
        }
        dirty = true
    }

    /** Written when the keyboard hides, not on every word. */
    fun save() {
        if (!dirty) return
        dirty = false
        prefs().edit().putString("words", JSONObject(words as Map<*, *>).toString()).putString("pairs", JSONObject(pairs as Map<*, *>).toString()).apply()
    }

    fun forget() { words.clear(); pairs.clear(); dirty = false; prefs().edit().clear().apply() }

    private fun cased(word: String, prefix: String) = when {
        prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        prefix.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
        else -> word
    }

    companion object {
        const val PREFS = "keys-words"
        const val MAX_WORDS = 2000
        const val MAX_PAIRS = 3000

        fun forget(context: Context) = context.getSharedPreferences(PREFS, 0).edit().clear().apply()

        /** The word being typed: the letters right before the cursor. */
        fun current(before: CharSequence?): String {
            val text = before ?: return ""
            var i = text.length
            while (i > 0 && (text[i - 1].isLetter() || text[i - 1] == '\'')) i--
            return text.substring(i)
        }

        /** The last whole word before the cursor's word. */
        fun previous(before: CharSequence?): String? {
            val text = before?.toString() ?: return null
            val words = text.trimEnd().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
            return words.lastOrNull()
        }
    }
}
