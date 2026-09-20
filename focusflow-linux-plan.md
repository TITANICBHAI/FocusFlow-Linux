# FocusFlow — Linux Migration Plan

**Based on actual code analysis of the uploaded zip. Every finding below is grounded in what I read.**

---

## Quick answer to your workflow question

**Do NOT replace Windows code. Add Linux branches alongside it.**

The codebase already does this correctly — `if (isWindows)` / `if (isLinux)` guards are everywhere. Keep that pattern through the whole migration. The Windows app is live on the Store; you cannot break it. The only things you delete are the wrong files listed in Phase 1 below. Full Windows cleanup only happens if you ever decide to drop Windows entirely — that is not this migration.

---

## What's already done (good news first)

These areas have real Linux code in place, not just stubs:

| Area | Status | Files |
|------|--------|-------|
| Platform detection | ✅ Done | `Platform.kt`, `WinApiBindings.kt` — `isLinux`, `isWayland`, `isX11`, `hasXdotool` all computed |
| Foreground window detection | ✅ Done | `WinEventHook.kt` — xdotool on X11, wmctrl+/proc fallback on Wayland, 500ms poller |
| Process kill | ✅ Done | `WinApiBindings.kt` — `killProcessByPid/Name` uses `ProcessHandle.destroyForcibly()` on Linux |
| Network blocking | ✅ Done | `NetworkBlocker.kt` — iptables rules via pkexec, per-process IP tracking via `/proc/net/tcp` |
| Hosts file blocking | ✅ Done | `HostsBlocker.kt` — `/etc/hosts` path + pkexec write, nscd restart |
| App scanning | ✅ Done | `InstalledAppsScanner.kt` — scans `/usr/share/applications` + `~/.local/share/applications` `.desktop` files |
| Keyboard hook | ✅ Partial | `GlobalKeyboardHook.kt` — `XGrabKeyboard` on X11; **Wayland has no grab at all** (noted below) |
| Watchdog | ✅ Done | `WatchdogInstaller.kt` — systemd user timer, `.service` + `.timer` unit files written |
| Autostart | ✅ Done | `WindowsStartupManager.kt` — `~/.config/autostart/focusflow.desktop` written |
| Tool checker | ✅ Done | `LinuxToolsChecker.kt` — probes xdotool, wmctrl, pkexec, notify-send via `which` |
| Setup screen | ✅ Done | `LinuxSetupScreen.kt` — install instructions for all deps, per-tool status cards |
| Nuclear mode | ✅ Done | `NuclearMode.kt` — Linux escape-process list, ProcessHandle kills |
| Kiosk panel hide | ✅ Done | `FocusLauncherService.kt` — `xdotool search --class panel` + `windowunmap/windowmap` |
| System tray | ✅ Partial | `SystemTrayManager.kt` — AWT tray works on X11; graceful skip on Wayland |
| Build targets | ✅ Done | `build.gradle.kts` — Deb, Rpm, AppImage targets; linux{} block present |
| App icon in app | ✅ Done | `Main.kt` — loads `focusflow.png` on Linux |

---

## What still needs doing — by priority

---

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

#### 5.3 — CI Linux build step

**What's needed:** The `build.gradle.kts` already skips tests on Windows (`onlyIf { !os.contains("windows") }`). The three test files that exist (`NetworkBlockerLinuxTest.kt`, `WatchdogInstallerTest.kt`, `WinApiBindingsLinuxTest.kt`) are Linux-specific. They need a Linux CI runner to actually run.

Add a GitHub Actions workflow (or equivalent) step that:
1. Runs on `ubuntu-latest`
2. Installs `xdotool`, `wmctrl`, `iptables`
3. Runs `./gradlew test`
4. Optionally runs `./gradlew packageDeb` to verify the package build

#### 5.4 — AppImage auto-update (future)

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
| `LinuxToolsChecker.kt` | ✅ Done | Wire notify-send result to notification fallback |
| `WinEventHook.kt` | ✅ Done | Verify Wayland poller accuracy |
| `ProcessMonitor.kt` | ✅ Done | Verify process name matching end-to-end |
| `GlobalKeyboardHook.kt` | ⚠️ Partial | Wayland: add D-Bus Inhibit or document limitation |
| `FloatingBlockOverlay.kt` | ⚠️ Partial | Wayland: test always-on-top behavior, add xdotool raise |
| `NetworkBlocker.kt` | ✅ Done | Nothing |
| `HostsBlocker.kt` | ✅ Done | Nothing |
| `RegistryLockdown.kt` | ⚠️ Guarded | No Linux equivalent; add escape-process coverage to NuclearMode instead |
| `WatchdogInstaller.kt` | ✅ Done | Nothing |
| `WindowsStartupManager.kt` | ⚠️ Partial | Verify resolveExePath() covers AppImage path |
| `NuclearMode.kt` | ✅ Done | Add GNOME/KDE task managers to linuxEscapeProcesses |
| `VpnBlocker.kt` | ✅ Done | Linux process list present |
| `AppIconExtractor.kt` | ❌ Stub | Implement XDG icon lookup |
| `InstalledAppsScanner.kt` | ⚠️ Partial | Add Flatpak/Snap dirs; verify Exec= stripping |
| `SystemTrayManager.kt` | ⚠️ Partial | Add notify-send notification fallback |
| `NotificationService.kt` | ⚠️ Partial | Depends on tray fix above |
| `FocusLauncherService.kt` | ⚠️ Partial | GNOME/KDE panel fallback; document DE coverage |
| `LinuxSetupScreen.kt` | ✅ Done | Add Wayland keyboard limitation notice |
| `WindowsSetupScreen.kt` | ✅ Gated | Verify it's gated by `IS_WINDOWS` in navigation |
| `OsBanner.kt` | ❓ Check | Verify `isWindows` guard on the admin banner |
| `Main.kt` | ⚠️ Bug | `focusflow.png` reference but file doesn't exist in resources |
| `build.gradle.kts` | ⚠️ Bug | `focusflow.png` icon ref but file missing |
| React/TSX files | ❌ Wrong | Delete entirely — wrong project |

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

Phase 5 (distribution):
  └─ OsBanner: verify or add Linux privilege context
  └─ CI: add Linux test + package build step
  └─ Manual test on Ubuntu (X11), Ubuntu (Wayland), Fedora, Arch

Phase 6 (future — after ship):
  └─ AppImage auto-update
  └─ Wayland native kiosk (compositor integration)
  └─ Snap packaging
```

---

*Confidence: 0.85 — all findings are from direct code reads; the "what's still wrong" items are based on reading the actual code paths, not assumptions. The only uncertainty is in runtime behavior (e.g. whether xdotool raise actually keeps the overlay on top on specific Wayland compositors) — that requires live testing on real Linux machines.*
