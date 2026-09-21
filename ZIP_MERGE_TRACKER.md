# FocusFlow ZIP Merge Tracker

## Scope

- **Source archive:** `attached_assets/ref_1790000380180.zip`
- **Archive snapshot:** 109 ZIP entries, including 99 Kotlin source files
- **Compared against:** current `src/main/kotlin/com/focusflow/` tree
- **Date:** 2026-09-21
- **Merge rule:** use the archive as a reference for behavior and UI, but merge changes into the current tree instead of replacing files. Preserve Windows branches and keep Linux behavior primary.

## Result

The archive is a Windows-focused snapshot. The current tree already contains the
Linux port work that is absent from the archive, including:

- Linux platform detection and tool checks
- XDG app-icon lookup
- Flatpak/Snap desktop-file discovery and `Exec=` normalization
- Linux process, network, hosts-file, watchdog, and autostart paths
- X11 keyboard-grab behavior and an honest reduced-protection message on native Wayland
- Linux setup screen and Linux-aware Settings, VPN/Network, onboarding, navigation,
  notification, overlay, and panel-handling UI

No Kotlin file was copied wholesale. The current Linux-aware implementations were
kept where they supersede the archive, and the archive-only Windows features were
not added because they are unused in the current call graph or would reintroduce
an embedded external webhook.

## Decisions from the archive

| Area | Decision | Reason |
|---|---|---|
| Linux enforcement and Linux-facing UI | Keep current implementation | The archive has no Linux equivalents; the current tree has the platform guards and completed migration work. |
| Shared UI components | Keep current implementation unless the archive contains a portable behavior change | Several archive differences are Windows-only copy, release notices, or older validation behavior. |
| `DiscordWebhookClient` and webhook-based feedback/crash reporting | Do not import | The archive embeds an external webhook endpoint. The current `ReviewPromptService` intentionally disables that path and uses the existing consent-controlled Sentry/reporting design. |
| Direct-install uninstall protection | Do not import yet | `InstallVariant`, `UninstallProtectionService`, `UninstallWizard`, `UninstallWizardFlag`, and `WindowsUninstallRegistration` are Windows-only and have no current call sites. Adding them would expand scope without improving Linux. |
| Archive-only Windows dashboard notice | Do not import | It depends on the unused direct-installer classes above. |
| Archive-only Windows uninstall UI and Nuclear Mode branches | Do not import | They are not portable and would need a separately scoped Windows integration pass. |
| Release version metadata | Do not silently change | The archive reports `2.0.1`, while the current Gradle package and changelog path reports `1.1.6`; changing this requires a release decision, not a source snapshot merge. |

## Current-only files intentionally preserved

These files have no archive counterpart and are required by the Linux-aware
current tree:

- `src/main/kotlin/com/focusflow/Platform.kt`
- `src/main/kotlin/com/focusflow/enforcement/LinuxToolsChecker.kt`
- `src/main/kotlin/com/focusflow/ui/components/FocusLauncherOverlay.kt`
- `src/main/kotlin/com/focusflow/ui/screens/LinuxSetupScreen.kt`
- `src/main/resources/focusflow.png`
- `src/test/kotlin/com/focusflow/enforcement/InstalledAppsScannerLinuxTest.kt`
- `src/test/kotlin/com/focusflow/enforcement/NetworkBlockerLinuxTest.kt`
- `src/test/kotlin/com/focusflow/enforcement/NuclearModeLinuxTest.kt`
- `src/test/kotlin/com/focusflow/enforcement/WatchdogInstallerTest.kt`
- `src/test/kotlin/com/focusflow/enforcement/WinApiBindingsLinuxTest.kt`

## File-by-file comparison

`same` means byte-identical. `diff` means the file was reviewed as a merge
candidate rather than replaced. `archive-only` means it exists only in the ZIP.

