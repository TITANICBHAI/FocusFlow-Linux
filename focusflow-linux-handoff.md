# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux migration implementation
Date: 2026-09-21  
Task IDs: P1.1, P1.2, P2.1, P2.2, P2.3, P3.1 completed
Status: Phase 1 build blockers, Phase 2 core-blocking code, and the P3.1 Wayland assessment are complete.
Changed: Added `src/main/resources/focusflow.png`; removed the unrelated root React/video files; added XDG icon lookup and Linux desktop metadata; normalized quoted, env, ordinary, and Flatpak `Exec=` values; added Flatpak/Snap desktop directories; guarded the Windows registry check and dialog; added explicit X11/Wayland keyboard-protection modes, Wayland limitation logging, and Linux UI copy.
Verification: `bash ./gradlew compileKotlin test --no-daemon` passed; `InstalledAppsScannerLinuxTest` passed; `src/main/resources/focusflow.png` is a valid 256x256 PNG identical to `focusflow_256.png`; Gradle copied it unchanged to `build/resources/main`; `bash ./gradlew packageDeb --no-daemon` passed; the `.deb` contains `./opt/focusflow/lib/FocusFlow.png`; root Kotlin/Gradle references to the removed files are absent; `git diff --check` passed.
Blockers or environment limits: No real X11, Wayland, desktop-environment, Windows runtime, or live app-blocking test has been performed. Native Wayland global shortcut suppression remains intentionally unavailable; the product now reports that reduced guarantee.
Next task: P3.2 — Linux escape processes.
Notes for the next agent: Read `AGENT_START_HERE.md` first. `ScannedApp` retains `execCommand` and `desktopFilePath`; the icon loader accepts the existing path API plus an optional process name and performs Linux disk/image work from existing IO call sites. `GlobalKeyboardHook.linuxKeyboardMode()` distinguishes X11 global grab, native Wayland reduced protection, and unavailable/headless sessions. The Linux build and smoke-test workflows already exist, so extend them rather than creating duplicates.

## Update template

Replace the current state section with the following after each focused task:

```text
Last agent:
Date:
Task IDs:
Status:
Changed:
Verification:
Blockers or environment limits:
Next task:
Notes for the next agent:
```

Keep this file factual and short. Do not paste full logs or secrets here.