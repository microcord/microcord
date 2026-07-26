# Parity audit — `com.discord.widgets.user` + `com.discord.widgets.friends` (PLAN Phase 8)

- **TARGET** (Kotlin reconstruction): `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\widgets\{user,friends}`
- **REFERENCE** (JADX, Discord Android 126.21, read-only): `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\widgets\{user,friends}`
- Method: every target file read in full; reference classes enumerated and their nested ViewState / Item / Event types, section wiring, adapter row types, and dispatch logic extracted.

## 0. Headline numbers

| | reference non-synthetic | target files | at/near parity | partial | placeholder/divergent | MISSING |
|---|---|---|---|---|---|---|
| `widgets/user/**` | 55 | 17 | 4 | 14 | 1 | 36 |
| `widgets/friends/**` | 13 | 5 | 2 | 8 | 1 | 2 |
| **total** | **68** | **22** | **6** | **22** | **2** | **38** |

Line volume: target 2 718 LOC vs reference 24 801 LOC (≈11 %).

Whole feature clusters with **zero** target code: rich presence (9 classes), moderation dialogs (ban/kick/prune, 5), account/email/phone verification (6), call launcher (1), global-search scoring + guild rail + frecency (5), user-sheet view model + store state (3), nearby friend scan (3).

---

## 1. Reference class inventory, grouped by subpackage

### `user/` root — 16
`Badge`, `UserMutualGuildsManager`, `UserNameFormatterKt`, `WidgetBanUser`, `WidgetKickUser`, `WidgetPruneUsers`, `WidgetPruneUsersViewModel`, `WidgetUserMentions`, `WidgetUserMentionsViewModel`, `WidgetUserMutualFriends`, `WidgetUserMutualGuilds`, `WidgetUserPasswordVerify`, `WidgetUserSetCustomStatus`, `WidgetUserSetCustomStatusViewModel`, `WidgetUserStatusSheet`, `WidgetUserStatusSheetViewModel`

### `user/account/` — 2
`WidgetUserAccountVerify`, `WidgetUserAccountVerifyBase`

### `user/calls/` — 1
`PrivateCallLauncher`

### `user/email/` — 2
`WidgetUserEmailUpdate`, `WidgetUserEmailVerify`

### `user/phone/` — 2
`WidgetUserPhoneManage`, `WidgetUserPhoneVerify`

### `user/presence/` — 9
`ModelRichPresence`, `ModelStageRichPresence`, `ViewHolderUserRichPresence`, `ViewHolderUserRichPresenceKt`, `ViewHolderGameRichPresence`, `ViewHolderMusicRichPresence`, `ViewHolderPlatformRichPresence`, `ViewHolderStageChannelRichPresence`, `ViewHolderStreamRichPresence`

### `user/profile/` — 8
`UserProfileHeaderView`, `UserProfileHeaderViewModel`, `UserProfileAdminView`, `UserProfileConnectionsView`, `UserStatusPresenceCustomView`, `WidgetUserProfileEmptyListItem`, `RightToLeftGridLayoutManager`, `DraweeSpanStringBuilderExtensionsKt`

### `user/search/` — 10
`WidgetGlobalSearch`, `WidgetGlobalSearchModel`, `WidgetGlobalSearchAdapter`, `WidgetGlobalSearchDialog`, `WidgetGlobalSearchGuildsAdapter`, `WidgetGlobalSearchGuildsModel`, `WidgetGlobalSearchScoreStrategy`, `WidgetGlobalSearchDismissModel`, `ViewGlobalSearchItem`, `ChannelFrecencyTracker`

### `user/usersheet/` — 5
`WidgetUserSheet`, `WidgetUserSheetViewModel`, `WidgetUserSheetViewModelKt`, `WidgetUserSheetViewModelStoreState`, `UserProfileVoiceSettingsView`

### `friends/` — 13
`FriendsListViewModel`, `WidgetFriendsList`, `WidgetFriendsListAdapter`, `WidgetFriendsListLoadingAdapter`, `FriendsListLoadingViewHolder`, `WidgetFriendsAdd`, `WidgetFriendsAddById`, `WidgetFriendsAddUserAdapter`, `WidgetFriendsFindNearby`, `WidgetFriendsFindNearbyViewModel`, `NearbyManager`, `EmptyFriendsStateView`, `SuggestedFriendView`

---

## 2. Coverage table

Status legend: **OK** = behaviourally equivalent · **PART** = present but incomplete · **DIV** = present but implements something else · **STUB** = placeholder · **MISSING**.

### user/ root

| Reference | Target | Status | Sev |
|---|---|---|---|
| `Badge` | `user/Badge.kt` | PART — data class only; `Badge.Companion.getBadgesForUser()` + `onBadgeClick()` not ported | MAJOR |
| `UserMutualGuildsManager` | — | MISSING | MAJOR |
| `UserNameFormatterKt` | — | MISSING (`getSpannableForUserNameWithDiscrim` two-tone name span) | MAJOR |
| `WidgetBanUser` | — | MISSING | BLOCKER |
| `WidgetKickUser` | — | MISSING | BLOCKER |
| `WidgetPruneUsers` | — | MISSING | MAJOR |
| `WidgetPruneUsersViewModel` | — | MISSING | MAJOR |
| `WidgetUserMentions` | `user/WidgetUserMentions.kt` | PART | BLOCKER |
| `WidgetUserMentionsViewModel` | — | MISSING | MAJOR |
| `WidgetUserMutualFriends` | `user/WidgetUserMutualFriends.kt` | PART | MINOR |
| `WidgetUserMutualGuilds` | `user/WidgetUserMutualGuilds.kt` | PART | MINOR |
| `WidgetUserPasswordVerify` | — | MISSING | MAJOR |
| `WidgetUserSetCustomStatus` | `user/WidgetUserSetCustomStatus.kt` | PART | MAJOR |
| `WidgetUserSetCustomStatusViewModel` | — | MISSING (form logic partly inlined) | MAJOR |
| `WidgetUserStatusSheet` | `user/WidgetUserStatusSheet.kt` | PART | MINOR |
| `WidgetUserStatusSheetViewModel` | — | MISSING | MAJOR |

