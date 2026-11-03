# Umbra
- Build: `./gradlew assembleRelease`. Signing reads `keystore.properties` and `*.jks`; both are gitignored, never commit them or `local.properties`.
- Privileged code lives in `server/PanelServer.java`, which runs as shell or root through `app_process`. Shell has `STATUS_BAR` but not `DISABLE_INPUT_DEVICE`, so block input with status bar disable flags, not InputManager.
- Every user-facing string goes in both `values/` and `values-tr/`.
- Without KVM the emulator only boots with `emulator -avd pk36 -accel off`. It's slow and shows frequent ANR dialogs; restore any settings you change for testing.
- For ongoing multi-session work, read PROGRESS.md at the repo root before planning, and update it at milestones.
