# Parity Audit — `widgets/channels/**` (PLAN Phase 6)

- **TARGET**: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\widgets\channels\**` — 15 Kotlin files, **1,776 lines**
- **REFERENCE**: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\widgets\channels\**` — 114 non-synthetic Java classes, **34,004 lines**
- Volume ratio: **5.2%**. Class coverage: **31 / 114 mapped (27%)**, of which only **8 are behaviourally complete**; **83 classes have NO target counterpart** (verified by repo-wide grep for each class name across all `.kt`/`.java` in the target — every one returned `NONE`).

---

## 1. Reference class inventory by subpackage

| Subpackage | Count |
|---|---|
| `channels/` (root) | 22 |
| `channels/invite/` | 4 |
| `channels/list/` | 8 |
| `channels/list/items/` | 23 |
| `channels/memberlist/` | 10 |
| `channels/memberlist/adapter/` | 9 |
| `channels/permissions/` | 21 |
| `channels/settings/` | 7 |
| `channels/threads/` | 1 |
| `channels/threads/browser/` | 9 |
| **TOTAL** | **114** |

### root (22)
ChannelOnboardingManager, ChannelPickerAdapterItem, ChannelPickerViewHolder, SimpleMembersAdapter, SimpleRolesAdapter, TextInVoiceOnboardingManager, TextInVoiceOnboardingManagerKt, UserAkaView, WidgetChannelOnboarding, WidgetChannelPickerBottomSheet, WidgetChannelPickerBottomSheetViewModel, WidgetChannelSelector, WidgetChannelSidebarActions, WidgetChannelSidebarActionsViewModel, WidgetChannelTopic, WidgetChannelTopicViewModel, WidgetChannelTopicViewModelKt, WidgetCreateChannel, WidgetGroupInviteFriends, WidgetGroupInviteFriendsAdapter, WidgetTextInVoiceOnboarding, WidgetchannelPickerAdapter

### invite (4)
GroupInviteFriendsSheet, GroupInviteFriendsSheetAdapter, GroupInviteFriendsSheetFeatureFlag, GroupInviteFriendsSheetViewModel

### list (8)
WidgetChannelListModel, WidgetChannelListUnreads, WidgetChannelsList, WidgetChannelsListAdapter, WidgetChannelsListItemChannelActions, WidgetChannelsListItemThreadActions, WidgetCollapsedUsersListAdapter, WidgetThreadArchiveActions

### list/items (23)
ChannelListBottomNavSpaceItem, ChannelListItem, ChannelListItemActiveEvent, ChannelListItemActiveEventData, ChannelListItemAddServer, ChannelListItemCategory, ChannelListItemDirectory, ChannelListItemEventsSeparator, ChannelListItemGuildJoinRequest, ChannelListItemGuildRoleSubscriptionsOverview, ChannelListItemGuildScheduledEvents, ChannelListItemHeader, ChannelListItemInvite, ChannelListItemMfaNotice, ChannelListItemPrivate, ChannelListItemStageAudienceCount, ChannelListItemStageVoiceChannel, ChannelListItemTextChannel, ChannelListItemThread, ChannelListItemVoiceChannel, ChannelListItemVoiceUser, ChannelListVocalItem, CollapsedUser

### memberlist (10)
GuildMemberListItemGeneratorKt, GuildMemberListItems, PrivateChannelMemberListItemGeneratorKt, PrivateChannelMemberListItems, PrivateChannelMemberListService, ThreadMemberListItemGeneratorKt, ThreadMemberListItems, WidgetChannelMembersList, WidgetChannelMembersListKt, WidgetChannelMembersListViewModel

### memberlist/adapter (9)
ChannelMembersListAdapter, ChannelMembersListAdapterKt, ChannelMembersListViewHolderAdd, ChannelMembersListViewHolderHeader, ChannelMembersListViewHolderJoinLeaveThread, ChannelMembersListViewHolderLoading, ChannelMembersListViewHolderMember, ChannelMembersListViewHolderPlaceholderHeader, ChannelMembersListViewHolderRoleHeader

### permissions (21)
AddMemberAdapter, AddMemberAdapterKt, AddPermissionOwnerView, ConfirmRemovePermissionDialog, ConfirmRemovePermissionDialogViewModel, PermissionOwner, PermissionOwnerListView, WidgetChannelSettingsAddMemberFragment, WidgetChannelSettingsAddMemberFragmentViewModel, WidgetChannelSettingsAddMemberSheet, WidgetChannelSettingsEditPermissions, WidgetChannelSettingsEditPermissionsModel, WidgetChannelSettingsPermissionsAddMember, WidgetChannelSettingsPermissionsAddRole, WidgetChannelSettingsPermissionsAdvanced, WidgetChannelSettingsPermissionsOverview, WidgetChannelSettingsPermissionsOverviewViewModel, WidgetChannelSettingsPermissionsOverviewViewModelKt, WidgetCreateChannelAddMember, WidgetStageChannelModeratorPermissions, WidgetStageChannelModeratorPermissionsViewModel

### settings (7)
ChannelGroupDMSettingsViewModel, ChannelSettings, WidgetChannelGroupDMSettings, WidgetChannelNotificationSettings, WidgetTextChannelSettings, WidgetThreadSettings, WidgetThreadSettingsViewModel

### threads (1) + threads/browser (9)
WidgetThreadDraftArchiveSheet | ThreadBrowserThreadView, WidgetThreadBrowser, WidgetThreadBrowserActive, WidgetThreadBrowserActiveViewModel, WidgetThreadBrowserAdapter, WidgetThreadBrowserArchived, WidgetThreadBrowserArchivedViewModel, WidgetThreadBrowserFilterSheet, WidgetThreadBrowserViewModel

