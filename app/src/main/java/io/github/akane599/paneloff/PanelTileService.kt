package io.github.akane599.paneloff

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class PanelTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render(PanelService.isActive)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val turningOff = !PanelService.isActive
        val action = if (turningOff) PanelService.ACTION_OFF else PanelService.ACTION_ON
        if (!PanelService.command(this, action, "tile")) {
            // Blocked by the background FGS restriction: start via an invisible activity instead.
            val intent = Intent().setClassName(this, ActionActivity.SHORTCUT_ALIAS)
                .setAction(action)
                .putExtra(PanelService.EXTRA_SOURCE, "tile")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
        render(turningOff)
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = getString(if (active) R.string.tile_sub_active else R.string.tile_sub_inactive)
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(context, ComponentName(context, PanelTileService::class.java))
            }
        }
    }
}
