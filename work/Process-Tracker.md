# Process Identity Implementation Tracker

The full, normative implementation contract is [`Process.md`](Process.md). This
tracker is the repository-specific execution plan for that contract.

## Authority and execution rules

- Treat `Process.md` as the specification. Its V7 final amendment and final gate
  override older or repeated wording elsewhere in the document.
- These batches are grouped by FocusFlow's code dependencies and verification
  needs, not by the specification's numbered phases.
- Work one explicitly assigned batch at a time. Each batch contains no more than
  seven checkable items.
- Keep every item unchecked until its implementation and focused acceptance
  checks have both passed. A successful compile alone is not sufficient.
- Preserve Windows behavior and all existing user data. Do not revert unrelated
  pre-existing workspace changes.
- Do not launch subagents for this workstream.
- Record evidence in the completion log before moving to another batch.

## Current-code anchors observed while creating this tracker

These are starting points, not a substitute for checking the current code when a
batch begins:

- `LinuxProcessSafety` currently includes generic `java` in its launcher-safe
  names and protects descendants of the FocusFlow process.
- `ProcessMonitor` and `FocusLauncherService` currently use process-name sets
  for important enforcement and launcher authorization paths.
- The repository already has `CanonicalAppReference` / `StoredAppReference`
  models and additive app-reference migrations. Extend that system rather than
  creating a disconnected identity store; verify the current schema before
  choosing a migration version.
- The Linux catalog already carries useful desktop/package/process metadata.
  Keep catalog correlation separate from raw process observations and from
  authorization.

## Non-negotiable V7 safety contract

- A v1 authorization-bearing process identity is `(pid, non-null start ticks)`.
  PID alone, `(pid, null)`, or a pidfd must never authorize or permit destructive
  enforcement.
- A pidfd is optional live-operation/revalidation hardening only. It cannot
  upgrade an unknown process identity.
- Use one authoritative raw Linux process snapshot. Runtime interpretation and
  installed-app/catalog correlation are derived layers, not extra raw-snapshot
  fields.
- Authorization has three outcomes: `ALLOW`,
  `DENY_AND_SAFE_TO_TERMINATE`, and `UNKNOWN` / insufficient or unsafe data.
  “Not allowed” does not by itself mean “safe to kill.”
- Generic runtimes such as Java, Python, Node, shells, or browsers are not
  globally safe application identities. FocusFlow ancestry and cgroup membership
  are not blanket protection or authorization.
- Process creation and same-instance `exec()`/image changes both matter.
  Observation/capture is not authorization; candidate processes are not yet
  associated processes.
- Never persist raw command lines that could contain credentials or tokens.
- Keep Linux changes behind platform boundaries and preserve working Windows
  paths.

## Batch 0 — Establish the safe authorization boundary

Prerequisite: none. Do this before broadening Linux identity behavior.

- [x] **PI-0.1 — Classify every global Linux protection**
  Maintain a reasoned inventory for FocusFlow-owned runtime, system-critical,
  and desktop-session protections. Every entry needs a category, reason, and
  regression coverage; do not use a blanket process-name exception.
- [x] **PI-0.2 — Remove generic runtime names from global authorization**
  Arbitrary Java/Python/Node/etc. processes must not become safe merely because
  their executable name matches a runtime. If bare runtime names remain blocked
  as manual targets, that restriction must be separate from global protection.
- [x] **PI-0.3 — Scope FocusFlow protection to verified ownership**
  Protect the actual FocusFlow process without treating every descendant as
  globally protected. Protect the exact running process only; do not infer
  ownership from a name or process-tree relationship.
- [x] **PI-0.4 — Add safety-boundary regressions**
  Cover generic runtimes versus the protected-name inventory, the exact
  FocusFlow PID, an ordinary child and an unknown PID, category/rationale
  coverage, and unchanged Windows behavior. Start-tick/pidfd identity tests
  belong to Batch 1.

## Batch 1 — Build authoritative Linux process observations

Depends on: Batch 0.

- [x] **PI-1.1 — Define the raw process snapshot**
  Keep it limited to observed Linux process facts; do not mix in runtime family,
  application catalog IDs, package identity, or authorization decisions.
