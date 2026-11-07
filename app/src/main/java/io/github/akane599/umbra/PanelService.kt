package io.github.akane599.umbra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that runs one session at a time, in one of three modes:
 *  - [Mode.PANEL]: starts the privileged server, turns the panel off, listens for exit triggers
 *    (volume keys, power key, double tap, shake, timer, notification, QS tile, Tasker) and safely
 *    turns the panel back on.
 *  - [Mode.LOCK]: touch lock. The display stays on and never times out; an overlay swallows
 *    touches, optionally dimmed. With Shizuku/root the volume combo unlocks too, and "full lock"
 *    also turns off the shade, heads-up alerts and home/back/recents (the system drops those flags
 *    by itself if the helper dies, so a crash can't leave the phone stuck).
 *  - [Mode.AWAKE]: keeps the screen on with no timeout until stopped or the power key is pressed.
 */
class PanelService : Service() {

    enum class State { IDLE, STARTING, ACTIVE, STOPPING }
    enum class Mode { PANEL, LOCK, AWAKE }

    private lateinit var settings: AppSettings
    private lateinit var power: PowerManager
    private val main = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("panel-worker")
    private lateinit var worker: Handler

    /** Accessed from both the main and worker threads. */
    @Volatile private var bridge: PrivilegedBridge? = null
    @Volatile private var destroyed = false
    private var sessionStart = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var screenTimeout: ScreenTimeout
    private var shield: TouchShield? = null
    private var lock: LockOverlay? = null
    /** Set on the worker thread once the server has turned off the shade and navigation. */
    @Volatile private var systemBlocked = false
    /** The newest start request that arrived mid-session or mid-transition; run once the current session ends. */
    private var pending: Pair<Mode, String>? = null
    private var shake: ShakeDetector? = null
    private var screenReceiverRegistered = false
    @Volatile private var keysOk = false
    /** Bumped whenever a session starts or ends, so a slow activation can tell it was cancelled. */
    @Volatile private var generation = 0
    private var powerPressedAt = 0L
    private var methodInfo = ""
    private var exitSummary = ""
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED
    /** startId of the newest command delivered; see [finishService]. */
    private var lastStartId = 0

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    private val timerRunnable = Runnable { restoreExit("timer") }
    private var showLock: Runnable? = null

    /** Set while a restore exit is putting the phone to sleep so it comes back locked; holds the exit's reason. */
    private var lockingExit: String? = null

    /** The sleep request didn't put the system to sleep: restore the panel the normal way after all. */
    private val lockCheck = Runnable {
        val reason = lockingExit ?: return@Runnable
        if (state == State.ACTIVE && power.isInteractive) {
            lockingExit = null
            deactivate(restorePanel = true, reason = reason)
        }
    }

    /** Power key was pressed but the system didn't go to sleep: the panel was most likely turned on already. */
    private val powerCheck = Runnable {
        if (state == State.ACTIVE && power.isInteractive) deactivate(restorePanel = true, reason = "power")
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        power = getSystemService(PowerManager::class.java)
        workerThread.start()
        worker = Handler(workerThread.looper)
        lastOrientation = resources.configuration.orientation
        screenTimeout = ScreenTimeout.of(this)
        // A new instance has no session: a timeout left raised by one that died goes back now.
        screenTimeout.release()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        val action = intent?.action
        // So the first notification already speaks for the mode being started.
        if (state == State.IDLE) modeFor(action)?.let { mode = it }
        // After startForegroundService we must go foreground within 5 s, whatever happens.
        goForeground()
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "?"
        when (action) {
            ACTION_OFF -> start(Mode.PANEL, source)
            ACTION_LOCK -> start(Mode.LOCK, source)
            ACTION_AWAKE -> start(Mode.AWAKE, source)
            ACTION_ON, ACTION_STOP -> when (state) {
                State.ACTIVE, State.STARTING -> {
                    pending = null
                    deactivate(restorePanel = true, reason = source)
                }
                State.IDLE -> if (action == ACTION_ON) forceOn() else finishService()
                // Mid-switch: an explicit stop cancels the mode that was about to start.
                State.STOPPING -> pending = null
            }
            ACTION_TOGGLE -> when {
                state == State.IDLE -> start(Mode.PANEL, source)
                mode == Mode.PANEL && state != State.STOPPING -> {
                    pending = null
                    deactivate(restorePanel = true, reason = source)
                }
                else -> start(Mode.PANEL, source)
            }
            else -> if (state == State.IDLE) finishService()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- activation

    private fun start(m: Mode, source: String) {
        when (startAction(state, mode, m)) {
            StartAction.ACTIVATE -> activate(m, source)
            StartAction.SWITCH -> {
                // Switch in place: end the current session, then start the requested one.
                pending = m to source
                deactivate(restorePanel = true, reason = "switch")
            }
            StartAction.QUEUE -> pending = m to source
            StartAction.CANCEL_PENDING -> pending = null
            StartAction.IGNORE -> Unit
        }
    }

    private fun activate(m: Mode, source: String) {
        if (!power.isInteractive) {
            finishService()
            return
        }
        mode = m
        generation++
        updateNotification() // after a switch the old mode's notification would linger otherwise
        when (m) {
            Mode.PANEL -> activatePanel(source)
            Mode.LOCK -> activateLock(source)
            Mode.AWAKE -> activateAwake()
        }
    }

    private fun activatePanel(source: String) {
        setState(State.STARTING)
        val privilege = settings.privilegeMode
        val method = settings.powerMethod
        val collapse = settings.collapseShade
        val hold = settings.holdOff
        // Key monitor: needed for the volume combo, power key detection, and pausing the holder on power key presses.
        val wantKeys = settings.exitVolume || settings.exitPower || hold
        val needsSetup = !settings.setupDone ||
            (settings.exitDoubleTap && !Settings.canDrawOverlays(this)) ||
            (settings.keepAwake && !ScreenTimeout.canHold(this))
        val preDelay = when (source) {
            "tile" -> 350L          // while the notification shade collapses
            "app", "shortcut" -> 450L  // while the activity moves to the background
            else -> 0L
        }

        worker.post {
            try {
                val br = bridge?.takeIf { it.alive } ?: newBridge().also {
                    it.start(privilege)
                    bridge = it
                }
                if (needsSetup) {
                    runCatching { br.request("grant $packageName") }
                        .onSuccess { settings.setupDone = true }
                }
                keysOk = wantKeys && runCatching { br.request("keys") }.isSuccess
                if (collapse) runCatching { br.request("collapse") }
                if (preDelay > 0) SystemClock.sleep(preDelay)
                val info = br.request("off $method")
                if (hold) runCatching { br.request("hold $HOLD_INTERVAL_S") }
                if (destroyed) {
                    // Service was destroyed while we were turning the panel off: nobody else will restore it, so do it now.
                    runCatching { br.request("on", 2000) }
                    br.stop()
                    bridge = null
                    return@post
                }
                onMain {
                    if (state == State.STARTING) onActivated(br.backend, info)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "activation failed", t)
                runCatching { bridge?.request("on", 2000) }
                bridge?.stop()
                bridge = null
                onMain { onActivationFailed(t) }
            }
        }
    }

    private fun activateAwake() {
        setState(State.ACTIVE)
        holdScreenOn(timeoutMs = null)
        registerScreenReceiver()
        updateNotification()
        // Stay awake doesn't need the helper, but on Samsung the wake lock alone doesn't last: let the helper
        // allow "Modify system settings" once, if there is one.
        val privilege = settings.privilegeMode
        if (!ScreenTimeout.canHold(this) && helperMayStart(privilege)) worker.post {
            // No callbacks: this helper's exit must not look like a session's server dying.
            runCatching { PrivilegedBridge(this).oneShot(privilege, "grant $packageName") }
                .onSuccess { onGranted() }
                .onFailure { Log.i(TAG, "stay awake without helper: ${it.message}") }
        }
    }

    /** Whether starting the helper can work at all (Shizuku-only with Shizuku not ready can't). */
    private fun helperMayStart(privilege: String) =
        !(privilege == "shizuku" && PrivilegedBridge.shizukuState() != PrivilegedBridge.Companion.ShizukuState.READY)

    /** Called on the worker thread after the helper's grant ran: a session already holding the screen can raise the timeout now. */
    private fun onGranted() {
        settings.setupDone = true
        onMain { if (wakeLock?.isHeld == true) screenTimeout.hold() }
    }

    private fun activateLock(source: String) {
        setState(State.STARTING)
        keysOk = false
        systemBlocked = false
        val privilege = settings.privilegeMode
        val full = settings.lockFull
        val overlayOk = Settings.canDrawOverlays(this)
        // The helper is optional for touch lock (it adds the volume combo and full lock), so skip it
        // when it can't possibly start rather than waiting on a doomed attempt.
        val tryHelper = helperMayStart(privilege)
        val canHold = ScreenTimeout.canHold(this)
        val delay = when (source) {
            "tile" -> 300L          // while the notification shade collapses
            "app", "shortcut" -> 450L  // while the activity moves to the background
            else -> 0L
        }
        if (overlayOk) {
            showLock = Runnable { onLockReady() }.also { main.postDelayed(it, delay) }
        }
        if (!tryHelper && !overlayOk) {
            onLockReady()
            return
        }
        if (!tryHelper) return

        val gen = generation
        val live = { !destroyed && generation == gen }
        worker.post {
            val br = runCatching { newBridge().also { it.start(privilege) } }
                .onFailure { Log.i(TAG, "touch lock without helper: ${it.message}") }
                .getOrNull() ?: run {
                if (!overlayOk) onMain { if (state == State.STARTING) onLockReady() }
                if (full) onMain { if (live()) toast(R.string.lock_full_unavailable) }
                return@post
            }
            if (!live()) {
                // Unlocked (or destroyed) while the helper was starting: nothing left to set up.
                br.stop()
                return@post
            }
            bridge = br
            if (!overlayOk || !canHold) {
                runCatching { br.request("grant $packageName") }.onSuccess { onGranted() }
            }
            if (!overlayOk) {
                onMain {
                    if (state == State.STARTING) {
                        showLock = Runnable { onLockReady() }.also { main.postDelayed(it, delay) }
                    }
                }
            }
            keysOk = runCatching { br.request("keys") }.isSuccess
            if (full && live()) {
                runCatching { br.request("collapse") }
                systemBlocked = runCatching { br.request("system off") }
                    .onFailure { Log.w(TAG, "system off failed", it) }.isSuccess
            }
            if (!live()) {
                // Nobody is left to unlock: the server restores the system UI as it exits.
                // (After an unlock, the queued deactivation task does the same with this bridge.)
                if (!destroyed) return@post
                br.stop()
                bridge = null
                return@post
            }
            val blocked = systemBlocked
            onMain {
                if (full && !blocked && live()) toast(R.string.lock_full_unavailable)
                if (state == State.ACTIVE && mode == Mode.LOCK) updateNotification()
            }
        }
    }

    /** Overlay permission is settled: show the lock, or explain why it can't be shown. */
    private fun onLockReady() {
        showLock = null
        if (state != State.STARTING || mode != Mode.LOCK) return
        if (!power.isInteractive) {
            deactivate(restorePanel = false, reason = "slept_while_starting")
            return
        }
        val overlay = LockOverlay(this) { main.post { deactivate(restorePanel = false, reason = "hold") } }
        val dim = settings.lockDim / 100f
        if (!overlay.show(dim, settings.lockBadge)) {
            settings.recordError(ActivationError.OVERLAY, "overlay permission missing")
            toast(ActivationError.OVERLAY.title)
            deactivate(restorePanel = false, reason = "overlay_denied")
            return
        }
        lock = overlay
        settings.clearError()
        setState(State.ACTIVE)
        holdScreenOn(timeoutMs = null)
        registerScreenReceiver()
        updateNotification()
        switchToPending()
    }

    private fun toast(res: Int) = Toast.makeText(applicationContext, res, Toast.LENGTH_LONG).show()

    /**
     * Hops from the worker thread to the main thread. Skips the block if this service instance was
     * destroyed: [state] is shared across instances, so a late callback from an old instance must not
     * clobber the new session's state.
     */
    private fun onMain(block: () -> Unit) {
        main.post { if (!destroyed) block() }
    }

    private fun onActivated(backend: PrivilegedBridge.Backend?, info: String) {
        if (!power.isInteractive) {
            // Device went to sleep during setup: the system owns the panel now; starting the
            // session (wake lock, holder) would blank the screen again on wake.
            deactivate(restorePanel = false, reason = "slept_while_starting")
            return
        }
        settings.clearError()
        setState(State.ACTIVE)
        sessionStart = SystemClock.elapsedRealtime()
        methodInfo = "${backend?.label ?: "?"} · ${info.substringBefore(' ')}"

        if (settings.keepAwake) holdScreenOn(timeoutMs = 12 * 60 * 60 * 1000L)
        registerScreenReceiver()

        val exits = mutableListOf<String>()
        if (settings.exitVolume && keysOk) exits += getString(R.string.exit_short_volume)
        if (settings.exitPower) exits += getString(R.string.exit_short_power)
        if (settings.exitDoubleTap) {
            val s = TouchShield(this) { main.post { restoreExit("double_tap") } }
            if (s.show()) {
                shield = s
                exits += getString(R.string.exit_short_double_tap)
            }
        }
        if (settings.exitShake) {
            val d = ShakeDetector(this) { main.post { restoreExit("shake") } }
            if (d.start()) {
                shake = d
                exits += getString(R.string.exit_short_shake)
            }
        }
        val minutes = settings.timerMinutes
        if (minutes > 0) {
            main.postDelayed(timerRunnable, minutes * 60_000L)
            exits += getString(R.string.exit_short_timer, minutes)
        }
        exits += getString(R.string.exit_short_tile)
        exitSummary = exits.joinToString(" · ")
        updateNotification()
        switchToPending()
    }

    /** Another mode was requested while this session was starting: switch to it now that it's up. */
    private fun switchToPending() {
        val next = pending ?: return
        if (next.first == mode) {
            pending = null
            return
        }
        deactivate(restorePanel = true, reason = "switch")
    }

    private fun onActivationFailed(t: Throwable) {
        // If cancelled during setup (STOPPING), deactivate() handles the cleanup.
        if (state != State.STARTING) return
        val raw = t.message ?: t.javaClass.simpleName
        val kind = ActivationError.classify(raw, settings.privilegeMode)
        // Record first, so the IDLE listener (the main screen, if open) shows the warning card when it refreshes.
        settings.recordError(kind, raw)
        setState(State.IDLE)
        // The toast shows only a short title; details and the fix live on a persistent card in the app.
        val msg = if (kind == ActivationError.GENERIC) getString(kind.title)
        else getString(R.string.error_toast, getString(kind.title))
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        // A mode requested while this one was starting still runs.
        val next = pending
        pending = null
        if (next != null) activate(next.first, next.second) else finishService()
    }

    // ---------------------------------------------------------------- deactivation

    /**
     * Ends the session from an exit that would bring the panel back (double tap, volume keys, shake, timer). On a
     * phone with a secure lock screen and "Lock when the screen comes back" on, a panel session instead puts the
     * system to sleep; [onScreenOff] then wakes it to the lock screen, the same way the power-key exit does. If the
     * phone doesn't go to sleep, the panel is restored as usual.
     */
    private fun restoreExit(reason: String) {
        val secure = getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        if (state != State.ACTIVE || !restoreLocks(mode, settings.lockOnRestore, secure)) {
            deactivate(restorePanel = true, reason = reason)
            return
        }
        if (lockingExit != null) return // already on its way to sleep
        lockingExit = reason
        val br = bridge
        worker.post {
            val slept = br != null && br.alive && runCatching { br.request("sleep") }
                .onFailure { Log.w(TAG, "sleep failed", it) }.isSuccess
            if (!slept) onMain { main.removeCallbacks(lockCheck); lockCheck.run() }
        }
        main.postDelayed(lockCheck, LOCK_SETTLE_MS)
    }

    /**
     * @param restorePanel return the panel to the on power mode (while the system is awake).
     * @param wake wake the system (so a single press brings it back if it slept via the power key).
     */
    private fun deactivate(restorePanel: Boolean, wake: Boolean = false, reason: String) {
        if (state == State.IDLE || state == State.STOPPING) return
        Log.i(TAG, "deactivate $mode: $reason (restore=$restorePanel wake=$wake)")
        // The "last session" line on the main screen is about the panel, so only panel sessions count.
        if (mode == Mode.PANEL && state == State.ACTIVE && sessionStart > 0) {
            settings.recordSession(SystemClock.elapsedRealtime() - sessionStart, reason)
        }
        sessionStart = 0L
        setState(State.STOPPING)
        main.removeCallbacks(timerRunnable)
        main.removeCallbacks(powerCheck)
        main.removeCallbacks(lockCheck)
        lockingExit = null
        showLock?.let { main.removeCallbacks(it) }
        showLock = null
        shield?.hide()
        shield = null
        shake?.stop()
        shake = null
        lock?.dismiss()
        lock = null
        generation++

        val privilege = settings.privilegeMode
        if (mode != Mode.PANEL) {
            // Nothing below needs these; don't hold full brightness while the worker catches up.
            releaseScreenOn()
            unregisterScreenReceiver()
            worker.post {
                val br = bridge
                if (systemBlocked) {
                    // Normally the live server restores it; if that server is gone, so are its flags.
                    if (br != null && br.alive) runCatching { br.request("system on") }
                    systemBlocked = false
                }
                br?.stop()
                bridge = null
                onMain { endSession() }
            }
            return
        }
        worker.post {
            val br = bridge
            // If the device slept, the server must stop managing the panel (holder + restore on exit).
            if (!restorePanel) runCatching { br?.request("release") }
            if (restorePanel) {
                val ok = br != null && br.alive && runCatching { br.request("on") }.isSuccess
                if (!ok) {
                    // The server may have died: retry with a fresh one.
                    runCatching { newBridge().oneShot(privilege, "on") }
                        .onFailure { Log.e(TAG, "failed to restore panel", it) }
                }
            }
            if (wake) runCatching { br?.request("wake") }
            br?.stop()
            bridge = null
            onMain { endSession() }
        }
    }

    private fun endSession() {
        releaseScreenOn()
        unregisterScreenReceiver()
        setState(State.IDLE)
        val next = pending
        pending = null
        if (next != null) activate(next.first, next.second) else finishService()
    }

    /** "on" command with no session running: a recovery path that returns the panel to normal. */
    private fun forceOn() {
        if (!power.isInteractive) {
            finishService()
            return
        }
        setState(State.STOPPING)
        val privilege = settings.privilegeMode
        worker.post {
            runCatching { newBridge().oneShot(privilege, "on") }.onFailure { Log.w(TAG, "forceOn", it) }
            // endSession also starts a mode requested while this ran.
            onMain { endSession() }
        }
    }

    private fun newBridge() = PrivilegedBridge(
        this,
        onEvent = { evt -> onMain { onBridgeEvent(evt) } },
        onDeath = { onMain { onServerDied() } },
    )

    private fun onServerDied() {
        if (state != State.ACTIVE && !(mode == Mode.LOCK && state == State.STARTING)) return
        when (mode) {
            Mode.PANEL -> deactivate(restorePanel = true, reason = "server_died")
            Mode.LOCK -> {
                // The lock itself doesn't need the helper: carry on overlay-only. The system dropped
                // the shade/navigation flags along with the dead process.
                keysOk = false
                bridge = null
                systemBlocked = false
                updateNotification()
            }
            Mode.AWAKE -> Unit
        }
    }

    private fun onBridgeEvent(evt: String) {
        if (state != State.ACTIVE) return
        when (evt) {
            "combo" -> if (mode == Mode.LOCK || settings.exitVolume) restoreExit("volume")
            "power" -> if (mode == Mode.PANEL) {
                powerPressedAt = SystemClock.elapsedRealtime()
                main.removeCallbacks(powerCheck)
                main.postDelayed(powerCheck, POWER_SETTLE_MS)
            }
        }
    }

    private fun onScreenOff() {
        if (state != State.ACTIVE) return
        if (mode != Mode.PANEL) {
            // Power key or a timeout we didn't block: the user is done with the screen.
            deactivate(restorePanel = false, reason = "screen_off")
            return
        }
        main.removeCallbacks(powerCheck)
        main.removeCallbacks(lockCheck)
        val locking = lockingExit
        if (locking != null) {
            // We put it to sleep for a restore exit: wake it straight to the lock screen.
            deactivate(restorePanel = false, wake = true, reason = locking)
            return
        }
        // System went to sleep. DisplayPowerController owns the panel now; sending "on" while
        // asleep would light it, so only wake the device (if it was the power key).
        val recentPower = SystemClock.elapsedRealtime() - powerPressedAt < 3000
        val byPower = recentPower || (!keysOk && wakeLock?.isHeld == true)
        deactivate(restorePanel = false, wake = settings.exitPower && byPower, reason = "screen_off")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation != lastOrientation) {
            lastOrientation = newConfig.orientation
            // Some devices turn the panel back on when rotating; reapply.
            if (state == State.ACTIVE && mode == Mode.PANEL) {
                val method = settings.powerMethod
                worker.post { runCatching { bridge?.request("off $method") } }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Keeps the system believing the screen is in use so it doesn't time out. [timeoutMs] null holds
     * it until the session ends (touch lock, stay awake): those end on the power key or a stop command.
     *
     * Two ways at once: a screen wake lock, and the system screen timeout raised to its maximum (see
     * [ScreenTimeout]; Samsung stops honoring the wake lock when the normal timeout runs out). The timeout
     * needs "Modify system settings"; without it the wake lock carries on alone.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("WakelockTimeout")
    private fun holdScreenOn(timeoutMs: Long?) {
        if (wakeLock?.isHeld == true) return
        wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "Umbra:keepAwake",
        ).apply {
            setReferenceCounted(false)
            if (timeoutMs != null) acquire(timeoutMs) else acquire()
        }
        if (!screenTimeout.hold()) Log.i(TAG, "screen timeout not raised (no \"Modify system settings\"); wake lock only")
        if (timeoutMs != null) main.postDelayed(screenHoldExpiry, timeoutMs)
    }

    /** The wake lock runs out by itself after holdScreenOn's timeoutMs; the raised timeout has to be put back. */
    private val screenHoldExpiry = Runnable { screenTimeout.release() }

    private fun releaseScreenOn() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        main.removeCallbacks(screenHoldExpiry)
        screenTimeout.release()
    }

    private fun registerScreenReceiver() {
        if (screenReceiverRegistered) return
        ContextCompat.registerReceiver(
            this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!screenReceiverRegistered) return
        runCatching { unregisterReceiver(screenReceiver) }
        screenReceiverRegistered = false
    }

    private fun setState(s: State) {
        if (state == s) return
        state = s
        PanelTileService.refresh(this)
        listeners.forEach { it(s) }
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Stops only if no newer command is on its way: one the system has already accepted keeps the service
        // alive, and its onStartCommand goes foreground again and handles it.
        stopSelfResult(lastStartId)
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        // Unexpected shutdown: do our best to turn the panel back on.
        if (state == State.ACTIVE || state == State.STARTING) {
            shield?.hide()
            shake?.stop()
            lock?.hideNow()
            lock = null
            unregisterScreenReceiver()
            // The server restores the system UI when it exits, so stop() covers touch lock.
            val panel = mode == Mode.PANEL
            bridge?.let { br -> Thread { if (panel) runCatching { br.request("on", 2000) }; br.stop() }.start() }
            bridge = null
        }
        // Reset the shared state no matter where we ended up; if it stayed stuck in STOPPING,
        // later commands would be ignored. (The queued "on" task still runs thanks to quitSafely.)
        if (state != State.IDLE) setState(State.IDLE)
        // Whatever state it was in (a panel session that is still stopping holds the screen too).
        releaseScreenOn()
        workerThread.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    description = getString(R.string.channel_desc)
                },
            )
        }
    }

    /** Ways out of a touch lock that will actually work right now. */
    private fun lockExits(): String = buildList {
        add(getString(R.string.lock_exit_hold))
        if (keysOk) add(getString(R.string.exit_short_volume))
        add(getString(R.string.exit_short_power))
    }.joinToString(" · ")

    private fun buildNotification(): Notification {
        val active = state == State.ACTIVE
        val onIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PanelService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_SOURCE, "notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val icon: Int
        val title: String
        val text: String
        val big: String
        val actionLabel: Int
        when (mode) {
            Mode.PANEL -> {
                icon = R.drawable.ic_tile
                title = getString(if (active) R.string.notif_title_active else R.string.notif_title_preparing)
                text = if (active) exitSummary else getString(R.string.notif_text_preparing)
                big = if (active) "$exitSummary\n$methodInfo" else text
                actionLabel = R.string.notif_action_on
            }
            Mode.LOCK -> {
                icon = R.drawable.ic_lock
                title = getString(if (systemBlocked) R.string.lock_notif_title_full else R.string.lock_notif_title)
                text = if (active) lockExits() else getString(R.string.lock_notif_preparing)
                big = text
                actionLabel = R.string.lock_notif_action
            }
            Mode.AWAKE -> {
                icon = R.drawable.ic_awake
                title = getString(R.string.awake_notif_title)
                text = getString(R.string.awake_notif_text)
                big = text
                actionLabel = R.string.awake_notif_action
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .apply { if (active) addAction(0, getString(actionLabel), onIntent) }
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    companion object {
        private const val TAG = "PanelService"
        const val ACTION_OFF = "io.github.akane599.umbra.OFF"
        const val ACTION_ON = "io.github.akane599.umbra.ON"
        const val ACTION_TOGGLE = "io.github.akane599.umbra.TOGGLE"
        const val ACTION_LOCK = "io.github.akane599.umbra.LOCK"
        const val ACTION_AWAKE = "io.github.akane599.umbra.AWAKE"
        /** Ends whatever session is running (ON does the same, and also recovers a stuck panel). */
        const val ACTION_STOP = "io.github.akane599.umbra.STOP"
        val ALL_ACTIONS = setOf(ACTION_OFF, ACTION_ON, ACTION_TOGGLE, ACTION_LOCK, ACTION_AWAKE, ACTION_STOP)
        /** Actions that only end a session; always accepted, even when outside commands are disabled. */
        val SAFE_ACTIONS = setOf(ACTION_ON, ACTION_STOP)
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "panel"
        private const val NOTIF_ID = 1
        private const val POWER_SETTLE_MS = 1500L
        private const val LOCK_SETTLE_MS = 2000L
        private const val HOLD_INTERVAL_S = 3

        @Volatile
        var state: State = State.IDLE
            private set

        /** Called on the main thread. Lets the UI reflect the state live. */
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<(State) -> Unit>()
        fun addListener(l: (State) -> Unit) { listeners += l }
        fun removeListener(l: (State) -> Unit) { listeners -= l }

        /** Mode of the running (or last) session. Only meaningful while [isActive]. */
        @Volatile
        var mode: Mode = Mode.PANEL
            private set

        val isActive: Boolean get() = state == State.ACTIVE || state == State.STARTING

        fun isActive(m: Mode): Boolean = isActive && mode == m

        /** What a start request (OFF, LOCK, AWAKE, or TOGGLE that starts) does; see [start]. */
        enum class StartAction { ACTIVATE, SWITCH, QUEUE, CANCEL_PENDING, IGNORE }

        /** Pure, so the transitions can be unit-tested. [current] is the mode running, starting or stopping. */
        fun startAction(state: State, current: Mode, requested: Mode): StartAction = when (state) {
            State.IDLE -> StartAction.ACTIVATE
            State.ACTIVE -> if (requested != current) StartAction.SWITCH else StartAction.IGNORE
            // Mid-transition, the newest request wins: it runs once the transition is over (or, for the mode
            // already starting, replaces whatever was queued before it).
            State.STARTING -> if (requested != current) StartAction.QUEUE else StartAction.CANCEL_PENDING
            State.STOPPING -> StartAction.QUEUE
        }

        /** Whether a panel session's double tap / volume / shake / timer exit locks the phone instead of restoring. */
        fun restoreLocks(mode: Mode, lockOnRestore: Boolean, deviceSecure: Boolean): Boolean =
            mode == Mode.PANEL && lockOnRestore && deviceSecure

        private fun modeFor(action: String?): Mode? = when (action) {
            ACTION_OFF, ACTION_TOGGLE -> Mode.PANEL
            ACTION_LOCK -> Mode.LOCK
            ACTION_AWAKE -> Mode.AWAKE
            else -> null
        }

        /** Sends a command to the service. Returns false if starting an FGS from the background is blocked. */
        fun command(context: Context, action: String, source: String): Boolean {
            val intent = Intent(context, PanelService::class.java)
                .setAction(action)
                .putExtra(EXTRA_SOURCE, source)
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "failed to start service ($source)", t)
                false
            }
        }
    }
}
