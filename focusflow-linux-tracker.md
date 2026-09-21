# FocusFlow Linux Migration Tracker

This is the Git-friendly companion to [`focusflow-linux-tracker.html`](focusflow-linux-tracker.html). The HTML file provides interactive status buttons, agent prompts, and browser-persisted manual test checkboxes; this file is the reviewable checklist to update in commits.

The detailed findings and implementation rationale live in [`focusflow-linux-plan.md`](focusflow-linux-plan.md).

## Working rule

Keep the Windows implementation working. Add Linux branches alongside existing Windows branches, and do not delete Windows paths as part of this migration.

## Phase 1 — Build blockers

- [x] **P1.1 — Add missing `focusflow.png` resource**
  The source resource is a valid copy of `focusflow_256.png`, is copied into build resources unchanged, and `packageDeb` completed successfully with the packaged `FocusFlow.png` icon present.
- [x] **P1.2 — Delete stray React/video files from the repo**
  Remove the unrelated `src/App.tsx`, `src/main.tsx`, `src/index.css`, and `src/components/video/` files after confirming they are not used by the Kotlin/Gradle build.

## Phase 2 — Core blocking

- [x] **P2.1 — `AppIconExtractor`: XDG icon lookup on Linux**
  Parse the `.desktop` file `Icon=` value, support absolute paths and XDG theme locations, and perform disk reads on `Dispatchers.IO`.
- [x] **P2.2 — `InstalledAppsScanner`: `Exec=` stripping and Flatpak/Snap directories**
  Store a normalized executable name for matching, retain the full command for diagnostics, and scan user/system Flatpak and Snap application directories when they exist.
- [x] **P2.3 — `App.kt`: guard registry orphan check with `IS_WINDOWS`**
  Prevent the Windows registry diagnostic and its Windows-specific dialog from running on Linux.

## Phase 3 — Kiosk hardening

- [x] **P3.1 — `GlobalKeyboardHook`: close the Wayland keyboard gap**
  Native Wayland is explicitly reported as reduced protection because portal Inhibit does not block global keyboard delivery and GlobalShortcuts does not suppress compositor escape keys. X11 retains the real global grab; process blocking and the overlay remain active on Wayland.
- [ ] **P3.2 — `NuclearMode`: add GNOME/KDE task managers to the escape list**  
  Cover common Linux system monitors and terminal tools without duplicating existing entries.
- [ ] **P3.3 — `FloatingBlockOverlay`: raise on Linux**  
  Add best-effort `xdotool windowraise` calls on Linux/X11 or XWayland without blocking or affecting Windows behavior.

## Phase 4 — System integration

- [ ] **P4.1 — `SystemTrayManager`: `notify-send` fallback for Wayland**  
  Route notifications through `notify-send` when AWT tray support is unavailable and the tool is installed.
- [ ] **P4.2 — `WindowsStartupManager`: verify `resolveExePath()` for AppImage/deb**  
  Resolve the actual Linux executable path, keep the `.desktop` entry valid, and include `StartupWMClass=focusflow`.
- [ ] **P4.3 — `FocusLauncherService`: GNOME/KDE panel handling**  
  Detect the desktop environment, avoid ineffective `xdotool` calls where panels are compositor-integrated, and log the limitation while preserving XFCE/LXPanel behavior.

## Phase 5 — Polish and distribution

- [ ] **P5.1 — `VpnNetworkScreen`: replace Windows-only text on Linux**  
  Show `/etc/hosts`, `iptables`, and `pkexec` context to Linux users instead of Windows firewall/administrator wording.
- [ ] **P5.2 — `SettingsScreen`: Linux-aware enforcement description**  
  Display Linux enforcement status and tooling accurately, including the xdotool polling path.
- [ ] **P5.3 — `CrashReporter`: verify `safetyCleanup()` on Linux**  
  Confirm crash cleanup cannot invoke unsafe Windows/JNA behavior on Linux and that existing tests remain green.
- [ ] **P5.4 — CI: GitHub Actions Linux build and test step**  
  Run Linux tests on `ubuntu-latest`, install required desktop tools, and verify at least one Linux package build.

## Manual test checklist

Run these after the implementation phases, on real Linux hardware where possible. Keep the Windows checks as regression gates.

- [ ] Set up an X11 test machine.
- [ ] Set up a Wayland test machine or VM.
- [ ] Build and install the `.deb` package.
- [ ] Confirm package builds complete without errors.
- [ ] Confirm the app window shows the correct icon.
- [ ] Confirm App Blocker shows real app icons.
- [ ] Confirm app blocking actually kills the process.
- [ ] Confirm no “Windows Task Manager disabled” dialog appears on Linux startup.
- [ ] Confirm Flatpak apps appear when Flatpak is installed.
- [ ] Confirm X11 keyboard shortcuts are blocked during kiosk mode.
- [ ] Confirm Wayland keyboard-hook handling does not crash.
- [ ] Confirm GNOME System Monitor is killed during Nuclear Mode.
- [ ] Confirm the block overlay stays on top on X11.
- [ ] Confirm Wayland notifications appear through `notify-send`.
- [ ] Confirm autostart works after a `.deb` install and login.
- [ ] Confirm a GNOME kiosk session starts and ends without crashing.
- [ ] Confirm VPN/Network screen shows Linux text.
- [ ] Confirm Settings shows green Linux enforcement status.
- [ ] Confirm the Windows build still succeeds.
- [ ] Confirm Windows block enforcement still works.

## Post-ship candidates

- [ ] AppImage auto-update.
- [ ] Native Wayland kiosk/compositor integration.
- [ ] Snap packaging.