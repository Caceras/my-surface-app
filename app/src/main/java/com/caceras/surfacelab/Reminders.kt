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

/** Inexact, durable reminders. A notification invites foreground AI; it never starts inference. */
object Reminders {
    const val CHANNEL = "workspace-reminders"
    fun allowed(context: Context) = (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    fun pending(context: Context, id: String, action: String = "due", due: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(action).setData(Uri.Builder().scheme("aegentica").authority("reminder").appendPath(id).appendPath(action).build()).putExtra("id", id).putExtra("due",due),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java).cancel(pending(context,id))
        context.getSystemService(NotificationManager::class.java).cancel(id,1)
    }
    fun schedule(context: Context, record: Record) {
        cancel(context, record.id)
        if(record.deleted || record.done || !record.enabled || record.due<=0 || record.kind !in listOf("task","routine")) return
        WorkspaceStore(context).use { store ->
            if(ConnectedAI.routineApproval(store,context,record)!=null) { RoutineJobService.schedule(context); return }
            val notified = store.readableDatabase.rawQuery("SELECT 1 FROM executions WHERE id=?",arrayOf("reminder:${record.id}:${record.due}")).use { it.moveToFirst() }
            if(notified) return
        }
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            record.due.coerceAtLeast(System.currentTimeMillis()+1000),pending(context,record.id,due=record.due))
    }
    fun reschedule(context: Context) { WorkspaceStore(context).use { it.scheduled() }.forEach { schedule(context,it) }; RoutineJobService.schedule(context) }
    fun nextDue(record: Record, now: Long): Long {
        if(record.cadence=="once") return 0
        var next = Instant.ofEpochMilli(record.due).atZone(ZoneId.of(record.zone))
        do { next = if(record.cadence=="weekly") next.plusWeeks(1) else next.plusDays(1) } while(next.toInstant().toEpochMilli()<=now)
        return next.toInstant().toEpochMilli()
    }
}
