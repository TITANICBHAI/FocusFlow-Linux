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

P1.2 and Phase 2 are complete. P1.1's source resource is also present and
verified, but its Debian package acceptance check is still open because the
current environment does not have `fakeroot`. The next action is to retry that
package check when the tool is available, then continue with P3.1.

The attached JPEG was not copied unchanged. `focusflow.png` uses the existing
valid `focusflow_256.png` source, and the original resource remains preserved.

## Ready-to-paste prompt for the next action

Use this when starting the P1.1 packaging closure:

```text
Continue the FocusFlow Linux migration. Read AGENT_START_HERE.md,
AGENT_PRE_WORK_PROMPT.md, FOCUSFLOW_LINUX_AGENT_PROMPT.md, and
focusflow-linux-handoff.md before editing.

Work only on the remaining P1.1 acceptance check. The source
src/main/resources/focusflow.png already exists and is a valid copy of
src/main/resources/focusflow_256.png. Do not replace either icon and do not
change Windows behavior.

Retry `bash ./gradlew packageDeb --no-daemon` when the Linux packaging tool
fakeroot is available. If it is unavailable, record that exact blocker and do
not mark P1.1 complete. Update focusflow-linux-tracker.md only when the
acceptance check passes, and update focusflow-linux-handoff.md with the result
and next task.
```

## Git check policy

The agent should run only this before editing:

```bash
git status --short
```

This is a lightweight safety check for uncommitted work from the previous
agent. Do not run `git log` or a full diff routinely. Use those only when the
handoff is unclear, unexpected files appear, or the task is destructive.