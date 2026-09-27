# FocusFlow Stored-Data Inventory

Inventory snapshot: 2026-09-27

This document supports DATA-01 through DATA-05. It describes persisted data
from `src/main/kotlin/com/focusflow/data/Database.kt`, `Models.kt`, the
settings call sites, and the backup/export services. It is an inventory, not a
migration: no data is rewritten by this work.

## DATA-01 — persisted schema and serialized values

The live database is SQLite at `~/.focusflow/focusflow.db` on Linux and
`%USERPROFILE%\.focusflow\focusflow.db` on Windows. SQLite `PRAGMA user_version`
is the schema version. The current target is version 8.

### Tables and columns

| Table | Columns and storage | Identifier/list notes |
|---|---|---|
| `tasks` | `id TEXT PRIMARY KEY`; `title TEXT NOT NULL`; `description TEXT DEFAULT ''`; `duration_minutes INTEGER DEFAULT 25`; `scheduled_date TEXT`; `scheduled_time TEXT`; `completed INTEGER DEFAULT 0`; `skipped INTEGER DEFAULT 0`; `recurring INTEGER DEFAULT 0`; `recurring_type TEXT`; `priority TEXT DEFAULT 'medium'`; `tags TEXT DEFAULT ''`; `created_at TEXT NOT NULL`; `completed_at TEXT`; `focus_mode INTEGER DEFAULT 0`; `focus_intensity TEXT DEFAULT 'standard'`; `focus_blocked_apps TEXT DEFAULT ''`; `focus_require_pin INTEGER DEFAULT 0` | `tags` is comma-separated user text. `focus_blocked_apps` is comma-separated process/app identifiers. |
| `focus_sessions` | `id TEXT PRIMARY KEY`; `task_id TEXT`; `task_name TEXT NOT NULL`; `start_time TEXT NOT NULL`; `end_time TEXT`; `planned_minutes INTEGER NOT NULL`; `actual_minutes INTEGER DEFAULT 0`; `completed INTEGER DEFAULT 0`; `interrupted INTEGER DEFAULT 0`; `notes TEXT DEFAULT ''` | Historical task/session text must not be normalized as an app identifier. |
| `block_rules` | `id TEXT PRIMARY KEY`; `process_name TEXT NOT NULL UNIQUE`; `display_name TEXT NOT NULL`; `enabled INTEGER DEFAULT 1`; `block_network INTEGER DEFAULT 0`; `source TEXT DEFAULT 'manual'` | `process_name` is an enforcement identifier. `source` was added in v2 and is not currently mapped into `BlockRule`. |
| `block_schedules` | `id TEXT PRIMARY KEY`; `name TEXT NOT NULL`; `days_of_week TEXT NOT NULL`; `start_hour INTEGER NOT NULL`; `start_minute INTEGER NOT NULL`; `end_hour INTEGER NOT NULL`; `end_minute INTEGER NOT NULL`; `enabled INTEGER DEFAULT 1`; `process_names TEXT DEFAULT ''` | `days_of_week` is comma-separated integers. `process_names` is a comma-separated process list. |
| `daily_allowances` | `process_name TEXT PRIMARY KEY`; `display_name TEXT NOT NULL`; `allowance_minutes INTEGER NOT NULL` | Process identifier is the primary key. Legacy duplicate rows are defensively deduplicated on read. |
| `daily_notes` | `date TEXT PRIMARY KEY`; `content TEXT NOT NULL`; `mood INTEGER DEFAULT 3`; `updated_at TEXT NOT NULL` | Notes and dates are not app/process fields. |
| `temptation_log` | `id INTEGER PRIMARY KEY AUTOINCREMENT`; `process_name TEXT NOT NULL`; `display_name TEXT NOT NULL`; `timestamp TEXT NOT NULL` | Historical `process_name` is an app/process identifier; rows are capped at the newest 1000. |
| `settings` | `key TEXT PRIMARY KEY`; `value TEXT NOT NULL` | All settings are string values; semantics are defined by call sites. |
| `daily_completions` | `date TEXT PRIMARY KEY`; `completed_count INTEGER DEFAULT 0`; `total_count INTEGER DEFAULT 0`; `focus_minutes INTEGER DEFAULT 0` | Aggregated history; no process serialization. |
| `habits` | `id TEXT PRIMARY KEY`; `name TEXT NOT NULL`; `emoji TEXT DEFAULT '✅'`; `created_at TEXT NOT NULL` | User content; do not normalize. |
| `habit_entries` | `habit_id TEXT NOT NULL`; `date TEXT NOT NULL`; `done INTEGER DEFAULT 1`; primary key `(habit_id, date)` | Relational history; no process serialization. |
| `network_cutoff_rules` | `id TEXT PRIMARY KEY`; `pattern TEXT NOT NULL`; `mode TEXT NOT NULL`; `target_process TEXT`; `target_display_name TEXT`; `enabled INTEGER DEFAULT 1` | `target_process` is an app/process identifier. `pattern` is a domain/keyword and must remain unchanged. |
| `custom_block_presets` | `id TEXT PRIMARY KEY`; `name TEXT NOT NULL`; `emoji TEXT NOT NULL DEFAULT '🚫'`; `process_names TEXT NOT NULL DEFAULT ''`; `created_at TEXT NOT NULL` | `process_names` is a comma-separated process list. Name and emoji are user content. |
| `daily_usage` | `date TEXT NOT NULL`; `process_name TEXT NOT NULL`; `seconds_used INTEGER NOT NULL DEFAULT 0`; primary key `(date, process_name)` | `process_name` is an app/process identifier. |
| `focus_launcher_presets` | `id TEXT PRIMARY KEY`; `name TEXT NOT NULL`; `process_names TEXT NOT NULL DEFAULT ''`; `created_at TEXT NOT NULL` | `process_names` is a comma-separated process list. |
| `focus_launcher_session` | `id INTEGER PRIMARY KEY CHECK (id = 1)`; `session_start_ms INTEGER NOT NULL`; `session_end_ms INTEGER NOT NULL DEFAULT 0`; `breaks_total INTEGER NOT NULL DEFAULT 1`; `breaks_used INTEGER NOT NULL DEFAULT 0`; `break_duration_seconds INTEGER NOT NULL DEFAULT 300`; `break_seconds_accumulated INTEGER NOT NULL DEFAULT 0`; `hard_locked INTEGER NOT NULL DEFAULT 0`; `break_active INTEGER NOT NULL DEFAULT 0`; `break_end_ms INTEGER NOT NULL DEFAULT 0`; `pin_hash TEXT NOT NULL` | Durable launcher recovery state. `pin_hash` is hashed credential material and must never be logged or exported as plaintext. |
| `focus_launcher_session_apps` | `session_id INTEGER NOT NULL`; `position INTEGER NOT NULL`; `process_name TEXT NOT NULL`; `display_name TEXT NOT NULL`; `exe_path TEXT`; primary key `(session_id, position)` | `process_name` is an app/process identifier; `exe_path` is a platform-specific path. |

