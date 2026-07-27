# StoreStream integration queue

Wave 3 store agents were barred from editing `stores/StoreStream.kt` (the gateway
agent owned it, and six agents editing one file would corrupt it). Each agent
therefore reports the exact construction, `storesV2` registration, and event
routing its stores need. **The coordinator applies these in one integration
pass.**

Apply in dependency order. Nothing here is optional — a store that is constructed
but not routed is dead code, which is exactly the defect the audit found in
`StoreNotifications`.

---

## Thread + forum stores (9 stores) — READY TO APPLY

Source: thread-stores agent handoff. All nine reconstructed from the Vineflower
Kotlin decompile, including `StoreThreadMembers.handleThreadMemberListUpdate`,
which the audit had listed as JADX-undecompilable.

### Construction

After `channels`, `users`, `messages`, `readStates`; the last two after
`gatewayConnection`.

```kotlin
private val threadsJoined = StoreThreadsJoined(users, observationDeck)
private val threadsActive = StoreThreadsActive(channels, observationDeck)
private val threadsActiveJoined = StoreThreadsActiveJoined(threadsActive, threadsJoined, observationDeck)
private val threadMembers = StoreThreadMembers(observationDeck, channels)
private val threadMessages = StoreThreadMessages(messages, channels, observationDeck)
private val threadDraft = StoreThreadDraft(dispatcher)
private val forumPostMessages = StoreForumPostMessages(dispatcher, users, observationDeck) {
    if (::restApi.isInitialized) restApi else null
}
private val archivedThreads = ArchivedThreadsStore(
    dispatcher, forumPostMessages, observationDeck,
    restApi = { if (::restApi.isInitialized) restApi else null },
    handleThreadCreateOrUpdate = ::handleChannelCreateOrUpdateInternal,
)
// must come after `gatewayConnection`:
private val forumPostReadStates = StoreForumPostReadStates(
    dispatcher = dispatcher,
    requestForumUnreadsOverGateway = gatewayConnection::requestForumUnreads,
    storeThreadsActive = threadsActive,
    storeReadStates = readStates,
    storeChannels = channels,
    storeUser = users,
    observationDeck = observationDeck,
    canAccessForumChannels = { false },   // DECISION REQUIRED — see below
)
```

Add all nine to `storesV2`.

### Routing

Order matters where noted: `threadsActive` reads `channels`' live maps, and
`threadsActiveJoined` reads both upstream stores.

- **`handleConnectionOpenInternal`**, *after* `channels.handleConnectionOpen`:
  `threadsJoined.handleConnectionOpen(payload)`, `threadsActive.handleConnectionOpen()`,
  `threadsActiveJoined.handleConnectionOpen()`, `threadMembers.handleConnectionOpen()`,
  `threadMessages.handleConnectionOpen(payload)`, `forumPostMessages.handleConnectionOpen(payload)`,
  `forumPostReadStates.handleConnectionOpen(payload)`, `archivedThreads.handleConnectionOpen()`
- **GUILD_CREATE**, after `channels.handleGuildAdd`: `threadsJoined.handleGuildCreate(guild)`,
  `threadsActive.handleGuildCreate()`, `threadsActiveJoined.handleGuildCreate(guild)`,
  `threadMessages.handleGuildCreate(guild)`
- **GUILD_DELETE**: the same four with `handleGuildDelete(guild.id)`
- **`StoreStream.kt:694-695` MUST BE SPLIT.** It currently routes
  `events.threadCreateOrUpdate` / `threadDelete` into the *channel* handlers, so
  threads take the channel branch and none of the thread stores fire.
  - THREAD_CREATE/UPDATE: `channels.handleChannelOrThreadCreateOrUpdate(t)`,
    `threadsJoined.handleThreadCreateOrUpdate(t)`, `threadsActive.handleThreadCreateOrUpdate(t)`,
    `threadsActiveJoined.handleThreadCreateOrUpdateOrDelete(t)`,
    `threadMembers.handleThreadCreateOrUpdate(t)`, `threadMessages.handleThreadCreateOrUpdate(t)`,
    `forumPostMessages.handleThreadCreateOrUpdate(t)`, `forumPostReadStates.handleThreadCreateOrUpdate(t)`
  - THREAD_DELETE: the same eight with their `handleThreadDelete(t)` /
    `handleThreadCreateOrUpdateOrDelete(t)` equivalents
- **`handleChannelCreateOrUpdateInternal`** (non-thread): `threadsActive.handleChannelCreateOrUpdate(c)`,
  `threadsActiveJoined.handleChannelCreateOrUpdate(c)`
