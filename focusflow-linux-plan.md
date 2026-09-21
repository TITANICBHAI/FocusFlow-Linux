# FocusFlow — Linux Migration Plan

**Based on actual code analysis of the uploaded zip. Every finding below is grounded in what I read.**

---

## Quick answer to your workflow question

**Do NOT replace Windows code during the temporary dual-platform period. Add Linux branches alongside it.**

The codebase already does this correctly — `if (isWindows)` / `if (isLinux)` guards are everywhere. Keep that pattern through Linux release hardening so the current Windows release remains usable. The long-term direction is Linux-only, but removing Windows paths is a separate post-readiness migration with its own compatibility, data, packaging, and release plan. Do not mix that removal into individual Linux tasks.

---

## What's already done (good news first)

These areas have real Linux code in place, not just stubs:

| Area | Status | Files |
|------|--------|-------|
| Platform detection | ✅ Done | `Platform.kt`, `WinApiBindings.kt` — `isLinux`, `isWayland`, `isX11`, `hasXdotool` all computed |
| Foreground window detection | ⚠️ Partial | `WinEventHook.kt` — xdotool is usable on X11/XWayland; the current Wayland `wmctrl -lp` fallback lists windows but does not prove which one is foreground |
| Process kill | ✅ Done | `WinApiBindings.kt` — `killProcessByPid/Name` uses `ProcessHandle.destroyForcibly()` on Linux |
| Network blocking | ⚠️ Partial | `NetworkBlocker.kt` — iptables attempts use pkexec, but `addRule()` reports success before the asynchronous command is verified |
| Hosts file blocking | ⚠️ Partial | `HostsBlocker.kt` — Linux path and atomic writes exist, but ordinary users currently receive `NoAdmin` when `/etc/hosts` is not writable; there is no pkexec write path |
| App scanning | ✅ Done | `InstalledAppsScanner.kt` — scans `/usr/share/applications` + `~/.local/share/applications` `.desktop` files |
| Keyboard hook | ✅ Partial | `GlobalKeyboardHook.kt` — `XGrabKeyboard` on X11; **Wayland has no grab at all** (noted below) |
| Watchdog | ⚠️ Partial | `WatchdogInstaller.kt` — systemd user timer is generated, but systemd availability, relaunch correctness, and cleanup require device verification |
| Autostart | ⚠️ Partial | `WindowsStartupManager.kt` — `.desktop` is written, but packaged/AppImage/development launch paths require install and login verification |
| Tool checker | ⚠️ Partial | `LinuxToolsChecker.kt` — probes xdotool, wmctrl, pkexec, notify-send; iptables is probed separately from the Compose screen |
| Setup screen | ⚠️ Partial | `LinuxSetupScreen.kt` — install instructions and async tool cards exist; the iptables probe still runs synchronously during composition |
| Nuclear mode | ✅ Done | `NuclearMode.kt` — Linux escape-process list, ProcessHandle kills |
| Kiosk panel hide | ✅ Done | `FocusLauncherService.kt` — `xdotool search --class panel` + `windowunmap/windowmap` |
| System tray | ✅ Partial | `SystemTrayManager.kt` — AWT tray works on X11; graceful skip on Wayland |
| Build targets | ⚠️ Partial | `build.gradle.kts` and CI produce Deb/Rpm/AppImage candidates, but artifact presence, metadata, dependency, checksum, and install/upgrade gates are not strict yet |
| App icon in app | ✅ Done | `Main.kt` — loads `focusflow.png` on Linux |

---

## Readiness audit — 2026-09-21

This audit was performed against the current source, workflows, installer scripts,
tests, and release documents after the ZIP comparison. The existing Gradle
compile/test check passed, but no real Linux display session or privileged
enforcement test was available in this environment. The items below are the
remaining gates for calling Linux release-ready.

### A. Enforcement correctness gates — highest priority

