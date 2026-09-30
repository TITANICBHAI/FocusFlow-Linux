# FocusFlow Linux Process / Application Identity — Implementation Specification v6

**Audience:** Diff/implementation agent that will modify the supplied project.

**Status:** FINAL PRE-CODING implementation contract. This document supersedes v2, v3, v4, and v5. Any wording elsewhere in the document that conflicts with the v6 amendments below is overridden by v6.

**Prepared by:** Planner + Inspector, incorporating all prior planner/inspector reviews plus the final v6 pre-coding review amendments.

**Scope:** Linux process discovery, application identity, manual application targeting, Focus Launcher enforcement, launcher-to-runtime handoff (especially Minecraft), process safety, and all Linux process-targeting paths that depend on the same identity semantics.

**Non-goal:** Do not broadly rewrite unrelated product areas. Preserve Windows behavior except where a shared model change is required for compatibility.

---

# V6 FINAL REVIEW AMENDMENTS — NORMATIVE OVERRIDES

This section is the final contract patch after the v5 review. It overrides any contradictory wording elsewhere in this document. The implementation/diff agent MUST implement these rules exactly.

## V6-1. Authorization-bearing process identity is PID + kernel start ticks only in v1

For v1, the authorization/domain identity of a live Linux process MUST be represented by a non-null kernel-derived start-time identity together with the PID:

```text
PID + processStartTicks
```

`pidfd` is **not** part of authorization identity in v1. It is an optional live-kernel operation handle used to harden operations such as termination and liveness checks. It MUST NOT participate in domain equality/hash semantics and MUST NOT be persisted as durable application identity.

Therefore the authoritative domain value object is:

```kotlin
data class ProcessInstanceKey(
    val pid: Long,
    val processStartTicks: Long
)
```

There is deliberately no nullable `processStartTicks` in this authorization-bearing key.

A process observation for which `processStartTicks` cannot be obtained MUST be represented outside `ProcessInstanceKey`, for example as a non-authoritative `ProcessObservation`/PID observation. Such an observation may be displayed, inspected, and used for non-authoritative discovery, but it MUST NOT become an authorization-bearing process instance.

Hard invariant:

```text
KNOWN_PROCESS_INSTANCE
    = PID + processStartTicks

UNKNOWN_PROCESS_INSTANCE
    = start ticks unavailable

UNKNOWN_PROCESS_INSTANCE
    -> observable
    -> non-authoritative
    -> never associated
    -> never launcherInstance
    -> never primaryRuntimeInstance
    -> never authorization-bearing
```

If a live pidfd exists while start ticks are unavailable, the pidfd MAY be used for safe live operations and revalidation, but it does **not** promote the observation into the v1 authorization identity model. This intentionally chooses the simpler v1 domain model: start ticks identify the instance; pidfd hardens operations.

If start ticks later become readable, construct the `ProcessInstanceKey` at that point and perform fresh attribution/matching. Do not retroactively treat the prior `(pid, null)` observation as an identity.

### Required regression scenario

```text
T1: PID X = Minecraft, startTicks unavailable
T1: process may be observed/candidate only; no associated identity exists
T2: Minecraft exits
T3: PID X is reused by unrelated Java
T3: startTicks still unavailable
```

Expected:

```text
new PID X is NOT associated with Minecraft
new PID X is NOT authorized as Minecraft
old observation cannot transfer authorization across PID reuse
```

Also test the case where a live pidfd exists but start ticks remain unavailable:

```text
PID X + pidfd A + no startTicks
    -> operation handle only
    -> not an authorization identity
```

## V6-2. FocusFlow ancestry is never a global protection rule

Being a descendant of FocusFlow MUST NOT, by itself, classify a process as `FOCUSFLOW_RUNTIME` or otherwise grant global protection.

`FOCUSFLOW_RUNTIME` is limited to:

```text
FocusFlow's own validated process instance(s)
+
explicitly designated FocusFlow helper/runtime processes
```

It MUST NOT mean:

```text
all descendants of FocusFlow
all children spawned by FocusFlow
all processes sharing FocusFlow's cgroup
all processes created during a FocusFlow launch
```

Application launcher/runtime processes are protected through their explicit `LaunchSession` authorization rules, not through FocusFlow ancestry.

### Required regression scenarios

1. FocusFlow starts an unauthorized terminal directly.
2. FocusFlow starts a selected Minecraft launcher through a `LaunchSession`.

Expected:

```text
unauthorized terminal
    != FOCUSFLOW_RUNTIME
    != globally protected

Minecraft launcher
    = authorized only through its launch-session bootstrap/handoff state
```

No ancestry-only allow rule is permitted.

## V6-3. Raw snapshot vs derived runtime metadata vs catalog correlation

The Linux process stack MUST keep three layers distinct:

```text
LinuxProcessSnapshot
    -> ProcessRuntimeMetadata
    -> ProcessApplicationCorrelation
```

### `LinuxProcessSnapshot`

Raw process/kernel observation only. This is the **single authoritative raw snapshot model** in this specification. It may contain:

```text
pid
processStartTicks
uid
parentPid
processGroupId
sessionId
comm
executablePath
executableBasename
argv
workingDirectory
cgroupPath
field availability/errors
```

Do not require this layer to resolve desktop IDs, package IDs, or application-specific runtime semantics.

### `ProcessRuntimeMetadata`

Derived, deterministic interpretation of a snapshot, preferably lazily computed and cached. Examples:

```text
runtimeFamily
executionEnvironment
Java mainClass when reliably parsed
Java launch artifact
structured application arguments
selected runtime markers
```

This layer owns Java/runtime argument interpretation, not the raw procfs repository.

### `ProcessApplicationCorrelation`

Catalog-level correlation from runtime snapshot/metadata to:

```text
desktop IDs
package IDs
installed application references
candidate application matches
```

This is where `ProcessApplicationResolver` belongs.

The repository MUST NOT become a per-tick monolith that performs full `/proc` scanning + Java parsing + desktop catalog lookup + package lookup for every process unconditionally. Do not add desktop/package correlation fields to the raw snapshot merely for convenience.

Preferred flow:

```text
repository snapshot
    -> lazy/cached runtime metadata
    -> lazy/cached catalog correlation when requested
    -> matcher / attribution
```

### Performance invariant

An enforcement tick MUST NOT repeatedly perform expensive derived/correlation work for the same process snapshot when cached results can be reused safely.

## V6-4. FocusFlow process-tree membership is not protection

No new rule is introduced here; V6-2 remains normative. Explicitly, an application launched by FocusFlow is protected because of its `LaunchSession`/application attribution, not because it is a descendant of FocusFlow.

## V6-5. Pidfd has one role in v1

For v1, use this rule everywhere:

```text
processStartTicks
    = authorization/domain identity

pidfd
    = optional live operation/revalidation handle
```

Do not encode pidfd identity into persisted application references, selector expressions, database keys, `ProcessInstanceKey`, `associatedInstances`, or `LaunchSession` identity fields.

## V6-6. Single authoritative raw snapshot definition

There MUST be exactly one authoritative `LinuxProcessSnapshot` model. Remove or rewrite any inherited model definition that includes fields such as:

```text
desktopCorrelation
packageCorrelation
runtimeFamily
mainClass
parsed application arguments
```

Those belong in `ProcessRuntimeMetadata` and `ProcessApplicationCorrelation`. Later sections MUST refer to the same raw snapshot contract rather than redefining a richer variant.

# V4 FINAL REVIEW AMENDMENTS — NORMATIVE OVERRIDES

This section is the final pre-coding patch after the v3 review-agent gate. It overrides any contradictory wording elsewhere in this document. The coding/diff agent MUST implement these rules exactly.

## V4-1. Launcher bootstrap candidate scope is launch-instance bound

The temporary `LAUNCH_HANDOFF_BOOTSTRAP` authorization exists to prevent a race, but it MUST NOT authorize any arbitrary process that merely matches the launcher's identity.

Before spawning the launcher, FocusFlow MUST create a `LaunchSession` with:

```text
launchSessionId
selectedApplicationReference
selectedLaunchDefinition
baselineProcessInstances
launchRootProcessInstance = UNBOUND initially
bootstrapSelector
```

When FocusFlow directly invokes the launch command, the returned process handle / PID is the **primary bootstrap candidate**. The implementation MUST validate that candidate and derive its `ProcessInstanceKey` from the live snapshot before binding it.

Fallback discovery is allowed only when direct process-handle binding is unavailable. In that fallback mode, bootstrap candidates MUST satisfy:

```text
NEW_INSTANCE after the session baseline
OR
IDENTITY_CHANGED existing instance after the session baseline
```

A process that already matched the launcher selector in the baseline MUST NOT receive bootstrap authorization merely because it is still running.

The implementation MUST NOT use an arbitrary rule such as:

```text
first process matching launcher selector
```

The expected binding priority is:

```text
1. directly spawned process handle/PID validated to exact ProcessInstanceKey;
2. otherwise a unique NEW_INSTANCE/IDENTITY_CHANGED candidate attributable to this launch;
3. otherwise leave launcher bootstrap unresolved and do not auto-select an existing matching process.
```

If multiple eligible bootstrap candidates remain ambiguous, keep the bootstrap scoped to the unresolved launch attempt rather than authorizing an arbitrary process. The session may continue discovery, but it MUST NOT broaden authorization to all matching launcher processes.

## V4-2. Authorization has three outcomes, not two

Process evaluation MUST distinguish:

```text
ALLOW
DENY_AND_SAFE_TO_TERMINATE
UNKNOWN / INSUFFICIENT_DATA / UNSAFE_TO_TERMINATE
```

Semantics:

### `ALLOW`

The process is explicitly protected or explicitly associated with the selected application/session and may remain alive under the active launcher policy.

### `DENY_AND_SAFE_TO_TERMINATE`

The process is not authorized, its identity is sufficiently established, the relevant process instance has been safely revalidated, and the enforcement policy permits destructive termination.

### `UNKNOWN / INSUFFICIENT_DATA / UNSAFE_TO_TERMINATE`

The process receives **no authorization privilege**, but FocusFlow MUST NOT destructively terminate it because process-instance identity or required evidence is unavailable or unsafe to act on.

Therefore:

```text
not authorized
    !=
kill
```

Destructive action requires a separate `SAFE_TO_TERMINATE` condition after the process instance has been revalidated.

A recommended internal decision shape is:

```text
ProcessAuthorizationDecision {
    state: ALLOW | DENY_AND_SAFE_TO_TERMINATE | UNKNOWN,
    reason,
    attribution,
    processInstanceKey,
    evidence
}
```

The exact type may differ, but the three-state semantics are mandatory.

## V4-3. Minecraft selector semantics are corrected

The canonical Minecraft runtime expression MUST NOT treat `gameDir` as a substitute for Minecraft-specific evidence.

The direct runtime selector shape is conceptually:

```text
ALL(
    runtimeFamily == JAVA,
    ANY(
        mainClass == MinecraftMain,
        recognizedMinecraftLoaderEntryPoint,
        otherStrongMinecraftRuntimeEvidence
    ),
    ANY(
        matchingGameDir,
        other strong instance/install discriminator
    )
)
```

`matchingGameDir` alone is never sufficient.

For launch-session attribution, the final association may instead combine:

```text
JAVA runtime evidence
+
Minecraft-specific runtime evidence
+
selected Minecraft LaunchDefinition
+
active LaunchSession
+
NEW_INSTANCE or IDENTITY_CHANGED
```

A launch session may strengthen attribution, but it MUST NOT convert a generic Java process with only `--gameDir` into Minecraft.

## V4-4. Java argument interpreter contract

The Java launch interpreter MUST expose semantic fields only when the launch syntax makes them reliable.

It MUST recognize, at minimum:

```text
java -cp <classpath> <main-class> ...
java -classpath <classpath> <main-class> ...
java -jar <jar> ...
java -m <module>/<main-class> ...
```

Rules:

- options that consume the next argv token MUST be parsed structurally;
- `--key value` and `--key=value` MUST both be supported where the option grammar permits;
- `-Dkey=value` remains a JVM system-property token and MUST NOT be confused with an application `--key value` argument;
- do not infer `mainClass` as the first token that merely resembles a Java class name;
- `-jar` establishes the launch artifact but does not by itself provide a Java main class unless additional trusted metadata resolves it;
- malformed or ambiguous Java argument structures produce missing semantic fields, not guessed ones.

## V4-5. `Launch & Detect` lifecycle is configuration-time discovery

`Launch & Detect` is a configuration/discovery flow, not an authorization bypass for an active inverse-kiosk session.

During `Launch & Detect`:

```text
candidate discovery
    ↓
user may inspect/select candidate
    ↓
FocusFlow derives/presents resulting matcher
    ↓
user explicitly saves the application reference
```

The implementation MUST NOT temporarily authorize an unresolved candidate merely to keep it alive during a normal configuration flow.

If the same process is simultaneously subject to active Focus Launcher enforcement, the normal launcher-session lifecycle rules apply; the configuration flow MUST NOT invent a separate implicit allow mechanism.

