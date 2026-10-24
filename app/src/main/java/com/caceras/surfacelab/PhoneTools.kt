package com.caceras.surfacelab

import android.content.Context

/**
 * The tools the phone agent gets for a question: the browser inside the sheet always, Ægentica
 * hands when the service is on and connected, and Ægentica's back office when the phone is
 * connected to aegentica.se.
 */
object PhoneTools {
    fun forAgent(context: Context): AgentToolbox {
        val app = context.applicationContext
        val parts = listOfNotNull(
            HandsTools(app).takeIf { Hands.connected && HandsTools.enabled(app) },
            BackendTools(app).takeIf { Backend.connected(app) },
            WebTools(app),
        )
        return if (parts.size == 1) parts.single() else Toolboxes(parts)
    }
}
