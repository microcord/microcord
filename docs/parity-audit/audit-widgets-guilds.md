# Parity Audit — guild-related widget packages

TARGET: `C:\Users\manti\Desktop\projeler\manticordrebuild`
REFERENCE: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21, JADX)

Scope: `widgets/guilds/**`, `widgets/guildscheduledevent/**`, `widgets/guild_role_subscriptions/**`, `widgets/roles/**`.
Method: every target Kotlin file in scope read in full (24 files, 2226 LOC). Reference read selectively —
all class/member signatures enumerated; bodies read for the guild-list cluster and the view classes that
have target counterparts.

## 0. Headline

| Package | Reference classes | Target files | Mapped | MISSING | Coverage |
|---|---|---|---|---|---|
| `widgets/guilds` | 75 | 8 | 7 | 68 | **9.3 %** |
| `widgets/guildscheduledevent` | 47 | 10 | 9 | 38 | **19.1 %** |
| `widgets/guild_role_subscriptions` | 42 | 4 | 4 | 38 | **9.5 %** |
| `widgets/roles` | 2 | 2 | 2 | 0 | **100 % (by file, not by behavior)** |
| **TOTAL** | **166** | **24** | **22** | **144** | **13.3 %** |

Two target files have no reference counterpart in this slice and are new inventions:
`widgets/guilds/list/GuildFolderReorder.kt`, `widgets/guilds/list/GuildFolderPersistenceRequest.kt`,
plus `widgets/guildscheduledevent/ScheduledEventUi.kt` (consolidates helpers that live outside this slice).

**Not a single Fragment, BottomSheetDialogFragment, DialogFragment, ViewModel, or RecyclerView adapter
from the reference exists in the target for this slice** — except the guild sidebar fragment
`WidgetGuildsList.kt`, which is a monolithic re-write, not a port. Everything the contract calls a
"dialog, bottom sheet, adapter row, interaction" is absent.

### JADX quality caveats (do not guess)
- `WidgetGuildsListViewModel.handleStoreState(StoreState)` (ref `WidgetGuildsListViewModel.java:1349`)
  is **NOT decompiled** ("Method dump skipped, instructions count: 1289"). The exact list assembly order
  (Friends → DMs → divider → guilds/folders → Create → Hub → Help → Space) and the unavailable-guild
  aggregation rule cannot be read from JADX. Item *types*, item *IDs*, and every model *field* are
  readable and are used below.
- `WidgetGuildListAdapter.onMove(...)` (ref `WidgetGuildListAdapter.java:442`) is **NOT decompiled**
  ("Method dump skipped, instructions count: 362"). The exact TOP/CENTER/BOTTOM hit-zone arithmetic is
  therefore unknown; only the `NEAR_CENTER_PERCENTAGE = 0.2f` constant (`:29`) and the `TargetPosition`
  enum (`:280`) are readable.
- `GuildsDragAndDropCallback.getMovementFlags` (ref `:70-79`) decompiled to a nonsense
  `(cond) ? false : false ? 3 : 0` expression. Intent is clearly `makeMovementFlags(UP|DOWN, 0)` when
  `DraggableViewHolder.canDrag()`; stated here as inference, not as read fact.

---

## 1. Reference class inventory by subpackage

### `widgets/guilds` (75)
**root (3)** — `WidgetGuildFolderSettings`, `WidgetGuildFolderSettingsViewModel`, `WidgetGuildSelector`
**contextmenu (4)** — `FolderContextMenuViewModel`, `GuildContextMenuViewModel`, `WidgetFolderContextMenu`, `WidgetGuildContextMenu`
**create (11)** — `ChannelTemplate`, `CreateGuildTrigger`, `CreationIntentArgs`, `GuildCreateCloneViews`, `GuildTemplateChannelsView`, `StockGuildTemplate`, `StockGuildTemplateKt`, `WidgetCreationIntent`, `WidgetGuildClone`, `WidgetGuildCreate`, `WidgetGuildCreateViewModel`
**invite (25)** — `GuildInvite`, `GuildInviteSettingsViewModel`, `GuildInviteShareSheetFeatureFlag`, `GuildInviteShareSheetViewModel`, `GuildInviteUiHelperKt`, `GuildInviteViewModel`, `InviteGenerator`, `InviteJoinHelper`, `InviteSuggestion`, `InviteSuggestionItem`, `InviteSuggestionItemV2`, `InviteSuggestionsAdapter`, `InviteSuggestionsManager`, `PrivateChannelAdapter`, `TargetChannelSelector`, `ViewInviteSettingsSheet`, `WidgetGuildInvite`, `WidgetGuildInviteSettings`, `WidgetGuildInviteShare`, `WidgetGuildInviteShareCompact`, `WidgetGuildInviteShareEmptySuggestions`, `WidgetGuildInviteShareSheet`, `WidgetGuildInviteShareViewModel`, `WidgetInviteInfo`, `WidgetInviteModel`
**join (13)** — `BaseChannelViewHolder`, `CaptchaArgs`, `ChannelItem`, `ChannelViewHolder`, `GuildJoinHelperKt`, `InviteArgs`, `JoinArgs`, `WidgetGuildJoin`, `WidgetGuildJoinCaptchaBottomSheet`, `WidgetGuildJoinCaptchaBottomSheetKt`, `WidgetGuildWelcomeSheet`, `WidgetGuildWelcomeSheetChannelAdapter`, `WidgetGuildWelcomeSheetViewModel`
**leave (2)** — `LeaveGuildDialogViewModel`, `WidgetLeaveGuildDialog`
**list (7)** — `FolderItemDecoration`, `GuildListItem`, `GuildListViewHolder`, `GuildsDragAndDropCallback`, `WidgetGuildListAdapter`, `WidgetGuildsList`, `WidgetGuildsListViewModel`
**profile (10)** — `BaseEmojiViewHolder`, `EmojiItem`, `EmojiViewHolder`, `MoreEmojiViewHolder`, `WidgetChangeGuildIdentity`, `WidgetChangeGuildIdentityViewModel`, `WidgetGuildProfileSheet`, `WidgetGuildProfileSheetEmojisAdapter`, `WidgetGuildProfileSheetViewModel`, `WidgetPublicAnnouncementProfileSheet`

### `widgets/guildscheduledevent` (47)
**root (44)** — `EventDetailsRsvpUsersFetchState`, `EventDetailsSection`, `GuildEventPromptListView`, `GuildEventPromptView`, `GuildIdsWithActiveEventsModel`, `GuildScheduledEventAPI`, `GuildScheduledEventBottomButtonView`, `GuildScheduledEventDateView`, `GuildScheduledEventDescriptionParser`, `GuildScheduledEventDescriptionParserKt`, `GuildScheduledEventDetailsArgs`, `GuildScheduledEventDetailsSource`, `GuildScheduledEventDetailsViewModel`, `GuildScheduledEventDirectoryAssociationState`, `GuildScheduledEventDirectoryAssociationViewModel`, `GuildScheduledEventExternalLocationParser`, `GuildScheduledEventExternalLocationParserKt`, `GuildScheduledEventItemView`, `GuildScheduledEventListAdapter`, `GuildScheduledEventListItem`, `GuildScheduledEventListViewModel`, `GuildScheduledEventLocationInfo`, `GuildScheduledEventModel`, `GuildScheduledEventModelKt`, `GuildScheduledEventPickerDate`, `GuildScheduledEventPickerDateTime`, `GuildScheduledEventPickerTime`, `GuildScheduledEventRsvpUserListAdapter`, `GuildScheduledEventRsvpUserListItem`, `GuildScheduledEventSettingsViewModel`, `GuildScheduledEventUrlUtils`, `GuildScheduledEventUser`, `PreviewGuildScheduledEventViewModel`, `WidgetEndGuildScheduledEventBottomSheet`, `WidgetGuildScheduledEventCreateSuccess`, `WidgetGuildScheduledEventCreateSuccessViewModel`, `WidgetGuildScheduledEventDetailsBottomSheet`, `WidgetGuildScheduledEventDetailsExtrasBottomSheet`, `WidgetGuildScheduledEventListBottomSheet`, `WidgetGuildScheduledEventLocationSelect`, `WidgetGuildScheduledEventLocationSelectViewModel`, `WidgetGuildScheduledEventSettings`, `WidgetGuildScheduledEventUpsellBottomSheet`, `WidgetPreviewGuildScheduledEvent`
**buttonconfiguration (3)** — `ButtonConfiguration`, `DirectoryButtonConfiguration`, `GuildButtonConfiguration`

