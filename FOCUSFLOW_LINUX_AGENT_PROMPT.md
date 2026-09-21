# FocusFlow Linux Migration — Agent Execution Prompt

Use this document as the main prompt when assigning an agent work on the FocusFlow
Linux port. It is intentionally code-aware. The tracker and older task prompts may
describe an earlier version of the code, so an agent must inspect the current
implementation before editing.

## Mission

Complete the FocusFlow Linux desktop port while keeping the production Windows
implementation intact. Add Linux behavior beside existing Windows behavior. Do not
replace, weaken, or remove working Windows enforcement.

The Linux port is not complete until:

- the five implementation phases are addressed;
- Linux builds, tests, and packaging are verified;
- X11 and Wayland limitations are explicitly handled or documented;
- relevant desktop-environment and packaging checks are run;
- Windows compilation and Windows regression behavior remain protected.

## Read before touching code

Read these in this order:

1. `AGENT_PRE_WORK_PROMPT.md`
2. `focusflow-linux-handoff.md`
3. `replit.md`
4. `focusflow-linux-plan.md`
5. `focusflow-linux-tracker.md`
6. The relevant section of `focusflow-linux-tracker.html`
7. `.agents/memory/MEMORY.md`, then any linked memory topic relevant to the files
   you will edit
8. The target Kotlin files and their call sites

Use this source-of-truth priority when documents disagree:

1. Current code and current build files
2. `focusflow-linux-tracker.md`
3. `focusflow-linux-plan.md`
4. `focusflow-linux-tracker.html` task prompts

The HTML tracker is useful for context and manual checks, but its interactive
status is browser state. The Markdown tracker and `focusflow-linux-handoff.md`
must be updated in the worktree so the next agent can recover the state.

## Verified repository baseline

This baseline was checked against the current repository. Re-check the target
before making a change because another agent may have moved an item forward.

### Already present

- Platform-specific Linux code exists in process monitoring, process killing,
  network blocking, hosts blocking, desktop-file scanning, systemd watchdog
  installation, autostart, setup UI, and package configuration.
- `build.gradle.kts` already declares Deb, Rpm, and AppImage targets.
- `.github/workflows/build-linux.yml` already builds Linux packages and contains
  an icon-generation step plus package dependency injection.
- `.github/workflows/test-linux.yml` already runs Linux enforcement tests.
- X11 keyboard grabbing is implemented in `GlobalKeyboardHook`; native Wayland
  does not provide the same grab.
- `NuclearMode` already includes many Linux terminals and task/system monitors,
  including `gnome-system-monitor`, `ksysguard`, `kdesystemguard`, and
  `xfce4-taskmanager`.
- `OsBanner` is already suppressed on Windows and has a Linux branch. Do not
  assume the wording is complete just because the guard exists.

### Completed in the current worktree

- `P1.2` root React/video files were confirmed unused by the Kotlin/Gradle build
  and removed without touching `artifacts/mockup-sandbox`.
- `P2.1` XDG icon lookup now reads Linux `.desktop` metadata and standard icon
  locations, with the existing FileSystemView fallback.
- `P2.2` Linux desktop scanning now retains full `Exec=` commands, normalizes
  quoted/env/ordinary/Flatpak launchers, and includes existing Flatpak/Snap
  export directories.
- `P2.3` the registry orphan check and Windows-only dialog are guarded by
  `IS_WINDOWS`.
- `P1.1` now has a source `focusflow.png` resource, and `packageDeb` has been
  verified successfully with the packaged Linux icon present.
- `P3.1` now reports native Wayland as reduced keyboard protection, logs the
  limitation, and explains it in Linux-facing UI while retaining the X11 grab.

### Confirmed still missing or incomplete at the time of this prompt

- `src/main/resources/focusflow.png` is now present and copied into build
  resources, but local `packageDeb` verification is blocked because `fakeroot`
  is not installed.
- `AppIconExtractor` currently uses Swing `FileSystemView` on non-Windows
  platforms; it does not perform XDG `.desktop` icon lookup.
- `InstalledAppsScanner.scanLinuxDesktopFiles()` currently scans only
  `/usr/share/applications` and `~/.local/share/applications`, and its `Exec=`
  parsing only takes the first space-delimited token.
- `ScannedApp` currently contains `processName`, `displayName`, `isRunning`, and
  `exePath`; it does not contain a desktop-file path or the full `Exec=` value.
  The older HTML prompt assumes such a field exists. Design the smallest clean
  data-flow change needed instead of copying that prompt literally.
- `App.kt` currently runs the registry orphan check without an outer
  `IS_WINDOWS` guard. The Linux path must not invoke the Windows registry
  diagnostic or show its Windows-specific dialog.
- `GlobalKeyboardHook` intentionally does not claim a native Wayland keyboard
  grab. The remaining kiosk work is process coverage and overlay behavior.
- `FloatingBlockOverlay` raises/focuses its AWT window but has no Linux
  `xdotool windowraise` best-effort path.
- `SystemTrayManager.showNotification()` only uses the AWT tray icon. It has no
  `notify-send` fallback when the tray is unavailable.
