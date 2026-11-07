package io.github.akane599.umbra

import android.view.KeyCharacterMap
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Lets touches that adb, scrcpy or another shell/root tool injects reach the app under the touch lock, while touches
 * on the screen itself stay blocked.
 *
 * The lock is a window over everything, so the system hands it every touch, injected or not, and a window can't pass
 * on a gesture it was given. So the lock records an injected gesture ([isInjected]), and once it ends the privileged
 * helper replays it through small holes opened in the lock where its fingers go down. A finger stays with the window
 * it went down on, so the holes only have to cover those points. The lock window also stays a little see-through for
 * the whole session (see LockOverlay), or Android would drop the replayed touch.
 *
 * The cost: an injected gesture reaches the app [SETTLE_MS] after it ends, and a finger on exactly those few pixels
 * while they are open would get through too. Runs on the main thread.
 */
class TouchRelay(private val port: Port) {

    interface Port {
        /** Whether the helper is there to replay gestures; without it injected touches stay blocked. */
        fun canReplay(): Boolean
        /** Opens a hole at each of [points] ([Pointer.x], [Pointer.y]: lock window coordinates). */
        fun openHoles(points: List<Pointer>)
        fun closeHoles()
        /** Hands [gesture] ([Gesture.encode]) to the helper; [onQueued] runs on the main thread with whether it took it. */
        fun replay(gesture: String, onQueued: (Boolean) -> Unit)
        fun postDelayed(r: Runnable, delayMs: Long)
        fun cancel(r: Runnable)
    }

    /** [x], [y]: lock window coordinates, for the hole. [rawX], [rawY]: display coordinates, for the replay. */
    class Pointer(
        val id: Int,
        val tool: Int,
        val x: Float,
        val y: Float,
        val rawX: Float,
        val rawY: Float,
        val pressure: Float,
        val size: Float,
    )

    /** One touch sample: [action] as MotionEvent reports it (pointer index included), at [timeMs] (uptime). */
    class Sample(val action: Int, val timeMs: Long, val buttons: Int, val pointers: List<Pointer>)

    class Gesture(val source: Int, val samples: List<Sample>) {
        val durationMs: Long get() = samples.last().timeMs - samples.first().timeMs

        /** Where each finger goes down: each lands on whatever window is under it, so each needs a hole. */
        val downs: List<Pointer> = samples.mapNotNull { s ->
            when (s.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> s.pointers.getOrNull(
                    (s.action and MotionEvent.ACTION_POINTER_INDEX_MASK) shr MotionEvent.ACTION_POINTER_INDEX_SHIFT,
                )
                else -> null
            }
        }

        /**
         * The helper's format (PanelServer.Gesture.parse): "source sample sample…", where a sample is
         * "action,ms since the first sample,buttons,pointer[,pointer…]" and a pointer "id:tool:x:y:pressure:size".
         * Float.toString, unlike String.format, never writes a decimal comma.
         */
        fun encode(): String = buildString {
            append(source)
            val t0 = samples.first().timeMs
            for (s in samples) {
                append(' ').append(s.action).append(',').append(s.timeMs - t0).append(',').append(s.buttons)
                for (p in s.pointers) {
                    append(',').append(p.id).append(':').append(p.tool).append(':').append(p.rawX).append(':')
                        .append(p.rawY).append(':').append(p.pressure).append(':').append(p.size)
                }
            }
        }

        /**
         * Whether [s] is this gesture coming back: the lock caught (a finger of) the replay instead of letting it
         * through. A finger caught on its own reaches the lock as a DOWN where it went down.
         */
        fun isReplayOf(s: Sample): Boolean {
            val p = s.pointers.first()
            return downs.any { abs(p.rawX - it.rawX) <= BOUNCE_SLOP_PX && abs(p.rawY - it.rawY) <= BOUNCE_SLOP_PX }
        }
    }

    private class Replay(val gesture: Gesture, val tries: Int) {
        var sent = false
        var bounced = false
        /** Time spent waiting for other injected gestures to end. */
        var waitedMs = 0L
    }

    private var recording: MutableList<Sample>? = null
    /** An injected touch is down on the lock, recorded or not: no replay may start until it ends. */
    private var incoming = false
    private var recordingSource = 0
    /** Dropping the rest of a replayed gesture that came back to the lock. */
    private var skipping = false
    private val queue = ArrayDeque<Replay>()
    /** Being replayed: its holes open, or about to once no injected gesture is coming in. */
    private var current: Replay? = null
    private var holesOpen = false

    private val prepareTask = Runnable { prepareCurrent() }
    private val sendTask = Runnable { sendCurrent() }
    private val finishTask = Runnable { finishCurrent() }

