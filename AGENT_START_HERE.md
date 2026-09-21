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

P1.1, P1.2, Phase 2, and P3.1 are complete. The source icon is packaged
successfully in the Debian artifact. Native Wayland is documented as reduced
keyboard protection because no supported compositor-independent global grab is
available. The next task is P3.2.

The attached JPEG was not copied unchanged. `focusflow.png` uses the existing
valid `focusflow_256.png` source, and the original resource remains preserved.

## Ready-to-paste prompt for the next action

Use this when starting P3.2:

```text
Continue the FocusFlow Linux migration. Read AGENT_START_HERE.md,
AGENT_PRE_WORK_PROMPT.md, FOCUSFLOW_LINUX_AGENT_PROMPT.md, and
focusflow-linux-handoff.md before editing.

Work only on P3.2: audit Linux escape processes for common GNOME, KDE, XFCE,
and terminal tools. Read the current NuclearMode list before editing, add only
missing safe-to-kill user tools, and never add compositors, display servers,
D-Bus infrastructure, input stacks, or other system-critical processes.
Preserve Windows behavior, run targeted checks, and update the tracker and
focusflow-linux-handoff.md only with verified results.
```

## Git check policy

The agent should run only this before editing:

```bash
git status --short
```

This is a lightweight safety check for uncommitted work from the previous
agent. Do not run `git log` or a full diff routinely. Use those only when the
handoff is unclear, unexpected files appear, or the task is destructive.