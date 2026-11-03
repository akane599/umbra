# Progress: Umbra 1.2.1 (touch lock + stay awake)
_Updated: 2026-11-03_

## Goal
Umbra (`io.github.akane599.umbra`, https://github.com/akane599/umbra) ships three modes: panel off, touch lock, stay awake. 1.2 is pushed and running on the owner's phone.

## Done
- 1.2.1: touch lock closes an open keyboard (the only way Samsung's bar leaked under full lock: with a keyboard up it re-enables "hide keyboard" and the keyboard switcher). Verified on the phone with Termux.
- 1.2 pushed (`b43e265`) and installed on the owner's Samsung (Android 17). Touch lock confirmed working by the owner; `statusbar_api=ok` in the helper's diag.
- Touch lock: overlay (`LockOverlay.kt`, `ui/LockView.kt`) + no-timeout wake lock; optional dim, badge, and "Block system gestures too" via `IStatusBarService.disable` from `server/PanelServer.java`.
- Stay awake mode, three quick-settings tiles, launcher shortcuts, EN/TR strings and READMEs.
- Earlier: Umbra rename, package rename, redesign (see git log).

## Next
- [ ] Owner to confirm 1.2.1 on the phone: lock with a keyboard open, then try the bottom corners.
- [ ] Optional: reword `mode_lock_sum` (EN/TR) to say the screen timeout is off while locked.

## Decisions
- Full lock uses status bar disable flags with a server-held Binder token, not `IInputManager.disableInputDevice`: shell lacks `DISABLE_INPUT_DEVICE`, and the flags auto-clear if the helper dies.
- Pressing power ends touch lock and stay awake on purpose, as a guaranteed way out. The owner is fine with this (2026-11-03).
- The lock window is non-focusable (the app underneath keeps focus and is never paused); it takes focus only when it sees keyboard insets, which needs `FLAG_ALT_FOCUSABLE_IM` set from the start, and clears both flags together so the system treats it as a keyboard user.
- Hold-to-unlock is timed with `SystemClock`, not an animator, so "Remove animations" can't turn it into a tap.
