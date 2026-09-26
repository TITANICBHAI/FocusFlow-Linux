# FocusFlow Stored Data Migration Plan

## Purpose

Migrate existing FocusFlow databases safely so Linux can become the primary
platform and eventually the only supported platform without losing user rules,
tasks, sessions, settings, schedules, allowances, or history.

This is a data-compatibility workstream. It is separate from the platform-code
separation plan and must be completed before Windows-specific storage concepts
are removed.

## Status

- Overall: **Not started**
- Owner: FocusFlow data and migration work
- Depends on: the shared app identity contract from the Linux app-picker plan
- Coordinates with: `work/shared-platform-boundary-plan.md`
- Required before: deleting Windows-specific settings, process assumptions, or
  runtime code

## Current baseline

`Database.kt` already has a transactional, versioned SQLite migration system:

- `PRAGMA user_version` tracks schema state.
- The current target schema is version 8.
- Migrations run in a transaction.
- Failed migrations roll back and are retried.
- Broken databases are backed up before recovery.

However, many persisted app references are still plain process strings:

- `block_rules.process_name`
- `block_schedules.process_names`
- `daily_allowances.process_name`
- `temptation_log.process_name`
- `tasks.focus_blocked_apps`
- `network_cutoff_rules.target_process`
- Custom preset process lists
- Focus Launcher app lists
- VPN custom processes in settings

Some Linux entry paths can also incorrectly persist `.exe` suffixes. Existing
data must be preserved and made understandable instead of being silently
deleted.

## Migration goals

1. Preserve all valid user data.
2. Convert known Windows-shaped process identifiers into Linux-safe values
   where the conversion is unambiguous.
3. Preserve unmatched or stale references with an explicit status.
4. Introduce stable app identity without breaking old process-only records.
5. Keep the migration idempotent and transactional.
6. Back up user data before any destructive or structural change.
7. Make rollback and support diagnostics possible.
8. Avoid modifying user-entered text that is not an app/process identifier.

## Data model direction

### Preserve process compatibility

Process names remain useful for enforcement and manual entries. Existing
tables should continue to support a normalized process value during the
transition.

### Add app identity where selection came from the catalog

Catalog-selected references should be able to store, where available:

- Stable application/desktop ID.
- Display name.
- Primary process name.
- Known process aliases.
- Source type.
- Desktop-file path or package ID.
- Last resolved timestamp.
- Resolution status.

Manual process entries may not have a desktop ID. They should remain valid
with a manual source and an explicit confidence/status value.

### Do not use a desktop ID as the only enforcement value

The desktop ID identifies the installed application, but process enforcement
still needs one or more normalized runtime process names or aliases.

## Data categories and migration behavior

### Block rules

- Normalize known process identifiers.
- Preserve display names and enabled/network settings.
- Add app identity fields when a catalog match is available.
- Keep unmatched rules as stale/manual records.
- Preserve rule IDs so history and references remain stable.

### Schedules

- Normalize every process in `process_names`.
- Preserve schedule timing, day selection, enabled state, and name.
- Resolve each process independently; one stale entry must not remove the
  entire schedule.

### Daily allowances

- Normalize process keys.
- Preserve allowance minutes and display names.
- Merge only when two records become the same normalized process and their
  merge behavior is explicitly defined.
- Do not silently choose between conflicting allowance values.

### Focus tasks and sessions

- Normalize `focus_blocked_apps`.
- Preserve task IDs, focus settings, durations, and completion history.
- Existing sessions remain historical records even if an application is no
  longer installed.

### Network cutoff rules

- Normalize `target_process`.
- Preserve domain/keyword pattern, mode, target display name, enabled state,
  and rule ID.
- Keep domain/keyword values untouched unless they fail their own validation.

### Custom presets and launcher data

- Normalize process lists.
- Preserve user-created names, emojis, descriptions, and selected apps.
- Mark unavailable Windows-only preset entries as unresolved rather than
  deleting them.
- Resolve launcher app identities through the catalog after migration.

### VPN settings

- Split known Windows and Linux process values where the setting currently
  stores a comma-separated list.
- Remove automatic `.exe` generation from future writes.
- Keep unrecognized custom entries as manual values.
- Do not classify generic networking tools as VPN clients without an explicit
  user choice.