---

## 2. Per-file gap analysis (target → reference)

### 2.1 `list/ChannelListRows.kt` (118 lines) — **BLOCKER**
Ad-hoc replacement for `WidgetChannelListModel` (879 lines) + 23 `list/items/*` classes.

- **BLOCKER** — Only 7 row types (`Category`, `Text`, `Voice`, `Stage`, `Directory`, `Thread`, `Private`, `ChannelListRows.kt:16-29`). Reference declares **21 concrete types** in `ChannelListItem.java:11-30`. See §4 for the full missing-type list.
- **BLOCKER** — No `ChannelListItemVoiceUser`: voice-channel participants (voice state, mute/deaf/stream icons, video, `CollapsedUser` avatars) are never emitted. `CollapsedUser.kt` exists but nothing consumes it — reference consumer is `WidgetCollapsedUsersListAdapter` (MISSING).
- **BLOCKER** — Model fields dropped wholesale. Ref `ChannelListItemTextChannel.java:13-23` carries `hasActiveThreads`, `isGuildRoleSubscriptionChannel`, `isGuildRoleSubscriptionLockedChannel`, `isLocked`, `muted`; target `Text` (`ChannelListRows.kt:18`) carries only `unread/mentionCount/selected`.
- **BLOCKER** — Ref `ChannelListItemVoiceChannel.java:15-30` has 16 fields (`guildMaxVideoChannelUsers`, `guildScheduledEvent`, `isAnyoneUsingVideo`, `isNsfw`, `numUsersConnected`, `permission`, `textSelected` vs `voiceSelected` split); target `Voice` (`ChannelListRows.kt:20`) has 5 and collapses text/voice selection into one boolean.
- **MAJOR** — Ref `ChannelListItemThread.java:14-21` has `isLocked`, `muted`, and a mutable `spineType: WidgetChannelListModel.ThreadSpineType` used to draw the thread spine/elbow connector. Target `Thread` (`ChannelListRows.kt:26`) has none — no spine rendering is possible.
- **MAJOR** — Ref `ChannelListItemCategory.java:12-17` has `canManageChannels` (gates the category "+" add-channel button) and `isMuted`. Target `Category` (`ChannelListRows.kt:16`) has neither; `WidgetChannelsList.kt:196` hard-hides the add button.
- **MAJOR** — Ref `ChannelListItemPrivate.java:15-21` has `isApplicationStreaming` and `muted`. Target `Private` (`ChannelListRows.kt:28`) has neither.
- **MAJOR** — Mute state is entirely absent from the model. No channel/category is ever dimmed or de-prioritised.
- **MAJOR** — Permission gating is only `VIEW_CHANNEL` and `CONNECT` (`ChannelListRows.kt:33-34, 50, 110-111`). No `MANAGE_CHANNELS`, `CREATE_INSTANT_INVITE`, `MANAGE_THREADS`, or guild-role-subscription lock evaluation.
- **MAJOR** — Category collapse (`ChannelListRows.kt:72-74`) keeps selected/unread/mentioned children. Reference also always keeps voice channels with connected users and channels the user is currently in.
- **MINOR** — Private-channel sort is `lastMessageId desc` (`ChannelListRows.kt:93`). Reference sorts by store-provided DM ordering.
- **MINOR** — `stableId = channel.id` (`ChannelListRows.kt:13`) collides between a category row and a channel row sharing an id space; ref uses `getKey(): String` per `ChannelListItem.java:71-72` prefixed by type.

### 2.2 `list/WidgetChannelsList.kt` (247 lines) — **BLOCKER**
Replaces `WidgetChannelsList.java` (491) + `WidgetChannelsListAdapter.java` (2,088).

- **BLOCKER** — **No long-press anywhere.** Reference wires `setOnLongClickListenerConsumeClick` on category (`WidgetChannelsListAdapter.java:283`), text channel (`:837`), thread (`:995`), voice (`:1276`, `:1281`), stage voice (`:688`), invite (`:1619`) and voice user (`:1885`). Target's `onBindViewHolder` (`WidgetChannelsList.kt:161-202`) sets only `setOnClickListener`. Consequence: `WidgetChannelsListItemChannelActions` and `WidgetChannelsListItemThreadActions` bottom sheets are unreachable.
- **BLOCKER** — Adapter is a plain `ListAdapter` with a single generic `Holder` (`WidgetChannelsList.kt:239`). Reference has 15 distinct `Item` viewholder subclasses (`WidgetChannelsListAdapter.java:156-1885`) each with its own binding and configure logic.
- **BLOCKER** — No `WidgetChannelListUnreads` integration. Reference embeds it (`WidgetChannelsList.java:66`) to drive the sticky "new messages ↑/↓" and "mentions ↑/↓" jump indicators with click-to-scroll (`WidgetChannelListUnreads.java:371 handleClick`, `:390 handleVisibleRangeUpdate`, `:463 onDatasetChanged`). Target has zero scroll observation.
- **MAJOR** — No guild banner / header icon configuration. Ref `configureHeaderColors` (`WidgetChannelsList.java:114`), `configureHeaderIcons` (`:131`), `bannerChangeDetector` (`:63`), `BANNER_TEXT_SHADOW_*` (`:58-60`), `roundPanelCorners`/`unroundPanelCorners` (`:357/:363`), `isCollapsed` AppBar state (`:68`). Target sets header text only (`WidgetChannelsList.kt:112`).
- **MAJOR** — No `WidgetGlobalStatusIndicatorState` handling (ref `:67`, `:349`) and no `BottomNavViewObserver` / bottom-nav space item (ref `:65`, `:109`).
- **MAJOR** — No `ActivityResultLauncher` (ref `:61`) — no add-server / directory result flow.
- **MAJOR** — No premium-guild hint ack (`ackPremiumGuildHint`, ref `:105`).
- **MAJOR** — Selected/unread visual state is a single `resolveAttribute` background swap (`WidgetChannelsList.kt:177-183`). Reference varies name text colour, icon tint, bold weight, mute alpha, and lock/NSFW icon per item type.
- **MAJOR** — Voice channel rows render name only; no connected-user avatar pile, no video/stream indicators, no user-limit text, no scheduled-event badge.
- **MAJOR** — Private-row avatar URL is hand-built (`WidgetChannelsList.kt:233-236`) and ignores group-DM multi-recipient icons, `isApplicationStreaming`, and typing state.
- **MINOR** — `companion object` static mutable callbacks (`WidgetChannelsList.kt:124-128`) are a test seam, not the reference's ViewModel/store wiring — process-global state, leaks across fragment instances.
- **MINOR** — Search/start-group clicks (`:42-47`) are plausible but reference routes through `WidgetGlobalSearchDialog` with analytics source `"Channels List"` (`WidgetChannelsList.java:57`) — no analytics emitted.

