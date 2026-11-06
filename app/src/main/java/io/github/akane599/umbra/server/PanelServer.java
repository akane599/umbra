package io.github.akane599.umbra.server;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Privileged helper process. Launched by the app via {@code app_process} through Shizuku
 * (shell/root) or su; reads commands from stdin and writes replies/events to stdout, one
 * per line.
 *
 * <pre>
 * args     : [client-pid]  (if given, the panel is restored and the server exits when the client dies)
 * commands : ping | off [auto|surface|dm] | on | release | hold SEC | keys | collapse | wake
 *            | system off|on | grant PACKAGE | diag | exit
 * replies  : "ok COMMAND detail" | "err COMMAND message"
 * events   : "evt combo" (vol+ and vol- together) | "evt power" (power key)
 * </pre>
 *
 * The panel is turned off by sending "power mode OFF" directly to SurfaceFlinger (scrcpy's
 * --turn-screen-off approach). The system still thinks the screen is on, so apps keep running
 * and drawing. If stdin closes or the client process dies, the panel is turned back on.
 * (Shizuku does not close its end of stdin when the client dies, hence the pid watchdog.)
 *
 * Note: this class runs inside app_process as shell/root, not in the app process, so the
 * app's targetSdk-dependent hidden API restrictions do not apply.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi",
        "DefaultUncaughtExceptionDelegation"})
public final class PanelServer {

    private static final int POWER_MODE_OFF = 0;
    private static final int POWER_MODE_NORMAL = 2;

    private static final int EV_KEY = 1;
    private static final int KEY_VOLUMEDOWN = 114;
    private static final int KEY_VOLUMEUP = 115;
    private static final int KEY_POWER = 116;

    private static final Object OUT_LOCK = new Object();
    private static final Object POWER_LOCK = new Object();
    private static final Object PM_LOCK = new Object();

    private static volatile boolean panelOff;
    private static volatile String offMethod = "";

    private static List<IBinder> cachedTokens;
    private static Method tokenMethod;
    /** Panels we actually turned off this session; "on" and the holder only touch these. */
    private static volatile List<IBinder> offTokens;
    private static Class<?> displayControlClass;
    private static Throwable displayControlError;

    private static Method setPowerModeMethod;
    private static Object powerManager;
    private static Method interactiveMethod;
    private static boolean interactiveNeedsDisplayId;

    private static Thread holder;
    /** In {@link SystemClock#elapsedRealtime()} time; unaffected by wall-clock jumps. */
    private static volatile long holdSuspendedUntil;

    private static boolean keysStarted;
    private static int keyDeviceCount;
    private static boolean volUp, volDown, comboFired;

    // StatusBarManager.DISABLE_* / DISABLE2_*: what "system off" turns off while touch lock is on.
    private static final int DISABLE_EXPAND = 0x00010000;
    private static final int DISABLE_NOTIFICATION_ALERTS = 0x00040000;
    private static final int DISABLE_HOME = 0x00200000;
    private static final int DISABLE_BACK = 0x00400000;
    private static final int DISABLE_RECENT = 0x01000000;
    private static final int DISABLE_SEARCH = 0x02000000;
    private static final int DISABLE2_QUICK_SETTINGS = 1;
    private static final int DISABLE2_NOTIFICATION_SHADE = 1 << 2;
    private static final int LOCK_DISABLE = DISABLE_EXPAND | DISABLE_NOTIFICATION_ALERTS | DISABLE_HOME
            | DISABLE_BACK | DISABLE_RECENT | DISABLE_SEARCH;
    private static final int LOCK_DISABLE2 = DISABLE2_QUICK_SETTINGS | DISABLE2_NOTIFICATION_SHADE;

    /**
     * Identifies our disable request to StatusBarManagerService, which links to its death: if this
     * process dies for any reason, the system drops the flags by itself.
     */
    private static final IBinder SYSTEM_TOKEN = new Binder();
    private static Object statusBar;
    private static volatile boolean systemOff;