- **`handleChannelDeleteInternal`**: `threadMessages.handleChannelDelete(c)`
- **THREAD_LIST_SYNC** — these stores take the whole `ModelThreadListSync`, not
  `(guildId, threads)`: `threadsJoined`, `threadsActive`, `threadsActiveJoined`,
  `threadMessages` `.handleThreadListSync(sync)`
- **Currently unrouted events** — wire them now:
  - `threadMemberUpdate` → `threadsJoined`, `threadsActiveJoined`
  - `threadMembersUpdate` → `threadsJoined`, `threadsActiveJoined`, `threadMembers`
  - `threadMemberListUpdate` → `threadMembers.handleThreadMemberListUpdate(p)`
  - `forumUnreads` → `forumPostReadStates.handleForumUnreads(p)`
- **MESSAGE_CREATE**: `threadMessages`, `forumPostMessages`, `forumPostReadStates`
- **MESSAGE_UPDATE / DELETE / BULK_DELETE**: `threadMessages`, `forumPostMessages`
- **MESSAGE_ACK**: `forumPostReadStates.handleMessageAck(readState)`
- **All four reaction events**: `forumPostMessages.handleReactionAdd/Remove/RemoveAll/RemoveEmoji(update)`
  (all take `MessageReactionUpdate`)
- **`StoreStream.kt:438`** chunk subscription: `threadMessages.handleMessagesLoaded(chunk)`
- **Logout** (`handlePreLogoutInternal` / `resetForLogout`): `reset()` on all nine

### Fixes this unblocks — apply in the same pass

- **`updateReadStateInputs()` (`StoreStream.kt:1582-1595`)** — the audit's single
  highest-leverage fix. All thread and voice unreads are currently discarded:
  ```kotlin
  activeJoinedThreads = threadsActiveJoined.getActiveJoinedThreadsInternal()
      .mapValues { StoreReadStates.ActiveJoinedThread(it.value.channel, it.value.muted) }
  activeForumThreads = threadsActive.getAllForumThreadsByIdInternal()
  ```
- **`StoreUserGuildSettings` (`StoreStream.kt:175-180`)**:
  `getJoinedThreadFlags = { threadsJoined.getJoinedThread(it)?.flags ?: 0 }`
- **`StorePermissions`**: feed `threadsJoined.hasJoinedInternal(threadId)` for
  private-thread permissions (`StorePermissions.java:92-95`)

### DECISION REQUIRED

`canAccessForumChannels` has **no default**. The reference is
`ForumUtils.canAccessRedesignedForumChannels(guildId)` — a guild-experiment
lookup (`2021-07_threads_only_channel`, bucket 1). Neither `ForumUtils` nor
`StoreExperiments` exists yet, so the agent refused to guess: a default that
makes a feature silently vanish is worse than a missing parameter. `{ false }`
matches an unbucketed account. **Flip it when `StoreExperiments` lands.**

### Cross-package divergence to reconcile

`ModelThreadListSync.mostRecentMessages`: the reference declares
`List<com.discord.api.message.Message>`; the target
(`models/thread/dto/ModelThreadListSync.kt:12`) declares
`List<com.discord.models.message.Message>`. The thread stores consume the target
type directly, skipping the reference's `Message(apiMessage)` wrap. Reconcile
with the gateway/models owner.

### Reference bugs reproduced verbatim — flagged for a decision

Commented in code. Same question as `SparseMutableList`: parity vs correctness.

1. `StoreThreadMessages.updateFromThread` **loses the message count** — both
   writes copy from the same pre-update `ThreadState`, so a payload carrying both
   `message_count` and an unseen `last_message_id` discards the count. Confirmed
   against the decompiled `copy$default` masks (23 then 15).
2. `StoreThreadsJoined.handleSearchFinish` **never calls `markChanged()`** — so
   search-discovered memberships only become observable on the next unrelated
   dispatch.
3. `StoreThreadsActive.handleThreadListSync` updates the forum index **outside
   the `isThread` guard**, letting archived and non-thread channels into
   `forumThreadsById`.
4. `StoreForumPostMessages.handleReactionAdd` **has no me-guard** — your own
   reaction echo increments the count. Same defect class the audit found in
   `StoreMessages`.

### Note

`stores/StoreThread.kt` was **not** created: the reference file is only the
`@StoreThread` annotation (14 lines), which the target already declares at
`stores/Store.kt:12-15`. No action needed.

---

## Guild-admin + stage stores (13 stores) — READY TO APPLY