### 2.3 `list/items/CollapsedUser.kt` (21 lines) — **MINOR / orphaned**
Matches `CollapsedUser.java` structurally. **Dead code**: nothing in the target references it (its only reference consumer is `WidgetCollapsedUsersListAdapter`, MISSING).

### 2.4 `memberlist/ChannelMemberListRows.kt` (240 lines) — **MAJOR**
Stands in for `GuildMemberListItems` + `GuildMemberListItemGeneratorKt` + `PrivateChannelMemberListItems` + `PrivateChannelMemberListItemGeneratorKt` + `ThreadMemberListItems` + `ThreadMemberListItemGeneratorKt`.

- **MAJOR** — Group-type mapping is lossy: `Group.Type.ROLE` is mapped to `Kind.ONLINE` (`ChannelMemberListRows.kt:107`), so a role group whose role id fails to resolve renders as a generic "Online" header.
- **MAJOR** — `privateChannel()` (`:156`) never emits `AddMember` unless the caller passes `canAddMembers`, and `WidgetChannelMembersList.kt:117` never passes it — the "Invite members" row for group DMs is unreachable in practice.
- **MAJOR** — Thread rows: `thread()` (`:139`) takes `joined: Boolean? = null` and the only caller (`WidgetChannelMembersList.kt:126-134`) omits it — the **Join/Leave Thread row never renders**.
- **MAJOR** — No `isApplicationStreaming` / stream state per member (ref `WidgetChannelMembersListViewModel.java:97` observes `StoreApplicationStreaming`).
- **MAJOR** — No `StoreUserRelationships` observation → no blocked-user filtering (ref `:97`).
- **MINOR** — Offline detection treats `null` presence as offline (`:194`); reference distinguishes "not yet loaded" from offline.
- **MINOR** — `stableId` FNV hash over the key (`:16`) risks collisions; ref uses `Object rowId` identity (`ChannelMembersListAdapter.java:180`).

### 2.5 `memberlist/ChannelMembersListAdapter.kt` (172 lines) — **MAJOR**
vs `ChannelMembersListAdapter.java` (1,434) + 7 viewholder classes.

- **BLOCKER** — No **sticky headers**. Reference has `StickyHeadersManager` (`ChannelMembersListAdapter.java:1046`) pinning the current role/online header at the top during scroll. Absent entirely.
- **BLOCKER** — No async list diffing pipeline. Reference has `ListUpdateRequest` (`:952`), `ListUpdateOperation.DiffUpdate` (`:909`) / `OverwriteUpdate` (`:928`), a `DiffUtilCallback` (`:147`) and a subscription-driven scheduler (`:69`, `:100`, `:126`) that computes diffs off the main thread and falls back to overwrite for large deltas. Target uses default `ListAdapter` diffing on the main-thread executor — will jank on 10k-member lists.
- **MAJOR** — 7 reference `ViewType`s (`:1106-1112`) collapsed into 5 (`ChannelMembersListAdapter.kt:160-164`); `RoleHeader`/`Header` share one layout and `AddMember`/`JoinLeaveThread` share one, so per-type view recycling and layout differences are lost.
- **MAJOR** — No member long-press / no `ScrollToTop` event handling (ref `WidgetChannelMembersListViewModel.java:270`).
- **MINOR** — `VERIFIED_BOT_FLAG = 1 shl 16` is inlined (`:165`) instead of using the shared `UserUtils` constant.
- **MINOR** — Placeholder shimmer widths are a fixed 8-entry table keyed on adapter position (`:61`, `:166`); reference keys on the row's own id so widths are stable across scroll.

### 2.6 `memberlist/WidgetChannelMembersList.kt` (173 lines) — **MAJOR**
vs `WidgetChannelMembersList.java` (208) + `WidgetChannelMembersListViewModel.java` (1,250) + `PrivateChannelMemberListService.java` (199).

- **BLOCKER** — No ViewModel. All store wiring is inlined in `defaultStateSource()` (`:105-158`) and duplicated behind process-global `@JvmField` callbacks (`:160-167`).
- **BLOCKER** — No `ViewState.Empty` / loading state. Reference has `ViewState.Empty` (`WidgetChannelMembersListViewModel.java:974`) and `ViewState.Loaded` (`:985`); target always submits a list, so an unloaded guild renders as blank rather than as the loading skeleton.
- **BLOCKER** — No `Event.Error` handling (ref `:224`); member-list fetch failures are silent.
- **MAJOR** — No permission gating for the add-member row: reference threads `StorePermissions` through (`:97`); target has no permission source at all.
- **MAJOR** — `onThreadJoinLeaveClicked` (ref `:1182`) has no target equivalent — the callback (`WidgetChannelMembersList.kt:167`) is null by default and the row is filtered out at `:74-76`.
- **MAJOR** — No `PrivateChannelMemberListService` equivalent (ref 199 lines) — group-DM member list is recomputed from `observeAllUsers()` on every emission (`:113`).
- **MINOR** — Visible-range debounce is a fixed 100 ms `postDelayed` (`:42`, `:169`); reference batches via `Event.UpdateRanges` (`:281`) and `updateSubscriptionsForChannel(IntRange)` (`:1213`).

