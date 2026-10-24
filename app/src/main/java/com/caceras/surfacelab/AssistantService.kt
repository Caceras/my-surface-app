package com.caceras.surfacelab

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

/**
 * Ægentica as the phone's digital assistant (Settings → Apps → Default apps →
 * Digital assistant app). The platform keeps this service bound while it is
 * selected, so it does nothing itself; each invocation (corner swipe or power
 * button) creates an [AssistantSession].
 */
class AssistantService : VoiceInteractionService() {
    override fun onReady() { super.onReady(); running = this }
    override fun onShutdown() { if (running === this) running = null; super.onShutdown() }

    companion object {
        @Volatile private var running: AssistantService? = null

        /** Ægentica is the phone's assistant right now, so [onScreen] can open it. */
        val available get() = running != null

        /**
         * Opens the assistant on the current screen (its text and a screenshot), exactly as the
         * gesture does; the keyboard's "Use the screen" calls this. False when Ægentica is not the
         * phone's assistant. The keyboard never captures the screen itself.
         */
        fun onScreen(): Boolean {
            val service = running ?: return false
            return runCatching { service.showSession(Bundle(), VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT) }.isSuccess
        }
    }
}
