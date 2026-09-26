# FocusFlow Linux App Discovery and Pickers Plan

## Purpose

Finish Linux installed-application discovery and make every app-selection flow
use the same Linux-aware catalog and picker.

This is a focused workstream. It does not remove Windows code, change the
enforcement engine, or declare the Linux release ready by itself.

## Status

- Overall: **Not started**
- Owner: FocusFlow implementation work
- Depends on: existing Linux platform detection and Compose UI
- Blocks: reliable Linux app blocking, task focus-app selection, and the later
  Windows-removal cleanup
- Execution mode: **Five batches; maximum seven tracker items per batch**
- Active batch: **APP-01–APP-07**

## Batch execution protocol

This plan is executed in small, ordered batches. Do not implement all 34 items
at once.

### Batch order

| Batch | Tracker IDs | Scope | Status |
|---|---|---|---|
| Batch 1 | APP-01–APP-07 | Catalog contract and source metadata | Not started |
| Batch 2 | APP-08–APP-14 | Refresh state and shared picker foundation | Not started |
| Batch 3 | APP-15–APP-21 | Manual entry and first screen integrations | Not started |
| Batch 4 | APP-22–APP-28 | Remaining screen integrations and compatibility | Not started |
| Batch 5 | APP-29–APP-34 | Cross-platform verification and AppImage decision | Not started |

### Strict tracker rules

1. A new agent receives exactly one batch and must not implement items outside
   that batch.
2. Read this plan, the current code, `replit.md`, and relevant memory before
   editing.
3. Keep the assigned tracker items in the same order unless a dependency makes
   that impossible.
4. Do not tick an item when work is merely started, compiled, or partially
   implemented.
5. Tick an item only after its implementation, focused verification, and
   acceptance evidence are complete.
6. Update the batch table status and the item checkbox in the same change.
7. If an item is blocked, leave it unchecked and record the blocker below the
   tracker rather than pretending it is complete.
8. Do not silently rewrite later tracker items to make the current batch appear
   complete.
9. At the end of a batch, report changed files, checks run, unchecked items,
   known limitations, and the next batch.
10. A batch is complete only when every assigned item is either checked with
    evidence or explicitly documented as blocked.

### Batch completion record

For each completed batch, add a short dated record here:

```text
Batch:
Date:
Completed tracker items:
Verification:
Blocked or deferred items:
Next batch:
```

The next agent must inspect the latest record before continuing.

## Current baseline

`InstalledAppsScanner` already discovers some Linux applications from desktop
files and separately discovers running processes. The current UI does not use
that information consistently:

- Focus Launcher mostly starts from existing rules and allowances.
- App Blocker has the strongest picker, but manual entry is still Windows
  oriented.
- Focus sessions have a simpler picker without full app metadata.
- Tasks use a remembered grid with no search or refresh.
- Settings only picks running applications.
- Schedules can search installed apps but force `.exe` for custom values.
- VPN rules have no installed-app picker and use Windows-oriented process
  examples.

The scanner is cached for the application process lifetime, so newly installed
applications do not appear until restart.

## Product decisions

### 1. Use an application identity, not only a process name

The catalog should distinguish:

- Human display name, such as `Firefox`
- Stable desktop/application ID, such as `org.mozilla.firefox`
- Desktop-file path
- Launch command
- Primary executable/process name
- Known process aliases
- Package/source type
- Icon reference or resolved icon
- Current running state and matching PIDs

The process name remains necessary for enforcement, but it must not be the
only identity stored by the picker.

### 2. Supported Linux sources

The first release of the catalog should support:

- `/usr/share/applications`
- `/usr/local/share/applications`
- `$XDG_DATA_HOME/applications`
- Every directory in `$XDG_DATA_DIRS`
- User and system Flatpak exported desktop files
- Snap desktop entries
- User-created desktop files
- Currently running processes that have no desktop entry

AppImage discovery should remain an explicit follow-up unless a reliable
desktop entry or configured application location is available. Scanning the
entire home directory for arbitrary `.AppImage` files is slow, noisy, and can
produce unsafe or duplicate entries.

