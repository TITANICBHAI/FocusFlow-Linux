# Linux Process Safety and Anti-Self-Termination Allowlist

## Status

**Documentation and proposal only.**

This document records the current Linux safety behavior and the list that should
be reviewed before changing process selection or termination code. It does not
change the runtime allowlists, picker behavior, or enforcement behavior.

## Short answer

FocusFlow already has a partial Linux safe list, but it is not one unified
contract:

1. `ProcessMonitor.linuxLauncherSafeProcesses` is used by **Focus Launcher**
   kiosk enforcement. It is excluded from the inverse allowlist kill sweep.
2. `InstalledAppsScanner.linuxSystemIgnore` hides many infrastructure processes
   from the **Pick Apps** catalog. This is a display/discovery filter, not a
   kill-safety guarantee.
3. `NuclearMode.linuxEscapeProcesses` is an intentional **escape-tool
   blocklist**, not a system-safe allowlist. Nuclear Mode only targets names in
   that explicit set and currently excludes FocusFlow's own PID from its Linux
   process scan.

Therefore, the answer is **yes for part of Focus Launcher, no as a complete
cross-feature safety boundary**.

## Current behavior by feature

### Pick Apps / shared Linux picker

The running-app catalog filters out entries in
`InstalledAppsScanner.linuxSystemIgnore`. This prevents many kernel, desktop,
audio, network, sandbox, and FocusFlow processes from appearing as normal
choices.

The current ignore set includes, among others:

- Core/kernel names: `systemd`, `init`, `kthreadd`, `kworker`, `watchdog`,
  `jbd2`, `ext4`, `kauditd`, and related kernel workers.
- Session and messaging infrastructure: `dbus-daemon`, `dbus-broker`,
  `gdm`, `sddm`, `lightdm`, `xsession`.
- Display and window management: `Xorg`, `Xwayland`, `mutter`, `kwin_x11`,
  `kwin_wayland`, `weston`, `xfwm4`, `openbox`, `fluxbox`.
- Desktop shell components and file managers: `panel`, `lxpanel`, `picom`,
  `nautilus`, `nemo`, `thunar`, `dolphin`, `pcmanfm`.
- Network and authorization: `NetworkManager`, `wpa_supplicant`, `dhcpcd`,
  `dhclient`, `polkitd`, `polkit`, `pk-launch`.
- Sandbox helpers: `bwrap`, `xdg-dbus-proxy`.
- FocusFlow runtime names: `focusflow`, `java`, `kotlin`.

However, `createManualProcessEntry()` validates the process-name shape but does
not reject protected names. A user can therefore type a protected process
manually even when it is hidden from the discovered-app list. Any feature that
accepts manual entries must use the same protection policy as discovered
entries.

### Focus Launcher

Focus Launcher uses inverse enforcement: every process not in the selected app
set is a kill candidate unless it is in
`ProcessMonitor.linuxLauncherSafeProcesses`.

The current Linux launcher safe list is:

#### FocusFlow

- `java`
- `focusflow`

#### Display server, compositor, and window manager

- `xorg`
- `xwayland`
- `wayland`
- `gnome-shell`
- `kwin_x11`
- `kwin_wayland`
- `xfwm4`
- `mutter`
- `muffin`
- `compiz`
- `openbox`

#### Login, session, IPC, and authorization

- `systemd`
- `systemd-logind`
- `systemd-user-session`
- `dbus-daemon`
- `dbus-launch`
- `gdm`
- `gdm-session-worker`
- `sddm`
- `sddm-greeter`
- `lightdm`
- `lightdm-gtk-greeter`
- `polkitd`
- `polkit-gnome`

#### Desktop services, storage, power, and networking

- `at-spi-bus-launcher`
- `at-spi2-registry`
- `gvfs`
- `gvfsd`
- `udisks`
- `udisksd`
- `upower`
- `networkmanager`
- `wpa_supplicant`

#### Input and remote access

- `libinput-daemon`
- `input`
- `inputlock`
- `sshd`

#### Audio

- `pulseaudio`
- `pipewire`
- `wireplumber`
- `alsa`
- `alsa-sink`
- `alsa-source`
- `jackd`