Before saving a generic-runtime reference, the UI MUST preview the resulting matcher and its breadth so the user can see, for example:

```text
✓ Minecraft-specific runtime evidence
✓ selected game directory
✗ arbitrary Java
```

## V4-6. Identity-change semantics are classified

The process snapshot diff MUST classify changes into at least:

```text
IDENTITY_CHANGE
METADATA_CHANGE
UNCHANGED
```

`IDENTITY_CHANGE` MUST include meaningful changes to:

```text
executablePath
runtimeFamily
executionEnvironment
parsed mainClass / launch artifact when reliable
structured application arguments used by selectors
workingDirectory when explicitly used as an application discriminator
cgroupPath when it is used as launch-session evidence
```

A change to `comm` alone MUST be `METADATA_CHANGE` unless the selected matcher explicitly uses `comm` as its primary identity selector and the implementation can establish that the change is relevant to that selector.

The diff engine MUST avoid treating routine argv/metadata mutation as a false process replacement while still detecting `exec()` image transitions.

## V4-7. Resolver boundary is non-authoritative by construction

`ProcessApplicationResolver` MUST return candidate associations only, for example:

```text
ResolverResult {
    candidates[]
    confidence
    evidence
    ambiguity
}
```

It MUST NOT:

```text
mutate authorization state
mark a process allowed
persist a resolved ApplicationReference silently
create a launcher allow rule
```

The authoritative path is:

```text
snapshot
    ↓
resolver candidates
    ↓
explicit matcher/attribution evaluation
    ↓
authorization decision
```

## V4-8. Protected-process inventory is mandatory implementation evidence

For every globally protected process category retained or introduced on Linux, the implementation MUST document:

```text
process identifier / selector
category
why protection is required
which feature depends on it
regression test
```

The three conceptual categories remain:

```text
FOCUSFLOW_RUNTIME
SYSTEM_CRITICAL
DESKTOP_SESSION
```

Application runtime association MUST NOT be used as a substitute for these global safety categories.

## V4-9. Compatibility consumers must converge

During phased migration, any remaining consumer using legacy `processName` values MUST obtain them from a compatibility adapter over the canonical reference/matcher system.

No implementation phase may introduce a new independent Linux process-name matcher.

The intermediate architecture may therefore contain:

```text
canonical matcher
        ↓
compatibility adapter
        ↓
legacy consumer
```

but MUST NOT contain two independent semantic implementations.

## V6-7. V6 mandatory acceptance additions

The implementation MUST add tests for:

1. an already-running launcher with the same identity is present in the baseline while FocusFlow starts a second launcher;
2. the directly spawned launcher process is selected by its validated returned process handle rather than an arbitrary matching PID;
3. an ambiguous launcher bootstrap candidate does not authorize the wrong process;
4. `ALLOW`, `DENY_AND_SAFE_TO_TERMINATE`, and `UNKNOWN` produce distinct behavior;
5. a process with no safe instance identity is not destructively terminated even when not authorized;
6. Minecraft `java + gameDir` without Minecraft-specific evidence is not attributed;
7. Java `-cp`, `-jar`, and `-m` parsing follows semantic rules rather than token-shape guessing;
8. `Launch & Detect` does not create temporary enforcement authorization;
9. a resolver-only candidate does not mutate authorization state;
10. **any** process observation with unavailable start ticks cannot enter `associatedInstances` or `primaryRuntimeInstance`, regardless of whether a pidfd exists;
11. PID reuse while start-time identity is unavailable cannot transfer an old application association;
12. a pidfd without start ticks is operation-only and does not create authorization identity;
13. a direct FocusFlow descendant is not globally protected merely due to ancestry;
14. a selected application launched by FocusFlow is protected by launch-session policy rather than ancestry;
15. raw snapshot collection does not eagerly perform catalog/package resolution for every process;
16. `LinuxProcessSnapshot` contains only raw observation fields, while runtime metadata and catalog correlation are produced by their dedicated layers.


# V3 FINAL REVIEW AMENDMENTS (historical; superseded by V4) — NORMATIVE OVERRIDES

This section is intentionally placed before the detailed inherited specification. The implementation agent MUST follow these amendments whenever any later text is less precise or conflicts with them.

## A. Focus Launcher bootstrap authorization

Before FocusFlow starts a Focus Launcher application's launcher process, it MUST:

```text
create LaunchSession
    ↓
register temporary LAUNCH_HANDOFF_ONLY launcher selector
    ↓
capture baseline
    ↓
start process observer/polling
    ↓
spawn launcher
    ↓
validate the directly spawned process instance when possible
    ↓
otherwise resolve a unique NEW_INSTANCE / IDENTITY_CHANGED launcher candidate
    ↓
bind exact launcher ProcessInstanceKey
```

It MUST NOT bind an arbitrary "first matching launcher" that already existed in the baseline.

The temporary bootstrap authorization exists specifically to prevent the normal enforcement sweep from killing the launcher in the short interval between process creation and full launcher-instance attribution.

Bootstrap authorization:

- applies only to the selected `LaunchDefinition` launcher identity;
- applies only to the active `LaunchSession`;
- expires when the launcher exits, the session fails, or handoff completes;
- MUST NOT authorize generic runtime descendants;
- MUST NOT authorize arbitrary processes merely because they appeared after launch;
- MUST be narrowed to the exact launcher `ProcessInstanceKey` once bound, or otherwise cease to be broad;
- MUST be represented by a distinct authorization reason such as `LAUNCH_HANDOFF_BOOTSTRAP`.

## B. Launch capture detects creation AND same-instance identity transitions

The capture algorithm MUST compare successive snapshots by `ProcessInstanceKey` and detect both:

```text
NEW_INSTANCE
IDENTITY_CHANGED
```

For `IDENTITY_CHANGED`, treat these as identity-bearing fields:

```text
executablePath
parsed runtime identity
Java mainClass when reliable
structured launch arguments
runtimeFamily
executionEnvironment
workingDirectory when it is an app discriminator
cgroupPath when session attribution is relevant
```

A `comm`-only change is not sufficient for a full runtime transition. It may be reported as metadata change/diagnostic evidence.

## C. Candidate observation is not authorization

The implementation MUST maintain the distinction:

```text
candidateInstances = observed during capture
associatedInstances = explicitly attributed to selected application runtime/helper
```

Only `associatedInstances` may participate in launcher authorization.

Creation-after-launch, descendant relationship, same cgroup, or appearance during the capture window are evidence, not authorization by themselves.

## D. cgroup is evidence, never blanket allow

Same-cgroup membership can strengthen launch-session attribution but cannot by itself authorize a process. A child process such as a terminal launched by an allowed browser MUST remain unauthorized unless it also satisfies an explicit compatible runtime/helper attribution rule.

## E. Same-process exec safety for launcher auto-close

Before terminating a launcher instance, re-read a fresh process snapshot. If:

```text
launcherInstance == primaryRuntimeInstance
```

or the launcher instance has become the primary runtime through `exec()`, the implementation MUST NOT terminate it. If launcher identity is ambiguous, skip auto-close and keep tracking the associated runtime.

## F. Java launch-argument parsing must be semantic

Implement a Java launch-argument interpreter that understands, at minimum, the relevant forms:

```text
java -cp <classpath> <main-class> ...
java -classpath <classpath> <main-class> ...
java -jar <jar> ...
java -m <module>/<main-class> ...
```

Only expose a semantic `mainClass` when the launch form establishes it reliably. Do not select the first token that merely resembles a Java class.

## G. Structured argument matching

`ARGUMENT_EQUALS` MUST parse argv structurally and support, where valid:

```text
--key value
--key=value
```

Match argument tokens and parsed values, not arbitrary substrings of a reconstructed command line.

## H. Minecraft attribution has two explicit modes

### H.1 Direct runtime identification

A strong Minecraft-specific runtime signature may attribute the process when it is sufficiently specific without an active FocusFlow launch session.

### H.2 Launch-session attribution

During a FocusFlow-controlled launch, runtime evidence may be somewhat broader when it is additionally tied to:

```text
selected Minecraft LaunchDefinition
+
active LaunchSession
+
new or exec-transitioned candidate
```

Do not collapse both modes into one permissive universal selector.

## I. `--gameDir` alone never identifies Minecraft

A Java process containing only:

```text
--gameDir /path/to/.minecraft
```

MUST NOT be attributed as Minecraft without additional Minecraft-specific runtime evidence or valid launch-session attribution.

## J. Desktop/package correlation has an explicit resolver boundary

A live `LinuxProcessSnapshot` does not inherently contain desktop-entry IDs. Required flow:

```text
LinuxProcessSnapshot
    ↓
ProcessApplicationResolver
    ↓
installed desktop/package catalog
    ↓
DesktopCorrelation / PackageCorrelation
```

The resolver is candidate discovery only. It MUST NOT mutate authorization state or silently persist a guessed application identity. Matcher/attribution logic remains authoritative.

## K. Remove dotted-token package inference

Remove or replace any heuristic equivalent to:

```text
some.dotted.token → packageId
```

Package IDs may come only from recognized package semantics such as Flatpak/Snap invocation or metadata, trusted desktop metadata, or verified sandbox metadata.

## L. RuntimeFamily and ExecutionEnvironment are distinct

Runtime families include:

```text
NATIVE
JAVA
PYTHON
NODE
ELECTRON
SCRIPT
OTHER_RUNTIME
```

Execution environments include:

```text
HOST
FLATPAK
SNAP
OTHER_SANDBOX
UNKNOWN
```

Do not combine these dimensions.

## M. Internal reference ID vs stable external identity

Every persisted application reference MUST have:

```text
referenceId = FocusFlow-owned internal persistent ID, non-null
stableAppId = nullable external/catalog identity
```

Path-only/manual references are valid with `stableAppId = null`.

## N. Selector-expression persistence must be versioned

Recursive selector expressions MUST be governed by the project's existing DB/schema migration/versioning mechanism. If the serialized expression itself can outlive a DB version, include an explicit expression `schemaVersion`. Do not invent an unrelated compatibility system.

## O. Legacy generic runtime migration is ambiguous by default

Legacy selectors such as:

```text
java
python
node
bash
```

MUST NOT silently become broad launcher authorization. Mark them as ambiguous/unresolved and require explicit user confirmation for any intentionally broad runtime selector supported by the product.

## P. Missing instance identity means no destructive PID-only action

If neither:

```text
processStartTicks
pidfd
```

is available for a live process, the process may still be displayed, inspected, logged, and considered for non-destructive discovery, but MUST NOT be destructively targeted solely by PID.

## Q. Helpers require explicit attribution

A recognized application process family may contain `PRIMARY`, `HELPER`, and `LAUNCHER` runtime roles. A helper becomes associated only when an explicit helper selector or compatible application-specific attribution rule matches. Arbitrary descendants are not automatically helpers.

## R. No parallel process-name matcher semantics during migration

Legacy consumers may temporarily use compatibility adapters, but new Linux process-targeting code MUST NOT introduce independent process-name matching. All new code must converge on the canonical process repository + matcher.

## S. Mandatory new tests

The coding agent MUST add tests for:

1. launcher bootstrap authorization race;
2. same-PID `exec()` launcher→runtime handoff;
3. fresh launcher identity verification before auto-close;
4. pre-existing unrelated Java + newly launched Minecraft Java;
5. `--key value` and `--key=value` argument parsing;
6. Java `-cp`, `-jar`, and `-m` main-class extraction;
7. `--gameDir` alone is not Minecraft;
8. resolver guesses do not mutate authorization;
9. selector-expression schema version handling through the project's migration system;
10. protected-process classification inventory/regression behavior;
11. PID reuse with unavailable start ticks cannot create or transfer an authorization association;
12. a pidfd without start ticks is operation-only and cannot enter authorization state;
13. a FocusFlow descendant is not globally protected merely by ancestry/cgroup;
14. raw `LinuxProcessSnapshot` contains no derived runtime/catalog correlation fields;
15. runtime metadata and application correlation are computed through their dedicated layers rather than per-tick raw repository enrichment.

---

## 0. Why v3 exists

The previous specification had the correct architecture but left several semantics open enough that an implementation agent could choose an unsafe interpretation.

The review identified these specific gaps:

1. no explicit serialization/representation for `ALL` vs `ANY` selector logic;
2. a generic `caseSensitive=false` field could incorrectly affect Linux paths;
3. `primaryProcessName` was still too mandatory for path-only references;
4. internal reference identity and external/stable catalog identity were mixed;
5. launch capture only considered newly appearing processes and could miss `exec()` image replacement;
6. launcher auto-close could kill the game when launcher and runtime share one process instance through `exec()`;
7. merely captured processes could accidentally become authorized;
8. cgroup membership could accidentally authorize arbitrary descendants;
9. desktop/package identity was described as if it were intrinsic to a live process;
10. the existing dotted-token package-ID heuristic could survive the alias cleanup;
11. runtime family and packaging/execution environment were mixed;
12. a rigid total capture timeout could fail slow launcher/runtime handoffs;
13. process observer callbacks were PID-only despite PID reuse/race concerns;
14. missing process start time lacked a defined destructive-action policy;
15. legacy generic runtime names could recreate the `java` authorization bug;
16. helper-process attribution needed a dedicated concept rather than all-descendant authorization.

