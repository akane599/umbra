package io.github.akane599.umbra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Broadcast receiver for Tasker/automation (Send Intent → Target: Broadcast Receiver).
 * Starting an FGS from the background requires the app to be exempt from battery optimization;
 * "Grant required permissions" does this automatically via Shizuku/root.
 *
 * Any app can send this broadcast. If "Commands from other apps" is off, only ON and STOP
 * (ending a session) are accepted.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in PanelService.ALL_ACTIONS) return
        if (action !in PanelService.SAFE_ACTIONS && !AppSettings(context).allowExternal) {
            Log.w(TAG, "external command rejected: $action (disabled in settings)")
            return
        }
        if (!PanelService.command(context, action, "broadcast")) {
            Log.w(TAG, "could not start service; use ActionActivity")
        }
    }

    private companion object {
        const val TAG = "CommandReceiver"
    }
}
