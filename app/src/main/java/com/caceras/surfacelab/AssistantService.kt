package com.caceras.surfacelab

import android.service.voice.VoiceInteractionService

/**
 * Ægentica as the phone's digital assistant (Settings → Apps → Default apps →
 * Digital assistant app). The platform keeps this service bound while it is
 * selected, so it does nothing itself; each invocation (corner swipe or power
 * button) creates an [AssistantSession].
 */
class AssistantService : VoiceInteractionService()