### user/account, user/email, user/phone, user/calls — **cluster fully MISSING**

| Reference | Target | Status | Sev |
|---|---|---|---|
| `account/WidgetUserAccountVerify` | — | MISSING | MAJOR |
| `account/WidgetUserAccountVerifyBase` | — | MISSING | MAJOR |
| `email/WidgetUserEmailUpdate` | — | MISSING | MAJOR |
| `email/WidgetUserEmailVerify` | — | MISSING | MAJOR |
| `phone/WidgetUserPhoneManage` | — | MISSING | MAJOR |
| `phone/WidgetUserPhoneVerify` | — | MISSING | MAJOR |
| `calls/PrivateCallLauncher` | — | MISSING | BLOCKER |

### user/presence — **cluster fully MISSING**

| Reference | Target | Status | Sev |
|---|---|---|---|
| `ModelRichPresence` | — | MISSING | BLOCKER |
| `ModelStageRichPresence` | — | MISSING | MAJOR |
| `ViewHolderUserRichPresence` (+`Kt`) | — | MISSING | BLOCKER |
| `ViewHolderGameRichPresence` | — | MISSING | BLOCKER |
| `ViewHolderMusicRichPresence` | — | MISSING | MAJOR |
| `ViewHolderPlatformRichPresence` | — | MISSING | MAJOR |
| `ViewHolderStageChannelRichPresence` | — | MISSING | MAJOR |
| `ViewHolderStreamRichPresence` | — | MISSING | BLOCKER |

### user/profile

| Reference | Target | Status | Sev |
|---|---|---|---|
| `UserProfileHeaderView` | `profile/UserProfileHeaderView.kt` | PART | BLOCKER |
| `UserProfileHeaderViewModel` | `profile/UserProfileHeaderViewModel.kt` | PART — ViewState.Loaded only; no `StoreState`, no `Uninitialized`, no `observeViewState()`, no `Companion.bind()` | MAJOR |
| `UserProfileAdminView` | `profile/UserProfileAdminView.kt` | OK (13/13 ViewState fields, 8 callbacks, all visibility + label/icon toggles match ref `UserProfileAdminView.java:31-44,429`) | — |
| `UserProfileConnectionsView` | `profile/UserProfileConnectionsView.kt` | OK (3/3 ViewState fields, adapter + mutual rows match ref `:190-193,319`) | MINOR |
| `UserStatusPresenceCustomView` | `profile/UserStatusPresenceCustomView.kt` | OK | — |
| `WidgetUserProfileEmptyListItem` | inlined `EmptyHolder` in the two mutual-* fragments | PART — not a shared class; no `MGRecyclerDataPayload` item | MINOR |
| `RightToLeftGridLayoutManager` | — | MISSING (target uses plain `GridLayoutManager(…, reverseLayout=true)`) | MINOR |
| `DraweeSpanStringBuilderExtensionsKt` | — | MISSING (`setAvatar` inline-avatar span) | MAJOR |

### user/search

| Reference | Target | Status | Sev |
|---|---|---|---|
| `WidgetGlobalSearch` | `search/WidgetGlobalSearch.kt` | PART | MAJOR |
| `WidgetGlobalSearchModel` | `search/WidgetGlobalSearchModel.kt` | PART — only `MatchedResult`/`ItemChannel`/`ItemGuild`/`ItemUser`; missing `ItemHeader`, `ChannelContext`, `UsersContext`, `SearchContext`, `SEARCH_TYPE_*`, `getForNav`/`getForSend` | BLOCKER |
| `WidgetGlobalSearchAdapter` | inlined `Adapter` in `WidgetGlobalSearch.kt:109` | PART — no `HeaderViewHolder`, no `onUpdated`, no long-press | MAJOR |
| `WidgetGlobalSearchDialog` | `search/WidgetGlobalSearchDialog.kt` | **DIV** — target implements a DM quick-switcher + group-DM recipient picker; reference is a thin `AppDialog` host for `WidgetGlobalSearch` with dismiss-VM wiring | MAJOR |
| `WidgetGlobalSearchGuildsAdapter` | — | MISSING (guild rail: `ItemGuild`/`ItemDirectMessage`/`ItemDivider`) | MAJOR |
| `WidgetGlobalSearchGuildsModel` | — | MISSING | MAJOR |
| `WidgetGlobalSearchScoreStrategy` | — | MISSING | BLOCKER |
| `WidgetGlobalSearchDismissModel` | — | MISSING | MINOR |
| `ViewGlobalSearchItem` | `search/ViewGlobalSearchItem.kt` | PART | MINOR |
| `ChannelFrecencyTracker` | — | MISSING | MAJOR |

### user/usersheet

| Reference | Target | Status | Sev |
|---|---|---|---|
| `WidgetUserSheet` | `usersheet/WidgetUserSheet.kt` | PART | BLOCKER |
| `WidgetUserSheetViewModel` (2 272 LOC) | — | MISSING | BLOCKER |
| `WidgetUserSheetViewModelStoreState` | — | MISSING | BLOCKER |
| `WidgetUserSheetViewModelKt` | — | MISSING | MINOR |
| `UserProfileVoiceSettingsView` | `usersheet/UserProfileVoiceSettingsView.kt` | OK (ViewState 3/3, three callbacks, visibility + progress logic match ref `:32-35,179-203`) | — |

### friends