1. **Implement a real Linux privilege path for `/etc/hosts`.** Either write
   through a tightly constrained helper invoked by `pkexec`, or use another
   documented privileged mechanism. It must preserve the atomic-write behavior,
   reject unsafe domain input, report cancellation/failure, and restore the
   original file on unblock and shutdown.
2. **Make Linux firewall status truthful.** `NetworkBlocker.addRule()` currently
   registers intent and returns `true` before the daemon thread knows whether
   `iptables` succeeded. Return or expose a pending/failed state, verify the
   tagged rule, and make cleanup retryable.
3. **Move every Linux tool probe off the Compose thread.** Add `iptables` to
   `LinuxToolsChecker.checkAll()` and have `LinuxSetupScreen` consume that single
   asynchronous result.
4. **Add bounded subprocess execution.** `pkexec`, `nslookup`, `resolvectl`,
   `iptables`, `wmctrl`, and `xdotool` calls need timeouts and explicit logging
   so a dismissed authentication prompt or broken desktop session cannot stall
   enforcement or shutdown.
5. **Decide and document the DNS scope.** Test systemd-resolved, NetworkManager,
   nscd, and a resolver with no cache service. Do not claim that a hosts-file
   change immediately affects every application or DoH-enabled browser.

### B. Display-session and desktop-environment gates

1. Test X11 on at least one Ubuntu/Debian desktop and one Fedora/Arch-style
   environment. Verify the global keyboard hook, foreground process detection,
   overlay raising, panel cleanup, tray, and notifications.
2. Test native Wayland on GNOME and KDE/Plasma. Verify the reduced guarantee:
   process blocking, overlay attempt, notifications, cleanup, and honest UI
   messaging work; global keyboard suppression and compositor panel hiding are
   explicitly not claimed.
3. Test XWayland applications from a native Wayland session separately from
   native Wayland applications. Confirm FocusFlow does not mistake an arbitrary
   `wmctrl -lp` window for the active foreground window.
4. Test one lightweight desktop/panel environment such as XFCE or LXQt because
   the Linux kiosk panel path is intentionally desktop-specific.
5. Test multi-monitor layouts, negative monitor coordinates, fractional scaling,
   display hot-plug, lock/unlock, suspend/resume, and a screen rotation if
   available. The overlay must close and restore cleanly in every case.

### C. Packaging and installation gates

1. Make the Linux CI workflow fail when a required `.deb` or `.rpm` is missing,
   when dependency injection fails, or when the AppImage fallback cannot produce
   an artifact. Do not treat a warning-only artifact step as a release pass.
2. Add package metadata checks: version matches Gradle, desktop entry has a valid
   `Exec` and icon, dependencies are correct, package architecture is explicit,
   and the installed launcher starts from a clean user account.
3. Test `.deb` install, upgrade, launch, autostart, uninstall, and cleanup on
   Debian/Ubuntu. Repeat the equivalent `.rpm` flow on Fedora if that artifact is
   shipped.
4. Test the AppImage from a directory containing spaces, with and without
   `xdotool`, `wmctrl`, `notify-send`, and `pkexec`. Verify the desktop entry
   icon path, user-local launcher, upgrade replacement, and manual uninstall.
5. Fix `install.sh` release provenance before advertising it: repository/release
   URLs must match the actual project, downloads need checksum or signature
   verification, temporary files need cleanup on failure, and repeated installs
   must be idempotent.
6. Finish the AUR package before publishing it: replace the placeholder
   maintainer, generate `.SRCINFO`, use a real release checksum instead of
   `SKIP`, and test `makepkg`, install, upgrade, and uninstall on Arch.

### D. Functional and recovery gates

1. Verify installed app discovery and real icons for native packages, Flatpak,
   Snap, user `.desktop` files, absolute `Icon=` paths, themed icons, and
   `Exec=` field codes.
2. Verify process blocking for a normal application, a child-process launcher,
   a Flatpak app, and an application with a different desktop-file ID. Record
   what cannot be identified under native Wayland.
