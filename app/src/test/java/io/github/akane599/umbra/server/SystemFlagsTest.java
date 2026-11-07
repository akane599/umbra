package io.github.akane599.umbra.server;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

/** PanelServer.systemFlags(): which status bar disable flags each "system" command sets. */
public class SystemFlagsTest {

    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE_NOTIFICATION_ICONS = 0x00020000;
    private static final int DISABLE_SYSTEM_INFO = 0x00100000;
    private static final int DISABLE_HOME = 0x00200000;
    private static final int DISABLE_CLOCK = 0x00800000;
    private static final int DISABLE2_SYSTEM_ICONS = 1 << 1;
    private static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;

    @Test
    public void onClearsEverything() {
        assertArrayEquals(new int[] {0, 0}, PanelServer.systemFlags("on"));
    }

    @Test
    public void offBlocksTheShadeAndNavigation() {
        int[] f = PanelServer.systemFlags("off");
        assertEquals(DISABLE_EXPAND, f[0] & DISABLE_EXPAND);
        assertEquals(DISABLE_HOME, f[0] & DISABLE_HOME);
        assertEquals(DISABLE2_NOTIFICATION_SHADE, f[1] & DISABLE2_NOTIFICATION_SHADE);
        assertEquals("the status bar keeps its icons", 0, f[0] & (DISABLE_CLOCK | DISABLE_SYSTEM_INFO));
    }

    @Test
    public void quietOnlyEmptiesTheStatusBar() {
        int[] f = PanelServer.systemFlags("quiet");
        int icons = DISABLE_CLOCK | DISABLE_SYSTEM_INFO | DISABLE_NOTIFICATION_ICONS;
        assertEquals(icons, f[0] & icons);
        assertEquals(DISABLE2_SYSTEM_ICONS, f[1] & DISABLE2_SYSTEM_ICONS);
        assertEquals("the shade and navigation keep working", 0, f[0] & (DISABLE_EXPAND | DISABLE_HOME));
        assertEquals(0, f[1] & DISABLE2_NOTIFICATION_SHADE);
    }

    @Test
    public void offAndQuietTogetherSetBoth() {
        int[] off = PanelServer.systemFlags("off");
        int[] quiet = PanelServer.systemFlags("quiet");
        assertArrayEquals(new int[] {off[0] | quiet[0], off[1] | quiet[1]}, PanelServer.systemFlags("off quiet"));
        assertArrayEquals(PanelServer.systemFlags("off quiet"), PanelServer.systemFlags("quiet  off"));
        assertNotEquals(0, quiet[0]);
    }

    @Test
    public void rejectsAnythingElse() {
        for (String arg : new String[] {"", "of", "on off", "quiet loud", "OFF"}) {
            try {
                PanelServer.systemFlags(arg);
                fail("accepted \"" + arg + "\"");
            } catch (IllegalArgumentException expected) {
                // ok
            }
        }
    }
}