### General settings

Audit settings such as:

- Windows startup flags.
- Windows setup completion flags.
- Firewall or registry-related settings.
- VPN blocker settings.
- Stored paths.
- Platform-specific quick presets.

For each setting, choose one of:

- Keep as shared data.
- Map to a Linux equivalent.
- Mark as legacy and ignore safely.
- Preserve for historical compatibility until the Windows-removal release.
- Remove only through a versioned migration after backup.

## Migration safety design

### Preflight backup

Before a data migration:

- Close or checkpoint the active connection safely.
- Create a consistent SQLite backup including relevant WAL state.
- Record migration version and backup path.
- Refuse to proceed if the backup cannot be created.

The existing broken-database backup path must not be treated as the only
normal migration backup.

### Transactional migration

Each schema change should:

- Use a new migration version.
- Be idempotent where possible.
- Run inside the existing transaction.
- Update `user_version` only after all steps succeed.
- Leave the previous database untouched if the migration fails.

### Migration report

Record a local, non-sensitive summary:

- Source schema version.
- Target schema version.
- Number of records examined.
- Number normalized.
- Number matched to catalog apps.
- Number left stale/manual.
- Number of conflicts.
- Whether rollback was needed.

Do not record passwords, tokens, full secret values, or unnecessary personal
content in logs.

### User-visible recovery

If migration partially cannot resolve app identities:

- Continue with preserved process rules when safe.
- Show unresolved entries in the app picker/settings.
- Explain how to reselect the current Linux application.
- Never silently drop active blocking rules.

## Implementation phases

### Phase A — Inventory and fixtures

Document every persisted table, column, setting key, serialized list, and
backup path. Build representative fixture databases for:

- Fresh schema.
- Older schema versions.
- Windows-style `.exe` values.
- Linux-style values.
- Duplicate values.
- Missing/empty values.
- Stale applications.
- Malformed serialized lists.
- Conflicting normalized records.

### Phase B — Canonical normalization

Define one normalization library for:

- Linux process names.
- Windows process names during compatibility migration.
- Paths and executable basenames.
- Comma-separated process lists.
- App aliases.

Normalization must be deterministic, case-aware where appropriate, and must
not interpret arbitrary user text as a process name.

### Phase C — Schema extension

Add the minimum schema needed for stable app identity and migration status.
Possible fields include:

- App/desktop ID.
- Source type.
- Executable/process aliases.
- Resolution status.
- Last resolved time.

The final columns should be chosen after the app-catalog contract is
finalized. Avoid duplicating the same identity fields in every table if a
normalized app-reference table can safely serve multiple features.

### Phase D — Data conversion

Implement versioned migrations that:

- Back up the database.
- Normalize known process values.
- Preserve stale records.
- Resolve catalog identities when possible.
- Handle conflicts explicitly.
- Preserve IDs and historical records.
- Keep migrations repeatable.

### Phase E — Runtime compatibility

Update repositories and services so new writes use the canonical identity
format and old records remain readable during the transition.

Do not allow individual UI screens to perform their own data conversion.

### Phase F — Backup, restore, and rollback

Test:

- Backup before migration.
- Successful migration.
- Failed migration rollback.
- Re-running a completed migration.
- Restore into a clean database.
- Upgrade from every supported older schema.
- Corrupt or locked database handling.

### Phase G — Linux-only cleanup readiness

After Linux release readiness:

- Remove obsolete Windows-only settings only through a final migration.
- Preserve an export or backup for users.
- Convert stale references to explicit Linux/manual records.
- Remove compatibility code only after all supported migrations have run.
- Document the final schema version and supported upgrade path.

## Tracker

### Inventory and migration fixtures

- [ ] **DATA-01** Inventory every persisted table, column, setting key, and
  serialized process/app list.
- [ ] **DATA-02** Document all existing schema versions through version 8.
- [ ] **DATA-03** Create fixture databases for fresh, old, malformed, stale,
  duplicate, and Windows-shaped data.
- [ ] **DATA-04** Identify every backup, restore, export, and import path.
- [ ] **DATA-05** Identify settings that are shared, Linux-equivalent,
  Windows-only, legacy, or unsafe to migrate automatically.

