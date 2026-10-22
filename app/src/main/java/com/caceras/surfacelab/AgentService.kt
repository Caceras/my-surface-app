package com.caceras.surfacelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo

/**
 * Keeps one conversation with the owner's LiveKit agent alive while the owner
 * uses other apps, so the agent can see a screen they chose to share. It is
 * started only from the visible Agent voice screen, shows a notification with
 * Stop the whole time, and is never restarted by the system.
 */
class AgentService : Service(), AgentConversation.Listener {
    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { conversation?.end("Ended from the notification."); stopSelf(); return START_NOT_STICKY }
        val current = conversation
        if (current != null && !current.ended) { notifyChanged(); return START_NOT_STICKY }
        if (!foreground()) { stopSelf(); return START_NOT_STICKY }
        ReadingService.pauseForCapture()
        TranscriptionService.yieldMicrophone()
        conversation = AgentConversation(this, this).also { it.start() }
        return START_NOT_STICKY
    }

    private fun foreground(): Boolean = try {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Agent voice", NotificationManager.IMPORTANCE_LOW))
        if (android.os.Build.VERSION.SDK_INT >= 30) startForeground(ID, notification(this, conversation), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(ID, notification(this, conversation))
        true
    } catch (_: Exception) { false }

    override fun changed(conversation: AgentConversation) {
        if (conversation.ended) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification(this, conversation)) }
        notifyChanged()
    }

    override fun onDestroy() {
        conversation?.takeIf { !it.ended }?.end("Agent voice stopped.")
        super.onDestroy()
    }

    companion object {
        const val STOP = "com.caceras.surfacelab.AGENT_STOP"
        private const val CHANNEL = "agent-voice"
        private const val ID = 48
        @Volatile var conversation: AgentConversation? = null; private set
        private val watchers = mutableListOf<() -> Unit>()

        /** Call only from a visible screen after microphone permission is granted. */
        fun start(context: Context) { context.startForegroundService(Intent(context, AgentService::class.java)) }
        fun stop(context: Context) { if (conversation?.ended == false) context.startService(Intent(context, AgentService::class.java).setAction(STOP)) }
        fun watch(watcher: () -> Unit): () -> Unit { synchronized(watchers) { watchers += watcher }; return { synchronized(watchers) { watchers -= watcher } } }
        /** Forget a finished conversation before starting another. */
        fun clear() { if (conversation?.ended != false) conversation = null }
        private fun notifyChanged() = synchronized(watchers) { watchers.toList() }.forEach { it() }

        /** Generic text only: the transcript never appears on the lock screen. */
        fun notification(context: Context, conversation: AgentConversation?, sharing: Boolean = conversation?.sharing == true): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, AgentActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(context, 1, Intent(context, AgentService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_surface)
                .setContentTitle(if (sharing) "Your agent hears you and sees your shared screen" else "Talking with your agent")
                .setContentText("Tap Stop & save to end. The conversation is saved to Notes.")
                .setContentIntent(open).setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(null, "Stop & save", stop).build())
                .build()
        }
    }
}
