# Manticord — Missing-Store Inventory (read-only audit)

Reference: `C:\Users\manti\StudioProjects\discord-jadx` (JADX decompile, Discord Android 126.21)
Target: `C:\Users\manti\Desktop\projeler\manticordrebuild`
Date: 2026-07-26

## Method

1. Enumerated `app/src/main/java/com/discord/stores/*.java` in the reference, excluding any
   filename containing `$` (JADX-synthesized inner/lambda classes). **187 files**, not ~170.
2. Word-grepped every one of those 187 identifiers across **all** `*.kt` in the target tree
   (not just `stores/`). 48 identifiers appear somewhere; 139 appear nowhere.
3. For each of the 139 name-absent stores, ran behavior probes (key method names / domain
   keywords from the reference class API) across the whole target tree before declaring MISSING.
4. Statuses:
   - **IMPLEMENTED** — a real target store (or an equivalently-named/inlined store) with behavior.
   - **PARTIAL** — some behavior exists elsewhere, or only a contract/stub/model type exists.
   - **MISSING** — no target counterpart anywhere.
   - **ABSORBED** — reference `*Kt` facade file whose functions live inside the target store
     (Kotlin does not need a separate file); counted as IMPLEMENTED only when the parent store is.

## Totals

| Status | Count |
| --- | --- |
| IMPLEMENTED | 44 |
| ABSORBED (Kt facade of an implemented store) | 7 |
| PARTIAL | 37 |
| MISSING | 99 |
| **Total reference stores** | **187** |

Counting ABSORBED as IMPLEMENTED: **51 implemented / 37 partial / 99 missing** (27% / 20% / 53%).
Excluding the 3 subpackages below, which are counted separately.

Target `stores/` contains 48 Kotlin files (7,432 lines), of which `StoreStream.kt` alone is 1,726.

## Full inventory

