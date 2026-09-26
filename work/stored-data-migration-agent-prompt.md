# Prompt for the Stored Data Migration Agent

Copy the prompt below to a new agent. Replace `<ASSIGNED_SCOPE>` with the
tracker IDs or phase you want that agent to handle.

---

You are working on FocusFlow's stored-data migration workstream.

## Project context

FocusFlow is a Kotlin/JVM Compose Desktop productivity application. The
long-term product direction is Linux-only, but Windows support and existing
user data must be preserved during the migration.

The authoritative plan is:

```text
work/stored-data-migration-plan.md
```

Related plans:

```text
work/linux-app-discovery-and-pickers-plan.md
work/linux-enforcement-and-release-tests-plan.md
work/shared-platform-boundary-plan.md
```

Read `replit.md`, the authoritative plan, `Database.kt`, relevant models and
repositories, `.agents/memory/MEMORY.md`, and linked memory topics before
editing.

## Assigned work

You are assigned exactly:

```text
<ASSIGNED_SCOPE>
```

Implement only that scope. Do not change unrelated schema, delete legacy data,
or remove Windows compatibility unless explicitly assigned.

## Workstream goal

Safely migrate saved FocusFlow data while moving from process-only app
references toward stable Linux application identities.

Data that must be preserved includes:

- Block rules
- Block schedules
- Daily allowances and usage
- Tasks and task focus-app lists
- Focus-session history
- Network cutoff rules
- Custom presets
- Focus Launcher data
- VPN custom processes
- Shared settings
- User history and notes

The database currently uses versioned SQLite migrations and has a schema target
of version 8. Follow the existing migration conventions.

## Non-negotiable rules

1. Back up the database before migration.
2. Keep migrations transactional and retryable.
3. Never silently delete a user rule or historical record.
4. Normalize only fields that are known to contain app/process identifiers.
5. Do not rewrite arbitrary notes, task text, domains, keywords, or user
   descriptions.
6. Preserve stale or unresolved app references visibly.
7. Do not make desktop/application ID the only enforcement value; retain
   process names or aliases where required.
8. Do not put secrets or unnecessary personal content in migration logs.
9. Do not increment the schema version until every migration step succeeds.
10. Never tick a tracker item based on compilation alone.

## Required workflow

1. Read the authoritative plan and identify the exact assigned tracker items.
2. Inspect existing schema versions, fixtures, backup behavior, and repository
   reads/writes.
3. Implement only the assigned scope.
4. Add focused migration, rollback, fixture, and repository tests.
5. Test successful migration, failed migration, retry, and restore behavior as
   appropriate to the scope.
6. Tick only verified `DATA-*` items in
   `work/stored-data-migration-plan.md`.
7. Leave blocked items unchecked and record the exact data or schema blocker.
8. Report changed files, migration versions, checks run, data-safety evidence,
   and suggested next scope.
9. Do not claim migration readiness until old supported schema versions and
   Windows-shaped data have been tested.

## Final response format

At the end, report:

- Assigned scope
- Tracker items completed and ticked
- Schema/migration versions affected
- Files changed
- Tests/checks run and results
- Backup/rollback evidence
- Items left unchecked
- Blockers or data-safety risks
- Suggested next scope

Do not implement or tick outside the assigned scope.

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned scope: <ASSIGNED_SCOPE>
```