### 3. Picker behavior

Every picker should support:

- Search by display name, process name, desktop ID, or package ID
- Installed versus Running filters
- Source badges: Native, Flatpak, Snap, Running-only, Manual
- App icon when available
- Running indicator
- Selected/blocked/limited/scheduled state
- Refresh/rescan
- Manual process entry where the feature requires it
- Existing references that are not currently installed
- Clear empty and permission-error states

The picker must not silently convert Linux process names to `.exe`.

## Implementation phases

### Phase A — Catalog contract

Define a shared catalog model and repository before changing individual
screens.

The proposed descriptor should contain enough information for the UI and
enforcement layers without forcing every screen to parse desktop files itself.
The exact Kotlin names can be chosen during implementation, but the contract
must represent:

- Stable ID
- Display name
- Process names/aliases
- Launch executable or command
- Desktop-file path
- Icon reference
- Source/package type
- Running PIDs
- Detection confidence

The repository should expose separate operations for:

- Read current catalog
- Read running applications
- Refresh catalog
- Resolve a stored rule back to a catalog entry
- Create a manual-process entry

### Phase B — Linux desktop-file parsing

Improve parsing to follow the relevant desktop-entry behavior:

- Localized `Name[...]` values
- `Icon=`
- `Hidden=`
- `NoDisplay=`
- `OnlyShowIn=`
- `NotShowIn=`
- `TryExec=`
- `Categories=`
- `StartupWMClass=`
- Desktop entry ID
- Flatpak and Snap identifiers
- `Exec=` field codes such as `%U`, `%F`, `%i`, `%c`, and `%k`

Hidden and helper entries should be excluded from the normal list, with an
optional diagnostic path for troubleshooting.

Desktop files that cannot be parsed safely should be skipped with a diagnostic,
not allowed to break the entire scan.

### Phase C — Process matching

Map catalog entries to live Linux processes using more than one signal:

- Executable basename
- `/proc/<pid>/exe`
- `/proc/<pid>/comm`
- `/proc/<pid>/cmdline`
- Flatpak/runtime identifiers where available
- Known aliases from the desktop entry

The catalog should preserve the difference between:

- Launcher executable
- Main application process
- Helper process
- Sandbox/runtime process

The UI should not claim that an app is fully matched when only a launcher
process was detected.

### Phase D — Refresh and asynchronous loading

All scans and icon reads must run off the Compose/UI thread.

Add:

- Explicit refresh action
- Last refreshed timestamp
- Loading state
- Partial-result/error state
- Safe concurrent-refresh guard
- Catalog invalidation after relevant changes

Refreshing should not reset the user's current search or selection.

### Phase E — Shared picker component

Create one reusable picker rather than maintaining separate screen-specific
implementations.

The shared component should support:

- Single-select and multi-select modes
- Optional manual process entry
- Optional running-only filter
- Optional source filter
- Existing/stale selection display
- Feature-specific status badges
- Consistent Linux process guidance

Each caller should provide only the selection state and the action taken after
selection.

### Phase F — Screen rollout

Roll out the shared picker in this order:

1. App Blocker
2. Focus Launcher
3. Daily Allowance and Timed Block
4. Focus Session extra apps
5. Tasks Focus Mode
6. Recurring Block Schedule
7. Settings blocked apps
8. VPN/network target-process rules
9. Onboarding preset confirmation

The App Blocker implementation should be treated as the visual baseline, but
its Windows-specific manual-entry logic must be removed from the shared path.

### Phase G — Stored-rule compatibility

Add a safe migration/resolution layer for existing stored values:

- Normalize known Linux process values that incorrectly end in `.exe`.
- Do not modify arbitrary user text.
- Preserve unmatched rules as stale references.
- Show why a stale reference cannot currently be resolved.
- Keep display names stable even if an app is temporarily uninstalled.

Do not silently delete rules during catalog refresh.

## Tracker

### Catalog and scanner