- [x] **PI-1.2 — Implement one canonical process repository**
  Produce one consistent snapshot generation per sweep and make downstream
  discovery/enforcement consume it instead of re-reading `/proc` independently.
- [x] **PI-1.3 — Parse procfs robustly**
  Handle parenthesized `comm`, NUL-separated arguments, executable/cwd links,
  start ticks, inaccessible or disappearing processes, and partial observations.
- [x] **PI-1.4 — Enforce the process-instance identity rule**
  Construct an authorization key only when both PID and non-null start ticks
  are known. Represent missing identity explicitly as unknown.
- [x] **PI-1.5 — Detect same-PID image/exec changes**
  Add a derived fingerprint that can detect relevant process changes without
  treating a reused PID as the old process.
- [x] **PI-1.6 — Test identity and observation edge cases**
  Cover procfs parsing, partial data, process disappearance, PID reuse, same-PID
  exec, and `(pid, null)` with a live pidfd remaining unknown and non-killable.

## Batch 2 — Add selectors, matching, and explicit authorization results

Depends on: Batch 1.

- [x] **PI-2.1 — Define serializable `ALL` / `ANY` selector expressions**
  Specify empty-expression and short-circuit semantics explicitly; serialization
  must not rely on ambiguous defaults.
- [x] **PI-2.2 — Add typed selectors and per-type comparison rules**
  Cover process name, executable basename/path, desktop/package identity,
  structured arguments, main class, working directory, runtime family, and
  execution environment. Paths remain case-sensitive.
- [x] **PI-2.3 — Separate runtime family from execution environment**
  A runtime such as Java and an environment such as a container/package source
  are distinct facts and must not be collapsed into one selector.
- [x] **PI-2.4 — Return evidence-bearing attribution results**
  Support zero, one, and multiple matching applications; preserve ambiguity and
  confidence as evidence rather than turning either into authorization.
- [x] **PI-2.5 — Implement the three-state authorization decision**
  Keep `ALLOW`, proven-safe denial, and unknown/unsafe distinct; require final
  process-instance revalidation before any destructive action.
- [x] **PI-2.6 — Make argument interpretation structured and privacy-safe**
  Remove arbitrary argv-token aliases and dotted-token package inference. Do not
  persist or log raw command lines that may contain secrets.
- [x] **PI-2.7 — Add selector/matcher/auth tests**
  Cover boolean semantics, case rules, missing fields, ambiguity, authorization
  states, and the difference between “observed” and “authorized.”

## Batch 3 — Bridge canonical identity into stored references safely

Depends on: Batch 2. Extend the existing sidecar and migration safety design.

- [x] **PI-3.1 — Define the reference/runtime/launch model boundary**
  Give every internal reference a stable `referenceId`; keep external app IDs
  optional, process names optional/legacy, and launch definitions separate from
  runtime definitions.
- [x] **PI-3.2 — Persist runtime and launch definitions additively**
  Version selector serialization and add only the schema needed after inspecting
  the current database version. Keep legacy source fields readable and intact.
- [x] **PI-3.3 — Preserve stale, ambiguous, and generic legacy references**
  Do not silently rewrite unresolved values or convert a legacy `java` entry
  into a broad runtime allow rule.
- [x] **PI-3.4 — Add repository adapters and round-trip operations**
  Create/read/update/delete the new reference metadata while maintaining
  compatibility with existing process-bearing models and owners.
- [x] **PI-3.5 — Gate migration with verified backup and atomic rollback**
  Follow the existing migration backup/atomicity contract; migration failure
  must not replace or corrupt the live database.
- [x] **PI-3.6 — Add migration, rollback, and compatibility fixtures**
  Cover fresh, legacy, duplicate, unresolved, malformed, and interrupted cases;
  prove original user data remains readable after success and failure.

## Batch 4 — Connect the app catalog and Linux target-selection flows

Depends on: Batch 3.

- [ ] **PI-4.1 — Add a non-authoritative catalog resolver**
  Correlate desktop/package/catalog metadata with process observations in a
  derived layer. A resolver result is evidence, never authorization.
- [ ] **PI-4.2 — Preserve existing catalog metadata**
  Reuse process aliases and desktop/package fields already discovered by the
  scanner, while removing arbitrary command-token aliases and heuristic package
  identity.