| Reference | Target | Status | Sev |
|---|---|---|---|
| `FriendsListViewModel` | `friends/FriendsUi.kt` (`FriendsListBuilder`) | PART | BLOCKER |
| `WidgetFriendsList` | `friends/WidgetFriendsList.kt` | PART | MAJOR |
| `WidgetFriendsListAdapter` | inlined `FriendsAdapter` | PART | MAJOR |
| `WidgetFriendsListLoadingAdapter` | inlined `LoadingAdapter` (`WidgetFriendsList.kt:101`) | PART — fixed count 8, no shimmer variance | MINOR |
| `FriendsListLoadingViewHolder` | folded into above | PART | MINOR |
| `WidgetFriendsAdd` | `friends/WidgetFriendsAdd.kt:17` | OK (2-tab SimplePager Username/Nearby, title) — missing `Companion.show()` + `openModal` analytics | MINOR |
| `WidgetFriendsAddById` | `friends/WidgetFriendsAdd.kt:32` | PART | MAJOR |
| `WidgetFriendsAddUserAdapter` | — | MISSING | MAJOR |
| `WidgetFriendsFindNearby` | `friends/WidgetFriendsAdd.kt:97` | **STUB** | BLOCKER |
| `WidgetFriendsFindNearbyViewModel` | — | MISSING | MAJOR |
| `NearbyManager` | — | MISSING | BLOCKER |
| `EmptyFriendsStateView` | `friends/EmptyFriendsStateView.kt` | OK | — |
| `SuggestedFriendView` | `friends/SuggestedFriendView.kt` | OK (avatar/username/publicName/send/decline + media-proxy sizing) | — |

### MISSING grouped by feature cluster

1. **Rich presence rendering** (8+1): all `presence/*`. No activity card at all in the user sheet.
2. **Guild moderation dialogs** (5): `WidgetBanUser`, `WidgetKickUser`, `WidgetPruneUsers(+ViewModel)`, `WidgetUserPasswordVerify`.
3. **Account/email/phone verification** (6): all of `account/`, `email/`, `phone/`.
4. **Calls** (1): `PrivateCallLauncher`.
5. **Quick-switcher engine** (5): `WidgetGlobalSearchScoreStrategy`, `WidgetGlobalSearchGuildsModel`, `WidgetGlobalSearchGuildsAdapter`, `ChannelFrecencyTracker`, `WidgetGlobalSearchDismissModel`.
6. **User-sheet state layer** (3): `WidgetUserSheetViewModel`, `…StoreState`, `…Kt`.
7. **Nearby friends** (3): `NearbyManager`, `WidgetFriendsFindNearbyViewModel`, and a stubbed `WidgetFriendsFindNearby`.
8. **Mentions state layer** (1): `WidgetUserMentionsViewModel`.
9. **Profile text/layout helpers** (3): `UserNameFormatterKt`, `DraweeSpanStringBuilderExtensionsKt`, `RightToLeftGridLayoutManager`.
10. **Misc** (2): `UserMutualGuildsManager`, `WidgetFriendsAddUserAdapter`.

---

## 3. Placeholder / stub flags

| Target file:line | Why it is a stub |
|---|---|
| `friends/WidgetFriendsAdd.kt:97-112` (`WidgetFriendsFindNearby`) | **Hard-coded error screen.** Hides the lottie, the results recycler and the learn-more link unconditionally and prints `add_friend_nearby_connection_error` + a `retry` button. Reference `WidgetFriendsFindNearby.java:88-208,321-420` has a 4-state `Model` sealed hierarchy (`Uninitialized`/`Empty`/`Error`/`NearbyUsers`), a `ModelProvider`, Bluetooth/location permission gating, `enableScanning()`, per-error copy (`add_friend_nearby_connection_error`/`_stopped`/`_generic_error`, `:446-458`), and per-row accept/decline REST calls (`acceptFriendRequest :306`, `declineFriendRequest :421`) with captcha fallback (`launchCaptchaFlow :470`). |
| `user/usersheet/WidgetUserSheet.kt:134-144` | Six whole sections are forced `View.GONE` on every render with no state input: stage-actions card + view, voice-settings header + card, `rich_presence_container`, guild container, developer header, copy-ID, add-to-server, edit-profile, actions divider. These are not "absent" — they are actively suppressed, which is the placeholder pattern the contract excludes. |
| `user/search/WidgetGlobalSearch.kt:27-29` | Guild rail (`global_search_guild_list`), add-a-friend and join-guild buttons forced `GONE`. |
| `user/profile/UserProfileHeaderViewModel.kt` (whole file, 41 LOC) | Named `…ViewModel` but is a bare data holder — no `StoreState`, no `Uninitialized`, no `observeViewState()`, no store subscriptions. |

---

## 4. Deep dive: `WidgetUserSheet`

Reference: `usersheet/WidgetUserSheet.java` (809 LOC) + `WidgetUserSheetViewModel.java` (2 272) + `WidgetUserSheetViewModelStoreState.java` (315).
Target: `usersheet/WidgetUserSheet.kt` (272 LOC), no view model.

### 4.1 Arguments / entry points

| Reference arg (`WidgetUserSheet.java:96-102`) | Target |
|---|---|
| `ARG_USER_ID` | present (`WidgetUserSheet.kt:256`) |
| `ARG_CHANNEL_ID` | **missing** |
| `ARG_GUILD_ID` | **missing** — this is what selects guild-scoped nick/roles/admin |
| `ARG_IS_VOICE_CONTEXT` | **missing** |
| `ARG_STREAM_PREVIEW_CLICK_BEHAVIOR` (`enum StreamPreviewClickBehavior { TARGET_AND_LAUNCH_SPECTATE, TARGET_AND_DISMISS }`, `:189-192`) | **missing** |
| `ARG_FRIEND_TOKEN` | **missing** |
| `Companion.enqueueNotice(userId, friendToken)` → `StoreNotices.Notice` (`:135-139`) | **missing** |

