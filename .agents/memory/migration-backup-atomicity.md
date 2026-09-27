---
name: Migration backup and atomicity
description: Safety boundary for FocusFlow schema migrations and their local diagnostics.
---

Schema migration must be gated by an independently verified SQLite snapshot
created before the migration transaction. A failed backup or failed migration
must leave the live database in place and must not enter the fresh-database
recovery path.

**Why:** Replacing a user database after a migration or backup error can silently
remove rules, history, and settings; WAL-aware snapshots are required for a
consistent pre-migration restore point.

**How to apply:** Use `VACUUM INTO`, validate the destination with SQLite
integrity and schema-version checks, update `user_version` only after commit,
and write only aggregate, non-sensitive migration diagnostics.