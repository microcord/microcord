# StoreStream wiring ledger - 2026-08-04

This replaces the Wave 3 "READY TO APPLY" queue. The historical construction and
routing blocks have largely landed; retaining them as pending instructions was
actively misleading.

Target: `app/src/main/java/com/discord/stores/StoreStream.kt`

Reference: read-only JADX and Vineflower `com/discord/stores/StoreStream` from
Discord Android 126.21.

No production code was modified.

## Current graph

- Reference constructed store instances: **145**.
- Current target mapping: **135/145 (93.1%)**.
- Reference `storesV2`: **106**.
- Target `storesV2`: **101** static entries plus two VoiceTransport stores added
  during initialization = **103/106 (97.2%)**
  (`StoreStream.kt:769-880,976-978`).
- Reference gateway events: **83**.
- Target typed/parser/subject surface: **83/83**; destination behavior is not all
  at parity (`IncomingParser.kt`; `StoreGatewayConnection.kt:459-596`;
  `StoreStream.kt:1508-1850`).

## Applied clusters

### Thread and forum stores - APPLIED

Constructed and in `storesV2`:

- `StoreThreadsJoined`
- `StoreThreadsActive`
- `StoreThreadsActiveJoined`
- `StoreThreadMembers`
- `StoreThreadMessages`
- `StoreThreadDraft`
- `StoreForumPostMessages`
- `StoreForumPostReadStates`
- `ArchivedThreadsStore`

Evidence: `StoreStream.kt:186-194,381-395,535-543,817-832`.

Routing now separates thread events from channel events, handles list/member/
forum updates, fans out messages/reactions/acks, and includes READY/guild/logout
paths (`StoreStream.kt:1632-1668,1705-1752,1921-1958,2596-2604,2719-2773,
2800-2805,2835-2838`).

Read-state inputs use active joined/forum threads and selected voice state directly
(`StoreReadStates.kt:81-106`). The old "four inputs never populated" defect is
resolved.

### Guild administration and stage - APPLIED

Constructed:

- bans, integrations, join requests, templates, verification form, audit log,
  role subscriptions, guild boost, scheduled events, instant invites
- stage channels, requested stage channels, stage self-presence

Evidence: `StoreStream.kt:200-219,695-701,858-878`.

Gateway and lifecycle fan-out is present at `StoreStream.kt:1591-1617,
1755-1849,1877-1889,2694-2839`.

### Emoji, stickers, notices and NUX - APPLIED

Constructed and initialized in the load-bearing order:

- notices, NUX, review request, emoji custom/catalog/guild, stickers/guild
  stickers, change log
- `emoji.initBlocking` precedes `Rules.setEmojiDataProvider`

Evidence: `StoreStream.kt:271-317,1098-1132`.

Emoji/sticker Gateway events reach client-data and dedicated stores
(`StoreStream.kt:1524-1532`). Presence receives real custom/unicode emoji
resolvers (`StoreStream.kt:301-317`). Notice hosting is connected in AppActivity
per the progress ledger.

### Applications, commands, interactions and billing - PARTIALLY APPLIED

Constructed/wired:

- application command frecency, application commands, local action component
  state, interactions, application directory, stream previews, embedded
  activities, applications/assets, entitlements, Play purchases/SKUs,
  subscriptions and payment sources

Evidence: `StoreStream.kt:476-530,803-816,1611-1617,1793-1821,2739-2773`.

Message create/update/load/delete fan-out into interactions is present
(`StoreStream.kt:1222-1230,1707-1717,1901,1966-1968`). Command attachments now
use `MessageRequest.SendApplicationCommand`; the old direct-POST limitation is
resolved (`MessageRequest.kt:73-85`; `MessageQueue.kt:283-324`).

The cluster is still partial because five present files are not in the graph:
`StoreGifting`, `StoreGuildRoleMemberCounts`, `StoreLibrary`,
`StoreOutboundPromotions`, and `StoreGuildVoiceRegions`. See "Unconstructed"
below.

### Social/security stores - APPLIED, WITH WIRING GAPS

Constructed:

- required actions, MFA, phone, survey, guild member requester, user
  connections, Spotify, contact sync, masked links, game party