| Reference store | Status | Target location (or —) | Owning phase | Notes |
| --- | --- | --- | --- | --- |
| ArchivedThreadsStore | MISSING | — | 12 | Threads/forums surface not started. |
| BuiltInCommands | MISSING | — | 12 | Slash-command catalog. |
| BuiltInCommandsProvider | MISSING | — | 12 | |
| ChannelAnalyticsViewType | MISSING | — | 13 | Analytics enum. |
| CommandAutocompleteState | MISSING | — | 12 | |
| CommandOptionAutocompleteQuery | MISSING | — | 12 | |
| ConnectionTimeStats | MISSING | — | 11 | RTC diagnostics. |
| DiscoverCommands | MISSING | — | 12 | |
| DispatchHandler | IMPLEMENTED | `stores/Dispatcher.kt`, `stores/StoreV2DispatchHandler.kt` | 3 | |
| Dispatcher | IMPLEMENTED | `stores/Dispatcher.kt` | 3 | |
| FailedMessageResolutionType | PARTIAL | `utilities/messagesend/MessageResult.kt`, `views/FailedUploadList.kt`, `StoreStream.failedUploads` | 7 | Failed-send retry exists but no typed resolution enum / no failed-message store contract. |
| LoadState | PARTIAL | ad-hoc per-store enums (e.g. `StoreUserRelationships.UserRelationshipsState`) | 3 | No shared `LoadState` primitive; each store rolls its own. |
| NotificationTextUtils | PARTIAL | `utilities/notifications/NotificationRenderer.kt` | 9 | Rendering exists; reference text-formatting rules not ported. |
| PendingDowngrade | MISSING | — | 13 | Billing. |
| ReadyPayloadUtils | IMPLEMENTED | `stores/ReadyPayloadHydrator.kt` | 4 | Equivalent, renamed. |
| RtcLatencyTestResult | MISSING | — | 11 | |
| SelectedChannelAnalyticsLocation | MISSING | — | 13 | |
| StageChannelRoleContext | PARTIAL | `widgets/stage/StageRoles.kt` | 11/12 | View-side role model only; no store context. |
| Store | IMPLEMENTED | `stores/Store.kt` | 3 | |
| StoreAccessibility | IMPLEMENTED | `stores/StoreAccessibility.kt` (97 L) | 10 | Delivered early. |
| StoreAnalytics | MISSING | — | 13 | No analytics pipeline at all in target. |
| StoreApplication | MISSING | — | 12 | |
| StoreApplicationAssets | MISSING | — | 12 | |
| StoreApplicationCommandFrecency | MISSING | — | 12 | |
| StoreApplicationCommands | MISSING | — | 12 | |
| StoreApplicationCommandsKt | MISSING | — | 12 | |
| StoreApplicationInteractions | MISSING | — | 12 | `models/botuikit/MessageComponents.kt` + `widgets/botuikit/**` views exist; no interaction store. |
| StoreApplicationStreamPreviews | PARTIAL | `stores/StoreApplicationStreamPreviews.kt` (8 L) | 11 | Sealed-interface contract only, no fetching/caching. |
| StoreApplicationStreaming | PARTIAL | `stores/VoiceStoreContracts.kt` | 11 | Data/enum contract only. |
| StoreAudioManagerV2 | PARTIAL | `stores/VoiceStoreContracts.kt` | 11 | `State` data class only. |
| StoreAuditLog | MISSING | — | 12 | |
| StoreAuditLogKt | MISSING | — | 12 | |
| StoreAuthentication | IMPLEMENTED | `stores/StoreAuthentication.kt` (268 L) | 5 | |
| StoreAutocomplete | MISSING | — | 12 | No autocomplete anywhere in target. |
| StoreBans | MISSING | — | 12 | |
| StoreCalls | MISSING | — | 11 | |
| StoreCallsIncoming | MISSING | — | 11 | |
| StoreChangeLog | MISSING | — | 12 | |
| StoreChangeLogKt | MISSING | — | 12 | |
| StoreChannelConversions | MISSING | — | 12 | `handleChannelCreateOrUpdate`/`handleChannelSelected` conversion tracking absent. |
| StoreChannelFollowerStats | MISSING | — | 12 | |
| StoreChannelMembers | IMPLEMENTED | `stores/StoreChannelMembers.kt` (217 L) | 6 | |
| StoreChannels | IMPLEMENTED | `stores/StoreChannels.kt` (291 L) | 6 | |
| StoreChannelsSelected | IMPLEMENTED | `stores/StoreChannelsSelected.kt` (106 L) | 6 | |
| StoreChat | PARTIAL | `stores/StoreChatInputEditing.kt` (28 L), `widgets/chat/**` local state | 7 | Only editing-message map. Missing `appendMention`, `replaceChatText`, chat `Event` bus, `InteractionState`, expanded-blocked-message groups. |
| StoreClientDataState | PARTIAL | `api/guildhash/GuildHashes.kt`, `gateway/io/GatewayPayloads.kt` | 4 | Hash/version models parsed but no client-state store to drive lazy READY / guild-hash resume. |
| StoreClientVersion | MISSING | — | 14 | Forced-update gate. |
| StoreCollapsedChannelCategories | IMPLEMENTED | `stores/StoreCollapsedChannelCategories.kt` | 6 | |
| StoreConnectionOpen | PARTIAL | `gateway/GatewayEventRouter.kt` + per-store `handleConnectionOpen` | 4 | Behavior distributed; no single observable connection-open store. |
| StoreConnectivity | MISSING | — | 4 | No `NetworkMonitor`/`ConnectivityManager` usage anywhere; `widgets/status/WidgetChatStatus.kt` has no connectivity source. |
| StoreContactSync | MISSING | — | 8/10 | Only the user-settings flag in `models/domain/ModelUserSettings.kt`. |
| StoreContactSyncKt | MISSING | — | 8/10 | |
| StoreDirectories | MISSING | — | 12 | `views/directories/**` exist without a store. |
| StoreDynamicLink | MISSING | — | 13 | Firebase dynamic links; `utilities/intent/DeepLinkParser.kt` covers plain deep links only. |
| StoreEmbeddedActivities | MISSING | — | 12 | |
| StoreEmbeddedActivitiesKt | MISSING | — | 12 | |
| StoreEmoji | MISSING | — | 12 | `WidgetEmojiPicker.kt` exists with no emoji store. |
| StoreEmojiCustom | MISSING | — | 12 | |
| StoreEmojiCustomKt | MISSING | — | 12 | |
| StoreEmojiGuild | MISSING | — | 12 | |
| StoreEntitlements | MISSING | — | 13 | |
| StoreExpandedGuildFolders | IMPLEMENTED | `stores/StoreExpandedGuildFolders.kt` | 6 | |
| StoreExperiments | PARTIAL | `models/experiments/**` DTOs only | 12 | Cross-cutting: no experiment evaluation/bucketing store, so no feature gating anywhere. |
| StoreExpressionPickerNavigation | MISSING | — | 12 | |
| StoreExpressionSuggestions | MISSING | — | 12 | |
| StoreForumPostMessages | MISSING | — | 12 | `widgets/forums/**` views exist without stores. |
| StoreForumPostReadStates | MISSING | — | 12 | |
| StoreFriendSuggestions | IMPLEMENTED | `stores/StoreFriendSuggestions.kt` (94 L) | 8 | |
| StoreFriendSuggestionsKt | ABSORBED | inside `stores/StoreFriendSuggestions.kt` | 8 | |
| StoreGameParty | MISSING | — | 12 | |
| StoreGatewayConnection | PARTIAL | `gateway/GatewaySocket.kt`, `gateway/GatewayEventRouter.kt`, `StoreStream.kt` | 4 | Socket + routing exist, but no store wrapper: no observable connection state, no store-owned identify/resume/voice-state-update send API. |
| StoreGifPicker | MISSING | — | 12 | `widgets/chat/input/gifpicker/GifLoadingView.kt` only. |
| StoreGifPickerKt | MISSING | — | 12 | |
| StoreGifting | MISSING | — | 13 | |
| StoreGooglePlayPurchases | MISSING | — | 13 | |
| StoreGooglePlaySkuDetails | PARTIAL | `utilities/billing/GooglePlayInAppSku.kt` | 13 | SKU model only, no billing client. |
| StoreGuildBoost | PARTIAL | `views/guildboost/**` | 13 | Views only. |
| StoreGuildIntegrations | MISSING | — | 12 | |
| StoreGuildIntegrationsKt | MISSING | — | 12 | |
| StoreGuildJoinRequest | MISSING | — | 12 | |
| StoreGuildMemberCounts | PARTIAL | `stores/StoreChannelMembers.kt` (member-list counts) | 6 | Approximate member counts from `GUILD_MEMBER_LIST_UPDATE` groups only; no per-guild count store or REST fetch. |
| StoreGuildMemberRequester | PARTIAL | `stores/StoreGuilds.kt`, `stores/StoreUser.kt` (chunk handling), `stores/StoreGuildSubscriptions.kt` | 6 | `GUILD_MEMBERS_CHUNK` is consumed, but there is no request queue/dedupe/`guildMemberExists`/`handleLoadMessages` fetch-on-demand path. |
| StoreGuildMemberVerificationForm | MISSING | — | 12 | `widgets/servers/member_verification/**` view only. |
| StoreGuildProfiles | MISSING | — | 12 | |
| StoreGuildRoleMemberCounts | MISSING | — | 12 | |
| StoreGuildRoleSubscriptions | MISSING | — | 13 | `widgets/guild_role_subscriptions/**` views only. |
| StoreGuildScheduledEvents | PARTIAL | `api/guildscheduledevent/**`, `widgets/guildscheduledevent/**`, `stores/StoreGuilds.kt` | 12 | Models + views; no event store/RSVP state. |
| StoreGuildSelected | IMPLEMENTED | `stores/StoreGuildSelected.kt` | 6 | |
| StoreGuildStickers | MISSING | — | 12 | |
| StoreGuildStickersKt | MISSING | — | 12 | |
| StoreGuildSubscriptions | IMPLEMENTED | `stores/StoreGuildSubscriptions.kt` (100 L) | 6 | |
| StoreGuildTemplates | MISSING | — | 12 | `widgets/guilds/create/GuildTemplateChannelsView.kt` only. |
| StoreGuildVoiceRegions | MISSING | — | 11 | |
| StoreGuildWelcomeScreens | MISSING | — | 12 | |
| StoreGuilds | IMPLEMENTED | `stores/StoreGuilds.kt` (268 L) | 6 | |
| StoreGuildsKt | ABSORBED | inside `stores/StoreGuilds.kt` | 6 | |
| StoreGuildsNsfw | MISSING | — | 12 | `api/user/NsfwAllowance.kt` exists; no NSFW-agree gate store. |
| StoreGuildsSorted | IMPLEMENTED | `stores/StoreGuildsSorted.kt` (127 L) | 6 | |
| StoreInstantInvites | MISSING | — | 12 | `widgets/guilds/invite/**` exists; no invite store. |
| StoreInviteSettings | PARTIAL | `widgets/guilds/invite/ViewInviteSettingsSheet.kt` | 12 | View only. |
| StoreLibrary | MISSING | — | 13 | |
| StoreLocalActionComponentState | MISSING | — | 12 | Bot-UI component in-flight state. |
| StoreLocalMessagesHolder | IMPLEMENTED | nested in `stores/StoreMessages.kt` | 7 | |
| StoreLurking | PARTIAL | hooks in `stores/StoreChannels.kt` (`getLurkingGuildIds`), `stores/StoreGuildsSorted.kt` (`lurkingGuildIds`) | 12 | Hooks are wired but permanently return empty; no lurk start/stop, no guild-preview flow. |
| StoreMFA | PARTIAL | `stores/StoreAuthentication.kt`, `widgets/auth/AuthFragments.kt` | 10 | Login-time MFA only; no MFA enable/disable/backup-code management. |
| StoreMaskedLinks | MISSING | — | 12 | |
| StoreMediaEngine | MISSING | — | 11 | `native/engine/NativeEngine.kt` exists but is not store-wrapped. |
| StoreMediaFavorites | MISSING | — | 12 | |
| StoreMediaSettings | PARTIAL | `stores/VoiceStoreContracts.kt` | 11 | `VoiceConfiguration` data class only; no persistence or apply path. |
| StoreMentions | PARTIAL | `widgets/user/WidgetUserMentions.kt`, `stores/StoreReadStates.kt` (mention counts) | 12 | Counts exist; recent-mentions inbox fetch/paging store absent. |
| StoreMessageAck | PARTIAL | `stores/StoreReadStates.kt`, `workers/MessageAckWorker.kt`, `StoreStream` | 7 | ACK path exists but no dedicated ack store: no pending/deferred ack ledger or `postPendingAck` batching semantics. |
| StoreMessageReactions | PARTIAL | `stores/StoreMessages.kt`, `StoreStream` (add/remove REST calls), `views/ReactionView.kt` | 7 | No reaction store: no optimistic reaction state, no reactor-list fetch/paging, no burst/emoji-detail state. |
| StoreMessageReactionsKt | PARTIAL | as above | 7 | |
| StoreMessageReplies | PARTIAL | `stores/StorePendingReplies.kt`, `models/message/Message.kt` (`messageReference`) | 7 | Compose-time replies covered; referenced-message resolution/fetch store absent. |
| StoreMessageRepliesKt | PARTIAL | as above | 7 | |
| StoreMessageState | IMPLEMENTED | `stores/StoreMessageState.kt` | 7 | |
| StoreMessageStateKt | ABSORBED | inside `stores/StoreMessageState.kt` | 7 | |
| StoreMessageUploads | IMPLEMENTED | `stores/StoreMessageUploads.kt` | 7 | |
| StoreMessageUploadsKt | ABSORBED | inside `stores/StoreMessageUploads.kt` | 7 | |
| StoreMessages | IMPLEMENTED | `stores/StoreMessages.kt` (400 L) | 7 | |
| StoreMessagesHolder | IMPLEMENTED | nested in `stores/StoreMessages.kt` | 7 | |
| StoreMessagesKt | ABSORBED | inside `stores/StoreMessages.kt` | 7 | |
| StoreMessagesLoader | IMPLEMENTED | `stores/StoreMessagesLoader.kt` (179 L) | 7 | |
| StoreMessagesMostRecent | IMPLEMENTED | `stores/StoreMessagesMostRecent.kt` | 7 | |
| StoreNavigation | IMPLEMENTED | `stores/StoreNavigation.kt` (141 L) | 5 | |
| StoreNotices | MISSING | — | 12 | In-app notice queue (dialogs/popups) absent. |
| StoreNotificationUpsells | MISSING | — | 9 | |
| StoreNotificationUpsellsKt | MISSING | — | 9 | |
| StoreNotifications | IMPLEMENTED | `stores/StoreNotifications.kt` (115 L) + `stores/NotificationPolicy.kt` | 9 | Delivered ahead of phase. |
| StoreNux | MISSING | — | 12 | |
| StoreOutboundPromotions | MISSING | — | 13 | |
| StorePaymentSources | PARTIAL | `widgets/settings/billing/PaymentSourceView.kt` | 13 | View only. |
| StorePendingReplies | IMPLEMENTED | `stores/StorePendingReplies.kt` | 7 | |
| StorePermissions | IMPLEMENTED | `stores/StorePermissions.kt` (122 L) | 6 | |
| StorePhone | PARTIAL | `utilities/phone/PhoneUtils.kt`, `views/phone/PhoneOrEmailInputView.kt` | 10 | Utilities/views only. |
| StorePinnedMessages | IMPLEMENTED | `stores/StorePinnedMessages.kt` | 7 | |
| StoreReadStates | IMPLEMENTED | `stores/StoreReadStates.kt` (316 L) | 6/7 | |
| StoreRequestedStageChannels | MISSING | — | 11 | |
| StoreReviewRequest | MISSING | — | 12 | |
| StoreRtcConnection | PARTIAL | `rtcconnection/RtcConnection.kt` | 11 | Connection type exists; no store/state machine wiring. |
| StoreRtcRegion | MISSING | — | 11 | |
| StoreSearch | MISSING | — | 12 | |
| StoreSearchData | MISSING | — | 12 | |
| StoreSearchInput | MISSING | — | 12 | `views/SearchInputView.kt`, `widgets/user/search/**` are local-filter only. |
| StoreSearchQuery | MISSING | — | 12 | |
| StoreSlowMode | PARTIAL | `utilities/messagesend/MessageSendCoordinator.kt`, `MessageResult.kt`, `widgets/chat/input/WidgetChatInput.kt` | 7 | 429/slowmode retry handled at send time; no cooldown store, no per-channel countdown observable, no thread-create cooldown. |
| StoreSpotify | MISSING | — | 12 | |
| StoreStageChannelSelfPresence | MISSING | — | 11 | |
| StoreStageChannels | MISSING | — | 11/12 | `views/stages/**`, `widgets/stage/**` views exist without stores. |
| StoreStageInstances | IMPLEMENTED | `stores/StoreStageInstances.kt` (90 L) | 6 | |
| StoreStickers | PARTIAL | `stores/StickerAnimationSettingsStore.kt`, `utilities/stickers/StickerUtils.kt`, `api/sticker/**` | 12 | Animation setting + models only; no sticker pack store. |
| StoreStream | IMPLEMENTED | `stores/StoreStream.kt` (1,726 L) | 3 | |
| StoreStreamRtcConnection | MISSING | — | 11 | |
| StoreSubscriptions | MISSING | — | 13 | `views/ActiveSubscriptionView.kt` only. |
| StoreTabsNavigation | IMPLEMENTED | `stores/StoreTabsNavigation.kt` | 5 | |
| StoreThread | MISSING | — | 12 | **Name collision**: target `StoreThread` is a `@StoreThread` annotation in `stores/Store.kt`, not the threads store. |
| StoreThreadDraft | MISSING | — | 12 | |
| StoreThreadMembers | MISSING | — | 12 | |
| StoreThreadMessages | MISSING | — | 12 | |
| StoreThreadsActive | MISSING | — | 12 | |
| StoreThreadsActiveJoined | MISSING | — | 12 | |
| StoreThreadsJoined | MISSING | — | 12 | |
| StoreUser | IMPLEMENTED | `stores/StoreUser.kt` (231 L) | 8 | |
| StoreUserAffinities | IMPLEMENTED | `stores/StoreUserAffinities.kt` | 8 | |
| StoreUserConnections | PARTIAL | `api/connectedaccounts/ConnectedAccount.kt`, `widgets/user/profile/UserProfileConnectionsView.kt` | 10 | Read-only display via profile; no own-connections store/management. |
| StoreUserGuildSettings | IMPLEMENTED | `stores/StoreUserGuildSettings.kt` (281 L) | 6/9 | |
| StoreUserNotes | IMPLEMENTED | `stores/StoreUserNotes.kt` | 8 | |
| StoreUserPresence | IMPLEMENTED | `stores/StoreUserPresence.kt` (248 L) | 8 | |
| StoreUserPresenceKt | ABSORBED | inside `stores/StoreUserPresence.kt` | 8 | |
| StoreUserProfile | IMPLEMENTED | `stores/StoreUserProfile.kt` (110 L) | 8 | |
| StoreUserRelationships | IMPLEMENTED | `stores/StoreUserRelationships.kt` | 8 | |
| StoreUserRelationshipsKt | ABSORBED | inside `stores/StoreUserRelationships.kt` | 8 | |
| StoreUserRequiredActions | PARTIAL | `stores/StoreAuthentication.kt`, `stores/StoreNavigation.kt`, `app/AppActivity.kt` | 5 | Routing works from the auth payload; no dedicated required-actions store observable / no re-fetch on 403 required-action responses. |
| StoreUserSettings | IMPLEMENTED | `stores/StoreUserSettings.kt` (218 L) | 10 | Delivered early. |
| StoreUserSettingsSystem | IMPLEMENTED | `stores/StoreUserSettingsSystem.kt` (144 L) | 10 | Delivered early. |
| StoreUserSurvey | PARTIAL | `api/user/UserSurvey.kt`, `models/domain/ModelPayload.kt` | 12 | Model parsed, never surfaced. |
| StoreUserSurveyKt | MISSING | — | 12 | |
| StoreUserTyping | IMPLEMENTED | `stores/StoreUsersTyping.kt` (113 L) | 8 | Renamed (`Users` vs `User`). |
| StoreV2 | IMPLEMENTED | `stores/StoreV2.kt` | 3 | |
| StoreV2DispatchHandler | IMPLEMENTED | `stores/StoreV2DispatchHandler.kt` | 3 | |
| StoreVideoStreams | MISSING | — | 11 | |
| StoreVoiceChannelSelected | MISSING | — | 11 | |
| StoreVoiceParticipants | PARTIAL | `stores/StoreVoiceParticipants.kt` (23 L) | 11 | `VoiceUser` interface contract only. |
| StoreVoiceSpeaking | MISSING | — | 11 | |
| StoreVoiceStates | PARTIAL | `api/voice/state/VoiceState.kt` | 11 | Model only; no voice-state store or gateway reconciliation. |
| VoiceConfigurationCache | MISSING | — | 11 | |
| VoicePropsTracker | MISSING | — | 11 | |

