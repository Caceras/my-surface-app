package com.caceras.surfacelab

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews

/** A small home-screen widget: the rabbit, "Ægentica" and what it is doing; a tap opens the genie. */
class GenieWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = draw(context, manager, ids, GenieStatus.now(context))

    companion object {
        fun refresh(context: Context, status: GenieStatus.Status) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, GenieWidgetProvider::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) draw(context, manager, ids, status)
        }

        fun views(context: Context, status: GenieStatus.Status) = RemoteViews(context.packageName, R.layout.genie_widget).apply {
            setTextViewText(R.id.genie_widget_status, status.words)
            setImageViewResource(R.id.genie_widget_rabbit, when {
                status.working -> R.drawable.mascot_running
                status.ready -> R.drawable.mascot_waving
                else -> R.drawable.mascot_sitting
            })
            setContentDescription(R.id.genie_widget_root, "Ægentica: ${status.words}. Tap to open the genie.")
            setOnClickPendingIntent(R.id.genie_widget_root, PendingIntent.getActivity(context, 7, GenieStatus.open(context, Genie.Origin.WIDGET),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }

        private fun draw(context: Context, manager: AppWidgetManager, ids: IntArray, status: GenieStatus.Status) =
            ids.forEach { manager.updateAppWidget(it, views(context, status)) }
    }
}
