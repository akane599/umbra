package io.github.akane599.umbra

import android.Manifest
import android.app.StatusBarManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.akane599.umbra.PrivilegedBridge.Companion.ShizukuState
import io.github.akane599.umbra.databinding.ActivityMainBinding
import io.github.akane599.umbra.ui.PowerOrbView
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var settings: AppSettings
    private val io = Executors.newSingleThreadExecutor()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> postRefresh() }
    private val binderListener = Shizuku.OnBinderReceivedListener { postRefresh() }
    private val deadListener = Shizuku.OnBinderDeadListener { postRefresh() }
    private val stateListener: (PanelService.State) -> Unit = { runOnUiThread { refresh() } }

    /** Rows whose summaries refresh when a setting changes. */
    private val dynamicRows = mutableListOf<() -> Unit>()
    private lateinit var noExitWarning: TextView
    private var advancedOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        ViewCompat.setOnApplyWindowInsetsListener(b.scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            b.statusScrim.layoutParams = b.statusScrim.layoutParams.apply { height = bars.top }
            insets
        }

        b.orb.setOnClickListener { onOrbClick() }
        b.privCard.setOnClickListener { onPrivilegeClick() }
        b.privAction.setOnClickListener { requestShizuku() }
        b.errorDismiss.setOnClickListener { settings.clearError(); refresh() }
        b.errorAction.setOnClickListener { onErrorAction() }
        b.advancedHeader.setOnClickListener { setAdvancedOpen(!advancedOpen, animate = true) }
        b.version.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        buildExits()
        buildBehavior()
        buildSetup()
        buildAdvanced()
        setAdvancedOpen(savedInstanceState?.getBoolean(STATE_ADVANCED) == true, animate = false)

        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED, advancedOpen)
    }

    override fun onStart() {
        super.onStart()
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        PanelService.addListener(stateListener)
        refresh()
    }

    override fun onStop() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        PanelService.removeListener(stateListener)
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // ================================================================ state

    private fun postRefresh() = runOnUiThread { if (!isDestroyed) refresh() }

    private fun refresh() {
        val shizuku = PrivilegedBridge.shizukuState()
        val mode = settings.privilegeMode
        val blocked = mode == "shizuku" && shizuku != ShizukuState.READY

        // --- main card
        val (orbMode, title, sub) = when {
            PanelService.state == PanelService.State.ACTIVE ->
                Triple(PowerOrbView.Mode.ACTIVE, R.string.hero_title_active, R.string.hero_sub_active)
            PanelService.state != PanelService.State.IDLE ->
                Triple(PowerOrbView.Mode.BUSY, R.string.hero_title_busy, R.string.hero_sub_busy)
            blocked ->
                Triple(PowerOrbView.Mode.BLOCKED, R.string.hero_title_blocked, R.string.hero_sub_blocked)
            else ->
                Triple(PowerOrbView.Mode.READY, R.string.hero_title_ready, R.string.hero_sub_ready)
        }
        b.orb.mode = orbMode
        b.orb.contentDescription = getString(title)
        b.heroTitle.setText(title)
        b.heroSub.setText(sub)

        renderExitPills()
        val last = settings.lastSessionMs
        b.lastSession.isVisible = last >= 0 && PanelService.state == PanelService.State.IDLE
        if (b.lastSession.isVisible) {
            val ago = agoText(settings.lastSessionEnd)
            b.lastSession.text = getString(
                R.string.last_session, formatDuration(last), reasonText(settings.lastSessionReason), ago,
            )
        }

        // --- last error: clears itself if permission was granted afterwards
        var err = settings.lastError
        if (err != null && shizuku == ShizukuState.READY &&
            (err == ActivationError.SHIZUKU_PERMISSION || err == ActivationError.SHIZUKU_OFF)
        ) {
            settings.clearError()
            err = null
        }
        b.errorCard.isVisible = err != null && PanelService.state == PanelService.State.IDLE
        if (err != null) {
            b.errorTitle.setText(err.title)
            b.errorBody.setText(err.body)
            // The action follows Shizuku's current state, not the recorded error: "Grant" is
            // pointless if there's no Shizuku to grant, and so is "Open Shizuku" if it isn't installed.
            val action = when {
                err.action == R.string.err_action_diag -> err.action
                shizuku == ShizukuState.NO_PERMISSION -> R.string.err_action_grant
                shizuku == ShizukuState.NOT_RUNNING &&
                    packageManager.getLaunchIntentForPackage(SHIZUKU_PKG) != null -> R.string.err_action_open_shizuku
                else -> R.string.err_action_diag
            }
            b.errorAction.setText(action)
            b.errorAction.tag = action
        }

        // --- privilege card
        val ok = ContextCompat.getColor(this, R.color.pk_accent)
        val warn = ContextCompat.getColor(this, R.color.pk_warn)
        val dim = ContextCompat.getColor(this, R.color.pk_text_dim)
        val modeLabel = resources.getStringArray(R.array.privilege_entries)
            .getOrElse(resources.getStringArray(R.array.privilege_values).indexOf(mode)) { mode }
        val (t, s, dot) = when {
            mode == "root" ->
                Triple(getString(R.string.status_root_mode), getString(R.string.status_root_mode_sub), dim)
            shizuku == ShizukuState.READY -> {
                val uid = PrivilegedBridge.shizukuUid()
                Triple(
                    getString(R.string.status_shizuku_ready),
                    getString(R.string.status_shizuku_ready_sub, if (uid == 0) "root" else "adb/shell", modeLabel),
                    ok,
                )
            }
            shizuku == ShizukuState.NO_PERMISSION ->
                Triple(getString(R.string.status_shizuku_no_perm), getString(R.string.status_shizuku_no_perm_sub), warn)
            shizuku == ShizukuState.TOO_OLD ->
                Triple(getString(R.string.status_shizuku_old), getString(R.string.status_shizuku_old_sub), warn)
            else -> Triple(
                getString(R.string.status_shizuku_off),
                getString(if (mode == "shizuku") R.string.status_shizuku_off_strict else R.string.status_shizuku_off_auto),
                if (mode == "shizuku") warn else dim,
            )
        }
        b.privTitle.text = t
        b.privSub.text = s
        b.privDot.backgroundTintList = ColorStateList.valueOf(dot)
        val needsGrant = mode != "root" && shizuku == ShizukuState.NO_PERMISSION
        b.privAction.isVisible = needsGrant
        b.privChevron.isVisible = !needsGrant

        noExitWarning.isVisible = !settings.exitVolume && !settings.exitPower &&
            !settings.exitDoubleTap && !settings.exitShake && settings.timerMinutes == 0
        b.advancedSummary.text = getString(
            R.string.advanced_summary,
            entryFor(R.array.privilege_entries, R.array.privilege_values, settings.privilegeMode),
            entryFor(R.array.method_entries, R.array.method_values, settings.powerMethod),
            getString(if (settings.allowExternal) R.string.on_short else R.string.off_short),
        )

        dynamicRows.forEach { it() }
    }

    /** Exit paths that will actually work once the panel goes dark, shown before pressing. */
    private fun renderExitPills() {
        val pills = buildList {
            if (settings.exitVolume) add(R.drawable.ic_volume to getString(R.string.exit_short_volume))
            if (settings.exitPower) add(R.drawable.ic_power to getString(R.string.exit_short_power))
            if (settings.exitDoubleTap) add(R.drawable.ic_touch to getString(R.string.exit_short_double_tap))
            if (settings.exitShake) add(R.drawable.ic_shake to getString(R.string.exit_short_shake))
            val m = settings.timerMinutes
            if (m > 0) add(R.drawable.ic_timer to getString(R.string.exit_short_timer, m))
            add(R.drawable.ic_tile to getString(R.string.exit_short_tile))
        }
        val group = b.exitPills
        val signature = pills.joinToString("|") { it.second }
        if (group.tag == signature) return
        group.tag = signature
        group.removeAllViews()
        for ((icon, label) in pills) {
            val chip = layoutInflater.inflate(R.layout.chip_pill, group, false) as Chip
            chip.text = label
            chip.setChipIconResource(icon)
            group.addView(chip)
        }
    }

    private fun entryFor(entriesRes: Int, valuesRes: Int, value: String): String {
        val i = resources.getStringArray(valuesRes).indexOf(value).coerceAtLeast(0)
        return resources.getStringArray(entriesRes)[i]
    }

    private fun setAdvancedOpen(open: Boolean, animate: Boolean) {
        advancedOpen = open
        b.advancedBox.isVisible = open
        val rot = if (open) -90f else 90f
        if (animate) b.advancedChevron.animate().rotation(rot).setDuration(200).start()
        else b.advancedChevron.rotation = rot
        ViewCompat.setStateDescription(
            b.advancedHeader, getString(if (open) R.string.expanded else R.string.collapsed),
        )
    }

    private fun onErrorAction() {
        when (b.errorAction.tag as? Int) {
            R.string.err_action_grant -> requestShizuku()
            R.string.err_action_open_shizuku -> openShizuku()
            else -> runDiagnostics()
        }
    }

    /** Session-end reason key (see PanelService.deactivate) → localized label. */
    private fun reasonText(key: String?): String = when (key) {
        "volume" -> getString(R.string.reason_volume)
        "power" -> getString(R.string.reason_power)
        "double_tap" -> getString(R.string.reason_double_tap)
        "shake" -> getString(R.string.reason_shake)
        "timer" -> getString(R.string.reason_timer)
        "tile" -> getString(R.string.reason_tile)
        "notification" -> getString(R.string.reason_notification)
        "broadcast", "external" -> getString(R.string.reason_automation)
        "shortcut" -> getString(R.string.reason_shortcut)
        "app" -> getString(R.string.reason_app)
        "screen_off" -> getString(R.string.reason_screen_off)
        "server_died" -> getString(R.string.reason_server_died)
        null -> "?"
        else -> key // older versions stored a ready-made label
    }

    /** Formats relative time itself so it follows the app's own language instead of mixing with the system locale. */
    private fun agoText(at: Long): String {
        val min = ((System.currentTimeMillis() - at) / DateUtils.MINUTE_IN_MILLIS).coerceAtLeast(0)
        return when {
            min < 1 -> getString(R.string.ago_now)
            min < 60 -> getString(R.string.ago_min, min.toInt())
            min < 60 * 24 -> getString(R.string.ago_hour, (min / 60).toInt())
            else -> getString(R.string.ago_day, (min / (60 * 24)).toInt())
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        return when {
            sec < 60 -> getString(R.string.dur_sec, sec.toInt())
            sec < 3600 -> getString(R.string.dur_min, (sec / 60).toInt())
            else -> getString(R.string.dur_hour, (sec / 3600).toInt(), ((sec % 3600) / 60).toInt())
        }
    }

    // ================================================================ actions

    private fun onOrbClick() {
        when {
            PanelService.state == PanelService.State.ACTIVE ->
                PanelService.command(applicationContext, PanelService.ACTION_ON, "app")
            PanelService.state != PanelService.State.IDLE -> Unit
            b.orb.mode == PowerOrbView.Mode.BLOCKED -> onPrivilegeClick()
            else -> {
                moveTaskToBack(true)
                PanelService.command(applicationContext, PanelService.ACTION_OFF, "app")
            }
        }
    }

    private fun onPrivilegeClick() {
        when (PrivilegedBridge.shizukuState()) {
            ShizukuState.NO_PERMISSION -> if (settings.privilegeMode != "root") requestShizuku() else runDiagnostics()
            ShizukuState.NOT_RUNNING -> if (settings.privilegeMode == "shizuku") openShizuku() else runDiagnostics()
            else -> runDiagnostics()
        }
    }

    private fun requestShizuku() {
        runCatching { Shizuku.requestPermission(REQ_SHIZUKU) }
            .onFailure { toast(getString(R.string.privileged_failed, it.message)) }
    }

    private fun openShizuku() {
        val intent = packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        if (intent != null) startActivity(intent) else runDiagnostics()
    }

    private fun runDiagnostics() {
        runPrivileged("diag") { r ->
            r.onSuccess { text -> showDiagnostics(text) }
                .onFailure { showDiagnostics(getString(R.string.privileged_failed, it.message)) }
        }
    }

    private fun showDiagnostics(raw: String) {
        val lastErr = settings.lastErrorRaw?.let {
            val at = agoText(settings.lastErrorAt)
            getString(R.string.diag_last_error, at, it)
        }.orEmpty()
        val pretty = lastErr + raw.split(Regex("\\s+(?=[A-Za-z_0-9]+=)")).joinToString("\n")
        val pad = dp(24)
        val tv = TextView(this).apply {
            text = pretty
            setTextAppearance(R.style.PK_Text_Mono)
            setTextIsSelectable(true)
            setPadding(pad, dp(8), pad, 0)
            setLineSpacing(0f, 1.25f)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.diag_title)
            .setView(tv)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.diag_copy) { _, _ -> copy(pretty) }
            .show()
    }

    /** Starts a temporary server, runs a single command, then shuts it down. */
    private fun runPrivileged(command: String, onDone: (Result<String>) -> Unit) {
        val ctx = applicationContext
        val mode = settings.privilegeMode
        toast(getString(R.string.working))
        io.execute {
            val result = runCatching {
                val br = PrivilegedBridge(ctx)
                try {
                    br.start(mode)
                    "backend=${br.backend?.label} ${br.request(command, 10_000)}".trim()
                } finally {
                    br.stop()
                }
            }
            runOnUiThread {
                if (!isDestroyed) {
                    onDone(result)
                    refresh()
                }
            }
        }
    }

    @RequiresApi(33)
    private fun requestAddTile() {
        getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(this, PanelTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { }
    }

    // ================================================================ sections

    private fun buildExits() {
        val box = b.exitsBox
        switchRow(
            box, R.drawable.ic_volume, R.string.pref_exit_volume,
            AppSettings.K_EXIT_VOLUME, true, on = R.string.pref_exit_volume_sum,
        )
        switchRow(
            box, R.drawable.ic_power, R.string.pref_exit_power,
            AppSettings.K_EXIT_POWER, true, on = R.string.pref_exit_power_on, off = R.string.pref_exit_power_off,
        )
        switchRow(
            box, R.drawable.ic_touch, R.string.pref_exit_double_tap,
            AppSettings.K_EXIT_DOUBLE_TAP, true,
            on = R.string.pref_exit_double_tap_on, off = R.string.pref_exit_double_tap_off,
        )
        switchRow(
            box, R.drawable.ic_shake, R.string.pref_exit_shake,
            AppSettings.K_EXIT_SHAKE, false, on = R.string.pref_exit_shake_sum,
        )
        timerRow(box)
        noExitWarning = TextView(this).apply {
            setText(R.string.no_exits_warning)
            setTextAppearance(R.style.PK_Text_Body)
            setTextColor(ContextCompat.getColor(context, R.color.pk_warn_text))
            background = ContextCompat.getDrawable(context, R.drawable.bg_warn_inline)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isVisible = false
        }
        box.addView(noExitWarning, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), 0, dp(16), dp(16))
        })
    }

    private fun buildBehavior() {
        val box = b.behaviorBox
        switchRow(
            box, R.drawable.ic_awake, R.string.pref_keep_awake, AppSettings.K_KEEP_AWAKE, true,
            on = R.string.pref_keep_awake_on, off = R.string.pref_keep_awake_off,
        )
        switchRow(box, R.drawable.ic_lock, R.string.pref_hold, AppSettings.K_HOLD, true, on = R.string.pref_hold_sum)
        switchRow(
            box, R.drawable.ic_collapse, R.string.pref_collapse, AppSettings.K_COLLAPSE, true,
            on = R.string.pref_collapse_sum,
        )
    }

    private fun buildSetup() {
        val box = b.setupBox
        if (Build.VERSION.SDK_INT >= 33) {
            actionRow(box, R.drawable.ic_add_tile, R.string.pref_add_tile, R.string.pref_add_tile_sum) { requestAddTile() }
        }
        actionRow(box, R.drawable.ic_verified, R.string.pref_grant, R.string.pref_grant_sum) {
            runPrivileged("grant $packageName") { r ->
                r.onSuccess { settings.setupDone = true }
                toast(r.fold({ getString(R.string.grant_done, it.substringAfterLast(' ')) }, { getString(R.string.privileged_failed, it.message) }))
            }
        }
        actionRow(box, R.drawable.ic_bug, R.string.pref_diag, R.string.pref_diag_sum) { runDiagnostics() }
    }

    private fun buildAdvanced() {
        val box = b.advancedBox
        // Divider between the header row and the first row.
        box.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.pk_outline))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        choiceRow(
            box, R.drawable.ic_shield, R.string.pref_privilege, AppSettings.K_PRIVILEGE,
            R.array.privilege_entries, R.array.privilege_values,
        )
        choiceRow(
            box, R.drawable.ic_tune, R.string.pref_method, AppSettings.K_METHOD,
            R.array.method_entries, R.array.method_values,
        )
        switchRow(
            box, R.drawable.ic_bolt, R.string.pref_external, AppSettings.K_ALLOW_EXTERNAL, true,
            on = R.string.pref_external_on, off = R.string.pref_external_off,
        )
        val code = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_code)
            setPadding(dp(16), dp(8), dp(4), dp(8))
        }
        for (action in listOf(PanelService.ACTION_OFF, PanelService.ACTION_ON, PanelService.ACTION_TOGGLE)) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(
                TextView(this).apply {
                    text = action.replace("io.github.akane599.umbra.", "…")
                    setTextAppearance(R.style.PK_Text_Mono)
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            line.addView(
                MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    setIconResource(R.drawable.ic_copy)
                    iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.pk_text_dim))
                    contentDescription = getString(R.string.copy_action, action.substringAfterLast('.'))
                    setOnClickListener { copy(action) }
                },
            )
            code.addView(line)
        }
        box.addView(code, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(4), dp(16), 0)
        })
        box.addView(
            TextView(this).apply {
                setText(R.string.automation_hint)
                setTextAppearance(R.style.PK_Text_Body)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setLineSpacing(0f, 1.2f)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(20), dp(12), dp(20), 0)
            },
        )
    }

    // ================================================================ row builders

    private class Row(val root: View) {
        val icon: ImageView = root.findViewById(R.id.icon)
        val title: TextView = root.findViewById(R.id.title)
        val summary: TextView = root.findViewById(R.id.summary)
        val value: TextView = root.findViewById(R.id.value)
        val toggle: MaterialSwitch = root.findViewById(R.id.toggle)
        val chevron: ImageView = root.findViewById(R.id.chevron)
    }

    private fun newRow(box: LinearLayout, @DrawableRes icon: Int, @StringRes title: Int): Row {
        if (box.childCount > 0) {
            box.addView(View(this).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.pk_outline))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { marginStart = dp(72) })
        }
        val v = LayoutInflater.from(this).inflate(R.layout.row_setting, box, false)
        box.addView(v)
        return Row(v).apply {
            this.icon.setImageResource(icon)
            this.title.setText(title)
        }
    }

    private fun switchRow(
        box: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        key: String,
        default: Boolean,
        @StringRes on: Int,
        @StringRes off: Int = on,
    ) {
        val row = newRow(box, icon, title)
        row.toggle.isVisible = true
        row.summary.isVisible = true
        fun render() {
            val v = settings.getBoolean(key, default)
            row.toggle.isChecked = v
            row.summary.setText(if (v) on else off)
            ViewCompat.setStateDescription(row.root, row.summary.text)
        }
        row.root.setOnClickListener {
            settings.putBoolean(key, !settings.getBoolean(key, default))
            render()
            refresh() // exit paths and warnings on the main card
        }
        ViewCompat.setAccessibilityDelegate(row.root, object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.isCheckable = true
                info.isChecked = settings.getBoolean(key, default)
                info.className = android.widget.Switch::class.java.name
            }
        })
        render()
    }

    private fun choiceRow(
        box: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        key: String,
        entriesRes: Int,
        valuesRes: Int,
    ) {
        val row = newRow(box, icon, title)
        val entries = resources.getStringArray(entriesRes)
        val values = resources.getStringArray(valuesRes)
        row.summary.isVisible = true
        row.chevron.isVisible = true
        fun render() {
            val i = values.indexOf(settings.getString(key, "auto")).coerceAtLeast(0)
            row.summary.text = entries[i]
        }
        dynamicRows += ::render
        row.root.setOnClickListener {
            val current = values.indexOf(settings.getString(key, "auto")).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(entries, current) { d, which ->
                    settings.putString(key, values[which])
                    d.dismiss()
                    refresh()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        render()
    }

    private fun actionRow(
        box: LinearLayout,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes summary: Int,
        onClick: () -> Unit,
    ) {
        val row = newRow(box, icon, title)
        row.summary.isVisible = true
        row.summary.setText(summary)
        row.chevron.isVisible = true
        row.root.setOnClickListener { onClick() }
    }

    private fun timerRow(box: LinearLayout) {
        val row = newRow(box, R.drawable.ic_timer, R.string.pref_exit_timer)
        row.summary.isVisible = true
        row.summary.setText(R.string.pref_exit_timer_sum)
        row.root.background = null
        row.root.isClickable = false

        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            isSingleLine = false
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(0)
            setPadding(dp(72), 0, dp(16), dp(12))
        }
        val current = settings.timerMinutes
        for (m in TIMER_CHOICES) {
            val chip = (layoutInflater.inflate(R.layout.chip_choice, group, false) as Chip).apply {
                id = View.generateViewId()
                text = when {
                    m == 0 -> getString(R.string.timer_off)
                    m >= 60 -> getString(R.string.timer_hour, m / 60)
                    else -> getString(R.string.timer_min, m)
                }
                isChecked = m == current
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        settings.putString(AppSettings.K_TIMER, m.toString())
                        refresh()
                    }
                }
            }
            group.addView(chip)
        }
        box.addView(group, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    // ================================================================ helpers

    private fun copy(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umbra", text))
        // On Android 13+ the system shows its own copied-to-clipboard confirmation.
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_SHIZUKU = 42
        const val STATE_ADVANCED = "advanced_open"
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        val TIMER_CHOICES = intArrayOf(0, 5, 10, 15, 30, 60, 120)
    }
}