- [ ] **APP-01** Define the shared Linux app descriptor and source enum.
- [ ] **APP-02** Add a catalog repository with read, refresh, and resolve APIs.
- [ ] **APP-03** Discover XDG application directories, Flatpak exports, and
  Snap desktop entries without duplicate results.
- [ ] **APP-04** Parse localized names, icon values, visibility keys, desktop
  IDs, package IDs, and `TryExec`.
- [ ] **APP-05** Safely parse `Exec=` field codes and retain both launch command
  and normalized executable information.
- [ ] **APP-06** Map desktop entries to running processes using executable,
  `/proc`, command-line, and known-alias signals.
- [ ] **APP-07** Add native, Flatpak, Snap, running-only, and manual source
  metadata.
- [ ] **APP-08** Add explicit refresh, loading, failure, and last-refresh state.
- [ ] **APP-09** Add scanner tests for malformed files, duplicate entries,
  localized names, hidden entries, field codes, and paths with spaces.

### Shared picker

- [ ] **APP-10** Build the reusable Linux app-picker UI.
- [ ] **APP-11** Add search across display name, process name, desktop ID, and
  package ID.
- [ ] **APP-12** Add Installed/Running filters and source badges.
- [ ] **APP-13** Add icons, running indicators, selection state, and stale-rule
  state.
- [ ] **APP-14** Add refresh without losing search or selection.
- [ ] **APP-15** Add safe manual-process entry without automatic `.exe` suffixes.
- [ ] **APP-16** Add accessible empty, loading, permission, and scan-error states.

### Screen integration

- [ ] **APP-17** Replace App Blocker picker logic with the shared picker.
- [ ] **APP-18** Upgrade Focus Launcher to browse all installed apps.
- [ ] **APP-19** Upgrade Daily Allowance and Timed Block pickers.
- [ ] **APP-20** Upgrade Focus Session extra-app selection.
- [ ] **APP-21** Replace the Tasks Focus Mode grid with searchable selection.
- [ ] **APP-22** Upgrade recurring schedule app selection.
- [ ] **APP-23** Upgrade Settings blocked-app selection.
- [ ] **APP-24** Add installed/running VPN target selection.
- [ ] **APP-25** Make onboarding presets report missing Linux applications.

### Compatibility and verification

- [ ] **APP-26** Centralize OS-aware process normalization.
- [ ] **APP-27** Normalize known invalid Linux `.exe` process values without
  deleting user rules.
- [ ] **APP-28** Add unit tests for catalog resolution and stale references.
- [ ] **APP-29** Test native, Flatpak, Snap, user desktop files, and
  running-only processes.
- [ ] **APP-30** Test app selection on X11 and native Wayland sessions.
- [ ] **APP-31** Test refresh after installing/uninstalling an application.
- [ ] **APP-32** Verify all picker screens no longer force `.exe` on Linux.
- [ ] **APP-33** Verify Compose screens do not perform scanner or database I/O
  directly on the UI thread.
- [ ] **APP-34** Document AppImage discovery decision and manual fallback.

## Acceptance criteria

This workstream is complete when:

1. Every app-selection screen uses the shared catalog/picker.
2. A Linux user can find installed applications even when they are not running.
3. Running-only applications can still be selected manually when they lack a
   desktop file.
4. Native, Flatpak, Snap, and user desktop applications are distinguishable.
5. Linux values never receive an automatic `.exe` suffix.
6. The catalog can be refreshed without restarting FocusFlow.
7. Existing stale rules remain visible and understandable.
8. Scanner and picker work is asynchronous and does not freeze Compose.
9. Native X11 and Wayland behavior is tested, with limitations shown honestly.
10. The resulting catalog is suitable for later removal of Windows-specific
    app identity assumptions.

## Out of scope for this document

- Removing Windows source code.
- Implementing or redesigning Linux firewall enforcement.
- Replacing the database.
- Scanning the entire filesystem for AppImage files.
- Claiming native Wayland foreground identification that the platform cannot
  reliably provide.