3. Verify Nuclear Mode covers the user-facing GNOME/KDE/XFCE task managers and
   terminals without killing the compositor, display server, D-Bus, input
   stack, or FocusFlow itself.
4. Start and end sessions repeatedly, force-kill FocusFlow during an active
   session, and confirm hosts entries, iptables rules, overlay visibility,
   panels, watchdog units, and autostart state are restored or clearly reported.
5. Verify database migration, backup/restore, locale/time-zone changes, clock
   adjustment, sleep/wake, and application restart do not corrupt session or
   enforcement state.
6. Verify the app remains usable without root, without optional desktop tools,
   without a systemd user session, and when `pkexec` authentication is cancelled.

### E. Security and release gates

1. Add tests for shell-argument safety around process names, domain names,
   desktop-file `Exec=` values, and paths containing spaces or shell metacharacters.
2. Confirm all subprocesses use argument arrays or safe quoting, all privileged
   helpers accept only allowlisted operations, and no user-controlled value is
   interpolated into a shell command.
3. Confirm telemetry is disabled without consent and no webhook or secret is
   embedded in Linux packages, installers, logs, or crash paths.
4. Run the full Linux test suite, package validation, dependency audit, and
   Windows build/enforcement regression checks before changing any remaining
   checklist item to complete.

## What still needs doing — by priority

---

The detailed findings in the historical phase sections below are retained as
implementation rationale. They are not a second status source: the current
status table above and the readiness audit are authoritative. Where a section
still says “What's wrong” or “Fix”, treat it as the original finding and use
the current status table for what remains.

### PHASE 1 — Build blockers (do these FIRST, nothing runs without them)

#### 1.1 — Missing `focusflow.png` resource

**What's wrong:** `build.gradle.kts` line:
```
iconFile.set(project.file("src/main/resources/focusflow.png"))
```
And `Main.kt`:
```kotlin
val iconRes = if (IS_LINUX) "focusflow.png" else "focusflow_256.png"
```
But the zip only has `focusflow_256.png` and `focusflow_logo_nobg.png`. No `focusflow.png`. Linux build will either fail at package time or show no icon at runtime.

**Fix:** Copy/rename `focusflow_256.png` → `focusflow.png` in `src/main/resources/`. One file copy — nothing else changes.

#### 1.2 — Stray React/video files in the repo

**What's wrong:** The zip contains these files that have nothing to do with FocusFlow:
```
src/App.tsx
src/main.tsx
src/index.css
src/components/video/VideoTemplate.tsx
src/components/video/video_scenes/Scene1–5.tsx
```
These are from a completely different React video project. They will cause confusion (and possibly build warnings) if left in.

**Fix:** Delete them from the repo. Nothing in the Kotlin/Gradle build references them.

---

### PHASE 2 — Core blocking (highest user-visible impact)

#### 2.1 — App icon extraction on Linux

**What's wrong:** `AppIconExtractor.kt` uses `FileSystemView` as the Linux fallback, which gives only a generic Java file icon — not the actual app icon. Every blocked app will show a blank/generic icon in the AppBlocker UI.

**Fix:** Implement XDG icon lookup in `AppIconExtractor.doExtract()`:
```
Linux path:
1. Read the .desktop file for the app (you already have the path from InstalledAppsScanner)
2. Parse the `Icon=` field (e.g. `Icon=firefox`, `Icon=/usr/share/pixmaps/vlc.png`)
3. If it's an absolute path → load directly
4. If it's a name → search in /usr/share/icons/<theme>/<size>/apps/<name>.png
   and ~/.local/share/icons/<name>.png as fallback
5. Convert to ImageBitmap
```
This is ~60 lines of Kotlin. Use `Dispatchers.IO` — disk reads.

#### 2.2 — InstalledAppsScanner: process name matching on Linux

