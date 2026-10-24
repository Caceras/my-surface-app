package com.caceras.surfacelab

import android.content.Context

/** The tools the phone agent gets for a question: the hands when they are switched on and connected. */
object PhoneTools {
    fun forAgent(context: Context): AgentToolbox = NoTools
}