**This v3 resolves those points explicitly and closes the remaining review-agent gaps.**

---

# 1. Non-negotiable architectural rules

The implementation agent MUST preserve these invariants.

### Rule 1 — Application identity is not process-name identity

Never represent an application solely as:

```text
processName = "java"
```

or:

```text
processName = executableBasename
```

A process executable is one piece of runtime evidence, not necessarily the application itself.

### Rule 2 — Persistent application identity is separate from live process identity

Persistent:

```text
ApplicationReference
```

Ephemeral:

```text
ProcessInstance
```

Do not store a PID as the permanent identity of an application.

### Rule 3 — `java`, `python`, `node`, `bash`, etc. are runtimes, not globally safe applications

Do not globally authorize a generic runtime executable.

At minimum, treat these as generic runtimes rather than globally safe application identities:

```text
java
javaw
kotlin
python
python3
node
bash
sh
zsh
ruby
dotnet
mono
```

The exact list may follow the existing project inventory, but the principle is mandatory.

### Rule 4 — Discovery confidence is not authorization

A probabilistic or weighted discovery result can be used to suggest:

> “This running process is probably Minecraft.”

It MUST NOT be used as:

```text
score >= N => launcher authorization
```

Authorization is granted only by an explicit application runtime selector expression, or by a properly attributed launch-session association as defined below.

### Rule 5 — Captured is not authorized

During launch capture:

```text
observed candidate != authorized runtime
```

Only after successful attribution may a process enter `associatedInstances` and become eligible for launcher authorization.

### Rule 6 — cgroup is evidence, not a universal allow rule

Even in a dedicated FocusFlow launch cgroup, cgroup membership alone does not automatically authorize every descendant.

A process must also satisfy a recognized runtime/application relation or explicit helper rule.

### Rule 7 — Detect both process creation and same-instance image replacement

Launch capture MUST detect:

```text
new ProcessInstanceKey
```

and:

```text
same ProcessInstanceKey
but executable/argv/comm identity changed
```

The second case covers Linux `execve()` semantics.

### Rule 8 — Never auto-close a process instance that is now the runtime

If the launcher and runtime share the same `ProcessInstanceKey`, never terminate that process as the “launcher.”

Before any launcher auto-close action, re-read the process identity and verify that the live process is still acting as the launcher and is not the associated runtime.

### Rule 9 — No destructive action without a validated v1 process identity

For v1, any destructive action MUST first have a validated:

```text
ProcessInstanceKey = PID + non-null processStartTicks
```

A live `pidfd` is **not** an alternative identity and MUST NOT make an observation with missing `processStartTicks` eligible for destructive enforcement. In v1, pidfd is operation/revalidation infrastructure only.

Therefore:

```text
validated ProcessInstanceKey (non-null startTicks)
    ↓
may become DENY_AND_SAFE_TO_TERMINATE after policy checks

(pid, null) + pidfd
    ↓
UNKNOWN_PROCESS_INSTANCE
    ↓
no destructive enforcement

(pid, null) without pidfd
    ↓
UNKNOWN_PROCESS_INSTANCE
    ↓
no destructive enforcement
```

This rule applies even when a live pidfd exists for the observed task. pidfd may be used to operate on or revalidate an already-validated process instance, but it never upgrades unknown identity into authorization-bearing identity in v1.

### Rule 10 — Linux path semantics are inherently case-sensitive

Do not apply a generic ignore-case switch to filesystem paths.

### Rule 11 — Desktop/package identity is resolved, not read directly from `/proc`

A live process snapshot may provide evidence that correlates to a desktop/package identity, but `desktopId`/`packageId` are catalog/package concepts and require a resolver.

### Rule 12 — Do not turn arbitrary argv tokens into aliases or package IDs

No heuristic of the form:

```text
some.dotted.token => packageId
```

or:

```text
arbitrary argv token => application alias
```

is allowed in the new implementation.

### Rule 13 — Launch definition and runtime identity are separate

For example:

```text
Minecraft

LaunchDefinition:
    selected Minecraft launcher

RuntimeIdentity:
    Java + Minecraft runtime signature + launch-session relation
```

### Rule 14 — One application may have multiple runtime processes

Model:

```text
Application
    -> primary runtime
    -> recognized helpers
    -> launcher/wrapper
```

Do not solve helpers by globally allowing every descendant.

### Rule 15 — Fail closed for ambiguous Focus Launcher attribution

If a process cannot be safely attributed to an allowed application, it is not authorized merely because it resembles a generic runtime or appeared during the capture window.

For destructive enforcement where the process identity itself is incomplete, follow the explicit “insufficient identity” policy in §21.

---

# 2. Existing codebase facts to preserve and migrate

The supplied project already has pieces of the intended architecture.

## 2.1 `enforcement/InstalledAppsScanner.kt`

`AppDescriptor` already contains or derives values including:

```text
processName
exePath
execCommand
desktopFilePath
desktopId
packageId
processAliases
runningPids
detectionConfidence
```

Retain this catalog work.

The problem is that downstream code currently reduces it to process-name identity. Do not throw away the existing fields; promote them into the new reference/matcher pipeline.

The implementation must explicitly remove/replace the following behaviors where they currently exist:

- generic runtime names treated as globally ignored/safe solely by executable name;
- arbitrary `linuxCommandLineAliases()` token generation as durable application identity;
- dotted-token package-ID inference such as “some.dotted.token = package ID.”

## 2.2 `ProcessNameNormalizer.kt`

Keep this helper for compatibility and simple process-name normalization.

Do not make it the canonical Linux identity abstraction.

It may continue to:

- trim input;
- validate process names;
- normalize legacy names.

It must no longer reject a valid absolute executable path merely because the caller is using the new manual-target flow.

Prefer a separate path parser/selector builder rather than changing legacy normalization semantics in a way that breaks old callers.

## 2.3 `enforcement/LinuxProcessSafety.kt`

Separate its responsibilities into:

```text
FocusFlow-owned safety
critical Linux/system/session safety
application runtime attribution
```

The first two may produce globally protected processes.

The third MUST NOT be represented as a global process-name safe list.

## 2.4 `enforcement/ProcessMonitor.kt`

This is the central enforcement migration point.

Current launcher authorization is effectively:

```text
Set<String>
```

Replace the Linux primary representation with resolved application references + active launch-session associations.

## 2.5 `services/FocusLauncherService.kt`

Current `FocusLauncherApp(processName, displayName, exePath)` is too weak.

Evolve it or bridge it to:

```text
applicationReferenceId
launchDefinition
runtime identity
```

without requiring a process name to exist.

## 2.6 `data/models/AppReference.kt`

There is an existing canonical/stored reference sidecar.

Use it as the basis of the new representation.

Do not create a completely disconnected second identity system unless the existing model makes integration impossible.

## 2.7 `data/Models.kt`

Existing process-name fields are compatibility debt, not an instruction to delete or ignore them.

The migration must preserve existing user data.

## 2.8 `ui/components/LinuxAppPicker.kt`

The current manual process-name field should become one advanced path inside a broader application-target flow.

## 2.9 `ui/launcher/LauncherContent.kt`

Launch behavior currently reconstructs executable/name based processes.

Migrate it to `LaunchDefinition` while process-running checks use `ApplicationReference` + matcher.

## 2.10 `ui/screens/FocusLauncherScreen.kt`

Selected applications must use stable internal references in memory, while legacy process-name records remain readable during migration.

## 2.11 `enforcement/WinEventHook.kt`

Keep foreground/window detection separate from Linux process attribution.

Do not use it as the authoritative source of whether a Linux process belongs to an application.

---

# 3. Canonical data model

The target model MUST distinguish four concepts:

```text
ApplicationReference
LaunchDefinition
RuntimeDefinition / SelectorExpression
ProcessInstance
```

A fifth ephemeral concept connects them during controlled launches:

```text
LaunchSession
```

## 3.1 `ApplicationReference`

Use a non-null FocusFlow-owned internal ID.

Conceptual model:

```kotlin
data class ApplicationReference(
    val referenceId: String,
    val stableAppId: String?,
    val displayName: String,
    val source: AppReferenceSource,
    val resolutionStatus: ResolutionStatus,
    val legacyProcessName: String?,
    val runtimeDefinitions: List<RuntimeDefinition>,
    val launchDefinitionId: String?,
    val conflictStatus: ConflictStatus?,
    val lastResolvedAtMs: Long?
)
```

### ID semantics

`referenceId`:

- mandatory;
- generated/owned by FocusFlow;
- stable across application restarts;
- used for DB foreign keys.

`stableAppId`:

- optional;
- catalog/package/desktop/vendor identity when available;
- not required for manual apps;
- may be null for a path-only/manual reference.

Example:

```text
referenceId = foc-01H...
stableAppId = null
```

is valid.

This explicitly resolves the earlier ID ambiguity.

## 3.2 Process name is optional

Do not make `primaryProcessName` the required identity field.

If compatibility requires the old property, make it nullable/deprecated:

```kotlin
val legacyProcessName: String?
```

A canonical application reference MUST be representable by:

```text
exact executable path
```

alone, or:

```text
desktop/package identity
```

alone, or:

```text
structured runtime signature
```

without inventing a process name as the identity.

## 3.3 `LaunchDefinition`

Separate how an application starts from how its runtime is identified.

Conceptual model:

```kotlin
data class LaunchDefinition(
    val id: String,
    val type: LaunchDefinitionType,
    val executablePath: String?,
    val executable: String?,
    val argv: List<String>,
    val workingDirectory: String?,
    val desktopFilePath: String?,
    val desktopId: String?,
    val packageId: String?,
    val dbusActivatable: Boolean,
    val handoffPolicy: HandoffPolicy
)
```

Do not store only one shell command string.

Use structured argv where possible.

## 3.4 `RuntimeDefinition`

An application may have one or more runtime definitions.

```kotlin
data class RuntimeDefinition(
    val id: String,
    val role: RuntimeRole,
    val selector: SelectorExpression,
    val executionEnvironment: ExecutionEnvironment,
    val runtimeFamily: RuntimeFamily?,
    val authorizationPurpose: RuntimeAuthorizationPurpose
)
```

### Runtime roles

```text
LAUNCHER
PRIMARY
HELPER
```

### Authorization purpose

At minimum:

```text
PRIMARY_RUNTIME
RECOGNIZED_HELPER
LAUNCH_HANDOFF_ONLY
```

This prevents “launcher process” and “game runtime process” from becoming the same concept.

---

# 4. Runtime family vs execution environment

Do NOT mix these concepts.

## 4.1 `RuntimeFamily`

Examples:

```text
NATIVE
JAVA
PYTHON
NODE
ELECTRON
SCRIPT
OTHER_RUNTIME
```

This describes how the application executes.

## 4.2 `ExecutionEnvironment`

Examples:

```text
HOST
FLATPAK
SNAP
OTHER_SANDBOX
UNKNOWN
```

This describes packaging/sandbox/execution context.

A Flatpak Java application therefore can legitimately be:

```text
runtimeFamily = JAVA
executionEnvironment = FLATPAK
```

Do not treat `FLATPAK` as a runtime family.

---

# 5. Linux process snapshot

Create one canonical process repository.

Conceptual model:

```kotlin
data class LinuxProcessSnapshot(
    val pid: Long,
    val processStartTicks: Long?,
    val startInstantMs: Long?,
    val uid: Long?,
    val parentPid: Long?,
    val processGroupId: Long?,
    val sessionId: Long?,
    val comm: String?,
    val executablePath: String?,
    val executableBasename: String?,
    val argv: List<String>,
    val workingDirectory: String?,
    val cgroupPath: String?,
    val ioState: ProcessIoState,
    val fieldAvailability: Set<ProcessField>
)
```

`LinuxProcessSnapshot` MUST NOT contain `desktopCorrelation`, `packageCorrelation`, `runtimeFamily`, `mainClass`, parsed application arguments, or other derived application semantics. Those belong to the layers below.

For observations with missing `processStartTicks`, the snapshot remains valid as raw observation data; it simply cannot be promoted to an authorization-bearing `ProcessInstanceKey` until start ticks are obtained.

Derived layers:

```kotlin
ProcessRuntimeMetadata
    - runtimeFamily
    - executionEnvironment
    - mainClass when reliably parsed
    - Java launch artifact
    - structured application arguments
    - selected runtime markers

ProcessApplicationCorrelation
    - desktop/package correlation
    - installed application candidates
    - catalog references
```

`commandLine` may be generated for diagnostics, but argv is canonical for matching.

## 5.1 Why use `processStartTicks`

Where procfs is available, the kernel's process start-time value is a better process-instance key than a PID alone.

Use it as the Linux instance identity component when available.

The implementation may additionally expose an `Instant` conversion for diagnostics, but the instance key should retain a stable kernel-derived value.

## 5.2 Data sources

Preferred sources:

```text
/proc/<pid>/exe
/proc/<pid>/cmdline
/proc/<pid>/comm
/proc/<pid>/stat
/proc/<pid>/cwd
/proc/<pid>/cgroup
```

Use Java `ProcessHandle` where it provides convenient metadata, but do not allow it to replace needed procfs fields when procfs is more authoritative/complete.

## 5.3 `comm` semantics

Treat `comm` as:

```text
useful process-name evidence
```

not definitive application identity.

It can be short/mutable and therefore must not be the sole identity for a complex runtime such as Minecraft.

## 5.4 argv semantics

Parse `/proc/<pid>/cmdline` as NUL-separated arguments.

Do not reconstruct a shell string and then attempt shell parsing.

Treat missing/modified cmdline as partial evidence.

## 5.5 Partial snapshots

A process MUST NOT be dropped from the repository merely because one field is unreadable.

Distinguish:

```text
PROCESS_EXITED_DURING_READ
PERMISSION_DENIED
FIELD_UNAVAILABLE
MALFORMED
AVAILABLE
```

---

# 6. `ProcessInstanceKey`

Use a non-null identity key for authorization-bearing state:

```kotlin
data class ProcessInstanceKey(
    val pid: Long,
    val processStartTicks: Long
)
```

This is the only process identity allowed in:

```text
associatedInstances
primaryRuntimeInstance
launcherInstance
authorization-bearing runtime/session membership
```

A separate live operation context may retain an optional pidfd:

```text
ValidatedProcessHandle
    processInstanceKey: ProcessInstanceKey
    pidfdHandle: Int?
```

The pidfd is an operation handle, not domain identity. It MUST NOT alter equality/hash semantics of `ProcessInstanceKey`, and it MUST NOT be used to create a second identity variant such as `(pid, null, pidfd)`.

## 6.1 Identity acquisition

A process may be promoted to `ProcessInstanceKey` only after a fresh observation establishes:

```text
pid
+
processStartTicks
```

If the start ticks are missing:

```text
raw observation = valid
process instance identity = UNKNOWN
authorization identity = unavailable
```

A live pidfd does not change that v1 authorization rule. It may be used to prevent PID-reuse races during an already-validated operation once a `ProcessInstanceKey` exists.

## 6.2 PID reuse safety

Before any destructive operation:

1. re-read a fresh snapshot;
2. establish the current non-null `ProcessInstanceKey`;
3. compare it with the authorized/target instance;
4. only then perform the operation;
5. optionally use an already-open pidfd for the final operation when available.

If the current process has no start ticks, the destructive operation MUST NOT proceed.

## 6.3 Unknown observations

Unknown observations may be tracked in temporary capture/discovery structures keyed by PID plus observation timestamp/sequence as needed, but those structures MUST never be reused as authorization identity. An old observation cannot authorize a later PID reuse.

# 7. Selector expression model — explicit boolean semantics

This section resolves the most important gap from the previous specification.

The selector model MUST explicitly represent boolean composition.

## 7.1 Expression type

Use a recursive expression model equivalent to:

```kotlin
sealed interface SelectorExpression {
    data class All(val children: List<SelectorExpression>) : SelectorExpression
    data class Any(val children: List<SelectorExpression>) : SelectorExpression
    data class Predicate(val value: RuntimeSelector) : SelectorExpression
}
```

Equivalent project-style types are acceptable, but the semantics must be identical.

## 7.2 Empty expressions

Define these semantics explicitly:

```text
ALL([]) = true
ANY([]) = false
```

However, reject empty expressions during validation for persisted application runtime definitions unless the expression is intentionally used as a disabled rule.

Do not silently interpret a missing expression as “match everything.”

## 7.3 Short-circuiting

`ALL` may stop at the first false child.

`ANY` may stop at the first true child.

All matcher results should still be able to provide evidence suitable for diagnostics when requested.

## 7.4 Runtime selector types

Minimum required types:

```text
PROCESS_NAME
EXECUTABLE_BASENAME
EXECUTABLE_PATH
DESKTOP_ID
PACKAGE_ID
ARGUMENT_EXISTS
ARGUMENT_EQUALS
MAIN_CLASS
WORKING_DIRECTORY
RUNTIME_FAMILY
EXECUTION_ENVIRONMENT
```

The following may be represented as context predicates rather than persistent selectors:

```text
LAUNCH_SESSION_ASSOCIATION
LAUNCHER_ANCESTRY
DEDICATED_CGROUP_ASSOCIATION
```

Do not persist a live PID as an application selector.

---

# 8. Selector case semantics

Do NOT use a generic selector property such as:

```kotlin
caseSensitive = false
```

as the global default.

Case semantics belong to the selector type.

## 8.1 Required defaults

| Selector | Matching semantics |
|---|---|
| `PROCESS_NAME` | case-insensitive by default |
| `EXECUTABLE_BASENAME` | case-insensitive by default |
| `EXECUTABLE_PATH` | case-sensitive |
| `WORKING_DIRECTORY` | case-sensitive |
| `DESKTOP_ID` | exact/native semantics |
| `PACKAGE_ID` | exact/native semantics |
| `MAIN_CLASS` | exact |
| `ARGUMENT_EXISTS` | exact argument-token semantics |
| `ARGUMENT_EQUALS` | configurable by value type; paths exact |
| `RUNTIME_FAMILY` | enum/exact |
| `EXECUTION_ENVIRONMENT` | enum/exact |

## 8.2 User overrides

Optional case-insensitive override may be exposed only for selectors where it is meaningful.

The implementation MUST prevent a UI-level “ignore case” switch from changing filesystem path matching to case-insensitive behavior.

---

# 9. Selector specificity and authorization

A matcher may return descriptive evidence such as:

```text
EXACT_PATH
PROCESS_NAME
COMMAND_ARGUMENT
MAIN_CLASS
PACKAGE_ID
DESKTOP_ID
LAUNCH_SESSION
CGROUP_EVIDENCE
ANCESTRY_EVIDENCE
```

But evidence strength is not the same as authorization.

## 9.1 Strong selectors

Typically:

```text
exact executable path
exact desktop ID
exact package ID
main class + app-specific argument
exact argument key/value for an app-specific path
explicit application runtime family + required signature
properly attributed launch-session association
```

## 9.2 Weak selectors

Examples:

```text
process name only
executable basename only
runtime family only
single generic argument token
```

Weak selectors may be legitimate user-created blocking rules, but Focus Launcher must treat broad generic runtime selectors as ambiguous unless the user explicitly confirms broad matching.

---

# 10. Application attribution

Create a central attribution operation:

```kotlin
fun attribute(
    process: LinuxProcessSnapshot,
    application: ApplicationReference,
    context: AttributionContext
): AttributionResult
```

The result should distinguish at least:

```text
MATCHED_PRIMARY
MATCHED_HELPER
MATCHED_LAUNCHER
NOT_MATCHED
AMBIGUOUS
INSUFFICIENT_DATA
```

## 10.1 Do not return only Boolean

The blocker needs to know why a process matched.

Conceptual result:

```kotlin
data class AttributionResult(
    val status: AttributionStatus,
    val applicationReferenceId: String?,
    val runtimeDefinitionId: String?,
    val evidence: List<MatchEvidence>
)
```

## 10.2 One process, multiple candidate applications

If multiple references match:

- prefer an exact/strong unique reference where policy permits;
- otherwise report `AMBIGUOUS`;
- do not arbitrarily choose based on list ordering;
- Focus Launcher MUST NOT allow an ambiguous generic match.

---

# 11. Launch-session model

`LaunchSession` is the generic solution to launcher → runtime handoff.

Do not create a Minecraft-only capture subsystem.

Conceptual model:

```kotlin
data class LaunchSession(
    val sessionId: String,
    val applicationReferenceId: String,
    val createdAtMs: Long,
    val baseline: Map<ProcessInstanceKey, ProcessFingerprint>,
    val launcherInstance: ProcessInstanceKey?,
    val launcherLastKnownFingerprint: ProcessFingerprint?,
    val candidateInstances: Set<ProcessInstanceKey>,
    val associatedInstances: Set<ProcessInstanceKey>,
    val primaryRuntimeInstance: ProcessInstanceKey?,
    val dedicatedCgroupPath: String?,
    val launcherExitedAtMs: Long?,
    val state: LaunchSessionState,
    val backend: LaunchCaptureBackend
)
```

All identity-bearing fields in `LaunchSession` use only strong `ProcessInstanceKey` values. Unknown/raw observations with unavailable start ticks are tracked separately as non-authoritative capture observations and MUST NOT be inserted into these sets/fields.

`LaunchSession` may retain a runtime-only `pidfd` operation handle outside the persisted/domain model if available.


## 11.1 Candidate vs associated

This distinction is mandatory:

```text
candidateInstances
    = processes observed as potentially related

associatedInstances
    = candidates that passed application/runtime attribution
```

Only `associatedInstances` may participate in authorization.

## 11.2 Suggested states

```text
ARMING
LAUNCHER_RUNNING
WAITING_FOR_RUNTIME
RUNTIME_ASSOCIATED
LAUNCHER_EXITED
POST_EXIT_GRACE
HANDOFF_COMPLETE
FAILED
ENDED
```

Equivalent naming is fine.

---

# 12. Launch capture lifecycle

Capture MUST start before spawning the launcher.

Required order:

```text
create LaunchSession
        ↓
create baseline snapshot
        ↓
start capture backend
        ↓
spawn launch definition
        ↓
resolve launcher ProcessInstanceKey
        ↓
begin runtime observation
```

Do not use:

```text
spawn launcher
wait for launcher exit
then search for runtime
```

as the primary method.

---

# 13. Launch capture MUST detect `exec()`

This is a hard acceptance requirement.

Linux `execve()` replaces the process image rather than creating a new process. Therefore the PID and process-instance identity can remain the same while:

```text
exe
comm
argv
```

change.

The implementation must compare successive snapshots by `ProcessInstanceKey` and detect meaningful identity changes.

Example:

```text
Snapshot A
PID 5000
start = X
exe = minecraft-launcher
argv = launcher

Snapshot B
PID 5000
start = X
exe = java
argv = java ... Minecraft ...
```

This MUST generate a process-identity-change event/candidate update even though no new `ProcessInstanceKey` appeared.

Reference: Linux/POSIX `exec` semantics: https://www.man7.org/linux/man-pages/man3/execve.3p.html

---

# 14. Process fingerprint for change detection

Create an immutable comparison fingerprint containing enough data to detect an `exec()` or major identity change:

```kotlin
data class ProcessFingerprint(
    val executablePath: String?,
    val executableBasename: String?,
    val comm: String?,
    val argv: List<String>,
    val workingDirectory: String?,
    val cgroupPath: String?
)
```

Do not persist secrets from argv in this fingerprint.

For in-memory launch capture, retain only the minimum needed diagnostic detail.

A fingerprint change does not itself authorize the process; it only causes the matcher to reevaluate it.

---

# 15. Launch polling backend

A polling backend is acceptable for v1 of the capture mechanism.

## 15.1 Snapshot loop

At each poll:

1. obtain one process snapshot generation;
2. index current processes by `ProcessInstanceKey`;
3. detect new process instances not in baseline/previous snapshot;
4. detect existing process instances whose fingerprints changed;
5. evaluate new/changed candidates against the selected application's runtime definitions;
6. update `candidateInstances`;
7. only on successful attribution update `associatedInstances`;
8. if a `PRIMARY` runtime is associated, set `primaryRuntimeInstance`;
9. continue monitoring until lifecycle termination conditions are reached.

## 15.2 Poll interval

Use a configurable interval.

Recommended initial behavior:

```text
active launch transition: 200–300 ms
long waiting state:        back off toward 500–1000 ms
```

Do not hard-code polling literals across several services.

## 15.3 Time limits

Do not define “30 seconds total” as the required lifecycle.

Use:

```text
capture while launcher remains alive
        +
post-launcher grace
        +
absolute safety cap
```

Recommended starting defaults:

```text
post-launcher grace: 15 s
absolute capture cap: 5 min
```

These are configuration constants, not semantic requirements. Tests may tune them, but the lifecycle rules must remain.

The absolute cap exists to prevent leaks; the launcher-alive phase is the normal capture period.

---

# 16. Event-driven process observer interface

Design the API so the backend can later become event-driven.

Prefer an event payload containing immutable process identity, not only PID:

```kotlin
data class ProcessObservation(
    val instance: ProcessInstanceKey,
    val snapshot: LinuxProcessSnapshot?,
    val kind: ProcessObservationKind
)
```

Kinds:

```text
CREATED
IDENTITY_CHANGED
EXITED
```

Interface concept:

```kotlin
interface LinuxProcessCreationObserver {
    fun start(
        listener: (ProcessObservation) -> Unit
    ): Closeable
}
```

The initial implementation may report `unsupported` and fall back to polling.

Do not require root-only process-event infrastructure in v1.

---

# 17. PID reuse and revalidation

Before destructive action:

```text
process snapshot says PID X is unauthorized
        ↓
re-read live process identity
        ↓
verify same ProcessInstanceKey
        ↓
verify matcher still says unauthorized
        ↓
terminate exact instance
```

If any step fails:

```text
do not kill
```

If a live pidfd is available, it may replace the PID-reuse-sensitive part of the termination operation **after** a valid v1 `ProcessInstanceKey` has already been established and the process has independently reached `DENY_AND_SAFE_TO_TERMINATE`. The pidfd does not establish the identity, create authorization state, or make `(pid, null)` destructively actionable.

Linux provides pidfds as stable references to specific processes; signaling through a pidfd avoids traditional PID-reuse races. See: https://www.man7.org/linux/man-pages/man2/pidfd_open.2.html and https://www.man7.org/linux/man-pages/man2/pidfd_send_signal.2.html

---

# 18. Optional pidfd hardening

Treat pidfd as Phase 2/advanced Linux hardening, not a prerequisite for v1.

Possible responsibilities:

```text
open pidfd for a validated live process instance
store it only in live in-memory session/process state
poll for process exit
send termination signal to the exact referenced process
```

The phrase **validated live process instance** is normative here: v1 requires an already-established `PID + non-null processStartTicks` identity before pidfd-backed destructive operations are permitted. pidfd availability does not relax that prerequisite.

Do not persist raw file-descriptor integers.

If pidfd is unavailable:

```text
use PID + validated processStartTicks
```

If both are unavailable:

```text
no destructive PID action
```

References: `pidfd_open(2)`: https://www.man7.org/linux/man-pages/man2/pidfd_open.2.html; `pidfd_send_signal(2)`: https://www.man7.org/linux/man-pages/man2/pidfd_send_signal.2.html

---

# 19. Cgroup/systemd launch tracking

Cgroup support is an attribution enhancement, not a universal authorization mechanism.

Linux cgroups organize processes hierarchically; newly forked processes start in the forking process's cgroup, but processes can also be migrated. See: https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html

## 19.1 When FocusFlow launches an application

If the environment supports it, a dedicated user-level systemd scope/cgroup may be used:

```text
FocusFlow launch session
        ↓
dedicated scope/cgroup
        ├── launcher
        ├── runtime
        └── recognized helpers
```

## 19.2 Cgroup policy

A process in the launch cgroup receives:

```text
CGROUP_EVIDENCE
```

not automatic authorization.

Authorization requires:

```text
runtime/application attribution
+
(optional) cgroup/session association
```

## 19.3 Browser/terminal escape prevention

This MUST fail:

```text
Allowed application = Browser
Browser launches Terminal
Terminal inherits application cgroup
```

Expected:

```text
Terminal is not automatically authorized
```

A helper must satisfy an explicit recognized-helper rule or a stronger application runtime attribution rule.

## 19.4 Migration/session boundaries

Do not assume a process remains in one cgroup forever.

Treat current cgroup membership as evidence observed at attribution/enforcement time.

---

# 20. Helper-process attribution

This is a distinct concept from primary runtime attribution.

An allowed application may have:

```text
main
renderer
GPU
utility
sandbox
crash handler
```

The system must not:

```text
allow every descendant
```

and must not:

```text
kill every process whose executable differs from the primary process
```

## 20.1 RuntimeDefinition helper rules

A `HELPER` runtime definition should normally require:

```text
explicit helper executable/runtime selector
+
application/session relationship evidence
```

Examples:

```text
Chrome helper executable
AND
same launch session
```

or a stronger application-specific runtime expression.

## 20.2 Unknown child process

If:

```text
allowed app launches unknown child process
```

and no helper selector matches:

```text
do not automatically authorize it
```

For enforcement, apply the existing product policy for unrelated processes.

---

# 21. Explicit insufficient-identity policy

When a process is a candidate for destructive enforcement but its identity cannot safely be established:

### Case A — unauthorized status can be proved without PID-only assumptions

Example:

```text
snapshot clearly shows process instance
start time known
matcher says unauthorized
```

Destructive action may proceed after revalidation.

### Case B — process PID exists but start time is unavailable

For v1, this remains an unknown process instance **even when a live pidfd exists**:

```text
(pid, null)
    ↓
UNKNOWN_PROCESS_INSTANCE
    ↓
no destructive enforcement
```

A pidfd may be retained for non-destructive liveness/revalidation/diagnostic operations, but it does not create a valid authorization identity.

```text
log degraded enforcement
continue observing
```

### Case C — matcher requires a missing field

Example:

```text
Minecraft selector requires --gameDir
cmdline unavailable
```

Result:

```text
INSUFFICIENT_DATA
```

Do not convert missing evidence into “matched.”

For Focus Launcher, this is not an authorization path.

---

# 22. Application reference selectors — concrete types

## 22.1 `PROCESS_NAME`

Matches `comm` or canonical configured process-name field.

Default:

```text
case-insensitive
exact value
```

Not enough by itself to identify a generic runtime application safely.

## 22.2 `EXECUTABLE_BASENAME`

Matches basename of `/proc/<pid>/exe`.

Example:

```text
firefox
```

Default case-insensitive.

## 22.3 `EXECUTABLE_PATH`

Matches complete executable path.

Example:

```text
/usr/bin/firefox
```

Must be case-sensitive.

Do not resolve a user-provided path to lowercase.

## 22.4 `DESKTOP_ID`

Exact match against the resolved desktop/catalog identity.

It is NOT expected to be read directly from `/proc`.

## 22.5 `PACKAGE_ID`

Exact package identity where verified.

Sources may include:

```text
flatpak invocation semantics
snap invocation semantics
trusted desktop metadata
verified sandbox/package metadata
```

Do not infer it from arbitrary dotted argv text.

## 22.6 `ARGUMENT_EXISTS`

Matches a complete argv token.

Example:

```text
--gameDir
```

## 22.7 `ARGUMENT_EQUALS`

Parse argv structurally. Support both `--key value` and `--key=value` where the option grammar permits both forms. Compare parsed token/value equality rather than raw command-line substring containment.


Matches a typed argument value.

Example:

```text
argument = --gameDir
value = /home/user/.minecraft/instances/Survival/minecraft
```

Path values are case-sensitive.

## 22.8 `MAIN_CLASS`

The matcher depends on a semantic Java launch-argument interpreter, not a class-looking-token heuristic. Recognize the relevant Java launcher forms (`-cp`/`-classpath`, `-jar`, `-m`) and only expose a main class when the syntax identifies it reliably.


Matches a recognized Java/application entry-point token.

For Minecraft, `net.minecraft.client.main.Main` is one known vanilla-style signal, but the matcher MUST support other recognized launcher/mod-loader entry points.

Do not require this one class for every Minecraft installation.

## 22.9 `WORKING_DIRECTORY`

Exact path semantics.

Case-sensitive.

## 22.10 `RUNTIME_FAMILY`

Enum match.

Example:

```text
JAVA
```

Never authorize a Focus Launcher application solely because `RUNTIME_FAMILY=JAVA`.

## 22.11 `EXECUTION_ENVIRONMENT`

Enum match.

Example:

```text
FLATPAK
```

This can be combined with `PACKAGE_ID` or runtime selectors.

---

# 23. Desktop and package correlation

A live process snapshot does not inherently contain a desktop-entry ID.

Implement an explicit correlation layer.

Conceptually:

```text
LinuxProcessSnapshot
        ↓
ProcessApplicationResolver
        ↓
Desktop/package catalog
        ↓
DesktopCorrelation / PackageCorrelation
```

## 23.1 Desktop correlation inputs

Potential evidence:

```text
exact executable path
executable basename
trusted desktop Exec data
package metadata
known application IDs
window metadata when available
```

The resolver must preserve uncertainty.

Example result:

```text
EXACT
POSSIBLE
NONE
AMBIGUOUS
```

## 23.2 D-Bus activation

Desktop entries may have:

```text
DBusActivatable=true
```

When true, desktop-entry implementations should use D-Bus activation rather than simply treating `Exec` as the activation mechanism. `Exec` should still be present for compatibility. See: https://specifications.freedesktop.org/desktop-entry/latest-single/

Therefore:

- do not claim that the desktop `Exec` command is always the actual launch path;
- preserve `dbusActivatable` in `LaunchDefinition`;
- if FocusFlow does not implement D-Bus launching, keep its existing compatible fallback behavior and mark activation capability accurately;
- do not fabricate a D-Bus client implementation as part of the process identity migration.

## 23.3 Package correlation

Replace any dotted-token heuristic with explicit package-aware parsing.

Allowed sources include:

```text
flatpak run <app-id>
snap run <app-id>
known desktop/package metadata
trusted runtime/sandbox metadata
```

Reject:

```text
arbitrary.foo.bar
```

as package identity merely because it contains dots.

---

# 24. Manual Linux application UX

Rename the conceptual action from:

```text
Manual process
```

to:

```text
Add application / process target
```

## 24.1 Primary options

Present these prominently:

```text
Select installed application
Choose a running application
Launch & detect
```

Advanced:

```text
Process name
Executable basename
Executable path
Command-line rule
Desktop ID
Package ID
```

## 24.2 Choose a running application

This should be the easiest way to capture an obscure application.

Display a curated application/process candidate list rather than a raw `ps` dump.

Example:

```text
Minecraft
    Java runtime
    PID 18432
    Detected from Minecraft runtime signature

Firefox
    /usr/lib/firefox/firefox
    PID 19102
```

Technical details may expand to:

```text
PID
start time
comm
exe
argv
PPID
PGID
session
cgroup
package/desktop correlation
```

Redact sensitive arguments.

## 24.3 Process name input

Accept:

```text
foo
Firefox
minecraft
```

Default matching:

```text
case-insensitive
exact process-name field
```

If multiple live processes match:

```text
show ambiguity
```

Do not silently choose the first result.

## 24.4 Executable path input

Accept absolute paths such as:

```text
/usr/bin/firefox
/opt/game/bin/game
/home/user/Games/foo/run
```

Path matching remains case-sensitive.

A path-only reference is valid and must not be forced into a process-name-centric representation.

## 24.5 Command-line input

Normal UI should use structured fields:

```text
Executable: [java]
Main class: [optional]
Argument exists: [--gameDir]
Argument value: [....]
```

Do not require users to write regular expressions for normal use.

Advanced matching may support a deliberately documented pattern language, but it must operate on tokenized argv and remain explicit.

## 24.6 Desktop/package input

Allow selection from known catalog values rather than asking users to discover `/usr/share/applications/...` paths unless they are in advanced mode.

## 24.7 Ambiguity UI

Example:

```text
3 running processes match “foo”

Foo
foo
foo-helper

Choose exactly what you want to target.
```

Provide a technical details view so the user can see why candidates differ.

---

# 25. Running-process capture: derive a selector, do not just save PID

When the user chooses:

```text
Choose a running application
```

FocusFlow should inspect the process and build a persistent application reference from stable evidence.

The saved object should look conceptually like:

```text
ApplicationReference
    referenceId = internal stable ID
    stableAppId = optional
    displayName = user-facing name
    runtimeDefinitions = generated selectors
```

The live process instance:

```text
PID + start time
```

is retained only for the current runtime/session context.

## 25.1 Preferred generated selectors

Prefer, in context:

```text
exact package/desktop identity
exact executable path
runtime + app-specific command signature
main class + required path/argument
```

Use bare process-name selectors only when stronger identity is unavailable or the user explicitly chose that mode.

## 25.2 Explain what will be matched

Before saving, show a summary such as:

```text
This target will match:

✓ Minecraft Java runtime
✓ selected game directory

It will NOT match:

✗ IntelliJ Java
✗ arbitrary Java applications
```

This is particularly important for generic runtimes.

---

# 26. Launch & Detect user flow

UI flow:

```text
Add application
    ↓
Launch & detect
    ↓
FocusFlow arms a LaunchSession
    ↓
user/FocusFlow starts the launcher
    ↓
FocusFlow observes process creation + identity changes
    ↓
candidate applications appear
    ↓
user may select the detected primary runtime if automatic attribution is not decisive
    ↓
FocusFlow derives/persists application runtime selectors
```

The user should not need to know the launcher PID or Java executable path.

---

# 27. Minecraft runtime detection

Minecraft is the primary acceptance scenario, but the implementation must remain generic.

## 27.1 Minecraft application model

Example:

```text
ApplicationReference
    displayName = Minecraft

LaunchDefinition
    selected Minecraft launcher

RuntimeDefinition #1
    role = PRIMARY
    runtimeFamily = JAVA
    selector = strong Minecraft expression

RuntimeDefinition #2...
    role = HELPER
    selector = recognized Minecraft helper expression(s)
```

## 27.2 Preferred Minecraft primary selector

