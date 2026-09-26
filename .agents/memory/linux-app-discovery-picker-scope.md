---
name: Linux app discovery and picker scope
description: Guardrails for making FocusFlow's app-selection flows use one Linux-aware catalog.
---

The Linux app-discovery workstream must converge all app-selection flows on one refreshable catalog. The catalog is intended to represent native and user-created `.desktop` applications, Flatpak and Snap applications, running-only processes, display and launch identity, package source, process names, aliases, icon references, and running state. It must also tolerate stale or unresolved saved references.

**Why:** App blocking, scheduling, and related selection screens otherwise drift into different platform-specific representations, while Linux launch and process identities do not map cleanly to Windows-style executable names.

**How to apply:** Keep scanner, icon, database, and process-discovery I/O off Compose/UI threads. Manual Linux process entries must preserve the user's value without automatically appending `.exe`; do not scan the entire home directory for arbitrary AppImages unless the authoritative plan explicitly decides to. Preserve working Windows behavior and make only the assigned tracker changes.

The authoritative plan for this scope is `work/linux-app-discovery-and-pickers-plan.md`; related work belongs in the enforcement/release-tests, shared-platform-boundary, and stored-data-migration plans. Tracker items require focused implementation evidence before being checked; compilation alone is insufficient. When blocked, leave the item unchecked and record the exact blocker.