Indexes currently created by migrations:

- `idx_tasks_date` on `tasks(scheduled_date)`
- `idx_sessions_start` on `focus_sessions(start_time)`
- `idx_temptation_ts` on `temptation_log(timestamp)`
- `idx_notes_date` on `daily_notes(date)`
- `idx_habit_entries` on `habit_entries(habit_id, date)`
- `idx_daily_usage_date` on `daily_usage(date)`
- `idx_net_rules_mode` on `network_cutoff_rules(mode)`

### Serialized lists and scalar encodings

| Location | Encoding | Migration handling |
|---|---|---|
| `tasks.tags` | comma-separated tags | User text; do not treat values as processes. |
| `tasks.focus_blocked_apps` | comma-separated process names | Normalize each nonblank app/process value. |
| `block_schedules.days_of_week` | comma-separated integers | Parse integers; preserve schedule if an individual app is stale. |
| `block_schedules.process_names` | comma-separated process names | Normalize each nonblank value. |
| `settings.blocked_keywords` | comma-separated keywords | Preserve keyword text; do not apply process normalization. |
| `settings.vpn_custom_processes` | comma-separated process names | Normalize app/process values only. |
| `settings.standalone_block_processes` | comma-separated process names | Normalize app/process values only. |
| `settings.alarm_fired_ids` | comma-separated task IDs | Preserve as IDs, not app values. |
| `settings.onboarding_presets` | comma-separated preset IDs | Preserve as IDs. |
| `settings.launcher_selected_apps` | comma-separated process names | Normalize app/process values only. |
| `custom_block_presets.process_names` | comma-separated process names | Normalize each nonblank value. |
| `focus_launcher_presets.process_names` | comma-separated process names | Normalize each nonblank value. |
| `focus_launcher_session_apps.process_name` | one process name per row | Normalize the process value; preserve row position. |
| CSV task export `tags` | pipe-separated tags inside a CSV field | Export-only representation; no import path exists. |

Empty elements, whitespace-only elements, duplicate process values, malformed
integers, and Windows-shaped `.exe` values are represented in the migration
fixtures under `src/test/resources/migrations/`.

### Settings key inventory

Settings are key/value rows rather than typed columns. The following keys are
defined or read by the current source. Dynamic key families are shown with
angle brackets.

