# Reliable Scroll Workstream Tracker

Authoritative plan: `scroll/reliable-scroll-plan.md`

## Status

- Overall: **Batch 3 partially verified; desktop input blocked**
- Current batch: **SCR-10–SCR-15**
- Last verification: `git diff --check`; focused scroll test; full
  `gradle test --no-daemon`
- Blockers: The Start application workflow is VNC-only and does not expose
  automated desktop input. The browser preview at `127.0.0.1:5000` refused the
  connection.

## Rules for ticking items

- Tick an item only after its implementation or evidence is complete.
- Compilation alone is not evidence for keyboard behavior.
- Record the exact tests, workflow, and manual viewport checks in the batch
  record below.
- Keep scope limited to mouse wheel plus Arrow Up/Down.

## Inventory and design

- [x] **SCR-01** Inventory every top-level screen, sidebar viewport, bounded
  dialog, and picker that owns vertical scrolling.
- [x] **SCR-02** Choose and document the shared `ScrollState` and
  `LazyListState` arrow-input API, focus strategy, and centralized logical
  step.
- [x] **SCR-03** Add focused tests or test seams for key filtering, direction,
  one-step movement, clamping, and editable-child event preservation.

## Shared implementation

- [x] **SCR-04** Add the shared Arrow Up/Down behavior to `ScrollUtils.kt`
  without replacing standard mouse-wheel scrolling.
- [x] **SCR-05** Apply the shared behavior to every direct `ScrollState`
  screen root.
- [x] **SCR-06** Apply the shared behavior to every direct `LazyListState`
  screen root.
- [x] **SCR-07** Apply the shared behavior to the scrollable side navigation.
- [x] **SCR-08** Apply the shared behavior to bounded dialogs and pickers,
  keeping independent viewports independent.
- [x] **SCR-09** Confirm Focus Launcher keeps the Linux picker non-scrollable
  inside the outer launcher `LazyColumn`.

## Regression verification

- [ ] **SCR-10** Verify text fields, search, buttons, checkboxes, and switches
  retain their existing arrow and focus behavior.
- [ ] **SCR-11** Verify existing Ctrl navigation shortcuts and the Tasks search
  focus shortcut still work.
- [x] **SCR-12** Verify every visible scrollbar uses the state moved by the
  corresponding viewport and arrow handler.
- [x] **SCR-13** Run focused scroll tests and the full Gradle test suite.
- [ ] **SCR-14** Run the desktop manual matrix for mouse wheel and Arrow
  Up/Down on every top-level screen.
- [x] **SCR-15** Record evidence, limitations, and any skipped environment
  checks in a completion record.

## Manual evidence matrix

Record `pass`, `fail`, or `blocked` with notes for each row.

| Viewport | Mouse wheel | Arrow Up/Down | Text input checked | Notes |
|---|---|---|---|---|
| Dashboard | blocked | blocked | blocked | VNC input automation unavailable |
| Tasks | blocked | blocked | blocked | VNC input automation unavailable |
| Focus | blocked | blocked | blocked | VNC input automation unavailable |
| Focus Launcher | blocked | blocked | blocked | VNC input automation unavailable |
| Active | blocked | blocked | blocked | VNC input automation unavailable |
| App Blocker | blocked | blocked | blocked | VNC input automation unavailable |
| Standalone Block | blocked | blocked | blocked | VNC input automation unavailable |
| Block Defense | blocked | blocked | blocked | VNC input automation unavailable |
| Keyword Blocker | blocked | blocked | blocked | VNC input automation unavailable |
| Nuclear Mode | blocked | blocked | blocked | VNC input automation unavailable |
| VPN & Network | blocked | blocked | blocked | VNC input automation unavailable |
| Stats | blocked | blocked | blocked | VNC input automation unavailable |
| Reports | blocked | blocked | blocked | VNC input automation unavailable |
| Notes | blocked | blocked | blocked | VNC input automation unavailable |
| Habits | blocked | blocked | blocked | VNC input automation unavailable |
| Profile | blocked | blocked | blocked | VNC input automation unavailable |
| Settings | blocked | blocked | blocked | VNC input automation unavailable |
| Windows/Linux Setup | blocked | blocked | blocked | VNC input automation unavailable |
| How To Use | blocked | blocked | blocked | VNC input automation unavailable |
| Changelog | blocked | blocked | blocked | VNC input automation unavailable |
| Contact | blocked | blocked | blocked | VNC input automation unavailable |
| Side navigation | blocked | blocked | N/A | VNC input automation unavailable |
| Bounded dialogs/pickers | blocked | blocked | blocked | VNC input automation unavailable |

