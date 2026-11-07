package io.github.akane599.umbra

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot no session is running: put back a screen timeout that one left raised (see [ScreenTimeout]). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ScreenTimeout.recover(context)
    }
}
