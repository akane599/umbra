# Umbra

**English** · [Türkçe](README.tr.md)

Umbra really turns the display **panel** off (power mode OFF) while the system keeps believing the
screen is on: foreground and background apps run at full speed, and audio, video, navigation and
downloads carry on. It isn't brightness −1 or a black overlay. It is the same technique as scrcpy's
`--turn-screen-off`.

Requires **Shizuku** (adb/shell or root) or **root** (KernelSU, Magisk, …). Android 10+.

Two display-on modes live next to it. Neither needs Shizuku or root for the basics:

- **Touch lock**: the display stays on and never times out, but touches do nothing. Optionally dims
  what's underneath and shows a small lock badge. Hold the lock to undo.
- **Stay awake**: no screen timeout, for as long as you want, until you stop it or press power.

## How it works

- The app starts the `PanelServer` class from its own APK inside `app_process`, through **Shizuku**
  or **su**, and talks to it over stdin/stdout with a small line protocol.
- The server calls `SurfaceControl.setDisplayPowerMode(token, OFF)`. On Android 14+ the physical
  display tokens come from `DisplayControl` in `services.jar`. As a fallback, Android 15+
  `DisplayManagerGlobal.requestDisplayPower` is tried (it isn't the default because it freezes the
  screen content on some devices). On foldables only the default display's panel is touched.
- The server also reads the volume and power keys straight from `/dev/input/event*` (the shell user
  is in the `input` group), so the hardware keys work while the panel is off.
- **If the app dies, the panel comes back on.** The server watches the app's pid in `/proc` once a
  second. EOF on stdin isn't enough, because Shizuku keeps its end of the pipe open. Shizuku also
  SIGTERMs the remote process when its owner dies, so the server isn't `exec`'d: it runs under a
  wrapper shell. The shell is the one that dies, and the server restores the panel and exits. With
  KernelSU the helper moves itself out of the app's cgroup so it isn't killed with the app.

## Turning the screen off

- **Quick Settings tile** (one tap to add it from the app)
- The big **power button** in the app (Umbra steps back; the previous app stays in front)
- Long-press the launcher icon → **“Screen off”** shortcut
- **Tasker / automation** (see below)

## Turning it back on

| Way | Notes |
|---|---|
| Vol+ and Vol− together | On by default |
| Power button | One press brings it back (the system is locked and woken immediately). If disabled, the phone locks normally. |
| Double tap | On by default. A transparent touch shield keeps touches from reaching the app underneath (pocket guard). If disabled, touches pass through. |
| Shake | 3 firm shakes within 1.5 s |
| Timer | 5 min – 2 h |
| QS tile / notification / Tasker `ON` | |

**Recovery:** whatever happens, pressing power twice (lock + unlock) always brings the panel back,
because the system turns the panel on itself when it wakes.

The main screen lists the ways back that are actually enabled *before* you tap, warns if none of the
quick ones are on, and keeps a card with a plain explanation and the right fix if the last attempt
failed.

## Touch lock

- An overlay over everything swallows touches; the app underneath keeps running and stays visible.
  The screen timeout is held off for as long as the lock is on.
- If a keyboard is open when the lock starts, the lock closes it. The keyboard is drawn above every
  overlay, so it would stay usable, and with it open the navigation bar brings back its "hide
  keyboard" and keyboard-switcher buttons even while navigation is disabled. Only then does the lock
  take key focus; otherwise the app underneath keeps it.
- **Dim the screen**: off, soft, dim or dark scrim over the content. The status bar is drawn above
  any app, so with Shizuku/root the helper also hides its clock and icons while a dimmed lock is on;
  they come back when it ends. The 3-button navigation bar stays as it is unless "Block system
  gestures too" hides it.
- **Show the lock**: a faint badge near the bottom edge (it drifts a few pixels now and then against
  OLED burn-in). When off, the badge appears only when you touch the screen.
- **Unlock**: hold the lock for about a second (a ring fills, the shackle opens). With Shizuku/root,
  Vol+ and Vol− together also unlock. The power button always ends the lock. With TalkBack, a
  double-tap-and-hold anywhere works, and there's an "Unlock" accessibility action.
- **Block system gestures too** (Shizuku or root): an overlay can't stop swipe-home, back or the
  notification shade, because the system draws those above every app. With this on, the helper also
  sets the status bar disable flags that kiosk and setup screens use: no notification shade, quick
  settings or heads-up pop-ups, and no home, back or recents. Touch itself keeps working, so the hold
  still unlocks. These flags are tied to the helper process: if it dies for any reason, Android drops
  them by itself, so a crash can't leave the phone stuck.
