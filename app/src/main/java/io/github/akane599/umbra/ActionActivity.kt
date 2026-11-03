package io.github.akane599.umbra

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Invisible trigger. Runs under two identities:
 *  - `.ShortcutActivity` (non-exported alias): launcher shortcut and QS tile fallback.
 *    Only this app (or the system on its behalf) can start it; always trusted.
 *  - `.ActionActivity` (exported): Tasker etc. "Send Intent → Activity". If the
 *    "Commands from other apps" setting is off, only ON/STOP (ending a session) are accepted.
 *
 * Being in the foreground, it isn't subject to background FGS start restrictions.
 * Actions: io.github.akane599.umbra.OFF | ON | TOGGLE | LOCK | AWAKE | STOP
 */
class ActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action?.takeIf { it in PanelService.ALL_ACTIONS } ?: PanelService.ACTION_TOGGLE
        val internal = componentName.className == SHORTCUT_ALIAS
        val source = if (internal) intent?.getStringExtra(PanelService.EXTRA_SOURCE) ?: "shortcut" else "external"
        if (internal || action in PanelService.SAFE_ACTIONS || AppSettings(this).allowExternal) {
            PanelService.command(applicationContext, action, source)
        } else {
            Log.w("ActionActivity", "external command rejected: $action (disabled in settings)")
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val SHORTCUT_ALIAS = "io.github.akane599.umbra.ShortcutActivity"
    }
}