Source: guild-feature-stores agent handoff. Every store takes
`() -> RestAPIInterface?` and no-ops when null, so ordering against
`createRestApi` does not matter.

**Construction** — guild-admin group after `stageInstances`; stage group after
`voiceChannelSelected`:
`bans`, `guildIntegrations`, `guildJoinRequests`, `guildTemplates`,
`guildMemberVerificationForm`, `auditLog`, `guildRoleSubscriptions`,
`guildBoost`, `guildScheduledEvents`, `instantInvites` (its
`onGuildScheduledEventResolved` points at
`guildScheduledEvents::addGuildScheduledEventFromInvite`), then `stageChannels`,
`requestedStageChannels`, `stageChannelSelfPresence`.

**`storesV2`** — append all except `guildIntegrations`, which is deliberately
**not** a `StoreV2` (the reference uses a plain `BehaviorSubject` store).

**Voice fan-out** — replace the inert lambda: `notifyVoiceStatesUpdated` must
call `stageChannels.handleVoiceStatesUpdated(guildId, channelId, userId)`, and
`voiceStates.handleVoiceStateUpdate(voiceState)` must also call
`stageChannelSelfPresence.handleVoiceStateUpdate(voiceState)`.

**Gateway routing** — resolves 10 of the outstanding TODOs: `guildBanAdd/Remove`
→ `bans`; `guildIntegrationsUpdate` → `guildIntegrations`;
`guildJoinRequestCreateOrUpdate`/`Delete` → `guildJoinRequests`; all five
`guildScheduledEvent*` → `guildScheduledEvents`. Extend the three existing
`stageInstance*` subscriptions with `stageChannelSelfPresence` equivalents.

**Lifecycle** — `handleConnectionOpen`, `handleGuildCreate/Remove`,
`handleChannelDelete`, `handleGuildMemberAdd/Remove`,
`handleGuildRoleCreateOrUpdate`, both logout/reset blocks, and
`stageChannelSelfPresence.init()`. **12 new companion accessors.**

The agent's full report has the exact line numbers and call list.

### Seams this unblocks
- **`InviteEmbedStore`** in `WidgetChatListAdapterItemInvite.kt:438-466` — delete;
  use `StoreInstantInvites`. States map 1:1 except the store adds `LoadFailed`.
  The row must key `observeInvite` on `ModelInvite.getInviteStoreKey(code, eventId)`.
  Merge `entries/InviteEmbedResolver` at the same time.
- **`ChatListRowNavigation.toggleEventRsvp`** — now backed by
  `guildScheduledEvents.toggleMeRsvpForEvent(event)`; rows should read
  `isMeRsvpedToEvent` instead of deriving RSVP from the payload.
- **Stage-invite speakers / listener count** — `stageChannels.observeGuildStageChannels(guildId)`
  for joined guilds; `requestedStageChannels.enqueueStageChannelFetch(channelId)`
  for invite-reached channels. This closes the audit's "largest behavioural gap".
- **Guild-template row** — `guildTemplates.maybeInitTemplateState(code)` +
  `observeGuildTemplate(code)` gives cross-row caching.

### Types to relocate (co-located only because of file-ownership limits)
`StageChannel` → `widgets/stage/model/`; `StageChannelRoleContext` → its own file
in `stores/` (pure move); `GuildScheduledEventUser` → `widgets/guildscheduledevent/`.

### Reference bug reproduced — decision needed
`StoreAuditLog.recordDeletedTarget` compares a deleted channel's `type` (parsed
as `Long`) against a boxed `Integer.valueOf(0)` — confirmed at disassembly offset
807. **Always false, so a deleted text channel never renders its leading `#`.**
One numeric comparison to fix.

### Unverified
`StoreStageChannels.handleConnectionOpen` clears its maps **without**
`markChanged()` — real reference behaviour, reproduced. Because `snapshotData`
only runs for changed stores and carries untouched channels over, **stale stage
roles survive a reconnect** until something dirties them.

## Emoji / sticker / notice stores (9 stores) — READY TO APPLY

Source: emoji-stores agent handoff. `StoreExperiments` and `StoreUserSurvey`
were already complete and wired; untouched.

**Construction** (after `permissions`, `guildsSorted`, `userSettingsSystem`,
`experiments`): `notices`, `nux`, `reviewRequest`, `changeLog`, `emojiCustom`,
`emoji`, `emojiGuild`, `stickers`, `guildStickers`.

