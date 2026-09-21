# FocusFlow Linux Migration — Persistent Handoff

This file is the durable bridge between agents. It is not a replacement for the
plan or tracker. Each agent must update it before finishing.

## Current state

Last agent: Initial repository audit  
Date: 2026-09-21  
Task IDs: None completed in this handoff session  
Status: Linux migration work is still at the documented starting point. The
source tree has no Linux implementation task completed yet.  
Changed: Added the Linux agent prompt, pre-work protocol, and this handoff
template; no application code changed.  
Verification: Read the Linux plan, Markdown tracker, HTML tracker, project
instructions, Linux CI/build files, memory index, and the main Kotlin files for
the remaining tasks.  
Blockers or environment limits: No real X11/Wayland/desktop-environment manual
test has been performed yet.  
Next task: P1.1 — add the missing source resource
`src/main/resources/focusflow.png`, after checking the current resource and
packaging state.  
Notes for the next agent: Read `AGENT_START_HERE.md` first. The current
`ScannedApp` model has no desktop-file
path/full `Exec=` field; the old HTML P2.1/P2.2 prompts assume one. The Linux
build and Linux smoke-test GitHub workflows already exist, so extend them rather
than creating duplicates. `NuclearMode` already contains most named Linux task
manager processes. Read `FOCUSFLOW_LINUX_AGENT_PROMPT.md` before implementation.

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