### `widgets/guild_role_subscriptions` (42)
**root (10)** — `GuildRoleSubscriptionBenefitItemView`, `GuildRoleSubscriptionUtils`, `GuildRoleSubscriptionUtilsKt`, `GuildRoleSubscriptionsFeatureFlag`, `GuildSubscriptionRoleImageUploadView`, `PriceTierPickerAdapter`, `PriceTierPickerBottomSheetViewModel`, `PriceTierViewHolder`, `RoleSubscriptionsLinkingUtil`, `WidgetPriceTierPickerBottomSheet`
**entrypoint (2)** — `WidgetGuildRoleSubscriptionEntryPoint`, `WidgetGuildRoleSubscriptionEntryPointViewModel`
**setup (6)** — `GuildRoleSubscriptionPlanDetailsViewModel`, `GuildRoleSubscriptionPlanFormatRadioButton`, `GuildRoleSubscriptionPlanSetupViewModel`, `WidgetGuildRoleSubscriptionPlanDetails`, `WidgetGuildRoleSubscriptionPlanFormat`, `WidgetGuildRoleSubscriptionPlanSetup`
**tier (5)** — `GuildRoleSubscriptionTierAdapter`, `GuildRoleSubscriptionTierAdapterItem`, `GuildRoleSubscriptionTierItemDecoration`, `GuildRoleSubscriptionTierListItemGeneratorKt`, `GuildRoleSubscriptionTierViewHolder`
**tier/create (8)** — `CreateGuildRoleSubscriptionTierViewModel`, `GuildRoleSubscriptionMemberPreview`, `GuildRoleSubscriptionReviewViewModel`, `GuildRoleSubscriptionTierViewModel`, `WidgetCreateGuildRoleSubscriptionTier`, `WidgetGuildRoleSubscriptionReview`, `WidgetGuildRoleSubscriptionTierDesign`, `WidgetGuildRoleSubscriptionTierDetails`
**tier/create/benefits (9)** — `GuildRoleSubscriptionBenefitAdapter`, `GuildRoleSubscriptionBenefitAdapterItem`, `GuildRoleSubscriptionBenefitListItemGeneratorKt`, `GuildRoleSubscriptionBenefitViewHolder`, `GuildRoleSubscriptionTierBenefitListType`, `GuildRoleSubscriptionTierBenefitViewModel`, `GuildRoleSubscriptionTierBenefitsViewModel`, `WidgetGuildRoleSubscriptionTierBenefit`, `WidgetGuildRoleSubscriptionTierBenefits`
**tier/model (2)** — `Benefit`, `GuildRoleSubscriptionTier`

### `widgets/roles` (2)
`RoleIconView`, `RolesListView`

---

## 2. Special focus — `widgets/guilds/list/WidgetGuildsList.kt` (Phase 6, IN PROGRESS)

Target: `.../widgets/guilds/list/WidgetGuildsList.kt` (318 lines) — a single file containing the fragment,
the row model (`GuildRow`, `:185-203`), and a private `GuildAdapter` (`:205-318`).
Reference equivalent: `WidgetGuildsList.java` (527) + `WidgetGuildsListViewModel.java` (1700) +
`WidgetGuildListAdapter.java` (554) + `GuildListItem.java` (835) + `GuildListViewHolder.java` (895) +
`GuildsDragAndDropCallback.java` (121) + `FolderItemDecoration.java` (193) = **4825 lines across 7 classes**.

### 2.1 Item type set — 4 of 10 present

Reference `GuildListItem` declares 10 view types (`GuildListItem.java:28-37`):

| Ref type (const) | Ref item class | Ref layout (`WidgetGuildListAdapter.java:507-552`) | Target |
|---|---|---|---|
| `TYPE_FRIENDS = 0` | `FriendsItem(isSelected)` `:295` | `widget_guilds_list_item_profile` | `GuildRow.Home(selected)` — **partial** (`:186`) |
| `TYPE_DIVIDER = 1` | `DividerItem` `:66` | `widget_guilds_list_item_divider` | **MISSING** |
| `TYPE_DM = 2` | `PrivateChannelItem(channel,isUnread,mentionCount)` | `widget_guilds_list_item_dm_vertical` | `GuildRow.Private` — **partial** (`:193`) |
| `TYPE_GUILD = 3` | `GuildItem` (15 fields) `:345` | `widget_guilds_list_item_guild_vertical` | `GuildRow.GuildItem` (4 fields) — **partial** (`:187`) |
| `TYPE_UNAVAILABLE = 4` | `UnavailableItem(unavailableGuildCount)` | `widget_guilds_list_item_guild_unavailable` | **MISSING** |
| `TYPE_CREATE = 5` | `CreateItem` `:55` | `widget_guilds_list_item_guild_new` | **MISSING (BLOCKER)** |
| `TYPE_FOLDER = 6` | `FolderItem` (12 fields) `:77` | `widget_guilds_list_item_folder` | `GuildRow.Folder` (7 fields) — **partial** (`:194`) |
| `TYPE_HELP = 7` | `HelpItem` `:630` | `widget_guilds_list_item_guild_nux` | **MISSING** |
| `TYPE_BOTTOM_NAV_SPACE = 8` | `SpaceItem` | `recycler_item_bottom_nav_space` | **MISSING** |
| `TYPE_HUB = 9` | `HubItem(showSparkle)` `:641` | `widget_guilds_list_item_hub_verification` | **MISSING** |

Target `GuildAdapter.getItemViewType` (`WidgetGuildsList.kt:221-226`) returns only 0..3.

### 2.2 Model field gaps

`GuildListItem.GuildItem` (ref `:346-360`) has 15 fields. Target `GuildRow.GuildItem`
(`WidgetGuildsList.kt:187-192`) has 4 (`guild`, `selected`, `unread`, `mentionCount`). Missing 11:

`isLurkingGuild`, `folderId`, `isConnectedToVoice`, `hasOngoingApplicationStream`,
`isTargetedForFolderCreation`, `isLastGuildInFolder`, `applicationStatus` (`ApplicationStatus` —
guild join-request state), `isPendingGuild`, `hasActiveStageChannel`, `isConnectedToStageChannel`,
`hasActiveScheduledEvent`.

`GuildListItem.FolderItem` (ref `:78-89`) has 12 fields. Target `GuildRow.Folder`
(`WidgetGuildsList.kt:194-202`) has 7. Missing: `isAnyGuildSelected`, `isAnyGuildConnectedToVoice`,
`isAnyGuildConnectedToStageChannel`, `isTargetedForFolderAddition`, `isPendingGuildsFolder`
(derived, `folderId == ID_PENDING_GUILDS_FOLDER = -7`, ref `:288`).

`GuildListItem` implements `WidgetChannelListUnreads.UnreadItem` on `GuildItem`/`FolderItem`/
`PrivateChannelItem` (ref `:77`, `:345`) so the sidebar drives the floating "new messages ↑/↓" pill
(`WidgetGuildsList.java:508-517`, `setMentionResId(R.string._new)`, `setUnreadsEnabled(false)`).
Target has no such interface and no unreads pill. **MAJOR.**

