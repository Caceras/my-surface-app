package com.caceras.surfacelab

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantSession(this)
}

/**
 * One invocation. The session draws no window of its own: it opens the floating
 * genie ([RabbitOverlay], when Ægentica hands is on) or [AssistantActivity] above
 * the current app, and hands it the screen text and screenshot as Android
 * delivers them. Context arrives only when the owner
 * invokes the assistant and has left *Use text from screen* / *Use
 * screenshot* on; secure windows arrive empty.
 */
class AssistantSession(context: Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenContexts.begin(this)
        // With Ægentica hands on, the genie floats over the app and stays while the owner keeps
        // using the phone; otherwise the sheet opens, which also answers offline with Gemini Nano.
        val genie = Hands.overlay
        if (genie != null && HandsTools.floats(context) && PhoneAgent.ready(context) && PhoneAgent.online(context)) { genie.summon(); return }
        startAssistantActivity(AssistantActivity.intent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onHandleAssist(state: AssistState) {
        if (!state.isFocused) return
        val structure = state.assistStructure ?: return
        ScreenContexts.text(structure.activityComponent?.packageName.orEmpty(), ScreenContexts.from(structure))
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot != null) ScreenContexts.screenshot(screenshot)
    }
}

