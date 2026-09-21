# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux UI readiness work
Date: 2026-09-21  
Task IDs: P6.10 — Linux UI honesty and guidance
Status: In progress. The documentation has been routed to this focused UI task before source edits. The Windows-focused archive comparison remains complete, and the current Linux implementation remains authoritative where the archive has no Linux equivalent.
Acceptance checks: Linux Setup has asynchronous, actionable tool status; Settings reports actual Linux enforcement and session limitations; VPN/Network explains current permissions and resolver behavior; package/autostart guidance distinguishes install modes; Linux startup shows no Windows registry or Task Manager messaging; Wayland limitations are explicit; targeted compile/test passes.
Changed: Updated `AGENT_PRE_WORK_PROMPT.md`, `AGENT_START_HERE.md`, `FOCUSFLOW_LINUX_AGENT_PROMPT.md`, and `focusflow-linux-tracker.md` with the current P6.10 scope. Source UI changes are next.
Verification: Documentation routing is in place. Source/UI verification is pending. No hosts-file or iptables success will be claimed from this task.
Blockers or environment limits: Native Wayland, privileged hosts/firewall behavior, and package/autostart lifecycle remain unavailable for real-device verification in this environment.
Next task: Finish P6.10 in the Linux UI source, run targeted compile/tests, inspect the running desktop screen, then update this handoff with exact files and limitations. Leave P6.1/P6.2 open.
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