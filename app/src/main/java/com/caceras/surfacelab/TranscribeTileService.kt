package com.caceras.surfacelab

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * One tap from the shade starts a transcript, or opens the running one. A tile
 * cannot hold the microphone or ask for permission, so it opens the visible
 * Transcribe screen, which starts the foreground service.
 */
class TranscribeTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        val state = TranscriptionService.state
        tile.state = if (state.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.shortcut_transcribe)
        tile.subtitle = when { state.active && state.paused -> "Paused"; state.active -> "Recording"; else -> "Tap to start" }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_mic)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isSecure) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        val intent = TranscribeActivity.intent(this, start = !TranscriptionService.state.active).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else launchLegacy(intent)
    }

    // The PendingIntent overload does not exist below API 34; see SurfaceTileService.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchLegacy(intent: Intent) {
        check(Build.VERSION.SDK_INT < 34)
        startActivityAndCollapse(intent)
    }

    companion object {
        fun refresh(context: Context) { runCatching { requestListeningState(context, ComponentName(context, TranscribeTileService::class.java)) } }
    }
}