- [ ] **PI-4.3 — Move Linux selection to stable application references**
  Integrate installed, running-only, manual, stale, and refreshed catalog entries
  without resetting in-progress selections during refresh.
- [ ] **PI-4.4 — Support the specified manual target types**
  Provide installed/running selection plus advanced process name, executable
  path, structured command predicate, and desktop/package identity inputs.
- [ ] **PI-4.5 — Surface ambiguity and match diagnostics**
  Explain what a rule will match and let the user resolve ambiguous choices;
  paths must not be rejected just because they are not process names.
- [ ] **PI-4.6 — Keep UI work safe and test the target flows**
  Keep database, filesystem, and process I/O off Compose/UI threads. Test manual
  target parsing, stale-reference resolution, catalog refresh, and ambiguity.

## Batch 5 — Migrate Focus Launcher authorization to references

Depends on: Batches 2 and 3; Batch 4 selection work may be completed first or
landed with this batch if its tracker scope is explicitly assigned.

- [x] **PI-5.1 — Replace Linux launcher name-set authorization**
  Make selected application references and runtime definitions the Linux
  authorization inputs; keep legacy process-name adaptation at a single boundary.
- [x] **PI-5.2 — Associate only matched, known process instances**
  Require a valid process-instance key and a definite application attribution
  before adding an instance to active session authorization.
- [x] **PI-5.3 — Separate global protection from session authorization**
  System/session safety, FocusFlow ownership, selected applications, and
  launcher-session associations must remain distinct policy sources.
- [x] **PI-5.4 — Revalidate before destructive enforcement**
  Recheck identity and authorization immediately before a kill; unknown or
  changed identity is not a destructive target.
- [x] **PI-5.5 — Test Focus Launcher state and platform compatibility**
  Cover selected and unselected apps, unrelated generic runtimes, ambiguous or
  stale references, lifecycle teardown, and unchanged Windows behavior.

## Batch 6 — Implement launch capture and safe handoff

Depends on: Batches 1, 2, 3, and 5.

- [x] **PI-6.1 — Treat Launch & Detect as configuration-time discovery**
  A user-triggered launch flow builds a launch definition; it is not itself a
  permanent runtime authorization rule.
- [x] **PI-6.2 — Isolate capture to a launch-instance baseline**
  Record the baseline at launch and observe only relevant changes for that launch
  instance; pre-existing processes must not become candidates by default.
- [x] **PI-6.3 — Detect both process creation and same-instance exec**
  Polling is the required first backend. Detect newly created PIDs and relevant
  image/argument changes on a still-live PID.
- [x] **PI-6.4 — Keep candidate and associated states separate**
  Observation, timing, parentage, or cgroup membership alone cannot authorize a
  candidate. Define explicit states and expiry/timeout behavior.
- [x] **PI-6.5 — Handle launcher exit and reparenting**
  Preserve valid app attribution across launcher exit/reparenting without
  assuming that launcher termination proves the runtime has started.
- [x] **PI-6.6 — Make launcher auto-close identity-safe**
  Revalidate the launcher's current PID plus start ticks immediately before
  closing it; do not close if that same instance has become the runtime or its
  identity is ambiguous.
- [x] **PI-6.7 — Test launch races and cleanup**
  Cover baseline isolation, candidate-to-associated transition, exec handoff,
  launcher exit/reparenting, cancellation, timeout, runtime exit, PID reuse, and
  same-instance close safety.

## Batch 7 — Add runtime and Minecraft attribution

Depends on: Batches 2 and 6.

- [x] **PI-7.1 — Implement generic runtime interpretation**
  Derive runtime family/environment and structured launch facts without turning
  a generic runtime executable into an application identity.
- [x] **PI-7.2 — Implement explicit Minecraft attribution modes**
  Support direct runtime identification and launch-session attribution as
  separate, explainable modes.
- [x] **PI-7.3 — Parse Java arguments semantically**
  Handle supported argument forms and selectors without relying on a single
  hardcoded main class or on `--gameDir` alone.
- [x] **PI-7.4 — Keep unrelated Java unauthorized**
  Vanilla and supported mod-loader variants may match Minecraft; unrelated Java
  must remain outside that association.
- [x] **PI-7.5 — Test resolver boundaries and handoff cases**
  Include game-directory-only negative cases, launcher-to-JVM timing, unrelated
  Java, supported runtime variants, and desktop/package correlation that is
  explicitly non-authoritative.

