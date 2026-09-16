package com.caceras.surfacelab

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.time.Instant
import java.time.ZoneId

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        Thread {
            try {
                if(intent.action in listOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
                    Reminders.reschedule(context)
                } else WorkspaceStore(context).use { store ->
                    val id = intent.getStringExtra("id") ?: return@use
                    val record = store.get(id) ?: return@use
                    if(record.deleted || record.done || !record.enabled || ConnectedAI.routineApproval(store,context,record)!=null) return@use
                    when(intent.action) {
                        "done" -> { Reminders.cancel(context,id); store.save(record.copy(done=true)) }
                        "snooze" -> { val next = store.save(record.copy(due=System.currentTimeMillis()+10*60*1000)); Reminders.schedule(context,next) }
                        "due" -> {
                            if(record.due != intent.getLongExtra("due",0) || record.due>System.currentTimeMillis()+1000 || !Reminders.allowed(context)) return@use
                            val key = "reminder:$id:${record.due}"
                            if(!store.execution(key,id,"notified","Open the app to review or run.")) return@use
                            val manager = context.getSystemService(NotificationManager::class.java)
                            manager.createNotificationChannel(NotificationChannel(Reminders.CHANNEL,"Reminders",NotificationManager.IMPORTANCE_DEFAULT))
                            val open = PendingIntent.getActivity(context,0,WorkspaceActivity.intent(context,"today",id).setData(Uri.Builder().scheme("aegentica").authority("item").appendPath(id).build()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                            val notification = Notification.Builder(context,Reminders.CHANNEL).setSmallIcon(R.drawable.ic_surface)
                                .setContentTitle("Ægentica AI").setContentText(if(record.kind=="routine") "Your AI routine is ready to open." else "You have a reminder to review.")
                                .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(open).setAutoCancel(true)
                                .addAction(Notification.Action.Builder(null,"In 10 minutes",Reminders.pending(context,id,"snooze")).build())
                            if(record.kind=="task") notification.addAction(Notification.Action.Builder(null,"Done",Reminders.pending(context,id,"done")).build())
                            manager.notify(id,1,notification.build())
                            val next=Reminders.nextDue(record,System.currentTimeMillis())
                            if(next>0) { val saved=store.save(record.copy(due=next)); Reminders.schedule(context,saved) }
                        }
                    }
                }
            } finally { result.finish() }
        }.start()
    }
}
