# Parity re-audit - guild-related widget packages

Date: **2026-08-04**

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Reference: Discord Android 126.21 Stable under
`C:\Users\manti\StudioProjects\discord-jadx`, supplemented by the Vineflower
DEX decompile under `C:\Users\manti\Desktop\projeler\discorddecompile\out`.

Scope: `widgets/guilds/**`, `widgets/guildscheduledevent/**`,
`widgets/guild_role_subscriptions/**`, and `widgets/roles/**` only.

## Result

The 2026-07-26 report is structurally obsolete. The target grew from 24 files
and 22 mapped reference classes to 171 files and **163 of 166** reference
classes represented (98.2% physical class coverage). Physical presence is not
behavioral parity: this report remains because several live controls are still
inert, three exact reference classes are absent, and broad device/live-backend
validation has not been performed.

| Package | Reference classes | Current target files | Reference classes represented | Missing |
| --- | ---: | ---: | ---: | ---: |
| `widgets/guilds` | 75 | 77 | 72 | 3 |
| `widgets/guildscheduledevent` | 47 | 49 | 47 | 0 |
| `widgets/guild_role_subscriptions` | 42 | 43 | 42 | 0 |
| `widgets/roles` | 2 | 2 | 2 | 0 |
| **Total** | **166** | **171** | **163** | **3** |

Counts are exact top-level, non-synthetic reference classes. Target-only
helpers and split files do not increase the represented-class numerator.

## Missing Inventory

Only these reference classes have no target JVM/source counterpart:

| Reference class | Current effect |
| --- | --- |
| `guilds/invite/GuildInviteShareSheetFeatureFlag` | Share-sheet experiment gating is not represented as the reference type. |
| `guilds/join/WidgetGuildJoinCaptchaBottomSheet` | The reference join-specific captcha destination is absent. |
| `guilds/join/WidgetGuildJoinCaptchaBottomSheetKt` | Its helper surface is absent with the destination. |

The previous report's missing guild list, context menu, creation, invite,
join/welcome, profile, scheduled-event, role-subscription, and role-view
inventories are resolved structurally. Examples include the complete guild
list class split under `widgets/guilds/list/`, all 49 scheduled-event target
files, all 43 role-subscription target files, and both role views.

## Resolved Findings

- The guild sidebar now has the reference item model, holders, adapter,
  decoration, drag callback, fragment, and ViewModel. The former monolithic
  four-row rewrite is gone.
- Guild and folder context menus, folder settings, creation, invite sharing,
  join/welcome, leave, and guild-profile destinations now exist.
- Scheduled-event details/list/settings/location/date-time/RSVP/API/model
  surfaces now exist and have focused contract tests.
- Role-subscription setup, tier, benefits, price-picker, entry-point, model,
  and server-side companion flows now exist.
- `RoleIconView` and `RolesListView` now expose the production role/icon paths;
  the old five-method absence is stale.

## Open Findings

### Blockers

1. Invite acceptance still does not enter voice/stage or enqueue an event
   details sheet. `InviteJoinHelper.kt:166-179` explicitly stops after guild or
   channel selection even though those destinations now exist. The comments
   are stale, but the missing calls are real.
2. Scheduled-event report is a dead tap.
   `WidgetGuildScheduledEventDetailsExtrasBottomSheet.kt:118-123` dismisses the
   sheet and deliberately performs no report navigation. `WidgetMobileReports`
   now exists, so the old justification is stale.
3. Role-subscription benefit channel and emoji selectors are disabled.
   `WidgetGuildRoleSubscriptionTierBenefit.kt:82-93` disables both controls and
   documents absent picker navigation. Existing values render, but users cannot
   choose either required value.

### Significant Residual Risk

- The exact join-captcha sheet types remain absent. A generic captcha path may
  cover some failures, but exact argument/result and lifecycle parity is not
  established.
- Role-subscription image upload documents a missing reference image helper in
  `RoleSubscriptionImageUpload.kt:13`; image processing/preview parity needs a
  device-backed comparison.
- Scheduled-event mention fallback remains intentionally reduced because the
  reference `Tags` scan is not reconstructed
  (`GuildScheduledEventDescriptionParser.kt:93-95`).
- No end-to-end test proves create/join/invite/event/role-subscription flows
  against the backend, restored process state, rotation, RTL, or large fonts.

## Decompiler Verification

The old report treated `WidgetGuildsListViewModel.handleStoreState` as
unverifiable because JADX skipped it. Vineflower now recovers the class and the
list/drag flow; it was used as secondary DEX evidence rather than guessing from
the broken JADX body. The scheduled-event cancel visibility remains explicitly
inferred in target code because JADX emitted a degenerate ternary
(`WidgetGuildScheduledEventDetailsExtrasBottomSheet.kt:112-115`). This report
does not claim that branch verified.

## Test Evidence And Gaps

Focused unit coverage exists for guild list assembly, guild independent/profile
ViewModels, join arguments/flow, scheduled-event flow and pickers, and
role-subscription contracts/ViewModels. Representative files include:

- `app/src/test/java/com/discord/widgets/guilds/list/GuildListItemBuilderTest.kt`
- `app/src/test/java/com/discord/widgets/guilds/join/GuildJoinFlowTest.kt`
- `app/src/test/java/com/discord/widgets/guildscheduledevent/GuildScheduledEventFlowTest.kt`
- `app/src/test/java/com/discord/widgets/guild_role_subscriptions/GuildRoleSubscriptionExactPathContractsTest.kt`

No checks were run for this documentation-only re-audit. Existing tests do not
close the three blockers above or the device/live-backend matrix.

## Status

**OPEN - 98.2% physical class coverage, behavioral parity unverified.** Do not
delete this report until the three missing classes are accounted for, inert
navigation/pickers are fixed, broken-decompiler branches are verified from DEX
or runtime evidence, and the relevant device/backend matrix passes.

## Resumable Task List

- `app/src/main/java/com/discord/widgets/guilds/invite/InviteJoinHelper.kt` | reference `app/src/main/java/com/discord/widgets/guilds/invite/InviteJoinHelper.java` | route vocal invites and scheduled-event invites to their now-present destinations.
- `app/src/main/java/com/discord/widgets/guildscheduledevent/WidgetGuildScheduledEventDetailsExtrasBottomSheet.kt` | reference sibling `.java` | wire the report action and verify the cancel visibility from DEX/runtime evidence.
- `app/src/main/java/com/discord/widgets/guild_role_subscriptions/tier/create/benefits/WidgetGuildRoleSubscriptionTierBenefit.kt` | reference sibling `.java` | wire channel and emoji pickers.
- `app/src/main/java/com/discord/widgets/guilds/join/WidgetGuildJoinCaptchaBottomSheet.kt` | reference sibling `.java` plus Vineflower equivalent | reconstruct the missing join captcha destination/helper without guessing.
