# Prompt for the Linux App Discovery and Pickers Agent

Copy the prompt below to a new agent. Replace `<BATCH>` with the assigned
batch, normally `Batch 1 — APP-01–APP-07`.

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
<BATCH>
```

Implement only the tracker items in that batch. Do not begin later batches,
even if you notice work that would be useful there. Record later work as a
blocker or follow-up note instead.

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

1. Work in a maximum seven-item batch.
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
3. Implement only the assigned batch.
4. Add or update focused tests for the completed behavior.
5. Run the cheapest relevant checks during development.
6. Run the complete verification appropriate to the batch before marking items
   complete.
7. Tick only the completed `APP-*` items in
   `work/linux-app-discovery-and-pickers-plan.md`.
8. Update the batch table status in that same file.
9. Add a batch completion record using the plan's template.
10. Do not claim completion for an item whose acceptance evidence is missing.

## Batch boundaries

```text
Batch 1: APP-01–APP-07
Batch 2: APP-08–APP-14
Batch 3: APP-15–APP-21
Batch 4: APP-22–APP-28
Batch 5: APP-29–APP-34
```

## Final response format

At the end, report:

- Assigned batch
- Tracker items completed and ticked
- Files changed
- Tests/checks run and results
- Items left unchecked
- Blockers or limitations
- Next batch to assign

Do not implement or tick later batches.

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned batch: <BATCH>
```