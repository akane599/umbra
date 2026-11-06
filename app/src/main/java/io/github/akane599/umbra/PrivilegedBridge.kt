package io.github.akane599.umbra

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.WorkerThread
import io.github.akane599.umbra.server.PanelServer
import rikka.shizuku.Shizuku
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The part of [PrivilegedBridge] that [oneShot] uses; lets tests check its cleanup without a real helper. */
interface Helper {
    fun start(pref: String): PrivilegedBridge.Backend
    fun request(command: String, timeoutMs: Long = 6000): String
    fun stop()
}

/**
 * Starts a temporary helper, sends [command] and returns the reply. The helper is stopped in every case: one left
 * running after a failed command would keep a shell/root process alive, since its reader threads hold it.
 */
@WorkerThread
fun Helper.oneShot(pref: String, command: String): String = try {
    start(pref)
    request(command)
} finally {
    stop()
}

/**
 * Launches the privileged [PanelServer] process via Shizuku or root (su) and talks
 * to it over a line-based protocol. All blocking calls must be made from a
 * background thread.
 */
class PrivilegedBridge(
    private val context: Context,
    private val onEvent: (String) -> Unit = {},
    private val onDeath: () -> Unit = {},
) : Helper {

    enum class Backend(val label: String) { SHIZUKU("Shizuku"), ROOT("Root") }

    @Volatile var backend: Backend? = null
        private set
    @Volatile var readyInfo: String = ""
        private set
    @Volatile var alive = false
        private set

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val responses = LinkedBlockingQueue<String>()
    @Volatile private var stopping = false
    @Volatile private var generation = 0
    @Volatile private var lastStderr: String? = null

    /** [pref]: "auto" | "shizuku" | "root". On failure, throws an exception listing the reasons. */
    @WorkerThread
    override fun start(pref: String): Backend {
        val order = when (pref) {
            "shizuku" -> listOf(Backend.SHIZUKU)
            "root" -> listOf(Backend.ROOT)
            else -> listOf(Backend.SHIZUKU, Backend.ROOT)
        }
        val errors = mutableListOf<String>()
        for (b in order) {
            try {
                launch(b)
                backend = b
                return b
            } catch (t: Throwable) {
                Log.w(TAG, "${b.label} failed to start", t)
                errors += "${b.label}: ${t.message ?: t.javaClass.simpleName}"
                destroyQuietly()
            }
        }
        throw IllegalStateException(errors.joinToString("\n"))
    }

    private fun launch(b: Backend) {
        val apk = context.applicationInfo.sourceDir
        // Deliberately NOT using "exec": when the client dies, Shizuku SIGTERMs the process it started.
        // With a shell in between, the shell is what dies; the server survives, notices the client's
        // death via the pid watchdog and restores the panel. The trailing "exit" keeps the shell from exec'ing its last command.
        val cmd = "CLASSPATH='$apk' /system/bin/app_process /system/bin " +
            "--nice-name=umbra_server ${PanelServer::class.java.name} ${android.os.Process.myPid()}; exit"
        val p = when (b) {
            Backend.SHIZUKU -> {
                if (!waitForShizukuBinder(2000)) throw IllegalStateException(ERR_SHIZUKU_NOT_RUNNING)
                if (Shizuku.isPreV11()) throw IllegalStateException("Shizuku is too old (v11+ required)")
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw IllegalStateException(ERR_SHIZUKU_NO_PERMISSION)
                }
                newShizukuProcess(arrayOf("sh", "-c", cmd))
            }
            // With su implementations that elevate in place (e.g. KernelSU), the process stays in the app's cgroup;
            // when the system kills the app (killProcessGroup) it would kill the server too and leave the panel
            // off. Moving to the root cgroup prevents that; if it fails, we silently carry on.
            Backend.ROOT -> launchSu("echo \$\$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null; $cmd")
        }
        attach(p)
    }

    private fun attach(p: Process) {
        val gen = ++generation
        val ready = CountDownLatch(1)
        process = p
        writer = p.outputStream.bufferedWriter()
        responses.clear()
        lastStderr = null
        stopping = false

        thread(name = "bridge-out", isDaemon = true) {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    when {
                        line.startsWith("ready") -> {
                            readyInfo = line.removePrefix("ready").trim()
                            alive = true
                            ready.countDown()
                        }
                        line.startsWith("ok ") || line.startsWith("err ") -> responses.offer(line)
                        line.startsWith("evt ") -> onEvent(line.removePrefix("evt ").trim())
                        else -> Log.d(TAG, "server: $line")
                    }
                }
            } catch (_: IOException) {
            }
            if (gen == generation) {
                val wasAlive = alive
                alive = false
                responses.offer("err * server process exited")
                // If it dies before becoming ready, fail start() immediately instead of waiting.
                ready.countDown()
                if (wasAlive && !stopping) onDeath()
            }
        }
        thread(name = "bridge-err", isDaemon = true) {
            try {
                p.errorStream.bufferedReader().forEachLine {
                    Log.w(TAG, "server stderr: $it")
                    if (it.isNotBlank()) lastStderr = it.trim()
                }
            } catch (_: IOException) {
            }
        }

        if (!ready.await(READY_TIMEOUT_S, TimeUnit.SECONDS)) {
            throw IllegalStateException("server not ready within ${READY_TIMEOUT_S} s")
        }
        if (!alive) {
            // Process exited without sending "ready": su denied, Shizuku error, app_process problem…
            SystemClock.sleep(50) // let the last stderr line arrive
            val why = lastStderr?.take(160)
            throw IllegalStateException(
                ERR_SERVER_NOT_STARTED + (why?.let { ": $it" } ?: " (permission denied or no su)"),
            )
        }
    }

    /**
     * Sends the command and returns the "ok" detail; throws IOException on "err".
     * Replies are matched by command name, so a late reply to an earlier command that
     * timed out is not mistaken for this one's.
     */
    @WorkerThread
    @Synchronized
    override fun request(command: String, timeoutMs: Long): String {
        val name = command.substringBefore(' ')
        if (!alive) throw IOException("server not running")
        responses.clear()
        try {
            writer!!.apply {
                write(command)
                write("\n")
                flush()
            }
        } catch (e: Exception) {
            throw IOException("cannot write to server: ${e.message}", e)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val left = deadline - System.nanoTime()
            val r = (if (left > 0) responses.poll(left, TimeUnit.NANOSECONDS) else null)
                ?: throw IOException("$name: timed out")
            val parts = r.split(' ', limit = 3)
            val kind = parts[0]
            val of = parts.getOrElse(1) { "" }
            val detail = parts.getOrElse(2) { "" }.trim()
            if (of != name && of != "*") {
                Log.d(TAG, "skipped stale reply: $r")
                continue
            }
            if (kind == "err") throw IOException(detail.ifEmpty { "$name failed" })
            return detail
        }
    }

    /** Tells the server to exit; if the panel is still off and the device is awake, the server turns it back on. */
    override fun stop() {
        stopping = true
        val p = process ?: return
        process = null
        try {
            writer?.apply {
                write("exit\n")
                flush()
                close()
            }
        } catch (_: Exception) {
        }
        writer = null
        thread(name = "bridge-reaper", isDaemon = true) {
            // The server exits on its own once stdin closes; if it hangs, force-kill it after 3 s.
            // (ShizukuRemoteProcess doesn't reliably support waitFor(timeout).)
            Thread.sleep(3000)
            runCatching { p.destroy() }
        }
    }

    private fun destroyQuietly() {
        stopping = true
        // Close stdin first: even if the shell dies, app_process sees EOF and exits.
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        process = null
        writer = null
        alive = false
    }

    companion object {
        private const val TAG = "PanelBridge"
        private const val READY_TIMEOUT_S = 8L

        // Matched by ActivationError; keep them in sync.
        const val ERR_SHIZUKU_NOT_RUNNING = "Shizuku is not running"
        const val ERR_SHIZUKU_NO_PERMISSION = "Shizuku permission not granted"
        const val ERR_SERVER_NOT_STARTED = "server did not start"
        const val ERR_NO_SU = "su not found"

        /**
         * Runs [script] through su. A missing su (no root, or KernelSU hiding it from apps it hasn't granted) fails
         * here, before any helper output exists, so it gets its own marker. [su] is only overridden by tests.
         */
        internal fun launchSu(script: String, su: String = "su"): Process = try {
            ProcessBuilder(su, "-c", script).start()
        } catch (e: IOException) {
            throw IllegalStateException("$ERR_NO_SU (${e.message})", e)
        }

        enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY, TOO_OLD }

        fun shizukuState(): ShizukuState = try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
                Shizuku.isPreV11() -> ShizukuState.TOO_OLD
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
                else -> ShizukuState.READY
            }
        } catch (_: Throwable) {
            ShizukuState.NOT_RUNNING
        }

        fun shizukuUid(): Int = try {
            Shizuku.getUid()
        } catch (_: Throwable) {
            -1
        }

        /** On cold start, ShizukuProvider delivers the binder a few ms late; wait for it. */
        @WorkerThread
        fun waitForShizukuBinder(timeoutMs: Long): Boolean {
            if (Shizuku.pingBinder()) return true
            val latch = CountDownLatch(1)
            val listener = Shizuku.OnBinderReceivedListener { latch.countDown() }
            Shizuku.addBinderReceivedListenerSticky(listener)
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } finally {
                Shizuku.removeBinderReceivedListener(listener)
            }
            return Shizuku.pingBinder()
        }

        /**
         * Shizuku.newProcess was hidden in API 13 but still exists; used so the root path's
         * stdin/stdout protocol can be shared without needing UserService/AIDL.
         */
        private fun newShizukuProcess(cmd: Array<String>): Process {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            m.isAccessible = true
            return m.invoke(null, cmd, null, null) as Process
        }
    }
}
