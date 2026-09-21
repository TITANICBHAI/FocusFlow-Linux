# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux parity sweep
Date: 2026-09-21  
Task IDs: P3.2, P3.3, P4.1, P4.2, P4.3, P5.1, P5.2, P5.3
Status: Linux feature and UI parity sweep in progress. The acceptance target is to add safe Linux behavior beside Windows behavior, correct Linux-facing copy, and verify compile/tests/package paths without claiming untested compositor behavior.
Acceptance checks: Linux escape-process coverage; best-effort X11 overlay raise; notify-send fallback; valid Linux autostart executable and StartupWMClass; desktop-aware launcher panel handling; Linux VPN/settings copy; crash cleanup guard review; targeted tests, package build, diff check, and fresh workflow startup.
Changed: Previous completed migration work remains intact; this task has only recorded the scoped parity sweep before editing.
Verification: Pending for this task.
Blockers or environment limits: No real X11, Wayland, desktop-environment, Windows runtime, or live app-blocking test has been performed. Native Wayland global shortcut suppression remains intentionally unavailable; the product now reports that reduced guarantee.
Next task: Complete the parity sweep, then update the tracker and handoff only with verified results.
Notes for the next agent: Read `AGENT_START_HERE.md` first. Preserve the Windows branches. The Linux build and smoke-test workflows already exist, so extend them rather than creating duplicates.

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