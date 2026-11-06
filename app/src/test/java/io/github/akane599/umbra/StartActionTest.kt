package io.github.akane599.umbra

import io.github.akane599.umbra.PanelService.Companion.StartAction
import io.github.akane599.umbra.PanelService.Companion.startAction
import io.github.akane599.umbra.PanelService.Mode
import io.github.akane599.umbra.PanelService.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** CONC-004: every (state × running mode × requested mode) start request; the newest request wins. */
class StartActionTest {

    @Test
    fun idleStartsTheRequestedMode() {
        for (cur in Mode.entries) for (req in Mode.entries) {
            assertEquals(StartAction.ACTIVATE, startAction(State.IDLE, cur, req))
        }
    }

    @Test
    fun activeSwitchesToADifferentModeAndIgnoresTheSameOne() {
        for (cur in Mode.entries) for (req in Mode.entries) {
            val want = if (req == cur) StartAction.IGNORE else StartAction.SWITCH
            assertEquals("$cur -> $req", want, startAction(State.ACTIVE, cur, req))
        }
    }

    @Test
    fun startingQueuesADifferentModeAndTheSameOneCancelsTheQueue() {
        for (cur in Mode.entries) for (req in Mode.entries) {
            val want = if (req == cur) StartAction.CANCEL_PENDING else StartAction.QUEUE
            assertEquals("$cur -> $req", want, startAction(State.STARTING, cur, req))
        }
    }

    @Test
    fun stoppingQueuesEveryRequest() {
        for (cur in Mode.entries) for (req in Mode.entries) {
            assertEquals("$cur -> $req", StartAction.QUEUE, startAction(State.STOPPING, cur, req))
        }
    }
}
