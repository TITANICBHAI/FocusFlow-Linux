# FocusFlow Agent Start Here

This is the only routing document the project owner needs to remember when
switching agents.

## What to paste into a new agent

Paste this short message:

```text
Continue work in this FocusFlow repository. Read AGENT_START_HERE.md first and
follow its instructions. Do not edit until you have read the persistent handoff
and identified the current task. For this session, work only on the current
next task unless I explicitly give you another task.
```

The agent can read the longer prompts directly from the repository. Do not paste
the full contents of every Markdown file into chat.

## Which file does what?

| File | When to use it |
|---|---|
| `AGENT_START_HERE.md` | Always. This is the routing guide. |
| `AGENT_PRE_WORK_PROMPT.md` | Every agent switch. It contains the short safety and handoff protocol. |
| `FOCUSFLOW_LINUX_AGENT_PROMPT.md` | Whenever the task is part of the Linux migration. It contains the detailed task queue and code-specific constraints. |
| `focusflow-linux-handoff.md` | The agent reads this; you normally do not paste it. It says what the previous agent did and what comes next. |
| `focusflow-linux-tracker.md` | The agent updates this after a Linux task passes its acceptance checks. |
| `focusflow-linux-tracker.html` | Optional visual reference. Its browser status is not the durable project status. |

## Current project position

P1.1, P1.2, Phase 2, P3.1–P3.3, P4.1–P4.3, and P5.1–P5.3 are complete
according to their scoped checks. The ZIP comparison and Linux-readiness audit
are recorded in `ZIP_MERGE_TRACKER.md` and `focusflow-linux-plan.md`.

The next work is not another broad migration sweep. It is release-readiness
hardening: close the CI/package gates, implement and verify the real Linux
privilege path for `/etc/hosts`, make iptables status truthful, move the
remaining Linux probe off the UI thread, and then run the device/VM matrix.
Native Wayland remains a documented reduced guarantee, not an X11 equivalent.

The attached JPEG was not copied unchanged. `focusflow.png` uses the existing
valid `focusflow_256.png` source, and the original resource remains preserved.

## Ready-to-paste prompt for the next action

Use this when starting the next Linux readiness task:

```text
Continue the FocusFlow Linux readiness work. Read AGENT_START_HERE.md,
AGENT_PRE_WORK_PROMPT.md, FOCUSFLOW_LINUX_AGENT_PROMPT.md,
focusflow-linux-plan.md, focusflow-linux-tracker.md, and
focusflow-linux-handoff.md before editing.

Choose exactly one open P6 task from the tracker. Re-read the current code and
call sites before changing it. Preserve Windows behavior, keep I/O off Compose
threads, do not claim native Wayland capabilities that are not supported, and
run the targeted test plus the relevant Gradle/package check. Update the
tracker only after its acceptance checks pass and record exact blockers in the
handoff.
```

## Git check policy

The agent should run only this before editing:

```bash
git status --short
```

This is a lightweight safety check for uncommitted work from the previous
agent. Do not run `git log` or a full diff routinely. Use those only when the
handoff is unclear, unexpected files appear, or the task is destructive.