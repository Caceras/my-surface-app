package com.caceras.surfacelab

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings: "Ægentica" with what the genie is doing; a tap opens it. */
class GenieTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val status = GenieStatus.now(this)
        val tile = qsTile ?: return
        tile.label = getString(R.string.genie_tile)
        tile.subtitle = status.words
        tile.state = if (status.working || status.ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_surface)
        tile.contentDescription = "Ægentica: ${status.words}"
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (isSecure) unlockAndRun { launch() } else launch()
    }

    private fun launch() {
        val intent = GenieStatus.open(this, Genie.Origin.TILE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else legacy(intent)
    }

    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun legacy(intent: Intent) = startActivityAndCollapse(intent)
}