    private PanelServer() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> emit("log uncaught " + t.getName() + " " + describe(e)));
        String watch = startClientWatchdog(args);
        emit("ready sdk=" + Build.VERSION.SDK_INT + " uid=" + android.os.Process.myUid() + " watch=" + watch);
        try {
            loop();
        } catch (Throwable t) {
            emit("log loop " + describe(t));
        } finally {
            restoreIfNeeded();
            System.exit(0);
        }
    }

    // ------------------------------------------------------------------ command loop

    private static void loop() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+", 2);
            String cmd = parts[0];
            String arg = parts.length > 1 ? parts[1].trim() : "";
            try {
                switch (cmd) {
                    case "ping":
                        emit("ok ping pong");
                        break;
                    case "off":
                        emit("ok off " + setPower(false, arg));
                        break;
                    case "on":
                        emit("ok on " + turnOn());
                        break;
                    case "release":
                        // System went to sleep: DisplayPowerController owns the panel now, leave it alone.
                        synchronized (POWER_LOCK) {
                            panelOff = false;
                            offMethod = "";
                            offTokens = null;
                        }
                        emit("ok release");
                        break;
                    case "hold":
                        emit("ok hold " + startHolder(arg));
                        break;
                    case "keys":
                        emit("ok keys " + startKeyMonitor());
                        break;
                    case "collapse":
                        emit("ok collapse " + run("/system/bin/cmd", "statusbar", "collapse"));
                        break;
                    case "wake":
                        emit("ok wake " + run("/system/bin/input", "keyevent", "224"));
                        break;
                    case "system":
                        if ("off".equals(arg)) emit("ok system " + systemUi(false));
                        else if ("on".equals(arg)) emit("ok system " + systemUi(true));
                        else emit("err system expected off|on");
                        break;
                    case "grant":
                        emit("ok grant " + grant(arg));
                        break;
                    case "diag":
                        emit("ok diag " + diag());
                        break;
                    case "exit":
                        return;
                    default:
                        emit("err " + cmd + " unknown command");
                }
            } catch (Throwable t) {
                emit("err " + cmd + " " + describe(t));
            }
        }
    }

    private static final PrintStream OUT = utf8Stdout();

    private static PrintStream utf8Stdout() {
        try {
            return new PrintStream(new FileOutputStream(FileDescriptor.out), false, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return System.out;
        }
    }

    static void emit(String s) {
        synchronized (OUT_LOCK) {
            OUT.print(s.replace('\n', ' '));
            OUT.print('\n');
            OUT.flush();
        }
    }

    private static String describe(Throwable t) {
        while (t instanceof InvocationTargetException && t.getCause() != null) t = t.getCause();
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        if (c != null && c != t) sb.append(" <- ").append(c.getClass().getSimpleName()).append(c.getMessage() != null ? ": " + c.getMessage() : "");
        return sb.toString();
    }

    // ------------------------------------------------------------------ client watchdog

    /**
     * Restores the panel and exits if the client (app) process dies. If /proc/PID is not visible
     * (hidepid), the watchdog is disabled; relying on stdin EOF beats turning the panel on by mistake.
     * Comparing cmdline guards against pid reuse.
     */
    private static String startClientWatchdog(String[] args) {
        if (args == null || args.length == 0) return "none";
        final int pid;
        try {
            pid = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException e) {
            return "bad-arg";
        }
        final String cmdline = readCmdline(pid);
        if (cmdline == null || cmdline.isEmpty()) return "unavailable";
        Thread t = new Thread(() -> {
            while (true) {
                SystemClock.sleep(1000);
                if (!cmdline.equals(readCmdline(pid))) {
                    // Restore the panel first; writing to stdout (broken pipe) may kill the process.
                    restoreIfNeeded();
                    System.exit(0);
                }
            }
        }, "client-watchdog");
        t.setDaemon(true);
        t.start();
        return Integer.toString(pid);
    }

    private static String readCmdline(int pid) {
        try {
            String s = readFully("/proc/" + pid + "/cmdline");
            int nul = s.indexOf('\0');
            return nul >= 0 ? s.substring(0, nul) : s;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ panel power

    private static String setPower(boolean on, String pref) throws Exception {
        synchronized (POWER_LOCK) {
            return setPowerLocked(on, pref);
        }
    }

    private static String setPowerLocked(boolean on, String pref) throws Exception {
        if (pref == null || pref.isEmpty()) pref = "auto";
        int mode = on ? POWER_MODE_NORMAL : POWER_MODE_OFF;
        Exception surfaceError = null;

        if (!"dm".equals(pref)) {
            try {
                // Turning off: only the default display's panel (leaves a foldable's outer screen alone).
                // Turning on: only the panels we turned off; never light a panel the system keeps off.
                List<IBinder> tokens = on && offTokens != null ? offTokens : targetTokens();
                int n = surfacePower(tokens, mode);
                panelOff = !on;
                offMethod = on ? "" : "surface";
                offTokens = on ? null : tokens;
                return "surface displays=" + n;
            } catch (Exception e) {
                surfaceError = e;
                if ("surface".equals(pref)) throw e;
            }
        }

        if (Build.VERSION.SDK_INT >= 35) {
            boolean ok;
            try {
                ok = displayManagerPower(on);
            } catch (Exception e) {
                throw new IllegalStateException("dm: " + describe(e)
                        + (surfaceError != null ? " | surface: " + describe(surfaceError) : ""));
            }
            if (ok) {
                panelOff = !on;
                offMethod = on ? "" : "dm";
                offTokens = null;
                return "dm" + (surfaceError != null ? " (surface: " + describe(surfaceError) + ")" : "");
            }
            throw new IllegalStateException("requestDisplayPower returned false"
                    + (surfaceError != null ? " | surface: " + describe(surfaceError) : ""));
        }
        throw surfaceError != null ? surfaceError : new IllegalStateException("no usable method");
    }

    private static String turnOn() throws Exception {
        Boolean interactive = isInteractive();
        synchronized (POWER_LOCK) {
            if (Boolean.FALSE.equals(interactive)) {
                // Device is asleep: turning the panel on now would light the screen while the system thinks it is off.
                // DisplayPowerController restores the panel to normal on wake anyway.
                panelOff = false;
                offMethod = "";
                offTokens = null;
                return "skipped-asleep";
            }
            String method = "dm".equals(offMethod) ? "dm" : "auto";
            return setPowerLocked(true, method);
        }
    }

    /**
     * Some ROMs turn the panel back on by themselves (notification, rotation, brightness event, etc.).
     * The holder periodically reapplies the off power mode for as long as the session lasts. It backs
     * off if the device has gone to sleep or the power key was just pressed.
     */
    private static synchronized String startHolder(String arg) {
        int sec;
        try {
            sec = Math.max(1, Math.min(60, Integer.parseInt(arg.trim())));
        } catch (NumberFormatException e) {
            sec = 3;
        }
        if (holder != null) return "already";
        final long interval = sec * 1000L;
        holder = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    return;
                }
                if (!panelOff || !"surface".equals(offMethod)) continue;
                if (SystemClock.elapsedRealtime() < holdSuspendedUntil) continue;
                Boolean interactive = isInteractive();
                synchronized (POWER_LOCK) {
                    if (!panelOff || !"surface".equals(offMethod)) continue;
                    if (Boolean.FALSE.equals(interactive)) {
                        panelOff = false;
                        offMethod = "";
                        offTokens = null;
                        continue;
                    }
                    List<IBinder> tokens = offTokens;
                    if (tokens == null) continue;
                    try {
                        surfacePower(tokens, POWER_MODE_OFF);
                    } catch (Exception e) {
                        emit("log hold " + describe(e));
                    }
                }
            }
        }, "holder");
        holder.setDaemon(true);
        holder.start();
        return sec + "s";
    }

    private static void restoreIfNeeded() {
        // app died / exiting: give the system UI back (the system would also do it once we die)
        if (systemOff) {
            try {
                systemUi(true);
            } catch (Throwable t) {
                emit("log restore-system " + describe(t));
            }
        }
        // then turn the panel back on if we turned it off
        if (!panelOff) return;
        try {
            turnOn();
        } catch (Throwable t) {
            emit("log restore " + describe(t));
        }
    }

    private static int surfacePower(List<IBinder> tokens, int mode) throws Exception {
        Method setMode = setPowerModeMethod;
        if (setMode == null) {
            setMode = Class.forName("android.view.SurfaceControl")
                    .getMethod("setDisplayPowerMode", IBinder.class, int.class);
            setPowerModeMethod = setMode;
        }
        for (IBinder token : tokens) {
            setMode.invoke(null, token, mode);
        }
        return tokens.size();
    }

    /**
     * Panels to turn off: the physical panel logical display 0 is currently bound to, so that on
     * foldables we don't toggle the other panel the system keeps off. Falls back to all panels.
     */
    private static List<IBinder> targetTokens() throws Exception {
        List<IBinder> all = physicalTokens();
        if (all.size() <= 1) return all;
        Long id = defaultPhysicalId();
        if (id != null && tokenMethod != null) {
            try {
                Object t = tokenMethod.invoke(null, id);
                if (t instanceof IBinder) return Collections.singletonList((IBinder) t);
            } catch (Exception e) {
                emit("log default-token " + describe(e));
            }
        }
        return all;
    }

    /** DisplayManagerGlobal.getDisplayInfo(0).address → DisplayAddress.Physical#getPhysicalDisplayId. */
    private static Long defaultPhysicalId() {
        try {
            Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
            Object dmg = dmgClass.getDeclaredMethod("getInstance").invoke(null);
            Object info = dmg.getClass().getMethod("getDisplayInfo", int.class).invoke(dmg, 0);
            if (info == null) return null;
            Object addr = info.getClass().getField("address").get(info);
            if (addr == null) return null;
            return (Long) addr.getClass().getMethod("getPhysicalDisplayId").invoke(addr);
        } catch (Throwable t) {
            return null;
        }
    }

    private static synchronized List<IBinder> physicalTokens() throws Exception {
        if (cachedTokens != null && !cachedTokens.isEmpty()) return cachedTokens;
        List<IBinder> list = new ArrayList<>();
        Class<?> sc = Class.forName("android.view.SurfaceControl");

        // Android 10-13: on SurfaceControl
        try {
            Method ids = sc.getMethod("getPhysicalDisplayIds");
            Method tok = sc.getMethod("getPhysicalDisplayToken", long.class);
            long[] arr = (long[]) ids.invoke(null);
            if (arr != null) for (long id : arr) addToken(list, tok.invoke(null, id));
            tokenMethod = tok;
        } catch (NoSuchMethodException ignored) {
            // Android 14+: moved to DisplayControl
        }

        if (list.isEmpty() && Build.VERSION.SDK_INT >= 34) {
            Class<?> dc = displayControl();
            if (dc != null) {
                Method ids = dc.getMethod("getPhysicalDisplayIds");
                Method tok = dc.getMethod("getPhysicalDisplayToken", long.class);
                long[] arr = (long[]) ids.invoke(null);
                if (arr != null) for (long id : arr) addToken(list, tok.invoke(null, id));
                tokenMethod = tok;
            }
        }

        if (list.isEmpty()) {
            try {
                addToken(list, sc.getMethod("getInternalDisplayToken").invoke(null));
            } catch (NoSuchMethodException ignored) {
            }
        }

        if (list.isEmpty()) {
            throw new IllegalStateException("no physical display token found"
                    + (displayControlError != null ? " (DisplayControl: " + describe(displayControlError) + ")" : ""));
        }
        cachedTokens = list;
        return list;
    }

    private static void addToken(List<IBinder> list, Object o) {
        if (o instanceof IBinder) list.add((IBinder) o);
    }

    private static synchronized Class<?> displayControl() {
        if (displayControlClass != null || displayControlError != null) return displayControlClass;
        try {
            String cp = System.getenv("SYSTEMSERVERCLASSPATH");
            if (cp == null || cp.isEmpty()) cp = "/system/framework/services.jar";
            Class<?> factory = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method create = factory.getDeclaredMethod("createClassLoader", String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader loader = (ClassLoader) create.invoke(null, cp, null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);
            Class<?> dc = loader.loadClass("com.android.server.display.DisplayControl");
            Method loadLibrary = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadLibrary.setAccessible(true);
            loadLibrary.invoke(Runtime.getRuntime(), dc, "android_servers");
            displayControlClass = dc;
        } catch (Throwable t) {
            displayControlError = t;
        }
        return displayControlClass;
    }

    /** Official (hidden) API on Android 15+. Freezes screen content on some devices, so it's only a fallback. */
    private static boolean displayManagerPower(boolean on) throws Exception {
        Class<?> dmgClass = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object dmg = dmgClass.getDeclaredMethod("getInstance").invoke(null);
        try {
            Method m = dmg.getClass().getMethod("requestDisplayPower", int.class, boolean.class);
            return (Boolean) m.invoke(dmg, 0, on);
        } catch (NoSuchMethodException e) {
            // Possible newer signature: requestDisplayPower(displayId, state) — Display.STATE_ON=2, STATE_OFF=1
            Method m = dmg.getClass().getMethod("requestDisplayPower", int.class, int.class);
            Object r = m.invoke(dmg, 0, on ? 2 : 1);
            return !(r instanceof Boolean) || (Boolean) r;
        }
    }

    /** Called by the holder every few seconds; reflection lookups are done once and cached. */
    private static Boolean isInteractive() {
        synchronized (PM_LOCK) {
            return isInteractiveLocked();
        }
    }

    private static Boolean isInteractiveLocked() {
        try {
            if (interactiveMethod == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "power");
                Object pm = Class.forName("android.os.IPowerManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, b);
                try {
                    interactiveMethod = pm.getClass().getMethod("isInteractive");
                    interactiveNeedsDisplayId = false;
                } catch (NoSuchMethodException e) {
                    interactiveMethod = pm.getClass().getMethod("isDisplayInteractive", int.class);
                    interactiveNeedsDisplayId = true;
                }
                powerManager = pm;
            }
            return interactiveNeedsDisplayId
                    ? (Boolean) interactiveMethod.invoke(powerManager, 0)
                    : (Boolean) interactiveMethod.invoke(powerManager);
        } catch (Throwable t) {
            interactiveMethod = null;
            powerManager = null;
            return null;
        }
    }

    // ------------------------------------------------------------------ key monitor

    private static synchronized String startKeyMonitor() {
        if (keysStarted) return "devices=" + keyDeviceCount;
        keysStarted = true;
        List<String> paths = findKeyDevices();
        StringBuilder failed = new StringBuilder();
        for (String path : paths) {
            try {
                final FileInputStream in = new FileInputStream(path);
                Thread t = new Thread(() -> readEvents(in), "keys:" + path);
                t.setDaemon(true);
                t.start();
                keyDeviceCount++;
            } catch (IOException | SecurityException e) {
                failed.append(' ').append(new File(path).getName());
            }
        }
        if (keyDeviceCount == 0) throw new IllegalStateException("could not open input devices:" + failed);
        return "devices=" + keyDeviceCount + (failed.length() > 0 ? " failed=" + failed.toString().trim() : "");
    }

    /** Picks the event devices in /proc/bus/input/devices that report volume or power keys. */
    static List<String> findKeyDevices() {
        List<String> result = new ArrayList<>();
        try {
            result.addAll(parseKeyDevices(readFully("/proc/bus/input/devices")));
        } catch (Throwable ignored) {
        }
        if (result.isEmpty()) {
            File[] all = new File("/dev/input").listFiles();
            if (all != null) for (File f : all) if (f.getName().startsWith("event")) result.add(f.getAbsolutePath());
        }
        return result;
    }

    static List<String> parseKeyDevices(String text) {
        List<String> result = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            String handler = null;
            String keys = null;
            for (String l : block.split("\\n")) {
                l = l.trim();
                if (l.startsWith("H: Handlers=")) {
                    for (String h : l.substring("H: Handlers=".length()).trim().split("\\s+")) {
                        if (h.startsWith("event")) handler = h;
                    }
                } else if (l.startsWith("B: KEY=")) {
                    keys = l.substring("B: KEY=".length()).trim();
                }
            }
            if (handler != null && keys != null
                    && (hasBit(keys, KEY_VOLUMEUP) || hasBit(keys, KEY_VOLUMEDOWN) || hasBit(keys, KEY_POWER))) {
                result.add("/dev/input/" + handler);
            }
        }
        return result;
    }

    private static String readFully(String path) throws IOException {
        // /proc files report a size of 0; read until EOF.
        try (FileInputStream in = new FileInputStream(path)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Kernel bitmap: most significant word first; word size may be 64 or 32 bits, so both are tried. */
    static boolean hasBit(String bitmap, int bit) {
        String[] words = bitmap.trim().split("\\s+");
        for (int wordBits : new int[]{64, 32}) {
            int idx = bit / wordBits;
            int pos = bit % wordBits;
            int fromRight = words.length - 1 - idx;
            if (fromRight < 0) continue;
            try {
                long w = Long.parseUnsignedLong(words[fromRight], 16);
                if (((w >>> pos) & 1L) != 0) return true;
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    private static void readEvents(InputStream in) {
        int size = android.os.Process.is64Bit() ? 24 : 16;
        byte[] buf = new byte[size * 64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        int filled = 0;
        try {
            while (true) {
                int n = in.read(buf, filled, buf.length - filled);
                if (n < 0) break;
                filled += n;
                int off = 0;
                while (filled - off >= size) {
                    int type = bb.getShort(off + size - 8) & 0xffff;
                    int code = bb.getShort(off + size - 6) & 0xffff;
                    int value = bb.getInt(off + size - 4);
                    onInputEvent(type, code, value);
                    off += size;
                }
                if (off > 0) {
                    System.arraycopy(buf, off, buf, 0, filled - off);
                    filled -= off;
                }
            }
        } catch (IOException e) {
            emit("log keys " + describe(e));
        }
    }

    static synchronized void onInputEvent(int type, int code, int value) {
        if (type != EV_KEY) return;
        switch (code) {
            case KEY_VOLUMEUP:
                volUp = value != 0;
                break;
            case KEY_VOLUMEDOWN:
                volDown = value != 0;
                break;
            case KEY_POWER:
                if (value == 1) {
                    holdSuspendedUntil = SystemClock.elapsedRealtime() + 4000;
                    emit("evt power");
                }
                return;
            default:
                return;
        }
        if (volUp && volDown) {
            if (!comboFired) {
                comboFired = true;
                emit("evt combo");
            }
        } else if (!volUp && !volDown) {
            comboFired = false;
        }
    }

    // ------------------------------------------------------------------ system UI (touch lock)

    /**
     * Turns off the notification shade, quick settings, heads-up alerts and the home / back /
     * recents navigation (buttons and gestures), the same flags kiosk and setup screens use. Touch
     * keeps working, so the lock's own hold-to-unlock still does; the power key is never affected.
     * Needs STATUS_BAR, which shell (Shizuku) and root both hold.
     */
    private static synchronized String systemUi(boolean enable) throws Exception {
        Object sb = statusBar();
        int what = enable ? 0 : LOCK_DISABLE;
        int what2 = enable ? 0 : LOCK_DISABLE2;
        sb.getClass().getMethod("disable", int.class, IBinder.class, String.class)
                .invoke(sb, what, SYSTEM_TOKEN, CALLER_TAG);
        // Flags may already be set even if disable2 fails below: make sure exit paths restore them.
        if (!enable) systemOff = true;
        String extra = "";
        try {
            sb.getClass().getMethod("disable2", int.class, IBinder.class, String.class)
                    .invoke(sb, what2, SYSTEM_TOKEN, CALLER_TAG);
        } catch (NoSuchMethodException e) {
            extra = " (no disable2)";
        }
        systemOff = !enable;
        return (enable ? "restored" : "blocked") + extra;
    }

    private static synchronized Object statusBar() throws Exception {
        if (statusBar == null) {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "statusbar");
            if (b == null) throw new IllegalStateException("statusbar service not found");
            statusBar = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, b);
        }
        return statusBar;
    }

    private static final String CALLER_TAG = "io.github.akane599.umbra";

    // ------------------------------------------------------------------ helper commands

    private static String grant(String pkg) throws Exception {
        if (!pkg.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("invalid package");
        int ok = 0;
        int total = 0;
        if (Build.VERSION.SDK_INT >= 33) {
            total++;
            if (run("/system/bin/pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS") == 0) ok++;
        }
        total++;
        if (run("/system/bin/appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow") == 0) ok++;
        total++;
        if (run("/system/bin/cmd", "deviceidle", "whitelist", "+" + pkg) == 0) ok++;
        return ok + "/" + total;
    }

    private static final long RUN_TIMEOUT_MS = 8000;

    private static int run(String... argv) throws Exception {
        return run(RUN_TIMEOUT_MS, argv);
    }

    /** Runs a tool and returns its exit code, or -1 if it didn't finish within {@code timeoutMs}. */
    static int run(long timeoutMs, String... argv) throws Exception {
        // The output isn't used. Sending it to /dev/null rather than a pipe we'd have to drain means neither the tool
        // nor a child it leaves behind can hold us up, so the timeout really bounds the call.
        Process p = new ProcessBuilder(argv).redirectErrorStream(true)
                .redirectOutput(new File("/dev/null"))
                .start();
        p.getOutputStream().close();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        return p.exitValue();
    }

    private static String diag() {
        StringBuilder sb = new StringBuilder();
        sb.append("sdk=").append(Build.VERSION.SDK_INT)
                .append(" uid=").append(android.os.Process.myUid())
                .append(" 64bit=").append(android.os.Process.is64Bit())
                .append(" interactive=").append(isInteractive());
        try {
            Class<?> sc = Class.forName("android.view.SurfaceControl");
            boolean scIds;
            try {
                sc.getMethod("getPhysicalDisplayIds");
                scIds = true;
            } catch (NoSuchMethodException e) {
                scIds = false;
            }
            sb.append(" sc_ids=").append(scIds);
        } catch (Throwable t) {
            sb.append(" sc=").append(describe(t));
        }
        try {
            int n = physicalTokens().size();
            sb.append(" tokens=").append(n);
            sb.append(" default_phys=").append(defaultPhysicalId());
            sb.append(" targets=").append(targetTokens().size());
        } catch (Throwable t) {
            sb.append(" tokens_err=").append(describe(t));
        }
        if (Build.VERSION.SDK_INT >= 34) {
            sb.append(" displaycontrol=").append(displayControl() != null ? "ok" : describe(displayControlError));
        }
        boolean dm = false;
        try {
            Class<?> c = Class.forName("android.hardware.display.DisplayManagerGlobal");
            for (Method m : c.getMethods()) if (m.getName().equals("requestDisplayPower")) dm = true;
        } catch (Throwable ignored) {
        }
        sb.append(" dm_api=").append(dm);
        List<String> devs = findKeyDevices();
        sb.append(" keydevs=");
        for (int i = 0; i < devs.size(); i++) {
            if (i > 0) sb.append(',');
            String name = new File(devs.get(i)).getName();
            sb.append(name).append(new File(devs.get(i)).canRead() ? "" : "(no-read)");
        }
        try {
            Object bar = statusBar();
            bar.getClass().getMethod("disable", int.class, IBinder.class, String.class);
            sb.append(" statusbar_api=ok");
        } catch (Throwable t) {
            sb.append(" statusbar_api=").append(describe(t));
        }
        sb.append(" panel_off=").append(panelOff);
        sb.append(" system_off=").append(systemOff);
        return sb.toString();
    }
}
