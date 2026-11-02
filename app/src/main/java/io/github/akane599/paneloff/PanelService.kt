package io.github.akane599.paneloff

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
 * Foreground service that runs a panel-off session: starts the privileged server, turns the
 * panel off, listens for exit triggers (volume keys, power key, double tap, shake, timer,
 * notification, QS tile, Tasker) and safely turns the panel back on.
 */
class PanelService : Service() {

    enum class State { IDLE, STARTING, ACTIVE, STOPPING }

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
    private var shield: TouchShield? = null
    private var shake: ShakeDetector? = null
    private var screenReceiverRegistered = false
    private var keysOk = false
    private var powerPressedAt = 0L
    private var methodInfo = ""
    private var exitSummary = ""
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    private val timerRunnable = Runnable { deactivate(restorePanel = true, reason = "timer") }

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
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // After startForegroundService we must go foreground within 5 s, whatever happens.
        goForeground()
        val source = intent?.getStringExtra(EXTRA_SOURCE) ?: "?"
        when (intent?.action) {
            ACTION_OFF -> if (state == State.IDLE) activate(source)
            ACTION_ON -> if (state == State.ACTIVE || state == State.STARTING) {
                deactivate(restorePanel = true, reason = source)
            } else if (state == State.IDLE) {
                forceOn()
            }
            ACTION_TOGGLE -> when (state) {
                State.IDLE -> activate(source)
                State.STARTING, State.ACTIVE -> deactivate(restorePanel = true, reason = source)
                State.STOPPING -> Unit
            }
            else -> if (state == State.IDLE) finishService()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- activation

    private fun activate(source: String) {
        if (!power.isInteractive) {
            finishService()
            return
        }
        setState(State.STARTING)
        val privilege = settings.privilegeMode
        val method = settings.powerMethod
        val collapse = settings.collapseShade
        val hold = settings.holdOff
        // Key monitor: needed for the volume combo, power key detection, and pausing the holder on power key presses.
        val wantKeys = settings.exitVolume || settings.exitPower || hold
        val needsSetup = !settings.setupDone ||
            (settings.exitDoubleTap && !Settings.canDrawOverlays(this))
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

        if (settings.keepAwake) acquireWakeLock()
        registerScreenReceiver()

        val exits = mutableListOf<String>()
        if (settings.exitVolume && keysOk) exits += getString(R.string.exit_short_volume)
        if (settings.exitPower) exits += getString(R.string.exit_short_power)
        if (settings.exitDoubleTap) {
            val s = TouchShield(this) { main.post { deactivate(restorePanel = true, reason = "double_tap") } }
            if (s.show()) {
                shield = s
                exits += getString(R.string.exit_short_double_tap)
            }
        }
        if (settings.exitShake) {
            val d = ShakeDetector(this) { main.post { deactivate(restorePanel = true, reason = "shake") } }
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
        finishService()
    }

    // ---------------------------------------------------------------- deactivation

    /**
     * @param restorePanel return the panel to the on power mode (while the system is awake).
     * @param wake wake the system (so a single press brings it back if it slept via the power key).
     */
    private fun deactivate(restorePanel: Boolean, wake: Boolean = false, reason: String) {
        if (state == State.IDLE || state == State.STOPPING) return
        Log.i(TAG, "deactivate: $reason (restore=$restorePanel wake=$wake)")
        if (state == State.ACTIVE && sessionStart > 0) {
            settings.recordSession(SystemClock.elapsedRealtime() - sessionStart, reason)
        }
        sessionStart = 0L
        setState(State.STOPPING)
        main.removeCallbacks(timerRunnable)
        main.removeCallbacks(powerCheck)
        shield?.hide()
        shield = null
        shake?.stop()
        shake = null

        val privilege = settings.privilegeMode
        worker.post {
            val br = bridge
            // If the device slept, the server must stop managing the panel (holder + restore on exit).
            if (!restorePanel) runCatching { br?.request("release") }
            if (restorePanel) {
                val ok = br != null && br.alive && runCatching { br.request("on") }.isSuccess
                if (!ok) {
                    // The server may have died: retry with a fresh one.
                    runCatching {
                        newBridge().apply {
                            start(privilege)
                            request("on")
                            stop()
                        }
                    }.onFailure { Log.e(TAG, "failed to restore panel", it) }
                }
            }
            if (wake) runCatching { br?.request("wake") }
            br?.stop()
            bridge = null
            onMain {
                releaseWakeLock()
                unregisterScreenReceiver()
                setState(State.IDLE)
                finishService()
            }
        }
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
            runCatching {
                newBridge().apply {
                    start(privilege)
                    request("on")
                    stop()
                }
            }.onFailure { Log.w(TAG, "forceOn", it) }
            onMain {
                setState(State.IDLE)
                finishService()
            }
        }
    }

    private fun newBridge() = PrivilegedBridge(
        this,
        onEvent = { evt -> onMain { onBridgeEvent(evt) } },
        onDeath = { onMain { if (state == State.ACTIVE) deactivate(restorePanel = true, reason = "server_died") } },
    )

    private fun onBridgeEvent(evt: String) {
        if (state != State.ACTIVE) return
        when (evt) {
            "combo" -> if (settings.exitVolume) deactivate(restorePanel = true, reason = "volume")
            "power" -> {
                powerPressedAt = SystemClock.elapsedRealtime()
                main.removeCallbacks(powerCheck)
                main.postDelayed(powerCheck, POWER_SETTLE_MS)
            }
        }
    }

    private fun onScreenOff() {
        if (state != State.ACTIVE) return
        main.removeCallbacks(powerCheck)
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
            if (state == State.ACTIVE) {
                val method = settings.powerMethod
                worker.post { runCatching { bridge?.request("off $method") } }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        // Keep the system believing the screen is on so it doesn't sleep on timeout.
        wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "Umbra:keepAwake",
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        // Unexpected shutdown: do our best to turn the panel back on.
        if (state == State.ACTIVE || state == State.STARTING) {
            shield?.hide()
            shake?.stop()
            releaseWakeLock()
            unregisterScreenReceiver()
            bridge?.let { br -> Thread { runCatching { br.request("on", 2000) }; br.stop() }.start() }
            bridge = null
        }
        // Reset the shared state no matter where we ended up; if it stayed stuck in STOPPING,
        // later commands would be ignored. (The queued "on" task still runs thanks to quitSafely.)
        if (state != State.IDLE) setState(State.IDLE)
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

    private fun buildNotification(): Notification {
        val active = state == State.ACTIVE
        val onIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PanelService::class.java).setAction(ACTION_ON).putExtra(EXTRA_SOURCE, "notification"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(if (active) R.string.notif_title_active else R.string.notif_title_preparing))
            .setContentText(if (active) exitSummary else getString(R.string.notif_text_preparing))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    if (active) "$exitSummary\n$methodInfo" else getString(R.string.notif_text_preparing),
                ),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp)
            .apply { if (active) addAction(0, getString(R.string.notif_action_on), onIntent) }
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
        const val ACTION_OFF = "io.github.akane599.paneloff.OFF"
        const val ACTION_ON = "io.github.akane599.paneloff.ON"
        const val ACTION_TOGGLE = "io.github.akane599.paneloff.TOGGLE"
        const val EXTRA_SOURCE = "source"
        private const val CHANNEL = "panel"
        private const val NOTIF_ID = 1
        private const val POWER_SETTLE_MS = 1500L
        private const val HOLD_INTERVAL_S = 3

        @Volatile
        var state: State = State.IDLE
            private set

        /** Called on the main thread. Lets the UI reflect the state live. */
        private val listeners = java.util.concurrent.CopyOnWriteArraySet<(State) -> Unit>()
        fun addListener(l: (State) -> Unit) { listeners += l }
        fun removeListener(l: (State) -> Unit) { listeners -= l }

        val isActive: Boolean get() = state == State.ACTIVE || state == State.STARTING

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
