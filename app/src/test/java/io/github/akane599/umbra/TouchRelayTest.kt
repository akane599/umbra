package io.github.akane599.umbra

import android.view.MotionEvent
import io.github.akane599.umbra.TouchRelay.Companion.GRACE_MS
import io.github.akane599.umbra.TouchRelay.Companion.MAX_QUEUED
import io.github.akane599.umbra.TouchRelay.Companion.MAX_SAMPLES
import io.github.akane599.umbra.TouchRelay.Companion.MAX_WAIT_MS
import io.github.akane599.umbra.TouchRelay.Companion.SETTLE_MS
import io.github.akane599.umbra.TouchRelay.Companion.WAIT_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchRelayTest {

    private class Timer(val at: Long, val r: Runnable)

    private class FakePort : TouchRelay.Port {
        var available = true
        val log = mutableListOf<String>()
        private val replies = mutableListOf<(Boolean) -> Unit>()
        private val timers = mutableListOf<Timer>()
        var now = 0L

        override fun canReplay() = available
        override fun openHoles(points: List<TouchRelay.Pointer>) {
            log += "open " + points.joinToString(" ") { "${it.x},${it.y}" }
        }
        override fun closeHoles() { log += "close" }
        override fun replay(gesture: String, onQueued: (Boolean) -> Unit) {
            log += "replay $gesture"
            replies += onQueued
        }
        override fun postDelayed(r: Runnable, delayMs: Long) { timers += Timer(now + delayMs, r) }
        override fun cancel(r: Runnable) { timers.removeAll { it.r === r } }

        /** The helper answers the oldest replay request. */
        fun reply(ok: Boolean = true) = replies.removeAt(0)(ok)
        val waitingReplies get() = replies.size

        fun advance(ms: Long) {
            val end = now + ms
            while (true) {
                val t = timers.filter { it.at <= end }.minByOrNull { it.at } ?: break
                timers.remove(t)
                now = t.at
                t.r.run()
            }
            now = end
        }

        fun replays() = log.filter { it.startsWith("replay") }
    }

    private val port = FakePort()
    private val relay = TouchRelay(port)

    private fun pointer(x: Float, y: Float, pressure: Float = 1f, id: Int = 0) =
        TouchRelay.Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y, x, y, pressure, 1f)

    private fun touch(action: Int, t: Long, vararg pointers: TouchRelay.Pointer) =
        relay.onInjected(TOUCHSCREEN, TouchRelay.Sample(action, t, 0, pointers.toList()))

    private fun tap(x: Float, y: Float, t: Long = 1000) {
        touch(MotionEvent.ACTION_DOWN, t, pointer(x, y))
        touch(MotionEvent.ACTION_UP, t, pointer(x, y, pressure = 0f))
    }

    private fun tapGesture(x: Float, y: Float) = "replay $TOUCHSCREEN 0,0,0,0:1:$x:$y:1.0:1.0 1,0,0,0:1:$x:$y:0.0:1.0"

    @Test
    fun aTapGoesThroughAHoleOnceItEnds() {
        touch(MotionEvent.ACTION_DOWN, 1000, pointer(100f, 200f))
        assertTrue("nothing happens mid-gesture", port.log.isEmpty())
        touch(MotionEvent.ACTION_UP, 1000, pointer(100f, 200f, pressure = 0f))
        assertEquals(listOf("open 100.0,200.0"), port.log)

        port.advance(SETTLE_MS - 1)
        assertTrue("the hole needs time to reach the input system", port.replays().isEmpty())
        port.advance(1)
        assertEquals(listOf(tapGesture(100f, 200f)), port.replays())

        port.reply()
        port.advance(GRACE_MS - 1)
        assertEquals("open while the replay is on its way", "replay", port.log.last().substringBefore(' '))
        port.advance(1)
        assertEquals("close", port.log.last())
    }

    @Test
    fun movesAndTheirTimingAreKept() {
        touch(MotionEvent.ACTION_DOWN, 1000, pointer(10f, 20f))
        touch(MotionEvent.ACTION_MOVE, 1016, pointer(15f, 25f))
        touch(MotionEvent.ACTION_UP, 1300, pointer(15f, 25f, pressure = 0f))
        port.advance(SETTLE_MS)
        assertEquals(
            listOf("replay $TOUCHSCREEN 0,0,0,0:1:10.0:20.0:1.0:1.0 2,16,0,0:1:15.0:25.0:1.0:1.0 1,300,0,0:1:15.0:25.0:0.0:1.0"),
            port.replays(),
        )
        port.reply()
        port.advance(300 + GRACE_MS - 1)
        assertTrue("held for the whole gesture", port.log.last().startsWith("replay"))
        port.advance(1)
        assertEquals("close", port.log.last())
    }

    @Test
    fun everyPointerAndThePointerIndexAreKept() {
        twoFingers()
        port.advance(SETTLE_MS)
        val sample = port.replays().single().split(' ')[3]
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        assertEquals("$secondDown,5,0,0:1:1.0:2.0:1.0:1.0,1:1:3.0:4.0:1.0:1.0", sample)
    }

    @Test
    fun aCancelledGestureIsDropped() {
        touch(MotionEvent.ACTION_DOWN, 0, pointer(1f, 1f))
        touch(MotionEvent.ACTION_CANCEL, 5, pointer(1f, 1f))
        port.advance(1000)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun withoutTheHelperNothingIsOpened() {
        port.available = false
        tap(5f, 5f)
        port.advance(1000)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun gesturesAreReplayedOneAtATime() {
        tap(1f, 1f)
        tap(2f, 2f)
        port.advance(SETTLE_MS)
        port.reply()
        assertEquals(listOf("open 1.0,1.0", tapGesture(1f, 1f)), port.log)
        port.advance(GRACE_MS)
        assertEquals(listOf("close", "open 2.0,2.0"), port.log.takeLast(2))
        port.advance(SETTLE_MS)
        assertEquals(tapGesture(2f, 2f), port.log.last())
    }

    @Test
    fun aReplayTheHelperRefusedClosesTheHoleAtOnce() {
        tap(1f, 1f)
        port.advance(SETTLE_MS)
        port.reply(ok = false)
        port.advance(0)
        assertEquals("close", port.log.last())
    }

    @Test
    fun aReplayWaitsForAnInjectedGestureStillComingIn() {
        // The system takes one injected touch stream at a time: replaying now would cut into this one.
        tap(1f, 1f)
        touch(MotionEvent.ACTION_DOWN, 1050, pointer(9f, 9f))
        port.advance(SETTLE_MS)
        assertEquals("the lock is whole while it waits", "close", port.log.last())
        port.advance(500)
        assertTrue(port.replays().isEmpty())
        touch(MotionEvent.ACTION_UP, 1700, pointer(9f, 9f, pressure = 0f))
        port.advance(WAIT_MS)
        assertEquals("open 1.0,1.0", port.log.last())
        port.advance(SETTLE_MS)
        assertEquals(listOf(tapGesture(1f, 1f)), port.replays())
    }

    @Test
    fun theNextReplayDoesNotOpenAHoleWhileAGestureIsComingIn() {
        tap(1f, 1f)
        tap(2f, 2f)
        port.advance(SETTLE_MS)
        touch(MotionEvent.ACTION_DOWN, 2000, pointer(9f, 9f))
        port.reply()
        port.advance(GRACE_MS)
        assertEquals("close", port.log.last())
        port.advance(10_000)
        assertEquals("close", port.log.last())
        assertEquals(1, port.replays().size)
        touch(MotionEvent.ACTION_UP, 2100, pointer(9f, 9f, pressure = 0f))
        port.advance(WAIT_MS)
        assertEquals("open 2.0,2.0", port.log.last())
    }

    @Test
    fun replaysWaitingOnATouchThatNeverEndsAreDropped() {
        tap(1f, 1f)
        tap(2f, 2f)
        touch(MotionEvent.ACTION_DOWN, 1050, pointer(9f, 9f))
        port.advance(SETTLE_MS + MAX_WAIT_MS + 10_000)
        // Replaying would cut into that touch, wherever it is stuck; the lock stays whole.
        assertTrue(port.replays().isEmpty())
        assertEquals(listOf("open 1.0,1.0", "close"), port.log)
        // Once it does end, it is replayed like any other.
        touch(MotionEvent.ACTION_UP, 1100, pointer(9f, 9f, pressure = 0f))
        port.advance(SETTLE_MS)
        assertEquals(listOf("replay $TOUCHSCREEN 0,0,0,0:1:9.0:9.0:1.0:1.0 1,50,0,0:1:9.0:9.0:0.0:1.0"), port.replays())
    }

    @Test
    fun anOverlongTouchStillHoldsBackReplaysUntilItEnds() {
        tap(1f, 1f)
        touch(MotionEvent.ACTION_DOWN, 1050, pointer(9f, 9f))
        repeat(MAX_SAMPLES) { touch(MotionEvent.ACTION_MOVE, 1051L + it, pointer(9f, 9f)) }
        port.advance(SETTLE_MS + 1000)
        assertTrue("no longer recorded, but still down", port.replays().isEmpty())
        assertEquals("close", port.log.last())
        touch(MotionEvent.ACTION_UP, 9000, pointer(9f, 9f, pressure = 0f))
        port.advance(WAIT_MS + SETTLE_MS)
        assertEquals(listOf(tapGesture(1f, 1f)), port.replays())
    }

    @Test
    fun aHoleOpensWhereverAFingerGoesDown() {
        twoFingers()
        assertEquals("open 1.0,2.0 3.0,4.0", port.log.first())
    }

    @Test
    fun aSecondFingerThatCameBackIsNeitherANewGestureNorRetried() {
        twoFingers()
        port.advance(SETTLE_MS)
        // The lock caught the second finger of the replay: it sees it as a touch of its own. The first finger may
        // have got through, so replaying the gesture again could repeat it.
        touch(MotionEvent.ACTION_DOWN, 5005, pointer(3f, 4f, id = 1))
        touch(MotionEvent.ACTION_UP, 5009, pointer(3f, 4f, id = 1))
        port.reply()
        port.advance(10_000)
        assertEquals(1, port.replays().size)
        assertEquals("close", port.log.last())
    }

    @Test
    fun aBacklogIsCapped() {
        repeat(100) { tap(it.toFloat(), 0f) }
        repeat(200) {
            port.advance(1000)
            while (port.waitingReplies > 0) port.reply()
        }
        assertEquals("the one under way and a full queue", 1 + MAX_QUEUED, port.replays().size)
    }

    @Test
    fun queuedGesturesAreDroppedWhenTheHelperIsGone() {
        tap(1f, 1f)
        tap(2f, 2f)
        port.advance(SETTLE_MS)
        port.available = false
        port.reply(ok = false)
        port.advance(10_000)
        assertEquals(1, port.replays().size)
        assertEquals("close", port.log.last())
    }

    /** One finger at 1,2, a second at 3,4 for a moment. */
    private fun twoFingers() {
        touch(MotionEvent.ACTION_DOWN, 0, pointer(1f, 2f))
        val secondDown = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        touch(secondDown, 5, pointer(1f, 2f), pointer(3f, 4f, id = 1))
        val secondUp = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        touch(secondUp, 9, pointer(1f, 2f), pointer(3f, 4f, id = 1))
        touch(MotionEvent.ACTION_UP, 12, pointer(1f, 2f))
    }

    @Test
    fun aReplayThatCameBackIsTriedOnceMore() {
        tap(50f, 60f)
        port.advance(SETTLE_MS)
        // The hole wasn't in place yet: the lock caught the replay itself.
        tap(50f, 60f, t = 5000)
        port.reply()
        port.advance(GRACE_MS)
        assertEquals(listOf("close", "open 50.0,60.0"), port.log.takeLast(2))
        // A retry, not a new gesture: it gives the hole longer to arrive.
        port.advance(2 * SETTLE_MS - 1)
        assertEquals(1, port.replays().size)
        port.advance(1)
        assertEquals(2, port.replays().size)

        // Caught again: given up rather than looping.
        tap(50f, 60f, t = 6000)
        port.reply()
        port.advance(10_000)
        assertEquals(2, port.replays().size)
        assertEquals("close", port.log.last())
    }

    @Test
    fun aNewGestureDuringAReplayIsNotMistakenForIt() {
        tap(50f, 60f)
        port.advance(SETTLE_MS)
        tap(300f, 60f, t = 5000)
        port.reply()
        port.advance(GRACE_MS + SETTLE_MS)
        assertEquals(listOf(tapGesture(50f, 60f), tapGesture(300f, 60f)), port.replays())
    }

    @Test
    fun anOverlongGestureIsDropped() {
        touch(MotionEvent.ACTION_DOWN, 0, pointer(1f, 1f))
        repeat(MAX_SAMPLES) { touch(MotionEvent.ACTION_MOVE, it + 1L, pointer(1f, 1f)) }
        touch(MotionEvent.ACTION_UP, MAX_SAMPLES + 1L, pointer(1f, 1f))
        port.advance(1000)
        assertTrue(port.log.isEmpty())
    }

    @Test
    fun stopClosesTheHoleAndForgetsEverything() {
        tap(1f, 1f)
        tap(2f, 2f)
        relay.stop()
        assertEquals("close", port.log.last())
        port.advance(10_000)
        assertEquals(listOf("open 1.0,1.0", "close"), port.log)
    }

    @Test
    fun aLateAnswerAfterStopDoesNothing() {
        tap(1f, 1f)
        port.advance(SETTLE_MS)
        relay.stop()
        port.reply()
        port.advance(10_000)
        assertEquals("close", port.log.last())
        assertEquals(1, port.log.count { it == "close" })
    }

    private companion object {
        const val TOUCHSCREEN = 4098 // InputDevice.SOURCE_TOUCHSCREEN
    }
}