`StoreState` (ref `WidgetGuildsListViewModel.java:732-800`) carries 23 fields; the target's store
composition (`WidgetGuildsList.kt:67-91`) reads 6 sources. Not sourced at all: `guildJoinRequests`,
`pendingGuilds`, `lurkingGuildIds`, `guildIdsWithActiveStageEvents`,
`guildIdsWithActiveScheduledEvents`, `allApplicationStreamContexts`, `allChannelPermissions`,
`selectedVoiceChannelId`, `unavailableGuilds`, `isNewUser`, `isLeftPanelOpened`, `isOnHomeTab`,
`showHubSparkle`, `channelIds`.

### 2.3 Row rendering gaps (`WidgetGuildsList.kt:238-309` vs `GuildListViewHolder.java`)

`GuildListViewHolder.GuildViewHolder` (ref `:380-785`) has dedicated configuration passes:
`configureApplicationStatus` (`:463`), `configureDraggingAlpha` (`:500`),
`configureGuildIconBackground` (`:512`), `configureGuildIconImage` (`:552`),
`configureGuildIconPositioning` (`:607`), plus `overlayColor`/`overlayColorInFolder` (`:392-393`,
injected at `WidgetGuildListAdapter.java:522-523` from `R.attr.colorBackgroundTertiary` /
`R.attr.primary_600`), `targetedAvatarSize`/`targetedAvatarMargin` (`:394-395`), `defaultAvatarSize`,
`imageRequestSize`.

The target binds these views to a hard-coded `View.GONE`:
- `R.id.guilds_item_voice` — `WidgetGuildsList.kt:259` (voice-connected ring)
- `R.id.guilds_item_application_stream` — `:260` (Go Live indicator)
- `R.id.guilds_item_application_status` — `:261` (join-request pending/approved badge)
- `R.id.guilds_item_event_status` — `:262` (active scheduled-event dot)
- folder `guilds_item_highlight` / `guilds_item_selected` / `guilds_item_voice` — `:287-289`

**Severity: MAJOR** — five distinct sidebar indicators are dead pixels.

Other rendering gaps:
- No in-folder icon geometry: reference shrinks/insets a guild avatar when `folderId != null`
  (`configureGuildIconPositioning`) and uses `isLastGuildInFolder` to round the folder-column bottom.
  Target renders folder children identically to top-level guilds (`WidgetGuildsList.kt:172-176`).
- No `isTargetedForFolderCreation` avatar shrink (ref `GuildViewHolder.isTargetedForFolderCreation()`
  `:759`; sizes at `:394-395`).
- No stage-channel indicator (`hasActiveStageChannel`, `isConnectedToStageChannel`).
- `FolderItemDecoration` (ref, 193 lines — draws the rounded folder "capsule" behind expanded folder
  children, `bg_folder_no_children` / `bg_folder_tintable_no_children` / `bg_folder_with_children`,
  attached at `WidgetGuildsList.java:379-388`) is **MISSING entirely**. Expanded folders in the target
  have no background grouping. **MAJOR.**
- `FolderViewHolder.setColor(Integer)` / `shouldDrawDecoration()` / `getNumChildren()`
  (ref `:292/:296/:270`) — target tints only `folderImage` (`WidgetGuildsList.kt:274`) and never
  coordinates with a decoration.

### 2.4 Drag & reorder gaps

Reference architecture: `GuildsDragAndDropCallback` (`ItemTouchHelper.Callback`) delegates to
`GuildsDragAndDropCallback.Controller` implemented by `WidgetGuildListAdapter`, which emits
`Operation.MoveAbove` / `Operation.MoveBelow` / `Operation.TargetOperation` to
`InteractionListener` → fragment → `WidgetGuildsListViewModel.moveAbove/moveBelow/target/onDrop`.

| Reference behavior | Location | Target |
|---|---|---|
| `chooseDropTarget` picking nearest centerY via `LinearLayoutManager.getTransformedBoundingBox` | `WidgetGuildListAdapter.java:340-358` | **MISSING** — default ItemTouchHelper targeting |
| `getMoveThreshold = 0.2f` | `GuildsDragAndDropCallback.java:64-67` | **MISSING** |
| `interpolateOutOfBoundsScroll` → `signum(dy) * 20` (`DEFAULT_DRAG_SCROLL_SPEED`) | `:82-85` | **MISSING** — no custom edge-scroll speed |
| `TargetPosition` TOP/CENTER/BOTTOM + `NEAR_CENTER_PERCENTAGE = 0.2f` hit zones | `WidgetGuildListAdapter.java:29`, `:280` | Ad-hoc 0.4..0.6 centre band, `GuildFolderDragCallback.kt:32` |
| `isPendingGuildOperation` — blocks any drag involving a pending guild or the pending-guilds folder | `WidgetGuildListAdapter.java:335-337` | **MISSING (BLOCKER)** — pending guilds are draggable |
| `onDragStarted` / `onDragEnded(boolean)` on the view holder (alpha + scale feedback) | `GuildListViewHolder.java:275/:283/:765/:777`, adapter `:412-427` | **MISSING** — no visual drag state |
| `onItemMoved()` → hides open guild/folder context menus mid-drag | `WidgetGuildsList.java:463-473` | **MISSING** (no context menus exist) |
| `target(from,to)` sets `isTargetedForFolderCreation` / `isTargetedForFolderAddition` and re-renders a preview *before* drop | `WidgetGuildsListViewModel.java:1663-1690` | **MISSING** — target combines immediately on hover (`WidgetGuildsList.kt:120-136`), no preview state |
| `untargetCurrentTarget` rollback when the drag leaves the target | ref `move()` `:1358+` | **MISSING** |
| `moveBelow` re-derives destination `folderId` (open folder ⇒ drop *inside*) | `WidgetGuildsListViewModel.java:1550-1571` | Partially: `GuildFolderReorder.addGuildToFolder` refuses when folder is open (`GuildFolderReorder.kt:73`) — **inverted semantics vs reference** |
| `onDrop()` rebuilds `List<StoreGuildsSorted.Entry>` from the item list, calls `setPositions` **and** `RestAPI.updateUserSettings(createWithGuildFolders(...))` | `WidgetGuildsListViewModel.java:1573-1609` | **Persistence MISSING (BLOCKER)** — target's drop handler calls `StoreStream.updateGuildFolders(result)` (`WidgetGuildsList.kt:53`); `GuildFolderPersistenceRequest.kt` exists but is **never constructed or called** anywhere |
| `setItems(items, calculateDiff)` — diff suppressed after a drag (`wasDragResult`) | `WidgetGuildListAdapter.java:450-461`; `ViewState.Loaded.wasDragResult` `:1199` | **MISSING** — target always diffs (`WidgetGuildsList.kt:94`) |
| `recyclerView.itemAnimator = null` | `WidgetGuildsList.java:363` | **MISSING** — default animator will fight drag |

### 2.5 Interaction gaps

`WidgetGuildsListViewModel.onItemClicked` (ref `:1611-1653`) — target `selectGuild` is a bare
`StoreStream.selectGuild(id)` (`WidgetGuildsList.kt:104-106`). Missing branches:
- pending guild ⇒ `MemberVerificationUtils.maybeShowVerificationGate(...)` **(BLOCKER)**
- already-selected guild ⇒ `StoreNavigation.setNavigationPanelAction(CLOSE)` (panel close-on-retap)
- `guild.isHub()` and hub disabled ⇒ toast `discord_u_coming_soon_to_mobile`
- Friends item already selected ⇒ panel CLOSE (target always `selectGuild(0)`, `:244`)
- `UnavailableItem` ⇒ `Event.ShowUnavailableGuilds(count)` ⇒ plural toast `partial_outage_count`
- `CreateItem` ⇒ `Event.ShowCreateGuild` ⇒ `WidgetGuildTemplates.launch(ctx, CreateGuildTrigger.IN_APP, false)`
- `HubItem` ⇒ `StoreDirectories.markDiscordHubClicked()` + `WidgetHubEmailFlow`
- `HelpItem` ⇒ `WidgetNavigationHelp.show(...)`
- Folder toggle ⇒ additionally emits `Event.AnnounceFolderToggleForAccessibility`