- **Touches from adb, scrcpy and other tools** (Shizuku or root, Android 14+): `adb shell input tap`
  and other injected touches reach the app underneath; touches on the screen stay blocked. The lock
  can't hand on a touch it caught, so it records the injected gesture and, once it ends, the helper
  replays it through holes of a few pixels opened where its fingers go down, so each one arrives
  about 0.1 s after it ends. Android drops a touch that goes through another app's window more than
  80% opaque, so the lock window stays slightly see-through for the whole session and draws brighter
  to make up for it: it looks the same, except the darkest dim, which stops at 78% instead of 88%.
  Nothing on screen changes when a touch is replayed. Injected gestures never show the badge or
  unlock. Give back-to-back commands a moment apart: Android takes one injected touch at a time, so a
  replay waits for an injected touch still coming in, and past 16 waiting gestures new ones are
  dropped.
- Start it from the app, the **Touch lock** QS tile or the launcher shortcut.

## Stay awake

Keeps the screen on until you switch it off (app, **Stay awake** QS tile, notification) or press
power. Nothing else changes: touch, brightness and apps behave as usual.

It holds a screen wake lock and also raises the system screen timeout to its maximum, then puts your
own timeout back when it ends. The timeout matters on Samsung: once the normal timeout runs out, One UI
stops honoring a wake lock from an app with nothing on screen, dims, and turns the screen off. Raising
it needs **Modify system settings**, which is granted automatically with Shizuku/root; without them
the app opens that page the first time. Screen off ("Keep the phone awake") and touch lock hold the
screen the same way. If a session is cut short (crash, force stop, reboot), your timeout is put back
the next time Umbra starts or Quick Settings opens.

## Behavior

- **Keep the phone awake** (default on): no screen timeout during a session (up to 12 h), so the
  phone doesn't lock by itself while the panel is off. The screen timeout setting is raised for the
  session and put back afterwards (see Stay awake). Turn this off to let it sleep and lock on its
  normal timeout (which also ends the session).
- **Lock when the screen comes back** (default on): if the phone has a PIN, pattern or password,
  double tap, the volume keys, shake and the timer put it to sleep and wake it straight to the lock
  screen, like the power button does, instead of returning to the unlocked phone. Whether it locks
  right away follows the system's "Power button instantly locks" setting. Turn this off to get back
  to the phone exactly as you left it; then anyone who picks it up while it's dark can get in.
- **Keep the screen dark** (default on): if the ROM turns the panel back on, it's turned off again
  within 3 s. This is skipped while asleep and for 4 s after a power press.
- **Privilege source** (Advanced): automatic (Shizuku → root), Shizuku only, root only.
- **Power-off method** (Advanced): automatic / SurfaceControl / DisplayManager.

On first activation, notification permission, the overlay permission, **Modify system settings** and
the battery optimization exemption are granted automatically through Shizuku/root (`pm grant`,
`appops set`, `cmd deviceidle whitelist`). The exemption is needed so Tasker broadcasts can start the foreground
service from the background.

## Tasker

Actions: `io.github.akane599.umbra.OFF`, `…ON`, `…TOGGLE`, `…LOCK` (touch lock), `…AWAKE` (stay awake),
`…STOP` (ends whatever is running; `ON` does too, and also recovers a stuck panel)

- **Recommended:** Action → *Send Intent* → Target: **Activity**, Package `io.github.akane599.umbra`,
  Class `io.github.akane599.umbra.ActionActivity`, Action one of the above. It's an invisible
  activity and works in every state.
- Alternative: Target **Broadcast Receiver**, same package and action.

First turn on **Advanced → Commands from other apps** (off by default). These entry points are
exported, so with the switch on any app on the phone can send these actions; with it off only `ON` and
`STOP` (ending a session) are accepted. The launcher shortcut and the QS tile go through a non-exported `ShortcutActivity` alias
and aren't affected by this setting.

Note: while the panel is off the system still considers the screen *on*, so Tasker's Display Off/On
contexts don't fire.

## Diagnostics

**Setup and diagnostics → Diagnostics** (or tap the privilege card) starts the helper and shows the
SDK, uid, whether `DisplayControl` could be loaded, how many physical display tokens were found,
whether `requestDisplayPower` exists, and which input devices can be read. If something doesn't
work, share that output (there's a Copy button).

Detailed logs: `adb logcat -s PanelService PanelBridge TouchShield`.

## Building

```bash
# Android SDK path in local.properties (sdk.dir=...)
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

For a signed release build, put a `keystore.properties` in the project root with `storeFile`,
`storePassword`, `keyAlias` and `keyPassword`; without it the release APK is unsigned. Keys and
passwords are git-ignored.

Requirements: Android 10+ (minSdk 29), Shizuku v11+ or root. Target: Android 16 (API 36).

## Languages

English and Turkish. The app follows the device language, and on Android 13+ you can pick it per app
in system settings.
