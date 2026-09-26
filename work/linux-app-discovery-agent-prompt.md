# Prompt for the Linux App Discovery and Pickers Agent

Copy the prompt below to a new agent. Replace `<ASSIGNED_SCOPE>` with the
tracker IDs or phase you want that agent to handle.

---

You are working on FocusFlow's Linux app discovery and picker workstream.

## Project context

FocusFlow is a Kotlin/JVM Compose Desktop productivity application. The
long-term product direction is Linux-only, but Windows support is still being
preserved temporarily. Do not remove Windows code in this task.

The authoritative plan is:

```text
work/linux-app-discovery-and-pickers-plan.md
```

The related plans are:

```text
work/linux-enforcement-and-release-tests-plan.md
work/shared-platform-boundary-plan.md
work/stored-data-migration-plan.md
```

Read `replit.md`, the authoritative app-discovery plan, and relevant files
before editing. Also check `.agents/memory/MEMORY.md` and any linked memory
topics that apply.

## Your assigned work

You are assigned exactly:

```text
<ASSIGNED_SCOPE>
```

Implement only the tracker items in that assigned scope. Do not begin
unassigned work, even if you notice work that would be useful. Record later
work as a blocker or follow-up note instead.

## Workstream goal

Finish Linux installed-application discovery and make every app-selection flow
use one Linux-aware catalog and picker.

The catalog must eventually support:

- Native `.desktop` applications
- User-created desktop files
- Flatpak applications
- Snap applications
- Running-only processes
- Display name, desktop/application ID, package source, launch executable,
  process names, aliases, icon reference, and running state
- Refresh without restarting FocusFlow
- Stale or unresolved saved references
- Manual Linux process entry without automatically adding `.exe`

Do not scan the entire home directory for arbitrary AppImage files unless the
plan's explicit AppImage decision says to do so.

## Non-negotiable rules

1. Stay inside the assigned tracker scope.
2. Keep the tracker accurate while working.
3. Never tick a tracker item merely because the project compiles.
4. Tick an item only after implementation and focused verification are done.
5. If blocked, leave the item unchecked and document the exact blocker.
6. Do not modify or delete user data.
7. Do not add automatic `.exe` suffixes to Linux values.
8. Keep scanner, icon, database, and process discovery I/O off Compose/UI
   threads. Use the existing project conventions and memory guidance.
9. Preserve existing Windows behavior during this migration.
10. Prefer small, reviewable changes over a rewrite.

## Required workflow

1. Read the authoritative plan and identify the exact assigned tracker items.
2. Inspect the current implementation and tests before editing.
3. Implement only the assigned scope.
4. Add or update focused tests for the completed behavior.
5. Run the cheapest relevant checks during development.
6. Run the complete verification appropriate to the scope before marking items
   complete.
7. Tick only the completed `APP-*` items in
   `work/linux-app-discovery-and-pickers-plan.md`.
8. Update the plan's status or completion record if the assigned scope requires
   one.
9. Record verification evidence and blockers in the plan or final report.
10. Do not claim completion for an item whose acceptance evidence is missing.

## Final response format

At the end, report:

- Assigned scope
- Tracker items completed and ticked
- Files changed
- Tests/checks run and results
- Items left unchecked
- Blockers or limitations
- Suggested next scope

Do not implement or tick outside the assigned scope.

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned scope: <ASSIGNED_SCOPE>
```