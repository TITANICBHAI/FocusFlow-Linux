# FocusFlow Linux Enforcement and Release Tests Plan

## Purpose

Make Linux enforcement behavior truthful, safe, testable, and releasable
before Windows-specific code is removed.

This workstream covers enforcement correctness and release validation. It is
separate from the installed-app catalog work in
`work/linux-app-discovery-and-pickers-plan.md`.

## Status

- Overall: **Not started**
- Owner: FocusFlow implementation and release work
- Depends on: existing Linux enforcement branches and test seams
- Required before: declaring Linux release readiness or beginning Windows
  removal

## Current baseline

The project already has Linux implementations for process termination,
hosts-file handling, process monitoring, X11 helpers, systemd-user watchdog
generation, notifications, and Linux packaging.

The current release-readiness evidence is not sufficient to call Linux
production-ready:

- Compile and non-destructive tests pass.
- Some privileged hosts/firewall tests exist but are opt-in.
- Real `/etc/hosts` privilege behavior has not been verified in a disposable
  privileged environment.
- Linux firewall status needs to reflect actual `iptables` success/failure.
- Native Wayland foreground identification has reduced guarantees.
- Subprocess timeout coverage must be completed.
- Package/install/upgrade/uninstall validation must run on real Linux systems.

## Safety rules

1. The default test suite must remain non-destructive.
2. Hosts and firewall tests must be opt-in and run only in a disposable
   environment.
3. Every privileged test must restore state in `finally` blocks.
4. No test may flush a user's firewall or replace their hosts file outside an
   explicitly isolated test fixture.
5. User-controlled values must never be interpolated into shell commands.
6. A failed or cancelled privilege prompt must produce a visible failure state,
   not a false success.
7. Wayland limitations must be reported as reduced guarantees, not hidden.
8. Passing compilation is not release evidence.

## Product decisions

### 1. Truthful enforcement state

Linux enforcement operations need observable states such as:

- Not requested
- Pending authorization
- Applying
- Active
- Failed
- Cancelled
- Removing
- Removed

The UI and logs must not report a rule as active before the underlying
operation has been verified.

### 2. Hosts and firewall are separate layers

Hosts-file blocking and firewall blocking should be reported independently.
Hosts blocking may work when firewall permission is unavailable. Firewall
failure must not be hidden behind a generic “network blocking enabled” label.

### 3. Session guarantees depend on the display server

The release documentation and UI must distinguish:

- X11: keyboard grab and foreground/window helpers where supported.
- XWayland applications: partial compatibility.
- Native Wayland: process enforcement and overlay attempts, but no claim of
  unrestricted global keyboard interception or reliable compositor control.

## Implementation phases

### Phase A — Test and fixture foundation

Create reusable test seams for:

- Command execution with timeout and exit status.
- Privilege escalation.
- Hosts-file reads/writes.
- Firewall rule insertion/removal/query.
- Current display-session detection.
- Systemd-user availability.
- Package inspection.

Use fake executors for ordinary unit tests. Reserve real commands for opt-in
integration tests.

Record distro, desktop environment, session type, kernel, Java version, and
optional tool versions for every manual test run.

### Phase B — Hosts-file enforcement

Implement and verify a constrained Linux privilege path:

- Atomic write.
- Backup and restore.
- `pkexec` or another documented helper.
- Cancellation handling.
- Timeout handling.
- Permission-denied handling.
- Duplicate-entry prevention.
- Shutdown cleanup.
- Resolver/cache behavior documentation.

The operation must validate domains before any privileged command is started.

### Phase C — Firewall enforcement

Make Linux firewall behavior real and observable:

- Use tagged rules owned by FocusFlow.
- Verify insertion after the command completes.
- Verify removal.
- Retry cleanup when appropriate.
- Handle missing `iptables`.
- Handle missing `pkexec`.
- Handle cancelled authentication.
- Handle unsupported firewall backends clearly.
- Bound every subprocess.

The UI should show whether network blocking is active through hosts, firewall,
both, or neither.

### Phase D — Process, focus, and recovery behavior

Verify Linux enforcement for:

