package com.caceras.surfacelab

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile. The system binds this service only while the shade is
 * open, so the work here stays trivial and every qsTile access is guarded --
 * touching it outside the listening window is the most common way these
 * services crash.
 *
 * Once there is a model to talk to, a tap is the hands-free path: open the
 * shade, tap, talk, hear the answer. That is what VoiceActivity is for. A
 * tile cannot request a permission and cannot hold a microphone, so it
 * launches an activity that can.
 *
 * In the "nano" flavour this is also the honest place to see whether Gemini
 * Nano is actually present, and to trigger the one-time feature download
 * without hunting through system settings.
 */
class SurfaceTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Brains.get().status(this) { render(it) }
    }

    override fun onClick() {
        super.onClick()
        if (isSecure) unlockAndRun { launch() } else launch()
    }

    /** Open a foreground surface so permission and model setup remain visible. */
    private fun launch() {
        // A service is not an activity, so the intent inside needs
        // FLAG_ACTIVITY_NEW_TASK or the launch is refused outright.
        val intent = Intent(this, if (Ears(this).available()) VoiceActivity::class.java else MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From API 34 the Intent overload throws. From API 31 a
            // PendingIntent built with neither mutability flag also throws,
            // which is the same trap the widget already stepped in.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            // Deprecated at 34, and the only overload that exists below it.
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: BrainStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.brain_name)
        // Voice status belongs on the same line as the model status: both
        // answer "will a tap do anything", and there is only one line.
        // Tile.setSubtitle landed in API 29, which is this app's minSdk.
        tile.subtitle = when {
            !status.ready -> status.label
            Ears(this).available() -> getString(R.string.voice_ready)
            else -> getString(R.string.voice_off)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_surface)
        tile.updateTile()
    }
}