`show()` in the target (`WidgetUserSheet.kt:268`) takes only `(userId, fragmentManager)`; the reference has six overloads. **BLOCKER** — a sheet opened from a guild member list cannot show roles, nickname, or admin actions.

### 4.2 `ViewState.Loaded` field coverage

Reference has **28 fields** (`WidgetUserSheetViewModel.java:1302-1328`). Target `State.Loaded` (`WidgetUserSheet.kt:34-46`) has **11**.

Missing fields: `bioAst` (`List<Node<MessageRenderContext>>`), `channel`, `channelVoiceState`, `currentGuildId`, `guildIcon`, `guildIconURL`, `guildId`, `guildName`, `guildSectionHeaderText`, `hasGuildMemberBio`, `hasPremiumCustomization`, `profileLoaded`, `richPresence`, `roleItems` (`List<GuildRole>`), `showVoiceSettings`, `stageViewState`, `streamContext`, `userInSameVoiceChannel`, `voiceSettingsViewState`, plus `shouldShowRoles()` (`:1677`).

`StoreState` (`:913-938`, 24 fields incl. `canDisableCommunication`, `computedMembers`, `currentChannelVoiceStates`, `guildRoles`, `mySelectedVoiceChannelVoiceStates`, `myStageRoles`/`userStageRoles`, `outputVolume`, `permissions`, `selfMuted`/`selfDeafened`, `stageChannel`, `userRequestToSpeakState`) has **no target counterpart at all**.

### 4.3 Section-by-section

| Reference section (configure method) | Target | Gap |
|---|---|---|
| `configureUI` header + analytics (`:420-479`) | `showHeader` (`:158`) | No `AnalyticsTracker.openUserSheet(hasPremiumCustomization, activityType, gamePlatform, guildMember)` (`:438`); no `openPopoutLogged` guard; no `setPeekHeightBottomView` (`:446`) |
| **Rich presence** (`ViewHolderUserRichPresence.Companion.setRichPresence` + `configureUi` + `configureUiTimestamp`, `:448-469`) | container forced GONE (`:138`) | **BLOCKER — entire activity card absent**: game/music/platform/stream/stage variants, elapsed timer, party size, custom activity buttons (`setOnActivityCustomButtonClicked`), stream preview click, spectate |
| `configureProfileActionButtons` (`:403`) | `showRelationship` (`:170`) | JADX region is broken (`Method not decompiled`, 437 instructions, `WidgetUserSheet.java:391-409`) — exact button matrix **cannot be read from the reference**; observable from the binding + VM: message/call/video/add-friend/pending/more all exist in both, but target has no `channelId` so `LaunchVoiceCall`/`LaunchVideoCall` cannot fire a real DM channel |
| `configureStageActionsSection` (`:411-418`) | forced GONE (`:134-135`) | **Missing**: `UserProfileStageActionsView` invite-to-speak / move-to-audience (`setOnInviteToSpeak :757`, `setOnMoveToAudience :758`) |
| `configureAboutMe` (`:213-263`) | `:150-155` | Target sets plain text from `user.bio`. Reference renders **markdown AST** (`AstRenderer.render(bioAst, MessageRenderContext)`), shows a **guild icon + short-name chip** for guild-scoped bios, and toasts `guild_identity_bio_toast` on tap. Spoiler reveal via `handleBioIndexClicked`/`revealedBioIndices` absent |
| `configureIncomingFriendRequest` (`:337-357`) | `:181-183` | Parity |
| `configureVoiceSection` (`:482-492`) | forced GONE (`:136-137`) | **Missing**: server-mute switch, deafen switch, per-user output volume slider — the view exists (`UserProfileVoiceSettingsView.kt`, at parity) but is never shown or wired to `toggleMute`/`toggleDeafen`/`setUserOutputVolume` |
| `configureGuildSection` (`:298-335`) | `showAdmin` only (`:216`) | **Missing `RolesListView`** entirely (`roleItems`, `shouldShowRoles()`, themed `primary_300`, `guildId`), and the `guildSectionHeaderText` ("Roles in <guild>") |
| `configureConnectionsSection` (`:265-276`) | `showConnections` (`:200`) | Parity in shape; mutual-guilds/friends callbacks present |
| `configureNote` (`:359-389`) | `showNote` (`:187`) | Reference reads `StoreUserNotes.UserNoteState` (`Empty`/`Loading`/`Loaded`) — target's 3-state `NoteState` mirrors it. Target lacks `setRawInputType(1)` and the save-on-`onPause` + `hideKeyboard` path (ref `:704-709`) |
| `configureDeveloperSection` (`:278-296`) | forced GONE (`:140-141`) | **Missing** copy-ID row gated on `StoreStream.getUserSettings().isDeveloperMode` |
| Guild subscriptions on resume/pause (`:699-703`, `:717-722`) | absent | `StoreGuildSubscriptions.subscribeUser/unsubscribeUser` never called → member/presence never lazily fetched |
| Overflow "more" sheet | `showMore` (`:240-253`) builds a 1-2 item `MaterialAlertDialogBuilder` | Reference opens `b.a.a.d.a` bottom sheet with `EXTRA_USER_ID` + `EXTRA_CHANNEL_ID` (`:731-749`) — full action list |
| Accessibility | headings set (`:92-96`) — matches ref `:802-806`; **missing** the `setTraversalAfter` delegate on the more-button (`:791-801`) | MINOR |
| `WidgetChannelSelector.registerForResult(REQUEST_KEY_MOVE_USER)` (`:807`) | absent | Server-move picks no channel |

### 4.4 Events

