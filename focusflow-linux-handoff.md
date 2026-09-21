# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux release-readiness work
Date: 2026-09-21  
Task IDs: P6.6 — strict Linux package gates; P6.7 — reproducible installers and release verification
Status: Source implementation complete. Linux CI now fails closed for missing or invalid `.deb`, `.rpm`, and AppImage outputs; release installers verify version-pinned assets against a published SHA256SUMS manifest. Real package publication and desktop installation remain environment-dependent.
Changed: Added `scripts/validate-linux-packages.sh` for package metadata, architecture, dependencies, desktop entries, icons, and payload checks. `build-linux.yml` now treats missing tools, failed AppImage/RPM creation, missing artifacts, and validation failures as fatal. `release.yml` publishes SHA256SUMS and requires the Linux assets. `install.sh` now targets `TITANICBHAI/FocusFlow-Linux`, verifies checksums, cleans temporary downloads, and safely repeats installs. AUR metadata and release instructions use the real repository and manifest verification instead of SKIP.
Verification: Full `gradle test --no-daemon` passed. `bash -n` for installer, validator, and PKGBUILD passed. `git diff --check` passed. The validator correctly rejects missing artifacts in this container; it cannot validate real packages because rpm/AppImage outputs and the rpm toolchain are unavailable here.
Blockers or environment limits: The current public v1.1.6 release has no Linux assets yet, so AUR cannot use a fabricated static AppImage hash. Native Wayland, privileged hosts/firewall behavior, desktop-session lifecycle, and actual GitHub package publication remain unavailable in this environment.
Next task: Complete the remaining P6.4/P6.5 bounded Linux desktop-process work and validate X11, XWayland, and native Wayland sessions; then run P6.8/P6.9 integration and shell-safety checks. Keep the existing real privileged-session task open.
Notes for the next agent: Preserve Windows branches and keep I/O off Compose threads. The long-term product is Linux-only, but Windows remains a temporary regression platform. Do not show Windows registry or Task Manager copy on Linux.

## Update template

Replace the current state section with the following after each focused task:

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

Keep this file factual and short. Do not paste full logs or secrets here.