package com.caceras.surfacelab

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * The last result, shared between surfaces. The widget has no process of its
 * own to remember anything, so this is where it reads from.
 */
object ResultStore {

    private const val PREFS = "surfacelab"
    private const val KEY_TEXT = "last_text"
    private const val KEY_TASK = "last_task"

    fun save(context: Context, task: Task, text: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TEXT, text)
            .putString(KEY_TASK, task.alias)
            .apply()
        poke(context)
    }

    fun lastText(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEXT, null)
            ?.takeIf { it.isNotBlank() }

    fun lastTask(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TASK, null)

    /** Nudge every placed widget to redraw. */
    private fun poke(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(
            ComponentName(app, DemoWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return
        app.sendBroadcast(
            Intent(app, DemoWidgetProvider::class.java)
                .setAction(DemoWidgetProvider.ACTION_REFRESH)
        )
    }
}
