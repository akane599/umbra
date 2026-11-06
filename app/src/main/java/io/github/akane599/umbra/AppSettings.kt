package io.github.akane599.umbra

import android.content.Context
import androidx.core.content.edit

class AppSettings(context: Context) {
    // androidx.preference's default file, so settings from older versions carry over.
    private val sp = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    val privilegeMode: String get() = sp.getString(K_PRIVILEGE, "auto") ?: "auto"
    val powerMethod: String get() = sp.getString(K_METHOD, "auto") ?: "auto"
    val exitVolume: Boolean get() = sp.getBoolean(K_EXIT_VOLUME, true)
    val exitPower: Boolean get() = sp.getBoolean(K_EXIT_POWER, true)
    val exitDoubleTap: Boolean get() = sp.getBoolean(K_EXIT_DOUBLE_TAP, true)
    val exitShake: Boolean get() = sp.getBoolean(K_EXIT_SHAKE, false)
    val timerMinutes: Int get() = sp.getString(K_TIMER, "0")?.toIntOrNull() ?: 0
    val keepAwake: Boolean get() = sp.getBoolean(K_KEEP_AWAKE, true)
    val lockOnRestore: Boolean get() = sp.getBoolean(K_LOCK_ON_RESTORE, DEF_LOCK_ON_RESTORE)
    val collapseShade: Boolean get() = sp.getBoolean(K_COLLAPSE, true)
    val holdOff: Boolean get() = sp.getBoolean(K_HOLD, true)

    val allowExternal: Boolean get() = sp.getBoolean(K_ALLOW_EXTERNAL, DEF_ALLOW_EXTERNAL)

    /** Touch lock: scrim strength in percent (0 = no dim). */
    val lockDim: Int get() = sp.getString(K_LOCK_DIM, "0")?.toIntOrNull()?.coerceIn(0, 95) ?: 0
    val lockBadge: Boolean get() = sp.getBoolean(K_LOCK_BADGE, true)
    val lockFull: Boolean get() = sp.getBoolean(K_LOCK_FULL, false)

    val lastSessionMs: Long get() = sp.getLong(K_LAST_MS, -1L)
    val lastSessionReason: String? get() = sp.getString(K_LAST_REASON, null)
    val lastSessionEnd: Long get() = sp.getLong(K_LAST_END, 0L)
    val sessionCount: Int get() = sp.getInt(K_COUNT, 0)

    fun recordSession(durationMs: Long, reason: String) = sp.edit {
        putLong(K_LAST_MS, durationMs)
        putString(K_LAST_REASON, reason)
        putLong(K_LAST_END, System.currentTimeMillis())
        putInt(K_COUNT, sessionCount + 1)
    }

    /** Last failed turn-off attempt; cleared on the next successful session or when the user dismisses it. */
    val lastError: ActivationError? get() = ActivationError.fromName(sp.getString(K_ERR_KIND, null))
    val lastErrorRaw: String? get() = sp.getString(K_ERR_RAW, null)
    val lastErrorAt: Long get() = sp.getLong(K_ERR_AT, 0L)

    fun recordError(kind: ActivationError, raw: String) = sp.edit {
        putString(K_ERR_KIND, kind.name)
        putString(K_ERR_RAW, raw)
        putLong(K_ERR_AT, System.currentTimeMillis())
    }

    fun clearError() {
        if (sp.contains(K_ERR_KIND)) sp.edit { remove(K_ERR_KIND); remove(K_ERR_RAW); remove(K_ERR_AT) }
    }

    fun putBoolean(key: String, v: Boolean) = sp.edit { putBoolean(key, v) }
    fun putString(key: String, v: String) = sp.edit { putString(key, v) }
    fun getBoolean(key: String, def: Boolean) = sp.getBoolean(key, def)
    fun getString(key: String, def: String) = sp.getString(key, def) ?: def

    var setupDone: Boolean
        get() = sp.getBoolean(K_SETUP_DONE, false)
        set(v) = sp.edit { putBoolean(K_SETUP_DONE, v) }

    companion object {
        const val K_PRIVILEGE = "privilege_mode"
        const val K_METHOD = "power_method"
        const val K_EXIT_VOLUME = "exit_volume_combo"
        const val K_EXIT_POWER = "exit_power"
        const val K_EXIT_DOUBLE_TAP = "exit_double_tap"
        const val K_EXIT_SHAKE = "exit_shake"
        const val K_TIMER = "exit_timer"
        const val K_KEEP_AWAKE = "keep_awake"
        const val K_LOCK_ON_RESTORE = "lock_on_restore"
        /** On: only acts when the phone has a secure lock screen (see PanelService.restoreLocks). */
        const val DEF_LOCK_ON_RESTORE = true
        const val K_COLLAPSE = "collapse_shade"
        const val K_HOLD = "hold_off"
        const val K_SETUP_DONE = "setup_done"
        const val K_ALLOW_EXTERNAL = "allow_external"
        /** Off: any installed app could otherwise start sessions (OFF/LOCK/AWAKE); ON and STOP are always accepted. */
        const val DEF_ALLOW_EXTERNAL = false
        const val K_LOCK_DIM = "lock_dim"
        const val K_LOCK_BADGE = "lock_badge"
        const val K_LOCK_FULL = "lock_full"
        private const val K_LAST_MS = "last_session_ms"
        private const val K_LAST_REASON = "last_session_reason"
        private const val K_LAST_END = "last_session_end"
        private const val K_COUNT = "session_count"
        private const val K_ERR_KIND = "last_error_kind"
        private const val K_ERR_RAW = "last_error_raw"
        private const val K_ERR_AT = "last_error_at"
    }
}
