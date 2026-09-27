# FocusFlow Reliable Mouse and Arrow Scroll Plan

## Purpose

Make vertical scrolling reliable across every navigable FocusFlow screen for:

1. Mouse-wheel scrolling.
2. Arrow Up and Arrow Down scrolling by one logical scroll step.

This plan intentionally covers only those two input paths. It does not add
Page Up/Page Down, Home/End, horizontal keyboard scrolling, or unrelated
keyboard shortcuts.

## Status

- Overall: **Planned**
- Owner: FocusFlow UI implementation
- Tracker: `scroll/scroll-tracker.md`
- Agent prompt: `scroll/reliable-scroll-agent-prompt.md`
- Current scope: main screens, sidebar, and bounded scrollable dialogs/pickers

## Current audit

The current screen inventory has vertical scroll containers throughout the
application:

- `verticalScroll` is used by Dashboard, Focus, Block Defense, Keyword
  Blocker, Nuclear Mode, VPN, Profile, Notes, Contact, setup, How To Use,
  Changelog, and similar form/content screens.
- `LazyColumn` is used by Tasks, Focus Launcher, App Blocker, Stats, Reports,
  Settings, Habits, and list-heavy sections.
- The side navigation has its own `verticalScroll`.
- Bounded dialogs and app pickers also contain local vertical scroll areas.

Mouse-wheel scrolling should work through these standard Compose scroll
containers when content is larger than its viewport.

The audit found no shared Arrow Up/Down routing. Existing key handlers are for
navigation shortcuts and search focus, not scroll movement. Native behavior may
work while a particular list has focus, but it is not a consistent application
contract because screen roots do not uniformly own focus or arrow handling.

## Product behavior

### Mouse wheel

- Every scrollable viewport must continue to accept normal desktop
  mouse-wheel input.
- The implementation must not replace `verticalScroll` or `LazyColumn` with a
  custom pointer system.
- A scroll container must remain the only vertical scroll owner for its
  viewport. Do not reintroduce nested unconstrained `LazyColumn` instances.
- Existing visible scrollbars must continue to represent the same state that
  the content uses.

### Arrow Up and Arrow Down

- Arrow Up moves the active vertical scroll owner upward by one logical step.
- Arrow Down moves the active vertical scroll owner downward by one logical
  step.
- The initial logical step should be a density-aware constant of approximately
  `48.dp` (one typical desktop row). Keep the value centralized so it can be
  tuned once.
- Scroll movement must be clamped naturally at the beginning and end.
- Handle only `KeyEventType.KeyDown`; do not repeat-scroll from key-up events.
- Do not consume arrow events from an actively edited text field, text area,
  numeric field, or other control that legitimately uses arrow keys.
- Do not interfere with existing Ctrl-based navigation shortcuts, search focus,
  checkbox, switch, button, or picker behavior.
- Arrow handling must work after the screen is entered and after focus moves
  between ordinary controls.

## Technical approach

### 1. Centralize the input behavior

Extend the existing scroll utilities in
`src/main/kotlin/com/focusflow/ui/components/ScrollUtils.kt` rather than
creating per-screen arrow handlers.

Provide a shared mechanism that can route Arrow Up/Down to either:

- a `ScrollState`, or
- a `LazyListState`.

The helper should:

- use the existing scroll state;
- launch the suspend scroll operation through a remembered coroutine scope;
- convert the centralized `48.dp` step through `LocalDensity`;
- return `true` only when it handles Arrow Up or Arrow Down;
- leave all other keys untouched;
- be safe when the content cannot scroll.

Prefer an `onKeyEvent`/focused-scroll-owner design that lets child text inputs
consume their own arrow keys first. Do not put an unconditional preview handler
above every text field.

### 2. Preserve focus usability

Give each top-level scroll viewport a reliable focus target without forcing
focus into a text field:

- A screen should be able to receive arrow input immediately after navigation.
- Clicking or tabbing into an editor must preserve normal editor arrow behavior.
- Moving focus to a button or checkbox must not disable screen scrolling.
- Do not steal focus from a dialog's first input or from an active search field.