Reference emits **14** event types (`WidgetUserSheetViewModel.Event`, `:139-912`): `ShowToast`, `ShowFriendRequestErrorToast(abortCode, username)`, `LaunchVoiceCall(channelId)`, `LaunchVideoCall(channelId)`, `LaunchSpectate(stream)`, `RequestPermissionsForSpectateStream(stream)`, `LaunchEditMember(guildId,userId)`, `LaunchKickUser(username,guildId,userId)`, `LaunchBanUser(username,guildId,userId)`, `LaunchDisableCommunication`, `LaunchEnableCommunication`, `LaunchMoveUser(guildId)`, `UserNotFound`, `DismissSheet`.

Target has **no event channel**; it exposes 10 static `@JvmField` lambda hooks (`WidgetUserSheet.kt:257-266`) that default to `null`, so with nothing assigned the sheet is inert. Notably absent: the `UserNotFound` → toast `user_profile_failure_to_open_message` + dismiss path (ref `:561-563`), the friend-request abort-code → `RestAPIAbortMessages.ResponseResolver.getRelationshipResponse` mapping (`:626-628`), and spectate-permission request (`:622-624`).

Target's `AdminAction` enum (`:56`) collapses reference's **separate** `LaunchDisableCommunication` / `LaunchEnableCommunication` into one `TIMEOUT` — the view already knows `isCommunicationDisabled` so this is recoverable, but the two reference destinations (`WidgetDisableGuildCommunication` vs `WidgetEnableGuildCommunication`) differ (context-launch vs fragment-manager-launch).

**Severity: BLOCKER.**

---

## 5. Deep dive: `UserProfileHeaderView`

Reference `profile/UserProfileHeaderView.java` (601 LOC) vs target `profile/UserProfileHeaderView.kt` (161 LOC).

| Aspect | Reference | Target | Sev |
|---|---|---|---|
| ViewState fields consumed | 25/25 (`UserProfileHeaderViewModel.java:448-473`) | 25 declared, but `streamContext`, `isMeUserPremium`, `isMeUserVerified`, `showMediumAvatar`, `showSmallAvatar`, `hasGuildMemberAvatar`, `isProfileLoaded`, `avatarColorId`, `guildMemberColorId` are **never read** | MAJOR |
| **Badges** | `Badge.Companion.getBadgesForUser(user, userProfile, isMeUserPremium, isMeUserVerified, context)` — 13 badge kinds via `UserUtils.isStaff/isPartner/isCertifiedModerator/isHypeSquad/isHypesquadHouse1-3/isBugHunterLevel1-2/isVerifiedDeveloper/isPremiumEarlySupporter` + `isPremium` + `isGuildBooster` (`Badge.java:63-117`) | 13 hand-rolled `publicFlags` bit tests (`UserProfileHeaderView.kt:126-141`) | — |
| Badge **tooltips** | `hypesquad_online_badge_tooltip` with house name, `premium_badge_tooltip` with `getPremiumSince(...)`, `premium_guild_subscription_tooltip` with `getBoostingSince(...)` (`Badge.java:78,106,114`) | tooltip field never populated; reuses `text` | MAJOR |
| Badge **premium upsell** | `showPremiumUpSell = !isMeUserPremium && isMeUserVerified` on early-supporter / nitro / boost badges; `objectType` = `"PREMIUM_EARLY_SUPPORTER"`/`"PREMIUM"`/`"PREMIUM_GUILD"` (`Badge.java:102,107,115`) | not computed | MAJOR |
| **Guild-boost tier icon** | 9 tiers by `guildBoostMonthsSubscribed` ≥ 2/3/6/9/12/15/18/24 (`Badge.java:112`) | always `…_lvl1_32dp` (`:140`) | MAJOR |
| Badge click | `Badge.Companion.onBadgeClick(fragmentManager, context)` opens the badge sheet (`WidgetUserSheet.java:751-756`) | `onBadgeClick` hook exists but `WidgetUserSheet.kt` never sets it | MAJOR |
| Badge layout | `RightToLeftGridLayoutManager(context, 3, VERTICAL, true)` (`:235`) | `GridLayoutManager(context, 3, VERTICAL, true)` — RTL row-fill order differs | MINOR |
| **Banner** | `IconUtils.getForGuildMemberBanner/getForUserBanner` sized to `max(screenW, screenH)`; `isDataUrlForGif` / `isImageHashAnimated` detection; `MGImages.DistinctChangeDetector`; async representative-colour fallback via `updateBannerBackgroundColorAsync` → `RepresentativeColorsKt.getUserRepresentativeColors()/getGuildMemberRepresentativeColors()` (`:269-328`, `:436-465`) | hard-coded CDN URLs at `?size=1024`, no data-URL GIF detection, no change detector, **no representative-colour fallback** — banner is flat-coloured when no image and no `bannerColorHex` (`:100-114`) | MAJOR |
| Avatar representative colour | `observeRepresentativeColor()` picks guild-member vs user palette by `guildMember.hasAvatar()`, `takeUntil { it != null }`, detaches on window-detach (`:436-452`) | samples the centre pixel of the loaded bitmap (`:77-80`) — different value, and fires on every state update | MAJOR |
| **Primary name** | `UserNameFormatterKt.getSpannableForUserNameWithDiscrim(...)` — two-tone name/discriminator with separate fonts, colours, sizes; plus **inline medium avatar span** when `showMediumAvatar` (`:330-336`) | plain `"$name#$discrim"` string, `showMediumAvatar` ignored (`:116-124`) | MAJOR |
| Bot tag | `R.string.bot_tag` + `UserUtils.isVerifiedBot(user)` (`:335`) | `R.string.bot_tag_bot`, verified flag hard-coded `false` (`:120`) | MINOR |
| **Secondary name** | `DraweeSpanStringBuilder` with inline **small avatar** when `showSmallAvatar` (`:338-358`) | plain text, `showSmallAvatar` ignored (`:121-123`) | MAJOR |
| **Custom status** | `EmojiNode` render into `DraweeSpanStringBuilder` honouring `allowAnimatedEmojis`; `PresenceUtils.getCustomStatusActivity` (`:367-412`, `:586-593`) | literal `":name:"` + state text concatenation — emoji is **never rendered as an image** (`:89-91`) | MAJOR |
| AKA | `configureAka` → `UserAkaView.configure(guildMembersForAka)` gated on `showAkas` (`:262-267`) | same (`:86-87`) | — |
| `Companion.bind(view, appComponent, observable)` | filters `ViewState.Loaded` and subscribes (`:157-176`) | absent — caller must push state manually | MINOR |
| `onFinishInflate` background/avatar/banner colour (`:484-492`) | done in `init` instead | MINOR |
| Themed default colour | `R.attr.primary_700` (`:244`) | `R.attr.colorBackgroundTertiary` (`:59`) | MINOR |