### 2.7 `settings/WidgetChannelNotificationSettings.kt` (99 lines) — **MAJOR / near-stub**
vs `WidgetChannelNotificationSettings.java` (527).

- **BLOCKER** — `stateSource` is a nullable process-global `@JvmField` (`:90`); if unset, `onStart()` (`:42`) subscribes to nothing and the screen renders **nothing at all**. No store wiring exists in the target.
- **BLOCKER** — Reference `Model` has 11 fields (`WidgetChannelNotificationSettings.java:191`); target `State` (`:18-25`) has 6. Missing: guild-level default notification level, parent-category override, forum-channel flags, `isGuildDefault` fallback labelling.
- **MAJOR** — No `configureGuildForumChannel` (ref `:399`) — forum-specific notification options (follow-all / follow-new-posts) absent.
- **MAJOR** — No `configureChannel` header (ref `:389`) — channel icon/type is not rendered.
- **MAJOR** — Mute-duration picker is reduced to a plain boolean mute (`:61`); reference offers timed-mute durations via `NotificationMuteSettingsView` selections.
- **MINOR** — Mute strings are hardcoded English (`"Mute ${state.channelName}"`, `:57`) instead of resource lookups.
- **MINOR** — No `launch(Context, long, boolean)` entry point (ref `:76`); only a `newInstance` (`:94`).

### 2.8 `WidgetChannelTopic.kt` (41 lines) — **PLACEHOLDER / BLOCKER**
vs `WidgetChannelTopic.java` (615) + `WidgetChannelTopicViewModel.java` (2,135) + `WidgetChannelTopicViewModelKt`.

Flag: **this is a stub.** It binds a name, a raw topic string and hides everything else.

- **BLOCKER** — Reference has 5 `StoreState` and 5 `ViewState` variants (`WidgetChannelTopicViewModel.java:343-968`, `:1068-1763`): `DM`, `GDM`, `Guild.Topic`, `Guild.DefaultTopic`, `NoChannel`. Target has no state modelling.
- **BLOCKER** — Topic text is set raw (`WidgetChannelTopic.kt:31`). Reference renders markdown/mentions/emoji via `RenderedTopic` (`WidgetChannelTopic.java:76`) and `configureChannelTopicTitle` (`:194`).
- **BLOCKER** — No expand/collapse. Ref `configureEllipsis` (`:253`) + `onToggleTopicExpansionState` (`:457`); target hard-hides the more-icon (`WidgetChannelTopic.kt:35`).
- **BLOCKER** — No overflow context menu. Ref `showContextMenu` (`:490`) with pinned-messages, copy-id, customize-group and close-DM (with `WidgetNoticeDialog` confirm, `:566`).
- **MAJOR** — `channel_aka` view is hard-hidden (`WidgetChannelTopic.kt:34`), so `UserAkaView` — which the target *does* implement — is never shown.
- **MAJOR** — No channel-type icon (ref `setChannelIcon` `:468`, `setChannelIconForGDM` `:478`); target only sets a group-DM icon URI (`:36`).
- **MAJOR** — No `Event.FocusFirstElement` accessibility handling (ref `:324`, `:439`).

### 2.9 `WidgetChannelSidebarActions.kt` (57 lines) — **MAJOR / near-stub**
vs `WidgetChannelSidebarActions.java` (~200) + `WidgetChannelSidebarActionsViewModel.java` (583).

- **BLOCKER** — No ViewModel. Reference `ViewState.Guild` (`WidgetChannelSidebarActionsViewModel.java:245`) carries 9 constructor args (`:573`): notification icon res, muted flag, `disablePins`, NSFW-allowed, parent-is-thread, is-thread, is-voice. Target passes `disablePins = false` unconditionally (`WidgetChannelSidebarActions.kt:45`) and never renders mute state.
- **BLOCKER** — No `ViewState.Uninitialized` / `StoreState.ChannelNotFound` handling (ref `:497`, `:221`); target just hides the view (`:26`).
- **MAJOR** — Reference observes `StoreNavigation.observeRightPanelState()`, `StoreUserGuildSettings`, `StoreGuildsNsfw`, `StoreUser` (`:65`). Target observes only the selected channel (`:18`) — notification icon never reflects mute/override state, NSFW gating absent.
- **MAJOR** — All five action callbacks are process-global nullable `@JvmField`s (`:51-55`), default null → **every button is a no-op out of the box**.

### 2.10 `threads/browser/ThreadBrowserThreadView.kt` (287 lines) — **MAJOR**
vs `ThreadBrowserThreadView.java` (612).

Best-effort implementation, but the markdown/mention pipeline is re-implemented rather than reused.

