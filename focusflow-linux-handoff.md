# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: FocusFlow Linux readiness audit
Date: 2026-09-21  
Task IDs: Linux readiness audit
Status: Audit complete; release readiness is not complete. The Windows-focused archive comparison remains complete, and the current Linux implementation remains authoritative where the archive has no Linux equivalent.
Acceptance checks: Pre-work instructions, plan, tracker, handoff, workflows, installer metadata, Linux source paths, and Linux tests were audited. Remaining gaps were added to Phase 6 and the manual device/VM matrix.
Changed: Updated `focusflow-linux-plan.md`, `focusflow-linux-tracker.md`, `AGENT_START_HERE.md`, `FOCUSFLOW_LINUX_AGENT_PROMPT.md`, and `replit.md` with the audited release gates. No Kotlin or workflow source was changed in this audit.
Verification: Existing `gradle :compileKotlin :test --no-daemon` passed on GraalVM 19 / Gradle 8.14.2 before this documentation-only audit. Final `git diff --check` passed after all documentation updates. The audit itself used source/workflow inspection; no privileged or display-session behavior was claimed as verified.
Blockers or environment limits: The current Linux hosts path has no actual pkexec write mechanism; Linux iptables reports intent before asynchronous success is known; the Linux Setup iptables probe is synchronous; package/installer gates are not strict; and real X11, native Wayland, XWayland, desktop-environment, privileged firewall, resolver, package lifecycle, and Windows runtime tests remain unavailable here.
Next task: Choose one open P6 item from `focusflow-linux-tracker.md`, preferably P6.1 (hosts-file privilege path) or P6.2 (truthful firewall status), then run its targeted tests. Do not check manual device items until they are run on the specified distro/session.
Notes for the next agent: Read `AGENT_START_HERE.md` first. Preserve Windows branches. Keep native Wayland documented as reduced protection. Do not import embedded webhooks or the archive's unused Windows uninstall path, and do not treat compile/test success as Linux release proof.

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