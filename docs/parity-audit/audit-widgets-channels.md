# Parity Re-audit - `com.discord.widgets.channels`

Date: **2026-08-04**

Scope:

- Target: `app/src/main/java/com/discord/widgets/channels/**`
- Reference: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\widgets\channels\**`
- Supplemental reference: `C:\Users\manti\Desktop\projeler\discorddecompile\out\com\discord\widgets\channels\**`

This replaces the 2026-07-26 gap snapshot. It verifies current structure and source-visible
behavior; it does not certify live guild/channel behavior on a device.

## Current counts

| Metric | 2026-07-26 | 2026-08-04 |
| --- | ---: | ---: |
| Reference non-synthetic classes | 114 | 114 |
| Target Kotlin files | 15 | **118** |
| Exact reference source paths present | 31 mapped | **113/114** |
| Explicit parity TODOs in package | not measured | **1** |

The only reference source path without a same-name target file is
`channels/invite/GroupInviteFriendsSheetFeatureFlag`. The target has five target-only support paths,
for a net file-count difference of four.

## Structural closure

Every large cluster reported missing on 2026-07-26 is now represented:

| Historical cluster | Current evidence |
| --- | --- |
| Channel model and all list row types | `list/WidgetChannelListModel.kt`, `WidgetChannelsListAdapter.kt` and `list/items/**`; voice users, stage events, directories, scheduled events, role subscriptions, join requests, headers, invites and nav spacing all have owners. |
| Channel/thread long-press sheets | `WidgetChannelsListItemChannelActions`, `WidgetChannelsListItemThreadActions` and `WidgetThreadArchiveActions` exist and are called from channel/chat/forum/thread surfaces. |
| Unread/mention jump bars | `list/WidgetChannelListUnreads.kt`; `WidgetChannelsList.kt:148-150` inflates the recovered stub on demand. |
| Member-list model, adapter and sticky headers | `memberlist/**` contains the generators, ViewModel, service and all adapter holders; `WidgetChannelMembersList.kt:63-90` installs `StickyHeaderItemDecoration` and the real ViewModel fallback. |
| Channel permissions | All 21 reference owners are represented under `permissions/**`, including overview/advanced/edit/add owner/stage moderator flows. |
| Channel settings | Text, thread, group-DM and notification settings plus ViewModels are present under `settings/**`. |
| Thread browser | Active/archived tabs, adapter, filter sheet, root ViewModel, row view and draft/archive sheet are present. |
| Channel picker/create/onboarding/group invite | Root and `invite/**` owners now exist and route to real REST/store-backed flows. |
| Topic/sidebar ViewModels | `WidgetChannelTopicViewModel` and `WidgetChannelSidebarActionsViewModel` now drive the views; the old raw-topic/static-null implementations are gone. |

## Resolved 2026-07-26 defects

- Channel rows are no longer the seven-variant `ChannelListRows` substitute. The model carries
  mute, permission, lock, thread spine, scheduled-event, voice-user, streaming and selection state.
- `WidgetChannelsListAdapter` now owns the full reference view-type switch and long-press behavior.
- Voice channels render connected users, user limits, video/stream state and stage information.
- Category add-channel controls and collapse behavior are permission/state driven.
- Directory rows route to directory UI rather than ordinary chat.
- Member lists expose loading/empty/error events, add-member and join/leave-thread behavior, sticky
  headers and visible-range subscription updates (`WidgetChannelMembersList.kt:93-159`).
- Notification settings have a live model, forum variants and mute-duration handling.
- Channel topics render state variants, entities and expansion/context actions through their
  ViewModel rather than hiding the recovered controls.
- The old process-global callbacks in the member list are now optional test seams; production falls
  back to `defaultStateSource()` and concrete navigation/mutation methods
  (`WidgetChannelMembersList.kt:63-90,135-159`).

## Open findings

### 1. Membership-screening card button is a production no-op - BLOCKER

`WidgetChannelListModel` emits `ChannelListItemGuildJoinRequest`, and `WidgetChannelsList.kt:222`
routes its button to `ChannelListNavigation.showMemberVerification`. That callback is explicitly a
TODO and defaults to an empty lambda (`ChannelListNavigation.kt:147-149`).

The reference calls `MemberVerificationUtils.maybeShowVerificationGate` with source
`"Channel Notice"` (Vineflower `WidgetChannelsListAdapter.kt:1745-1774`). A pending member can see
the screening card but cannot complete screening in the target.

### 2. Hub-directory unread counts are hardcoded empty - MAJOR

`ChannelListSources.directoryUnreadCounts` always returns `Observable.just(emptyMap())`
(`ChannelListSources.kt:105-108`), even though `StoreDirectories` now exists and publishes both
directory entries and directory scheduled events (`StoreDirectories.kt:162-171`). The channel model
consumes this empty map at `WidgetChannelListModel.kt:447-475,899`, so directory rows never show
their unread/new-server counts.

The reference combines `StoreDirectories.observeDirectories()` and
`observeDirectoryGuildScheduledEvents()` in the channel-list model (Vineflower
`WidgetChannelListModel.kt:552,585-591,667-677`). The target comment saying the owning source is
absent is stale.

### 3. Group-DM invite-sheet experiment gate is missing - MAJOR divergence

The exact-path audit finds no `GroupInviteFriendsSheetFeatureFlag`. Vineflower recovers its entire
predicate: user experiment `2020-12_invite_to_gdm` must be bucket 1
(`GroupInviteFriendsSheetFeatureFlag.kt:20-27`). The target launches `GroupInviteFriendsSheet`
unconditionally from the channel list, friends list, home header and voice sheet. Accounts outside
the reference experiment receive a different flow.

### 4. Runtime/device verification remains incomplete - BLOCKER to declaring parity

The current PLAN keeps guilds/channels `IN PROGRESS` because reorder, permissions, panels and
persistence still require broad device validation (`PLAN.md:964`). This re-audit found no evidence
covering a large guild member list, category/channel drag persistence, private-thread membership,
stage/voice participants, directory navigation, timed mutes or permission edits against a live
backend across recreation and reconnect.

## JADX/Vineflower integrity

- The original report said this package's JADX output was generally readable; no finding depended
  on a skipped central method.
- Vineflower was checked for the current unmatched/ambiguous regions. It recovers the exact
  membership-screening call (`WidgetChannelsListAdapter.kt:1745-1774`) and the complete
  `GroupInviteFriendsSheetFeatureFlag` predicate (`:20-27`).
- Vineflower also confirms the reference directory observables in `WidgetChannelListModel`; the
  target empty map is not an inferred mismatch.
- No open finding in this report rests only on obfuscated binding-field names.

## Verification boundary

Latest project-wide evidence in `PLAN.md:942-948` is 1,678 unit tests, debug/release assembly,
debug lint, release lint-vital, Android-test compilation and 59/59 connected tests on Android 16.
This documentation re-audit ran no Gradle task, device test or live-backend action.

## Remaining blockers

1. Route the membership-screening card to the real verification flow.
2. Feed directory unread/event state from `StoreDirectories` into `WidgetChannelListModel`.
3. Restore or deliberately retire the group-invite experiment gate with runtime evidence.
4. Complete live/device validation for reorder, permissions, panels, threads, member lists and persistence.
