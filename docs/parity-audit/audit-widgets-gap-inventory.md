# Current Widget Gap Inventory - Discord Android 126.21

Date: **2026-08-04**

Reference: `C:\Users\manti\StudioProjects\discord-jadx`

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`

Supplemental reference: `C:\Users\manti\Desktop\projeler\discorddecompile\out`

This supersedes the 2026-07-26 inventory. Counts are current filesystem counts, not carried from
the original campaign baseline.

## Counting method

- Reference count: `*.java` under `com/discord/widgets/<package>/**` whose filename contains no
  `$` (non-synthetic top-level reference classes).
- Target count: current `*.kt` files under the corresponding target package.
- Exact source-path count: replace each reference `.java` suffix with `.kt` and test the same
  relative path in the target.
- File count is not a behavioral-parity percentage. The target consolidates some reference JVM
  facades and adds support files that have no one-to-one reference filename.

## Headline

| Metric | 2026-07-26 | 2026-08-04 |
| --- | ---: | ---: |
| Reference widget packages | 45 | 45 |
| Reference packages with no target directory | 24 | **0** |
| Reference classes inside zero-coverage packages | 161 | **0** |
| Reference non-synthetic widget classes | 1,155 | 1,155 |
| Target widget Kotlin files | 132 | **1,247** |
| Exact reference source paths present | not measured in baseline | **1,132/1,155 (98.0%)** |
| Unmatched reference filenames | 1,023 by old file-count comparison | **23** |

The target now has all 45 reference package directories plus two target-only package directories,
`applicationdirectory` and `mfa`. The old "24 packages at 0%" section is fully resolved.

The PLAN's 1,150/1,155 physical-path checkpoint predates the 2026-08-03 captcha redesign and is
stale for current source. Exact current path coverage is 1,132/1,155. This does **not** mean 23
features disappeared: the unmatched set includes consolidated facades, intentionally removed
captcha screens, experiment gates and approved Samsung exclusions, classified below.

## Current package table

| Package | Reference classes | Target `.kt` files |
| --- | ---: | ---: |
| accessibility | 1 | 1 |
| announcements | 3 | 3 |
| auth | 21 | 22 |
| botuikit | 21 | 21 |
| bugreports | 6 | 6 |
| captcha | 4 | 2 |
| changelog | 3 | 3 |
| channels | 114 | 118 |
| chat | 277 | 303 |
| client | 1 | 1 |
| contact_sync | 9 | 12 |
| debugging | 2 | 2 |
| directories | 16 | 18 |
| donut | 1 | 1 |
| emoji | 2 | 2 |
| feedback | 2 | 2 |
| forums | 16 | 17 |
| friends | 13 | 13 |
| guild_automod | 3 | 3 |
| guild_delete_feedback | 3 | 3 |
| guild_role_subscriptions | 42 | 43 |
| guildcommunicationdisabled | 7 | 7 |
| guilds | 75 | 77 |
| guildscheduledevent | 47 | 49 |
| home | 11 | 10 |
| hubs | 54 | 58 |
| media | 3 | 3 |
| mobile_reports | 7 | 6 |
| notice | 5 | 5 |
| nux | 14 | 15 |
| phone | 4 | 5 |
| playstation | 3 | 3 |
| quickmentions | 1 | 1 |
| roles | 2 | 2 |
| search | 4 | 9 |
| servers | 108 | 114 |
| settings | 69 | 90 |
| share | 1 | 2 |
| stage | 31 | 32 |
| status | 8 | 8 |
| stickers | 9 | 14 |
| tabs | 6 | 6 |
| tos | 5 | 5 |
| user | 55 | 58 |
| voice | 66 | 67 |

## The 23 unmatched reference filenames

### Consolidated/folded behavior - 6

| Reference filename | Current target representation |
| --- | --- |
| `captcha/WidgetCaptchaBottomSheetKt` | Helper behavior is folded into `WidgetCaptchaBottomSheet.kt`. |
| `contact_sync/WidgetContactSyncViewModelKt` | Toolbar/config helpers are folded into `WidgetContactSyncViewModel.kt` / `WidgetContactSync.kt`. |
| `home/PanelLayout` | Exact `PanelLayout` interface is declared in `HomePanelsLayout.kt:7`. |
| `playstation/PlaystationExperimentUtilsKt` | Top-level `canSeePlaystationAccountIntegration` is in `PlaystationExperimentUtils.kt:6`. |
| `servers/community/WidgetServerSettingsEnableCommunityViewModelKt` | Kotlin facade behavior is consolidated with its ViewModel owner. |
| `friends/WidgetFriendsFindNearbyViewModel` | State/model provider is folded into `WidgetFriendsFindNearby.kt:58-69,303+`; PLAN records removal of the separate owner after captcha activity removal. |

These are source-path mismatches, not automatically missing behavior. Only `PanelLayout` is an exact
same-name JVM type; the Kotlin facade names should not be claimed as emitted unless an explicit
`@file:JvmName` is added.

### Approved captcha redesign - 5

- `captcha/WidgetCaptcha`
- `captcha/WidgetCaptchaKt`
- `auth/WidgetAuthCaptcha`
- `guilds/join/WidgetGuildJoinCaptchaBottomSheet`
- `guilds/join/WidgetGuildJoinCaptchaBottomSheetKt`

`PLAN.md:1198` records their deliberate deletion. Challenges now route globally through
`CaptchaInterceptor` to `WidgetCaptchaBottomSheet` and use `X-Captcha-*` headers. This is functional
replacement evidence, not exact 126.21 structural parity.

### Missing experiment/feature gates - 9

| Reference class | Reference gate recovered by Vineflower | Current effect |
| --- | --- | --- |
| `channels/invite/GroupInviteFriendsSheetFeatureFlag` | `2020-12_invite_to_gdm`, bucket 1 | Group-invite sheet is launched unconditionally. |
| `chat/list/CreateThreadsFeatureFlag` | `2020-09_threads`, `2021-06_threads_rollout`, guild features | Thread creation is not gated by the reference rollout owner. |
| `chat/list/NewThreadsPermissionsFeatureFlag` | `2021-08_threads_permissions` or guild feature | New permission behavior can differ by guild bucket. |
| `chat/list/ViewThreadsFeatureFlag` | `2021-02_view_threads`, bucket 1 | Thread visibility is not reference-gated. |
| `chat/list/TextInVoiceFeatureFlag` | `2022-03_text_in_voice` or guild feature | Text-in-voice availability can differ. |
| `chat/input/emoji/EmojiAutocompletePremiumUpsellFeatureFlag` | `2021-03_nitro_emoji_autocomplete_upsell_android` | Upsell availability is not reference-gated. |
| `guilds/invite/GuildInviteShareSheetFeatureFlag` | reference experiment owner absent | Invite-share flow is not controlled by the 126.21 gate. |
| `guildcommunicationdisabled/start/GuildCommunicationDisabledGuildsFeatureFlag` | reference timeout rollout owner absent | Timeout UI availability is not controlled by the 126.21 gate. |
| `mobile_reports/ReportsFeatureFlag` | reference reports rollout owner absent | New reports behavior is selected without the reference class. |

These are genuine structural/behavioral gaps unless runtime evidence proves the backend/guild
features made the 126.21 gates permanently redundant.

### Missing reference behavior - 1

- `auth/SmsAuthCodeBroadcastReceiver`: manual SMS code submission exists, but the reference SMS
  auto-fill receiver/listener lifecycle is absent.

### Explicit Samsung exclusions - 2

- `auth/WidgetOauth2AuthorizeSamsung`
- `notice/WidgetNoticeNuxSamsungLink`

Samsung OAuth/linking is explicitly out of scope (`PLAN.md:893,962`) because it requires
project-owned Samsung credentials and integration. These are not release blockers, but they remain
reference exceptions.

## Historical zero-package findings now resolved

Every package previously listed as entirely absent now has concrete target source:

`accessibility`, `announcements`, `bugreports`, `changelog`, `client`, `contact_sync`, `debugging`,
`directories`, `donut`, `emoji`, `feedback`, `guild_automod`, `guild_delete_feedback`,
`guildcommunicationdisabled`, `hubs`, `media`, `notice`, `nux`, `phone`, `playstation`,
`quickmentions`, `search`, `stickers`, and `tos`.

Examples of former hard stops that are now closed:

- `WidgetTos` exists; required-action routing no longer instantiates a missing class.
- Directory channels have directory screens/adapters/ViewModels and no longer open ordinary chat.
- Search has input, suggestions and results owners.
- Media has fullscreen media and QR-scanner screens.
- Notice widgets and `StoreNotices` now provide the shared notice substrate.
- Phone has the country-code picker; contact sync has permission/upsell and list flows.
- TOS reporting, AutoMod reporting, timeout UI, hubs, NUX, sticker sheets and feedback sheets all
  have first-party owners.

## Manifest and route re-audit

The old report's manifest inventory is stale. Current `AndroidManifest.xml` registers the dedicated
call/incoming-call activities, overlay/foreground voice services, notification/download handlers,
`DiscordConnectService`, Direct Share shortcuts and Google App Actions. Relevant current evidence
includes `AndroidManifest.xml:240-353`.

The old live dead-ends are resolved:

| 2026-07-26 dead-end | Current status |
| --- | --- |
| TOS required-action crash | Resolved: `widgets/tos/WidgetTos.kt` exists and is routed. |
| Sidebar Search no-op | Resolved: channel navigation launches the reconstructed search surface. |
| Directory channel opened blank chat | Resolved: `widgets/directories/**` owns directory navigation. |
| `/template`, `discord.new`, `/discovery`, `/query` consumed then dropped | Resolved structurally by NUX/directory/search routes; full route/device matrix remains open. |
| Blocked-users row hidden | Resolved in current settings/user campaign. |
| Shortcuts/App Actions resources unhooked | Resolved at `AndroidManifest.xml:240-243,353+`. |

## Current cross-package behavioral blockers

These are not zero-directory findings, but prevent deleting this report:

1. **Nearby friends has no transport.** `NearbyManager.Transport.factory` defaults to `null`
   (`NearbyManager.kt:42-69`), and `buildClientAndPublish` reports permission denied when no
   transport is installed (`:99-108`). The screen exists but cannot discover peers.
2. **Chat overlay sticker suggestions remain unwired.** See `audit-widgets-chat.md`.
3. **Channel membership screening and directory unread state remain unwired.** See
   `audit-widgets-channels.md`.
4. **SMS code auto-fill is missing.** See `audit-widgets-shell-auth.md`.
5. **Nine experiment/feature gates are absent.** Their downstream UI may be over- or under-exposed.
6. **Provider/runtime coverage remains incomplete.** Project-owned FCM/dynamic-link/OAuth products
   and broad live/device validation remain open in `PLAN.md`.

## JADX/Vineflower/DEX integrity

- Every unmatched feature-flag predicate listed above was checked in Vineflower where available;
  no predicate was guessed from an alphabetical enum or an obfuscated caller.
- `WidgetOauth2AuthorizeSamsung` is fully readable in Vineflower and confirms the external Samsung
  activity/client dependency.
- Captcha deletions are backed by the 2026-08-03 decision log, not inferred from missing files.
- No open inventory count depends on JADX successfully decompiling a method body; counts use current
  filesystem paths.

## Verification boundary

Latest project-wide evidence in `PLAN.md:942-948` is 1,678 unit tests, debug/release assembly,
debug lint, release lint-vital, Android-test compilation and 59/59 connected tests on Android 16.
This documentation re-audit ran no Gradle task, device test or live-backend action.

## Remaining blockers

1. Implement a real Nearby transport or explicitly remove the user-reachable scan.
2. Resolve the nine absent reference feature gates with runtime evidence or matching owners.
3. Restore SMS auto-fill if exact phone-auth parity is required.
4. Close the chat/channel blockers linked above.
5. Complete provider integration and the full route/device/runtime matrix.

This report is retained because current widget structure is not 100% exact and behavioral/runtime
caveats remain.
