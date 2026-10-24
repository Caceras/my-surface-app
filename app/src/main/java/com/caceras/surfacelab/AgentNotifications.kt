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
 * The agent's notifications, a live surface while it works: what it is doing now (with Android
 * 16's live-update progress where the phone has it), Stop, Allow and Don't when a step waits, and
 * a reply field that steers it. One follows the phone agent while it uses other apps; one follows
 * each job handed to the server agent on aegentica.se, with its price when a step costs money.
 * Allowing and replying need the phone unlocked. When the work ends the notification says so and
 * opens the answer.
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
    const val JOB_STOP = "com.caceras.surfacelab.job.STOP"
    const val JOB_APPROVE = "com.caceras.surfacelab.job.APPROVE"
    const val JOB_DENY = "com.caceras.surfacelab.job.DENY"
    const val JOB_REPLY = "com.caceras.surfacelab.job.REPLY"
    const val JOB = "job"
    const val REQUEST = "request"
    const val OPTION = "option"
    private const val JOB_IDS = 7400

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

    /** One notification per server job, so two jobs never hide each other. */
    fun jobId(job: ServerJobs.Job) = JOB_IDS + Math.floorMod(job.id.hashCode(), 1000)
    private val shown = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun jobExtras(job: ServerJobs.Job, input: ServerJobs.Input? = null, option: String? = null): Intent.() -> Unit = {
        putExtra(JOB, job.id); input?.let { putExtra(REQUEST, it.requestId) }; option?.let { putExtra(OPTION, it) }
    }

    /** Distinct request codes per job and action, so one job's buttons never answer another's. */
    private fun code(job: ServerJobs.Job, slot: Int) = jobId(job) * 8 + slot

    /** A job on aegentica.se while it works: its newest step, Stop, and Approve or a reply when it waits. */
    /** [force] posts again even when nothing changed (after a reply, so Android drops its "sending" state); [note] says what went wrong. */
    fun job(context: Context, job: ServerJobs.Job, force: Boolean = false, note: String? = null) = synchronized(this) {
        if (force) shown.remove(job.id)
        postJob(context, job, note)
    }

    // One at a time: a notification built from an older state must never replace a newer one.
    private fun postJob(context: Context, job: ServerJobs.Job, note: String? = null) {
        if (job.finished) return
        val input = job.inputs.firstOrNull()
        val running = job.steps.lastOrNull { it.status == "running" } ?: job.steps.lastOrNull()
        val now = when {
            input != null -> listOfNotNull(input.prompt.oneLine().take(120), ServerJobs.price(input)).joinToString(" · ")
            running != null -> running.label
            else -> "Working on aegentica.se"
        }
        val signature = listOf(job.state, now, input?.requestId, job.cost?.let { String.format(java.util.Locale.ROOT, "%.2f", it) }).joinToString("|")
        if (shown.put(job.id, signature) == signature) return
        channel(context)
        val open = PendingIntent.getActivity(context, code(job, 0), AssistantActivity.showJob(context, job.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_surface).setContentIntent(open).setOnlyAlertOnce(input == null)
            .setContentTitle(job.title.ifBlank { "Ægentica is working" }.take(80)).setContentText(now)
            .setOngoing(true).setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), "Stop", broadcast(context, JOB_STOP, code(job, 1), jobExtras(job))).build())
        job.cost?.let { builder.setSubText(String.format(java.util.Locale.ROOT, "$%.2f so far", it)) }
        note?.let { builder.setSubText(it) }
        val approve = input?.let { ServerJobs.approveOption(it) }
        if (input != null && input.kind == "tool-approval" && approve != null) {
            val title = ServerJobs.price(input)?.let { "Approve · ${it.removePrefix("About ")}" } ?: approve.label.ifBlank { "Approve" }
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_arrow_up), title, broadcast(context, JOB_APPROVE, code(job, 2), jobExtras(job, input, approve.id)))
                .apply { if (android.os.Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
            ServerJobs.declineOption(input)?.let { no ->
                builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_close), "Don't", broadcast(context, JOB_DENY, code(job, 3), jobExtras(job, input, no.id))).build())
            }
        } else {
            val label = if (input != null) "Answer" else "Tell it"
            val reply = RemoteInput.Builder(TEXT).setLabel(if (input != null) input.prompt.oneLine().take(60) else "Tell Ægentica").apply {
                if (input != null && input.options.isNotEmpty()) setChoices(input.options.take(3).map { it.label }.toTypedArray())
            }.build()
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_compose), label, broadcast(context, JOB_REPLY, code(job, 4), jobExtras(job, input), mutable = true))
                .addRemoteInput(reply).apply { if (android.os.Build.VERSION.SDK_INT >= 31) setAuthenticationRequired(true) }.build())
        }
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setProgressIndeterminate(true))
            builder.setShortCriticalText(if (input != null) "Waiting" else "Working")
            builder.extras.putBoolean("android.requestPromotedOngoing", true)
        } else builder.setProgress(0, 0, true)
        manager(context).notify(jobId(job), builder.build())
    }

    /** A finished job: its answer, the chat on aegentica.se and its first file. */
    fun jobDone(context: Context, job: ServerJobs.Job) = synchronized(this) { postDone(context, job) }

    private fun postDone(context: Context, job: ServerJobs.Job) {
        if (shown.put(job.id, "done:${job.state}") == "done:${job.state}") return
        channel(context)
        val open = PendingIntent.getActivity(context, code(job, 0), AssistantActivity.showJob(context, job.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (job.state) { "failed" -> "Ægentica could not finish"; "cancelled" -> "Ægentica stopped the job"; else -> "Ægentica is done" }
        val body = Markdown.strip(job.result ?: job.error ?: job.text.ifBlank { job.title }).trim()
        val builder = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_surface).setContentIntent(open).setAutoCancel(true)
            .setContentTitle(title).setContentText(job.title.ifBlank { body }.take(200))
            .setStyle(Notification.BigTextStyle().bigText(body.take(1200)))
        job.cost?.let { builder.setSubText(String.format(java.util.Locale.ROOT, "$%.2f", it)) }
        job.links.firstOrNull()?.let { (_, url) ->
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_arrow_up_right), "Open chat",
                PendingIntent.getActivity(context, code(job, 5), AegenticaActivity.open(context, url), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        job.links.drop(1).firstOrNull()?.let { (name, url) ->
            builder.addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_arrow_up_right), name.take(30),
                PendingIntent.getActivity(context, code(job, 6), AegenticaActivity.open(context, url), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        manager(context).notify(jobId(job), builder.build())
    }
}
