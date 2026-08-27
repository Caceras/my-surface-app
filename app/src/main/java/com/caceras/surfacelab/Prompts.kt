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

    /** Pre-filled in the Ask box so the first tap is not a blank page. */
    val SUGGESTIONS = listOf(
        "Explain this simply",
        "What is this actually saying?",
        "Translate this to English",
        "Turn this into a checklist",
        "What is wrong with this argument?",
        "Reply to this politely"
    )
}
