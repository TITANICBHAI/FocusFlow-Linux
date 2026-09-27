# Reliable Scroll Workstream Tracker

Authoritative plan: `scroll/reliable-scroll-plan.md`

## Status

- Overall: **Not started**
- Current batch: **SCR-01–SCR-03**
- Last verification: None
- Blockers: None recorded

## Rules for ticking items

- Tick an item only after its implementation or evidence is complete.
- Compilation alone is not evidence for keyboard behavior.
- Record the exact tests, workflow, and manual viewport checks in the batch
  record below.
- Keep scope limited to mouse wheel plus Arrow Up/Down.

## Inventory and design

- [ ] **SCR-01** Inventory every top-level screen, sidebar viewport, bounded
  dialog, and picker that owns vertical scrolling.
- [ ] **SCR-02** Choose and document the shared `ScrollState` and
  `LazyListState` arrow-input API, focus strategy, and centralized logical
  step.
- [ ] **SCR-03** Add focused tests or test seams for key filtering, direction,
  one-step movement, clamping, and editable-child event preservation.

## Shared implementation

- [ ] **SCR-04** Add the shared Arrow Up/Down behavior to `ScrollUtils.kt`
  without replacing standard mouse-wheel scrolling.
- [ ] **SCR-05** Apply the shared behavior to every direct `ScrollState`
  screen root.
- [ ] **SCR-06** Apply the shared behavior to every direct `LazyListState`
  screen root.
- [ ] **SCR-07** Apply the shared behavior to the scrollable side navigation.
- [ ] **SCR-08** Apply the shared behavior to bounded dialogs and pickers,
  keeping independent viewports independent.
- [ ] **SCR-09** Confirm Focus Launcher keeps the Linux picker non-scrollable
  inside the outer launcher `LazyColumn`.

## Regression verification

- [ ] **SCR-10** Verify text fields, search, buttons, checkboxes, and switches
  retain their existing arrow and focus behavior.
- [ ] **SCR-11** Verify existing Ctrl navigation shortcuts and the Tasks search
  focus shortcut still work.
- [ ] **SCR-12** Verify every visible scrollbar uses the state moved by the
  corresponding viewport and arrow handler.
- [ ] **SCR-13** Run focused scroll tests and the full Gradle test suite.
- [ ] **SCR-14** Run the desktop manual matrix for mouse wheel and Arrow
  Up/Down on every top-level screen.
- [ ] **SCR-15** Record evidence, limitations, and any skipped environment
  checks in a completion record.

## Manual evidence matrix

Record `pass`, `fail`, or `blocked` with notes for each row.

| Viewport | Mouse wheel | Arrow Up/Down | Text input checked | Notes |
|---|---|---|---|---|
| Dashboard | — | — | — | |
| Tasks | — | — | — | |
| Focus | — | — | — | |
| Focus Launcher | — | — | — | |
| Active | — | — | — | |
| App Blocker | — | — | — | |
| Standalone Block | — | — | — | |
| Block Defense | — | — | — | |
| Keyword Blocker | — | — | — | |
| Nuclear Mode | — | — | — | |
| VPN & Network | — | — | — | |
| Stats | — | — | — | |
| Reports | — | — | — | |
| Notes | — | — | — | |
| Habits | — | — | — | |
| Profile | — | — | — | |
| Settings | — | — | — | |
| Windows/Linux Setup | — | — | — | |
| How To Use | — | — | — | |
| Changelog | — | — | — | |
| Contact | — | — | — | |
| Side navigation | — | — | N/A | |
| Bounded dialogs/pickers | — | — | As applicable | |

## Batch completion records

### Batch SCR-01–SCR-03

- Date:
- Completed items:
- Files changed:
- Tests/checks:
- Manual evidence:
- Blockers:
- Next batch:

### Batch SCR-04–SCR-09

- Date:
- Completed items:
- Files changed:
- Tests/checks:
- Manual evidence:
- Blockers:
- Next batch:

### Batch SCR-10–SCR-15

- Date:
- Completed items:
- Files changed:
- Tests/checks:
- Manual evidence:
- Blockers:
- Next batch: