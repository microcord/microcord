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

## Pending from other Wave 3 agents

Messages, guilds, channels, users/social, and voice agents are still running.
Their wiring blocks will be appended here as they report.
