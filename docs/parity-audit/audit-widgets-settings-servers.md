# Parity re-audit - settings, servers, forums, and mobile reports

Date: **2026-08-04**

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Reference: Discord Android 126.21 Stable under
`C:\Users\manti\StudioProjects\discord-jadx`, supplemented by the Vineflower
DEX decompile under `C:\Users\manti\Desktop\projeler\discorddecompile\out`.

Scope: `widgets/settings/**`, `widgets/servers/**`, `widgets/forums/**`, and
`widgets/mobile_reports/**` only.

## Result

The 2026-07-26 inventory of 23 target files and 177 missing classes is stale.
The current target has 227 scoped files and represents **199 of 200** reference
classes (99.5% physical class coverage). The report remains open because one
reference class is absent and several shipping screens retain explicit store,
provider, and runtime gaps.

| Package | Reference classes | Current target files | Reference classes represented | Missing |
| --- | ---: | ---: | ---: | ---: |
| `widgets/settings` | 69 | 90 | 69 | 0 |
| `widgets/servers` | 108 | 114 | 108 | 0 |
| `widgets/forums` | 16 | 17 | 16 | 0 |
| `widgets/mobile_reports` | 7 | 6 | 6 | 1 |
| **Total** | **200** | **227** | **199** | **1** |

Counts are top-level, non-synthetic reference classes. Target helpers,
additional sessions/authenticator screens, and split implementations do not
inflate the represented-class count.

## Missing Inventory

`widgets/mobile_reports/ReportsFeatureFlag` is the sole absent reference class.
The report flow itself is present through `MobileReportArgs`,
`MobileReportsBottomButton`, `MobileReportsBreadcrumbs`, `MobileReportsViewModel`,
`ReportsMenuNode`, and `WidgetMobileReports`.

## Resolved Findings

- Settings now has every reference screen/ViewModel in the audited inventory,
  including account, MFA, billing, connections, developer, premium/gifting,
  profile, mute, notification, privacy, appearance, language, and voice.
- The settings root binds real routes and profile-header actions
  (`WidgetSettings.kt:59-102`); the old blocked-user and inert-notification
  findings no longer describe current code.
- Server notifications now has a ViewModel, override adapter/selector, mute
  sheet, highlights handling, and mutation flow. The prior null-hook placeholder
  was replaced.
- All 108 server reference classes are represented, including server shell and
  pages, audit log, community, monetization, guild role subscriptions, boosts,
  member verification, role members, invites, and members.
- All 16 forum classes are represented; `ForumUtils` and the browser/ViewModel
  are no longer two-member or missing shells.
- `MobileReportsViewModel` is now a substantive implementation rather than the
  old nine-line `SubmitState` shell.

## Open Findings

### Blockers

1. The settings root still carries non-reference row suppression.
   `WidgetSettings.kt:39-51` documents `hideUnavailableRows` and states that
   routes without destinations are hidden. This prevents a full route-surface
   parity claim even though physical class coverage is nearly complete.
2. Notification and voice settings remain dependent on unpublished stores.
   `WidgetSettings.kt:47-50` says notification toggles stay disabled until
   `StoreNotifications` is published; `WidgetSettings.kt:100-102` hides OpenSL
   ES and microphone-test behavior because no media engine is published.
3. Contact-sync privacy mutation is incomplete. The reference immediately
   uploads contacts and clears dismissal state; target omits both because
   `StoreContactSync` is not exposed (`WidgetSettingsPrivacy.kt:409-424`).

### Significant Residual Risk

- `ReportsFeatureFlag` is absent, so reference experiment-gating identity and
  behavior are not represented even though reporting UI exists.
- Settings refreshes subscriptions on every resume because the reference tab
  selection listener is absent (`WidgetSettings.kt:131-135`). This is a known
  lifecycle divergence.
- `NotificationsOverridesAdapter.kt:68` retains a
  `NotImplementedError` fallback for unknown view types. Current known types may
  be exhaustive, but the crash branch remains a robustness risk.
- Provider-owned credentials, project OAuth/Firebase products, session evidence,
  nullable-clear paths, and broad device validation remain open per the current
  project plan; physical widgets cannot close those integration gaps.
- No complete settings/server/forum/report matrix covers process restoration,
  rotation, RTL, large fonts, accessibility services, malformed REST responses,
  or live backend behavior.

## Decompiler Verification

The old report's broken JADX regions were not guessed. Vineflower recovers
`MobileReportsBreadcrumbs.setup`, so the breadcrumb algorithm is no longer
classified from an opaque JADX bytecode dump alone. Broken visibility ternaries
in `NotificationHighlightsSettingsView` and `WidgetSettings` still require DEX
instruction or runtime confirmation before exact branch parity can be claimed.

## Test Evidence And Gaps

Focused unit coverage now includes settings roots/contracts, notifications,
account/MFA, profile, billing, connections, server sections and ViewModels,
forums, and mobile reports. Representative files:

- `app/src/test/java/com/discord/widgets/settings/RemainingSettingsContractsTest.kt`
- `app/src/test/java/com/discord/widgets/settings/NotificationSettingsModalContractTest.kt`
- `app/src/test/java/com/discord/widgets/servers/ServerSettingsSectionsTest.kt`
- `app/src/test/java/com/discord/widgets/forums/ForumExactPathContractsTest.kt`
- `app/src/test/java/com/discord/widgets/mobile_reports/MobileReportsContractsTest.kt`

No checks were run for this documentation-only re-audit. Unit contracts do not
prove the remaining store/provider wiring or the device/live-backend matrix.

## Status

**OPEN - 99.5% physical class coverage, behavioral/integration parity not
verified.** Do not delete this report until `ReportsFeatureFlag` is accounted
for, the documented store and route gaps are closed, broken-decompiler branches
are verified, and the relevant device/backend matrix passes.

## Resumable Task List

- `app/src/main/java/com/discord/widgets/settings/WidgetSettings.kt` | reference `app/src/main/java/com/discord/widgets/settings/WidgetSettings.java` | remove residual row suppression after every route/store is live and restore tab-selection refresh semantics.
- `app/src/main/java/com/discord/widgets/settings/WidgetSettingsPrivacy.kt` | reference sibling `.java` | wire immediate contact upload and dismissal-state clearing through `StoreContactSync`.
- `app/src/main/java/com/discord/widgets/mobile_reports/ReportsFeatureFlag.kt` | reference sibling `.java` plus Vineflower equivalent | reconstruct the sole missing scoped class and its experiment gate.
- `app/src/main/java/com/discord/widgets/servers/NotificationsOverridesAdapter.kt` | reference sibling `.java` | prove the view-type set exhaustive or replace the reachable crash fallback.
