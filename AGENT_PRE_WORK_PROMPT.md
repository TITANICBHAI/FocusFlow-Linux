# FocusFlow Agent Pre-Work Prompt

Paste this at the start of every new agent session working on this repository.
It is a short protocol; the detailed Linux task prompt is in
`FOCUSFLOW_LINUX_AGENT_PROMPT.md`.

## Before editing

1. Read `replit.md`.
2. Read `focusflow-linux-handoff.md`.
3. Read `focusflow-linux-plan.md` and `focusflow-linux-tracker.md`.
4. Read the relevant section of `focusflow-linux-tracker.html` if the task is
   part of the Linux migration.
5. Read `.agents/memory/MEMORY.md` and any linked topic relevant to the code.
6. Run:

   ```bash
   git status --short
   git log --oneline -5
   ```

7. Search the current code and call sites for the task before trusting an older
   prompt. Documentation can be stale; the current implementation wins.
8. Choose one narrow task. State its task ID and acceptance checks in the
   handoff before making changes.

## Non-negotiable rules

- Preserve the Windows implementation. Add Linux branches beside it; do not
  replace or delete working Windows paths.
- Do not duplicate an existing workflow, helper, model field, or dependency
  without checking the current code first.
- Keep database, filesystem, process, image, and package I/O off the UI thread.
- Keep platform-specific code behind platform guards.
- Do not claim a Linux or Wayland capability that was not tested or clearly
  documented as a limitation.
- Do not make unrelated refactors.
- Do not use a successful compile as the only proof that a task is complete.

## After editing

1. Run a targeted compile/test first.
2. Run the relevant Gradle test, package task, or manual environment check.
3. Tick the matching checkbox in `focusflow-linux-tracker.md` only when its
   acceptance checks pass.
4. If the implementation state changed, update the matching default status or
   prompt in `focusflow-linux-tracker.html`; do not rely on browser-only state.
5. Update `focusflow-linux-handoff.md` with:
   - the task ID and status;
   - files changed;
   - commands/checks run and their results;
   - any environment limitation;
   - the next uncompleted task.
6. End with a concise handoff so the next agent can continue without repeating
   the investigation.

If you cannot complete the task, leave it unchecked, record the exact blocker,
and identify the smallest next action. Never silently skip it.