## Reference `stores/` subpackages

### `stores/updates/` — 2 non-synthetic files

| Reference | Status | Target |
| --- | --- | --- |
| `ObservationDeck` | IMPLEMENTED | `stores/updates/ObservationDeck.kt` (110 L) |
| `ObservationDeckProvider` | IMPLEMENTED | `stores/updates/ObservationDeckProvider.kt` (8 L) |

### `stores/authentication/` — 1 non-synthetic file

| Reference | Status | Target |
| --- | --- | --- |
| `AuthStateCache` | IMPLEMENTED | `stores/authentication/AuthStateCache.kt` (34 L) |

### `stores/utilities/` — 9 non-synthetic files (of 15 total; 6 are `$` lambdas)

**The entire package is MISSING from the target.** Word-greps for `BatchManager`, `Batched`,
`RestCallState`, `StoreUtilities`, and `leadingEdgeThrottle` return zero hits in any `*.kt`.

| Reference file | Status | Target | Owning phase | Notes |
| --- | --- | --- | --- | --- |
| `BatchManager.java` | MISSING | — | 3 | Batched dispatcher work; stores currently emit unbatched. |
| `Batched.java` | MISSING | — | 3 | Rx `Batched` operator. |
| `RestCallState.java` | MISSING | — | 3/4 | Loading/Success/Failure wrapper used by most REST-backed stores. |
| `Default.java` (RestCallState.Default) | MISSING | — | 3/4 | JADX-extracted nested subclass. |
| `Loading.java` (RestCallState.Loading) | MISSING | — | 3/4 | |
| `Success.java` (RestCallState.Success) | MISSING | — | 3/4 | |
| `Failure.java` (RestCallState.Failure) | MISSING | — | 3/4 | |
| `RestCallStateKt.java` | MISSING | — | 3/4 | `executeRequest` / `handleResponse` helpers. |
| `StoreUtilitiesKt.java` | MISSING | — | 3 | `leadingEdgeThrottle` and store Rx helpers. |