## Batch completion records

### Batch SCR-01–SCR-03

- Date: 2026-09-27
- Completed items: SCR-01, SCR-02, SCR-03
- Files changed:
  - `scroll/scroll-inventory.md`
  - `scroll/reliable-scroll-plan.md`
  - `scroll/scroll-tracker.md`
  - `src/main/kotlin/com/focusflow/ui/components/ScrollKeyboardPolicy.kt`
  - `src/test/kotlin/com/focusflow/ui/components/ScrollKeyboardPolicyTest.kt`
- Tests/checks: `git diff --check`; `gradle test --no-daemon
  --tests com.focusflow.ui.components.ScrollKeyboardPolicyTest` — passed.
- Manual evidence: Not applicable to the inventory/design/test-seam batch.
- Blockers: Full desktop behavior is not claimed until SCR-04–SCR-15.
- Next batch: SCR-04–SCR-09, shared modifier implementation and viewport wiring.

### Batch SCR-04–SCR-09

- Date: 2026-09-27
- Completed items: SCR-04, SCR-05, SCR-06, SCR-07, SCR-08, SCR-09
- Files changed:
  - `src/main/kotlin/com/focusflow/ui/components/ScrollUtils.kt`
  - `src/main/kotlin/com/focusflow/ui/components/ScrollKeyboardPolicy.kt`
  - direct screen files under `src/main/kotlin/com/focusflow/ui/screens/`
  - `src/main/kotlin/com/focusflow/ui/components/SideNav.kt`
  - `src/main/kotlin/com/focusflow/ui/components/LinuxAppPicker.kt`
  - `src/main/kotlin/com/focusflow/ui/components/FocusLauncherOverlay.kt`
  - `src/main/kotlin/com/focusflow/ui/components/OnboardingScreen.kt`
  - `src/main/kotlin/com/focusflow/ui/components/BlockScheduleEditorDialog.kt`
  - `src/main/kotlin/com/focusflow/ui/components/PostPinRecommendationsDialog.kt`
  - `src/main/kotlin/com/focusflow/ui/launcher/LauncherContent.kt`
- Tests/checks:
  - `gradle compileKotlin --no-daemon` — passed.
  - `gradle test --no-daemon` — passed.
  - `git diff --check` — passed.
- Manual evidence: Not completed in this batch; desktop behavior is tracked by
  SCR-10–SCR-15.
- Blockers: None for implementation. Do not claim full manual reliability yet.
- Next batch: SCR-10–SCR-15, regression and desktop verification.

### Batch SCR-10–SCR-15

- Date: 2026-09-27
- Completed items: SCR-12, SCR-13, SCR-15
- Files changed:
  - `scroll/scroll-tracker.md`
- Tests/checks:
  - `git diff --check` — passed.
  - `gradle test --no-daemon --tests com.focusflow.ui.components.ScrollKeyboardPolicyTest` — passed.
  - `gradle test --no-daemon` — passed.
  - Static source audit confirmed post-child arrow handling, unchanged Ctrl
    navigation/search handlers, and matching scrollbar/state references.
- Manual evidence: SCR-10, SCR-11, and SCR-14 are blocked. Every matrix row
  above is recorded as `blocked`.
- Blockers: The running desktop workflow is VNC-only; this session has no
  desktop input injection tool. The browser preview request to port 5000
  returned `ERR_CONNECTION_REFUSED`.
- Next batch: Repeat SCR-10, SCR-11, and SCR-14 in an interactive desktop
  session, then close the remaining unchecked items.

### Batch SCR-10–SCR-15

- Date:
- Completed items:
- Files changed:
- Tests/checks:
- Manual evidence:
- Blockers:
- Next batch: