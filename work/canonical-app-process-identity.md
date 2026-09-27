# Canonical App/Process Identity Contract

This contract implements the definition work for DATA-06 through DATA-09.
It is intentionally compatible with the existing `AppDescriptor` catalog and
does not add a database migration. Existing process-only columns remain
readable until the later schema workstream adds storage for the extra fields.

## DATA-06 — canonical process normalization

### Canonical process value

A canonical process value is a trimmed, Unicode-root-lowercased executable
basename containing only:

```text
a-z 0-9 _ . + -
```

It is not a desktop ID, package ID, path, command line, display name, or
arbitrary user description. The canonical process value remains the primary
enforcement value even after stable app identity is added.

### Platform rules

| Input/context | Windows | Linux/other |
|---|---|---|
| New catalog/manual process without suffix | Append `.exe` only for Windows compatibility | Never append `.exe` |
| Existing known FocusFlow-generated Windows value | Keep `.exe` | Remove `.exe` for the explicit known compatibility list |
| Existing unknown `legacy-tool.exe` | Keep unchanged except trim/case normalization | Keep `.exe`; do not guess that it means `legacy-tool` |
| Explicit executable path from a trusted catalog parser | Extract basename, then normalize | Extract basename, then normalize |
| Persisted value containing a path | Preserve as an unresolved/stale value; do not rewrite automatically | Preserve as an unresolved/stale value; do not rewrite automatically |
| Manual input containing a path, command line, whitespace, or shell metacharacter | Reject | Reject |
| Blank list element | Ignore for the canonical list, never create a fake process | Ignore for the canonical list, never create a fake process |

The implementation is centralized in `ProcessNameNormalizer`. Its APIs have
different safety contracts:

- `normalize` handles a trusted single token.
- `normalizeManual` validates a new user-entered process.
- `normalizeStored` performs compatibility normalization without dropping an
  unknown stored value.
- `executableBasename` is opt-in path parsing for trusted catalog input.
- `normalizeStoredList` and `normalizeAliases` trim, normalize, remove blanks,
  and preserve first-seen order while deduplicating.
- `equivalentStored` compares values using the same known-legacy mapping.

### Alias rules

Aliases are supplied by the app catalog, desktop metadata, package metadata,
or a running-process observation. The normalizer does not invent semantic
aliases. Every alias is normalized with the same process rules, and duplicate
aliases are removed in first-seen order. The primary process remains separate
from aliases so enforcement never depends on a desktop ID or display name.

## DATA-07 — canonical app-reference fields

The app catalog already exposes the following source fields in
`AppDescriptor`:

| Canonical field | Current catalog field | Purpose |
|---|---|---|
| `stableId` | `desktopId` or `packageId` | Stable application identity when available |
| `displayName` | `displayName` | User-visible name preserved across stale periods |
| `primaryProcessName` | `processName` | Required enforcement value |
| `processAliases` | `processAliases` | Additional runtime names used for matching |
| `source` | `AppSource` | Discovery provenance |
| `desktopFilePath` | `desktopFilePath` | Linux desktop-entry provenance |
| `packageId` | `packageId` | Flatpak/Snap/package identity |
| `lastResolvedAtMs` | catalog metadata to be added at persistence time | Last successful catalog resolution |
| `resolutionStatus` | derived from catalog lookup | `resolved`, `stale`, `unresolved`, or `ambiguous` |

`exePath`, `execCommand`, icon data, categories, running PIDs, and detection
confidence remain catalog/runtime metadata. They are useful for display,
launching, and diagnostics but are not the sole persisted enforcement identity.

The code contract is represented by `CanonicalAppReference` in
`com.focusflow.data.models`. It is deliberately not wired into SQLite yet;
that belongs to DATA-11 through DATA-13 after the app-catalog schema contract
is finalized.

## DATA-08 — source and resolution status

### Source values

The persisted wire values are:

```text
catalog_native
catalog_flatpak
catalog_snap
windows_registry
running_only
manual
legacy
```

`legacy` is for process-only data whose original source cannot be recovered.
Staleness is not a source; it is a resolution status.

### Resolution status values

| Status | Meaning | Required behavior |
|---|---|---|
| `resolved` | Exactly one current catalog identity matches | Use its primary process and aliases; retain the stored display name unless the user explicitly changes it |
| `stale` | Previously known identity is no longer in the current catalog | Keep the reference visible and keep its process value; do not delete the rule |
| `unresolved` | Manual/legacy value has no current catalog match | Keep it as a manual process candidate; allow later refresh or reselection |
| `ambiguous` | More than one catalog identity matches | Do not auto-select; preserve the raw reference and ask for explicit user selection |

A missing stable ID does not make a reference invalid when a safe primary
process value exists. Conversely, a stable ID alone must not be used to enforce
blocking.

## DATA-09 — duplicate/conflict behavior

Normalization can make old records equivalent, especially `Discord.exe` and
`discord` on Linux. The policy is:

1. **Never silently delete a record.** Preserve original IDs, display names,
   enabled flags, network flags, and historical rows.
2. **Duplicate block rules:** use one canonical process key for active
   enforcement, but classify all contributing rule IDs as a
   `duplicate-normalized-process` conflict. If rule semantics differ, require
   explicit user resolution; do not choose an enabled/network value silently.
3. **Duplicate daily allowances:** if normalized process and allowance values
   are identical, they are mergeable for runtime lookup while retaining all
   source IDs/history. If allowance minutes or display names conflict, classify
   the key as a conflict and do not choose max, min, first, or last
   automatically.
4. **Serialized lists:** remove duplicate canonical values for runtime
   enforcement while preserving unresolved original references and recording
   that a duplicate was collapsed.
5. **Stable identity conflicts:** if stable IDs disagree with process/alias
   matches, prefer neither automatically; mark `ambiguous` and preserve the
   process value until the user reselects an app.
6. **Retryability:** conflict classification must be deterministic and
   idempotent. Re-running normalization must not create new records or alter
   arbitrary user text.

The later schema migration must provide a durable place for conflict/status
metadata before it turns these policy decisions into destructive updates.

## DATA-10 test coverage

`ProcessNameNormalizerTest` covers:

- Case and locale-independent lowercasing
- Windows `.exe` compatibility and Linux no-suffix behavior
- Known versus unknown Windows-shaped values
- Trusted Unix and Windows executable paths
- Manual path/command-line rejection
- Alias normalization and first-seen deduplication
- Empty elements and malformed comma-separated lists
- Safe equivalence comparisons

`InstalledAppsCatalogResolutionTest` continues to cover stable IDs, package
IDs, display names, aliases, known stale `.exe` values, and unknown stale
values. `CanonicalAppReferenceTest` covers the source/status wire contract and
the required primary-process field.