| Key or family | Value shape | Data classification |
|---|---|---|
| `theme_mode`, `app_language`, `sidebar_collapsed` | enum/code or boolean string | Shared UI state |
| `onboarding_complete`, `onboarding_presets`, `default_focus_minutes`, `last_seen_version` | boolean, CSV IDs, integer, version | Shared onboarding state |
| `app_open_count`, `android_promo_shown_date`, `android_promo_last_version`, `edge_extension_promo_dismissed`, `post_pin_recommendations_shown` | counter, date/version, booleans | Legacy/product-prompt state; preserve, do not infer platform equivalence |
| `user_name`, `daily_focus_goal` | user text and integer | Shared user preference |
| `overlay_message`, `overlay_dismiss_seconds` | user text and integer | Shared UI preference |
| `always_on_enforcement`, `sound_aversion`, `sound_volume`, `temptation_log` | boolean/float | Shared or Linux-equivalent runtime preference |
| `pomodoro_mode`, `pomodoro_work`, `pomodoro_short`, `pomodoro_long`, `pomodoro_cycles` | boolean and integers | Shared focus preference |
| `pomodoro_work_chime`, `pomodoro_break_chime` | enum/code | Shared sound preference |
| `focus_lock_until_timer` | boolean | Shared focus behavior |
| `weekly_report_last_generated` | date/time | Shared service state |
| `blocked_keywords`, `keyword_blocker_enabled` | CSV user keywords and boolean | Shared blocker state; keyword text is not process data |
| `vpn_enabled`, `vpn_block_enabled` | boolean | Platform-dependent runtime preference; preserve until VPN migration policy is finalized |
| `vpn_custom_processes` | CSV process names | App/process data |
| `launcher_selected_apps` | CSV process names | App/process data |
| `launcher_session_pin_hash`, `global_pin_hash`, `session_pin_hash`, `nuclear_pin_hash` | hash or blank | Sensitive hashed credential material; preserve without logging/exporting values |
| `global_pin_skipped` | boolean | Shared consent state |
| `launcher_crash_guard`, `launcher_hard_locked`, `launcher_break_used_date`, `launcher_break_duration_sec` | boolean/date/integer | Launcher recovery state |
| `nuclear_mode`, `nuclear_last_session_attempts` | boolean/integer | Platform-dependent enforcement state; preserve, do not silently retire |
| `escape_attempt_<sanitized-process>` | integer | Dynamic historical counter keyed by an enforcement process |
| `standalone_block_processes`, `standalone_block_until`, `standalone_block_start` | CSV process names and epoch milliseconds | App/process state plus timing |
| `alarm_fired_date`, `alarm_fired_ids` | date and CSV task IDs | Task-alarm state |
| `killswitch_remaining_today`, `killswitch_reset_date` | integer/date | Runtime allowance state |
| `block_promo_reset_date` | date | Product-prompt state |
| `crash_reports_enabled`, `last_crash_version`, `last_crash_ts` | boolean/version/timestamp | Privacy choice and diagnostic metadata |
| `review_permanently_dismissed`, `review_declined_date`, `review_prompt_shown` | boolean/date/legacy boolean | Product-prompt state; legacy key is read-only compatibility |
| `start_with_windows` / `AppSettings.startWithWindows` | boolean-shaped model value | Windows-only startup concept; current startup implementation uses the Windows registry and does not persist this key through `Database` call sites |

The inventory intentionally does not reproduce any PIN/hash values or user
content. Unknown settings keys must be retained by a migration unless a later
versioned decision explicitly classifies them.

## DATA-02 — schema history through version 8

SQLite starts at `user_version = 0` for a new/empty database. `Database.migrate`
runs each missing migration in order inside one transaction and updates
`user_version` to 8 only after all steps succeed.

| Version | Change |
|---:|---|
| 0 | Empty or pre-migration SQLite database. No FocusFlow schema is assumed. |
| 1 | Creates baseline tables: tasks, focus sessions, block rules, schedules, daily allowances, notes, temptation log, settings, daily completions, habits, and habit entries. Adds guarded task/daily-completion columns and baseline indexes. |
| 2 | Adds `block_rules.source`, defaulting to `manual`. |
| 3 | Adds `tasks.focus_blocked_apps` and `tasks.focus_require_pin`. |
| 4 | Adds `network_cutoff_rules` and `idx_net_rules_mode`. |
| 5 | Adds `custom_block_presets`. |
| 6 | Adds `daily_usage` and `idx_daily_usage_date`. |
| 7 | Adds `focus_launcher_presets`. |
| 8 | Adds `focus_launcher_session` and `focus_launcher_session_apps` for durable launcher recovery. |

There is no v9 migration. Existing migration functions must remain unchanged;
future schema work belongs in a new numbered migration.

## DATA-03 — fixture databases

The fixture suite is executable and creates temporary SQLite databases from
versioned schema/data SQL:

| Fixture | Starting state | Covers |
|---|---|---|
| `old-v1` | baseline schema, `user_version=1` | Old supported schema with representative task and rule data |
| `fresh-v8` | complete schema, `user_version=8` | Fresh current database with default settings |
| `malformed-v8` | complete schema, `user_version=8` | Empty list elements, malformed schedule integers, and malformed CSV process settings |
| `stale-v8` | complete schema, `user_version=8` | Uninstalled/stale process references across rules, schedules, tasks, presets, network, and launcher data |
| `duplicate-v0` | deliberately weak pre-schema tables, `user_version=0` | Duplicate process rows and duplicate serialized values that must not be silently dropped |
| `windows-shaped-v8` | complete schema, `user_version=8` | `.exe` process values, Windows paths, and Windows-shaped VPN/launcher values |

The test materializes every fixture in a temporary directory and validates
that it opens, reports the expected schema version, and contains its
representative data. Fixtures are safe: they never use the user database path.

## DATA-04 — backup, restore, export, and import paths

| Path | Location/operation | Classification and migration implication |
|---|---|---|
| Normal live DB | `~/.focusflow/focusflow.db` | SQLite WAL database; migration must account for `-wal` and `-shm`. |
| Broken-open recovery | `Database.safeBackupBrokenDb` writes `focusflow.db.broken_<timestamp>`, plus matching `-wal`/`-shm` copies when present, then deletes copied originals | Recovery-only, best-effort, not a normal pre-migration backup. A future migration must not rely on this path because a locked auxiliary file may be skipped. |
| Verified automatic backup | `~/.focusflow/backups/focusflow_<timestamp>.db` with `.sha256` and `.meta.json` sidecars | `AutoBackupService.runBackupNow` uses `VACUUM INTO`, hashes and verifies the snapshot, and keeps seven database backups. |
| Restore | `AutoBackupService.restoreBackup` | Requires a hash sidecar, verifies it, creates `pre_restore_safety.db` via `VACUUM INTO`, then replaces the live database and attempts safety rollback on copy failure. |
| Low-level snapshot | `Database.vacuumInto(destPath)` | WAL-safe consistent SQLite snapshot primitive used by automatic backup and restore safety. |
| Session CSV export | `BackupService.exportToCsv` via save dialog | Exports up to 1000 recent sessions. It is not a database backup and has no import path. |
| Task CSV export | `BackupService.exportTasksToCsv` via save dialog | Exports tasks and pipe-joins tags. It is not a database backup and has no import path. |
| Stats session export | `StatsScreen` writes `~/.focusflow/sessions_<date>.csv` | Second session export implementation; no import path. |
| Clear data action | `BackupService.clearAllData` | Destructive clear of sessions, tasks, temptation log, and notes only; not a backup or restore path. |
| Import paths | None found in current source | No CSV, JSON, or SQLite import exists. Do not describe exports as round-trippable backups. |

## DATA-05 — settings migration classification

| Classification | Settings/state |
|---|---|
| Shared and safe to preserve | Theme/language/sidebar, onboarding completion and preset IDs, user name, focus goal, overlay text/timing, Pomodoro values and chimes, sound volume, focus lock, weekly report timestamp, keyword text/enabled state, review/promo counters, app-open count, crash-report opt-in, task-alarm state |
| Linux-equivalent or runtime-shared | `always_on_enforcement`, `sound_aversion`, `temptation_log`, `vpn_enabled`, `vpn_block_enabled`, `vpn_custom_processes`, standalone block process/timing values, launcher selection and recovery state. Preserve first; map behavior only in a later platform-aware migration. |
| Windows-only or platform-specific | Windows startup/registry state represented by `WindowsStartupManager`; Windows setup, Defender, firewall, hosts, registry lockdown, and `start_with_windows` model semantics. Preserve unknown database keys, but do not claim a Linux equivalent automatically. |
| Legacy/product metadata | Android promo keys, Edge extension promo dismissal, post-PIN recommendations, review-prompt legacy key, block promo reset, last-crash metadata, version markers, dynamic escape-attempt counters |
| Unsafe to migrate automatically | PIN/hash values into logs or exports; unknown settings keys; arbitrary user text; domain/keyword patterns; Windows paths; platform-specific enforcement flags whose Linux behavior is not equivalent; comma-separated values when the field's identity type is not known |

### Data-safety conclusions

1. DATA-01 inventory is complete for the current `Database.kt` schema and
   settings call sites.
2. DATA-02 covers v0 through the current target v8; v4 is invoked before v5
   even though its function appears later in the source file.
3. DATA-03 fixtures are temporary-database tests and do not alter production
   data.
4. DATA-04 confirms there is no import/round-trip path today.
5. DATA-05 leaves unknown settings and unresolved app references preserved for
   later versioned migration decisions.
