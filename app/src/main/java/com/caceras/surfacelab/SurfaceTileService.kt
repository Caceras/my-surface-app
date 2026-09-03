package com.caceras.surfacelab

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

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
        val brain = Brains.get()
        brain.status(this) { status ->
            when {
                status.preparable -> {
                    render(BrainStatus("Preparing", ready = false))
                    brain.prepare(this) { render(it) }
                }
                status.ready && Ears(this).available() -> talk()
                status.ready -> {
                    val last = ResultStore.lastText(this)
                    Toast.makeText(
                        this,
                        last ?: getString(R.string.voice_unavailable),
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> Toast.makeText(this, status.label, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * A tile is tappable on the lock screen, and for this tile that means
     * opening a microphone and writing the answer somewhere the home screen
     * widget will show it. isSecure() and unlockAndRun() make the phone ask
     * for the PIN first. Nothing else in this app has needed that, because
     * nothing else in it starts a private session from the shade.
     */
    private fun talk() {
        if (isSecure) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        // A service is not an activity, so the intent inside needs
        // FLAG_ACTIVITY_NEW_TASK or the launch is refused outright.
        val intent = Intent(this, VoiceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // From API 34 the Intent overload throws. From API 31 a
            // PendingIntent built with neither mutability flag also throws,
            // which is the same trap the widget already stepped in.
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE
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