**What's wrong:** The scanner reads `.desktop` files and builds a `ScannedApp` list, but the `processName` field used to match against running processes might not align with what `ProcessHandle` returns. Example: desktop file has `Exec=/usr/bin/firefox %U`, running process is `firefox`. The current code strips path and args correctly in some places but this needs end-to-end verification.

**Fix:**
- In `scanLinuxDesktopFiles()`: strip the absolute path prefix AND any `%U`, `%F`, `%i`, `%c`, `%k` arguments from the `Exec=` value before storing it as `processName`
- In `enforceBlock()` inside `ProcessMonitor`: verify the comparison is case-insensitive and handles both the binary name and full command path

**How to verify:** Add a live running processes dump to `LinuxSetupScreen` (debug-only) showing what `ProcessHandle.allProcesses()` reports for process names, then compare against what `scanLinuxDesktopFiles()` returns.

---

### PHASE 3 — Kiosk mode hardening (security layer)

#### 3.1 — Keyboard grab on Wayland (critical security gap)

**What's wrong:** `GlobalKeyboardHook.kt` calls `XGrabKeyboard` on X11 — that works. On Wayland, the comment in the code says:
```
// TODO: If xdg-desktop-portal or kiosk-shell is available, integrate
```
Currently, on Wayland there is NO keyboard grab at all. A user in kiosk mode on Wayland can freely Alt+Tab, use keyboard shortcuts, and reach the panel.

**Fix options (pick one):**
1. **D-Bus org.freedesktop.portal.Inhibit** — `xdg-desktop-portal` exposes a `RequestInhibit` call that can suppress keyboard/idle. Available on most modern GNOME/KDE setups. Call it via `ProcessBuilder("gdbus", "call", ...)` or via a D-Bus library.
2. **kiosk-shell / compositor-specific** — GNOME Kiosk, cage compositor, or `mutter --display-server` can lock to one app but requires root/compositor integration — too invasive.
3. **Document the limitation** — Add a clear warning in `LinuxSetupScreen` that Wayland kiosk mode has reduced keyboard enforcement. Still enforce via process kills + overlay.

Recommendation: implement option 1 for GNOME Wayland (most common), document the limitation for other Waylandcompositors.

#### 3.2 — Registry lockdown Linux equivalent

**What's wrong:** `RegistryLockdown.kt` disables Windows Task Manager (`DisableTaskMgr`) and logoff during sessions. On Linux, `if (!isWindows) return` — the function is a no-op. There is no Linux equivalent currently.

**What's possible on Linux:**
- **GNOME:** `gsettings set org.gnome.desktop.lockdown disable-log-out true` and disabling `System Monitor` via `xdotool`/process kill
- **Generic:** Kill `gnome-system-monitor`, `xfce4-taskmanager`, `ksysguard` etc. on detection — this is already covered by `NuclearMode.linuxEscapeProcesses`
- **Nothing fully equivalent to DisableTaskMgr** exists as a cross-distro mechanism

**Fix:** 
- Accept the limitation explicitly — document it in `EnforcementLog` on Linux startup
- The process-kill path in `NuclearMode` already covers killing terminal emulators and system monitors
- Add `gnome-system-monitor`, `xfce4-taskmanager`, `ksysguard`, `plasma-systemmonitor` to `NuclearMode.linuxEscapeProcesses` if not already there

#### 3.3 — FloatingBlockOverlay on Wayland always-on-top

**What's wrong:** `FloatingBlockOverlay.kt` has `if (!isWindows && !isLinux) return` — so Linux is included. But AWT always-on-top windows (`window.isAlwaysOnTop = true`) are not guaranteed to stay above all other windows on Wayland. Some compositors honor it, some don't.

**Fix:**
- On X11: use `xdotool windowraise` after showing the overlay as a belt-and-suspenders
- On Wayland: attempt the `_NET_WM_FULLSCREEN_MONITORS` hint and `_NET_WM_STATE_ABOVE` via JNA X11 (works under XWayland)
- Fall back to aggressive process killing (which already happens) when the overlay doesn't stay on top

---

