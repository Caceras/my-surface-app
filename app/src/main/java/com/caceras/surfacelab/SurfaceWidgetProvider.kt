package com.caceras.surfacelab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Last answer with explicit Type and Talk entry points into the shared conversation. */
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
        val last = ResultStore.lastText(context)?.let { Markdown.strip(it) }
        val title = ResultStore.lastTask(context) ?: context.getString(R.string.app_name)
        val value = last?.let { if (it.length > 160) it.take(157) + "..." else it }
            ?: context.getString(R.string.widget_empty)

        // A mutability flag is mandatory on API 31+; omitting both
        // FLAG_IMMUTABLE and FLAG_MUTABLE throws here.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val pending = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val talk = PendingIntent.getActivity(context, 1,
            Intent(context, if (Ears(context).available()) VoiceActivity::class.java else MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)

        val views = RemoteViews(context.packageName, R.layout.widget).apply {
            setTextViewText(R.id.widget_title, title)
            setTextViewText(R.id.widget_value, value)
            setOnClickPendingIntent(R.id.widget_root, pending)
            setOnClickPendingIntent(R.id.widget_type, pending)
            setOnClickPendingIntent(R.id.widget_talk, talk)
        }

        manager.updateAppWidget(id, views)
    }

    companion object {
        const val ACTION_REFRESH = "com.caceras.surfacelab.WIDGET_REFRESH"
    }
}
