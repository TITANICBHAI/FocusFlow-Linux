# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux release-readiness work
Date: 2026-09-21  
Task IDs: P6.8/P6.9 — privileged and recovery integration tests; shell/input safety tests
Status: P6.9 is complete. P6.8 test coverage is implemented with non-destructive defaults, but the real hosts/firewall checks remain open until they run in a disposable privileged Linux environment.
Changed: Added `LinuxShellInputSafetyTest.kt` and `LinuxReleaseReadinessIntegrationTest.kt`. Hardened Linux desktop-file executable normalization against shell metacharacters, fixed Linux autostart quoting for paths with spaces, made watchdog unit generation direct-argument safe, and added resolver/atomic-hosts test seams. P6.8 privileged tests are opt-in and clean up their hosts/firewall state in `finally` blocks.
Verification: `gradle compileKotlin --no-daemon` passed. `gradle test --no-daemon` passed: 33 tests completed, 2 privileged tests skipped by their opt-in assumptions. `git diff --check` passed. The skipped checks require a disposable Linux environment with PolicyKit/iptables and `FOCUSFLOW_RUN_PRIVILEGED_TESTS=1`; `FOCUSFLOW_FIREWALL_TEST_PROCESS` must name a running process with a real connection.
Blockers or environment limits: This Replit container cannot honestly verify real `/etc/hosts` privilege elevation, cancelled PolicyKit authentication, or tagged iptables insertion/removal. The interactive tracker HTML has no P6.8/P6.9 entries, so the durable Markdown tracker is the source of truth for these phase-6 items.
Next task: Run P6.8's opt-in hosts/firewall integration tests in a disposable Linux VM/container, then return to the earlier uncompleted P6.4/P6.5 timeout and session-type validation work.
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