| Archive file | Status |
|---|---|
| `App.kt` | diff |
| `Main.kt` | diff |
| `data/Database.kt` | diff |
| `data/models/Models.kt` | diff |
| `enforcement/AppBlocker.kt` | diff |
| `enforcement/AppIconExtractor.kt` | diff |
| `enforcement/BlockPresets.kt` | diff |
| `enforcement/EnforcementLog.kt` | same |
| `enforcement/FloatingBlockOverlay.kt` | diff |
| `enforcement/GlobalKeyboardHook.kt` | diff |
| `enforcement/InstallVariant.kt` | archive-only |
| `enforcement/InstalledAppsScanner.kt` | diff |
| `enforcement/KillSwitchService.kt` | same |
| `enforcement/NetworkBlocker.kt` | diff |
| `enforcement/NuclearMode.kt` | diff |
| `enforcement/ProcessMonitor.kt` | diff |
| `enforcement/RegistryLockdown.kt` | same |
| `enforcement/VpnBlocker.kt` | diff |
| `enforcement/WatchdogInstaller.kt` | diff |
| `enforcement/WinApiBindings.kt` | diff |
| `enforcement/WinEventHook.kt` | diff |
| `enforcement/WindowsStartupManager.kt` | diff |
| `i18n/AppLanguage.kt` | same |
| `i18n/AppStrings.kt` | diff |
| `i18n/LocalizationManager.kt` | same |
| `i18n/Translations.kt` | diff |
| `services/AutoBackupService.kt` | same |
| `services/BackupService.kt` | same |
| `services/BlockScheduleService.kt` | same |
| `services/BreakEnforcer.kt` | same |
| `services/CrashReporter.kt` | diff |
| `services/DailyAllowanceTracker.kt` | same |
| `services/DiscordWebhookClient.kt` | archive-only |
| `services/FocusInsightsService.kt` | same |
| `services/FocusLauncherService.kt` | diff |
| `services/FocusSessionService.kt` | diff |
| `services/GlobalPin.kt` | same |
| `services/HostsBlocker.kt` | diff |
| `services/KeywordMatchLogger.kt` | same |
| `services/NotificationService.kt` | same |
| `services/NuclearPin.kt` | same |
| `services/PinPolicy.kt` | diff |
| `services/RecurringTaskService.kt` | same |
| `services/ReviewPromptService.kt` | diff |
| `services/SessionPin.kt` | diff |
| `services/SoundAversion.kt` | diff |
| `services/StandaloneBlockService.kt` | diff |
| `services/SystemTrayManager.kt` | diff |
| `services/TaskAlarmService.kt` | same |
| `services/TemptationLogger.kt` | same |
| `services/UninstallProtectionService.kt` | archive-only |
| `services/UninstallWizard.kt` | archive-only |
| `services/UninstallWizardFlag.kt` | archive-only |
| `services/WeeklyReportService.kt` | same |
| `services/WindowsUninstallRegistration.kt` | archive-only |
| `ui/AppLocals.kt` | same |
| `ui/components/AndroidPromoDialog.kt` | same |
| `ui/components/AppLogo.kt` | same |
| `ui/components/BlockOverlay.kt` | same |
| `ui/components/BlockScheduleEditorDialog.kt` | diff |
| `ui/components/EdgeExtensionPromoDialog.kt` | diff |
| `ui/components/EmptyStateCard.kt` | same |
| `ui/components/GlobalPinSetupDialog.kt` | diff |
| `ui/components/InfoTooltip.kt` | diff |
| `ui/components/NuclearPinDialogs.kt` | same |
| `ui/components/OnboardingScreen.kt` | diff |
| `ui/components/OsBanner.kt` | diff |
| `ui/components/PinGateDialog.kt` | same |
| `ui/components/PostPinRecommendationsDialog.kt` | diff |
| `ui/components/ReviewPromptDialog.kt` | diff |
| `ui/components/ScrollUtils.kt` | same |
| `ui/components/ShareDialog.kt` | diff |
| `ui/components/ShortcutTooltip.kt` | same |
| `ui/components/SideNav.kt` | diff |
| `ui/components/TaskCard.kt` | same |
| `ui/components/TelemetryConsentDialog.kt` | diff |
| `ui/launcher/LauncherContent.kt` | diff |
| `ui/launcher/LauncherWindow.kt` | diff |
| `ui/screens/ActiveScreen.kt` | same |
| `ui/screens/AppBlockerScreen.kt` | diff |
| `ui/screens/BlockDefenseScreen.kt` | diff |
| `ui/screens/ChangelogScreen.kt` | diff |
| `ui/screens/ContactScreen.kt` | diff |
| `ui/screens/DailyNotesScreen.kt` | same |
| `ui/screens/DashboardScreen.kt` | diff |
| `ui/screens/FocusLauncherScreen.kt` | diff |
| `ui/screens/FocusScreen.kt` | same |
| `ui/screens/HabitsScreen.kt` | same |
| `ui/screens/HowToUseScreen.kt` | diff |
| `ui/screens/KeywordBlockerScreen.kt` | diff |
| `ui/screens/NuclearModeScreen.kt` | diff |
| `ui/screens/ProfileScreen.kt` | same |
| `ui/screens/ReportsScreen.kt` | same |
| `ui/screens/SettingsScreen.kt` | diff |
| `ui/screens/StatsScreen.kt` | same |
| `ui/screens/TasksScreen.kt` | diff |
| `ui/screens/VpnNetworkScreen.kt` | diff |
| `ui/screens/WindowsSetupScreen.kt` | same |
| `ui/theme/Theme.kt` | same |

## Verification notes

- The archive was extracted to a temporary directory only; it was never
  extracted over the project.
- No archive file was directly replaced in the current source tree.
- No secrets, webhook URLs, or external credentials were copied.
- Runtime X11, native Wayland, desktop-environment, Windows, and privileged
  firewall tests still require their respective environments.

## Follow-up candidates

- Decide whether `2.0.1` is a release version or only an archive snapshot before
  changing Gradle/package/changelog metadata.
- If Windows direct-installer uninstall protection is still required, handle it
  as a separate Windows-scoped feature with explicit tests and no impact on the
  Linux build path.
- Continue the remaining Linux CI/manual verification items in
  `focusflow-linux-tracker.md`.