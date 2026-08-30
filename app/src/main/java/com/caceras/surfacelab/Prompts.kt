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
                "Reply in the same language the user writes in."
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