Evidence: `StoreStream.kt:242,556-606,849-857`.

READY/gateway/logout fan-out exists (`StoreStream.kt:1670-1815,2535-2638,
2694-2774`). Detailed reports identify the remaining StorePhone/typing/auth
composition gaps below.

### Voice/media - APPLIED, RUNTIME OPEN

StoreStream constructs the static voice state/speaking/selection/calls/stream/
stage/participant stores, then installs `VoiceTransport` with native media, RTC,
stream RTC and audio manager stores (`StoreStream.kt:610-752,936-1001`).

Gateway voice/call/stream fan-out is live (`StoreStream.kt:1755-1791`), and READY/
selection/logout routes exist (`StoreStream.kt:1357-1377,1852-1874,2591-2595,
2753-2764`). ARM64/live end-to-end validation remains open.

### Notifications and Gateway wrapper - APPLIED

- `StoreNotifications` is constructed, restored, in `storesV2`, fed auth/channel/
  message state and reset (`StoreStream.kt:545-555,847-849,1107,1387-1411,
  2442,2488,2547,2679`).
- `StoreGatewayConnection` owns seeded subjects, ClientState, BatchManager and
  outgoing APIs (`StoreGatewayConnection.kt:97-467`).
- `StoreConnectivity`, `StoreConnectionOpen`, and `StoreClientDataState` are
  constructed/routed (`StoreStream.kt:472-475,800-803,1250-1258,1852-1874,
  2694-2778`).

## Remaining production wiring blockers

### 1. Authentication age gate and push token

- StoreAuthentication production construction omits `observeMe`,
  `observeSelectedChannel`, and `pushTokenProvider`
  (`StoreStream.kt:144-151`; defaults at `StoreAuthentication.kt:51-57`).
- `getShouldShowAgeGate()` therefore never emits, and StoreNavigation never
  subscribes it into `AuthNavigationState.ageGate`
  (`StoreAuthentication.kt:275-287`; `StoreNavigation.kt:81-95`).
- Logout posts no push registration token.

Required wiring:

- `observeMe = { users.observeMe(true) }`
- `observeSelectedChannel = channelsSelected::observeSelectedChannel`
- `pushTokenProvider = notifications::getPushToken`
- combine age-gate output into navigation routing, preserving required-action
  priority.

### 2. StorePhone initialization

`phone` is constructed, READY-routed and exposed (`StoreStream.kt:559,2741,3120`)
but `phone.init(context)` is absent from startup. `countryCodes` remains empty,
so default/selected country resolution cannot work (`StorePhone.kt:27-57`).

### 3. Guild timeout lifecycle and permission callback

- Startup calls `guilds.restore()` instead of `guilds.init(context)`
  (`StoreStream.kt:1269-1274`), so the ten-second expired-timeout observer never
  starts (`StoreGuilds.kt:79-123`).
- StoreGuilds construction does not supply `onMeCommunicationEnabled`
  (`StoreStream.kt:172-177`), so lifting the current user's timeout does not
  recompute permissions (`StoreGuilds.kt:302-315`).

### 4. StoreUserGuildSettings REST and analytics

StoreStream supplies channel and joined-thread lookups (`StoreStream.kt:252-258`)
but never calls `guildSettings.bindApi(api)` and does not supply the analytics
adapter. All setter bodies return on null API (`StoreUserGuildSettings.kt:318-324,
383-389`). Guild/channel/thread notification writes are inert.

### 5. StoreChannels permission source

StoreChannels production construction does not supply
`observeChannelPermissionsForGuild` (`StoreStream.kt:178-185`). The default empty
map makes `observeDefaultChannel` unable to choose a visible channel
(`StoreChannels.kt:319-326`). Because of the constructor cycle, bind a provider
after StorePermissions construction or introduce the smallest existing seam; do
not duplicate permission state.

### 6. StoreChannelMembers dependencies

StoreStream supplies only deck and one role lookup (`StoreStream.kt:243-245`).
Missing constructor wiring:

- dispatcher
- channel lookup
- full guild role map
- guild member count

Defaults at `StoreChannelMembers.kt:36-48` disable coalescing and make channel-
based member-list id/fallback count incorrect.