- **MAJOR** — Message preview is rendered by hand-rolled regex (`:137-174`): `applyInlineMarkdown` handles only bold/underline/strike/code/italic. Reference delegates to the shared `SimpleDraweeSpanTextView` + AST renderer, so custom emoji images, spoilers, links, code blocks, timestamps and role-colour pills are all lost. Target replaces `<a?:name:id>` with plain `:name:` text (`:146`).
- **MAJOR** — Date parsing (`parseUtcDate`, `:259`) silently returns `null` on any unexpected `archiveTimestamp` format, and `configureArchivedThreadUI` (`:109`) then `return`s, leaving a **stale timestamp from the recycled view**.
- **MINOR** — Timestamp strings are formatted by `String.replace("{count}", …)` (`:196-203`) rather than resource plural/format args — no pluralisation.
- **MINOR** — `themedColor` falls back to `Color.WHITE` (`:221`) instead of the theme's header colour.

### 2.11 `permissions/PermissionOwnerListView.kt` (116 lines) — **MINOR**
vs `PermissionOwnerListView.java` (357). Structurally faithful (all 4 `CannotRemove.Reason` values handled, `IsRemoving` alpha, toasts). Gaps:
- **MINOR** — `notifyDataSetChanged()` on every `setData` (`:70`) instead of DiffUtil.
- **MINOR** — `IS_GUILD_OWNER` / `IS_ADMINISTRATOR` reasons fall through to `Unit` (`:104-106`) — reference shows an explanatory toast for these too.

### 2.12 `permissions/PermissionOwner.kt` (14 lines) — **OK**
Matches `PermissionOwner.java` (`Role`, `Member` with `nickname`/`isOwner`). Complete.

### 2.13 `permissions/AddPermissionOwnerView.kt` (42 lines) — **MINOR**
Matches `AddPermissionOwnerView.java`. **MINOR**: no click handling or enabled/disabled state exposed; it is purely a labelled row. Reference callers set the click on the view externally, so this is acceptable — but nothing in the target ever instantiates it (the whole `WidgetChannelSettingsPermissions*` cluster is MISSING), so it is **dead code**.

### 2.14 `SimpleMembersAdapter.kt` (70 lines) — **MINOR**
vs `SimpleMembersAdapter.java` (195). Diffing, single `TYPE_MEMBER`, `SettingsMemberView.configure` all present.
- **MINOR** — `onUserClickedListener` is assigned *after* `dispatchUpdatesTo` (`:44`), so the first frame after a data change binds with the previous listener.
- **MINOR** — `configure(position, data)` ignores `position` (`:53`); reference uses it for divider/last-item styling.

### 2.15 `UserAkaView.kt` (79 lines) — **MINOR**
vs `UserAkaView.java` (175). `configure`, pile items, `LeadingMarginSpan`, doOnPreDraw measurement all present.
- **MINOR** — `nicknameStringBuilder` (`:26`) is declared but never used; the reference builds the joined nickname string through it.
- **MINOR** — Nicknames are joined with `", "` (`:55`) rather than the reference's localized separator.
- **MINOR** — Only reachable via `WidgetChannelTopic`, which hard-hides it (`WidgetChannelTopic.kt:34`) → effectively dead.

---

## 3. Full coverage table (114 reference classes)

Legend: ✅ present & substantially complete · 🟡 present but partial · ❌ MISSING

### root
| Reference | Target | Status |
|---|---|---|
| ChannelOnboardingManager | — | ❌ |
| ChannelPickerAdapterItem | — | ❌ |
| ChannelPickerViewHolder | — | ❌ |
| SimpleMembersAdapter | `channels/SimpleMembersAdapter.kt` | 🟡 |
| SimpleRolesAdapter | — | ❌ |
| TextInVoiceOnboardingManager | — | ❌ |
| TextInVoiceOnboardingManagerKt | — | ❌ |
| UserAkaView | `channels/UserAkaView.kt` | 🟡 |
| WidgetChannelOnboarding | — | ❌ |
| WidgetChannelPickerBottomSheet | — | ❌ |
| WidgetChannelPickerBottomSheetViewModel | — | ❌ |
| WidgetChannelSelector | — | ❌ |
| WidgetChannelSidebarActions | `channels/WidgetChannelSidebarActions.kt` | 🟡 |
| WidgetChannelSidebarActionsViewModel | — | ❌ |
| WidgetChannelTopic | `channels/WidgetChannelTopic.kt` | 🟡 (stub) |
| WidgetChannelTopicViewModel | — | ❌ |
| WidgetChannelTopicViewModelKt | — | ❌ |
| WidgetCreateChannel | — | ❌ |
| WidgetGroupInviteFriends | — | ❌ |
| WidgetGroupInviteFriendsAdapter | — | ❌ |
| WidgetTextInVoiceOnboarding | — | ❌ |
| WidgetchannelPickerAdapter | — | ❌ |

### invite — 0/4
GroupInviteFriendsSheet ❌ · GroupInviteFriendsSheetAdapter ❌ · GroupInviteFriendsSheetFeatureFlag ❌ · GroupInviteFriendsSheetViewModel ❌

### list — 1/8
| Reference | Target | Status |
|---|---|---|
| WidgetChannelListModel | (loosely `list/ChannelListRows.kt`) | 🟡 |
| WidgetChannelListUnreads | — | ❌ |
| WidgetChannelsList | `list/WidgetChannelsList.kt` | 🟡 |
| WidgetChannelsListAdapter | (private `ChannelAdapter` in `WidgetChannelsList.kt:131`) | 🟡 |
| WidgetChannelsListItemChannelActions | — | ❌ |
| WidgetChannelsListItemThreadActions | — | ❌ |
| WidgetCollapsedUsersListAdapter | — | ❌ |
| WidgetThreadArchiveActions | — | ❌ |

