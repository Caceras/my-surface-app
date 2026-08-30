package com.caceras.surfacelab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home screen widget showing the most recent result. A widget has no process
 * of its own, so everything it displays has to come from storage -- see
 * ResultStore.
 *
 * Tapping it opens the hands-free screen, which is the third entry point to
 * voice alongside the tile and the app shortcut: ask from the home screen and
 * the answer is written back here, because every surface saves through
 * ResultStore. On a phone with no on-device recogniser the tap falls back to
 * the refresh broadcast this provider has always handled.
 */
class SurfaceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id -> push(context, appWidgetManager, id) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SurfaceWidgetProvider::class.java)
            )
            onUpdate(context, manager, ids)
        }
    }

    private fun push(context: Context, manager: AppWidgetManager, id: Int) {
        val last = ResultStore.lastText(context)
        val title = ResultStore.lastTask(context) ?: context.getString(R.string.app_name)
        val value = last?.let { if (it.length > 160) it.take(157) + "..." else it }
            ?: SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

        // A mutability flag is mandatory on API 31+; omitting both
        // FLAG_IMMUTABLE and FLAG_MUTABLE throws here.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pending = if (Ears(context).available()) {
            // A widget is not an activity either, so the same NEW_TASK rule
            // as the tile applies.
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, VoiceActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                flags
            )
        } else {
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, SurfaceWidgetProvider::class.java)
                    .setAction(ACTION_REFRESH),
                flags
            )
        }

        val views = RemoteViews(context.packageName, R.layout.widget).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_value, value)
            setOnClickPendingIntent(R.id.widget_root, pending)
        }

        manager.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_REFRESH = "com.caceras.surfacelab.WIDGET_REFRESH"
    }
}
