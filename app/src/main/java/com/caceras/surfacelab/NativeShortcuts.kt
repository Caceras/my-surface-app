package com.caceras.surfacelab

import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast

object NativeShortcuts {
    const val HISTORY = "com.caceras.surfacelab.OPEN_HISTORY"
    const val ACTIONS = "com.caceras.surfacelab.OPEN_ACTIONS"
    fun pin(activity: Activity, voice: Boolean) {
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager?.isRequestPinShortcutSupported != true) {
            Toast.makeText(activity, "Your launcher does not support pinning. Long-press the app icon for shortcuts.", Toast.LENGTH_LONG).show()
            return
        }
        val shortcut = ShortcutInfo.Builder(activity, if (voice) "pinned-voice" else "pinned-chat")
            .setShortLabel(if (voice) "Talk to Æ" else "Ask Æ")
            .setIcon(Icon.createWithResource(activity, R.mipmap.ic_launcher))
            .setIntent(Intent(activity, if (voice) VoiceActivity::class.java else MainActivity::class.java).setAction(Intent.ACTION_VIEW))
            .build()
        runCatching { manager.requestPinShortcut(shortcut, null) }.onFailure {
            Toast.makeText(activity, "Could not request the shortcut. Try your launcher’s app menu.", Toast.LENGTH_LONG).show()
        }
    }
}