### list/items — 9/23
| Reference | Target | Status |
|---|---|---|
| ChannelListItem | `ChannelListRow` sealed class (`ChannelListRows.kt:7`) | 🟡 |
| ChannelListItemCategory | `ChannelListRow.Category` (`:16`) | 🟡 |
| ChannelListItemTextChannel | `ChannelListRow.Text` (`:18`) | 🟡 |
| ChannelListItemVoiceChannel | `ChannelListRow.Voice` (`:20`) | 🟡 |
| ChannelListItemStageVoiceChannel | `ChannelListRow.Stage` (`:22`) | 🟡 |
| ChannelListItemDirectory | `ChannelListRow.Directory` (`:24`) | 🟡 |
| ChannelListItemThread | `ChannelListRow.Thread` (`:26`) | 🟡 |
| ChannelListItemPrivate | `ChannelListRow.Private` (`:28`) | 🟡 |
| CollapsedUser | `list/items/CollapsedUser.kt` | ✅ (orphaned) |
| ChannelListBottomNavSpaceItem | — | ❌ |
| ChannelListItemActiveEvent | — | ❌ |
| ChannelListItemActiveEventData | — | ❌ |
| ChannelListItemAddServer | — | ❌ |
| ChannelListItemEventsSeparator | — | ❌ |
| ChannelListItemGuildJoinRequest | — | ❌ |
| ChannelListItemGuildRoleSubscriptionsOverview | — | ❌ |
| ChannelListItemGuildScheduledEvents | — | ❌ |
| ChannelListItemHeader | — | ❌ |
| ChannelListItemInvite | — | ❌ |
| ChannelListItemMfaNotice | — | ❌ |
| ChannelListItemStageAudienceCount | — | ❌ |
| ChannelListItemVoiceUser | — | ❌ |
| ChannelListVocalItem | — | ❌ |

### memberlist — 4/10
| Reference | Target | Status |
|---|---|---|
| GuildMemberListItems | `ChannelMemberListRows.guild()` (`ChannelMemberListRows.kt:89`) | 🟡 |
| GuildMemberListItemGeneratorKt | `groupedRows()` (`:181`) | 🟡 |
| PrivateChannelMemberListItems | `privateChannel()` (`:156`) | 🟡 |
| ThreadMemberListItems | `thread()` (`:139`) | 🟡 |
| PrivateChannelMemberListItemGeneratorKt | — | ❌ |
| ThreadMemberListItemGeneratorKt | — | ❌ |
| PrivateChannelMemberListService | — | ❌ |
| WidgetChannelMembersList | `memberlist/WidgetChannelMembersList.kt` | 🟡 |
| WidgetChannelMembersListKt | — | ❌ |
| WidgetChannelMembersListViewModel | — | ❌ |

### memberlist/adapter — 8/9
| Reference | Target | Status |
|---|---|---|
| ChannelMembersListAdapter | `memberlist/ChannelMembersListAdapter.kt` | 🟡 |
| ChannelMembersListViewHolderMember | `bindMember` (`:73`) | 🟡 |
| ChannelMembersListViewHolderHeader | `bindHeader` (`:108`) | 🟡 |
| ChannelMembersListViewHolderRoleHeader | `bindRoleHeader` (`:119`) | 🟡 |
| ChannelMembersListViewHolderAdd | `bindAction` (`:129`) | 🟡 |
| ChannelMembersListViewHolderJoinLeaveThread | `bindAction` (`:129`) | 🟡 |
| ChannelMembersListViewHolderLoading | `bind` PLACEHOLDER_MEMBER (`:60`) | 🟡 |
| ChannelMembersListViewHolderPlaceholderHeader | `bind` no-op (`:69`) | 🟡 |
| ChannelMembersListAdapterKt | — | ❌ |

### permissions — 3/21
| Reference | Target | Status |
|---|---|---|
| PermissionOwner | `permissions/PermissionOwner.kt` | ✅ |
| PermissionOwnerListView | `permissions/PermissionOwnerListView.kt` | ✅ |
| AddPermissionOwnerView | `permissions/AddPermissionOwnerView.kt` | ✅ (orphaned) |
| AddMemberAdapter | — | ❌ |
| AddMemberAdapterKt | — | ❌ |
| ConfirmRemovePermissionDialog | — | ❌ |
| ConfirmRemovePermissionDialogViewModel | — | ❌ |
| WidgetChannelSettingsAddMemberFragment | — | ❌ |
| WidgetChannelSettingsAddMemberFragmentViewModel | — | ❌ |
| WidgetChannelSettingsAddMemberSheet | — | ❌ |
| WidgetChannelSettingsEditPermissions | — | ❌ |
| WidgetChannelSettingsEditPermissionsModel | — | ❌ |
| WidgetChannelSettingsPermissionsAddMember | — | ❌ |
| WidgetChannelSettingsPermissionsAddRole | — | ❌ |
| WidgetChannelSettingsPermissionsAdvanced | — | ❌ |
| WidgetChannelSettingsPermissionsOverview | — | ❌ |
| WidgetChannelSettingsPermissionsOverviewViewModel | — | ❌ |
| WidgetChannelSettingsPermissionsOverviewViewModelKt | — | ❌ |
| WidgetCreateChannelAddMember | — | ❌ |
| WidgetStageChannelModeratorPermissions | — | ❌ |
| WidgetStageChannelModeratorPermissionsViewModel | — | ❌ |

### settings — 1/7
| Reference | Target | Status |
|---|---|---|
| WidgetChannelNotificationSettings | `settings/WidgetChannelNotificationSettings.kt` | 🟡 |
| ChannelSettings | — | ❌ |
| ChannelGroupDMSettingsViewModel | — | ❌ |
| WidgetChannelGroupDMSettings | — | ❌ |
| WidgetTextChannelSettings | — | ❌ |
| WidgetThreadSettings | — | ❌ |
| WidgetThreadSettingsViewModel | — | ❌ |