**`storesV2`** — add ONLY `emojiCustom`, `emojiGuild`, `stickers`,
`guildStickers`. `StoreEmoji`, `StoreNotices`, `StoreNux`, `StoreReviewRequest`
and `StoreChangeLog` are plain `Store`s and must **not** go in that list.

**`init(context)` — order is load-bearing:**
```kotlin
notices.init(context); reviewRequest.init(context)
changeLog.init(context.applicationContext as Application)
emoji.initBlocking(context)
Rules.setEmojiDataProvider(emoji)   // MUST precede the first parse
stickers.init()
```
**This closes the "Required init-order coupling" item below.** `StoreEmoji`
implements `Rules.EmojiDataProvider` directly. Until this line exists, named and
unicode emoji render as literal text everywhere and nothing errors.

**Gateway** — replace the client-data-state-only handlers at `:1049-1050` so
`guildEmojisUpdate` also reaches `emojiCustom` + `emojiGuild`, and
`guildStickersUpdate` also reaches `guildStickers`. Delete the
`TODO(StoreEmoji, StoreStickers)` at `:1035`.

**Lifecycle** — `handleConnectionOpen`, guild create/remove, guild-member-add
(own role changes only), guild selection → `nux`, and the logout path.
**9 new accessors.**

### Two seams this closes in the same edit
- **`StoreUserPresence`** is constructed with stubbed emoji lambdas — pass
  `resolveCustomEmoji`/`resolveUnicodeEmoji` backed by `emoji`. Custom-status
  emoji are currently stubbed.
- **`widgets/stickers/StickerStores`** (lines 109/111) → `StoreStream.getStickers()`
  / `getGuildStickers()`. The new stores implement those exact interfaces, so it
  is a one-line swap each; `RestBackedStickerStore` and
  `GuildsBackedGuildStickerStore` in `StickerStoreDefaults.kt` then become dead.

### Reference behaviours reproduced verbatim — flagged
- **Guild emoji sort is reverse-alphabetical** (`sortedByDescending` on the
  lowercased name). Looks like a reference bug.
- **`noticeQueue`'s second sort key is `-priority`**, so numerically-higher (less
  urgent) priorities sort first among plain notices.
- **`StoreChangeLog`'s experiment branches are dead** — R8 folded the bucket to a
  constant `null` in 126.21 and the experiment name is unrecoverable, so
  `WidgetChangeLogSpecial` is permanently unreachable.

### Still not presented
`Notice.show()` / `validScreens` have **no consumer**. The reference filters and
honours `autoMarkSeen` inside `StoreNavigation.ActivityNavigationLifecycleCallbacks`.
Until `StoreNavigation` subscribes to `getNotices()`, notices queue and publish
but nothing displays them.

## Applications / commands / interactions / commerce (18 stores) — READY TO APPLY

Source: application-and-commerce-stores agent handoff. Every routing line below was
read off the **reference `StoreStream`** in the Vineflower decompile
(`discorddecompile/out/com/discord/stores/StoreStream.kt`), not inferred — the
line numbers cited are that file's.

### Construction

Three ordering constraints, everything else is free:

1. `applicationInteractions` needs `messages` and `localActionComponentState`.
2. `applicationCommands` needs `gatewayConnection` (it holds a method reference to
   it), plus `permissions` and `experiments`.
3. `gifting` needs `library`.

Every REST-backed store here defaults to `{ RestAPI.apiOrNull }`, which
`StoreStream` already installs at `:2528`, so **no `restApi =` argument is
needed** — unlike the thread/guild waves.

```kotlin
// Applications and slash commands
private val application = StoreApplication(dispatcher, observationDeck)
private val applicationAssets = StoreApplicationAssets(dispatcher, observationDeck)
private val applicationCommandFrecency = StoreApplicationCommandFrecency(dispatcher, observationDeck)
private val autocomplete = StoreAutocomplete(observationDeck, dispatcher)
private val localActionComponentState = StoreLocalActionComponentState(dispatcher, observationDeck)
private val embeddedActivities = StoreEmbeddedActivities(observationDeck)
private val applicationInteractions = StoreApplicationInteractions(
    dispatcher = dispatcher,
    storeMessages = messages,
    storeLocalActionComponentState = localActionComponentState,
    sharedPrefs = preferences,
)

// MUST come after `gatewayConnection` (:429):
private val applicationCommands = StoreApplicationCommands(
    dispatcher = dispatcher,
    storeApplicationCommandFrecency = applicationCommandFrecency,
    gatewayRequest = gatewayConnection::requestApplicationCommands,
    storePermissions = permissions,
    storeExperiments = experiments,
    observationDeck = observationDeck,
)

// Commerce
private val library = StoreLibrary(dispatcher, observationDeck)
private val entitlements = StoreEntitlements(dispatcher, observationDeck)
private val subscriptions = StoreSubscriptions(observationDeck, dispatcher)
private val paymentSources = StorePaymentSources(dispatcher, observationDeck)
private val googlePlayPurchases = StoreGooglePlayPurchases(observationDeck, dispatcher)
private val googlePlaySkuDetails = StoreGooglePlaySkuDetails(observationDeck, dispatcher)
private val outboundPromotions = StoreOutboundPromotions(dispatcher, observationDeck)
private val gifting = StoreGifting(dispatcher, onGiftRedeemed = library::fetchApplications)
private val clientVersion = StoreClientVersion()
private val dynamicLink = StoreDynamicLink(dispatcher, authentication)
```