If the chosen Compose Desktop focus behavior cannot satisfy both initial arrow
scrolling and text editing, document the limitation and add the smallest
visible focus affordance needed rather than intercepting text input globally.

### 3. Apply the helper to every scroll owner

Update the actual owner of each vertical viewport, not every child row.
Review these groups explicitly:

- `ScrollState` screen roots: Dashboard, Focus, Block Defense, Keyword
  Blocker, Nuclear Mode, VPN, Profile, Notes, Contact, setup, How To Use,
  Changelog, and any other direct `verticalScroll` root.
- `LazyListState` screen roots: Tasks, Focus Launcher, App Blocker, Stats,
  Reports, Settings, Habits, and any other main `LazyColumn`.
- The scrollable side navigation in `SideNav.kt`.
- Bounded dialog/picker scroll areas in screen files and shared components.
- Linux app-picker modes, ensuring the Focus Launcher non-scrollable picker
  still delegates vertical scrolling to its outer launcher list.

Do not add a second vertical scroll owner to a screen that already has one.
For screens with multiple independent panes or dialogs, attach the behavior to
each independently bounded viewport.

### 4. Keep scrollbar state aligned

Every visible `FfVerticalScrollbar` must receive the exact `ScrollState` or
`LazyListState` used by its content and arrow handler. Do not create a second
state solely for keyboard scrolling.

For scroll containers without an existing visible scrollbar, do not add visual
design changes unless needed to make the input behavior understandable. The
scope is reliable input first.

## Verification plan

### Static checks

- Search for every `verticalScroll`, `LazyColumn`, `LazyGrid`, and bounded
  picker/dialog scroll owner.
- Confirm each owner has mouse-wheel support through the standard Compose
  modifier and the shared Arrow Up/Down behavior.
- Confirm no top-level screen still uses a direct arrow handler with a
  conflicting step or state.
- Confirm text-entry screens do not consume arrows before their inputs.
- Confirm the Focus Launcher still has one vertical owner around the Linux
  picker.

### Automated checks

Add focused tests for the shared behavior where the project’s Compose test
setup permits:

- Arrow Up sends a negative one-step scroll.
- Arrow Down sends a positive one-step scroll.
- Key-up, unrelated keys, and Ctrl-modified arrows are ignored.
- Scroll movement clamps at both ends.
- `ScrollState` and `LazyListState` use the same logical step.
- An editable child can consume arrow events without the screen scrolling.

### Manual desktop matrix

Run the desktop application and verify with content long enough to overflow:

- Mouse wheel over each main screen.
- Arrow Up/Down immediately after entering each main screen.
- Arrow Up/Down after clicking a normal button or checkbox.
- Arrow Up/Down while a text field is focused; caret movement must remain
  normal and must not scroll the page unexpectedly.
- Focus Launcher with the Linux app picker open.
- App Blocker and Settings dialogs with bounded scrolling.
- Sidebar overflow and navigation after scrolling the sidebar.
- Beginning/end clamping and scrollbar thumb movement.

Record any environment-specific limitations instead of marking the related
tracker item complete based on compilation alone.

## Acceptance criteria

This workstream is complete when:

1. All navigable screens and independently bounded scroll viewports are
   inventoried.
2. Mouse-wheel behavior remains standard and functional.
3. Arrow Up and Arrow Down work reliably on every top-level scroll viewport.
4. Each arrow press moves approximately one centralized logical step.
5. Text fields retain normal arrow-key editing behavior.
6. Existing Ctrl shortcuts and focus behavior remain intact.
7. Scrollbars, where present, track the same state that is moved by arrows.
8. The Focus Launcher Linux nested-scroll crash prevention remains intact.
9. Focused tests and the full Gradle test suite pass.
10. Manual desktop evidence is recorded in `scroll/scroll-tracker.md`.

## Non-goals

- Page Up/Page Down, Home/End, Tab navigation redesign, or custom keybindings.
- Horizontal arrow scrolling.
- Redesigning scrollbar visuals.
- Replacing Compose scroll containers.
- Removing Windows compatibility paths.
- Reworking app navigation or unrelated keyboard shortcuts.