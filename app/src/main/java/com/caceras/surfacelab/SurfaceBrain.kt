package com.caceras.surfacelab

import android.content.Context

/**
 * The one thing every surface in this app actually does: take some text and
 * give back some other text.
 *
 * Kept deliberately free of coroutines, futures and AndroidX so that the
 * "core" flavour can implement it with framework APIs alone. The "nano"
 * flavour implements the same interface on top of Gemini Nano. Neither the
 * activities, the tile, nor the widget know which one they are talking to.
 */
interface SurfaceBrain {

    /** Which tasks this brain can actually perform. */
    val tasks: List<Task>

    /** Cheap, non-blocking. Answers on the main thread. */
    fun status(context: Context, onStatus: (BrainStatus) -> Unit)

    /** Ask the system to make the brain usable (for Nano: download the feature). */
    fun prepare(context: Context, onStatus: (BrainStatus) -> Unit)

    /** Do the work. Answers on the main thread, always exactly once. */
    fun run(context: Context, task: Task, input: String, onResult: (BrainResult) -> Unit)
}

/**
 * One entry in the text-selection popup. The alias name is the manifest
 * <activity-alias> that produced the intent, which is how a single activity
 * serves several menu items.
 */
enum class Task(val alias: String) {
    UPPERCASE("Uppercase"),
    SUMMARIZE("Summarize"),
    PROOFREAD("Proofread"),
    REWRITE("Rewrite");

    companion object {
        fun fromComponent(className: String?): Task {
            val simple = className.orEmpty().substringAfterLast('.')
            return entries.firstOrNull { it.alias == simple } ?: UPPERCASE
        }
    }
}

data class BrainStatus(
    val label: String,
    /** True when run() can be expected to succeed right now. */
    val ready: Boolean,
    /** True when prepare() would do something useful. */
    val preparable: Boolean = false
)

data class BrainResult(
    val text: String,
    val ok: Boolean,
    /** Shown under the result. Explains a caveat or a failure in plain words. */
    val note: String? = null
) {
    companion object {
        fun failure(note: String) = BrainResult("", false, note)
    }
}