**Long-press is entirely absent.** Reference `WidgetGuildsList.onItemLongPressed` (`:438-461`):
computes `PointF(x + dpToPixels(64), y)` and shows `WidgetGuildContextMenu` for a guild or
`WidgetFolderContextMenu` for a folder; `PrivateChannelItem` long-press routes to
`Event.ShowChannelActions(channelId)` ⇒ `WidgetChannelsListItemChannelActions`. Target's adapter sets
**no** `setOnLongClickListener` anywhere. **BLOCKER.**

Also missing from the fragment:
- `AddGuildHint` nested model + `Companion.get()` / `getDismissAction()` (ref `WidgetGuildsList.java:84-217`),
  `configureAddGuildHint` (`:253`), `dismissAddGuildHint` (`:291`), `AnalyticsTracker.showFirstServerTipTutorial` /
  `closeFirstServerTipTutorial`, `WidgetHome.setOnGuildListAddHintCreate` wiring (`:519-526`). **MAJOR.**
- `configureBottomNavSpace()` + `bottomNavViewObserver.observeHeight()` → `adapter.handleBottomNavHeight(h)`
  (ref `:266`, adapter `:406-409`, `SpaceViewHolder.configure(h)`). Target list is occluded by the bottom nav. **MAJOR.**
- `announceFolderToggle` a11y announcement ("collapsed/expanded, <name>", ref `:239-250`) and
  `focusFirstElement()` (`:311`) / `Event.FocusFirstElement`. **MINOR/MAJOR (a11y).**
- `ViewState.Uninitialized` vs `Loaded` — target has no loading state at all; it renders whatever
  `combineLatest` first emits. `hasChannels` flag (ref `ViewState.Loaded.getHasChannels()` `:1191`)
  unused. **MAJOR (no loading/empty/error state).**
- `leadingEdgeThrottle(100ms)` on the store observable (ref `:520`) and `computationLatest().distinctUntilChanged()`
  (ref `:1694-1696`). Target subscribes raw and hops to `activity?.runOnUiThread` manually
  (`WidgetGuildsList.kt:92`) — no throttling, no `distinctUntilChanged`. **MAJOR (perf).**

### 2.6 Ordering / composition gaps
Target `buildRows` (`WidgetGuildsList.kt:139-180`) emits: Home → mention-only DMs → guilds/folders. Full stop.
Reference emits Friends, DMs, Divider, guilds/folders, Unavailable, Create, Hub, Help, Space. Exact ordering
rules are in the non-decompiled `handleStoreState` — **do not guess**; re-derive from the item-type list plus
the layouts, or from a live build.

Also: target hides the folder row's own `guilds_item_selected` unconditionally (`:288`) even though the
reference tracks `isAnyGuildSelected` on `FolderItem`, so a collapsed folder containing the selected
guild shows no selection pill.

### 2.7 Target-only inventions (no reference counterpart)
- `GuildFolderReorder.kt` (200 lines) — a pure-function reorder engine with `require()` validation
  (`:185-188`) that **throws** on duplicate guild IDs or on reordering lurking/pending guilds. The
  reference never throws here; it silently filters. A malformed store snapshot will crash the sidebar.
  **MAJOR (crash risk).**
- `GuildFolderPersistenceRequest.kt` (10 lines) — dead code, never referenced.
- Negative temporary folder IDs allocated from `nextFolderId = -1000L` decrementing
  (`WidgetGuildsList.kt:36`, `:124`) collide with the reference's reserved sentinel IDs
  (`ID_PENDING_GUILDS_FOLDER = -7`, `ID_HUB = -8`, … `ID_UNIDENTIFIED = -1`, ref `GuildListItem.java:20-27`).
  Currently safe only because the target has no sentinel items; will break as soon as they are added. **MINOR now / MAJOR later.**

---

## 3. Per-file findings for the remaining 20 target files

### 3.1 `widgets/guilds/list/GuildFolderDragCallback.kt` (50)
vs `GuildsDragAndDropCallback.java` (121). Missing `Controller` / `DraggableViewHolder` interfaces,
`chooseDropTarget` (delegated to a no-op default), `getMoveThreshold(0.2f)`,
`interpolateOutOfBoundsScroll` (20 px/frame). Emits `MoveBefore/MoveAfter` instead of the reference's
`MoveAbove/MoveBelow/TargetOperation` triad. **MAJOR.**

### 3.2 `widgets/guilds/create/GuildTemplateChannelsView.kt` (72)
vs `GuildTemplateChannelsView.java`. Reasonable 1:1 for the view itself. Notes:
- `notifyDataSetChanged()` on every set (`:36`) — reference uses the same; acceptable.
- The whole `create` cluster it belongs to is missing (see §4), so this view has no caller.
**MINOR** in isolation; **BLOCKER** as an orphan.

### 3.3 `widgets/guilds/invite/ViewInviteSettingsSheet.kt` (147)
vs `ViewInviteSettingsSheet.java` (~470). Target replaced the reference's `viewModel` property
(`setViewModel(WidgetGuildInviteShareViewModel)`, ref `:410`/`:423`) with three loose lambdas
(`onChannelSelected`/`onSettingsChanged`/`onGenerateLinkListener`, `:34-36`). The reference sheet owns
the view model and pushes settings itself. Consequence: no channel-permission filtering, no
"generating" spinner state, no error surface. `getMaxUsesString` (ref `:223`) is inlined. **MAJOR.**

### 3.4 `widgets/guilds/invite/WidgetInviteInfo.kt` (169)
vs `WidgetInviteInfo.java` (~430). Gaps:
- `parseAttributeSet(Context, AttributeSet, int)` (ref `:315`) — styleable attrs not read; the target
  instead hard-codes background + 24 dp padding (`:47-49`). **MINOR.**
- Target `Failure(code, guildLimit)` data class (`:30`) replaces the reference's `Error` type
  (`configureUIFailure(Error)`, ref `:377`); only error code `30001` is special-cased (`:67`). Other
  invite error codes fall through to the generic "expired" copy. **MAJOR.**
- Reference formats via `b.a.k.b.*` (i18n formatter with `**bold**` markdown); target hand-rolls
  `markedText`/`bold` string surgery with literal `"!!{username}!!"`/`"{username}"` probing
  (`:138-154`). Fragile against any string that uses a different placeholder. **MAJOR.**

### 3.5 `widgets/guilds/invite/WidgetInviteModel.kt` (17)
vs `WidgetInviteModel.java`. Field-for-field match (9 fields). One typing deviation: reference `me` is
`MeUser`; target is `com.discord.models.user.User` (`:14`). **MINOR.**
The `Companion` factory in the reference (`:20`) that composes this model from stores is **MISSING**.

### 3.6 `widgets/guildscheduledevent/GuildScheduledEventItemView.kt` (200)
vs `GuildScheduledEventItemView.java` (429). Missing public entry points:
- `configureAsPreview(GuildScheduledEventModel, Channel, UserGuildMember)` (ref `:200`) — used by
  `WidgetPreviewGuildScheduledEvent`. **MISSING.**