The selected Minecraft runtime should be recognized using an explicit `ALL`/`ANY` expression.

Example conceptual expression:

```text
ALL(
    EXECUTABLE_BASENAME == java,
    ANY(
        MAIN_CLASS == net.minecraft.client.main.Main,
        recognized loader-specific Minecraft entry point,
        other trusted Minecraft runtime signature
    ),
    ANY(
        ARGUMENT_EQUALS(--gameDir, selectedGameDir),
        trusted Minecraft installation/path evidence
    )
)
```

Exact predicates may vary by launcher/loader. Implement the two attribution modes defined by the v3 amendments. Preserve the semantic structure:

```text
Java runtime
AND
Minecraft-specific evidence
AND/OR
launch-session / instance / location discriminator as required by the attribution mode
```

`--gameDir` alone is never sufficient.

Do not reduce the selector to:

```text
EXECUTABLE_BASENAME == java
```

## 27.3 Do not require one exact main class

Support vanilla and known mod-loader/runtime variants.

A Minecraft detector may use:

```text
main-class evidence
Minecraft argument evidence
game directory
game/library path evidence
launcher properties
launch-session relationship
```

Use the minimum sufficient combination for safe attribution.

---

# 28. Minecraft launcher handoff

Required lifecycle:

```text
FocusFlow
   ↓
LaunchSession created
   ↓
baseline captured
   ↓
Minecraft launcher started
   ↓
launcher instance recorded
   ↓
new/changed Java process observed
   ↓
Minecraft matcher evaluates candidate
   ↓
Minecraft runtime associated
   ↓
launcher may exit / be closed according to handoff policy
   ↓
Minecraft remains associated
```

## 28.1 Launcher exit is not proof of runtime detection

Do not assume:

```text
launcher exited => Minecraft exists
```

The runtime must actually be attributed.

## 28.2 Runtime may be reparented

After launcher exit, ancestry can change.

Therefore ancestry is:

```text
launch-time evidence
```

not a permanent required relationship.

---

# 29. Same-process `exec()` handoff and launcher closing

This is a hard safety rule.

Scenario:

```text
PID 5000
launcher
      ↓ exec()
PID 5000
Minecraft runtime
```

Then:

```text
launcherInstance == primaryRuntimeInstance
```

The implementation MUST NOT terminate PID 5000 as the launcher.

## 29.1 Before launcher auto-close

Re-read a fresh snapshot for the launcher instance.

Verify all of:

```text
same process instance
still represents launcher role
not equal to primaryRuntimeInstance
not associated as PRIMARY runtime
not transformed via exec into runtime
```

Only then may the launcher-close operation proceed.

## 29.2 If launcher identity is ambiguous

Do not close it.

Continue tracking the application runtime.

Log a diagnostic such as:

```text
Launcher auto-close skipped: launcher/runtime process instance identity changed or became ambiguous.
```

---

# 30. Focus Launcher authorization semantics

Replace:

```text
allowedProcessNames: Set<String>
```

with something equivalent to:

```text
allowedApplicationReferences
activeLaunchSessions
```

## 30.1 Per-process authorization algorithm

For each live Linux process, produce a three-state decision:

```text
1. Is this FocusFlow itself / explicitly protected system infrastructure?
       YES -> ALLOW (protected)
       NO  -> continue

2. Is this an exact launcher instance covered by active
   LAUNCH_HANDOFF_BOOTSTRAP authorization?
       YES -> ALLOW (handoff only)
       NO  -> continue

3. Does an active launch-session/application runtime definition explicitly
   attribute this process?
       YES -> ALLOW (associated runtime/helper)
       NO  -> continue

4. Does a selected application reference match it directly?
       YES -> ALLOW
       NO  -> continue

5. Can the process be safely identified/revalidated as an unauthorized
   process eligible for destructive enforcement?
       YES -> DENY_AND_SAFE_TO_TERMINATE
       NO  -> UNKNOWN / INSUFFICIENT_DATA / UNSAFE_TO_TERMINATE
```

`NOT AUTHORIZED` is not itself a kill command. Destructive action is permitted only for `DENY_AND_SAFE_TO_TERMINATE` after instance revalidation.

## 30.2 Generic runtime example

Given:

```text
Allowed application = Minecraft
```

and:

```text
Minecraft JVM = java
IDE JVM       = java
Gradle JVM    = java
```

Only the Java process satisfying the Minecraft runtime definition may be allowed.

`java` must never mean:

```text
allow all Java
```

---

# 31. Launch-session association rules

Association may happen through a combination of:

```text
runtime selector match
launch-time ancestry
launch-time creation/change
known launcher role
selected working directory/game directory
cgroup evidence
```

But the final association MUST satisfy an application/runtime rule.

## 31.1 Required invariant

```text
observed candidate
    !=
authorized associated runtime
```

The implementation must have separate collections/fields for both.

## 31.2 Cgroup-only association is forbidden

This is invalid:

```text
same cgroup => authorized
```

Valid pattern:

```text
same dedicated cgroup
+
recognized runtime/helper selector
+
launch-session relationship
=> associated
```

---

# 32. Legacy migration safety

Migration MUST NOT recreate the very runtime bug this project is fixing.

## 32.1 Legacy generic runtime names

When reading old Focus Launcher records containing only:

```text
java
python
node
bash
sh
ruby
...
```

mark them as:

```text
AMBIGUOUS_LEGACY_RUNTIME
```

for launcher authorization.

Do NOT silently reinterpret them as precise application references.

## 32.2 User-confirmed broad selector

If the UI exposes a broad runtime selector as an explicit advanced/manual choice, the user may deliberately confirm it.

That is different from silent migration.

The UI should warn:

```text
This rule matches multiple applications that use Java.
```

before allowing it as a Focus Launcher allow target.

## 32.3 Legacy app migration

Migration sequence:

```text
legacy row
    ↓
try catalog/running-process resolution
    ↓
if unique strong match:
    create rich reference
    migrate

if generic/ambiguous:
    preserve legacy value
    mark AMBIGUOUS
    do not use it as broad launcher authorization
```

---

# 33. Existing `app_references` persistence model

Separate:

```text
referenceId        -- internal FocusFlow ID, required
stableAppId        -- external/catalog identity, nullable
```

Do not use `stableAppId` as the database foreign key if it is nullable.

## 33.1 Runtime selector persistence

The serialized selector representation MUST be versioned by the project's migration/versioning system. If the expression is independently persisted or transported, include an explicit `schemaVersion`.


Choose the least invasive schema compatible with the project's existing DB/serialization conventions.

The serialized representation MUST preserve the recursive expression tree.

Example JSON-like shape:

```json
{
  "type": "ALL",
  "children": [
    {
      "type": "PREDICATE",
      "selector": {
        "type": "EXECUTABLE_BASENAME",
        "value": "java"
      }
    },
    {
      "type": "ANY",
      "children": [
        {
          "type": "PREDICATE",
          "selector": {
            "type": "MAIN_CLASS",
            "value": "net.minecraft.client.main.Main"
          }
        },
        {
          "type": "PREDICATE",
          "selector": {
            "type": "ARGUMENT_EQUALS",
            "key": "--gameDir",
            "value": "/home/user/.minecraft/instances/Survival/minecraft"
          }
        }
      ]
    }
  ]
}
```

The exact serialization format may use the project's existing serializer, but `type`, `children`, and predicate data must remain unambiguous.

Do not use comma-separated strings as the canonical representation.

## 33.2 Selector-table alternative

A normalized recursive selector/group table is acceptable if it fits the project's ORM better.

The implementation must still represent:

```text
ALL
ANY
PREDICATE
```

explicitly.

---

# 34. Launch definition persistence

Preserve launch metadata independently from runtime selectors.

Conceptual table:

```sql
CREATE TABLE IF NOT EXISTS app_launch_definitions (
    id TEXT PRIMARY KEY,
    reference_id TEXT NOT NULL,
    type TEXT NOT NULL,
    executable_path TEXT,
    executable TEXT,
    argv_json TEXT,
    working_directory TEXT,
    desktop_file_path TEXT,
    desktop_id TEXT,
    package_id TEXT,
    dbus_activatable INTEGER NOT NULL DEFAULT 0,
    handoff_policy TEXT NOT NULL DEFAULT 'NONE'
);
```

Exact schema can follow project conventions.

Critical requirements:

- `argv` remains structured;
- path remains distinct from executable name;
- desktop metadata remains distinct from runtime identity;
- no shell command string is the sole source of truth.

---

# 35. Data-model migration across process-targeting features

The application contains process-name-only fields across multiple features.

Migrate progressively.

## P0 — Focus Launcher

Mandatory.

Replace name allowlisting with:

```text
ApplicationReference
RuntimeDefinition
LaunchSession
```

## P1 — Standard App Blocker

Use the common matcher where Linux process attribution occurs.

A legacy process-name rule may remain a weak selector if the user explicitly created it.

## P1 — Block schedules

Resolve process names to application references where uniquely possible.

## P1 — Standalone blocks

Same.

## P1 — Daily allowance

Prefer application reference IDs for usage accounting when resolved.

Do not merge unrelated Java applications into one `java` usage bucket merely because they share an executable.

## P1 — Focus session extra blocked apps

Resolve through the common selector/matcher pipeline.

## P1 — Network process targeting

Use the common Linux process repository/matcher where process selection is required.

## P1 — Logging

Where practical include:

```text
application reference ID
runtime definition ID
PID
process start identity
attribution status
```

alongside human-readable process names.

---

# 36. `ProcessMonitor` requirements

## 36.1 One snapshot generation per sweep

Prefer:

```text
snapshotAll()
    ↓
all matcher decisions for this tick
```

rather than repeated procfs/ProcessHandle reads from multiple callers.

## 36.2 Launcher sweep

For each process:

```text
protected system process?
    -> skip

FocusFlow-owned?
    -> skip

associated allowed runtime?
    -> allow

matched selected application?
    -> allow

ambiguous/unknown generic runtime?
    -> unauthorized / not authorized

otherwise
    -> existing launcher policy
```

## 36.3 Revalidation before destructive action

Never do:

```text
snapshot
kill(pid)
```

Do:

```text
snapshot
    ↓
match
    ↓
re-read identity
    ↓
validate ProcessInstanceKey or pidfd
    ↓
match again when needed
    ↓
terminate exact instance
```

## 36.4 Do not let launcher logic own process discovery

`FocusLauncherService` should request:

```text
process repository
matcher
launch session manager
```

rather than directly scanning and interpreting process names in several places.

---

# 37. `LauncherContent` requirements

Before launch:

```text
Does this application already have a live matching runtime?
```

not:

```text
Does executable basename X exist?
```

Launch using `LaunchDefinition`.

For desktop applications:

```text
prefer existing supported desktop launch semantics
```

For Flatpak/Snap:

```text
preserve package-aware launch argv
```

Do not accidentally launch the wrapper as the target when the actual package identity is the application.

---

# 38. Process name/path manual semantics

### Process name

```text
trim
case-insensitive default
exact field match
ambiguity detection
```

### Executable basename

```text
trim
case-insensitive default
exact basename match
```

### Executable path

```text
trim
must be absolute for exact-path mode
case-sensitive
no shell interpretation
```

### Working directory

```text
absolute path
case-sensitive
```

---

# 39. Command-line parser and alias cleanup

## 39.1 Remove broad alias generation

Retire/replace `linuxCommandLineAliases()` as a durable application identity mechanism.

It may remain temporarily behind compatibility code if existing catalog UI needs it, but the new reference/matcher layer MUST NOT depend on arbitrary token aliases.

## 39.2 Remove dotted-token package heuristic

Explicitly remove/replace any helper equivalent to:

```text
linuxPackageIdFromCommandLine()
```

when it infers package identity merely because a token contains dots.

Package identity must come from recognized semantics.

## 39.3 Typed argv interpretation

For Java/Minecraft, recognize:

```text
main class
key/value launcher arguments
paths
known loader/runtime markers
```

Do not interpret every token as an application name.

---

# 40. Security / privacy of command-line inspection

Never persist the entire raw command line as durable application identity.

JVM/game launch command lines can include authentication-related values.

Persist only stable discriminators such as:

```text
main class
selected game directory
known runtime markers
safe path values
package ID
desktop ID
```

Redact or omit:

```text
access tokens
credential-like values
authentication fields
session secrets
```

Raw command line may be displayed temporarily under a technical diagnostics panel, subject to redaction.

---

# 41. Generic runtime safety

Remove generic runtimes from the global application-safe concept.

`FOCUSFLOW_RUNTIME` MUST be explicit-process protection, not FocusFlow ancestry protection. An implementation MUST NOT classify arbitrary descendants as globally protected.

At minimum audit:

```text
java
javaw
kotlin
python
python3
node
bash
sh
zsh
ruby
dotnet
mono
```

Classify existing `LinuxProcessSafety` entries into:

```text
FOCUSFLOW_RUNTIME
SYSTEM_CRITICAL
DESKTOP_SESSION
APPLICATION_RUNTIME
```

