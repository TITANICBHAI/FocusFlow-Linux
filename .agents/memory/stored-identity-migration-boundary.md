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