- Linux autostart writes an `.desktop` file, but `resolveExePath()` has a generic
  fallback and the desktop entry does not yet include `StartupWMClass=focusflow`.
  Verify actual behavior for packaged installs, AppImage, and development runs
  before changing it.
- `FocusLauncherService` hides panels only through `xdotool` class `panel`.
  This is not effective for GNOME Shell, KDE Plasma, or native Wayland.
- Linux-facing copy remains in places such as `VpnNetworkScreen` and
  `SettingsScreen`; some strings still describe Windows administrator,
  hosts-file, firewall, or Win32-only behavior.
- `CrashReporter.safetyCleanup()` calls several cleanup services directly. Verify
  that every called path is Linux-safe/no-op or guarded before declaring crash
  cleanup complete.

## Ordered work queue

Work on the first incomplete item unless the user explicitly chooses another.
Use the task IDs in all tracker and handoff updates.

### Phase 1 — Build blockers

- **P1.1 — Add `focusflow.png`**
  Add the source resource without deleting `focusflow_256.png`. Verify that the
  Linux package task resolves the file and that runtime resource loading is safe.
  Do not rely only on CI-generated files.
- **P1.2 — Remove unrelated React/video files**
  Confirm Gradle/Kotlin code does not use them, then remove only the unrelated
  files listed by the tracker. Do not remove the FocusFlow promo artifact or
  unrelated documentation.

### Phase 2 — Core blocking

- **P2.1 — XDG icon lookup**
  Implement Linux icon lookup for `.desktop` entries, absolute `Icon=` paths,
  standard XDG theme directories, and safe fallback behavior. Keep disk and
  image work off the UI thread. Respect the current `AppIconExtractor` API and
  introduce desktop-file metadata only if it is needed by the real call graph.
- **P2.2 — Linux app discovery and process matching**
  Normalize desktop-file `Exec=` values without destroying the information
  needed for diagnostics. Handle ordinary binaries, quoted commands, field
  codes, `env`, and Flatpak launchers deliberately. Add existing user/system
  Flatpak and Snap desktop directories only when they exist. Verify matching
  against both `ProcessHandle.info().command()` and command-line behavior.
- **P2.3 — Windows registry diagnostic guard**
  Guard both the registry check and its Windows-only dialog in `App.kt`. Linux
  startup must not touch Windows registry/JNA logic. Windows behavior must remain
  unchanged.

### Phase 3 — Kiosk hardening

- **P3.1 — Native Wayland keyboard gap**
  Investigate the supported environment before adding a dependency or subprocess
  protocol. If D-Bus portal inhibition is not reliable across supported
  compositors, document the reduced guarantee and retain process-kill and overlay
  enforcement. Never claim a Wayland keyboard grab that the implementation does
  not actually provide.
- **P3.2 — Linux escape processes**
  Compare the current set with common GNOME, KDE, XFCE, and terminal escape
  tools. Add only missing safe-to-kill user tools; never add compositors,
  display servers, D-Bus infrastructure, input stacks, or other system-critical
  processes.
- **P3.3 — Overlay raising**
  Add a bounded, non-blocking best-effort raise path for Linux/X11 or XWayland
  where it is useful. Do not affect Windows behavior or hang enforcement if
  `xdotool` is absent.

### Phase 4 — System integration

- **P4.1 — Wayland notifications**
  Preserve AWT tray notifications when available. When Linux has no tray icon
  and `notify-send` is installed, use it safely and handle missing tools,
  process-start errors, and message arguments without crashing.
- **P4.2 — Linux autostart**
  Resolve a real executable or a valid launcher command for the install mode in
  use. Keep the generated `.desktop` file valid, add a correct `Exec` value and
  `StartupWMClass` only when appropriate, and test enable/disable idempotence.
- **P4.3 — Desktop-environment panel handling**
  Keep the existing XFCE/LXPanel behavior. Detect GNOME/KDE/native Wayland
  limitations before issuing ineffective X11 commands. It is acceptable to
  document unsupported panel hiding for a release, but the UI/logs must be
  honest and session cleanup must not leave a panel hidden.

### Phase 5 — Polish and distribution

- **P5.1 — Linux VPN/network copy**
  Replace Windows-only wording with Linux-specific `/etc/hosts`, `iptables`,
  `pkexec`, and privilege guidance where the current screen exposes it.
- **P5.2 — Linux settings copy/status**
  Report the actual Linux enforcement capabilities, including polling,
  X11/Wayland differences, and optional tool availability. Do not label a
  capability active unless the code path is actually active.
- **P5.3 — Crash cleanup**
  Trace every service called by `CrashReporter.safetyCleanup()`. Ensure Linux
  cleanup cannot load Windows-only JNA paths or mutate unrelated state
  incorrectly. Add focused tests if the existing test style supports them.
- **P5.4 — CI**
  Extend the existing Linux workflows only where coverage is missing. Do not
  create a duplicate workflow. Keep the current package workflow and smoke-test
  workflow responsibilities clear. The current workflows are not a complete
  release gate: the test workflow does not prove privileged enforcement or
  package output, and the package workflow tolerates some missing-artifact and
  repackaging failures. Required artifacts and metadata must fail closed.