Consequence: every REST-backed target store hand-rolls its own loading/error handling
(`StoreUserRelationships.UserRelationshipsState`, `StoreUserProfile`, `StorePinnedMessages`, …),
which is the reason `LoadState` and `RestCallState` are both classified PARTIAL/MISSING.

## Parity holes in phases claimed DONE or IN PROGRESS

These are MISSING/PARTIAL stores whose owning phase is 0–8. Phases 0–5 are marked **DONE**;
phases 6–8 are **IN PROGRESS** but all of their checklist items are already ticked `[x]`,
so these represent unticked work behind ticked boxes.

### Phase 3 — Core Runtime and Stores (**DONE**)

| Store | Status | Gap |
| --- | --- | --- |
| `LoadState` | PARTIAL | No shared load-state primitive; each store re-invents it. |
| `stores/utilities/BatchManager` | MISSING | No batched store emission. |
| `stores/utilities/Batched` | MISSING | |
| `stores/utilities/StoreUtilitiesKt` | MISSING | `leadingEdgeThrottle` absent — store observables are unthrottled. |
| `stores/utilities/RestCallState` (+ 4 subclasses, + `RestCallStateKt`) | MISSING | Whole REST-state package absent. |

### Phase 4 — REST and Gateway (**DONE**)

| Store | Status | Gap |
| --- | --- | --- |
| `StoreGatewayConnection` | PARTIAL | Socket and router exist; no store wrapper, no observable connection state, no store-owned send API (voice-state-update, member requests). |
| `StoreConnectionOpen` | PARTIAL | `handleConnectionOpen` is duplicated across stores instead of a single source. |
| `StoreConnectivity` | MISSING | No `NetworkMonitor`/`ConnectivityManager` anywhere; offline/reconnect banner has no data source. |
| `StoreClientDataState` | PARTIAL | `GuildHashes` parsed but unused — no guild-hash/version-based lazy READY or resume optimization. |

