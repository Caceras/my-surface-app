package com.caceras.surfacelab

/** The framework demo build has no WebSocket client, so Gemini Live is unavailable. */
object LiveTransports {
    const val available = false
    fun connect(url: String, listener: LiveTransportListener): LiveTransport = error("Gemini Live needs the Nano build.")
}