### Phase 6 — Release-readiness hardening

- **P6.10 — Linux UI honesty and guidance**
  Keep the Linux UI complete and truthful while enforcement hardening continues.
  Audit `LinuxSetupScreen`, `SettingsScreen`, `VpnNetworkScreen`, `OsBanner`,
  onboarding/navigation, autostart/package guidance, and the Linux startup
  registry guard. Tool checks must be asynchronous and actionable. Wayland copy
  must clearly distinguish reduced keyboard, foreground-window, overlay, and
  panel guarantees. Do not claim that `/etc/hosts` pkexec writes or iptables
  enforcement are verified until their implementation succeeds.

- **P6.1 — Hosts-file privilege path**
  The Linux hosts path currently checks whether `/etc/hosts` is writable but
  does not implement the documented `pkexec` write path. Add a constrained,
  auditable privilege mechanism, preserve atomic writes, and test cancellation,
  cleanup, and resolver behavior.
- **P6.2 — Firewall truthfulness**
  Linux `NetworkBlocker.addRule()` currently registers intent before its
  asynchronous iptables operation is known to succeed. Expose pending/failure
  state, verify tagged rules, bound subprocesses, and retry or clean up safely.
- **P6.3 — UI-thread I/O**
  Add `iptables` to the shared async Linux tool probe and remove the synchronous
  iptables check from `LinuxSetupScreen`.
- **P6.4 — Runtime and packaging gates**
  Validate X11, native Wayland, XWayland, GNOME, KDE/Plasma, and a lightweight
  desktop separately. Validate strict Deb/Rpm/AppImage outputs, installer/AUR
  checksums, desktop entries, upgrades, uninstalls, and recovery behavior.
- **P6.5 — Security and recovery**
  Add shell-argument safety tests, subprocess timeouts, privileged integration
  coverage, force-kill cleanup checks, and no-consent telemetry/package-content
  checks.

## Engineering rules

- Preserve all Windows branches and Windows-only behavior.
- Use existing platform guards and conventions. Prefer a guarded Linux branch
  over a broad refactor.
- Do not add a dependency when the JDK, existing JNA setup, or a guarded
  subprocess is sufficient.
- Keep filesystem, process, database, and image I/O off Compose/UI threads.
- Follow the existing concurrency conventions. Use atomic StateFlow operations
  for read-modify-write and do not introduce `runBlocking` into shutdown or
  enforcement paths.
- Do not silently swallow a new failure in a way that makes enforcement appear
  active. Log or expose an honest limitation.
- Do not make broad cleanup changes while working on one task.
- Do not mark a task complete merely because the code compiles.

## Required work loop

1. Read the handoff and inspect `git status`.
2. Re-check the current target code and call sites.
3. Mark the selected task as in progress in the handoff before editing.
4. Make the smallest focused implementation.
5. Run the cheapest targeted check, then the relevant Gradle test or build.
6. Run packaging/manual checks when the task affects packaging or desktop
   integration.
7. Update `focusflow-linux-tracker.md` only after the acceptance checks pass.
8. Update the default status/prompt in `focusflow-linux-tracker.html` if the
   task's implementation state changed, while remembering that browser
   localStorage is not the durable record.
9. Update `focusflow-linux-handoff.md` with completed IDs, files, verification,
   blockers, and the exact next task.
10. Report what changed and what remains. If blocked by unavailable X11,
    Wayland, desktop environment, privileges, or packaging tools, record that
    explicitly instead of guessing.

## Verification gates

Use the commands appropriate to the change. At minimum, preserve this order:

```bash
./gradlew compileKotlin
./gradlew test
./gradlew packageDeb
```

The package command may require Linux packaging tools and a graphical
environment. If it cannot run in the current environment, record the exact
reason and use the existing CI workflow or a targeted static check instead.

Before Linux migration completion, the manual checklist must cover:

- X11 and Wayland startup;
- package build/install and icon display;
- real app icon lookup and process blocking;
- no Windows registry dialog on Linux;
- Flatpak/Snap discovery where installed;
- X11 keyboard enforcement and Wayland reduced-guarantee behavior;
- Linux task-manager/terminal escape handling;
- overlay and notification behavior;
- autostart enable/disable and cleanup;
- Linux VPN/network and Settings copy;
- Linux Setup missing-tool warnings and package/autostart guidance;
- no Windows registry or Task Manager messaging on Linux startup;
- crash cleanup;
- hosts-file and iptables success/failure with and without `pkexec`;
- systemd-resolved/nscd/no-cache DNS variants;
- autostart/watchdog install, relaunch, duplicate prevention, and uninstall;
- multi-monitor, scaling, lock/unlock, suspend/resume, and display hot-plug;
- clean-package install, upgrade, launch, and removal on supported distros;
- missing optional tools and cancelled authentication;
- Windows build and Windows enforcement regression checks.

## Handoff format

Every agent must leave `focusflow-linux-handoff.md` in this format:

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

The next agent should be able to continue from that file without asking the
previous agent what happened.