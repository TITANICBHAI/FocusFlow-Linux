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

The documentation and handoff system is ready, but no Linux implementation
task has been completed yet. The next task is:

**P1.1 — Add `src/main/resources/focusflow.png`**

The attached icon can be considered as a source, but the agent must inspect the
existing `focusflow_256.png` first. The package resource must be a correct PNG;
do not copy the JPEG unchanged. Preserve the existing resource.

## Ready-to-paste prompt for the current task

Use this when starting P1.1:

```text
Continue the FocusFlow Linux migration. Read AGENT_START_HERE.md,
AGENT_PRE_WORK_PROMPT.md, FOCUSFLOW_LINUX_AGENT_PROMPT.md, and
focusflow-linux-handoff.md before editing.

Work only on P1.1: add the missing source resource
src/main/resources/focusflow.png. Inspect the existing
src/main/resources/focusflow_256.png and the attached icon before choosing the
source. The result must be a valid PNG suitable for the Linux package and
runtime. Preserve the original resources and do not change Windows behavior.

Verify the relevant resource/package path, update focusflow-linux-tracker.md
only if the acceptance checks pass, and update focusflow-linux-handoff.md with
the files changed, verification, blockers, and next task.
```

## Git check policy

The agent should run only this before editing:

```bash
git status --short
```

This is a lightweight safety check for uncommitted work from the previous
agent. Do not run `git log` or a full diff routinely. Use those only when the
handoff is unclear, unexpected files appear, or the task is destructive.