- `configureInDirectoryEventList(HubGuildScheduledEventData, …4 listeners)` (ref `:282`). **MISSING.**
- `configureInEventList(GuildScheduledEventListItem.Event, …5 listeners)` (ref `:297`). **MISSING.**
Missing rendering behavior:
- `configureDescription(String, long guildId)` (ref `:66`) routes through
  `GuildScheduledEventDescriptionParser` (markdown, mentions, emoji). Target sets
  `description.text = event.description` raw and applies `LinkifyCompat.addLinks(WEB_URLS)`
  (`:80-82`). **MAJOR** — no markdown/mention/emoji rendering in event descriptions.
- External location name goes through `GuildScheduledEventExternalLocationParser` in the reference
  (`:196`); target sets `standaloneName.text = location.locationName` + Linkify (`:181-184`). **MAJOR.**
- Target `configureInChatList` toggles primary/secondary buttons by reaching into
  `buttons.findViewById(R.id.primary_button)` from outside (`:123-126`) instead of expressing it in the
  `ButtonConfiguration`. Fragile. **MINOR.**

### 3.7 `widgets/guildscheduledevent/GuildScheduledEventDateView.kt` (79)
vs `GuildScheduledEventDateView.java` (238). Reference splits into `configureViaTiming`,
`configureStartingTimeString`, `configureCompleteTimeString`, `configureFinishedEvent`,
`configureTruncatedDateTime`, `getTextColorViaTiming` (ref `:76-196`). Target collapses these into one
`configure` (`:43-72`). Public surface matches (2 overloads + truncate flag). Colour mapping is a
plausible reconstruction, not verified against `getTextColorViaTiming` (ref `:183`) — **flagged as
unverified**, severity **MINOR**.

### 3.8 `widgets/guildscheduledevent/GuildScheduledEventLocationInfo.kt` (41)
vs `GuildScheduledEventLocationInfo.java` (210). Sealed class + `ChannelLocation`/`ExternalLocation` +
`locationName`/`locationIcon`/`locationIconSmall` + `Companion.buildLocationInfo` all present and shaped
like the reference (`:162-207`). Closest to parity in the whole slice. **OK.**

### 3.9 `widgets/guildscheduledevent/GuildScheduledEventBottomButtonView.kt` (77)
vs `GuildScheduledEventBottomButtonView.java` (115). Reference splits into `configureStartEventButton`,
`configureInterestedButton`, `configureShareButton` (`:31-65`); target inlines. Public API
(`configure`, `configureForDetails`) matches. Target hard-forces `extraButton` GONE inside `configure`
(`:59`) then re-enables it in `configureForDetails` (`:70`) — reference does not need the double pass.
**MINOR.**

### 3.10 `widgets/guildscheduledevent/buttonconfiguration/ButtonConfiguration.kt` (23)
vs `ButtonConfiguration.java` (40). All 12 properties + 3 methods present. **OK.**

### 3.11 `widgets/guildscheduledevent/buttonconfiguration/GuildButtonConfiguration.kt` (84)
vs `GuildButtonConfiguration.java` (435). All 14 constructor params match the reference ctor
(`:54`). Suspicious: `secondaryButtonTextDrawableRes` (`:45-49`) returns `ic_bell_16dp` for both the
`isRsvped` and `else` branches — a dead branch, almost certainly wrong (reference distinguishes
filled vs outline bell). **MINOR-to-MAJOR, needs re-read of ref `:257`.** `isExtrasVisible` is a
constant `true` (`:58`) — verify against ref `:341`.

### 3.12 `widgets/guildscheduledevent/buttonconfiguration/DirectoryButtonConfiguration.kt` (55)
vs `DirectoryButtonConfiguration.java` (270). 8 ctor params match ref `:40`. `isAnyButtonVisible` is a
constant `true` (`:37`) — verify against ref `:194`. **MINOR.**

### 3.13 `widgets/guildscheduledevent/GuildEventPromptView.kt` (65)
vs `GuildEventPromptView.java` (63). Matches. The three-ctor duplication (`:20-43`) is ugly but
behaviorally equivalent. **OK.**

### 3.14 `widgets/guildscheduledevent/GuildEventPromptListView.kt` (72)
vs `GuildEventPromptListView.java` (190). Same `ScheduledEventData` nested model, same
`configure(scheduledEventData, onCreateEventClick, onStartStageClick)` signature (ref `:123`). Icon/color/
string choices are a reconstruction; not verified line-by-line against ref `:123-173`. **MINOR.**

### 3.15 `widgets/guildscheduledevent/ScheduledEventUi.kt` (99)
No counterpart in this slice — the reference `GuildScheduledEventTiming` enum and timing/format helpers
live outside `widgets/guildscheduledevent` (referenced as `GuildScheduledEventTiming` by
`GuildScheduledEventDateView.java:76`). Target consolidation is acceptable, but
`GuildScheduledEventModelKt` / `GuildScheduledEventModel` / `GuildScheduledEventUrlUtils` are still
missing (§4). Not a placeholder. **N/A.**

### 3.16 `widgets/roles/RoleIconView.kt` (46)
vs `RoleIconView.java` (192). Target has only `setRoleIconPreview(GuildRole?)` and
`setRoleIconPreview(String?)`. **MISSING** from the reference public API:
- `setRole(GuildRole, Long guildId)` (ref `:145`) — the primary production entry point
- `setRole(Long roleId, Long guildId)` (ref `:189`)
- `showRoleIconToast(Context, GuildRole)` (ref `:97`) — tap-to-explain toast, with the
  `RoleIconView$showRoleIconToast$1` formatter
- `hasIcon` / `hasUnicodeEmoji` / `hasIconOrUnicodeEmoji` predicates (ref `:56/:67/:71`)
- `setIcon(GuildRole)` (ref `:82`)
Every consumer outside `RolesListView` / `GuildRoleSubscriptionMemberPreview` uses `setRole`, so the
target view is unusable in member lists / chat / profiles. **MAJOR.**

### 3.17 `widgets/roles/RolesListView.kt` (70)
vs `RolesListView.java` (98). `roles` property + `updateView(list, defaultColor, guildId)` match
(ref `:42/:46/:51`). Target reads the developer-mode flag via `PreferenceManager` with a literal key
`"CACHE_KEY_DEVELOPER_MODE"` (`:65`) rather than the store; and the `guildId` param is accepted but
never used (`:31`) — the reference passes it into the role-icon toast path. **MINOR.**

### 3.18 `widgets/guild_role_subscriptions/GuildRoleSubscriptionBenefitItemView.kt` (77)
vs `GuildRoleSubscriptionBenefitItemView.java`. Both `configureUI` overloads + `setChannelIcon` present,
styleable attrs read. Closest to parity in this package. **OK.**

### 3.19 `widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt` (129)
vs `GuildSubscriptionRoleImageUploadView.java`. `configureUI(onImageRemoved, onImageChooserClicked)`
stores `onImageRemovedListener` (`:71`) but **nothing ever invokes it** — there is no remove/clear
affordance wired to any view. The reference exposes removal from the edit affordance.
**MAJOR (dead interaction).** `MEDIA_PROXY_SIZES` / `mediaProxySize` / `storeAssetImage` (`:115-128`)
are local re-implementations of `IconUtils`; `storeAssetImage` has no caller. **MINOR.**

### 3.20 `widgets/guild_role_subscriptions/setup/GuildRoleSubscriptionPlanFormatRadioButton.kt` (72)
vs `GuildRoleSubscriptionPlanFormatRadioButton.java`. `Checkable` implemented, both format variants
configured from `format_type` styleable. `toggle()` uses `!isSelected` rather than `!isChecked`
(`:56`) — divergent if the two ever desync. **MINOR.**

### 3.21 `widgets/guild_role_subscriptions/tier/create/GuildRoleSubscriptionMemberPreview.kt` (54)
vs `GuildRoleSubscriptionMemberPreview.java`. `setMemberDesign(color, icon, guildRole)` present.
Depends on `RoleIconView.setRoleIconPreview`, which survives. **OK** — but it is the only surviving
piece of an 8-class tier-creation flow.

