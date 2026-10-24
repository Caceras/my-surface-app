package com.caceras.surfacelab

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * Ægentica's genie sheet (the assistant sheet and its phone agent), summoned with text: a selection
 * on one of Ægentica's own pages, or what the owner is typing (the keyboard's workspace). The
 * selection menu of other apps reaches the same sheet through the `.Ask` alias. Every in-app entry
 * asks for the sheet here, so a new sheet is a change in this one place.
 *
 * The answer comes back only when the owner taps Replace in the sheet: to [handback] when given
 * (held in memory by the sheet), otherwise as the activity result (EXTRA_PROCESS_TEXT), Android's
 * own process-text contract. [EXTRA_ORIGIN] and [EXTRA_REQUEST] are hints the sheet may use: where
 * it was summoned from, and what the owner already asked for, so it can start helping at once.
 */
object Genie {
    const val EXTRA_ORIGIN = "com.caceras.surfacelab.extra.GENIE_ORIGIN"
    const val EXTRA_REQUEST = "com.caceras.surfacelab.extra.GENIE_REQUEST"

    /** Selections and fields longer than this are cut, like the screen text the assistant gets. */
    const val MAX_TEXT = ScreenContexts.MAX_TEXT

    enum class Origin { PAGE, KEYBOARD }

    /** The sheet, opened about [text] from [app]. [editable] lets the sheet offer Replace. Without text it opens on its own. */
    fun intent(
        context: Context,
        text: String,
        editable: Boolean,
        origin: Origin,
        app: String? = null,
        request: String? = null,
        handback: ((String) -> Unit)? = null,
    ): Intent {
        val handed = text.take(MAX_TEXT)
        val intent = if (handed.isBlank()) AssistantActivity.intent(context, direct = true)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            else AssistantActivity.withText(context, handed, app, editable, if (editable) handback else null)
        intent.putExtra(EXTRA_ORIGIN, origin.name)
        request?.trim()?.takeIf { it.isNotEmpty() }?.let { intent.putExtra(EXTRA_REQUEST, it) }
        return intent
    }

    /** What the owner chose to put back (they tapped Replace), or null. */
    fun reply(resultCode: Int, data: Intent?): String? =
        data?.takeIf { resultCode == Activity.RESULT_OK }?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
}
