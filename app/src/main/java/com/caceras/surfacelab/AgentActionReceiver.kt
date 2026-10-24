package com.caceras.surfacelab

import android.app.RemoteInput
import android.content.Context
import android.content.Intent

/** Stop, Allow, Don't and a reply, from the agent's notifications (the phone agent, and jobs on aegentica.se). */
class AgentActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action?.startsWith("com.caceras.surfacelab.job.") == true) return job(context, intent)
        val session = PhoneAgent.current ?: return
        when (intent.action) {
            AgentNotifications.STOP -> { Hands.stop(); session.stop() }
            AgentNotifications.ALLOW -> intent.getStringExtra(AgentNotifications.CALL)?.let { session.decide(it, Decision(true)) }
            AgentNotifications.DENY -> intent.getStringExtra(AgentNotifications.CALL)?.let { session.decide(it, Decision(false)) }
            AgentNotifications.REPLY -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifications.TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { text -> PhoneAgent.ask(context, session, text) }
        }
    }

    /** A job's buttons: the owner's own choice, sent to aegentica.se while the receiver stays alive. */
    private fun job(context: Context, intent: Intent) {
        val job = intent.getStringExtra(AgentNotifications.JOB)?.let { ServerJobs.jobs[it] } ?: return
        val request = intent.getStringExtra(AgentNotifications.REQUEST)
        // Keeps the receiver alive until aegentica.se answered (null when called directly, as in tests).
        val pending: PendingResult? = goAsync()
        val finish: (String?) -> Unit = { pending?.finish() }
        when (intent.action) {
            AgentNotifications.JOB_STOP -> ServerJobs.cancel(context, job, finish)
            AgentNotifications.JOB_APPROVE, AgentNotifications.JOB_DENY -> {
                val option = intent.getStringExtra(AgentNotifications.OPTION)
                val input = job.inputs.firstOrNull { it.requestId == request }
                // Only a button the job itself offered for a request still open.
                if (input == null || option == null || input.options.none { it.id == option }) finish(null) else ServerJobs.respond(context, job, input.requestId, option, done = finish)
            }
            AgentNotifications.JOB_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifications.TEXT)?.toString()?.trim().orEmpty()
                ServerJobs.tell(context, job, text, job.inputs.firstOrNull { it.requestId == request }) { error ->
                    // Posted again either way, so Android ends its "sending" state; a refusal says why.
                    AgentNotifications.job(context, job, force = true, note = error)
                    finish(error)
                }
            }
            else -> finish(null)
        }
    }
}
