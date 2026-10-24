package com.caceras.surfacelab

import android.app.RemoteInput
import android.content.Context
import android.content.Intent

/** Stop, Allow, Don't and a reply, from the agent's notification. */
class AgentActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = PhoneAgent.current ?: return
        when (intent.action) {
            AgentNotifications.STOP -> { Hands.stop(); session.stop() }
            AgentNotifications.ALLOW -> intent.getStringExtra(AgentNotifications.CALL)?.let { session.decide(it, Decision(true)) }
            AgentNotifications.DENY -> intent.getStringExtra(AgentNotifications.CALL)?.let { session.decide(it, Decision(false)) }
            AgentNotifications.REPLY -> RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifications.TEXT)?.toString()?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { text -> PhoneAgent.ask(context, session, text) }
        }
    }
}