### 7. StoreChannelsSelected analytics/frecency

StoreStream supplies no analytics callbacks (`StoreStream.kt:227-229`), leaving
both no-op defaults (`StoreChannelsSelected.kt:55-60`). Channel frecency and its
`CHANNEL_HISTORY_V3` persistence are not implemented at all.

### 8. Guild subscriptions do not follow guild/voice selection

StoreStream constructs with deck/dispatcher only (`StoreStream.kt:246`), leaving
selected/voice guild providers at `0L` (`StoreGuildSubscriptions.kt:80-89`). It
also never calls `handleGuildSelect` from the guild-selection path
(`StoreStream.kt:2400-2424`). Consequences:

- selecting a guild does not subscribe typing/activity/thread streams;
- disconnect retain drops all guilds;
- reconnect cannot restore selected-guild subscriptions.

### 9. Message-loader background state

Background lifecycle is forwarded to connectivity only
(`StoreStream.kt:1254-1258`). Add the same dispatcher action for
`messagesLoader.handleBackgrounded(backgrounded)`; otherwise background history
fetches remain enabled (`StoreMessagesLoader.kt:134-145,192-197`).

### 10. Typing REST and slow-mode callback

StoreUsersTyping is constructed without its `api` or `onCooldown` providers
(`StoreStream.kt:457`; defaults `StoreUsersTyping.kt:22-36`). `setUserTyping`
returns without a request (`StoreUsersTyping.kt:85-99`). Wire the installed API
and `StoreSlowMode.onCooldown` for both message-send and thread-create types.

### 11. Voice participant initialization

`StoreVoiceParticipants.init(context)` has no production call. The selected
participant is not cleared when voice channel changes
(`StoreVoiceParticipants.kt:249-256`).

### 12. StoreNavigation/global initialization state

StoreStream publishes `initialized=true` unconditionally after queueing restore
(`StoreStream.kt:1268-1286`). The reference combines four readiness sources.
This blocks correct global route priority and can expose Home before restoration.
The exact Func4 predicate remains DEX-unverified; do not guess it.

## Present but unconstructed reference graph stores

### Target files present, StoreStream dead

- `StoreGifting.kt`
- `StoreGuildRoleMemberCounts.kt`
- `StoreGuildVoiceRegions.kt`
- `StoreLibrary.kt`
- `StoreOutboundPromotions.kt`

Each needs construction, correct dependency order, `storesV2` registration only
when the reference type is StoreV2, lifecycle/event routing, accessor and focused
test. A file alone is not integration.

### Reference graph responsibilities absent or differently represented

- `StoreAutocomplete`
- `StoreChannelFollowerStats`
- `StoreClientVersion`
- `StoreDirectories`
- `StoreDynamicLink`

Current substitutes (`StoreApplicationCommands` autocomplete state,
`StoreApplicationDirectory`, direct link parsing) cover only part of these
reference store contracts. Do not add duplicate classes until the existing
behavior is compared and a real missing second owner is proven.

## Remaining non-wiring blockers

These cannot be fixed solely in StoreStream:

- `ReadyPayloadHydrator` must snapshot custom emoji/sticker dedicated stores, not
  stale guild copies.
- `StoreChatInputEditing` callers must migrate to `StoreChat.EditingMessage`.
- `StoreMessageAck` comparator/fallback and `StoreMentions` multi-role behavior
  require DEX verification before edits.
- `StorePermission` thread member handlers look up threads through a guild-channel
  accessor that does not search `threadsById`.
- Notification policy lacks joined-thread and selected-voice inputs.
- ConnectionTimeStats is absent.

## Reference caveats

- The reference StoreStream initialization Func4 is still `<unrepresentable>` in
  Vineflower. Four inputs are known; predicate is not.
- Historical thread/member/message/gateway JADX failures were checked against
  Vineflower before their applied status was recorded here.
- No item is marked applied solely because a class exists; current construction
  and routing citations are required above.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend check ran.
- No current test asserts every production provider is non-default/non-null.
- No full 83-event destination-store fixture matrix exists.
- No ARM64/live voice regression matrix exists.
