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
- [x] **P3.2 — `NuclearMode`: add GNOME/KDE task managers to the escape list**  
  Added missing KDE/GNOME/LXQt monitor variants and user-launched monitor tools while excluding compositor, display-server, D-Bus, and system-critical processes.
- [x] **P3.3 — `FloatingBlockOverlay`: raise on Linux**  
  Added an IO-dispatched, best-effort `xdotool windowraise` path for X11/XWayland. Native Wayland remains process-enforcement-only.

## Phase 4 — System integration

- [x] **P4.1 — `SystemTrayManager`: `notify-send` fallback for Wayland**  
  Notifications now use `notify-send` asynchronously when AWT tray support is unavailable and the tool is installed.
- [x] **P4.2 — `WindowsStartupManager`: verify `resolveExePath()` for AppImage/deb**  
  Linux autostart now resolves a real executable when available, retains a valid JVM fallback, quotes desktop commands, and writes `StartupWMClass=focusflow`.
- [x] **P4.3 — `FocusLauncherService`: GNOME/KDE panel handling**  
  Native Wayland and GNOME/KDE/Plasma compositor panels are detected and skipped with a one-time limitation log; X11 XFCE/LXPanel behavior remains unchanged.

## Phase 5 — Polish and distribution

- [x] **P5.1 — `VpnNetworkScreen`: replace Windows-only text on Linux**  
  Linux now describes `/etc/hosts`, iptables, and pkexec while Windows retains its administrator/Firewall copy.
- [x] **P5.2 — `SettingsScreen`: Linux-aware enforcement description**  
  Linux shows active xdotool/wmctrl polling, a green enforcement status, Linux enforcement components, and the Linux database path.
- [x] **P5.3 — `CrashReporter`: verify `safetyCleanup()` on Linux**  
  Safety cleanup documents the guarded registry call and uses the existing Linux-safe launcher emergency restore path; Linux tests remain green.
- [ ] **P5.4 — CI: GitHub Actions Linux build and test step**  
  Linux test and package workflows exist, but they are not one strict release gate yet. The test workflow runs enforcement tests without desktop tools, while the package workflow builds artifacts separately and tolerates some missing-artifact/repackaging failures. Close the gates before checking this item.

## Phase 6 — Release-readiness hardening

These items were added after a source/workflow/package audit on 2026-09-21.
They are intentionally open: static inspection is not a substitute for a real
Linux session or a privileged enforcement test.

- [ ] **P6.1 — Make `/etc/hosts` privilege handling real and safe**
  Implement and test a constrained `pkexec` or equivalent helper for atomic Linux hosts-file writes. Verify cancellation, failure reporting, unblock, shutdown cleanup, and resolver behavior. The current code checks writability but does not itself elevate the write.
- [ ] **P6.2 — Make Linux firewall state truthful**
  Do not report `NetworkBlocker.addRule()` as successful before the asynchronous `iptables` operation is verified. Expose pending/failed state, use bounded subprocesses, and verify tagged rule cleanup.
- [ ] **P6.3 — Remove remaining UI-thread Linux probes**
  Add `iptables` to `LinuxToolsChecker.checkAll()` and remove the synchronous `LinuxToolsChecker.isInstalled("iptables")` call from `LinuxSetupScreen`.
- [ ] **P6.4 — Add subprocess timeouts and failure diagnostics**
  Cover `pkexec`, `iptables`, `nslookup`, `resolvectl`, `xdotool`, `wmctrl`, `systemctl`, and notification calls. A cancelled prompt or broken desktop session must not hang enforcement or shutdown.
- [ ] **P6.5 — Validate foreground detection by session type**
  Test X11, XWayland, and native Wayland separately. The current Wayland `wmctrl -lp` fallback must not be treated as proof of the active window.
- [ ] **P6.6 — Close strict CI/package gates**
  Fail CI when required `.deb`/`.rpm` artifacts, dependency metadata, or AppImage output are missing. Validate version, architecture, desktop entry, icon, dependencies, and package contents.
- [ ] **P6.7 — Make release installers reproducible and verifiable**
  Align `install.sh` and AUR URLs with the actual release repository, add checksum/signature verification, clean up failed downloads, make repeated installs safe, replace AUR placeholders, and remove `sha256sums=('SKIP')`.
- [ ] **P6.8 — Add privileged and recovery integration tests**
  Test real hosts/firewall blocking, cleanup after force-kill, watchdog/autostart lifecycle, resolver variants, and cancelled authentication in disposable Linux environments. Keep unit tests non-destructive.
- [ ] **P6.9 — Add shell/input safety tests**
  Cover process names, domain names, desktop-file `Exec=` values, paths with spaces, and shell metacharacters. Verify no user-controlled value reaches an unsafe shell command.

## Manual test checklist

Run these after the implementation phases, on real Linux hardware or disposable
VMs where possible. Record distro, desktop, session type, tool versions, and
result. Do not check a box from code inspection alone.

### Session matrix

