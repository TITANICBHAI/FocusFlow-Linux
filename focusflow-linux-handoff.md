# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux cross-platform app-discovery verification
Date: 2026-09-27
Task IDs: APP-29–APP-34
Status: APP-29, APP-31–APP-34 are complete. APP-30 is explicitly blocked pending real X11 and native Wayland sessions.
Changed: Added cross-source catalog fixtures and install/remove rescan coverage; centralized Linux session classification for deterministic X11, XWayland, native Wayland, and headless tests; removed Linux-facing `.exe` guidance from picker flows; and documented the AppImage desktop-entry/manual-fallback policy.
Verification: `gradle compileKotlin --no-daemon`, focused scanner/session/picker tests, complete `gradle test --no-daemon`, and `git diff --check` passed. The complete suite reported 53 tests with 2 privileged tests skipped by their opt-in assumptions.
Blockers or environment limits: This Replit container cannot honestly verify real `/etc/hosts` privilege elevation, cancelled PolicyKit authentication, tagged iptables insertion/removal, X11/Wayland session behavior, or desktop-environment lifecycle. It is unprivileged (`uid=1000`), has no XDG session type, and has no `appimagetool`; the `.deb` generated locally declares only `xdg-utils` and was not treated as a release artifact because CI dependency injection still needs to run. The interactive tracker HTML has no P6.8/P6.9 entries, so the durable Markdown tracker is the source of truth for these phase-6 items.
Next task: Run APP-30's real app-selection checks in disposable X11 and native Wayland environments before declaring this workstream fully verified.
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