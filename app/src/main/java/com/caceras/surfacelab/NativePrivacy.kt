package com.caceras.surfacelab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.view.Window
import android.view.WindowManager

/** Explicit surface privacy preferences; chat storage and exports stay compatible. */
object NativePrivacy {
    private fun prefs(context: Context) = context.getSharedPreferences("surfacelab", Context.MODE_PRIVATE)
    fun privateScreen(context: Context) = prefs(context).getBoolean("private_screen", false)
    fun setPrivateScreen(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("private_screen", enabled).apply() }
    fun widgetPreview(context: Context) = prefs(context).getBoolean("widget_preview", false)
    fun setWidgetPreview(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("widget_preview", enabled).apply()
        SurfaceWidgetProvider.refresh(context)
    }
    fun apply(context: Context, window: Window?) {
        if (privateScreen(context)) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    fun copy(context: Context, label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }
}

fun android.app.AlertDialog.Builder.showProtected(context: Context): android.app.AlertDialog =
    show().also { NativePrivacy.apply(context, it.window) }