    /** Takes the samples of an injected touch event, oldest first. */
    fun onInjected(source: Int, sample: Sample) {
        when (sample.action and MotionEvent.ACTION_MASK) {
            MotionEvent.ACTION_DOWN -> {
                incoming = true
                val c = current
                if (c != null && c.sent && c.gesture.isReplayOf(sample)) {
                    c.bounced = true
                    skipping = true
                    recording = null
                    return
                }
                skipping = false
                recording = mutableListOf(sample)
                recordingSource = source
            }
            MotionEvent.ACTION_UP -> {
                incoming = false
                if (skipping) {
                    skipping = false
                    return
                }
                val r = recording ?: return
                recording = null
                r += sample
                if (!port.canReplay()) return
                // Automation outrunning the replays: drop rather than replay stale touches on whatever is on screen by then.
                if (queue.size >= MAX_QUEUED) return
                queue.addLast(Replay(Gesture(recordingSource, r), tries = 0))
                next()
            }
            MotionEvent.ACTION_CANCEL -> {
                incoming = false
                skipping = false
                recording = null
            }
            else -> {
                if (skipping) return
                val r = recording ?: return
                if (r.size < MAX_SAMPLES) r += sample else recording = null
            }
        }
    }

    private fun next() {
        if (current != null) return
        if (!port.canReplay()) {
            queue.clear()
            return
        }
        current = queue.removeFirstOrNull() ?: return
        prepareCurrent()
    }

    /** Opens the holes once no injected gesture is coming in, then gives them time to reach the input system. */
    private fun prepareCurrent() {
        val r = current ?: return
        if (incoming) {
            // The system takes one injected touch stream at a time: replaying now would cut into the one coming in.
            // Wait with the lock whole. A touch held down past MAX_WAIT_MS is stuck: drop the replays instead.
            if (r.waitedMs < MAX_WAIT_MS) {
                r.waitedMs += WAIT_MS
                port.postDelayed(prepareTask, WAIT_MS)
            } else {
                current = null
                queue.clear()
            }
            return
        }
        port.openHoles(r.gesture.downs)
        holesOpen = true
        port.postDelayed(sendTask, SETTLE_MS * (r.tries + 1))
    }

    private fun sendCurrent() {
        val r = current ?: return
        if (incoming) {
            // Another injected gesture began while the holes settled.
            closeHoles()
            prepareCurrent()
            return
        }
        r.sent = true
        port.replay(r.gesture.encode()) { ok ->
            if (current === r) port.postDelayed(finishTask, if (ok) r.gesture.durationMs + GRACE_MS else 0)
        }
    }

    private fun finishCurrent() {
        val r = current ?: return
        current = null
        closeHoles()
        // Retried once, with longer for the holes to arrive; never in a loop. Only a one-finger gesture: it came back
        // whole, while of several fingers some may have got through, and replaying those again would repeat them.
        if (r.bounced && r.gesture.downs.size == 1 && r.tries + 1 < MAX_TRIES) {
            queue.addFirst(Replay(r.gesture, r.tries + 1))
        }
        next()
    }

    private fun closeHoles() {
        if (!holesOpen) return
        holesOpen = false
        port.closeHoles()
    }

    /** The lock is going away: drops everything and closes the holes. */
    fun stop() {
        port.cancel(prepareTask)
        port.cancel(sendTask)
        port.cancel(finishTask)
        queue.clear()
        recording = null
        incoming = false
        skipping = false
        current = null
        closeHoles()
    }

    companion object {
        /** From opening the holes to replaying through them: the holes have to reach the input system first. */
        const val SETTLE_MS = 100L
        /** After the helper took a gesture, how long past its duration the holes stay open. */
        const val GRACE_MS = 150L
        const val WAIT_MS = 50L
        /** The longest gesture the helper replays (PanelServer.MAX_GESTURE_MS). */
        const val MAX_WAIT_MS = 60_000L
        const val MAX_TRIES = 2
        /** About half a minute of moves; a longer gesture is dropped. */
        const val MAX_SAMPLES = 4000
        /** Gestures waiting behind the one being replayed; more are dropped. */
        const val MAX_QUEUED = 16
        private const val BOUNCE_SLOP_PX = 2f

        /** Injected events reach windows with device id -1; the screen's own touches never do (InputDispatcher). */
        fun isInjected(e: MotionEvent) = e.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD
    }
}

/** This event's samples, oldest first: a batched move carries the earlier ones in its history. */
fun MotionEvent.toSamples(): List<TouchRelay.Sample> {
    // Where the lock window sits on the display; the same for every pointer and sample of a gesture.
    val dx = rawX - x
    val dy = rawY - y
    val n = pointerCount
    val out = ArrayList<TouchRelay.Sample>(historySize + 1)
    for (h in 0 until historySize) {
        out += TouchRelay.Sample(action, getHistoricalEventTime(h), buttonState, List(n) { i ->
            val x = getHistoricalX(i, h)
            val y = getHistoricalY(i, h)
            TouchRelay.Pointer(
                getPointerId(i), getToolType(i), x, y, x + dx, y + dy,
                getHistoricalPressure(i, h), getHistoricalSize(i, h),
            )
        })
    }
    out += TouchRelay.Sample(action, eventTime, buttonState, List(n) { i ->
        TouchRelay.Pointer(getPointerId(i), getToolType(i), getX(i), getY(i), getX(i) + dx, getY(i) + dy, getPressure(i), getSize(i))
    })
    return out
}
