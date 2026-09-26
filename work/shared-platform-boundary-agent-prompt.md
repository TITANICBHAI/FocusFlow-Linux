# Prompt for the Shared and Platform Code Separation Agent

Copy the prompt below to a new agent. Replace `<ASSIGNED_SCOPE>` with the
tracker IDs or phase you want that agent to handle.

---

You are working on FocusFlow's shared-code and platform-boundary workstream.

## Project context

FocusFlow is a Kotlin/JVM Compose Desktop productivity application. The
long-term product direction is Linux-only, but Windows support is still being
preserved temporarily. This task separates the code first; it does not
immediately delete Windows support.

The authoritative plan is:

```text
work/shared-platform-boundary-plan.md
```

Related plans:

```text
work/linux-app-discovery-and-pickers-plan.md
work/linux-enforcement-and-release-tests-plan.md
work/stored-data-migration-plan.md
```

Read `replit.md`, the authoritative plan, current source/tests,
`.agents/memory/MEMORY.md`, and linked memory topics before editing.

## Assigned work

You are assigned exactly:

```text
<ASSIGNED_SCOPE>
```

Implement only that scope. Do not begin broad Windows removal, database
migration, or unrelated Linux feature work unless the assigned tracker item
requires it.

## Workstream goal

Separate shared product behavior from operating-system implementation.

Shared code should contain:

- Focus sessions, tasks, schedules, allowances, and reports
- Block-rule intent and validation
- Shared app identity and selection state
- Database repositories and migrations
- Session lifecycle and user-facing capability state

Platform code should contain:

- Process discovery and termination
- Foreground/window detection
- Installed-app and icon lookup
- Startup and watchdog
- Hosts and firewall operations
- VPN detection
- Tray, notifications, overlays, and panel behavior
- Platform-specific process lists and native commands

## Non-negotiable rules

1. Preserve Windows behavior during the temporary migration period.
2. Do not perform a large rewrite when a small extraction is sufficient.
3. Shared code must not import Win32/JNA classes or construct Linux shell
   commands directly.
4. UI screens must not own native command construction.
5. Database repositories must not call platform services.
6. Platform operations must return explicit capability states, not misleading
   Boolean success values where permission or Wayland state matters.
7. Keep I/O and platform probes off Compose/UI threads.
8. Do not change stored-data behavior unless explicitly assigned.
9. Do not delete Windows files, resources, or build targets in this workstream
   unless the assigned scope explicitly reaches the final removal phase.
10. Never tick a tracker item based on compilation alone.

## Required workflow

1. Read the authoritative plan and identify the exact assigned tracker items.
2. Inspect the current mixed-platform implementation and tests.
3. Define or use the smallest appropriate capability boundary.
4. Implement only the assigned scope.
5. Add focused contract, fake-platform, Linux, or regression tests as needed.
6. Run the complete verification appropriate to the assigned scope.
7. Tick only verified `PLAT-*` items in
   `work/shared-platform-boundary-plan.md`.
8. Leave blocked items unchecked and record the exact dependency.
9. Report changed files, verification evidence, remaining risks, and suggested
   next scope.
10. Do not claim Windows-removal readiness until the app-discovery,
    enforcement, and data-migration plans are also complete.

## Final response format

At the end, report:

- Assigned scope
- Tracker items completed and ticked
- Interfaces or boundaries introduced
- Files changed
- Tests/checks run and results
- Items left unchecked
- Blockers or compatibility risks
- Suggested next scope

Do not implement or tick outside the assigned scope.

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned scope: <ASSIGNED_SCOPE>
```