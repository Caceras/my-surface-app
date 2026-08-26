package com.example.surfacelab

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Quick Settings tile. The system binds this service only while the shade
 * is open, so keep the work here trivial.
 */
class DemoTileService : TileService() {

    private var active = false

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        active = !active
        render()
        Toast.makeText(
            this,
            if (active) "Tile ON" else "Tile OFF",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun render() {
        // qsTile is null outside the listening window -- touching it
        // unguarded is the most common way these services crash.
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        // Tile.setSubtitle landed in API 29; this app runs from 26, so
        // calling it unguarded throws NoSuchMethodError on older devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (active) "On" else "Off"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_surface)
        tile.updateTile()
    }
}