- Normal native applications.
- Applications with child processes.
- Flatpak applications.
- Snap applications.
- Applications whose launcher and process names differ.
- Terminal and shell escape routes.
- Nuclear Mode.
- Focus Launcher allowlists.
- Timed blocks.
- Daily allowances.
- Recurring schedules.
- Session shutdown.
- Forced process termination.

Test cleanup after:

- Normal shutdown.
- App crash.
- Process kill.
- System suspend/resume.
- User logout.
- Display lock/unlock.

### Phase E — Display-session and desktop integration

Run a session matrix for:

- X11.
- Native Wayland GNOME.
- Native Wayland KDE/Plasma.
- XWayland applications.
- XFCE or LXQt where supported.

Validate:

- Foreground detection.
- Keyboard behavior.
- Overlay visibility and raising.
- Panel handling.
- Tray behavior.
- Notifications.
- Multi-monitor layouts.
- Fractional scaling.
- Display hot-plug.

Document each capability as supported, reduced, optional, or unavailable.

### Phase F — Watchdog, startup, and recovery

Verify:

- User autostart.
- Packaged and development launch paths.
- AppImage paths containing spaces.
- systemd-user timer installation.
- systemd absence.
- Watchdog relaunch.
- Watchdog cleanup.
- Crash recovery.
- Stale hosts entries.
- Stale firewall rules.
- Stale overlay/panel state.

The watchdog must never call Windows registry logic on Linux.

### Phase G — CI and package gates

Make CI fail closed when:

- Compilation fails.
- Any required test fails.
- A required package is missing.
- Package version or architecture is wrong.
- Desktop entry is invalid.
- Icon is missing.
- Dependencies are missing.
- AppImage creation fails.
- RPM tooling is absent when RPM is required.
- Checksums or installer provenance are invalid.

Validate:

- `.deb`
- `.rpm`
- AppImage
- Installer script
- SHA256 manifest
- AUR metadata if shipped

### Phase H — Release acceptance

Run install, upgrade, launch, autostart, enforcement, and uninstall tests on
real Linux environments.

Do not mark the Linux release ready using only the Replit VNC environment. The
current environment cannot prove real X11/Wayland behavior or privileged
hosts/firewall success.

## Tracker

### Test foundation

- [ ] **ENF-01** Define fake and real command-executor seams with bounded
  timeout and captured exit status.
- [ ] **ENF-02** Define isolated hosts-file and firewall test fixtures.
- [ ] **ENF-03** Ensure default Gradle tests never require root, `pkexec`,
  `iptables`, X11, Wayland, or a systemd-user session.
- [ ] **ENF-04** Add test metadata for distro, desktop, session type, Java,
  kernel, and optional tool versions.
- [ ] **ENF-05** Add shell/input safety coverage for every privileged or
  subprocess-backed operation.

### Hosts-file enforcement

- [ ] **ENF-06** Implement the constrained Linux privilege path for atomic
  `/etc/hosts` updates.
- [ ] **ENF-07** Handle authentication cancellation, timeout, denial, and
  malformed input without false success.
- [ ] **ENF-08** Verify duplicate prevention, backup, restore, unblock, and
  shutdown cleanup.
- [ ] **ENF-09** Verify resolver/cache behavior on supported Linux resolver
  configurations.
- [ ] **ENF-10** Run the opt-in privileged hosts integration test in a
  disposable Linux environment.

### Firewall enforcement

- [ ] **ENF-11** Add tagged FocusFlow firewall rules with safe ownership
  markers.
- [ ] **ENF-12** Verify rule insertion before reporting the rule active.
- [ ] **ENF-13** Verify rule removal, retry behavior, and stale-rule cleanup.
- [ ] **ENF-14** Handle missing tools, unsupported backends, cancelled
  authentication, and command failures visibly.
- [ ] **ENF-15** Run opt-in firewall integration tests in a disposable
  privileged Linux environment.
- [ ] **ENF-16** Add UI state for hosts blocking versus firewall blocking.

### Process and session enforcement

- [ ] **ENF-17** Test native process blocking and forced termination.
- [ ] **ENF-18** Test child-process applications and process aliases.
- [ ] **ENF-19** Test Flatpak and Snap process behavior.
- [ ] **ENF-20** Test Focus Launcher allowlist enforcement.
- [ ] **ENF-21** Test timed blocks, daily allowances, recurring schedules, and
  focus-session extra apps.
