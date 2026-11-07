package io.github.akane599.umbra

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes

/** A Quick Settings tile that starts or stops one session mode of [PanelService]. */
abstract class ModeTileService : TileService() {

    protected abstract val mode: PanelService.Mode
    protected abstract val startAction: String
    protected open val stopAction: String = PanelService.ACTION_STOP
    @get:StringRes protected abstract val label: Int
    @get:StringRes protected abstract val subActive: Int
    @get:StringRes protected abstract val subInactive: Int

    /** Close the shade before starting: the session is about the app underneath, not the shade. */
    protected open val collapseOnStart: Boolean = false

    override fun onStartListening() {
        super.onStartListening()
        render(PanelService.isActive(mode))
        ScreenTimeout.recover(this)
    }

    override fun onClick() {
        super.onClick()
        val starting = !PanelService.isActive(mode)
        val action = if (starting) startAction else stopAction
        if ((starting && collapseOnStart) || !PanelService.command(this, action, "tile")) {
            // Also the fallback when the background FGS restriction blocks a direct start.
            launchCollapsed(action)
        }
        render(starting)
    }

    /** Starts the command through the invisible activity, which also collapses the shade. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchCollapsed(action: String) {
        val intent = Intent().setClassName(this, ActionActivity.SHORTCUT_ALIAS)
            .setAction(action)
            .putExtra(PanelService.EXTRA_SOURCE, "tile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, mode.ordinal, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(label)
        tile.subtitle = getString(if (active) subActive else subInactive)
        tile.updateTile()
    }
}

/** Screen off. Keeps its historic class name so tiles users already placed keep working. */
class PanelTileService : ModeTileService() {
    override val mode = PanelService.Mode.PANEL
    override val startAction = PanelService.ACTION_OFF
    override val stopAction = PanelService.ACTION_ON
    override val label = R.string.tile_label
    override val subActive = R.string.tile_sub_active
    override val subInactive = R.string.tile_sub_inactive

    companion object {
        private val ALL = listOf(PanelTileService::class.java, LockTileService::class.java, AwakeTileService::class.java)

        /** Asks every tile to re-read the session state. */
        fun refresh(context: Context) {
            for (c in ALL) runCatching { requestListeningState(context, ComponentName(context, c)) }
        }
    }
}

class LockTileService : ModeTileService() {
    override val mode = PanelService.Mode.LOCK
    override val startAction = PanelService.ACTION_LOCK
    override val label = R.string.lock_tile_label
    override val subActive = R.string.lock_tile_sub_active
    override val subInactive = R.string.lock_tile_sub_inactive
    override val collapseOnStart = true
}

class AwakeTileService : ModeTileService() {
    override val mode = PanelService.Mode.AWAKE
    override val startAction = PanelService.ACTION_AWAKE
    override val label = R.string.awake_tile_label
    override val subActive = R.string.awake_tile_sub_active
    override val subInactive = R.string.awake_tile_sub_inactive
}