### Phase 5 — Authentication and Navigation (**DONE**)

| Store | Status | Gap |
| --- | --- | --- |
| `StoreUserRequiredActions` | PARTIAL | Routed at login from the payload only; no store observable and no handling of required-action responses returned mid-session. |

### Phase 6 — Guilds and Channels (**IN PROGRESS**, all items ticked)

| Store | Status | Gap |
| --- | --- | --- |
| `StoreGuildMemberRequester` | PARTIAL | `GUILD_MEMBERS_CHUNK` consumed, but no request queue/dedupe, no `guildMemberExists`, no fetch-members-for-loaded-messages path — message authors outside the member list will not resolve. |
| `StoreGuildMemberCounts` | PARTIAL | Only member-list group counts; no per-guild member/online count store. |

### Phase 7 — Messages and Uploads (**IN PROGRESS**, all items ticked)

| Store | Status | Gap |
| --- | --- | --- |
| `StoreMessageReactions` | PARTIAL | Checklist claims "reactions"; there is no reaction store — no optimistic reaction state, no reactor-list fetch/paging. |
| `StoreMessageAck` | PARTIAL | Checklist claims "ACK/read state"; ACK exists in `StoreReadStates` + worker, but no pending-ack ledger/batching store. |
| `StoreSlowMode` | PARTIAL | Checklist claims "slow mode"; only send-time 429 handling — no cooldown store, no per-channel countdown observable, no thread-create cooldown. |
| `StoreMessageReplies` | PARTIAL | Compose-side replies exist (`StorePendingReplies`); referenced-message resolution/fetch store absent. |
| `StoreChat` | PARTIAL | Only `StoreChatInputEditing`. Missing `appendMention`, `replaceChatText`, chat event bus, interaction state, expanded-blocked-message groups. |
| `FailedMessageResolutionType` | PARTIAL | Retry exists without the typed resolution contract. |

