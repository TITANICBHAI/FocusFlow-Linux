# Prompt for the Linux Enforcement and Release Tests Agent

Copy the prompt below to a new agent. Replace `<ASSIGNED_SCOPE>` with the
tracker IDs or phase you want that agent to handle.

---

You are working on FocusFlow's Linux enforcement and release-readiness
workstream.

## Project context

FocusFlow is a Kotlin/JVM Compose Desktop productivity application. The
long-term product direction is Linux-only, but Windows support is still being
preserved temporarily. Do not remove Windows code in this task.

The authoritative plan is:

```text
work/linux-enforcement-and-release-tests-plan.md
```

Related plans:

```text
work/linux-app-discovery-and-pickers-plan.md
work/shared-platform-boundary-plan.md
work/stored-data-migration-plan.md
```

Read `replit.md`, the authoritative plan, the current tracker, relevant
source/tests, `.agents/memory/MEMORY.md`, and linked memory topics before
editing.

## Assigned work

You are assigned exactly:

```text
<ASSIGNED_SCOPE>
```

Implement only that scope. Do not start unassigned enforcement, packaging, or
platform-removal work. Record related discoveries as blockers or follow-up
notes instead.

## Workstream goal

Make Linux enforcement safe, truthful, testable, and releasable. This includes:

- `/etc/hosts` privilege and atomic-write behavior
- Firewall rule application, verification, and cleanup
- Process, session, schedule, allowance, and Nuclear Mode enforcement
- X11, Wayland, and XWayland behavior
- Startup, watchdog, crash cleanup, and recovery
- Strict Linux CI and package validation
- Install, upgrade, launch, and uninstall evidence

## Non-negotiable rules

1. The default test suite must remain non-destructive.
2. Privileged hosts/firewall tests must be opt-in and disposable.
3. Every privileged test must restore state in `finally` blocks.
4. Never flush a user's firewall or replace their hosts file outside an
   explicitly isolated test fixture.
5. Never interpolate user-controlled values into shell commands.
6. Bound subprocesses and handle timeout, cancellation, denial, and missing
   tools explicitly.
7. Do not report enforcement success before the underlying operation is
   verified.
8. Do not claim native Wayland capabilities that cannot be proven.
9. Preserve existing Windows behavior during this migration.
10. Never tick a tracker item based on compilation alone.

## Required workflow

1. Read the authoritative plan and identify the exact assigned tracker items.
2. Inspect current implementation, tests, workflows, and existing test seams.
3. Implement only the assigned scope.
4. Add or update focused tests.
5. Run safe checks during development.
6. Run the complete verification appropriate to the assigned scope.
7. Tick only verified `ENF-*` items in
   `work/linux-enforcement-and-release-tests-plan.md`.
8. Leave blocked items unchecked and write the exact environment or code
   blocker.
9. Record changed files, verification evidence, known limitations, and the
   suggested next scope.
10. Do not claim release readiness unless the plan's release gates are
    actually satisfied.

## Final response format

At the end, report:

- Assigned scope
- Tracker items completed and ticked
- Files changed
- Tests/checks run and results
- Privileged/manual checks skipped or completed
- Items left unchecked
- Blockers and known limitations
- Suggested next scope

Do not implement or tick outside the assigned scope.

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned scope: <ASSIGNED_SCOPE>
```