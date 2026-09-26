---
name: Linux workstream batch execution protocol
description: FocusFlow Linux plans are executed in batches of up to seven tracker items with strict evidence-based checkbox updates.
---

FocusFlow's Linux workstreams are intentionally executed in batches of no more
than seven tracker items. The assigned agent must stay within its batch, update
the relevant plan tracker, and tick an item only after implementation and
focused verification are complete.

**Why:** The Linux migration spans app discovery, enforcement, platform
separation, and data compatibility. Small batches make regressions easier to
review and prevent an agent from silently changing the scope of a release
critical workstream.

**How to apply:** Before implementation, assign one documented batch and read
its plan, project instructions, and relevant memory. At completion, record
changed files, verification, blockers, and the next batch. Never use a compile
pass alone as evidence for a checked tracker item.