### threads + threads/browser — 1/10
| Reference | Target | Status |
|---|---|---|
| ThreadBrowserThreadView | `threads/browser/ThreadBrowserThreadView.kt` | 🟡 |
| WidgetThreadDraftArchiveSheet | — | ❌ |
| WidgetThreadBrowser | — | ❌ |
| WidgetThreadBrowserActive | — | ❌ |
| WidgetThreadBrowserActiveViewModel | — | ❌ |
| WidgetThreadBrowserAdapter | — | ❌ |
| WidgetThreadBrowserArchived | — | ❌ |
| WidgetThreadBrowserArchivedViewModel | — | ❌ |
| WidgetThreadBrowserFilterSheet | — | ❌ |
| WidgetThreadBrowserViewModel | — | ❌ |

---

## 4. Channel-list row types: reference vs target — **BLOCKER**

Reference `ChannelListItem.java:11-30` declares 21 types; `WidgetChannelsListAdapter.java:2037-2084` instantiates a viewholder for each. Target `ChannelAdapter.getItemViewType` (`WidgetChannelsList.kt:138-146`) produces **7**.

| # | Reference TYPE | Ref layout | Target row | Status |
|---|---|---|---|---|
| 0 | TYPE_CHANNEL | `widget_channels_list_item_channel` | `ChannelListRow.Text` | 🟡 fields missing |
| 1 | TYPE_CHANNEL_VOICE | `..._channel_voice` | `ChannelListRow.Voice` | 🟡 fields missing |
| 2 | TYPE_CHANNEL_VOICE_USER | `..._voice_user` | — | ❌ **MISSING** |
| 3 | TYPE_CHANNEL_PRIVATE | `..._channel_private` | `ChannelListRow.Private` | 🟡 |
| 6 | TYPE_HEADER | `..._header` | — | ❌ **MISSING** |
| 7 | TYPE_MFA_NOTICE | `..._mfa` | — | ❌ **MISSING** |
| 8 | TYPE_CATEGORY | `..._category` | `ChannelListRow.Category` | 🟡 |
| 9 | TYPE_INVITE | `..._invite` | — | ❌ **MISSING** |
| 11 | TYPE_BOTTOM_NAV_SPACE | `recycler_item_bottom_nav_space` | — | ❌ **MISSING** |
| 12 | TYPE_THREAD | `..._thread` | `ChannelListRow.Thread` | 🟡 no spine |
| 13 | TYPE_CHANNEL_STAGE_VOICE | `..._channel_stage_voice` | `ChannelListRow.Stage` | 🟡 |
| 14 | TYPE_STAGE_EVENTS_SEPARATOR | `..._stage_events_separator` | — | ❌ **MISSING** |
| 15 | TYPE_ACTIVE_EVENT | `..._active_event` | — | ❌ **MISSING** |
| 16 | TYPE_STAGE_AUDIENCE_COUNT | `..._audience_count` | — | ❌ **MISSING** |
| 17 | TYPE_DIRECTORY | `..._directory` | `ChannelListRow.Directory` | 🟡 |
| 18 | TYPE_ADD_SERVER | `..._directory` | — | ❌ **MISSING** |
| 19 | TYPE_GUILD_SCHEDULED_EVENTS | `..._guild_scheduled_events` | — | ❌ **MISSING** |
| 20 | TYPE_HUB_INVITE | `..._directory` | — | ❌ **MISSING** |
| 21 | TYPE_GUILD_ROLE_SUBSCRIPTIONS_OVERVIEW | `..._guild_role_subs` | — | ❌ **MISSING** |
| 22 | TYPE_GUILD_JOIN_REQUEST | `..._guild_join_request` | — | ❌ **MISSING** |

**13 of 20 live row types are never produced.**

Notably, **every one of these layouts already exists in the target's `res/layout/`** (`widget_channels_list_item_voice_user.xml`, `_active_event.xml`, `_audience_count.xml`, `_guild_join_request.xml`, `_guild_role_subs.xml`, `_guild_scheduled_events.xml`, `_header.xml`, `_invite.xml`, `_mfa.xml`, `_stage_events_separator.xml`, plus `widget_channels_list_item_actions.xml` and `widget_channels_list_item_thread_actions.xml` and the whole `thread_browser_*` set). They are unreferenced by any Kotlin code — layout-only placeholders, which the PLAN contract explicitly excludes from parity.

---

## 5. PLACEHOLDER / stub files (explicit flags)

| File | Verdict |
|---|---|
| `WidgetChannelTopic.kt` (41 lines vs 615+2135 ref) | **PLACEHOLDER** — binds name + raw topic; hard-hides `channel_aka` (`:34`) and `channel_topic_more_icon` (`:35`); no markdown, no expansion, no context menu, no ViewModel. |
| `WidgetChannelSidebarActions.kt` (57 lines vs ~200+583 ref) | **NEAR-STUB** — all 5 action callbacks default to `null` (`:51-55`) so every button is a no-op; `disablePins = false` hardcoded (`:45`); no mute/NSFW state. |
| `settings/WidgetChannelNotificationSettings.kt` (99 vs 527) | **NEAR-STUB** — renders nothing unless the process-global `stateSource` (`:90`) is set; no store wiring exists in the repo. |
| `list/items/CollapsedUser.kt` | **ORPHANED** — no consumer. |
| `permissions/AddPermissionOwnerView.kt` | **ORPHANED** — no consumer (whole permissions-settings cluster missing). |
| `UserAkaView.kt` | **EFFECTIVELY ORPHANED** — only entry point hides it. |

---

## 6. Missing feature clusters (83 classes)

