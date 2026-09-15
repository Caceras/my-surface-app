package com.caceras.surfacelab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.util.SizeF
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

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        push(context, manager, id)
    }

    private fun push(context: Context, manager: AppWidgetManager, id: Int) {
        val options = manager.getAppWidgetOptions(id)
        val layout = if (Build.VERSION.SDK_INT >= 31) RemoteViews(mapOf(
            SizeF(180f, 100f) to views(context, true),
            SizeF(180f, 160f) to views(context, false)
        )) else views(context, options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) < 150)
        manager.updateAppWidget(id, layout)
    }

    internal fun views(context: Context, compact: Boolean): RemoteViews {
        val last = if (NativePrivacy.widgetPreview(context)) ResultStore.lastText(context)?.let { Markdown.strip(it) } else null
        val value = last?.let { if (it.length > 160) it.take(157) + "…" else it } ?: context.getString(R.string.widget_empty)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val talk = PendingIntent.getActivity(context, 1, Intent(context, VoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)
        return RemoteViews(context.packageName, if (compact) R.layout.widget_compact else R.layout.widget).apply {
            setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
            if (!compact) setTextViewText(R.id.widget_value, value)
            setContentDescription(R.id.widget_type, "Open Ægentica AI chat")
            setContentDescription(R.id.widget_talk, "Open Ægentica AI voice")
            setOnClickPendingIntent(R.id.widget_root, pending)
            setOnClickPendingIntent(R.id.widget_type, pending)
            setOnClickPendingIntent(R.id.widget_talk, talk)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.caceras.surfacelab.WIDGET_REFRESH"
        fun refresh(context: Context) {
            context.sendBroadcast(Intent(context, SurfaceWidgetProvider::class.java).setAction(ACTION_REFRESH))
        }
    }
}
