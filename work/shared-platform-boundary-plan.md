# FocusFlow Shared and Platform Code Separation Plan

## Purpose

Separate FocusFlow's shared product logic from operating-system integrations so
the Linux implementation can become the primary product without carrying
Windows behavior through every service and screen.

This is an architecture and refactoring workstream. It does not delete Windows
code immediately. Windows removal is a later phase after Linux discovery,
enforcement, release validation, and stored-data migration are complete.

## Status

- Overall: **Not started**
- Owner: FocusFlow implementation work
- Depends on: Linux app discovery and enforcement plans
- Coordinates with: `work/stored-data-migration-plan.md`
- Required before: safe Windows-only cleanup

## Current baseline

The project currently mixes shared and platform-specific behavior in the same
files:

- `WinApiBindings.kt` contains Win32/JNA APIs and Linux process/window helpers.
- `WinEventHook.kt` contains both the Windows event hook and Linux polling.
- `WindowsStartupManager.kt` contains both Windows registry startup and Linux
  desktop autostart.
- `WatchdogInstaller.kt` contains both scheduled-task and systemd-user logic.
- `ProcessMonitor.kt` contains shared enforcement decisions plus large
  Windows/Linux safe-process lists.
- `NetworkBlocker.kt`, `VpnBlocker.kt`, `AppIconExtractor.kt`, and
  `InstalledAppsScanner.kt` mix platform behavior with product-facing APIs.
- UI screens directly branch on `isWindows`/`isLinux`.
- `Models.kt` contains a Windows-only screen and setting name.
- `Database.kt` is shared persistence but currently stores many app references
  as plain process strings.

The current code works by dispatching on platform flags. That is useful during
the temporary dual-platform period, but it makes Linux-only cleanup risky
because shared code and Windows code are not clearly bounded.

## Target architecture

### Shared domain layer

Shared code should contain only product behavior and platform-neutral contracts:

- Focus sessions.
- Tasks and schedules.
- Daily allowances.
- Block rules.
- Network rule intent.
- App-selection state.
- Session lifecycle.
- Reports and statistics.
- Database models and repositories.
- Validation.
- User-facing capability state.

Shared code must not import:

- JNA Win32 classes.
- Windows registry APIs.
- `taskkill`.
- PowerShell.
- `iptables`.
- `pkexec`.
- `xdotool`.
- `wmctrl`.
- Desktop-specific filesystem paths.

### Platform capability layer

Define narrow interfaces for operations such as:

- Process discovery.
- Process termination.
- Foreground application detection.
- Window title lookup.
- Window focus/raise.
- Application launching.
- Startup registration.
- Watchdog registration.
- Hosts-file updates.
- Firewall updates.
- VPN process detection.
- Installed-app discovery.
- Icon lookup.
- Privilege checks.
- Notification delivery.

The shared layer should depend on these interfaces and receive capability
results. It should not determine how Linux or Windows performs the operation.

### Platform implementations

During the transition, retain separate implementations:

- `platform/linux/...`
- `platform/windows/...`

Existing names can be moved gradually. A file named `WindowsStartupManager`
should not remain the long-term home of Linux startup behavior.

## Design principles

1. Shared code decides **what FocusFlow wants**, not how the OS performs it.
2. Platform code reports success, failure, pending authorization, and reduced
   capability explicitly.
3. No platform implementation may expose raw command construction to the UI.
4. Platform-specific process names and safe-process lists stay in platform
   modules.
5. UI consumes capability state instead of repeating OS checks.
6. Database code stores product data and normalized app identities, not
   platform-specific command syntax.
7. Temporary Windows compatibility adapters are allowed, but their lifetime and
   removal condition must be documented.

## Implementation phases

### Phase A — Inventory and classification

Classify every platform reference as one of:

- Shared domain logic.
- Linux implementation.
- Windows implementation.
- UI capability presentation.
- Build/package configuration.
- Test-only platform code.
- Historical documentation.

Do not move files based only on their names. Some files with Windows names
contain active Linux behavior and need to be split first.

### Phase B — Define capability contracts

Create small interfaces and result types for the platform operations used by
the services.

Each result should be explicit enough to distinguish:

- Supported and active.
- Supported but not configured.
- Pending.
- Permission denied.
- Tool missing.
- Not supported by the current display session.
- Failed.

Avoid returning only `Boolean` from operations where the UI needs to explain a
Linux limitation or permission problem.

### Phase C — Extract Linux implementations

Move Linux behavior out of mixed classes in focused steps:

1. Process and foreground detection.
2. Installed-app discovery and icon lookup.
3. Startup and watchdog.
4. Hosts and firewall.
5. VPN detection.
6. Notifications and tray.
7. Overlay and panel behavior.

Keep the public behavior stable while moving implementation details. Run the
Linux tests after each extraction.

### Phase D — Simplify shared services

Update shared services to depend on capability interfaces:

- `ProcessMonitor`
- `FocusSessionService`
- `FocusLauncherService`
- `DailyAllowanceTracker`
- `BlockScheduleService`
- `StandaloneBlockService`
- `NuclearMode`
- `CrashReporter`
- startup and watchdog coordination

The services should not know whether a process was killed by `taskkill` or
`ProcessHandle`, or whether a startup entry is a registry value or a desktop
file.

### Phase E — Move UI decisions to capability state

Replace repeated `isWindows`/`isLinux` branches with capability data where
possible.

Examples:

- Show “Start on login” instead of a Windows-specific setting name.
- Show network-blocking capabilities and permission states.
- Show X11/Wayland limitations from a capability report.
- Use the shared app picker instead of platform-specific app-selection logic.
- Remove Windows-only setup navigation only after the Linux replacement is
  complete.