---

## 4. Coverage table — every reference class → target

### `widgets/guilds`
| Reference | Target | Sev |
|---|---|---|
| `WidgetGuildFolderSettings` | MISSING | BLOCKER |
| `WidgetGuildFolderSettingsViewModel` | MISSING | BLOCKER |
| `WidgetGuildSelector` | MISSING | MAJOR |
| `contextmenu/WidgetGuildContextMenu` | MISSING | BLOCKER |
| `contextmenu/GuildContextMenuViewModel` | MISSING | BLOCKER |
| `contextmenu/WidgetFolderContextMenu` | MISSING | BLOCKER |
| `contextmenu/FolderContextMenuViewModel` | MISSING | BLOCKER |
| `create/GuildTemplateChannelsView` | `create/GuildTemplateChannelsView.kt` | OK |
| `create/ChannelTemplate` | MISSING | MAJOR |
| `create/CreateGuildTrigger` | MISSING | MAJOR |
| `create/CreationIntentArgs` | MISSING | MAJOR |
| `create/GuildCreateCloneViews` | MISSING | MAJOR |
| `create/StockGuildTemplate` | MISSING | MAJOR |
| `create/StockGuildTemplateKt` | MISSING | MAJOR |
| `create/WidgetCreationIntent` | MISSING | BLOCKER |
| `create/WidgetGuildClone` | MISSING | MAJOR |
| `create/WidgetGuildCreate` | MISSING | BLOCKER |
| `create/WidgetGuildCreateViewModel` | MISSING | BLOCKER |
| `invite/ViewInviteSettingsSheet` | `invite/ViewInviteSettingsSheet.kt` | MAJOR gaps |
| `invite/WidgetInviteInfo` | `invite/WidgetInviteInfo.kt` | MAJOR gaps |
| `invite/WidgetInviteModel` | `invite/WidgetInviteModel.kt` | MINOR gaps |
| `invite/GuildInvite` | MISSING | MAJOR |
| `invite/GuildInviteSettingsViewModel` | MISSING | BLOCKER |
| `invite/GuildInviteShareSheetFeatureFlag` | MISSING | MINOR |
| `invite/GuildInviteShareSheetViewModel` | MISSING | BLOCKER |
| `invite/GuildInviteUiHelperKt` | MISSING | MAJOR |
| `invite/GuildInviteViewModel` | MISSING | BLOCKER |
| `invite/InviteGenerator` | MISSING | BLOCKER |
| `invite/InviteJoinHelper` | MISSING | BLOCKER |
| `invite/InviteSuggestion` | MISSING | MAJOR |
| `invite/InviteSuggestionItem` | MISSING | MAJOR |
| `invite/InviteSuggestionItemV2` | MISSING | MAJOR |
| `invite/InviteSuggestionsAdapter` | MISSING | MAJOR |
| `invite/InviteSuggestionsManager` | MISSING | MAJOR |
| `invite/PrivateChannelAdapter` | MISSING | MAJOR |
| `invite/TargetChannelSelector` | MISSING | MAJOR |
| `invite/WidgetGuildInvite` | MISSING | BLOCKER |
| `invite/WidgetGuildInviteSettings` | MISSING | BLOCKER |
| `invite/WidgetGuildInviteShare` | MISSING | BLOCKER |
| `invite/WidgetGuildInviteShareCompact` | MISSING | MAJOR |
| `invite/WidgetGuildInviteShareEmptySuggestions` | MISSING | MAJOR |
| `invite/WidgetGuildInviteShareSheet` | MISSING | BLOCKER |
| `invite/WidgetGuildInviteShareViewModel` | MISSING | BLOCKER |
| `join/BaseChannelViewHolder` | MISSING | MAJOR |
| `join/ChannelItem` | MISSING | MAJOR |
| `join/ChannelViewHolder` | MISSING | MAJOR |
| `join/CaptchaArgs` | MISSING | MAJOR |
| `join/GuildJoinHelperKt` | MISSING | BLOCKER |
| `join/InviteArgs` | MISSING | MAJOR |
| `join/JoinArgs` | MISSING | MAJOR |
| `join/WidgetGuildJoin` | MISSING | BLOCKER |
| `join/WidgetGuildJoinCaptchaBottomSheet` | MISSING | BLOCKER |
| `join/WidgetGuildJoinCaptchaBottomSheetKt` | MISSING | MAJOR |
| `join/WidgetGuildWelcomeSheet` | MISSING | BLOCKER |
| `join/WidgetGuildWelcomeSheetChannelAdapter` | MISSING | MAJOR |
| `join/WidgetGuildWelcomeSheetViewModel` | MISSING | BLOCKER |
| `leave/WidgetLeaveGuildDialog` | MISSING | BLOCKER |
| `leave/LeaveGuildDialogViewModel` | MISSING | BLOCKER |
| `list/WidgetGuildsList` | `list/WidgetGuildsList.kt` | BLOCKER gaps (§2) |
| `list/GuildListItem` | inlined `GuildRow` (`WidgetGuildsList.kt:185`) | BLOCKER gaps |
| `list/GuildListViewHolder` | inlined `GuildAdapter.Holder` (`:311`) | BLOCKER gaps |
| `list/WidgetGuildListAdapter` | inlined `GuildAdapter` (`:205`) | BLOCKER gaps |
| `list/GuildsDragAndDropCallback` | `list/GuildFolderDragCallback.kt` | MAJOR gaps |
| `list/WidgetGuildsListViewModel` | MISSING (logic inlined in fragment) | BLOCKER |
| `list/FolderItemDecoration` | MISSING | MAJOR |
| `profile/WidgetGuildProfileSheet` | MISSING | BLOCKER |
| `profile/WidgetGuildProfileSheetViewModel` | MISSING | BLOCKER |
| `profile/WidgetGuildProfileSheetEmojisAdapter` | MISSING | MAJOR |
| `profile/BaseEmojiViewHolder` | MISSING | MAJOR |
| `profile/EmojiItem` | MISSING | MAJOR |
| `profile/EmojiViewHolder` | MISSING | MAJOR |
| `profile/MoreEmojiViewHolder` | MISSING | MAJOR |
| `profile/WidgetChangeGuildIdentity` | MISSING | BLOCKER |
| `profile/WidgetChangeGuildIdentityViewModel` | MISSING | BLOCKER |
| `profile/WidgetPublicAnnouncementProfileSheet` | MISSING | MAJOR |

