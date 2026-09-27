# FocusFlow Vertical Scroll Inventory

This inventory is the SCR-01 baseline for reliable mouse-wheel and Arrow
Up/Down scrolling. It records each current vertical scroll owner rather than
assuming that every `LazyColumn` belongs to a top-level screen.

## Direct top-level `ScrollState` viewports

These screens own a `rememberScrollState()` and apply it to their primary
vertical content:

| Viewport | Source |
|---|---|
| Dashboard | `src/main/kotlin/com/focusflow/ui/screens/DashboardScreen.kt:132-211` |
| Focus | `src/main/kotlin/com/focusflow/ui/screens/FocusScreen.kt:167-195` |
| Active | `src/main/kotlin/com/focusflow/ui/screens/ActiveScreen.kt:85-92` |
| Block Defense | `src/main/kotlin/com/focusflow/ui/screens/BlockDefenseScreen.kt:72-76` |
| Keyword Blocker | `src/main/kotlin/com/focusflow/ui/screens/KeywordBlockerScreen.kt:91-97` |
| Nuclear Mode | `src/main/kotlin/com/focusflow/ui/screens/NuclearModeScreen.kt:53-60` |
| VPN & Network | `src/main/kotlin/com/focusflow/ui/screens/VpnNetworkScreen.kt:129-139` |
| Profile | `src/main/kotlin/com/focusflow/ui/screens/ProfileScreen.kt:79-83` |
| Daily Notes | `src/main/kotlin/com/focusflow/ui/screens/DailyNotesScreen.kt:79-85` |
| Linux Setup | `src/main/kotlin/com/focusflow/ui/screens/LinuxSetupScreen.kt:44-72` |
| Windows Setup | `src/main/kotlin/com/focusflow/ui/screens/WindowsSetupScreen.kt:29-36` |
| How To Use | `src/main/kotlin/com/focusflow/ui/screens/HowToUseScreen.kt:137-143` |
| Changelog | `src/main/kotlin/com/focusflow/ui/screens/ChangelogScreen.kt:313-319` |
| Contact | `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt:38-99` |

`FocusScreen` also contains horizontal-only scroll areas; they are not
vertical owners and are outside this workstream.

## Direct `LazyColumn` and vertical grid viewports

Each row below is an independently bounded owner and must keep its own state.
Tab-specific lists are listed separately because they do not share a
`LazyListState`.

| Viewport | State / owner | Source |
|---|---|---|
| Tasks list | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/TasksScreen.kt:265-267` |
| Focus Launcher page | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/FocusLauncherScreen.kt:222-224` |
| App Blocker rules | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt:355-357` |
| App Blocker allowance list | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt:1012-1014` |
| Standalone Block list | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt:1973-1975` |
| Reports sessions tab | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/ReportsScreen.kt:192-194` |
| Reports timeline tab | implicit `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/ReportsScreen.kt:385` |
| Reports blocked-apps tab | implicit `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/ReportsScreen.kt:473` |
| Stats weekly tab | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/StatsScreen.kt:205-207` |
| Stats all-time tab | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/StatsScreen.kt:400-402` |
| Stats daily tab | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/StatsScreen.kt:713-715` |
| Settings page | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/SettingsScreen.kt:154-156` |
| Habits list | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/HabitsScreen.kt:209-211` |
| Focus Launcher overlay grid | `LazyGridState` | `src/main/kotlin/com/focusflow/ui/components/FocusLauncherOverlay.kt:149-151` |
| Launcher content grid | `LazyGridState` | `src/main/kotlin/com/focusflow/ui/launcher/LauncherContent.kt:64-73` |
| Onboarding choice grid | implicit grid state | `src/main/kotlin/com/focusflow/ui/components/OnboardingScreen.kt:755` |

The reports tab lists currently rely on their internally created state. Batch
2 must either expose/use those states directly or keep their arrow handler
attached to the actual list owner; it must not create a second state.

## Sidebar viewport

| Viewport | State / owner | Source |
|---|---|---|
| Side navigation | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/SideNav.kt:76-141` |

The visible scrollbar already uses this exact state at
`SideNav.kt:490-494`.

## Bounded dialogs, pickers, and previews

These are independent vertical viewports when the corresponding dialog or
picker is visible:

| Viewport | State / owner | Source |
|---|---|---|
| Tasks add dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/TasksScreen.kt:421-426` |
| Tasks edit dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/TasksScreen.kt:672-677` |
| App Blocker app picker | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt:1575-1577` |
| App Blocker preset/app picker | `LazyListState` | `src/main/kotlin/com/focusflow/ui/screens/AppBlockerScreen.kt:2610-2612` |
| Settings app picker | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/SettingsScreen.kt:1485` |
| Add block schedule dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/SettingsScreen.kt:1919` |
| Add daily allowance dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/SettingsScreen.kt:2024` |
| Add habit dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/HabitsScreen.kt:450` |
| Edit habit dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/HabitsScreen.kt:520` |
| Contact preview | `ScrollState` | `src/main/kotlin/com/focusflow/ui/screens/ContactScreen.kt:266-272` |
| Block schedule editor main content | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/BlockScheduleEditorDialog.kt:98` |
| Block schedule editor inner list | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/BlockScheduleEditorDialog.kt:166` |
| Post-pin recommendations dialog | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/PostPinRecommendationsDialog.kt:48` |
| Onboarding language picker | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/OnboardingScreen.kt:309-315` |
| Onboarding main content | `ScrollState` | `src/main/kotlin/com/focusflow/ui/components/OnboardingScreen.kt:1002-1041` |

Other dialogs were searched and do not currently own vertical scrolling.

## Nested-scroll boundary

`LinuxAppPicker` has one conditional vertical owner:

- `scrollable = true`: it owns its `LazyColumn` at
  `src/main/kotlin/com/focusflow/ui/components/LinuxAppPicker.kt:395-432`.
- `scrollable = false`: it renders a plain `Column` at
  `LinuxAppPicker.kt:433-469`.

`FocusLauncherScreen` passes `scrollable = false` at
`FocusLauncherScreen.kt:439-451`, so the outer launcher `LazyColumn` remains the
only vertical owner for the Linux picker. Batch 2 must preserve this boundary.

## Non-owners and scope exclusions

- `horizontalScroll` instances are not vertical owners.
- `ScrollUtils.kt` provides reusable scrollbar/layout wrappers but does not
  create an additional viewport by itself.
- Native mouse-wheel behavior remains provided by Compose's standard
  `verticalScroll`, `LazyColumn`, and `LazyVerticalGrid` containers.