### Phase 8 — Users and Social Features (**IN PROGRESS**, all items ticked)

| Store | Status | Gap |
| --- | --- | --- |
| `StoreContactSync` / `StoreContactSyncKt` | MISSING | Only the user-settings flag is modeled; friend-suggestion source is incomplete without it. |
| `StoreUserConnections` | PARTIAL | Read-only display through the profile view; no own-connections store. (Management itself is Phase 10.) |

### Phase 9–14 (NOT STARTED) — bulk of the gap

Not parity holes against current claims, listed for completeness:

- **Phase 9 (notifications)**: `StoreNotificationUpsells`(+`Kt`), `NotificationTextUtils` (PARTIAL).
  Note `StoreNotifications` and `NotificationPolicy` were already delivered early.
- **Phase 10 (settings/account)**: `StoreMFA`, `StorePhone`, `StoreUserConnections`, `StoreContactSync`.
  `StoreUserSettings`, `StoreUserSettingsSystem`, `StoreAccessibility` already delivered early.
- **Phase 11 (voice/calls/video)**: 22 stores — `StoreCalls`, `StoreCallsIncoming`, `StoreMediaEngine`,
  `StoreRtcConnection`, `StoreRtcRegion`, `StoreStreamRtcConnection`, `StoreVideoStreams`,
  `StoreVoiceChannelSelected`, `StoreVoiceSpeaking`, `StoreVoiceStates`, `StoreStageChannels`,
  `StoreStageChannelSelfPresence`, `StoreRequestedStageChannels`, `StoreGuildVoiceRegions`,
  `VoiceConfigurationCache`, `VoicePropsTracker`, `ConnectionTimeStats`, `RtcLatencyTestResult`,
  plus the four contract-only stubs (`StoreApplicationStreaming`, `StoreAudioManagerV2`,
  `StoreMediaSettings`, `StoreVoiceParticipants`, `StoreApplicationStreamPreviews`).