`gatewayConnection::requestApplicationCommands` converts to the store's
`fun interface ApplicationCommandRequester` directly — the parameter lists match
positionally (`guildId, nonce, applications, query, offset, commandLimit, commandIds`).

`onGiftRedeemed = library::fetchApplications` reproduces
`StoreGifting.kt:222`, where the reference calls
`StoreStream.getLibrary().fetchApplications()` inline. The lambda seam exists only
so the store is testable without the graph.

### `storesV2`

Add **15**: `application`, `applicationAssets`, `applicationCommandFrecency`,
`applicationCommands`, `applicationInteractions`, `autocomplete`,
`localActionComponentState`, `embeddedActivities`, `library`, `entitlements`,
`subscriptions`, `paymentSources`, `googlePlayPurchases`, `googlePlaySkuDetails`,
`outboundPromotions`.

**Do NOT add** `gifting` (plain `Store` publishing through a `BehaviorSubject`;
the reference's is not even a `Store`), `clientVersion` (plain `Store`), or
`dynamicLink` (not a store at all — it has no base class in the reference either).

### `init(context)`

`applicationInteractions.init(context)` is `@StoreThread` and calls `markChanged()`,
so it belongs in the existing `dispatcher.schedule { … }` block at
`StoreStream.kt:737` alongside `experiments.init(context)`:

```kotlin
dispatcher.schedule {
    experiments.init(context)
    userConnections.init(context)
    mediaSettings.init()
    notifications.restore()
    applicationInteractions.init(context)   // restores the persisted send-data ledger
}
```

`clientVersion.init(context)` is `@Synchronized`, not store-confined, and goes with
the other plain-`Store` inits (`notices.init`, `reviewRequest.init`) — reference
`StoreStream.kt:277`.

`dynamicLink` has **no init**: its entry point is
`storeLinkIfExists(intent, context)`, called by `AppActivity` on a launch intent.
See "Manticord-owned configuration required" below before wiring that call.

### Routing — gateway

All seven currently-unrouted subjects listed in `initGatewaySocketListeners`'
KDoc (`StoreStream.kt:1080-1087`) are closed by this block. **Delete those seven
TODO lines.**

```kotlin
// Applications and commands
dispatchSubscribe(events.guildApplicationCommands, "streamGuildApplicationCommands") {
    applicationCommands.handleApplicationCommandsUpdate(it)          // ref :694
}
dispatchSubscribe(events.applicationCommandAutocompleteResult, "streamCommandAutocomplete") {
    applicationCommands.handleApplicationCommandAutocompleteResult(it) // ref :1686
}

// Interactions
dispatchSubscribe(events.interactionCreate, "streamInteractionCreate") {
    applicationInteractions.handleInteractionCreate(it)               // ref :890
}
dispatchSubscribe(events.interactionSuccess, "streamInteractionSuccess") {
    applicationInteractions.handleInteractionSuccess(it)              // ref :896
}
dispatchSubscribe(events.interactionFailure, "streamInteractionFailure") {
    applicationInteractions.handleInteractionFailure(it)              // ref :1758
}
dispatchSubscribe(events.interactionModalCreate, "streamInteractionModalCreate") {
    applicationInteractions.handleInteractionModalCreate(it)          // ref :1765
}

// Embedded activities
dispatchSubscribe(events.embeddedActivityInboundUpdate, "streamEmbeddedActivityUpdate") {
    embeddedActivities.handleEmbeddedActivityInboundUpdate(it)        // ref :651
}

// Billing. Both arrive with NO `d` payload — the socket substitutes `Unit`, so the
// handlers take no argument and their only possible reaction is to re-fetch.
dispatchSubscribe(events.userSubscriptionsUpdate, "streamUserSubscriptionsUpdate") {
    subscriptions.handleUserSubscriptionsUpdate()                     // ref :1184
}
dispatchSubscribe(events.userPaymentSourcesUpdate, "streamUserPaymentSourcesUpdate") {
    paymentSources.handleUserPaymentSourcesUpdate()                   // ref :1172
}
```

### Routing — lifecycle

- **`handleConnectionOpenInternal`** (`:2299`), after `guildScheduledEvents.handleConnectionOpen`
  — reference order at `:618-624` is exactly this:
  ```kotlin
  outboundPromotions.handleConnectionOpen(payload)
  embeddedActivities.handleConnectionOpen(payload)
  applicationCommands.handleConnectionOpen(payload)
  applicationInteractions.handleConnectionOpen(payload)
  ```
- **`handleGatewayConnectionReadyInternal`** (`:1401`) — reference `:645-646`:
  ```kotlin
  applicationCommands.handleConnectionReady(ready)
  applicationInteractions.handleConnectionReady(ready)
  ```
  This is load-bearing twice over: it flushes the application request parked while
  the socket was down, and it fails every command still awaiting an
  `INTERACTION_CREATE` (the gateway does not replay old interactions, so without it
  an orphaned optimistic row spins forever).
- **`handleGuildCreateOrUpdateInternal`** (`:2367`) — reference `:705`, `:731`:
  `embeddedActivities.handleGuildCreate(guild)`
- **`handleMessageCreateInternal`** (`:1425`) — reference `:925`:
  `applicationInteractions.handleMessageCreate(message)` (the **api** message)
- **`events.messageUpdate`** (`:1287`) — reference `:963`:
  `applicationInteractions.handleMessageUpdate(message)`
- **Chunk subscription** (`:806`) — reference `:970`:
  `applicationInteractions.handleMessagesCreateOrLoad(chunk.messages)`
- **`handleResolvedChannelChanged`** (`:2049`) — reference `:533`:
  `applicationInteractions.handleChannelSelected()`
- **`handleLocalMessageDelete`** — reference `:1777` calls
  `applicationInteractions.handleLocalMessageDelete(message)`. The target has no
  local-delete fan-out point yet; the natural home is `StoreMessages`'
  `deleteLocalMessage` path. **Left unrouted — see "Unrouted" below.**

### Routing — logout

The reference's `handlePreLogout` (`:980-1000`) calls only four of these:
`gifting`, `paymentSources`, `subscriptions`, `applicationInteractions`.

Put all of them in `resetSecondaryStoresInternal()` instead, so `handlePreLogout`
and `resetForLogout` cannot diverge (the defect that made `StoreNotifications`
dead code):

```kotlin
applicationCommands.handlePreLogout()
applicationCommandFrecency.handlePreLogout()
applicationInteractions.handlePreLogout()
autocomplete.handlePreLogout()
localActionComponentState.handlePreLogout()
embeddedActivities.handlePreLogout()
application.handlePreLogout()
applicationAssets.handlePreLogout()
library.handlePreLogout()
entitlements.handlePreLogout()
subscriptions.handlePreLogout()
paymentSources.handlePreLogout()
googlePlayPurchases.handlePreLogout()
googlePlaySkuDetails.handlePreLogout()
outboundPromotions.handlePreLogout()
gifting.handlePreLogout()
```

Fourteen of those sixteen are **additions to the reference**, which simply leaks the
previous account's state. Each is documented in the store it belongs to. Two are
deliberately *not* symmetric:

- `StoreLibrary.handlePreLogout` clears **without** `markChanged()` on purpose: its
  nullable snapshot doubles as the "already fetched" latch, and publishing an empty
  non-null snapshot would re-arm the latch closed and stop the next account from
  ever fetching its library.
- `StoreAutocomplete.handlePreLogout` has no reference counterpart at all (the flag
  is view-driven). It exists only so the store's lifecycle is explicit; dropping it
  changes nothing.

### 18 new companion accessors

Reference names, **including its typo** in `getApplicationComandFrecency`
(one `m`) — kept so a future diff against the reference stays clean:

```kotlin
// Applications, commands and interactions
@JvmStatic fun getApplication() = requireInstance().application
@JvmStatic fun getApplicationAssets() = requireInstance().applicationAssets
@JvmStatic fun getApplicationComandFrecency() = requireInstance().applicationCommandFrecency
@JvmStatic fun getApplicationCommands() = requireInstance().applicationCommands
@JvmStatic fun getInteractions() = requireInstance().applicationInteractions
@JvmStatic fun getAutocomplete() = requireInstance().autocomplete
@JvmStatic fun getLocalActionComponentState() = requireInstance().localActionComponentState
@JvmStatic fun getEmbeddedActivities() = requireInstance().embeddedActivities

// Commerce
@JvmStatic fun getLibrary() = requireInstance().library
@JvmStatic fun getEntitlements() = requireInstance().entitlements
@JvmStatic fun getSubscriptions() = requireInstance().subscriptions
@JvmStatic fun getPaymentSources() = requireInstance().paymentSources
@JvmStatic fun getGifting() = requireInstance().gifting
@JvmStatic fun getGooglePlayPurchases() = requireInstance().googlePlayPurchases
@JvmStatic fun getGooglePlaySkuDetails() = requireInstance().googlePlaySkuDetails
@JvmStatic fun getOutboundPromotions() = requireInstance().outboundPromotions
@JvmStatic fun getClientVersion() = requireInstance().clientVersion
@JvmStatic fun getDynamicLinkCache() = requireInstance().dynamicLink
```

### Seams this closes

| Consumer | Today | After wiring |
| --- | --- | --- |
| `widgets/botuikit/ComponentChatListState.observeChatListComponentState()` | **unwritten** — needs `getInteractions()` + `getLocalActionComponentState()` | both published; write the combineLatest |
| `SelectComponentBottomSheet(ViewModel)` | dispatches nothing | `applicationInteractions.sendComponentInteraction` + `localActionComponentState.setSelectComponentSelection` |
| `widgets/chat/input/autocomplete/*` | falls back to a local emoji set | `applicationCommands.observeGuildApplications / observeQueryCommands / observeFrecencyCommands / observeDiscoverCommands` |
| `WidgetChatListAdapterItemGift` | resolves codes via REST per row, no cache; "Verify" disabled | `gifting.requestGift(code)` caches across rows |
| `views/PaymentSourceView`, `ActiveSubscriptionView` | inert | `paymentSources.observePaymentSourcesState()`, `subscriptions.observeSubscriptions()` |
| `widgets/settings/SettingsViewModel` | `promoCount` hardcoded `0`, `refreshSubscriptions()` a no-op | `outboundPromotions.observeUnseenCount()`, `subscriptions.fetchSubscriptions()` |
| `IconUtils.setApplicationIcon(ImageView, Application)` | unimplemented | `application.observeApplication(id)` supplies the record |
| `widgets/voice/model/CallModel` activity tile | no activities | `embeddedActivities.observeEmbeddedActivitiesForChannel(id)` + `applicationAssets.observeEmbeddedAppBackgrounds()` |

### Manticord-owned configuration required — three sites, all fail closed

Per PLAN Phase 13 no Discord Play product, Firebase value or promotions
application id is reproduced. Each site is a named constant or an injected seam,
never a silent default:

1. **`StoreOutboundPromotions.OUTBOUND_PROMOTIONS_APPLICATION_ID = 0L`** — the
   reference hardcodes Discord's promotions application id. With `0L` the
   entitlements lookup returns nothing, so no promotion is filtered out as
   "already consumed"; the promotions list itself is fully functional.
2. **`StoreClientVersion(minVersionSource = null)`** — the reference polls
   Discord's release manifest hourly. `null` disables polling; the store still
   reports the last persisted value, so an already-installed update gate keeps
   working but nothing new is discovered. Supply a source reading Manticord's own
   manifest (`min_version: Int`, absent ⇒ `0`).
3. **`StoreDynamicLink(linkResolver = NoDeferredLink)`** — the reference resolves
   deferred links through Firebase Dynamic Links, whose configuration is a
   recovered Google credential (and the service is discontinued). The default
   resolves to `Uri.EMPTY`, i.e. "no deferred link". **Direct launch intents are
   unaffected** — `AppIntentParser`/`DeepLinkParser` already handle those.
   `routeConsumer` also defaults to a no-op and should be pointed at
   `IntentUtils.consumeRoutingIntent`.

**Not one of the 18 stores declares a SKU id, product id or licence key.**
`StoreGooglePlaySkuDetails` and `StoreGooglePlayPurchases` are keyed entirely by
ids Play itself returned.

> **Violation to fix outside this wave:**
> `utilities/billing/GooglePlayInAppSku.kt` hardcodes four Play product ids
> (`premium_month_tier_1`, `premium_year_tier_1`, `premium_month_tier_2`,
> `premium_year_tier_2`) and two Discord SKU ids (`521846918637420545`,
> `521847234246082599`) recovered from the reference APK. That file is the single
> place the catalog lives, and it must be replaced with Manticord-owned product
> configuration. Owner: billing/utilities wave.

### Still unrouted after this block — deliberate

- **`applicationInteractions.handleLocalMessageDelete(message)`** (reference
  `StoreStream.kt:1777`). The target has no local-message-delete fan-out; the
  handler exists and is correct but nothing calls it, so deleting a failed
  optimistic command row leaves its send-data entry in the persisted ledger until
  the next reconnect fails it. One line in whatever owns `StoreMessages`'
  local-delete path.
- **`applicationCommands.requestApplications(guildId)` / `handleDmUserApplication(botUser)`**
  are driven by the composer, not by `StoreStream`, and are wired when the
  autocomplete cluster is connected.
- **`googlePlayPurchases.verifyPurchases` / `processPurchases`** stay inert until
  `utilities/billing/BillingUtils` and a `GooglePlayBillingManager` exist. The
  store's state machine, downgrade flow, retry predicate and persisted analytics
  traits are complete and testable now.
- **`StoreGooglePlayPurchases.analytics`** defaults to `PaymentFlowAnalytics.None`
  because `AnalyticsTracker` does not declare `paymentFlow{Started,Step,Completed,Failed}`.

### Degraded relative to the reference — one item, owned elsewhere

`StoreApplicationInteractions.sendApplicationCommand` **POSTs `/interactions`
directly** instead of enqueuing through the message pipeline. The reference calls
`StoreMessages.sendMessage(localMessage, applicationCommandLocalSendData, attachments)`
(decompile `StoreMessages.kt:1225`), which builds a
`MessageRequest.SendApplicationCommand` and lets `MessageQueue` drive the
multipart `sendApplicationCommand(payload_json, files)` overload.

None of that exists in the target: `MessageRequest` declares only `Send`,
`MessageQueue.doSend` has no interaction branch, `StoreMessages` has no
`(message, localSendData, attachments)` overload, and there is no
`LocalMessageCreators.createLocalApplicationCommandMessage`. Two visible
consequences, both commented in the store:

- **no optimistic "using /command" row** — the command appears only when the
  application replies;
- **attachment arguments are accepted and validated but not uploaded**
  (`AppLog.e` records it).

The send-data ledger, nonce correlation and every gateway path are fully live, so
closing this is purely a transport swap once the four pieces above land.
**Owner: send-pipeline wave** (`utilities/messagesend/**`, `stores/StoreMessages.kt`).

### Verified at bytecode/decompile level this wave

- **`StoreApplicationInteractions`' Gson must carry the API type adapters.** The
  persisted ledger contains `api.user.User.avatar: NullSerializable<String>?`, a
  sealed class. A bare `Gson()` throws on restore, the surrounding `catch` clears
  the cache, and **every pending slash-command send is dropped on every cold
  start**, silently. Now built as
  `GsonBuilder().registerDiscordApiTypeAdapters().create()`. **No
  `FieldNamingPolicy`** — the reference sets none, so raw Kotlin field names are
  the correct persisted keys, and adding one would invalidate every ledger on disk.
- The reference accessor really is spelled `getApplicationComandFrecency`.
- `StoreSubscriptions`/`StorePaymentSources` reference `handleUserSubscriptionsUpdate`/
  `handleUserPaymentSourcesUpdate` take **no argument**, confirming the audit's
  "these events carry no `d`" finding from the consuming side.

### Reference behaviours reproduced verbatim — flagged

- **`StoreApplicationAssets.embeddedAppBackgroundsLoading` is write-only for
  removal.** The id is never *added*, so the set suppresses nothing and a tile
  that binds twice before the response lands issues two requests. Kept because
  adding the id would change request volume relative to the reference.
- **`StoreGifting.generateGiftCode`'s error path** classifies the failure and then
  unconditionally overwrites the slot with `LoadFailed`, so a rejected generate
  never surfaces as `Invalid`. Callers distinguish through `onError`.
- **`StoreLocalActionComponentState.clearState`** with a non-null index that is not
  present clears nothing and does not `markChanged()`.
- **`StoreClientVersion.init`** assigns `clientMinVersion` and *then* calls the
  setter with the same value, so the setter's equality check swallows it: a client
  that was outdated last run reports `false` until the first poll answers.

## Pending from other Wave 3 agents

Messages, guilds, channels, users/social, and voice agents are still running.
Their wiring blocks will be appended here as they report.