Platform detection can remain at the application composition root, but screens
should not contain native command or file-path logic.

### Phase F — Build and dependency boundary

Audit the build after code extraction:

- Keep only dependencies required by Linux and shared code.
- Verify whether JNA is still needed for Linux before removing it.
- Remove Windows-only JVM arguments.
- Separate Linux package targets from temporary Windows targets.
- Ensure tests do not load Win32 classes on Linux.
- Ensure Windows-specific resources are not required by Linux packaging.

Do not remove a dependency merely because it was originally introduced for
Windows. Confirm all remaining usages first.

### Phase G — Windows-removal readiness

Only after the Linux and data plans pass their acceptance criteria:

- Remove Windows implementations.
- Remove Windows adapters and platform branches.
- Remove Windows UI and navigation.
- Remove Windows package targets and resources.
- Remove Windows-only tests and documentation.
- Rename remaining misleading classes and APIs.
- Run a Linux-only compile, test, package, install, and recovery pass.

## Tracker

### Inventory and contracts

- [ ] **PLAT-01** Produce a complete inventory of platform-specific classes,
  functions, imports, resources, build targets, and documentation.
- [ ] **PLAT-02** Mark each platform reference as shared, Linux, Windows,
  UI-capability, build, test, or historical.
- [ ] **PLAT-03** Define capability result states for success, pending,
  denied, missing-tool, unsupported, and failure.
- [ ] **PLAT-04** Define interfaces for process discovery, termination,
  foreground detection, and application launching.
- [ ] **PLAT-05** Define interfaces for startup, watchdog, overlay, tray,
  notification, and icon behavior.
- [ ] **PLAT-06** Define interfaces for hosts, firewall, VPN, and privilege
  operations.

### Linux extraction

- [ ] **PLAT-07** Split Linux process/foreground behavior from Win32 bindings.
- [ ] **PLAT-08** Split Linux polling from the Windows event-hook lifecycle.
- [ ] **PLAT-09** Move Linux installed-app discovery behind the shared catalog
  contract.
- [ ] **PLAT-10** Move Linux icon resolution behind the app catalog/icon
  contract.
- [ ] **PLAT-11** Split Linux startup from registry startup.
- [ ] **PLAT-12** Split Linux watchdog from Windows scheduled-task logic.
- [ ] **PLAT-13** Split Linux hosts and firewall implementations from shared
  network-rule intent.
- [ ] **PLAT-14** Split Linux VPN detection from Windows VPN lists.
- [ ] **PLAT-15** Split Linux notification, tray, overlay, and panel behavior
  from shared session logic.

### Shared service cleanup

- [ ] **PLAT-16** Make `ProcessMonitor` consume a platform process controller
  and capability provider.
- [ ] **PLAT-17** Make focus-session, launcher, schedule, and allowance
  services platform-neutral.
- [ ] **PLAT-18** Keep Windows/Linux safe-process lists inside their platform
  implementations.
- [ ] **PLAT-19** Replace shared service Boolean assumptions with explicit
  capability results where permission or Wayland state matters.
- [ ] **PLAT-20** Ensure all platform I/O remains off Compose threads.
- [ ] **PLAT-21** Remove direct native command construction from UI screens.

### UI and data boundary

- [ ] **PLAT-22** Replace repeated screen-level OS branches with capability
  state where practical.
- [ ] **PLAT-23** Rename shared settings and labels that encode Windows-only
  concepts.
- [ ] **PLAT-24** Keep `Models.kt` platform-neutral; move Windows-only screen
  and setting concepts to the temporary compatibility layer.
- [ ] **PLAT-25** Make the shared picker and stored app identity independent of
  Windows executable conventions.
- [ ] **PLAT-26** Ensure database repositories do not call platform services.

### Build and test boundary

- [ ] **PLAT-27** Audit JNA and all native dependencies before changing them.
- [ ] **PLAT-28** Remove Windows-only JVM arguments from the Linux execution
  path.
- [ ] **PLAT-29** Add Linux contract tests for every extracted implementation.
- [ ] **PLAT-30** Add fake platform implementations for shared service tests.
- [ ] **PLAT-31** Verify Linux packaging does not include unnecessary Windows
  resources or classes.
- [ ] **PLAT-32** Verify startup, shutdown, recovery, and enforcement tests
  still pass after each extraction.

### Final Windows-removal readiness

- [ ] **PLAT-33** Confirm the app discovery plan is complete.
- [ ] **PLAT-34** Confirm the enforcement/release-tests plan is complete.
- [ ] **PLAT-35** Confirm the stored-data migration plan is complete.
- [ ] **PLAT-36** Create a final Windows-compatible backup/checkpoint before
  deleting Windows implementations.
- [ ] **PLAT-37** Remove Windows runtime implementations and adapters.
- [ ] **PLAT-38** Remove Windows UI, build targets, resources, and tests.
- [ ] **PLAT-39** Rename misleading remaining APIs and files.
- [ ] **PLAT-40** Run the complete Linux-only verification and package matrix.

## Acceptance criteria

This workstream is complete when:

1. Shared services depend on platform contracts rather than native APIs.
2. Linux implementations are isolated and testable without Windows classes.
3. UI screens consume capability state instead of constructing native commands.
4. Database repositories contain no OS-specific behavior.
5. Platform-specific process lists, paths, commands, and permission logic are
   outside shared product logic.
6. Linux tests can run without loading Win32/JNA classes unnecessarily.
7. The code can remove Windows implementations without rewriting core product
   behavior.
8. Linux package builds contain only intended Linux/shared runtime behavior.

## Out of scope

- Immediate deletion of Windows support.
- Changing the product's database schema by itself.
- Rewriting the Compose UI.
- Replacing JNA without an actual dependency audit.
- Adding macOS support.