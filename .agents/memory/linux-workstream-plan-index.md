---
name: Linux workstream plan index
description: The Linux-only transition is tracked by four separate workstream plans and matching agent prompts under work/.
---

The authoritative workstream documents are:

- `work/linux-app-discovery-and-pickers-plan.md` — Linux app catalog, process
  identity, shared pickers, screen integration, and stale app references.
- `work/linux-enforcement-and-release-tests-plan.md` — truthful enforcement,
  privilege handling, session testing, recovery, CI, and package release gates.
- `work/shared-platform-boundary-plan.md` — separation of shared product logic
  from Linux/Windows implementations before Windows removal.
- `work/stored-data-migration-plan.md` — transactional SQLite migration,
  app/process identity compatibility, backup, rollback, and stale records.

Each plan has a matching generic agent prompt in `work/`. The prompt receives
the tracker scope at assignment time; it does not prescribe a fixed batch.

**Why:** These workstreams have different risks and acceptance gates. Keeping
their plans separate prevents platform refactoring or data migration from being
mixed into app-picker or enforcement changes.

**How to apply:** Give an agent one plan and an explicit tracker scope. The
agent must read the plan and relevant memory, work only within that scope, tick
only verified items, and report blockers before another scope is assigned.