## Batch 8 — Converge Linux consumers and close acceptance gates

Depends on: Batches 1–7.

- [ ] **PI-8.1 — Route legacy process-name consumers through one adapter**
  Compatibility consumers must use the canonical repository/matcher; do not
  create parallel Linux matching semantics.
- [ ] **PI-8.2 — Migrate standard Linux process-targeting paths**
  Cover app blocking, schedules, standalone blocks, daily allowance, session
  extra-blocks, and network process targeting where applicable.
- [ ] **PI-8.3 — Keep Nuclear Mode and safety inventories purpose-specific**
  Do not conflate escape-tool blocking with global protection or selected-app
  authorization; preserve reasoned categories.
- [ ] **PI-8.4 — Make unknown/ambiguous enforcement outcomes observable**
  Diagnostics must explain why a process was allowed, safely denied, or left
  unenforced as unknown without exposing raw secret-bearing arguments.
- [ ] **PI-8.5 — Run focused and full Linux verification**
  Verify parser/matcher/migration/enforcement/launcher tests and the full Linux
  suite. Record any environment-dependent tests that could not run.
- [ ] **PI-8.6 — Verify Windows compatibility**
  Run relevant Windows compilation/tests or documented checks and confirm no
  Windows targeting, enforcement, or persisted-data behavior was removed.
- [ ] **PI-8.7 — Complete the V7 definition-of-done audit**
  Check every applicable category in `Process.md` §52 and link evidence below;
  no unresolved safety blocker may be represented as complete.

## Deferred, optional hardening (not core-v1 completion)

Only start these after Batch 8 is complete and the user assigns the work:

- Process-creation event backend.
- pidfd-backed operations, only for an already validated PID + start-ticks key.
- Dedicated systemd/cgroup launch scopes; cgroup remains evidence, never blanket
  authorization.

## Batch completion evidence

Add one row per completed batch. Do not claim completion based only on a build.