#### Accessibility

- `orca`
- `speech-dispatcher`
- `onboard`
- `xvkbd`

This list protects the named process, not necessarily every process in the
desktop session that is functionally critical. It also matches bare executable
names, so aliases, renamed binaries, wrapper processes, and multiple installed
versions need explicit handling.

### Nuclear Mode

Nuclear Mode does not use the Focus Launcher safe list. It scans only for the
explicit Linux escape-process set and terminates matching processes:

- Terminal emulators: `gnome-terminal`, `konsole`, `xfce4-terminal`, `xterm`,
  `terminator`, `mate-terminal`, `lxterminal`, `qterminal`, `tilix`,
  `alacritty`, `kitty`, `kgx`, `ptyxis`, `foot`, `wezterm`, `st`, `yakuake`,
  and other listed terminal names.
- Shells: `bash`, `zsh`, `sh`, `dash`, `fish`, `ksh`, `csh`, `tcsh`, `nu`,
  and `nushell`.
- System monitors: GNOME, KDE, XFCE, LXQt, MATE, `htop`, `btop`, `top`,
  `glances`, and other listed monitor names.
- Configuration editors: `dconf-editor`, `gconf-editor`.
- Process managers: `procman`, `lxtask`, `xfce4-taskmanager`,
  `lxqt-taskmanager`.
- Run-command launchers: `gnome-run`, `krunner`, `xfce4-appfinder`, `rofi`,
  and `dmenu`.
- Package managers: `gnome-software`, `discover`, `pamac-manager`,
  `synaptic`, `aptitude`, and `dpkg`.

This targeted design is safer than a broad allowlist sweep. The existing Linux
tests also assert that `gnome-shell`, `kwin_wayland`, `Xwayland`, `systemd`,
and `dbus-daemon` are not in the Nuclear Mode escape set.

The current Linux scan excludes FocusFlow's own PID. That prevents Nuclear Mode
from killing the current JVM merely because its executable name happens to
match a monitored name. It does not by itself protect a second FocusFlow
instance, a helper process, or a FocusFlow process reached through a different
identity.

## Main risks to resolve

### 1. Manual picker entries can bypass discovery filtering

Hiding a process from the catalog is not enough. Manual process entry can
reintroduce:

- FocusFlow itself (`focusflow`, `java`, or a packaged JVM name).
- The active display server or compositor.
- D-Bus, the login/session manager, or the input stack.
- A desktop shell or panel needed to recover the session.
- A process that is safe in one feature but destructive in another.

### 2. FocusFlow can be selected as a target

The launcher sweep excludes its current PID, but the ordinary targeted block
path does not show an equivalent protected-target check before calling the kill
function. A stored rule or manual entry that resolves to FocusFlow's process
identity could therefore create a direct self-termination path.

The protection must cover both discovered and manually entered references and
must happen before the rule is persisted or enforced.

### 3. One process name can represent several identities

Protecting `java` protects every Java process, while blocking `java` can affect
FocusFlow and unrelated Java applications. Similarly, a desktop launcher,
Flatpak wrapper, sandbox helper, application process, and child helper may all
have different names.

The safe policy must use process identity and ownership where available, not
only a display name or one bare process name.

### 4. Desktop shells differ across Linux environments

The current list covers several common environments but is not a complete
desktop matrix. A static list cannot safely predict every compositor, panel,
input method, portal, accessibility bridge, or user-selected window manager.

The active session's display server, compositor/window manager, shell/panel,
input method, and FocusFlow process tree should be discovered dynamically and
protected for the session. Static names should remain a fallback, not the only
defense.

### 5. Focus Launcher and Nuclear Mode have different goals

Focus Launcher needs to preserve the desktop session while killing everything
outside the selected app set. Nuclear Mode needs to kill known escape tools
without broad process destruction.

They should share a protected-process contract, but they must not be collapsed
into one list:

- Focus Launcher: protected system/session identities plus selected apps.
- Nuclear Mode: explicit escape blocklist plus protected FocusFlow/session
  identities.
- Pickers: deny protected targets at discovery, manual entry, resolution, and
  save time.

## Proposed protection layers

These are the proposed policy layers for implementation review.

