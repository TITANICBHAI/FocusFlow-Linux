# Prompt for the Reliable Scroll Agent

Copy the prompt below into a new agent context. Replace
`<ASSIGNED_SCOPE>` with the tracker IDs assigned to that agent.

---

You are implementing FocusFlow's reliable scrolling workstream.

## Project context

FocusFlow is a Kotlin/JVM Compose Desktop productivity application. The
application has both `ScrollState`-based layouts and `LazyColumn`-based
layouts. The requested behavior is deliberately narrow:

- Preserve reliable mouse-wheel scrolling.
- Add reliable Arrow Up and Arrow Down scrolling.
- Move one logical scroll step per arrow key press.

Do not add Page Up/Page Down, Home/End, horizontal keyboard scrolling, or
unrelated keyboard shortcuts in this assignment.

## Read first

Read these files before editing:

```text
replit.md
scroll/reliable-scroll-plan.md
scroll/scroll-tracker.md
.agents/memory/MEMORY.md
```

Then inspect the relevant source and tests, especially:

```text
src/main/kotlin/com/focusflow/ui/components/ScrollUtils.kt
src/main/kotlin/com/focusflow/ui/components/SideNav.kt
src/main/kotlin/com/focusflow/App.kt
src/main/kotlin/com/focusflow/ui/screens/
```

Use search to find every `verticalScroll`, `LazyColumn`, `LazyGrid`, and
bounded dialog/picker scroll owner. Do not assume the initial inventory is
complete.

## Assigned scope

You are assigned exactly:

```text
<ASSIGNED_SCOPE>
```

Implement only that scope. Do not begin unrelated UI, platform migration,
scrollbar redesign, or navigation work. If you find related work outside the
assignment, record it as a blocker or follow-up note instead of expanding the
scope.

## Required behavior

1. Keep standard Compose mouse-wheel behavior intact.
2. Arrow Up moves the active vertical scroll owner up by one logical step.
3. Arrow Down moves the active vertical scroll owner down by one logical step.
4. Use one centralized, density-aware step of approximately `48.dp`.
5. Handle only key-down events for these two keys.
6. Clamp naturally at the beginning and end.
7. Do not consume arrow keys from an actively edited text field or text area.
8. Do not break Ctrl navigation shortcuts, search focus, buttons, checkboxes,
   switches, or app-picker controls.
9. Use the existing `ScrollState` or `LazyListState`; never create a second
   state just for arrow handling.
10. Keep one vertical scroll owner per viewport. In particular, the Focus
    Launcher Linux picker must remain non-scrollable inside the outer launcher
    `LazyColumn`.

## Implementation guidance

- Put reusable behavior in `ScrollUtils.kt` or another focused component file,
  not as copied handlers in every screen.
- Support both `ScrollState` and `LazyListState`.
- Use a remembered coroutine scope for suspend scroll operations.
- Prefer post-child key handling where possible so text inputs retain their
  normal arrow behavior.
- Ensure a screen can receive arrow input after navigation without forcing
  focus into a text field.
- Attach the handler to the actual scroll owner, not each row.
- Preserve existing scrollbar state wiring.

## Verification requirements

Run the narrowest relevant checks during development, then run:

```text
git diff --check
gradle test --no-daemon
```

Use the configured Java 19 environment if needed:

```bash
export JAVA_HOME=/nix/store/c8hr2f0b0dm685yx1dkp6bw24bpx495n-graalvm19-ce-22.3.1
export PATH="$JAVA_HOME/bin:$PATH"
```

For manual verification, start the `Start application` workflow and test:

- Mouse wheel over every top-level screen.
- Arrow Up/Down after entering a screen.
- Arrow Up/Down after focusing a button or checkbox.
- Arrow Up/Down inside text fields, confirming caret movement is preserved.
- Focus Launcher with the Linux app picker.
- Bounded dialogs and pickers.
- Side navigation overflow.
- Top and bottom clamping.

Do not mark manual items complete when the workflow cannot be run. Record the
blocker in the tracker.

## Tracker protocol

Tick only the assigned, verified items in:

```text
scroll/scroll-tracker.md
```

Add a batch completion record with:

- assigned scope;
- tracker items completed;
- files changed;
- tests and checks run;
- manual evidence;
- known limitations or blockers;
- suggested next scope.

Never claim all-screen reliability from compilation alone.

## Final response format

Report:

- Assigned scope
- Tracker items completed
- Files changed
- Tests/checks run and results
- Manual checks completed or blocked
- Items left unchecked
- Known limitations
- Suggested next scope

---

## Current assignment

Replace this line before sending the prompt:

```text
Assigned scope: <ASSIGNED_SCOPE>
```