| Cluster | Missing classes | Severity |
|---|---|---|
| **Channel settings** (text/thread/group-DM settings screens + VMs) | ChannelSettings, ChannelGroupDMSettingsViewModel, WidgetChannelGroupDMSettings, WidgetTextChannelSettings, WidgetThreadSettings, WidgetThreadSettingsViewModel (6) | BLOCKER |
| **Channel permissions** (overview/advanced/edit/add-member/add-role/stage-moderator + adapters + confirm dialog) | 18 of 21 in `permissions/` | BLOCKER |
| **Thread browser** (active/archived tabs, adapter, filter sheet, 3 VMs) | WidgetThreadBrowser, …Active, …ActiveViewModel, …Adapter, …Archived, …ArchivedViewModel, …FilterSheet, …ViewModel, WidgetThreadDraftArchiveSheet (9) | BLOCKER |
| **Channel-list context menus / actions** | WidgetChannelsListItemChannelActions, WidgetChannelsListItemThreadActions, WidgetThreadArchiveActions (3) | BLOCKER |
| **Channel-list rows** (voice users, headers, invites, MFA, stage events, scheduled events, role subs, join requests, add-server, nav space) | 14 of 23 in `list/items/` + WidgetCollapsedUsersListAdapter (15) | BLOCKER |
| **Channel-list unreads / jump bars** | WidgetChannelListUnreads (1) | BLOCKER |
| **Channel creation & selection** | WidgetCreateChannel, WidgetChannelSelector, WidgetCreateChannelAddMember (3) | MAJOR |
| **Channel picker bottom sheet** | WidgetChannelPickerBottomSheet, …ViewModel, WidgetchannelPickerAdapter, ChannelPickerAdapterItem, ChannelPickerViewHolder (5) | MAJOR |
| **Group-DM invite friends** | WidgetGroupInviteFriends, WidgetGroupInviteFriendsAdapter + all 4 `invite/` classes (6) | MAJOR |
| **Channel onboarding / text-in-voice** | ChannelOnboardingManager, WidgetChannelOnboarding, TextInVoiceOnboardingManager(+Kt), WidgetTextInVoiceOnboarding (5) | MAJOR |
| **Member-list ViewModel & services** | WidgetChannelMembersListViewModel, WidgetChannelMembersListKt, PrivateChannelMemberListService, PrivateChannelMemberListItemGeneratorKt, ThreadMemberListItemGeneratorKt, ChannelMembersListAdapterKt (6) | BLOCKER |
| **Channel topic / sidebar ViewModels** | WidgetChannelTopicViewModel(+Kt), WidgetChannelSidebarActionsViewModel (3) | BLOCKER |
| **Misc** | SimpleRolesAdapter, WidgetChannelListModel (as a first-class model) (2) | MAJOR |

---

## 7. Top gaps by severity

**BLOCKER**
1. 83/114 reference classes have no target counterpart at all.
2. 13 of 20 live channel-list row types are never produced (`WidgetChannelsList.kt:138-146` vs `ChannelListItem.java:11-30`).
3. No long-press anywhere in the channel list → channel/thread action bottom sheets unreachable.
4. Entire channel-settings cluster (6 classes) missing.
5. Entire channel-permissions cluster (18 classes) missing.
6. Entire thread-browser cluster (9 classes) missing — only the row view exists.
7. `WidgetChannelListUnreads` (jump-to-unread/mention indicators) missing.
8. No ViewModels anywhere in the slice; store wiring is inlined and duplicated behind process-global mutable `@JvmField` callbacks.
9. `WidgetChannelTopic.kt` is a placeholder (41 lines vs 2,750 reference lines).
10. Member-list adapter has no sticky headers and no off-main-thread diff pipeline.
11. No loading / empty / error states in the member list.
12. Voice channels render no participants, video, stream, or user-limit state.
13. Mute state absent from every channel-list model.

**MAJOR**
14. Thread spine/elbow rendering absent (`ChannelListItemThread.spineType` unmapped).
15. Guild banner / header icons / collapsing-toolbar behaviour absent from `WidgetChannelsList.kt`.
16. Join/Leave-thread row and group-DM Invite-members row are unreachable (callbacks default null and are filtered at `WidgetChannelMembersList.kt:74-76`).
17. `WidgetChannelNotificationSettings.kt` renders nothing without a manually-installed `stateSource`; forum options and timed-mute durations missing.
18. `ThreadBrowserThreadView.kt` message preview uses hand-rolled regex markdown — loses emoji, spoilers, links, code blocks.
19. Permission gating limited to `VIEW_CHANNEL`/`CONNECT`; no `MANAGE_CHANNELS`, `CREATE_INSTANT_INVITE`, `MANAGE_THREADS`, or role-subscription locks.

**MINOR**
20. `ChannelListRow.stableId = channel.id` risks category/channel id collisions.
21. `notifyDataSetChanged()` in `PermissionOwnerListView.kt:70`.
22. `SimpleMembersAdapter.kt:44` assigns the click listener after dispatching the diff.
23. `UserAkaView.nicknameStringBuilder` declared but unused (`:26`).
24. Hardcoded English strings in `WidgetChannelNotificationSettings.kt:57`.
25. `ThreadBrowserThreadView.parseUtcDate` failure leaves a stale recycled timestamp (`:109`).

---

## 8. Notes on JADX output quality
JADX output for this package is readable. Lambdas are extracted into `$`-named synthetic classes (excluded from the count per the task definition), and some field names are obfuscated to single letters (e.g. `binding.a`, `binding.f2285b` in `WidgetChannelsListAdapter.java`), so exact view-id → binding-field mappings inside those viewholders could not be recovered from the decompile alone. Where that affected a claim (specifically which sub-view receives a listener inside `ItemChannelCategory`/`ItemHeader`), the finding above is stated at the level of "a listener is attached" rather than naming the view — this is a limitation of the decompile, not an inference.
