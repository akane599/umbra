package io.github.akane599.umbra.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** PanelServer.run(): helper tools must never block the command thread past their timeout. */
public class RunTest {

    @Test
    public void returnsTheExitCode() throws Exception {
        assertEquals(0, PanelServer.run(5000, "sh", "-c", "exit 0"));
        assertEquals(3, PanelServer.run(5000, "sh", "-c", "echo noise; echo more >&2; exit 3"));
    }

    @Test
    public void hungToolTimesOut() throws Exception {
        long t0 = System.nanoTime();
        int rc = PanelServer.run(300, "sh", "-c", "sleep 5");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(-1, rc);
        assertTrue("took " + ms + " ms", ms < 2000);
    }

    /** A background child that keeps stdout open must not stall run() after the tool itself exited. */
    @Test
    public void lingeringGrandchildDoesNotBlock() throws Exception {
        long t0 = System.nanoTime();
        int rc = PanelServer.run(3000, "sh", "-c", "sleep 5 & exit 0");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(0, rc);
        assertTrue("took " + ms + " ms", ms < 2000);
    }

    @Test
    public void largeOutputDoesNotBlock() throws Exception {
        assertEquals(0, PanelServer.run(5000, "sh", "-c", "head -c 4000000 /dev/zero"));
    }
}
