package com.caceras.surfacelab

import android.app.Activity
import android.app.AlertDialog
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Connecting this phone to Ægentica on aegentica.se, in plain words: what the phone agent can then
 * do with the owner's memory, Inbox, tasks and server jobs, what never happens without them, and
 * how to disconnect. aegentica.se makes the access token and hands it to the app directly.
 */
object BackendSetup {
    const val EXPLAIN = "Connect this phone to Ægentica on aegentica.se, and the phone agent can:\n\n" +
        "• Check what Ægentica remembers about you, and remember new things when you ask.\n" +
        "• Read your Inbox and draft replies. Drafts are never sent: you review and send them in Ægentica.\n" +
        "• Propose scheduled tasks. They start turned off until you turn them on.\n" +
        "• Hand long jobs to Ægentica's server agent. It keeps working while the phone sleeps; you watch it here, answer its questions and approve anything that costs money, with the price shown first.\n" +
        "• Keep your phone chats in Ægentica's chat history, so they continue on your computer.\n\n" +
        "Connect opens aegentica.se's Settings → Security. Tap Make token there; the app keeps it encrypted on this phone. Disconnect here or there at any time."

    fun show(activity: Activity) {
        val connected = Backend.token(activity) != null
        val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; padDp(20, 8, 20, 8) }
        val state = if (connected) "Connected as “${Backend.name(activity) ?: "Pixel"}”.\n\n" else "Not connected.\n\n"
        layout.addView(activity.label(state + EXPLAIN, 14f, true).apply { tag = "backend-explain" })
        layout.addView(activity.preferenceSwitch("Keep phone chats in Ægentica's chat history", Backend.saving(activity)) { Backend.setSaving(activity, it) }.apply { tag = "backend-save" })
        layout.addView(activity.label("Questions and answers only, as text. Screens and screenshots stay on the phone.", 12f, true))
        val dialog = AlertDialog.Builder(activity).setTitle("Connect to Ægentica").setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton(if (connected) "Connect again" else "Connect") { _, _ -> activity.startActivity(AegenticaActivity.connect(activity)) }
            .setNegativeButton("Done", null)
        if (connected) dialog.setNeutralButton("Disconnect") { _, _ ->
            Backend.disconnect(activity)
            android.widget.Toast.makeText(activity, "Disconnected on this phone. To end the access everywhere, remove it in aegentica.se's Settings → Security.", android.widget.Toast.LENGTH_LONG).show()
        }
        dialog.showProtected(activity)
    }
}