**Severity: BLOCKER** (badge fidelity, name spans, custom-status emoji, banner colour pipeline).

---

## 6. Remaining per-file gaps

### `user/WidgetUserMentions.kt` (86) vs reference (1 427) — **BLOCKER**
- **No filter bottom sheet.** Reference nests `WidgetUserMentionFilter extends AppBottomSheet` (`:1046-1228`) driving `Model.MessageLoader.Filters` (`:290`) with four fields: `guildId` (this-server vs all-servers), `includeEveryone`, `includeRoles`, `includeMultipleMentions` — each a `CheckedSetting` (`:1159-1211`).
- **No toolbar menu.** Reference inflates `R.menu.menu_user_mentions` and handles `R.id.menu_user_mentions_filter` (`:1242-1283`); title flips between the guild name and `all_servers` (`:1236`).
- **No `LoadingState`** (`:411`) / `handleLoadError` / `retry` (`:521-546`) — target silently swallows errors (`WidgetUserMentions.kt:62`) with no error UI or retry.
- **No `UserMentionsAdapterEventHandler`** (`:845`) — reference wires the full `WidgetChatListAdapter.EventHandler` (reactions, long-press, user click, `jumpToMessage :859`). Target hand-builds three `ChatListEntry` types (`:79-83`).
- No thread-spine `ItemDecoration` (`:1230`), no empty state, no `WidgetUserMentionsViewModel`.

### `user/WidgetUserSetCustomStatus.kt` (119) vs reference widget (297) + VM (713) — **MAJOR**
- `Expiration` enum matches exactly (`WidgetUserSetCustomStatusViewModel.java:215-221`).
- **Emoji picker is a fake**: 5 hard-coded unicode emoji in an alert dialog (`WidgetUserSetCustomStatus.kt:44-48`); reference calls `openEmojiPicker()` (`:127`) into the real picker and stores a `com.discord.models.domain.emoji.Emoji`.
- No `ViewState.Uninitialized` / loading gate; no `Event.SetStatusSuccess(custom_status_update_success)` / `Event.SetStatusFailure(default_failure_to_perform_action_message)` toasts (`:107-160`).
- Radio labels are static; reference formats them with plurals — `custom_status_clear_in_minutes` / `custom_status_clear_in_hours` (`WidgetUserSetCustomStatus.java:253-273`).
- Reference derives the form from `StoreState(customStatusSetting, emojiSet)` (`:309-311`); target uses a nullable static `initialState` hook.

### `user/WidgetUserStatusSheet.kt` (66) vs reference (181) + VM (331) — **MINOR/MAJOR**
- Four `ClientStatus` rows present and correct. Missing: `ViewState.Uninitialized` gate, `trackOpen(storeState)` analytics (`WidgetUserStatusSheetViewModel.java:294`), `getPlaceholderEmoji()` sourced from the real emoji store (`:265` — target hard-codes `"💬"` at `WidgetUserStatusSheet.kt:62`), and `setStatus` REST wiring (`:314`).

### `user/WidgetUserMutualFriends.kt` / `WidgetUserMutualGuilds.kt` — **MINOR**
Shape matches (`Model.Item.MutualFriend`/`Empty`, plurals titles, empty-state image attr, guild-icon-vs-shortname fallback, 4 mutual-server chips). Gaps: no `Companion.show(context, user)` navigation helper, no `UserMutualGuildsManager` (paged/lazy guild fetch), no `MGRecyclerAdapterSimple` diffing (target uses `notifyDataSetChanged`), no loading spinner state.

### `user/search/WidgetGlobalSearch.kt` (131) vs reference (397) — **MAJOR**
- **No filter picker.** Reference `showFilterPickerDialog()` (`:285-299`) offers `FILTER_OPTIONS_LIST` = `users(@)`, `text_channels(#)`, `voice_channels(!)`, `servers(*)` (`:101-102`) and `setSearchPrefix()` (`:256`). Target only reacts to a typed prefix (`WidgetGlobalSearch.kt:66`).
- **No guild rail** (`global_search_guild_list` forced GONE, `:27`); reference binds `WidgetGlobalSearchGuildsAdapter` with click + long-click (`:143-164`).
- **No scoring.** Target sorts alphabetically and truncates at 100 (`:91`); reference runs `WidgetGlobalSearchScoreStrategy` over `SearchContext` (frecency, recent guilds, mention counts, selected/previous channel, permission predicate). Note: `WidgetGlobalSearchScoreStrategy.java:65,158` are JADX-broken (`Code decompiled incorrectly`) — the exact weights **cannot be recovered** from this decompile.
- **No permission filtering** — target lists every channel in every guild regardless of `channelPerms` (ref `DEFAULT_PERMISSIONS_PREDICATE`).
- No `ItemHeader` rows, no `handleOnSelected` long-press → `WidgetUserSheet` (`:207-233`), no `onSelected(NavigationTab, PanelAction)` (`:234`), no `focusSearchBar()` (`:179`), no add-friend / join-guild buttons.
- Empty/no-results flipper indices (0/1/2) do match ref `getViewIndex` (`:199-205`).

