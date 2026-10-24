package com.caceras.surfacelab

import android.content.Context

/** The tools the phone agent gets for a question: Ægentica hands when the service is on and connected. */
object PhoneTools {
    fun forAgent(context: Context): AgentToolbox = if (Hands.connected && HandsTools.enabled(context)) HandsTools(context.applicationContext) else NoTools
}
