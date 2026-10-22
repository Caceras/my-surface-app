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
 * One invocation. The session draws no window of its own: it opens
 * [AssistantActivity] above the current app and hands it the screen text and
 * screenshot as Android delivers them. Context arrives only when the owner
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