### `user/search/ViewGlobalSearchItem.kt` (180) vs reference (252) — **MINOR**
Channel/guild/user configure paths, unread dot, mention badge, status view, bold match span, group-DM recipient styling all present. Gaps: reference uses `MGImages.setImage` (change-detected) rather than raw `setImageURI`; target's `styledText` never applies the `ItemHeader` path; `IconUtils` sizing hints absent.

### `friends/FriendsUi.kt` (`FriendsListBuilder`) vs `FriendsListViewModel` (1 887) — **BLOCKER**
- Sections match reference `getItems()` (`:1653-1745`): suggestions header → suggestions, pending header → pending, online header → friends, offline header → friends; "show 2, expand" thresholds (`size > 2`) match (`FriendsUi.kt:65,69`).
- **Missing `Item.ContactSyncUpsell(dismissed)`** (`FriendsListViewModel.java:472-505`) — a first row with click + long-click-to-dismiss (`WidgetFriendsListAdapter.java:84-140`) and its own `R.layout.widget_friends_list_contact_sync_upsell`.
- **Missing `Item.Friend.isApplicationStreaming`** (`:530`) — no "streaming" presence treatment.
- **Suggestion sort differs**: reference sorts by `suggestion.user.id` (`:1663`); target sorts by a synthetic `affinityRank` then id (`FriendsUi.kt:62`).
- **Presence model is lossy**: target reduces presence to a 4-value enum `FriendPresence` + a `String?` activity (`FriendsUi.kt:10,19`); reference keeps the full `Presence` and renders activity through `SimpleDraweeSpanTextView` (emoji-capable).
- **No `ViewState.Empty(showContactSyncIcon)`** field — target `FriendsListState.Empty` carries nothing (`FriendsUi.kt:33`), so the empty screen cannot toggle the contact-sync toolbar icon (ref `WidgetFriendsList.java:203-207`).
- **No Events**: reference emits `ShowToast`, `ShowFriendRequestErrorToast(abortCode, username)`, `LaunchVoiceCall(channelId)`, `CaptchaError(username, discriminator, error)` (`:181-423`). Target has none — accept/decline/call are bare lambdas with no result handling, no toast, no captcha retry.
- `BlockedUsersBuilder` / `BlockedUsersState` (`FriendsUi.kt:87-98`) have **no reference counterpart in this package** (blocked users live in `widgets/settings`); harmless but unverified.

### `friends/WidgetFriendsList.kt` (242) vs reference (328) + adapter (696) — **MAJOR**
- Toolbar menu present, but the three destinations are stubbed to nullable lambdas. Reference (`WidgetFriendsList.java:96-122`): add-friend → `WidgetFriendsAdd.Companion.show(context, null, "Friends")`; contact-sync → `ContactSyncFlowAnalytics.trackStart` + `AnalyticsTracker.openModal("Contact Sync", Traits.Location("Friends List Icon"))` + `WidgetContactSync.launch`; start-group → feature-flagged `GroupInviteFriendsSheet` vs `WidgetGroupInviteFriends`.
- Contact-sync menu visibility is driven by `onContactSync != null` (`WidgetFriendsList.kt:39`) instead of `ViewState.showContactSyncIcon` (ref `:128-132`).
- **No `PrivateCallLauncher`** (ref `:230`) — the call button cannot start a call.
- Adapter is missing the `ItemContactSyncUpsell` row type and both contact-sync callbacks (`setOnClickContactSyncUpsell` / `…LongClick`, ref `:291-296`).
- No `onTabSelected()` → `AnalyticsTracker.friendsListViewed()` + accessibility focus (ref `:233-238`), no `WidgetTabsHost.registerTabSelectionListener(NavigationTab.FRIENDS)` (ref `:249-251`).
- Loading adapter is a fixed 8 rows with no binding (`WidgetFriendsList.kt:101-107`) vs `WidgetFriendsListLoadingAdapter` + `FriendsListLoadingViewHolder`.

### `friends/WidgetFriendsAdd.kt` → `WidgetFriendsAddById` — **MAJOR**
- Validation, error text (`add_friend_error_username_only`), success toast (`add_friend_confirmation`), own-username indicator all present.
- **Captcha is a stub**: `onCaptcha` defaults to `done(null)` (`WidgetFriendsAdd.kt:93`). Reference registers a real `ActivityResultLauncher` via `WidgetCaptcha.Companion.registerForResult` and launches `WidgetCaptcha` with `CaptchaErrorBody.createFromError(error)` (`WidgetFriendsAddById.java:158,206-208`).
- **No REST call**: `submitRequest` defaults to an immediate error (`:90-92`). Reference calls `RestAPI.sendRelationshipRequest("Search - Add Friend Search", username, discriminator, captchaPayload)` (`:211`).
- No analytics: `friendAddViewed("Id")` (`:329`), `friendRequestFailed(input, username, discriminator, "Invalid Username")` (`:177`).
- Username indicator is a raw string; reference builds `self_username_indicator` with a styled `SpannableStringBuilder` (`:198`).
- `WidgetFriendsAdd` itself: missing `Companion.show(context, text, source)` + `openModal("Add Friend Modal", source)` (`WidgetFriendsAdd.java:44-54`).