### PHASE 4 — System integration

#### 4.1 — System tray notifications on Wayland

**What's wrong:** `SystemTrayManager` uses `java.awt.SystemTray` which doesn't work on native Wayland (only X11/XWayland). The code already logs a warning and skips. But `NotificationService.kt` routes ALL notifications through `SystemTrayManager.showNotification()` — so on native Wayland, no notifications reach the user at all.

**Fix:** Add a `notify-send` fallback in `SystemTrayManager.showNotification()`:
```kotlin
fun showNotification(title: String, body: String, ...) {
    if (trayIcon != null) {
        trayIcon.displayMessage(title, body, type)
    } else if (isLinux && LinuxToolsChecker.isInstalled("notify-send")) {
        ProcessBuilder("notify-send", "-a", "FocusFlow", title, body).start()
    }
}
```
`notify-send` is already probed by `LinuxToolsChecker` and listed in `LinuxSetupScreen`. Wire it up.

#### 4.2 — WindowsStartupManager.resolveExePath() on Linux

**What's wrong:** The autostart `.desktop` file's `Exec=` line needs the path to the actual binary. `resolveExePath()` has a Linux branch but the path it resolves will differ depending on how the app was installed:
- `.deb` install → `/usr/bin/focusflow` or `/opt/focusflow/bin/focusflow`
- AppImage → wherever the user put the `.AppImage` file
- Running from build dir → `./build/compose/binaries/...`

**Fix:** In `WindowsStartupManager.resolveExePath()` Linux branch:
1. Try `ProcessHandle.current().info().command()` — this gives the actual running binary path
2. If that's a JVM path (ends in `java`), look for the wrapper script in the same directory
3. Write that path into the `.desktop` file

Also verify the `.desktop` file template includes `StartupWMClass=focusflow` so the taskbar correctly associates the window with the launcher entry.

#### 4.3 — FocusLauncherService panel hide: DE coverage

**What's current:** The kiosk panel hide uses `xdotool search --class panel`. This works for XFCE (`xfce4-panel`), LXPanel, and similar. It does NOT work for:
- GNOME Shell (panels are baked into the compositor, not separate windows)
- KDE Plasma (panels are also compositor-integrated)
- Wayland compositors (xdotool window operations fail)

**Fix:** Branch per DE:
```kotlin
val de = System.getenv("XDG_CURRENT_DESKTOP")?.lowercase() ?: ""
when {
    de.contains("gnome") -> hideGnomePanel()  // gdbus call to gnome-shell extension or gsettings
    de.contains("kde")   -> hideKdePanel()    // qdbus call to plasma-shell
    else                 -> hideViaXdotool()   // existing xdotool path
}
```
For v1 it's acceptable to document that "panel hiding works best on XFCE/LXPanel in X11 session."

---

### PHASE 5 — Polish and distribution

#### 5.1 — OsBanner Linux context

**What to check:** `OsBanner.kt` shows a Windows-specific "run as administrator" banner. Verify that on Linux it either doesn't show (guarded by `isWindows`) or shows the correct Linux message ("FocusFlow needs pkexec for network blocking — see Linux Setup").

Look for `isWindows` guards in `OsBanner.kt` — if they're there, it's already handled. If not, add them.

#### 5.2 — Flatpak and Snap app detection

**What's missing:** `InstalledAppsScanner.scanLinuxDesktopFiles()` only reads from `/usr/share/applications` and `~/.local/share/applications`. Flatpak apps install to `~/.local/share/flatpak/exports/share/applications/` and Snap apps to `/var/lib/snapd/desktop/applications/`. These are a large percentage of apps on Ubuntu.

**Fix:** Add to `scanLinuxDesktopFiles()`:
```kotlin
val dirs = listOf(
    File("/usr/share/applications"),
    File("${home}/.local/share/applications"),
    File("${home}/.local/share/flatpak/exports/share/applications"),  // Flatpak user
    File("/var/lib/flatpak/exports/share/applications"),               // Flatpak system
    File("/var/lib/snapd/desktop/applications")                        // Snap
)
```

