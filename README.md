# FocusFlow — Deep Focus App Blocker for Linux

> **Real enforcement. No soft timers. No “I’ll just close the app.”**

FocusFlow is a local-first desktop app for people who want their computer to
stop negotiating with them during a focus session. It tracks the work you
actually planned, blocks the distractions you named, and keeps an audit trail
of the attempts that got blocked.

The Linux port is built for people who care about how their desktop works:
processes, sessions, hosts files, firewall rules, X11, Wayland, permissions,
package formats, and the difference between a real block and a button that
looks like one. The UI is Kotlin + Compose Desktop. The data stays on the
machine in SQLite. There is no account and no cloud sync.

FocusFlow is currently dual-platform in the source tree. Linux is the forward
product direction; the Windows implementation remains available while Linux
enforcement and desktop-session support continue to mature.

---

## Download FocusFlow for Linux

The current release is **v2.0.1**:

| Distribution | Package | Notes |
|--------------|---------|-------|
| Debian / Ubuntu | [`focusflow_2.0.1_amd64.deb`](https://github.com/TITANICBHAI/FocusFlow-Linux/releases/tag/v2.0.1) | Installs as a native Debian package |
| Fedora / RPM-based systems | [`focusflow-2.0.1-1.x86_64.rpm`](https://github.com/TITANICBHAI/FocusFlow-Linux/releases/tag/v2.0.1) | Native RPM package |
| Any x86_64 Linux | [`FocusFlow-2.0.1-x86_64.AppImage`](https://github.com/TITANICBHAI/FocusFlow-Linux/releases/tag/v2.0.1) | Portable; no system installation required |
| Flatpak-capable desktops | `FocusFlow-2.0.1.flatpak` | Bundle included while Flathub review is pending |
| Snap-capable desktops | `focusflow_2.0.1_amd64.snap` | Bundle included while Snap Store review is pending |
| Other distributions | [`install.sh`](https://github.com/TITANICBHAI/FocusFlow-Linux/releases/tag/v2.0.1) | Installer and checksum are attached to the release |

Download only from the [GitHub release page](https://github.com/TITANICBHAI/FocusFlow-Linux/releases/tag/v2.0.1)
or a package source linked from this repository. Verify a downloaded file
against `SHA256SUMS` when installing outside a package manager.

The Linux packages are built and checked by GitHub Actions. The release
workflow publishes them only after the Debian, RPM, AppImage, and Linux smoke
test jobs pass.

### Quick start

For Debian or Ubuntu:

```bash
wget https://github.com/TITANICBHAI/FocusFlow-Linux/releases/download/v2.0.1/focusflow_2.0.1_amd64.deb
sudo apt install ./focusflow_2.0.1_amd64.deb
```

For the portable AppImage:

```bash
wget https://github.com/TITANICBHAI/FocusFlow-Linux/releases/download/v2.0.1/FocusFlow-2.0.1-x86_64.AppImage
chmod +x FocusFlow-2.0.1-x86_64.AppImage
./FocusFlow-2.0.1-x86_64.AppImage
```

The AppImage and packages are currently built for **x86_64**. ARM builds are
not advertised until they have a separate CI build and desktop test path.

## What FocusFlow does

FocusFlow is deliberately more opinionated than a timer:

- You define the task or session before the clock starts.
- You choose which applications, domains, keywords, and escape routes are
  off-limits.
- A blocked attempt is recorded instead of disappearing into a log file you
  will never read.
- Breaks, PINs, schedules, allowances, and hard-lock mode make the easy exit
  require an intentional decision.
- The app keeps your tasks, notes, habits, reports, and focus history locally.

It is not a parental-control service, an antivirus product, or a remote
monitoring system. It is a local desktop tool for making a commitment harder
to break.

---

## Linux support status

The Linux desktop is the primary workstream. The important distinction is
between the parts that are already packaged and the parts that depend on the
desktop session:

| Area | Current Linux behavior |
|------|------------------------|
| Compose desktop UI | Supported |
| SQLite data and reports | Supported |
| Process inspection and process blocking | Supported with desktop/session limitations |
| Hosts-file website blocking | Requires the appropriate privilege |
| iptables network blocking | Linux-only path; status and privileges are checked explicitly |
| X11 foreground detection | The most complete desktop path |
| Wayland foreground detection | Session/compositor dependent; native Wayland support is still being hardened |
| XWayland applications | Tested separately from native Wayland applications |
| `.deb`, `.rpm`, AppImage | Built and validated in GitHub Actions |
| Windows enforcement | Preserved for compatibility while the migration continues |

FocusFlow does not pretend that X11 and Wayland expose the same controls.
When a compositor or permission model prevents a feature, the limitation
should be visible rather than hidden behind a green “blocked” label.

## Linux Migration Roadmap

Linux migration work is planned and tracked in two root-level documents:

- **[Linux migration plan](focusflow-linux-plan.md)** — code-grounded findings, priorities, platform limitations, and the suggested implementation order.
- **[Interactive Linux tracker](focusflow-linux-tracker.html)** — browser-based task status board with agent prompts and manual test checklists.
- **[Markdown Linux tracker](focusflow-linux-tracker.md)** — Git-friendly checkbox version of the interactive tracker for reviews and commits.

The migration preserves the Windows implementation in source. Linux release readiness is tracked separately, including the Linux package, privilege, desktop-session, and enforcement checks.

See the [Linux publishing guide](LINUX_PUBLISHING_GUIDE.md) for Flathub,
Snap Store, AUR, COPR, OBS, Launchpad PPA, distro repositories, AppImage
distribution, and the release checklist.

---

## Features
### Enforcement

- **Application blocking** — blocks selected processes and records the attempt.
- **Keyword blocking** — catches matching window titles where the desktop
  session exposes them.
- **Website blocking** — writes selected domains to the hosts-file path when
  the user grants the required privilege.
- **Network blocking** — uses the Linux firewall path where the environment
  allows it; Windows uses its native firewall path.
- **Block schedules** — repeat blocking on a weekly timetable.
- **Daily allowances** — let an app run for a defined amount of time per day.
- **Standalone blocks** — start a timed block without creating a full focus
  session.
- **Block Defense** — inspect and configure the enforcement layers instead of
  treating them as magic.
- **Nuclear Mode** — the strictest mode, intended to remove common escape
  routes rather than merely dimming a notification.

### Focus work

- Pomodoro and custom focus sessions
- Session PINs and PIN-gated breaks
- Hard-lock sessions with no break path
- Focus Launcher with a dedicated work surface
- Recurring tasks, alarms, and reminders
- Daily notes, habits, streaks, stats, and session history
- Weekly focus reports and focus insights
- Temptation log for blocked attempts
- Optional aversion tone when an application is stopped

### Local-first by design

- SQLite database stored under `~/.focusflow/`
- Rolling local backups
- No account required
- No cloud dashboard
- No advertising SDK
- No remote administrator
- Privacy policy and terms are published with the project

The app still includes a system tray, notifications, onboarding, and recovery
paths. Linux startup and notification behavior varies by desktop environment,
so those integrations are being verified per session type rather than marked
universally complete.

---

## How enforcement works on Linux

FocusFlow does not need a cloud service to block a local process. The Linux
path uses the JVM process APIs and session-aware helpers, then records the
result in SQLite. Website and firewall enforcement are separate layers because
they need different permissions and fail for different reasons.

On X11, foreground-window information is generally available to a desktop
application. On native Wayland, the compositor intentionally withholds much
of that information. FocusFlow therefore treats X11, XWayland, and native
Wayland as different support cases.

The Linux package validator checks more than whether a file exists:

- package name, version, architecture, and dependencies;
- desktop entry and icon payload;
- AppImage executable mode and x86_64 format;
- AppImage extraction and payload metadata.

This is why a green compiler result is not treated as a complete Linux release
test.

---

## Build and develop locally

Requires JDK 19 for the current project configuration. Linux development is
the normal path; the native enforcement layer must still be tested on the
desktop session where it will run.

```bash
# Run the desktop UI
./gradlew run

# Run the Linux enforcement tests
./gradlew test --tests "com.focusflow.enforcement.*"

# Build Linux packages
./gradlew packageDeb
./gradlew packageRpm
./gradlew packageAppImage

# Validate package metadata and payloads
scripts/validate-linux-packages.sh build/compose/binaries/main 2.0.1
```

For a clean release, use the GitHub Actions workflows instead of trusting one
developer machine:

- `Build Linux Packages` builds `.deb`, `.rpm`, and AppImage artifacts.
- `Linux Smoke Tests` runs the Linux enforcement test group.
- `Publish Linux Release` publishes the assets only after a successful build.

The CI jobs install their own Ubuntu dependencies and use JDK 19. Privileged
hosts-file and firewall checks are intentionally not part of the default
non-destructive smoke-test suite.

---

## Tech Stack

| Layer | Choice | Version |
|-------|--------|---------|
| Language | Kotlin/JVM | 1.9.22 |
| UI | Compose Multiplatform Desktop | 1.6.1 |
| UI design system | Material 3 dark theme | — |
| Native interop | JNA + jna-platform | 5.14.0 |
| Database | org.xerial:sqlite-jdbc | 3.47.1.0 |
| Async | kotlinx.coroutines-swing | 1.7.3 |
| Build | Gradle (Kotlin DSL) | 8.14.2 |
| Packaging | jpackage (Compose Desktop plugin) | bundled JRE |
| CI/CD | GitHub Actions `ubuntu-latest` | Linux packages and smoke tests |

---

## Project Structure

```
src/main/kotlin/com/focusflow/
├── Main.kt                          Entry point; wires all services + tray
├── App.kt                           Root composable; onboarding check + nav
├── ui/
│   ├── theme/Theme.kt               Material 3 dark theme
│   ├── screens/
│   │   ├── DashboardScreen.kt
│   │   ├── TasksScreen.kt
│   │   ├── FocusScreen.kt
│   │   ├── FocusLauncherScreen.kt   CBT kiosk launcher UI
│   │   ├── AppBlockerScreen.kt
│   │   ├── StatsScreen.kt
│   │   ├── SettingsScreen.kt
│   │   ├── HabitsScreen.kt
│   │   ├── ReportsScreen.kt
│   │   ├── DailyNotesScreen.kt
│   │   ├── ProfileScreen.kt
│   │   ├── ActiveScreen.kt
│   │   ├── BlockDefenseScreen.kt
│   │   ├── KeywordBlockerScreen.kt
│   │   ├── WindowsSetupScreen.kt
│   │   └── PrivacyPermissionsScreen.kt
│   └── components/
│       ├── SideNav.kt
│       ├── TaskCard.kt
│       ├── BlockOverlay.kt
│       ├── FocusLauncherOverlay.kt  Full-screen kiosk overlay
│       ├── AppLogo.kt
│       ├── EmptyStateCard.kt
│       ├── ScrollUtils.kt
│       ├── OsBanner.kt
│       └── OnboardingScreen.kt
├── data/
│   ├── Database.kt                  SQLite via sqlite-jdbc
│   └── models/Models.kt             Data classes
├── enforcement/
│   ├── WinApiBindings.kt            JNA Win32 bindings
│   ├── ProcessMonitor.kt            Dual-mode: WinEventHook + 500ms polling
│   ├── AppBlocker.kt                Kill + overlay bridge
│   ├── NetworkBlocker.kt            netsh advfirewall rules
│   ├── NuclearMode.kt               Nuclear blocking (30+ escape routes blocked)
│   ├── WinEventHook.kt              Instant foreground event detection
│   ├── InstalledAppsScanner.kt      Curated + live running process scanner
│   └── WindowsStartupManager.kt     HKCU Run key auto-start
└── services/
    ├── FocusSessionService.kt
    ├── FocusLauncherService.kt      Kiosk session state + taskbar control
    ├── TemptationLogger.kt
    ├── SessionPin.kt
    ├── SoundAversion.kt
    ├── SystemTrayManager.kt
    ├── NotificationService.kt
    ├── TaskAlarmService.kt
    ├── RecurringTaskService.kt
    ├── BlockScheduleService.kt
    ├── StandaloneBlockService.kt
    ├── DailyAllowanceTracker.kt
    ├── WeeklyReportService.kt
    ├── BreakEnforcer.kt
    ├── FocusInsightsService.kt
    ├── BackupService.kt
    ├── AutoBackupService.kt
    ├── HostsBlocker.kt
    └── PrivacyPolicyService.kt
```

---

## Windows compatibility

Windows support is retained while the Linux port is completed. The Windows
installer and Microsoft Store identity are kept here for contributors working
on that path; they are not the Linux distribution identity.

| Field | Value |
|-------|-------|
| Identity Name | `TBTechs.FocusFlowDeepFocusAppBlocker` |
| Publisher | `CN=E08824C8-6F22-4DC2-8025-DD8C707E2BE9` |
| Version | `1.0.6.0` |
| Display Name | `FocusFlow — Deep Focus & App Blocker` |
| Publisher Display Name | `TBTechs` |

---

## Links

- **Website**: https://focusflowpc.pages.dev/
- **Privacy Policy**: https://focusflowpc.pages.dev/privacy-policy/
- **Terms of Service**: https://focusflowpc.pages.dev/terms-of-service/
- **Android version**: https://focusflowapp.pages.dev
