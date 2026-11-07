package io.github.akane599.umbra

import android.content.Context
import android.provider.Settings

/**
 * Keeps the screen from timing out by raising the system screen timeout to its maximum, and puts the user's value
 * back afterwards.
 *
 * A screen wake lock alone isn't enough on Samsung (One UI): when the normal timeout runs out it treats a screen wake
 * lock held by an app with nothing on screen as abuse ("abuse wakelock package" in the log), dims, and turns the
 * screen off 20 s later. A timeout that never runs out never gets there. Callers keep their wake lock as well, for
 * phones where Umbra may not change system settings ("Modify system settings", granted by the helper).
 *
 * The user's value is saved before the setting changes, so [release] can still put it back after a crash, a force
 * stop or a reboot: [recover] runs whenever the app starts and no session is running.
 */
class ScreenTimeout(private val setting: Setting, private val store: Store) {

    /** The system screen timeout, in ms. */
    interface Setting {
        fun get(): Int?
        /** False if it couldn't be written (no permission). */
        fun put(ms: Int): Boolean
    }

    /** Where the user's own timeout waits while it is raised. [save] must be durable before it returns. */
    interface Store {
        fun load(): Int?
        fun save(ms: Int?)
    }

    /** Raises the timeout. Returns whether the setting now keeps the screen on. */
    fun hold(): Boolean {
        val current = setting.get() ?: return false
        // Raised already: by us (a value is saved) or by the user (nothing to put back later either way).
        if (current == FOREVER) return true
        store.save(current)
        if (setting.put(FOREVER)) return true
        store.save(null)
        return false
    }

    /** Puts the user's timeout back, unless they changed it in the meantime. */
    fun release() {
        val saved = store.load() ?: return
        // Couldn't write (permission revoked): keep the value for the next try.
        if (setting.get() == FOREVER && !setting.put(saved)) return
        store.save(null)
    }

    companion object {
        /** What the setting is raised to: about 24 days, which the system treats as never. */
        const val FOREVER = Int.MAX_VALUE

        fun canHold(context: Context): Boolean = Settings.System.canWrite(context)

        fun of(context: Context): ScreenTimeout {
            val app = context.applicationContext
            val prefs = AppSettings(app)
            val cr = app.contentResolver
            return ScreenTimeout(
                object : Setting {
                    override fun get() =
                        runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()

                    override fun put(ms: Int) = canHold(app) &&
                        runCatching { Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, ms) }.getOrDefault(false)
                },
                object : Store {
                    override fun load() = prefs.savedScreenTimeout
                    override fun save(ms: Int?) { prefs.savedScreenTimeout = ms }
                },
            )
        }

        /** Puts back a timeout that a session raised and never lowered (crash, force stop, reboot). */
        fun recover(context: Context) {
            if (!PanelService.isActive) of(context).release()
        }
    }
}
