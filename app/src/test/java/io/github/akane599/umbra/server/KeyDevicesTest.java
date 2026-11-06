package io.github.akane599.umbra.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Which /dev/input devices the helper reads for the volume combo and the power key. */
public class KeyDevicesTest {

    private static final int KEY_VOLUMEDOWN = 114;
    private static final int KEY_VOLUMEUP = 115;
    private static final int KEY_POWER = 116;

    /** Trimmed from a 64-bit phone: gpio-keys (volume), qpnp_pon (power), a touchscreen and a sensor hub. */
    private static final String DEVICES_64 = ""
            + "I: Bus=0019 Vendor=0001 Product=0001 Version=0100\n"
            + "N: Name=\"gpio-keys\"\n"
            + "P: Phys=gpio-keys/input0\n"
            + "S: Sysfs=/devices/platform/soc/soc:gpio_keys/input/input2\n"
            + "U: Uniq=\n"
            + "H: Handlers=event2 \n"
            + "B: PROP=0\n"
            + "B: EV=3\n"
            + "B: KEY=c000000000000 0\n"
            + "\n"
            + "I: Bus=0000 Vendor=0000 Product=0000 Version=0000\n"
            + "N: Name=\"qpnp_pon\"\n"
            + "P: Phys=qpnp_pon/input0\n"
            + "S: Sysfs=/devices/platform/soc/qpnp-power-on/input/input0\n"
            + "U: Uniq=\n"
            + "H: Handlers=event0 \n"
            + "B: PROP=0\n"
            + "B: EV=3\n"
            + "B: KEY=14000000000000 0\n"
            + "\n"
            + "I: Bus=0018 Vendor=0000 Product=0000 Version=0000\n"
            + "N: Name=\"fts_ts\"\n"
            + "H: Handlers=event3 \n"
            + "B: PROP=2\n"
            + "B: EV=b\n"
            + "B: KEY=400 0 0 0 0 0\n"
            + "B: ABS=6e18000 0\n"
            + "\n"
            + "I: Bus=0000 Vendor=0000 Product=0000 Version=0000\n"
            + "N: Name=\"sensorhub\"\n"
            + "H: Handlers=event5 \n"
            + "B: PROP=0\n"
            + "B: EV=9\n"
            + "B: ABS=100 3\n";

    @Test
    public void picksVolumeAndPowerDevicesOn64BitKernel() {
        assertEquals(Arrays.asList("/dev/input/event2", "/dev/input/event0"),
                PanelServer.parseKeyDevices(DEVICES_64));
    }

    @Test
    public void picksPowerDeviceOn32BitKernel() {
        String text = "N: Name=\"pmic_pwrkey\"\n"
                + "H: Handlers=kbd event1\n"
                + "B: EV=3\n"
                + "B: KEY=100000 0 0 0\n"
                + "\n"
                + "N: Name=\"touch\"\n"
                + "H: Handlers=event4\n"
                + "B: EV=b\n"
                + "B: KEY=400 0 0 0 0 0 0 0 0 0 0\n";
        assertEquals(Collections.singletonList("/dev/input/event1"), PanelServer.parseKeyDevices(text));
    }

    @Test
    public void takesTheEventHandlerAmongSeveral() {
        String text = "N: Name=\"keyboard\"\n"
                + "H: Handlers=sysrq kbd leds event7\n"
                + "B: KEY=10000000000000 0\n";
        assertEquals(Collections.singletonList("/dev/input/event7"), PanelServer.parseKeyDevices(text));
    }

    @Test
    public void skipsBlocksWithoutKeysOrHandler() {
        String text = "N: Name=\"no-keys\"\n"
                + "H: Handlers=event1\n"
                + "B: EV=9\n"
                + "\n"
                + "N: Name=\"no-handler\"\n"
                + "H: Handlers=kbd\n"
                + "B: KEY=10000000000000 0\n";
        assertTrue(PanelServer.parseKeyDevices(text).isEmpty());
        assertTrue(PanelServer.parseKeyDevices("").isEmpty());
    }

    @Test
    public void hasBitReads64BitWords() {
        assertTrue(PanelServer.hasBit("c000000000000 0", KEY_VOLUMEDOWN));
        assertTrue(PanelServer.hasBit("c000000000000 0", KEY_VOLUMEUP));
        assertFalse(PanelServer.hasBit("c000000000000 0", KEY_POWER));
        assertTrue(PanelServer.hasBit("10000000000000 0", KEY_POWER));
    }

    @Test
    public void hasBitReads32BitWords() {
        assertTrue(PanelServer.hasBit("c0000 0 0 0", KEY_VOLUMEDOWN));
        assertTrue(PanelServer.hasBit("c0000 0 0 0", KEY_VOLUMEUP));
        assertTrue(PanelServer.hasBit("100000 0 0 0", KEY_POWER));
        assertFalse(PanelServer.hasBit("400 0 0 0 0 0 0 0 0 0 0", KEY_POWER));
    }

    @Test
    public void hasBitToleratesShortOrBrokenBitmaps() {
        assertFalse(PanelServer.hasBit("0", KEY_POWER));
        assertFalse(PanelServer.hasBit("", KEY_POWER));
        assertFalse(PanelServer.hasBit("zz 0", KEY_POWER));
        // Low bits only: a bitmap too short to contain key 116 in either word size.
        assertFalse(PanelServer.hasBit("ffffffff", KEY_POWER));
    }
}
