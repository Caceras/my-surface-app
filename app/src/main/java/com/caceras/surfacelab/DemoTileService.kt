package com.caceras.surfacelab

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Quick Settings tile. The system binds this service only while the shade is
 * open, so the work here stays trivial and every qsTile access is guarded --
 * touching it outside the listening window is the most common way these
 * services crash.
 *
 * In the "nano" flavour this is the honest place to see whether Gemini Nano
 * is actually present on the device, and to trigger the one-time feature
 * download without hunting through system settings.
 */
class DemoTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        BrainProvider.get().status(this) { render(it) }
    }

    override fun onClick() {
        super.onClick()
        val brain = BrainProvider.get()
        brain.status(this) { status ->
            when {
                status.preparable -> {
                    render(BrainStatus("Preparing", ready = false))
                    brain.prepare(this) { render(it) }
                }
                status.ready -> {
                    val last = ResultStore.lastText(this)
                    Toast.makeText(
                        this,
                        last ?: "Ready. Select text anywhere to use it.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                else -> Toast.makeText(this, status.label, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun render(status: BrainStatus) {
        val tile = qsTile ?: return
        tile.state = if (status.ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.brain_name)
        // Tile.setSubtitle landed in API 29, which is this app's minSdk.
        tile.subtitle = status.label
        tile.icon = Icon.createWithResource(this, R.drawable.ic_surface)
        tile.updateTile()
    }
}
