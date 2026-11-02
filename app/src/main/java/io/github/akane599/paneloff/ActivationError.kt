package io.github.akane599.paneloff

import androidx.annotation.StringRes

/**
 * Maps the bridge's raw error text ("Shizuku: … \n Root: server did not start: …") to a
 * user-facing title + explanation + the right action. The raw text stays in diagnostics.
 */
enum class ActivationError(
    @StringRes val title: Int,
    @StringRes val body: Int,
    @StringRes val action: Int,
) {
    SHIZUKU_PERMISSION(R.string.err_shizuku_perm_title, R.string.err_shizuku_perm_body, R.string.err_action_grant),
    SHIZUKU_OFF(R.string.err_shizuku_off_title, R.string.err_shizuku_off_body, R.string.err_action_open_shizuku),
    ROOT_DENIED(R.string.err_root_title, R.string.err_root_body, R.string.err_action_diag),
    GENERIC(R.string.err_generic_title, R.string.err_generic_body, R.string.err_action_diag);

    companion object {
        fun classify(raw: String, privilegeMode: String): ActivationError {
            val root = raw.lineSequence().firstOrNull { it.startsWith("Root:") }
            return when {
                PrivilegedBridge.ERR_SHIZUKU_NO_PERMISSION in raw -> SHIZUKU_PERMISSION
                privilegeMode == "root" && root != null -> ROOT_DENIED
                PrivilegedBridge.ERR_SHIZUKU_NOT_RUNNING in raw && privilegeMode == "shizuku" -> SHIZUKU_OFF
                // Auto: no Shizuku, and root is missing/denied → the likeliest fix is starting Shizuku.
                PrivilegedBridge.ERR_SHIZUKU_NOT_RUNNING in raw && root != null &&
                    PrivilegedBridge.ERR_SERVER_NOT_STARTED in root -> SHIZUKU_OFF
                else -> GENERIC
            }
        }

        fun fromName(name: String?): ActivationError? = entries.firstOrNull { it.name == name }
    }
}
