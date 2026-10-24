package com.caceras.surfacelab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService

/**
 * What the genie is doing right now, for the doors that only glance at it: the Quick Settings tile
 * and the home-screen widget. Words only ("Working · Reading your text", "Answer ready"); the
 * step's own words show only when widget previews are on (Settings), like the classic widget.
 */
object GenieStatus {
    data class Status(val working: Boolean, val ready: Boolean, val words: String)

    @Volatile private var followed: AgentSession? = null
    private val observer: (AgentSession) -> Unit = { s -> appContext?.let { changed(it) } }
    @Volatile private var appContext: Context? = null

    fun now(context: Context): Status {
        follow(context)
        val details = NativePrivacy.widgetPreview(context)
        val keys = KeyboardLive.state
        val session = PhoneAgent.current
        val view = session?.takeIf { it.running }?.view()
        val step = (view?.steps ?: keys.steps).lastOrNull { it.status == "running" || it.status == "awaiting-approval" }
        return when {
            view?.approval != null || keys.approval != null -> Status(true, false, "Waiting for you")
            view != null || keys.busy -> Status(true, false, "Working" + (step?.label?.takeIf { details }?.let { " · $it" } ?: "…"))
            KeyboardLive.unseen() -> Status(false, true, "Answer ready")
            else -> Status(false, false, "Ask")
        }
    }

    /** Keeps the tile and widget current with the agent's latest session. */
    private fun follow(context: Context) {
        appContext = context.applicationContext
        val session = PhoneAgent.current ?: return
        if (session === followed) return
        followed?.removeObserver(observer)
        session.addObserver(observer)
        followed = session
    }

    private var last: Status? = null

    /** Something changed: refresh the tile (when it is visible) and every genie widget. */
    fun changed(context: Context) {
        val now = now(context)
        if (now == last) return
        last = now
        runCatching { TileService.requestListeningState(context, ComponentName(context, GenieTileService::class.java)) }
        GenieWidgetProvider.refresh(context, now)
    }

    /** Opens the genie from a tile or widget, in a task of its own. */
    fun open(context: Context, origin: Genie.Origin): Intent =
        Intent(context, GenieEntryActivity::class.java).setAction(ACTION).putExtra(Genie.EXTRA_ORIGIN, origin.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    const val ACTION = "com.caceras.surfacelab.action.GENIE"
}