Only the first three may be globally protected according to actual project policy.

`APPLICATION_RUNTIME` must go through attribution.

---

# 42. Foreground detection

Keep foreground detection separate from process attribution.

Conceptually:

```text
foreground/window layer
    -> UI/overlay reaction

process repository + matcher
    -> enforcement
```

Native Wayland limitations must not disable process-wide enforcement.

A lack of foreground PID/window information must not be interpreted as:

```text
process does not exist
```

---

# 43. Performance constraints

The implementation must avoid turning every 500 ms enforcement tick into many independent `/proc` full scans.

Required:

- one snapshot generation per consumer tick where practical;
- reuse process snapshots among Linux enforcement components;
- compile/parse application selectors once and cache them;
- cache desktop/package catalog metadata;
- read expensive fields lazily when not needed;
- do not continuously hash executable binaries;
- do not continuously resolve symlinks/realpaths unless needed;
- bound launch-session lifetime;
- avoid duplicate full process scans from ProcessMonitor + FocusLauncher + another service at the same time.

---

# 44. Permissions / partial process visibility

Some Linux configurations may restrict procfs visibility or fields.

The process layer must distinguish:

```text
not present
permission denied
field unreadable
process exited
```

Do not collapse all of these to:

```text
not a match
```

For Focus Launcher authorization:

```text
missing identity evidence
```

must never create an allow path.

For destructive enforcement:

follow §21 and do not kill solely by PID when exact process identity cannot be established.

---

# 45. Application catalog redesign

## 45.1 `AppDescriptor`

Continue to expose rich discovery metadata.

Prefer adding structured fields rather than removing existing ones:

```text
runtimeFamily
executionEnvironment
runtimeDefinitions
launchDefinition
correlation status
```

## 45.2 Running-app grouping

Do not group every Java process into one application simply because executable basename is `java`.

Grouping should use stronger evidence first:

```text
package/desktop identity
exact path
runtime signature
working directory
main class
```

and use process name only as fallback/ambiguity-aware information.

## 45.3 Catalog display

A display candidate may combine multiple process instances belonging to one application.

Example:

```text
Firefox
    main process
    6 helper processes
```

This is one application candidate, not seven independent app choices.

---

# 46. Focus Launcher UI model migration

## 46.1 In-memory state

Selected applications should be represented by:

```text
referenceId
```

not only:

```text
processName
```

## 46.2 Legacy hydration

When an old preset contains a process name:

```text
resolve legacy selector
    ↓
unique rich reference?
    YES -> use reference
    NO  -> preserve legacy value + mark ambiguous if generic
```

## 46.3 UI warning for broad selectors

If user explicitly selects:

```text
java
```

show:

```text
This target can match multiple applications that use Java.
```

Do not hide the breadth of the selector.

---

# 47. Launch cleanup and lifecycle

LaunchSession resources MUST be closed deterministically.

On session end:

```text
stop process observer
stop polling job
release pidfds
remove in-memory session
persist only safe diagnostic outcome if needed
```

Do not leave a background observer running after the session has ended.

## 47.1 Launcher closes normally

Continue through:

```text
LAUNCHER_EXITED
POST_EXIT_GRACE
```

if a runtime candidate has not yet been fully attached.

## 47.2 Launcher closes before runtime association

Do not infer success.

Continue bounded grace observation, then fail the launch session if no runtime was attributed.

## 47.3 Runtime exits

Clear live process-instance association.

Do not treat the old PID as reusable authorization.

---

# 48. Testing requirements — mandatory before declaring complete

## 48.1 Selector expression tests

Test:

```text
ALL
ANY
nested ALL/ANY
empty validation
short-circuit correctness
```

Minecraft-specific example:

```text
ALL(
    runtimeFamily == JAVA,
    ANY(
        MinecraftMain,
        recognizedMinecraftLoaderEntryPoint,
        otherStrongMinecraftRuntimeEvidence
    ),
    ANY(
        matchingGameDir,
        otherStrongInstanceOrInstallDiscriminator
    )
)
```

This MUST NOT match:

```text
IntelliJ Java
```

or any other Java process that merely shares the Java runtime, even if it happens to contain a matching `--gameDir`. The test MUST separately verify that `java + matchingGameDir` without Minecraft-specific evidence is rejected.

## 48.2 Case semantics tests

Verify:

```text
Foo == foo for PROCESS_NAME
/home/X == /home/x => NOT equal for EXECUTABLE_PATH
MainClass case mismatch => NOT equal
```

## 48.3 Reference ID tests

Verify:

```text
referenceId != null
stableAppId may be null
path-only reference can persist
```

## 48.4 Snapshot parser tests

Test:

```text
exe
cmdline NUL parsing
comm
PPID
PGID
session ID
cgroup
working directory
start-time value
missing fields
permission failures
process disappearance during read
```

## 48.5 Attribution tests

Test:

```text
PRIMARY match
HELPER match
LAUNCHER match
AMBIGUOUS
INSUFFICIENT_DATA
NOT_MATCHED
```

## 48.5 Launcher bootstrap race test

Simulate:

```text
LaunchSession armed
launcher spawned
process-monitor sweep runs immediately
launcher ProcessInstanceKey not yet fully bound
```

Expected:

```text
launcher is not killed
LAUNCH_HANDOFF_ONLY bootstrap authorization is active
launcher instance is later bound
bootstrap scope is narrowed/expired
```

## 48.5a Launcher bootstrap baseline-isolation test

Simulate:

```text
baseline contains PID A = existing Minecraft launcher
FocusFlow starts a second Minecraft launcher
PID B = directly spawned launcher instance
process sweep sees both A and B
```

Expected:

```text
PID A is not granted LAUNCH_HANDOFF_BOOTSTRAP
PID B is selected/validated as the launch root when possible
no arbitrary first-match selection is used
```

## 48.5b Authorization-state separation test

Verify distinct outcomes:

```text
ALLOW
DENY_AND_SAFE_TO_TERMINATE
UNKNOWN / INSUFFICIENT_DATA / UNSAFE_TO_TERMINATE
```

Expected:

```text
ALLOW -> no kill
DENY_AND_SAFE_TO_TERMINATE -> destructive action may occur after final revalidation
UNKNOWN -> no destructive action
```

## 48.5c Launch & Detect lifecycle-boundary test

During configuration-time `Launch & Detect`, verify that candidate observation does not create temporary Focus Launcher authorization. The process must be allowed to continue only through the normal configuration-flow lifecycle or through an already-active explicit enforcement session, never through an implicit candidate allow rule.

## 48.6 Captured-vs-associated test

Simulate:

```text
Minecraft launch
Java candidate A = Minecraft
Java candidate B = unrelated Java
```

Both can enter:

```text
candidateInstances
```

Only A may enter:

```text
associatedInstances
```

B must remain unauthorized.

## 48.7 `exec()` handoff test

Simulate:

```text
baseline:
PID X, start S, launcher

next snapshot:
PID X, start S, java/Minecraft
```

Expected:

```text
identity change detected
Minecraft candidate evaluated
```

No new PID is required.

## 48.8 Same-instance launcher-close safety test

Simulate:

```text
launcherInstance == primaryRuntimeInstance
```

Expected:

```text
launcher auto-close is skipped
```

## 48.9 PID reuse test

Simulate:

```text
PID X + start S1 = Minecraft
PID X + start S2 = unrelated process
```

Expected:

```text
S1 association does not authorize S2
```

## 48.10 Missing start-time test

Simulate:

```text
PID X
startTime = null
pidfd = unavailable
```

Expected:

```text
no destructive PID-only action
```

## 48.11 cgroup escape test

Simulate:

```text
allowed browser
    -> terminal child
same cgroup
```

Expected:

```text
terminal not automatically authorized
```

## 48.12 Legacy generic-runtime migration test

Input:

```text
Focus Launcher preset = java
```

Expected:

```text
legacy runtime marked ambiguous
not silently converted into “all Java allowed”
```

## 48.13 Package heuristic regression test

Input argv containing:

```text
some.dotted.token
```

Expected:

```text
no packageId inferred unless a recognized package semantic exists
```

## 48.14 Manual picker tests

Verify:

```text
absolute path accepted
process name accepted
case behavior correct
ambiguity shown
running-process capture works
technical details available
path-only reference persists
```

## 48.14a Java argument parsing test

Verify reliable semantic handling of:

```text
java -cp <cp> com.example.Main
java -classpath <cp> com.example.Main
java -jar <jar>
java -m module/com.example.Main
```

Also verify that:

