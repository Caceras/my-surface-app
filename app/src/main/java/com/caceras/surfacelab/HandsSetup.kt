package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Turning on Ægentica hands, in plain words: what it lets the assistant do, what it never does,
 * how to stop it, and how to turn it off. Android's own Accessibility settings switch it on; a
 * sideloaded app may first need "Allow restricted settings" in its App info.
 */
object HandsSetup {
    fun component(context: Context) = ComponentName(context, HandsService::class.java)

    /** Whether Android has the service switched on (it may still be connecting). */
    fun on(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = component(context)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    const val EXPLAIN = "Ægentica hands lets the assistant see your screen and tap, type, scroll and open apps for you, " +
        "only while it works on something you asked.\n\n" +
        "• A rabbit shows every move before it happens, and Stop stays on screen.\n" +
        "• Touch the screen and it pauses; tap Continue to hand it back.\n" +
        "• It asks before each step unless you allow an app always. Anything that sends, calls, pays, buys, deletes or changes an account always asks.\n" +
        "• It never types passwords, card numbers or one-time codes: it asks you to take over.\n" +
        "• What it sees on the screen goes to the AI you chose (Settings → Phone agent), only for your request.\n\n" +
        "Turn it off at any time in Android's Settings → Accessibility → Ægentica hands, or press Stop to end a task at once.\n\n" +
        "If Android says the setting is restricted (apps installed from a file): open App info, tap ⋮, choose Allow restricted settings, then try again."

    fun show(activity: Activity, after: () -> Unit = {}) {
        val prefs = activity.getSharedPreferences(HandsTools.PREFS, 0)
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; padDp(20, 8, 20, 8) }
        layout.addView(activity.label((if (on(activity)) "On. " else "Off. ") + EXPLAIN, 14f, true).apply { tag = "hands-explain" })
        layout.addView(activity.preferenceSwitch("Let the assistant use the hands", prefs.getBoolean("enabled", true)) {
            prefs.edit().putBoolean("enabled", it).apply()
        }.apply { tag = "hands-enabled" })
        layout.addView(activity.preferenceSwitch("Hands-free: tap and type without asking each time", prefs.getBoolean("hands_free", false)) {
            prefs.edit().putBoolean("hands_free", it).apply()
        }.apply { tag = "hands-free" })
        layout.addView(activity.label("Sending, calling, paying, buying, deleting and account changes still ask every time.", 12f, true))
        layout.addView(activity.preferenceSwitch("The assistant gesture opens the floating bar", prefs.getBoolean("float", true)) {
            prefs.edit().putBoolean("float", it).apply()
        }.apply { tag = "hands-float" })
        val always = HandsTools.always(activity)
        if (always.isNotEmpty()) {
            val names = always.map { pkg -> runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            layout.addView(activity.label("Allowed always in: " + names.sorted().joinToString(", "), 13f, true).apply { padDp(0, 12, 0, 4) })
            layout.addView(activity.pill("Forget these") { HandsTools.forgetAlways(activity) }.apply { tag = "hands-forget" })
        }
        AlertDialog.Builder(activity).setTitle("Ægentica hands").setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton(if (on(activity)) "Accessibility settings" else "Turn on in Settings") { _, _ -> openSettings(activity); after() }
            .setNeutralButton("App info") { _, _ ->
                runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))) }
            }
            .setNegativeButton("Done") { _, _ -> after() }
            .showProtected(activity)
    }

    /** Android's Accessibility settings, with Ægentica hands highlighted where the phone supports it. */
    fun openSettings(activity: Activity) {
        val key = component(activity).flattenToString()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(":settings:fragment_args_key", key)
            .putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        runCatching { activity.startActivity(intent) }.onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }
}