### `friends/EmptyFriendsStateView.kt`, `SuggestedFriendView.kt` — at parity
Both mirror the reference: three constructors + `ef_title_text_size` styled attr + `updateView(source)` intent launch; and avatar/username/publicName/send/decline with the media-proxy size ladder respectively. Only nit: target's `WidgetFriendsAddContract.show` re-implements the intent by hand instead of delegating to `WidgetFriendsAdd.Companion.show` (MINOR).

---

## 7. JADX quality notes (do not guess from these)

The following reference regions are **not readable** in this decompile and were excluded from the comparison rather than assumed:

- `usersheet/WidgetUserSheet.java:391-409` — `configureProfileActionButtons` (437 instructions, `Method not decompiled`). The exact visibility matrix for message/call/video/add-friend/pending/accept/ignore buttons **cannot be verified**.
- `usersheet/WidgetUserSheetViewModel.java:1910` — `Code decompiled incorrectly`.
- `usersheet/WidgetUserSheetViewModelStoreState.java:301` — `Code decompiled incorrectly`.
- `profile/UserProfileHeaderView.java:111-127` (`BadgeViewHolder.<init>`), `:514-556` (`updateBannerColor` — body is readable as a comment dump and was used).
- `profile/UserProfileHeaderViewModel.java:147`, `:813-889` (constructor).
- `presence/ViewHolderUserRichPresence.java:604-696` (`configureTextUi`), `presence/ViewHolderGameRichPresence.java:43-133`, `presence/ViewHolderStreamRichPresence.java:61-112` — the rich-presence text layout rules are **unrecoverable** from this decompile.
- `search/WidgetGlobalSearchScoreStrategy.java:65,158` — the scoring weights are **unrecoverable**.
- `search/WidgetGlobalSearchModel.java:457`.
- `WidgetPruneUsersViewModel.java:513-592`, `WidgetUserSetCustomStatusViewModel.java:492-518`, `WidgetUserStatusSheetViewModel.java:233-262` — constructors only; the ViewState/Event surfaces are readable.

---

## 8. Top gaps by severity

**BLOCKER**
1. `WidgetUserSheetViewModel` (2 272 LOC) + `WidgetUserSheetViewModelStoreState` absent — the sheet has no state layer; all behaviour hangs off nullable static lambdas.
2. Entire `user/presence/**` cluster absent — no activity/game/music/stream/stage card anywhere.
3. `WidgetUserSheet` suppresses six sections unconditionally (`:134-144`): stage actions, voice settings, rich presence, guild/roles, developer copy-ID, add-to-server/edit.
4. `RolesListView` never bound in the user sheet — roles are invisible even though the target project ships `widgets/roles/RolesListView.kt`.
5. `UserProfileHeaderView` badge pipeline: no tooltips, no premium upsell, no `objectType`, boost badge always level 1, badge click never wired.
6. `UserProfileHeaderView` custom status renders `":emojiname:"` as literal text instead of an emoji drawee span.
7. `WidgetUserMentions` has no filter sheet (`guildId`/`includeEveryone`/`includeRoles`/`includeMultipleMentions`), no menu, no error/retry state.
8. `PrivateCallLauncher` missing → every call/video button in both packages is a no-op.
9. `WidgetBanUser` / `WidgetKickUser` missing → the admin view's ban/kick callbacks have no destination.
10. `WidgetFriendsFindNearby` is a hard-coded error screen; `NearbyManager` + its view model absent.
11. `WidgetGlobalSearchScoreStrategy` + `ChannelFrecencyTracker` missing → quick-switcher ranks alphabetically and ignores channel permissions.
12. `WidgetGlobalSearchModel` missing `ItemHeader`, `SearchContext`, `ChannelContext`, `UsersContext`, `SEARCH_TYPE_*`, `getForNav`/`getForSend`.
13. `FriendsListViewModel` events absent (`CaptchaError`, `ShowFriendRequestErrorToast`, `ShowToast`, `LaunchVoiceCall`) — friend accept/decline/call give no feedback.

**MAJOR**
14. `WidgetUserSheet.show()` takes no `guildId`/`channelId`/`streamPreviewClickBehavior`/`friendToken` → guild-scoped nick, roles, admin actions and DM calls are unreachable.
15. About-me renders plain text, not the markdown AST + guild-identity icon/toast + spoiler reveal.
16. Banner has no representative-colour fallback and no animated/data-URL GIF detection.
17. `UserNameFormatterKt` + `DraweeSpanStringBuilderExtensionsKt` missing → no two-tone name spans, no inline medium/small guild avatars.
18. `WidgetGlobalSearchDialog` is a different feature (group-DM picker) than the reference (quick-switcher host); guild rail model + adapter both missing.
19. `WidgetFriendsAddById` never calls REST and its captcha handler always fails.
20. Contact-sync upsell row + `showContactSyncIcon` state absent from the friends list.
21. `account/`, `email/`, `phone/` verification flows (6 classes) entirely absent.
22. `WidgetPruneUsers(+ViewModel)`, `WidgetUserPasswordVerify`, `WidgetFriendsAddUserAdapter`, `UserMutualGuildsManager` absent.
23. `UserProfileHeaderViewModel` is a data holder with no `StoreState`/`Uninitialized`/`observeViewState`.
24. Custom-status emoji picker is 5 hard-coded emoji.

**MINOR**
25. `RightToLeftGridLayoutManager`, `WidgetGlobalSearchDismissModel`, `WidgetUserProfileEmptyListItem` not extracted as classes.
26. Guild-subscription subscribe/unsubscribe on sheet resume/pause missing.
27. `setTraversalAfter` accessibility delegate on the more-button missing.
28. Themed header default colour differs (`colorBackgroundTertiary` vs `primary_700`); bot tag uses `bot_tag_bot` and hard-codes verified-bot `false`.
29. Mutual-friends/guilds adapters use `notifyDataSetChanged` instead of diffed `MGRecyclerAdapterSimple`.
