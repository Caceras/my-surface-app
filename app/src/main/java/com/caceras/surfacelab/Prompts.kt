package com.caceras.surfacelab

/**
 * The presets are just prompts. Nothing in the app treats them as special,
 * which is the point: "Summarise" is not a feature, it is a system
 * instruction someone wrote once.
 *
 * Add a preset by adding a Task, an entry here, and an <activity-alias>.
 */
object Prompts {

    /** Sent as a system instruction where the device supports one. */
    fun system(task: Task): String = when (task) {
        Task.SUMMARIZE ->
            "You summarise text. Reply with at most three short bullet points. " +
                "No preamble, no closing remark. Answer in the same language as the input."
        Task.PROOFREAD ->
            "You correct spelling, grammar and punctuation. Reply with the corrected " +
                "text only -- no commentary, no explanation of what you changed. " +
                "Keep the original language and meaning."
        Task.REWRITE ->
            "You rewrite text in a professional register. Reply with the rewritten " +
                "text only. Keep the original language, meaning and approximate length."
        Task.ASK ->
            "You are a concise assistant running on the user's phone. Answer directly. " +
                "Reply in the same language the user writes in. " +
                "Earlier turns of the conversation may appear above the latest one. " +
                "Use them to work out what words like \"more\", \"it\" and \"again\" " +
                "refer to. Reply with your next answer only: do not repeat the " +
                "conversation back, and do not prefix your reply with a name."
        Task.UPPERCASE -> ""
    }

    /** What actually gets sent, given the selection and the user's own words. */
    fun user(task: Task, selection: String, instruction: String): String = when (task) {
        Task.ASK ->
            if (selection.isBlank()) instruction
            else instruction.trim().ifEmpty { "What is this?" } + "\n\n" + selection
        else -> selection
    }

    /**
     * The conversation, as one prompt.
     *
     * SurfaceBrain.run takes a single string and no history, which is the
     * right shape for the presets -- "summarise this" means this, not this
     * and the four things before it. A chat is the other case: "list 10 more"
     * is meaningless without the turn it follows, and the model answered it
     * with fruit because that is genuinely all it was given.
     *
     * Older turns are dropped whole rather than truncated, newest kept, so
     * what the model sees is always a real exchange rather than half of one.
     */
    fun conversation(history: List<Turn>, question: String): String {
        val kept = fit(history, BUDGET - question.length)
        if (kept.isEmpty()) return question
        val out = StringBuilder()
        kept.forEach { out.append(YOU).append(it.you).append("\n")
                          .append(THEM).append(it.reply).append("\n\n") }
        return out.append(YOU).append(question).toString()
    }

    /**
     * How much of the history fits, oldest dropped first.
     *
     * Gemini Nano's context is small and shared with the answer it is about
     * to write, so this is deliberately mean. A single turn longer than the
     * whole budget is kept with its reply cut short: some idea of what was
     * just discussed beats none, and the question it belongs to is the one
     * being answered.
     */
    private fun fit(history: List<Turn>, budget: Int): List<Turn> {
        if (history.isEmpty() || budget <= 0) return emptyList()
        val kept = ArrayDeque<Turn>()
        var left = budget
        for (turn in history.asReversed()) {
            val cost = turn.you.length + turn.reply.length + OVERHEAD
            if (cost <= left) {
                kept.addFirst(turn)
                left -= cost
            } else if (kept.isEmpty()) {
                val room = left - turn.you.length - OVERHEAD
                if (room > MIN_REPLY) {
                    kept.addFirst(turn.copy(
                        reply = turn.reply.take(room).trimEnd() + ELLIPSIS
                    ))
                }
                break
            } else {
                break
            }
        }
        return kept.toList()
    }

    /**
     * A model told to continue a transcript will sometimes write the next
     * speaker label too. Cheap to strip, and it is the whole first line of
     * the answer when it happens.
     */
    fun reply(raw: String): String {
        val text = raw.trimStart()
        LABELS.forEach { label ->
            if (text.startsWith(label, ignoreCase = true)) {
                return text.substring(label.length).trimStart()
            }
        }
        return raw
    }

    private const val YOU = "You: "
    private const val THEM = "Assistant: "
    private const val ELLIPSIS = "\u2026"

    /** Characters of history, at most. Nano has to fit the answer in too. */
    private const val BUDGET = 1500

    /**
     * The speaker labels and blank lines a turn costs on top of its text.
     * Not a const: Kotlin will not fold a String.length into one.
     */
    private val OVERHEAD = YOU.length + THEM.length + 3

    /** Below this a truncated reply says nothing, so the turn is dropped. */
    private const val MIN_REPLY = 40

    private val LABELS = listOf(THEM, "AI: ", "Bot: ")

    /**
     * Two sets, because the two places that offer them are not the same
     * place. "What is this actually saying?" is a fine thing to tap when you
     * have just selected a paragraph, and nonsense on an empty chat screen
     * where there is no "this" -- the model can only answer by asking what
     * you meant, which is exactly what it did.
     */

    /** Offered next to a selection. There is always material to refer to. */
    val ABOUT_SELECTION = listOf(
        "Explain this simply",
        "What is this actually saying?",
        "Translate this to English",
        "Turn this into a checklist",
        "What is wrong with this argument?",
        "Reply to this politely"
    )

    /** Offered on the empty chat screen. Each one stands on its own. */
    val OPENERS = listOf(
        "Explain a hard idea simply",
        "Help me word a message",
        "Give me three ideas for",
        "What should I ask about",
        "Translate to English",
        "Write a short summary of"
    )
}