### Layer A — Hard protected identities

Never allow a user-selected blocking target to resolve to:

- The current FocusFlow PID.
- The FocusFlow executable path and packaged launcher identity.
- FocusFlow child/helper processes that are owned by the current session.
- The active process supervisor or JVM wrapper when it is part of the
  FocusFlow process tree.

This protection should be PID/path-aware and should not rely only on the
literal names `java` or `focusflow`.

### Layer B — Session survival protection

In Focus Launcher mode, protect the currently active:

- Init/session manager.
- Display server and compositor/window manager.
- Desktop shell and panel.
- D-Bus broker and user-session bus.
- Input method and accessibility bridge.
- Audio session services.
- Desktop portals needed for dialogs and sandboxed applications.
- Network/session authorization services where killing them would strand the
  user or prevent recovery.

Candidate names that are currently missing or need environment-specific
verification include:

- `dbus-broker`
- `xdg-desktop-portal`
- `xdg-document-portal`
- `xdg-permission-store`
- `gnome-session-binary`
- `plasmashell`
- `xfce4-session`
- `lxqt-session`
- `cinnamon`
- `mate-session`
- `mate-panel`
- `ibus-daemon`
- `fcitx`
- `fcitx5`
- `kded5`
- `kded6`

These should not be blindly added as a permanent global list. The preferred
approach is to identify which of them are actually part of the current session
and protect only those processes, with a conservative static fallback.

### Layer C — Picker deny policy

The shared picker should mark protected entries as `Protected` or omit them
from selectable results, while keeping a clear explanation available to the
user. Manual entry must apply the same policy.

At minimum, the deny policy must cover:

- FocusFlow and its runtime/process tree.
- Every current launcher safe-list identity.
- The active display/session/input infrastructure.
- Nuclear Mode's own protected infrastructure exclusions.

An existing saved rule that becomes protected should not be silently deleted.
It should remain visible as a protected or invalid target and stop being
enforced until the user replaces it.

### Layer D — Runtime final check

Every kill path must perform a final protected-target check immediately before
termination:

- Normal process blocking.
- Timed/scheduled blocking.
- Daily allowance blocking.
- Focus session extra-app blocking.
- Focus Launcher foreground enforcement.
- Focus Launcher full-process sweep.
- Nuclear Mode termination.

This check must use the PID and resolved executable identity when available,
then apply the conservative name/path fallback. A picker check alone is not
sufficient because rules can be old, manually entered, migrated, or created by
another feature.

## Recommended review decisions before implementation

1. Confirm that `work/linux-process-safety-allowlist.md` is the authoritative
   design document for Linux process protection.
2. Decide whether protected entries should be hidden, visibly disabled, or
   shown with a `Protected` badge in Pick Apps.
3. Decide whether an existing protected rule is automatically disabled or
   remains enabled but skipped with a visible warning.
4. Define the supported desktop-session matrix for dynamic discovery:
   X11, GNOME Wayland, KDE Wayland, XFCE, LXQt, Cinnamon, MATE, and tiling
   window managers.
5. Decide whether `java` may remain a broad launcher-safe fallback or must be
   replaced with FocusFlow-owned PID/path identity.
6. Define recovery behavior if a platform cannot identify the active compositor,
   session shell, or input method. The safe default should be to refuse the
   destructive launcher sweep rather than guess.

## Verification required after implementation

The enforcement plan should add evidence for:

- Pickers rejecting FocusFlow and protected Linux infrastructure, including
  manual entry.
- Normal block rules never terminating FocusFlow's PID or process tree.
- Focus Launcher preserving the display server, compositor, shell/panel,
  D-Bus, input, audio, and recovery path.
- Nuclear Mode terminating escape tools while preserving FocusFlow and all
  session-critical infrastructure.
- X11, native Wayland GNOME, native Wayland KDE/Plasma, XFCE/LXQt, and at
  least one tiling-window-manager session where those environments are
  supported.
- Stale saved references remaining visible and understandable rather than
  being deleted or silently re-enabled.

Related existing tracker item:

- `ENF-22` — test Nuclear Mode escape routes without killing FocusFlow, the
  compositor, display server, D-Bus, or input stack.