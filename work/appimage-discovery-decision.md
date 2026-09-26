# AppImage Discovery Decision

## Decision

FocusFlow does not scan a user's entire home directory for arbitrary `.AppImage`
files. The application catalog discovers AppImages only when a trusted desktop
entry exposes them through the normal XDG application directories, or when the
user supplies a specific process/application entry through the manual picker.

## Why

Whole-home AppImage scanning is slow, noisy, and difficult to match safely to a
launch process. It also produces duplicate entries when the same application is
already represented by a desktop file, Flatpak export, or Snap entry.

## Manual fallback

Users can add a process name through the shared picker when an AppImage has no
desktop entry. Linux manual entries are validated as process names and are
stored without an automatically added `.exe` suffix. A future explicit
application-location feature may accept a user-selected AppImage path, but it
must not be implemented as an unrestricted filesystem scan.

## Verification boundary

The current Replit environment has no `appimagetool` and cannot verify desktop
integration for a real AppImage. Packaging and launch checks remain part of the
separate Linux release-readiness work.