### Canonical app/process identity

- [ ] **DATA-06** Define canonical process normalization rules.
- [ ] **DATA-07** Define canonical app-reference fields with the app-catalog
  workstream.
- [ ] **DATA-08** Define source and resolution-status values for catalog,
  manual, stale, and unresolved references.
- [ ] **DATA-09** Define conflict behavior when normalization produces duplicate
  rules or allowances.
- [ ] **DATA-10** Add unit tests for paths, case, `.exe` compatibility,
  aliases, empty values, and malformed lists.

### Schema and migration

- [ ] **DATA-11** Add the next versioned schema migration without editing
  completed migration functions.
- [ ] **DATA-12** Add stable app identity storage without making desktop ID the
  only enforcement key.
- [ ] **DATA-13** Add resolution status and last-resolved metadata where
  required.
- [ ] **DATA-14** Migrate block rules while preserving IDs, display names,
  enabled state, and network settings.
- [ ] **DATA-15** Migrate schedules without dropping stale individual entries.
- [ ] **DATA-16** Migrate daily allowances with explicit duplicate/conflict
  handling.
- [ ] **DATA-17** Migrate task focus-app lists and preserve task/session
  history.
- [ ] **DATA-18** Migrate network cutoff target processes without changing
  domain/keyword patterns.
- [ ] **DATA-19** Migrate custom presets, launcher data, and saved app lists.
- [ ] **DATA-20** Migrate VPN custom-process settings without forcing `.exe`.
- [ ] **DATA-21** Preserve or safely retire Windows-only settings through a
  versioned decision.

### Backup and failure behavior

- [ ] **DATA-22** Add a verified pre-migration SQLite backup including WAL state.
- [ ] **DATA-23** Refuse migration when the required backup cannot be created.
- [ ] **DATA-24** Keep migration transaction boundaries and version updates
  atomic.
- [ ] **DATA-25** Add local migration summary diagnostics without sensitive
  values.
- [ ] **DATA-26** Test failed migration rollback and safe retry.
- [ ] **DATA-27** Test locked, corrupt, read-only, and insufficient-space
  database behavior.
- [ ] **DATA-28** Test restore into a clean database.

### Runtime and UI compatibility

- [ ] **DATA-29** Make all repositories read old and new app references during
  the transition.
- [ ] **DATA-30** Ensure new writes use canonical Linux-safe identity values.
- [ ] **DATA-31** Keep stale references visible in the shared app picker.
- [ ] **DATA-32** Add user-visible reselect/relink behavior for unresolved apps.
- [ ] **DATA-33** Remove per-screen process normalization and conversion logic.
- [ ] **DATA-34** Verify no screen silently deletes or rewrites user rules.

### Upgrade validation

- [ ] **DATA-35** Run migration tests from every supported schema version.
- [ ] **DATA-36** Test a Windows-shaped database opened by the Linux build.
- [ ] **DATA-37** Test a Linux database after application uninstall/reinstall.
- [ ] **DATA-38** Test backup/restore across application upgrades.
- [ ] **DATA-39** Test migration with active schedules, allowances, tasks,
  sessions, VPN settings, and network rules.
- [ ] **DATA-40** Record the final supported upgrade path before Windows
  compatibility code is removed.

## Acceptance criteria

This workstream is complete when:

1. Existing user data is backed up before migration.
2. Migrations are transactional, retryable, and versioned.
3. Block rules, schedules, allowances, tasks, history, network rules, presets,
   launcher data, and VPN settings are preserved.
4. Known `.exe` compatibility values are normalized only in app/process fields.
5. Stale or unresolved applications remain visible instead of being deleted.
6. New writes use the shared Linux app/process identity contract.
7. Migration reports contain useful counts without sensitive user content.
8. Every supported old schema has a tested upgrade path.
9. Restore and rollback have been tested.
10. The final Windows-removal release can retire legacy settings without data
    loss or silent rule deletion.

## Out of scope

- Implementing the shared app picker itself.
- Separating platform services and native APIs.
- Rewriting all database access methods.
- Deleting the Windows database format before the final Linux-only release.
- Migrating arbitrary user note/task text.