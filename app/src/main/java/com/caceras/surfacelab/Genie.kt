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

    enum class Origin { PAGE, KEYBOARD, SHARE, SHORTCUT, TILE, WIDGET, TAG }

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

    /**
     * The genie the way the assistant gesture opens it, from a door outside any app's text: the
     * floating genie when Ægentica hands is on and the agent is ready, otherwise the sheet. What came
     * through the door ([text], [picture], from [app]) becomes the genie's screen context for the
     * first question, held in memory only.
     */
    fun summon(context: Context, origin: Origin, text: String = "", picture: android.graphics.Bitmap? = null, app: String = "") {
        val shared = text.isNotBlank() || picture != null
        if (shared) {
            ScreenContexts.begin()
            ScreenContexts.text(app, text.take(MAX_TEXT))
            picture?.let { ScreenContexts.screenshot(it) }
        }
        val genie = Hands.overlay
        if (genie != null && HandsTools.floats(context) && PhoneAgent.ready(context) && PhoneAgent.online(context)) { genie.summon(); return }
        val intent = AssistantActivity.intent(context, direct = !shared).putExtra(EXTRA_ORIGIN, origin.name)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * The genie starts on [request] the owner already made (an NFC tag): the phone agent's
     * conversation begins at once and the sheet opens on it, with its steps, Allow prompts and Stop.
     * Without an OpenRouter sign-in the sheet opens on its own and says how to sign in.
     */
    fun ask(context: Context, request: String, origin: Origin) = asker(context, request, origin)

    /** Test seam for [ask]. */
    @Volatile internal var asker: (Context, String, Origin) -> Unit = { context, request, origin ->
        if (!PhoneAgent.ready(context)) {
            android.widget.Toast.makeText(context, PhoneAgent.NO_KEY, android.widget.Toast.LENGTH_LONG).show()
            summon(context, origin)
        } else {
            val session = PhoneAgent.session(context, fresh = true)
            session.tools = PhoneTools.forAgent(context)
            session.send(AgentTurn.User(text = request, notes = listOf("The person started this by tapping an NFC tag they wrote on this phone.")))
            context.startActivity(sameTask(context, AssistantActivity.resume(context)))
        }
    }

    /** From an activity, the sheet stays in that activity's task (so Ægentica's own screens never come forward). */
    internal fun sameTask(context: Context, intent: Intent): Intent =
        if (context is Activity) intent.apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() } else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** What the owner chose to put back (they tapped Replace), or null. */
    fun reply(resultCode: Int, data: Intent?): String? =
        data?.takeIf { resultCode == Activity.RESULT_OK }?.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
}