```text
--key value
--key=value
``
parse to the same semantic argument where the option grammar permits both forms.

Do not accept a class-looking token unless the Java launch form establishes it as the main class. For `-jar`, verify that the parser does not invent a main class from the jar filename.

## 48.14b `ARGUMENT_EQUALS` syntax test

Verify equivalent parsing for supported forms:

```text
--gameDir /x/y
--gameDir=/x/y
```

Verify substring-only matches do not satisfy the predicate.

## 48.14c Game-directory-only negative test

A non-Minecraft Java process with `--gameDir /home/user/.minecraft` but no Minecraft-specific runtime evidence MUST NOT be attributed as Minecraft, even during ordinary catalog/running-process discovery. Launch-session attribution may strengthen a candidate only when the selected Minecraft LaunchDefinition and active LaunchSession provide the additional attribution context.

## 48.15 Minecraft acceptance test

System state:

```text
Minecraft launcher selected
unrelated Java process already running
```

Launch.

Expected:

```text
Minecraft Java runtime -> associated/allowed
unrelated Java -> unauthorized
```

Then:

```text
Minecraft launcher exits
```

Expected:

```text
Minecraft runtime remains associated
```

If the launcher and runtime are one process through `exec()`:

```text
FocusFlow does not kill that process
```

## 48.15a Resolver boundary test

Given a process that the resolver marks as possible or ambiguous:

```text
resolver result = POSSIBLE / AMBIGUOUS
```

Expected:

```text
no automatic authorization mutation
no silent persistence as a fully resolved runtime identity
```

## 48.16 Flatpak/Snap tests

Verify:

```text
flatpak/snap wrapper executable != application identity
```

and:

```text
bwrap/flatpak/snap generic executables
```

do not become globally allowed application runtimes.

## 48.17 Wayland tests

Verify process enforcement works without relying on foreground-window PID discovery.

## 48.18 Windows regression tests

Run the existing Windows test suite.

Ensure:

```text
Windows process matching unchanged
Windows .exe normalization unchanged
shared persistence remains backward compatible
```

---

# 49. Recommended implementation phases

## Phase 0 — safety correction

Must happen first:

1. remove generic Java/runtime names from global Linux application-safe semantics;
2. preserve FocusFlow/system safety independently;
3. add regression tests proving arbitrary Java is not globally authorized.

## Phase 1 — canonical Linux process repository

Implement:

```text
LinuxProcessSnapshot
LinuxProcessRepository
ProcessInstanceKey
ProcessFingerprint
```

and canonical procfs parsing.

## Phase 2 — matcher and attribution

Implement:

```text
SelectorExpression
RuntimeSelector
ProcessIdentityMatcher
AttributionResult
RuntimeDefinition
```

with explicit `ALL`/`ANY` behavior.

## Phase 3 — application reference bridge

Implement:

```text
referenceId
nullable stableAppId
optional legacy process name
runtime definitions
launch definition
```

and persistence/migration adapters.

## Phase 4 — Focus Launcher migration

Replace process-name allowlist logic with:

```text
application references
runtime definitions
launch sessions
three-state authorization decisions
launch-instance-bound bootstrap authorization
```

## Phase 5 — Launch & Detect

Implement polling capture first.

Required:

```text
baseline
created process detection
exec/change detection
candidate vs associated
launcher handoff
launcher-close safety
```

## Phase 6 — Minecraft runtime attribution

Implement generic runtime detector + Minecraft detector.

No Minecraft hardcoded process-name allowlist.

## Phase 7 — Manual Linux UX

Implement:

```text
installed app
running app
launch & detect
advanced process name
advanced executable path
advanced command predicate
desktop/package identity
ambiguity flow
```

## Phase 7.5 — Compatibility adapter convergence

Any legacy consumer that still requires `processName` MUST receive it through a compatibility adapter backed by the canonical matcher. Do not add a second process-name matching implementation inside the consumer.

## Phase 8 — Other Linux enforcement paths

Migrate standard blocking/schedule/allowance/network/nuclear paths to the same repository/matcher where applicable.

## Phase 9 — Stronger Linux backends

Optional:

```text
process creation event backend
pidfd operations
dedicated systemd/cgroup launch scopes
```

These are enhancements, not prerequisites for the core architecture.

---

# 50. File-level change map

## Must inspect/change

```text
enforcement/InstalledAppsScanner.kt
enforcement/ProcessMonitor.kt
enforcement/LinuxProcessSafety.kt
services/FocusLauncherService.kt
ui/components/LinuxAppPicker.kt
ui/screens/FocusLauncherScreen.kt
ui/launcher/LauncherContent.kt
data/models/AppReference.kt
data/models/Models.kt
data/Database.kt
```

## Likely change

```text
services/BlockScheduleService.kt
services/StandaloneBlockService.kt
services/DailyAllowanceTracker.kt
services/FocusSessionService.kt
enforcement/NetworkBlocker.kt
enforcement/AppBlocker.kt
enforcement/NuclearMode.kt
ui/screens/FocusScreen.kt
services/CrashReporter.kt
stored-data migration files
```

## Keep for compatibility

```text
ProcessNameNormalizer.kt
```

but demote it from the canonical identity role.

## Likely new files / cohesive modules

Names may follow project conventions:

```text
enforcement/linux/LinuxProcessSnapshot.kt
enforcement/linux/LinuxProcessRepository.kt
enforcement/linux/ProcessIdentityMatcher.kt
enforcement/linux/RuntimeSelector.kt
enforcement/linux/RuntimeDefinition.kt
enforcement/linux/LaunchSession.kt
enforcement/linux/LaunchSessionManager.kt
enforcement/linux/RuntimeDetector.kt
enforcement/linux/MinecraftRuntimeDetector.kt
enforcement/linux/ProcessApplicationResolver.kt
```

Do not create a tiny file for every predicate type if the project structure favors cohesive modules.

---

# 51. Things the implementation agent MUST NOT do

Do not:

```text
add java/minecraft to a global safe-name list
```

Do not:

```text
make Minecraft = java
```

Do not:

```text
use PID alone as persistent identity
```

Do not:

```text
allow all processes in the selected app cgroup
```

Do not:

```text
allow every process observed during launch capture
```

Do not:

```text
only detect new PIDs and ignore exec()-based image changes
```

Do not:

```text
kill the launcher without rechecking its current process identity
```

Do not:

```text
apply one global ignoreCase flag to every selector
```

Do not:

```text
force path-only targets to invent a processName
```

Do not:

```text
make stableAppId a required foreign-key identity
```

Do not:

```text
infer package IDs from arbitrary dotted argv tokens
```

Do not:

```text
convert arbitrary command-line tokens into app aliases
```

Do not:

```text
persist raw command lines containing credentials/tokens
```

Do not:

```text
let foreground-window detection be the sole Linux enforcement signal
```

Do not:

```text
replace all existing persisted process-name fields in one destructive migration
```

Do not:

```text
rewrite unrelated Windows enforcement as part of this task
```

Do not:

```text
build only the UI while leaving enforcement on Set<String> process names
```

---

# 52. Definition of done

Implementation is complete only when all of the following are true.

## Application identity

- application references have a non-null internal `referenceId`;
- external/stable identity is nullable;
- process name is optional/legacy;
- launch definition is separate from runtime identity;
- one application can represent multiple runtime definitions.

## Selector model

- boolean selector expression explicitly supports `ALL` and `ANY`;
- selector semantics are serialized without ambiguity;
- case rules are type-specific;
- filesystem paths remain case-sensitive;
- process names remain friendly/case-insensitive by default;
- ambiguity is surfaced instead of arbitrarily resolved.

## Process subsystem

- one canonical Linux process repository exists;
- snapshots contain process-instance identity data;
- `exec()` changes are detectable;
- process identity is not PID-only;
- missing start time produces an unknown/non-authoritative process observation that cannot enter authorization state, even if a pidfd is available;
- PID reuse cannot transfer an old association;
- pidfd is optional hardening, not a hidden dependency;
- raw snapshots, derived runtime metadata, and catalog correlation are separate layers.

## Attribution

- candidate vs associated processes are distinct;
- cgroup is evidence, not blanket authorization;
- helper attribution is explicit;
- discovery confidence is not an authorization score.

## Manual Linux UX

- user can select a running process/application;
- user can provide an executable path;
- user can provide a process name;
- user can provide a structured command-line rule;
- user can select desktop/package identity where available;
- path input is not rejected merely for being a path;
- technical diagnostics can explain what will be matched.

## Focus Launcher

- launcher policy no longer fundamentally depends on `Set<String>` process names;
- selected application references drive authorization;
- active launch sessions drive controlled handoff;
- unrelated Java/Python/Node/etc. processes are not automatically allowed.

## Minecraft

- launcher → JVM handoff is detected while launch is occurring;
- launcher exit does not cause Minecraft to disappear from attribution;
- vanilla and known mod-loader/runtime variants can be matched;
- Minecraft is not represented as generic `java`;
- unrelated Java remains unauthorized;
- same-PID `exec()` handoff cannot cause FocusFlow to kill the runtime.

## Protected-process classification

The implementation/test artifacts MUST contain a reasoned classification inventory for every globally protected process category, for example:

```text
FOCUSFLOW_RUNTIME
SYSTEM_CRITICAL
DESKTOP_SESSION
```

Do not globally protect a process merely because removing it once caused a regression; protection requires an explicit reason and regression test.

## Safety

- authorization distinguishes `ALLOW`, `DENY_AND_SAFE_TO_TERMINATE`, and `UNKNOWN`/`UNSAFE_TO_TERMINATE`;
- not authorized does not imply destructively killable;
- no generic runtime escape hatch;
- no PID-only destructive kill;
- no `(pid, null)` authorization identity;
- no FocusFlow-descendant-only global protection;
- no ambiguous silent authorization;
- no shell injection;
- no command-line secret persistence;
- no cgroup descendant blanket authorization.

## Compatibility

- existing user data remains readable;
- legacy generic-runtime entries are not silently converted into broad allow rules;
- Windows behavior is not regressed.

---

# 53. Engineering rationale for the disputed review points

This section exists so the implementation agent does not “simplify” a required rule back into the old unsafe model.

## 53.1 Why `ALL`/`ANY` must be explicit

Minecraft needs semantics closer to:

```text
Java
AND
Minecraft-specific evidence
AND/OR
instance/game-directory discriminator
```

not:

```text
Java
OR
Minecraft token
```

A simple list cannot represent that safely.

## 53.2 Why `captured != associated`

A launch window can observe unrelated processes:

```text
Minecraft JVM
another Java JVM
helper
```

Observation is not proof of ownership.

## 53.3 Why cgroup is not enough

A process can create children that are not semantically part of the allowed application. Cgroup inheritance is useful for session attribution but does not define the application's semantic boundary.

## 53.4 Why path identity cannot be forced into process-name identity

A manually supplied path may be the strongest available selector. Replacing it with its basename recreates collision risks.

## 53.5 Why `exec()` is mandatory to handle

A launch handoff can occur without a new PID/process instance. A design that watches only new processes is incomplete.

## 53.6 Why launcher auto-close needs a second verification

The process with a given PID/start-time pair can change executable image through `exec()` without changing the process instance. Therefore “this was the launcher earlier” is not enough.

## 53.7 Why legacy `java` needs special migration handling

A legacy field containing `java` has historically represented one application poorly. Automatically migrating it to a new rich reference as “Java application” would create exactly the broad authorization bug the new architecture is intended to remove.

## 53.8 Why runtime family and execution environment are separate

`JAVA` describes execution technology; `FLATPAK` describes packaging/sandboxing. Conflating them makes future matching and diagnostics harder to reason about.

## 53.9 Why pidfd is optional

The architecture should work on a wide range of Linux environments without turning a native kernel interface into a mandatory installation dependency. Where available, pidfd hardens process-instance operations.

---

# 54. Research / technical basis

The following external behavior supports the design decisions above.

### Linux `execve()`

Linux/POSIX `exec` replaces the current process image. It does not create a fresh process in the ordinary sense. This is the basis for requiring same-instance identity-change detection during launch capture.

Source: Linux/POSIX `exec(3p)` / `execve(2)`: https://www.man7.org/linux/man-pages/man3/execve.3p.html

### Linux cgroup v2

Cgroup v2 is a hierarchical mechanism for organizing processes; processes created by a process begin in its cgroup, but cgroup membership can change. This supports using cgroups as session evidence while rejecting blanket descendant authorization.

Source: Linux kernel cgroup v2 documentation: https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html

### pidfd

Linux pidfds provide stable process references that can be polled and used for signaling, avoiding traditional PID-reuse races. This supports optional hardening of live process operations.

Sources: `pidfd_open(2)`: https://www.man7.org/linux/man-pages/man2/pidfd_open.2.html ; `pidfd_send_signal(2)`: https://www.man7.org/linux/man-pages/man2/pidfd_send_signal.2.html

### Desktop entries / D-Bus activation

The Desktop Entry Specification defines `Exec` as a launch command and `DBusActivatable=true` as a signal that the implementation should use D-Bus activation instead of treating `Exec` as the actual activation path. This supports separating launch definition from runtime identity.

Source: Freedesktop Desktop Entry Specification: https://specifications.freedesktop.org/desktop-entry/latest-single/

---

# 55A. Final pre-coding review status

The v3 review identified three substantive final clarifications plus one lifecycle clarification:

```text
1. Minecraft test contradiction
2. launcher bootstrap must be launch-instance bound
3. authorization must distinguish denial from unsafe-to-terminate
4. Launch & Detect must remain a configuration-time discovery flow
```

V4 resolves all four explicitly. The coding/diff agent MUST treat the V4 amendment section as authoritative over any older example or wording below.

No further architectural redesign is expected from the implementation agent. Remaining decisions should be limited to concrete implementation details that preserve these invariants.

# 55. Final implementation principle

The system should evolve from:

```text
Linux application
    ↓
process name
    ↓
Set<String>
    ↓
kill / allow
```

to:

```text
Installed/selected application
    ↓
ApplicationReference
    ├── LaunchDefinition
    └── RuntimeDefinition(s)
            ↓
     SelectorExpression
            ↓
     LinuxProcessSnapshot
            ↓
     ProcessIdentityMatcher
            ↓
     Application attribution
            ↓
     LaunchSession (when launched by FocusFlow)
            ↓
     safe instance-aware enforcement
```

For Minecraft:

```text
Minecraft application
        ↓
selected launcher
        ↓
LaunchSession armed before launch
        ↓
launcher process
        ↓
new or exec-replaced Java process
        ↓
Minecraft runtime matcher
        ↓
associated Minecraft runtime
        ↓
launcher may exit safely
        ↓
Minecraft remains tracked
```

The implementation must preserve this distinction at every layer:

```text
Application identity
≠
Runtime technology
≠
Process instance
≠
Foreground window
≠
Launcher wrapper
```

That distinction is the core requirement of this task.

# V7 FINAL AMENDMENT — PIDFD DOES NOT UPGRADE UNKNOWN IDENTITY

This v7 revision incorporates the final inspector correction to v6. The v1 authorization/domain identity is strictly `PID + non-null processStartTicks`. A pidfd is an optional live-kernel operation/revalidation handle only. It MUST NOT serve as an alternative domain identity, MUST NOT make `(pid, null)` authorization-bearing, and MUST NOT make an unknown process eligible for destructive enforcement.

The authoritative safety state machine is therefore:

```text
KNOWN_PROCESS_INSTANCE
    +
DENY_AND_SAFE_TO_TERMINATE
    +
final revalidation
    ↓
destructive action may proceed

UNKNOWN_PROCESS_INSTANCE
    ↓
no destructive enforcement in v1
```

Any pidfd-backed termination in v1 is permitted only after the process already has a validated `ProcessInstanceKey` with non-null start ticks and has independently reached `DENY_AND_SAFE_TO_TERMINATE`.

The implementation agent MUST NOT implement either of these interpretations:

```text
pidfd + missing startTicks → valid ProcessInstanceKey
pidfd + missing startTicks → destructively killable
```

The duplicate/inherited wording in this document has been aligned to this rule; this v7 section is the final authority if any older descriptive wording is encountered.

## Additional mandatory regression test

```text
T1: PID X observed for Minecraft
    processStartTicks unavailable
    live pidfd A exists

T2: process exits

T3: PID X is reused by unrelated Java
    processStartTicks still unavailable
    live pidfd B exists
```

Expected:

```text
old association cannot exist as an authorization-bearing identity
new PID X is UNKNOWN_PROCESS_INSTANCE
new PID X is not associated with Minecraft
new PID X is not authorized through the old session state
no destructive action is taken solely from PID/pidfd without validated start ticks
```

# V7 FINAL GATE — READY FOR CODING

This v7 revision incorporates the final inspector correction to v6. The two v6 structural decisions remain authoritative: (1) authorization identity is **PID + non-null processStartTicks** for v1; pidfd is strictly an optional live operation/revalidation handle and cannot upgrade unknown identity; and (2) there is one authoritative raw `LinuxProcessSnapshot`, with runtime interpretation and catalog correlation kept in separate derived layers.

The implementation/diff agent MUST treat v7 as the sole contract. Do not reintroduce nullable `ProcessInstanceKey` equality, pidfd-backed domain identity, descendant-only FocusFlow protection, or desktop/package/runtime fields into the raw snapshot.

**Decision: GO for implementation.**
