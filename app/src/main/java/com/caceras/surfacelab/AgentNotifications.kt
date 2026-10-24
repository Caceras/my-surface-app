package com.caceras.surfacelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

/**
 * The agent's notification, a live surface while it works over other apps: what it is doing now
 * (with Android 16's live-update progress where the phone has it), Stop, Allow and Don't when a
 * step waits, and a reply field that steers the run. Allowing and replying need the phone unlocked.
 * When the run ends the notification says so and opens the answer.
 */
object AgentNotifications {
    const val CHANNEL = "agent-work"
    const val ID = 7301
    const val STOP = "com.caceras.surfacelab.agent.STOP"
    const val ALLOW = "com.caceras.surfacelab.agent.ALLOW"
    const val DENY = "com.caceras.surfacelab.agent.DENY"
    const val REPLY = "com.caceras.surfacelab.agent.REPLY"
    const val CALL = "call"
    const val TEXT = "text"

    private var watched: AgentSession? = null
    private var app: Context? = null
    private var used = false
    private val observer: (AgentSession) -> Unit = { session -> app?.let { update(it, session) } }

    /** Follow a conversation: the notification shows while the run uses the hands, and when it ends. */
    fun watch(context: Context, session: AgentSession) {
        app = context.applicationContext
        if (watched === session) return
        watched?.removeObserver(observer)
        watched = session; used = false
        session.addObserver(observer)
    }

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channel(context: Context) {
        manager(context).createNotificationChannel(NotificationChannel(CHANNEL, "Ægentica at work", NotificationManager.IMPORTANCE_LOW).apply {
            description = "What the phone agent is doing while it uses your apps, with Stop and Allow."
        })
    }

    private fun broadcast(context: Context, action: String, code: Int, extras: Intent.() -> Unit = {}, mutable: Boolean = false) =
        PendingIntent.getBroadcast(context, code, Intent(context, AgentActionReceiver::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

    internal fun update(context: Context, session: AgentSession) {
        val view = session.view()
        if (Hands.active) used = true
        if (!used) return
        channel(context)
        val open = PendingIntent.getActivity(context, 0, AssistantActivity.resume(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_surface).setContentIntent(open).setOnlyAlertOnce(true)
        if (!view.busy) {
            used = false
            builder.setContentTitle(if (view.error != null) "Ægentica stopped" else "Ægentica is done")
                .setContentText(Markdown.strip(view.answer.ifBlank { view.error ?: view.question }).take(200))
                .setStyle(Notification.BigTextStyle().bigText(Markdown.strip(view.answer.ifBlank { view.error.orEmpty() }).take(1200)))
                .setAutoCancel(true)
            manager(context).notify(ID, builder.build())
            return
        }
        val approval = view.approval
        val running = view.steps.lastOrNull { it.status == "running" }
        val now = approval?.let { "Waiting for you: ${it.label}" } ?: running?.label ?: "Thinking…"
        builder.setContentTitle(view.question.ifBlank { "Ægentica is working" }.take(80)).setContentText(now)
            .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), "Stop", broadcast(context, STOP, 1)).build())
        if (approval != null) {
            val call: Intent.() -> Unit = { putExtra(CALL, approval.call.id) }
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_arrow_up), if (approval.risk == Risk.ALWAYS) "Allow once" else "Allow", broadcast(context, ALLOW, 2, call))
                .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_close), "Don't", broadcast(context, DENY, 3, call)).build())
            approval.reason?.let { builder.setSubText(it) }
        } else {
            val input = RemoteInput.Builder(TEXT).setLabel("Tell Ægentica").build()
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_compose), "Tell it", broadcast(context, REPLY, 4, mutable = true))
                .addRemoteInput(input).apply { if (android.os.Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
        }
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            builder.setShortCriticalText(if (approval != null) "Waiting" else "Working")
            // Android 16's live updates: shown as a chip in the status bar where the phone allows it.
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        } else builder.setProgress(0, 0, true)
        manager(context).notify(ID, builder.build())
    }
}
