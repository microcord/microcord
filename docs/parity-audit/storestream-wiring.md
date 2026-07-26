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

## Pending from other Wave 3 agents

Messages, guilds, channels, users/social, and voice agents are still running.
Their wiring blocks will be appended here as they report.