| Batch | Date | Tracker IDs completed | Changed areas | Commands/tests and results | Manual evidence or environment limits | Next batch |
|---|---|---|---|---|---|---|
| 0 | 2026-09-30 | PI-0.1–PI-0.4 | LinuxProcessSafety, InstalledAppsScanner, LinuxAppPicker, ProcessMonitor, LinuxProcessSafetyTest | `gradle test --tests com.focusflow.enforcement.LinuxProcessSafetyTest --tests com.focusflow.enforcement.NuclearModeLinuxTest --tests com.focusflow.enforcement.InstalledAppsScannerLinuxTest --tests com.focusflow.ui.components.LinuxAppPickerFilterTest --tests com.focusflow.enforcement.WinApiBindingsLinuxTest --no-daemon` — passed (34 tests); `git diff --check` — passed | No Windows runtime available. Direct diff review confirmed Windows-specific safe-name and manual-target paths are unchanged; new generic-runtime target restriction is Linux-gated. | Batch 1 |
| 1 | 2026-09-30 | PI-1.1–PI-1.6 | LinuxProcessRepository and LinuxProcessRepositoryTest; consumers use the shared repository | `gradle test --tests com.focusflow.enforcement.LinuxProcessRepositoryTest --no-daemon` — passed; source audit found `/proc` process enumeration only in LinuxProcessRepository (specialized socket-table reads remain in NetworkBlocker) | Repository test suite covers procfs parsing, partial/disappearing processes, generation IDs, PID reuse, same-instance exec fingerprints, and live-pidfd/missing-start-ticks non-identity. No Windows runtime available. | Batch 2 |
| 2 | 2026-09-30 | PI-2.1–PI-2.7 | LinuxProcessSelectors, LinuxProcessRuntimeMetadata, LinuxProcessMatcher, privacy-safe Linux snapshot formatting, InstalledAppsScanner Linux running-process interpretation, focused tests | `gradle test --tests com.focusflow.enforcement.LinuxProcessSelectorsTest --tests com.focusflow.enforcement.LinuxProcessMatcherTest --tests com.focusflow.enforcement.InstalledAppsScannerLinuxTest --tests com.focusflow.enforcement.LinuxProcessRepositoryTest --tests com.focusflow.enforcement.LinuxProcessSafetyTest --no-daemon` — passed (47 tests); `git diff --check` — passed | Covered versioned selector round-trips and empty-expression validation; typed comparison rules; missing-field and correlation uncertainty; multi-candidate ambiguity; Java structured args; generic/broad selector UNKNOWN; identity-bound revalidation; and argv redaction. Windows runtime unavailable; changed scanner interpretation is Linux-only and Windows enforcement paths were not edited. | Batch 3 |
| 3 | 2026-09-30 | PI-3.1–PI-3.6 | `UpgradeValidationTest`; verified `AppReference`, migrations V9–V11, database CRUD, and preflight-backup/rollback paths | `gradle test --tests com.focusflow.data.StoredDataMigrationV9Test --tests com.focusflow.data.StoredDataMigrationV10Test --tests com.focusflow.data.StoredDataMigrationV11Test --tests com.focusflow.data.MigrationSafetyTest --tests com.focusflow.data.StoredDataFixtureTest --tests com.focusflow.data.UpgradeValidationTest --tests com.focusflow.data.models.CanonicalAppReferenceTest --no-daemon` — passed (27 tests); `git diff --check` — passed | Temporary SQLite fixtures covered fresh, legacy, duplicate, stale/unresolved, generic `java`, malformed source data, CRUD round-trips, backup failure, and interrupted migration rollback. No production database or Windows runtime was used. | Batch 4 |
| 5 | 2026-09-30 | PI-5.1–PI-5.5 | FocusLauncherScreen reference propagation; FocusLauncherService lifecycle/restore paths; FocusLauncherRuntimePolicy and ProcessMonitor; session reference/runtime-definition persistence and cleanup; policy and SQLite tests | `gradle test --no-daemon` — passed (154 tests, 0 failures, 0 errors, 2 skipped); `git diff --check` — passed; Start application workflow restarted and remained running at `:run` | Tests cover selected/unselected refs, generic Java uncertainty, ambiguity/staleness, unknown/changed identity, authorization clearing, Windows legacy name-set adapter, and DB recovery/teardown. No Windows runtime or real destructive process test was available; VNC workflow has no browser port. Batch 4 checkboxes were left unchanged. | Batch 6 |
| 6 | 2026-09-30 | PI-6.1–PI-6.7 | LinuxLaunchCapture tracker/controller and tests; Linux Launch & Detect dialog and Focus Launcher integration; persisted launch/runtime definitions and session-cleanup preservation; launcher runtime association metadata | `gradle test --no-daemon` — passed (165 tests, 0 failures, 0 errors, 2 skipped); `git diff --check` — passed; Start application workflow restarted and running | Tests cover baseline isolation, explicit candidate attribution, same-PID exec, reparenting, bounded grace/timeout, cancellation, runtime exit, PID reuse, unknown identity, close identity checks, raw command omission, and SQLite persistence through cleanup. Desktop app process was running under the VNC workflow; no browser screenshot port, real app handoff, destructive close, or Windows runtime was exercised. | Batch 7 |
| 7 | 2026-10-01 | PI-7.1–PI-7.5 | LinuxProcessRuntimeMetadata and LinuxMinecraftAttribution; launch-session evidence and attribution in LinuxLaunchCapture; focused parser, selector, catalog-boundary, and handoff tests | `gradle test --tests com.focusflow.enforcement.LinuxMinecraftAttributionTest --tests com.focusflow.enforcement.LinuxProcessMatcherTest --tests com.focusflow.enforcement.LinuxLaunchCaptureTest --no-daemon` — passed (33 tests); `gradle test --no-daemon` — passed (175 tests, 0 failures, 0 errors, 2 skipped); `git diff --check` — passed; Start application workflow restarted and remained running | Tests cover Java runtime/environment derivation, structured class/JAR/module arguments and redaction, Vanilla/Fabric/Quilt/Forge/NeoForge signatures, game-directory-only and unknown-option negatives, non-authoritative catalog correlation, unrelated Java denial, selected-launcher-to-JVM timing, and reparenting. No live Minecraft launcher/runtime or Windows runtime was exercised; two tests were skipped by the existing suite configuration. | Batch 4 remains open; Batch 8 still depends on completion of Batches 1–7. |