#### 5.3 — CI Linux test and package gates

Linux workflows now exist, but they are not yet a complete release gate. The
test workflow intentionally runs without `xdotool` and only targets the
enforcement test package. The package workflow builds Deb/Rpm/AppImage
candidates separately, and some repackaging or missing-artifact paths currently
warn instead of failing. Extend the existing workflows rather than creating
duplicates:

1. Run the full Linux unit test suite on `ubuntu-latest`.
2. Keep a headless/no-optional-tools test job for graceful degradation.
3. Add a separate package-validation job that requires every shipped artifact.
4. Inspect package version, architecture, dependencies, desktop entry, icon,
   launcher, and file contents.
5. Upload reports and fail the workflow if required validation is incomplete.

#### 5.4 — Release installer validation

The package workflows do not prove that a user can install and remove the
application safely. Validate the `.deb`, `.rpm`, AppImage, `install.sh`, and
AUR paths on clean disposable environments. Align repository URLs, replace
placeholder AUR metadata, generate a real checksum, and verify downloads before
installation. Test repeat install, upgrade, launch, autostart, and uninstall.

#### 5.5 — AppImage auto-update (future)

The `build.gradle.kts` targets `TargetFormat.AppImage`. AppImages don't have a built-in update mechanism. If you want auto-update on AppImage, look into `appimageupdatetool` or `gh-releases-zsync`. This is a future item — ship the AppImage first.

---

## The workflow: how to actually do each change

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Rule: NEVER delete or break a Windows code path.                        │
│ Always add Linux alongside. Keep both compiling and passing on each PR. │
└─────────────────────────────────────────────────────────────────────────┘
```

For every item above, the pattern is:

**If a file already has `if (isWindows)` / `if (isLinux)` guards** (most files):
→ Go directly to the Linux branch and improve/fix it. Don't touch the Windows branch.

**If a file has Windows-only code with NO platform guard yet** (rare — `RegistryLockdown` is already guarded):
→ Step 1: Add `if (!isWindows) return` at the top as a guard
→ Step 2: Implement the Linux branch below it
→ Step 3: Verify Windows build still passes

**When do you do the Windows cleanup?**
You don't — unless you ever decide to drop Windows and become Linux-only. That is a completely separate decision made after Linux is stable and shipped. Files like `WinApiBindings.kt` (the JNA Windows-specific interfaces) don't hurt anything on Linux because the JNA load happens lazily and the code paths are guarded. Leave them alone.

---

## File-by-file status summary

| File | Linux status | What's needed |
|------|-------------|---------------|
| `Platform.kt` | ✅ Done | Nothing |
| `WinApiBindings.kt` | ✅ Done | Nothing (JNA Windows interfaces won't load on Linux, but they're guarded) |
| `LinuxToolsChecker.kt` | ⚠️ Partial | Add iptables to the shared async probe and keep notification/tool checks off the UI thread |
| `WinEventHook.kt` | ⚠️ Partial | Verify Wayland poller accuracy; current wmctrl fallback does not identify the active window with certainty |
| `ProcessMonitor.kt` | ✅ Done | Verify process name matching end-to-end |
| `GlobalKeyboardHook.kt` | ⚠️ Partial | Native Wayland reduced protection is documented; optional compositor integration remains future work |
| `FloatingBlockOverlay.kt` | ⚠️ Partial | X11/XWayland raise path exists; test always-on-top behavior on native Wayland |
| `NetworkBlocker.kt` | ⚠️ Partial | Verify asynchronous iptables results before reporting success; add timeouts and cleanup status |
| `HostsBlocker.kt` | ⚠️ Partial | Implement the documented privileged Linux write path; current canWriteHostsFile gate rejects ordinary users without it |
| `RegistryLockdown.kt` | ⚠️ Guarded | No Linux equivalent; add escape-process coverage to NuclearMode instead |
| `WatchdogInstaller.kt` | ⚠️ Partial | Verify systemd user availability, relaunch command, duplicate prevention, and uninstall on real distributions |
| `WindowsStartupManager.kt` | ⚠️ Partial | Verify resolveExePath() covers AppImage, Deb/Rpm, and development paths |
| `NuclearMode.kt` | ✅ Done | Common GNOME/KDE/XFCE/LXQt user escape tools are covered; verify safely on devices |
| `VpnBlocker.kt` | ✅ Done | Linux process list present |
| `AppIconExtractor.kt` | ✅ Done | XDG icon lookup exists; verify native, themed, Flatpak, and Snap icons on devices |
| `InstalledAppsScanner.kt` | ✅ Done | Flatpak/Snap directories and `Exec=` normalization exist; verify end-to-end process matching |
| `SystemTrayManager.kt` | ✅ Done | `notify-send` fallback exists; verify notifications on native Wayland |
| `NotificationService.kt` | ✅ Done | Routes through the tray/notification fallback; verify delivery by session type |
| `FocusLauncherService.kt` | ⚠️ Partial | GNOME/KDE/native Wayland limitations are documented; verify cleanup on supported panels |
| `LinuxSetupScreen.kt` | ⚠️ Partial | Wayland keyboard limitation notice exists; move the iptables probe off composition |
| `WindowsSetupScreen.kt` | ✅ Gated | Verify it's gated by `IS_WINDOWS` in navigation |
| `OsBanner.kt` | ❓ Check | Verify `isWindows` guard on the admin banner |
| `Main.kt` | ✅ Done | Linux icon resource is present; verify packaged/runtime icon on device |
| `build.gradle.kts` | ⚠️ Partial | Validate all required package artifacts and metadata in CI |
| React/TSX files | ✅ Removed | Confirmed unrelated files are no longer part of the Linux build |

---

## Suggested order of execution

```
Phase 1 (do today — unblocks everything):
  └─ Copy focusflow_256.png → focusflow.png
  └─ Delete src/App.tsx, src/main.tsx, src/index.css, src/components/video/

