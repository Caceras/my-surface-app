package com.caceras.surfacelab

import android.content.Context

/** The framework demo build has no WebRTC, so the LiveKit agent is unavailable. */
object AgentRoomFactory {
    const val available = false
    fun connect(context: Context, url: String, token: String, listener: AgentRoomListener): AgentRoom = error("Agent voice needs the Nano build.")
}