- [ ] **ENF-22** Test Nuclear Mode escape routes without killing FocusFlow,
  the compositor, display server, D-Bus, or input stack.
- [ ] **ENF-23** Test forced shutdown, crash cleanup, suspend/resume, and
  logout/restart recovery.

### Display and desktop matrix

- [ ] **ENF-24** Run the X11 foreground, keyboard, overlay, panel, tray, and
  notification checklist.
- [ ] **ENF-25** Run native Wayland GNOME tests and record reduced guarantees.
- [ ] **ENF-26** Run native Wayland KDE/Plasma tests and record reduced
  guarantees.
- [ ] **ENF-27** Test XWayland applications separately from native Wayland
  applications.
- [ ] **ENF-28** Test XFCE or LXQt panel behavior if that path is shipped.
- [ ] **ENF-29** Test multi-monitor, fractional scaling, hot-plug, lock/unlock,
  and display rotation behavior.
- [ ] **ENF-30** Ensure the UI never claims unsupported global keyboard or
  foreground guarantees on native Wayland.

### Startup and recovery

- [ ] **ENF-31** Verify autostart for installed package and development launch.
- [ ] **ENF-32** Verify AppImage startup paths, including paths with spaces.
- [ ] **ENF-33** Verify systemd-user watchdog installation, relaunch, and
  cleanup.
- [ ] **ENF-34** Verify behavior when systemd-user is unavailable.
- [ ] **ENF-35** Verify stale hosts, firewall, overlay, panel, and watchdog
  state after crash or forced termination.

### CI and packaging

- [ ] **ENF-36** Make Linux compile and test jobs strict release gates.
- [ ] **ENF-37** Fail when `.deb`, `.rpm`, or AppImage output is missing.
- [ ] **ENF-38** Validate package version, architecture, desktop entry, icon,
  executable permissions, dependencies, and payload.
- [ ] **ENF-39** Test `.deb` install, upgrade, launch, autostart, and uninstall.
- [ ] **ENF-40** Test `.rpm` install, upgrade, launch, autostart, and uninstall.
- [ ] **ENF-41** Test AppImage execution, desktop integration, replacement,
  and manual removal.
- [ ] **ENF-42** Validate installer URLs, checksums, cleanup, and idempotence.
- [ ] **ENF-43** Validate AUR metadata and package installation if AUR remains
  part of the release.

### Final release sign-off

- [ ] **ENF-44** Run the complete non-destructive Gradle test suite.
- [ ] **ENF-45** Run opt-in privileged tests in a disposable Linux environment.
- [ ] **ENF-46** Complete X11, Wayland, XWayland, and desktop-environment
  manual test records.
- [ ] **ENF-47** Complete package install/upgrade/uninstall records.
- [ ] **ENF-48** Verify backup/restore and database migration.
- [ ] **ENF-49** Verify no secrets, unsafe command interpolation, or debug-only
  behavior is included in release artifacts.
- [ ] **ENF-50** Publish a release-readiness report with known limitations.

## Acceptance criteria

This workstream is complete when:

1. Linux blocking operations report actual state rather than intent.
2. Hosts-file and firewall operations handle permission failures safely.
3. All privileged and subprocess operations are bounded and cancellable.
4. Default tests are safe and reproducible.
5. Opt-in privileged tests pass in a disposable Linux environment.
6. X11, native Wayland, XWayland, and supported desktop environments have
   explicit test results.
7. Crash, shutdown, watchdog, and recovery behavior is verified.
8. Required Linux packages install, upgrade, launch, and uninstall correctly.
9. CI fails closed on missing or invalid release artifacts.
10. Known limitations are visible in the product and release documentation.

## Gate before Windows removal

Windows-specific source and packaging must not be removed until both this plan
and `linux-app-discovery-and-pickers-plan.md` meet their acceptance criteria.

The final Windows-removal phase should be a separate cleanup project with:

- Database compatibility handling
- Linux-only build validation
- Removal of Windows UI and runtime classes
- Removal of Windows packaging and documentation
- A final Linux-only regression run