---
name: Stored identity migration boundary
description: Data-safety rule for introducing stable app identities into FocusFlow's legacy SQLite records.
---

Stable app identity must be introduced through additive per-reference metadata while leaving legacy process-bearing source rows intact.

**Why:** Existing FocusFlow enforcement and historical data depend on process values, and normalized duplicates or unavailable catalog entries cannot be resolved safely by rewriting or deleting the source record.

**How to apply:** Store the canonical primary process separately from the original value, keep unresolved references visible, record deterministic duplicate/conflict metadata, and treat desktop/application IDs as supplemental rather than sufficient for enforcement.

Windows-only and platform-specific settings should remain in the legacy settings
store until a later explicit retirement migration. Record the policy version and
classification separately instead of deleting or rewriting settings during app
identity migration.

**Why:** Windows registry, Defender, firewall, hosts, and startup state do not
have automatic Linux equivalents, and unknown settings may belong to future or
platform-specific features.

**How to apply:** Use a versioned preserve-legacy decision table for known
patterns, keep unknown settings untouched, and never treat the decision metadata
as permission to remove the original setting.

For launcher-session recovery, canonical references must follow the full
session-row positions even when the legacy process-name sidecar contains fewer
entries. The compact legacy list is compatibility data, not the authoritative
selected-app list.

**Why:** Path-only references have no legacy process name. Compacting them out
shifts later sidecar positions and can associate a recovered process name with
the wrong selected application.

**How to apply:** Persist one session slot per selected reference, keep
compatibility process rows separate, and clear session-owned references and
runtime definitions during teardown. Never infer canonical identity from a
compacted process-name index.