- [ ] Record one X11 run: distro/version, desktop, `DISPLAY`, and tool versions.
- [ ] Record one native Wayland GNOME run: distro/version, compositor, and tool versions.
- [ ] Record one native Wayland KDE/Plasma run.
- [ ] Record one XFCE or LXQt run if the panel-hiding path is shipped.
- [ ] Repeat the important checks with `xdotool`, `wmctrl`, `notify-send`, `pkexec`,
  `iptables`, and `systemd --user` individually absent where the UI says they
  are optional or has a fallback.

### Install and package

- [ ] Build `.deb`, `.rpm`, and AppImage; fail the release if any required artifact
  is absent or has the wrong version/architecture.
- [ ] Inspect package dependencies, desktop entry, `Exec=`, icon, launcher path,
  permissions, and installed files.
- [ ] Install, launch, upgrade, and uninstall the `.deb` on Debian/Ubuntu.
- [ ] Install, launch, upgrade, and uninstall the `.rpm` on Fedora or another RPM
  distro if RPM is shipped.
- [ ] Run the AppImage from a path containing spaces and verify the generated
  launcher, icon, upgrade replacement, and manual uninstall.
- [ ] Test `install.sh` on a clean user account, including a failed download and
  repeated install. Verify checksum/signature behavior before trusting the file.
- [ ] On Arch, build/install/uninstall the AUR package only after replacing the
  placeholder maintainer and `SKIP` checksum.

### App discovery and UI

- [ ] Confirm the app window and tray/menu entry show the correct icon.
- [ ] Confirm App Blocker shows icons from native `.desktop` files, user icons,
  themed icons, absolute `Icon=` paths, Flatpak, and Snap where installed.
- [ ] Confirm `Exec=` field codes, `env` prefixes, quoted paths, and Flatpak IDs
  normalize to the intended process.
- [ ] Confirm no Windows Task Manager/registry dialog appears on Linux startup.
- [ ] Confirm Linux Setup probes are asynchronous and accurately show missing tools.
- [ ] Confirm VPN/Network and Settings screens describe the actual session
  guarantees, permissions, and missing optional tools.

### Enforcement and kiosk behavior

- [ ] Confirm a normal native Linux application is blocked and killed.
- [ ] Confirm a Flatpak app and a Snap app are handled where installed.
- [ ] Confirm child-process launchers and applications with different desktop-file
  IDs are handled or explicitly reported as unsupported.
- [ ] Confirm X11 keyboard shortcuts are blocked during kiosk mode.
- [ ] Confirm native Wayland keyboard-hook handling does not crash and the reduced
  guarantee is visible; do not mark it equivalent to X11.
- [ ] Confirm GNOME, KDE/Plasma, XFCE, and LXQt task-manager/terminal escape tools
  are handled without killing compositor, display-server, D-Bus, or input-stack
  processes.
- [ ] Confirm the block overlay stays on top on X11 and behaves honestly on
  XWayland/native Wayland.
- [ ] Test one monitor, multiple monitors, negative coordinates, scaling, hot-plug,
  lock/unlock, suspend/resume, and display rotation where available.
- [ ] Confirm notifications through AWT tray and `notify-send` fallback.
- [ ] Confirm native Wayland and GNOME/KDE panel limitations are logged and panels
  are not left hidden.

### Privilege, network, lifecycle, and recovery

- [ ] Confirm hosts blocking succeeds through the documented privilege path,
  reports cancelled authentication, verifies DNS behavior, and removes every
  FocusFlow entry on unblock/shutdown.
- [ ] Confirm process/domain firewall rules are actually present, tagged, and
  removed after the session; test both `pkexec` success and failure.
- [ ] Test systemd-resolved, NetworkManager/nscd, and a machine without a cache
  service. Do not assume `nslookup` is installed or that DoH is blocked.
- [ ] Confirm autostart works after package install and login, and disabling/
  uninstalling removes the generated entry.
- [ ] Confirm the watchdog relaunches a killed app only when systemd user services
  are available and does not create duplicate instances.
- [ ] Force-kill FocusFlow during an active session and confirm overlay, panels,
  hosts entries, firewall rules, watchdog units, and autostart state recover.
- [ ] Test database migration, backup/restore, locale/time-zone changes, clock
  adjustment, sleep/wake, and application restart.
- [ ] Confirm the app remains usable without root and when optional tools or
  `pkexec` are missing.

### Regression and release safety

- [ ] Run the full Linux test suite, not only the enforcement package subset.
- [ ] Run package-content and desktop-entry validation in CI.
- [ ] Run dependency/security checks and inspect release contents for embedded
  webhooks, credentials, or unintended telemetry.
- [ ] Confirm the Windows build still succeeds.
- [ ] Confirm Windows block enforcement, cleanup, startup, and kiosk behavior still
  work.

## Post-ship candidates

- [ ] AppImage auto-update.
- [ ] Native Wayland kiosk/compositor integration.
- [ ] Snap packaging.