### `widgets/guildscheduledevent`
| Reference | Target | Sev |
|---|---|---|
| `GuildEventPromptListView` | `GuildEventPromptListView.kt` | MINOR |
| `GuildEventPromptView` | `GuildEventPromptView.kt` | OK |
| `GuildScheduledEventBottomButtonView` | `GuildScheduledEventBottomButtonView.kt` | MINOR |
| `GuildScheduledEventDateView` | `GuildScheduledEventDateView.kt` | MINOR |
| `GuildScheduledEventItemView` | `GuildScheduledEventItemView.kt` | MAJOR gaps |
| `GuildScheduledEventLocationInfo` | `GuildScheduledEventLocationInfo.kt` | OK |
| `buttonconfiguration/ButtonConfiguration` | `buttonconfiguration/ButtonConfiguration.kt` | OK |
| `buttonconfiguration/GuildButtonConfiguration` | `.../GuildButtonConfiguration.kt` | MINOR |
| `buttonconfiguration/DirectoryButtonConfiguration` | `.../DirectoryButtonConfiguration.kt` | MINOR |
| `EventDetailsRsvpUsersFetchState` | MISSING | MAJOR |
| `EventDetailsSection` | MISSING | MAJOR |
| `GuildIdsWithActiveEventsModel` | MISSING | MAJOR |
| `GuildScheduledEventAPI` | MISSING | BLOCKER |
| `GuildScheduledEventDescriptionParser` | MISSING | MAJOR |
| `GuildScheduledEventDescriptionParserKt` | MISSING | MAJOR |
| `GuildScheduledEventDetailsArgs` | MISSING | BLOCKER |
| `GuildScheduledEventDetailsSource` | MISSING | MAJOR |
| `GuildScheduledEventDetailsViewModel` | MISSING | BLOCKER |
| `GuildScheduledEventDirectoryAssociationState` | MISSING | MAJOR |
| `GuildScheduledEventDirectoryAssociationViewModel` | MISSING | MAJOR |
| `GuildScheduledEventExternalLocationParser` | MISSING | MAJOR |
| `GuildScheduledEventExternalLocationParserKt` | MISSING | MAJOR |
| `GuildScheduledEventListAdapter` | MISSING | BLOCKER |
| `GuildScheduledEventListItem` | MISSING | BLOCKER |
| `GuildScheduledEventListViewModel` | MISSING | BLOCKER |
| `GuildScheduledEventModel` | MISSING | BLOCKER |
| `GuildScheduledEventModelKt` | MISSING | MAJOR |
| `GuildScheduledEventPickerDate` | MISSING | BLOCKER |
| `GuildScheduledEventPickerDateTime` | MISSING | BLOCKER |
| `GuildScheduledEventPickerTime` | MISSING | BLOCKER |
| `GuildScheduledEventRsvpUserListAdapter` | MISSING | MAJOR |
| `GuildScheduledEventRsvpUserListItem` | MISSING | MAJOR |
| `GuildScheduledEventSettingsViewModel` | MISSING | BLOCKER |
| `GuildScheduledEventUrlUtils` | MISSING | MAJOR |
| `GuildScheduledEventUser` | MISSING | MAJOR |
| `PreviewGuildScheduledEventViewModel` | MISSING | MAJOR |
| `WidgetEndGuildScheduledEventBottomSheet` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventCreateSuccess` | MISSING | MAJOR |
| `WidgetGuildScheduledEventCreateSuccessViewModel` | MISSING | MAJOR |
| `WidgetGuildScheduledEventDetailsBottomSheet` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventDetailsExtrasBottomSheet` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventListBottomSheet` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventLocationSelect` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventLocationSelectViewModel` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventSettings` | MISSING | BLOCKER |
| `WidgetGuildScheduledEventUpsellBottomSheet` | MISSING | MAJOR |
| `WidgetPreviewGuildScheduledEvent` | MISSING | MAJOR |

### `widgets/guild_role_subscriptions`
| Reference | Target | Sev |
|---|---|---|
| `GuildRoleSubscriptionBenefitItemView` | `GuildRoleSubscriptionBenefitItemView.kt` | OK |
| `GuildSubscriptionRoleImageUploadView` | `GuildSubscriptionRoleImageUploadView.kt` | MAJOR (dead remove listener) |
| `setup/GuildRoleSubscriptionPlanFormatRadioButton` | `setup/GuildRoleSubscriptionPlanFormatRadioButton.kt` | MINOR |
| `tier/create/GuildRoleSubscriptionMemberPreview` | `tier/create/GuildRoleSubscriptionMemberPreview.kt` | OK |
| `GuildRoleSubscriptionUtils` | MISSING | BLOCKER |
| `GuildRoleSubscriptionUtilsKt` | MISSING | MAJOR |
| `GuildRoleSubscriptionsFeatureFlag` | MISSING | MAJOR |
| `PriceTierPickerAdapter` | MISSING | BLOCKER |
| `PriceTierPickerBottomSheetViewModel` | MISSING | BLOCKER |
| `PriceTierViewHolder` | MISSING | BLOCKER |
| `RoleSubscriptionsLinkingUtil` | MISSING | MAJOR |
| `WidgetPriceTierPickerBottomSheet` | MISSING | BLOCKER |
| `entrypoint/WidgetGuildRoleSubscriptionEntryPoint` | MISSING | BLOCKER |
| `entrypoint/WidgetGuildRoleSubscriptionEntryPointViewModel` | MISSING | BLOCKER |
| `setup/GuildRoleSubscriptionPlanDetailsViewModel` | MISSING | BLOCKER |
| `setup/GuildRoleSubscriptionPlanSetupViewModel` | MISSING | BLOCKER |
| `setup/WidgetGuildRoleSubscriptionPlanDetails` | MISSING | BLOCKER |
| `setup/WidgetGuildRoleSubscriptionPlanFormat` | MISSING | BLOCKER |
| `setup/WidgetGuildRoleSubscriptionPlanSetup` | MISSING | BLOCKER |
| `tier/GuildRoleSubscriptionTierAdapter` | MISSING | BLOCKER |
| `tier/GuildRoleSubscriptionTierAdapterItem` | MISSING | BLOCKER |
| `tier/GuildRoleSubscriptionTierItemDecoration` | MISSING | MAJOR |
| `tier/GuildRoleSubscriptionTierListItemGeneratorKt` | MISSING | BLOCKER |
| `tier/GuildRoleSubscriptionTierViewHolder` | MISSING | BLOCKER |
| `tier/create/CreateGuildRoleSubscriptionTierViewModel` | MISSING | BLOCKER |
| `tier/create/GuildRoleSubscriptionReviewViewModel` | MISSING | BLOCKER |
| `tier/create/GuildRoleSubscriptionTierViewModel` | MISSING | BLOCKER |
| `tier/create/WidgetCreateGuildRoleSubscriptionTier` | MISSING | BLOCKER |
| `tier/create/WidgetGuildRoleSubscriptionReview` | MISSING | BLOCKER |
| `tier/create/WidgetGuildRoleSubscriptionTierDesign` | MISSING | BLOCKER |
| `tier/create/WidgetGuildRoleSubscriptionTierDetails` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionBenefitAdapter` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionBenefitAdapterItem` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionBenefitListItemGeneratorKt` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionBenefitViewHolder` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionTierBenefitListType` | MISSING | MAJOR |
| `tier/create/benefits/GuildRoleSubscriptionTierBenefitViewModel` | MISSING | BLOCKER |
| `tier/create/benefits/GuildRoleSubscriptionTierBenefitsViewModel` | MISSING | BLOCKER |
| `tier/create/benefits/WidgetGuildRoleSubscriptionTierBenefit` | MISSING | BLOCKER |
| `tier/create/benefits/WidgetGuildRoleSubscriptionTierBenefits` | MISSING | BLOCKER |
| `tier/model/Benefit` | MISSING | BLOCKER |
| `tier/model/GuildRoleSubscriptionTier` | MISSING | BLOCKER |

### `widgets/roles`
| Reference | Target | Sev |
|---|---|---|
| `RoleIconView` | `roles/RoleIconView.kt` | MAJOR (5 public methods missing) |
| `RolesListView` | `roles/RolesListView.kt` | MINOR |

---

## 5. MISSING grouped by feature cluster

| Cluster | Missing classes | Status |
|---|---|---|
| **Guild sidebar (list)** — item types, view holders, folder decoration, view model | 4 of 7 (`GuildListItem`, `GuildListViewHolder`, `WidgetGuildsListViewModel`, `FolderItemDecoration`) + degraded rewrites of the other 3 | **BLOCKER** |
| **Guild context menu / folder context menu** | 4 | **BLOCKER** — long-press sidebar action does nothing |
| **Guild folder settings** (rename, recolor) | 2 | **BLOCKER** |
| **Guild creation** (create, clone, templates, creation intent) | 10 of 11 | **BLOCKER** |
| **Join guild** (join fragment, captcha sheet, welcome sheet + adapter/VM, args) | 13 of 13 | **BLOCKER** |
| **Invite** (share, share sheet, compact share, settings, suggestions, generator, join helper, target-channel selector) | 22 of 25 | **BLOCKER** |
| **Guild profile sheet** (profile sheet + VM, emoji adapter/holders, change identity, public announcement) | 10 of 10 | **BLOCKER** |
| **Leave guild dialog** | 2 of 2 | **BLOCKER** |
| **Guild selector** | 1 | **MAJOR** |
| **Scheduled event details / extras / end / list bottom sheets** | 5 | **BLOCKER** |
| **Scheduled event create / settings / location select / date-time pickers** | 8 | **BLOCKER** |
| **Scheduled event list adapter + items + RSVP user list** | 6 | **BLOCKER** |
| **Scheduled event API, model, description/location parsers, url utils** | 10 | **BLOCKER** |
| **Scheduled event preview / upsell / directory association** | 6 | **MAJOR** |
| **Role subscription setup flow** (plan format/details/setup + 3 VMs) | 5 of 6 | **BLOCKER** |
| **Role subscription tier list** (adapter, items, holder, decoration, generator) | 5 of 5 | **BLOCKER** |
| **Role subscription tier create** (create/design/details/review + 3 VMs) | 7 of 8 | **BLOCKER** |
| **Role subscription tier benefits** (adapter, items, holder, 2 VMs, 2 fragments, list type, generator) | 9 of 9 | **BLOCKER** |
| **Role subscription price tier picker** (sheet, VM, adapter, holder) | 4 | **BLOCKER** |
| **Role subscription entry point + utils + feature flag + linking util** | 6 | **BLOCKER** |
| **Role subscription tier models** (`Benefit`, `GuildRoleSubscriptionTier`) | 2 | **BLOCKER** |

---

## 6. Placeholder / stub / dead-code flags

| File | Finding | Sev |
|---|---|---|
| `widgets/guilds/list/GuildFolderPersistenceRequest.kt:1-10` | Complete file, zero call sites. Guild-folder ordering is never persisted to the REST API (ref does it in `WidgetGuildsListViewModel.java:1596-1606`). | **BLOCKER** |
| `widgets/guilds/list/WidgetGuildsList.kt:259-262` | Four indicator views bound to a hard-coded `View.GONE` — a stub, not behavior. | **MAJOR** |
| `widgets/guilds/list/WidgetGuildsList.kt:287-289` | Three more folder-row views hard-coded `GONE`, incl. folder selection state. | **MAJOR** |
| `widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt:71` | `onImageRemovedListener` assigned, never invoked — no remove affordance. | **MAJOR** |
| `widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt:124-127` | `storeAssetImage(...)` has no call sites. | **MINOR** |
| `widgets/guildscheduledevent/buttonconfiguration/GuildButtonConfiguration.kt:45-49` | `when` with two identical branches (`ic_bell_16dp`) — dead branch, likely a lost distinction. | **MAJOR** |
| `widgets/roles/RolesListView.kt:31` | `guildId` parameter accepted, never used. | **MINOR** |
| `widgets/guilds/create/GuildTemplateChannelsView.kt` | Correct in isolation but orphaned — the entire guild-creation flow that consumes it is absent. | **BLOCKER (context)** |
| `widgets/guildscheduledevent/*` (9 view files) | All correct-ish leaf views, all orphaned — no fragment, sheet, adapter, or VM in the target ever instantiates them. | **BLOCKER (context)** |
| `widgets/guild_role_subscriptions/*` (4 view files) | Same: orphaned leaf views, no host flow. | **BLOCKER (context)** |

No file in the slice is a literal `TODO()`/empty-body stub. The stub-ness here is structural: **22 of 24
target files are leaf custom Views with no host Fragment/ViewModel/adapter**, plus one monolithic
sidebar fragment that reimplements ~13 % of a 4825-line reference subsystem.

---

## 7. Top gaps, ranked

1. **BLOCKER** — No guild/folder long-press context menus (`WidgetGuildContextMenu`,
   `WidgetFolderContextMenu` + 2 VMs). Target adapter sets no long-click listener at all
   (`WidgetGuildsList.kt:238-309`); ref `WidgetGuildsList.java:438-461`.
2. **BLOCKER** — Guild folder reorder is never persisted server-side.
   `GuildFolderPersistenceRequest.kt` is dead; ref calls
   `RestAPI.updateUserSettings(createWithGuildFolders(...))` at `WidgetGuildsListViewModel.java:1596`.
3. **BLOCKER** — 6 of 10 sidebar item types absent: Divider, Unavailable, **Create-server (+)**, Help,
   Hub, BottomNavSpace (`GuildListItem.java:28-37` vs `WidgetGuildsList.kt:221-226`).
4. **BLOCKER** — `WidgetGuildsListViewModel` does not exist; 1700 lines of state machine
   (targeting preview, untarget rollback, move validation, event bus) collapsed into a fragment.
5. **BLOCKER** — Pending/lurking guilds are draggable and clickable without the member-verification
   gate. Ref guards via `isPendingGuildOperation` (`WidgetGuildListAdapter.java:335`) and
   `MemberVerificationUtils.maybeShowVerificationGate` (`WidgetGuildsListViewModel.java:1618`).
6. **BLOCKER** — Entire join-guild cluster missing (13 classes): invite accept, captcha bottom sheet,
   guild welcome sheet + channel adapter.
7. **BLOCKER** — Entire invite-share cluster missing (22 of 25): share sheet, suggestions adapter,
   invite generator, target-channel selector.
8. **BLOCKER** — Entire guild profile sheet cluster missing (10): sheet, VM, emoji adapter/holders,
   change-guild-identity.
9. **BLOCKER** — Entire scheduled-event flow missing (38 of 47): details sheet, list sheet, create,
   settings, location select, 3 date/time pickers, list adapter + items, RSVP user list, API layer.
10. **BLOCKER** — Entire role-subscription flow missing (38 of 42): setup, tier list, tier create,
    benefits, price-tier picker, entry point, models.
11. **MAJOR** — 5 sidebar indicators are hard-coded `GONE`: voice-connected, Go Live stream,
    application/join-request status, active-scheduled-event, folder highlight
    (`WidgetGuildsList.kt:259-262`, `:287-289`).
12. **MAJOR** — `FolderItemDecoration` missing: expanded folders render with no capsule background
    (ref attached at `WidgetGuildsList.java:379-388`).
13. **MAJOR** — No drop-target *preview*: reference sets `isTargetedForFolderCreation` /
    `isTargetedForFolderAddition` and re-renders before commit (`WidgetGuildsListViewModel.java:1663`);
    target mutates the real list on hover (`WidgetGuildsList.kt:120-136`).
14. **MAJOR** — `GuildFolderReorder.validate` throws `IllegalArgumentException` on duplicate IDs or on
    lurking/pending guilds (`GuildFolderReorder.kt:185-188`); the reference never throws. Crash risk on
    a bad store snapshot.
15. **MAJOR** — `RoleIconView` missing `setRole(GuildRole, Long)`, `setRole(Long, Long)`,
    `showRoleIconToast`, and the 3 predicates (ref `RoleIconView.java:97/:145/:189/:56/:67/:71`) —
    the view is unusable outside its two current call sites.

Runners-up: no `AddGuildHint` first-server tutorial + analytics; no bottom-nav height space item; no
`WidgetChannelListUnreads` "new messages" pill wiring; no loading/`Uninitialized` view state; no
`leadingEdgeThrottle(100ms)`/`distinctUntilChanged` on the store stream; event descriptions and
external locations rendered as raw text instead of via
`GuildScheduledEventDescriptionParser` / `GuildScheduledEventExternalLocationParser`.
