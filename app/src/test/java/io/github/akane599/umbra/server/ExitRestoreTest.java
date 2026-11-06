package io.github.akane599.umbra.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.os.Binder;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * Invariant I1: when the helper exits (client died, stdin closed), a panel it turned off is turned back on, even if
 * the exit races an "off" that SurfaceFlinger is still executing. SurfaceFlinger is faked through the
 * powerModeSetter seam. The test leaves the server in its exiting state; no other test in this JVM touches power.
 */
public class ExitRestoreTest {

    private static final int OFF = 0;
    private static final int NORMAL = 2;

    @Test
    public void exitDuringOffTurnsThePanelBackOnAndRefusesLaterOffs() throws Exception {
        AtomicInteger panel = new AtomicInteger(NORMAL);
        CountDownLatch offApplied = new CountDownLatch(1);
        CountDownLatch finishOff = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        PanelServer.cachedTokens = Collections.singletonList(new Binder());
        PanelServer.powerModeSetter = (token, mode) -> {
            panel.set(mode);
            if (mode == OFF && offApplied.getCount() > 0) {
                // The panel is dark inside SurfaceFlinger, but setDisplayPowerMode hasn't returned yet.
                offApplied.countDown();
                finishOff.await(5, TimeUnit.SECONDS);
            }
        };
        PanelServer.exitProcess = exited::countDown;

        Thread loop = new Thread(() -> {
            try {
                PanelServer.setPower(false, "surface");
            } catch (Exception ignored) {
            }
        }, "command-loop");
        loop.start();
        assertTrue(offApplied.await(5, TimeUnit.SECONDS));

        // The client dies right now: the watchdog takes the exit path.
        Thread watchdog = new Thread(PanelServer::restoreAndExit, "client-watchdog");
        watchdog.start();
        watchdog.join(300);
        finishOff.countDown();
        loop.join(5000);
        assertTrue("exit path never finished", exited.await(5, TimeUnit.SECONDS));
        watchdog.join(5000);
        assertEquals("panel left off after exit", NORMAL, panel.get());

        // Nothing may turn the panel off once the process is exiting.
        try {
            PanelServer.setPower(false, "surface");
            fail("off accepted while exiting");
        } catch (IllegalStateException expected) {
        }
        assertEquals(NORMAL, panel.get());
    }
}