- **Phase 12 (extended parity)**: ~60 stores — search (4), threads/forums (9), emoji/stickers/gifs (8),
  application commands/autocomplete (7), guild administration (~15), notices/nux/changelog/review (5),
  misc (`StoreSpotify`, `StoreMaskedLinks`, `StoreMediaFavorites`, `StoreExperiments`, `StoreLurking`, …).
- **Phase 13 (billing/integrations)**: `StoreAnalytics`, `StoreEntitlements`, `StoreSubscriptions`,
  `StoreGifting`, `StoreGuildBoost`, `StoreGuildRoleSubscriptions`, `StoreLibrary`,
  `StoreGooglePlayPurchases`, `StoreGooglePlaySkuDetails`, `StorePaymentSources`,
  `StoreOutboundPromotions`, `StoreDynamicLink`, `PendingDowngrade`,
  `ChannelAnalyticsViewType`, `SelectedChannelAnalyticsLocation`.
- **Phase 14 (hardening)**: `StoreClientVersion`.

## Cross-cutting observations

1. **`StoreExperiments` is the single highest-leverage absence outside its phase.** DTOs are already
   parsed in `models/experiments/**` and arrive in `ModelPayload`, but nothing evaluates them.
   Every reference store that gates behavior on an experiment will diverge silently.
2. **No analytics layer exists at all** (`StoreAnalytics` + the two analytics enums). Many reference
   stores call into it on state transitions; those call sites are simply absent in the target.
3. **`stores/utilities/` being entirely absent** is why 4 of the 5 Phase 3 parity holes exist. Porting
   `RestCallState` + `BatchManager` + `leadingEdgeThrottle` would retroactively normalize ~10 target stores.
4. **Two renames to keep in mind when auditing**: `StoreUserTyping` → `StoreUsersTyping.kt`, and
   `ReadyPayloadUtils` → `ReadyPayloadHydrator.kt`. Also `StoreThread` in the target is an
   annotation, **not** the threads store — naive greps will report a false positive.
5. **Five target store files are contract/stub-only** (no dispatcher wiring, no state):
   `StoreApplicationStreamPreviews.kt` (8 L), `StoreVoiceParticipants.kt` (23 L), and the three
   classes in `VoiceStoreContracts.kt`. They exist to let voice widgets compile, not to store state.