Phase 2 (core blocking — make the app actually useful on Linux):
  └─ AppIconExtractor: XDG icon lookup
  └─ InstalledAppsScanner: Exec= stripping + Flatpak/Snap dirs
  └─ ProcessMonitor: end-to-end process name match verification

Phase 3 (kiosk hardening):
  └─ GlobalKeyboardHook: D-Bus Inhibit on Wayland OR document limitation
  └─ NuclearMode: add GNOME/KDE task managers to escape list
  └─ FloatingBlockOverlay: Wayland always-on-top test + xdotool raise

Phase 4 (system integration):
  └─ SystemTrayManager: notify-send fallback
  └─ WindowsStartupManager: verify resolveExePath() on AppImage/deb
  └─ FocusLauncherService: GNOME/KDE panel hide attempt

Phase 5 (remaining distribution gates):
  └─ Make CI tests and required package artifacts strict
  └─ Validate Deb/Rpm/AppImage/install.sh/AUR metadata and lifecycle
  └─ Manual test on X11, GNOME Wayland, KDE Wayland, and a lightweight DE

Phase 6 (remaining enforcement hardening):
  └─ Implement the real Linux hosts-file privilege path
  └─ Make iptables status truthful and bounded
  └─ Move all Linux probes off the UI thread
  └─ Add recovery, shell-safety, and privileged integration coverage

Phase 7 (future — after ship):
  └─ AppImage auto-update
  └─ Wayland native kiosk/compositor integration
  └─ Snap packaging
```

---

*Confidence: 0.85 — all findings are from direct code reads; the "what's still wrong" items are based on reading the actual code paths, not assumptions. The only uncertainty is in runtime behavior (e.g. whether xdotool raise actually keeps the overlay on top on specific Wayland compositors) — that requires live testing on real Linux machines.*
