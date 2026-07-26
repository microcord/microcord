# Parity Audit — Message Stores (PLAN Phase 7)

TARGET root: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE root: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (Discord Android 126.21, JADX)

Legend: BLOCKER = feature cannot work / whole subsystem absent; MAJOR = observable behavioral
divergence or lost state; MINOR = perf/shape divergence with equivalent semantics.

JADX-broken regions are called out explicitly and are **not** guessed at.

---

## Pair 1 — `stores/StoreMessages.kt` vs `stores/StoreMessages.java` (+ StoreMessagesHolder / StoreLocalMessagesHolder / StoreMessagesKt)

**SEVERITY: BLOCKER** — 9 blocker-class, 7 major, 6 minor.

### 1A. `StoreMessages` class

#### MISSING FIELDS
| Reference (`StoreMessages.java:81-89`) | Target |
|---|---|
| `Clock clock` | absent |
| `Context context` (lateinit) | absent |
| `Dispatcher dispatcher` | absent (target ctor `StoreMessages.kt:261-265` takes only `ObservationDeck` + 2 holders) |
| `StoreStream stream` | absent |
| `HashMap<Long, MessageQueue> messageQueues` | absent — per-channel queues live in `utilities/messagesend/MessageSendCoordinator.kt:61` instead |
| `ExecutorService queueExecutor` | absent |
| `BehaviorSubject<Boolean> initResendFinished` | absent |
| `Companion.BACKGROUND_SENDING_DELAY_MS = 120000` (`:79`) | absent |

Target's only extra field is `messagesSnapshot` (`StoreMessages.kt:266`) which has no reference
counterpart (reference derives the merged view via rx `combineLatest`).

#### MISSING METHODS / BEHAVIOR
All of the message **send/edit/delete/retry pipeline** is absent from the target store:

- `enqueueRequest(channelId, createRequest)` — `StoreMessages.java:132-145` (dispatcher-scheduler confined)
- `getOrCreateMessageQueue(channelId)` — `:149-164` (`@StoreThread`)
- `cancelMessageSend(channelId, requestId)` — `:364-367`
- `sendMessage(channelId, author, content, mentions, attachments, stickers, messageReference, allowedMentions, application, activity, messageActivity, lastManualAttemptTimestamp, initialAttemptTimestamp, numRetries, captchaPayload)` — `:625-698` **BLOCKER**
- `sendMessage(Message, ApplicationCommandLocalSendData, attachments)` — `:754-759` **BLOCKER** (slash-command local send)
- `resendMessage(message, isAutoAttempt, captchaPayload)` — `:569-612`, incl. the type-guard
  `IllegalArgumentException` for auto vs. manual attempt (`:576-578`)
- `resendMessageWithCaptcha(channelId, nonce, captchaPayload)` — `:614-621`
- `resendAllLocalMessages()` — `:307-323` (`@StoreThread`, mergeDelayError over all type `-1` locals, feeds `initResendFinished`)
- `markLocalCaptchaRequiredMessagesFailed()` — `:239-251` (`@StoreThread`, type `-6` → failed)
- `markMessageFailed(channelId, nonce)` — `:517-523`
- `deleteMessage(Message)` — `:376-387` (REST for remote, dispatcher path for local/ephemeral)
- `deleteLocalMessage(channelId, nonce)` — `:370-374` (cancels the queued request first)
- `editMessage(messageId, channelId, content, allowedMentions)` — `:389-393`
- `handleSendMessageFailure(Message)` — `:206-217`
- `handleSendMessageFailureAutoMod(Message, UtcDateTime)` — `:221-223` (type `-8`)
- `handleSendMessageCaptchaRequired(Message)` — `:200-202` (type `-6`)
- `handleSendMessageValidationError(Message, String)` — `:233-236`
- `trackFailedLocalMessageResolved(Message, FailedMessageResolutionType)` — `:356-362`
  **JADX BROKEN**: `"Method not decompiled"`, 277 instructions skipped. Cannot enumerate the analytics
  payload. Also: reference enum `stores/FailedMessageResolutionType.java` has **no target counterpart**.
- Interaction handling — all absent:
  `handleInteractionStateUpdate(update, isFailed, isLoading)` `:167-174`,
  `handleInteractionCreate` `:433-436`, `handleInteractionFailure` `:439-442`,
  `handleInteractionModalCreate` `:445-451`
- `handleConnectionOpen(ModelPayload)` — `:427-430` → `holder.setMyUserId(payload.me.id)`. **MISSING**;
  target instead threads `meUserId` in as a call parameter (`StoreMessages.kt:322`), so the store has no
  identity of its own.
- `init(Context)` — `:507-515`: `holder.init(true)` + `localMessagesHolder.init(false)` +
  `markLocalCaptchaRequiredMessagesFailed()` + `resendAllLocalMessages()`. Target `restore()`
  (`StoreMessages.kt:268-273`) does only the two restores; **no captcha-fail sweep, no resend-on-boot**.
  Note also the reference asymmetry: synced holder restores with cache **enabled**, local holder with
  cache **disabled** (`init$default(..., false, 1, null)`), i.e. locals are read but not re-persisted at
  boot. Target restores both with `enabled = true` (`:270-271`) — **divergent**.
- `observeInitResendFinished()` — `:525-529`
- `getAllDetached()` — `:395-399` (`computationLatest` on the detached subject). Target only exposes
  `observeIsDetached(channelId)` (`StoreMessages.kt:384-385`); nothing publishes the whole detached set,
  which is exactly what `StoreMessagesLoader.init` subscribes to in the reference (see Pair 2).
- `handleConnected(true)` fan-out to `messageQueue.handleConnected()` for every channel queue
  (`:415-425`). Target's `handleConnected` (`StoreMessages.kt:281`) only handles the `false` branch.

#### EXTRA / DIVERGENT
- `handleReactionUpdate(List<MessageReactionUpdate>, add)` / `handleReactionsRemoveAll(update)` /
  `handleReactionsRemoveEmoji(update)` (`:491-504`) are **batch** and delegate to the holder. Target
  replaces them with `handleReaction(update, added, meUserId)` /
  `handleReactionRemoveAll(channelId, messageId)` / `handleReactionRemoveEmoji(channelId, messageId, emoji)`
  (`StoreMessages.kt:322-350`) — single-item, reaction math re-implemented in the store, `me` supplied
  by caller. **MAJOR** signature + placement divergence.
- `observeMessagesForChannel(channelId)`: reference `combineLatest(synced, local, isDetached)` and, when
  not detached, returns `synced + local` — locals are **appended, never sorted** (`:562`). Target sorts
  the merged list by id (`StoreMessages.kt:393`). Local messages use synthetic ids; re-sorting changes
  ordering. **MAJOR**.
- `getMessage(channelId, messageId)`: reference is `@StoreThread` and reads the **synced holder TreeMap
  only** (`:402-408`); target reads the merged snapshot including locals (`StoreMessages.kt:379`).
  `StoreMessageReplies.processMessage` depends on the reference semantics. **MINOR-MAJOR**.
- `handlePreLogout()`: reference clears **only** `localMessagesHolder` (`:487-489`) — the synced
  `STORE_MESSAGES_CACHE_V38` survives logout. Target also calls `holder.clear()` which wipes the
  persisted synced cache (`StoreMessages.kt:371-376`). **MAJOR divergence**.
- Target adds `handleMessageCreate(Message)`, `handleMessageCreate(channelId, message, nonce)`,
  `handleMessageDelete(channelId, messageId)`, `handleLocalMessageCreate/Delete(channelId, nonce, …)`,
  `handleMessageUpdate(channelId, messageId, merge)`, `restore()`, `snapshotData()`,
  `observeMessage(channelId, messageId)` — EXTRA convenience surface (reference exposes
  `observeMessagesForChannel(channelId, messageId)` at `:717-752`, same semantics, different name).
- Target extends `StoreV2` + `ObservationDeck`; reference extends `Store` and publishes via
  `SerializedSubject` + `computationBuffered`/`computationLatest`. Architectural divergence — accepted,
  but note the reference emits from the holder subject (no snapshotData gating).
- `stores/StoreMessagesKt.java` is an empty typealias holder (`ApiMessage`, `ClientMessage`) — nothing to port.

### 1B. `StoreMessagesHolder`

#### MISSING FIELDS (reference `StoreMessagesHolder.java:37-57`)
| Reference | Target (`StoreMessages.kt:16-31`) |
|---|---|
| `boolean cacheEnabled` (`:42`) | **MISSING** — `restore(enabled)` param is dropped after use, so `cache.set(...)` at `:162` runs unconditionally even when the cache was disabled. **MAJOR** |
| `Subscription cachePersistSubscription` (`:43`) | **MISSING** |
| `long cachePersistedAt` (`:55`) | **MISSING** |
| `Map<Long,List<Message>> cacheSnapshot` (`:54`) | **MISSING** (dedupe before write) |
| `long myUserId` (`:46`) | **MISSING** |
| `Set<Long> updatedChannels` (`:56`) | **MISSING** (incremental publish set) |
| `CACHE_PERSIST_INTERVAL = 60000` (`:39`) | **MISSING** |
| `Map<String,Long> messageNonceIds` — keyed by **nonce alone** (`:52`) | keyed by `Pair<Long,String>` (`:23`) — DIVERGENT (arguably a fix) |

Target adds `detachedSubject`/`messagesSubject` as plain `BehaviorSubject` (reference uses
`SerializedSubject(BehaviorSubject)` — `:50`, `:62`). **MINOR** (thread-safety of `onNext`).

#### MISSING METHODS / BEHAVIOR
- `messageCacheTryPersist()` (`:117-147`) — 60 s throttle, equality-dedupe against `cacheSnapshot`,
  and a self-rescheduling `cachePersistSubscription` when inside the window. Target `publish()`
  (`StoreMessages.kt:157-163`) recomputes and writes the Persister on **every mutation**. **MAJOR** (I/O churn).
- `publishIfUpdated(boolean force)` (`:149-167`) — incremental snapshot merge from `updatedChannels`.
  Target rebuilds the entire map on every publish. **MINOR** (perf).
- `setMyUserId(long)` (`:384-386`) — **MISSING**.
- `addMessages` guard: reference requires `isChannelActive(channelId) && !isChannelDetached(channelId)`
  (`:229`). Target `addMessage` checks only `channelId in activeChannels` (`StoreMessages.kt:60`), so
  new messages get appended into a **detached** (jumped-to) channel. **MAJOR**.
- `updateDetachedState(channelId, map, isAppendingTop, isPresent, isJump)` (`:192-218`) — one routine
  that trims **and** flips detachment. Target splits into `trim()` + `setDetached()`
  (`StoreMessages.kt:138-155`). Reference line `:209`
  `z5 = (z6 && z2 && !z3) ? false : false;` is **JADX-BROKEN** (both branches false → dead expression);
  the real detach-on-trim predicate cannot be recovered from this dump. Target's `detach` argument
  (`isAppendingTop || isJump`, `:105`) is a **guess** and must be re-derived from smali.
- `addReaction(message, emoji, isMe)` (`:65-87`) — has an early-out when the reaction is already
  `me == true` for that emoji (`:69-71`). Target's `handleReaction` (`StoreMessages.kt:322-340`) has
  **no such dedupe**, so the gateway echo of your own reaction double-counts. **MAJOR**.
- `removeReaction(message, emoji, isMe)` (`:169-190`) — line `:180`
  `(!messageReaction.c() || z2) ? false : false` is **JADX-BROKEN**; the resulting `me` flag is
  unrecoverable. Reference also guards `if (!z2 || reactionsMap.get(c).c())` (`:173`), i.e. a
  *me*-removal is ignored when `me` was already false. Target has no such guard (`:331-337`). **MAJOR**.
- Reaction storage shape: reference keeps an **ordered map keyed by `emoji.c()`** (`getReactionsMap()` /
  `LocalMessageCreatorsKt.createWithReactions`), collapsing to `null` when empty (`:182-184`). Target
  keeps a `List<MessageReaction>` and hand-rolls emoji equality in `sameEmoji`
  (`StoreMessages.kt:398-399`). **MAJOR** — ordering and the null-vs-empty distinction are lost.
- `updateMessages(ApiMessage)` (`:400-412`) — note the reference's own control flow is suspicious
  (`publishIfUpdated()` is only reached in the `!isChannelActive` branch); target's
  `updateMessage` always publishes (`StoreMessages.kt:73-81`). Flagging as a **reference oddity**, not
  a target defect.
- `TreeMap` comparator: reference constructs `new TreeMap<>(MessageUtils.getSORT_BY_IDS_COMPARATOR())`
  at `:289` and `:395`. Target uses a bare `TreeMap<Long, Message>()` (natural `Long` order,
  `StoreMessages.kt:34`, `:48`, `:61`, `:97`). If `SORT_BY_IDS_COMPARATOR` special-cases local/negative
  ids, ordering diverges. **MAJOR — must verify `MessageUtils.getSORT_BY_IDS_COMPARATOR()`**.
- `invalidate()` (`:303-311`) adds `selectedChannelId` unconditionally; target guards `> 0`
  (`StoreMessages.kt:116`). **MINOR** (target is safer).
- `deleteMessages` requires `isChannelActive` (`:259`); target does not (`StoreMessages.kt:84-91`) but
  also purges the nonce index (`:87`) which reference never does. DIVERGENT/EXTRA.
- Cache sizing constants match: `CACHE_MAX_CHANNELS=8`, 20 for selected / 10 for others,
  `MAX_MESSAGES_PER_CHANNEL=200`, trim to `100`. ✔ (`:37-41`, `:106` vs `StoreMessages.kt:174-181`).

### 1C. `StoreLocalMessagesHolder`

#### MISSING FIELDS (`StoreLocalMessagesHolder.java:38-45`)
- `Gson gson` + `SharedPreferences sharedPreferences` — the reference persists **manually** via
  `SharedPreferencesProvider` under key `"STORE_LOCAL_MESSAGES_CACHE_V11"` (`:72-77`, `:191-196`).
  Target uses `Persister` (`StoreMessages.kt:185`). DIVERGENT — same key, different serializer/codec.
- `boolean cacheEnabled` (`:38`) — **MISSING**; same unconditional-write problem as 1B.
- `Map cacheSnapshot` (`:44`) — **MISSING** (write dedupe, `:70`).
- `Set<Long> updatedChannels` (`:45`) — **MISSING**.

#### MISSING METHODS / BEHAVIOR
- `init(cacheEnabled)` restore wraps deserialization in `try/catch` → `clearCache()` +
  `Logger.e(AppLog.g, "Error restoring cached local messages", e)` (`:217-220`). Target `restore()`
  (`StoreMessages.kt:193-200`) has **no error recovery and no logging** — a corrupt blob will propagate.
  **MAJOR**.
- `deleteMessage(Message)` public overload (`:136-152`) — target only has
  `deleteMessage(channelId, nonce)` / `deleteMessage(channelId, messageId)`. MINOR.
- `messageCacheTryPersist()` is `@StoreThread` and dedupes; target persists on every publish. MINOR.
- `getFlattenedMessages()` reads the **snapshot** (`:156`); target matches (`StoreMessages.kt:247`). ✔

#### EXTRA
- `LocalEntry(nonce, message)` wrapper (`StoreMessages.kt:187`) — reference stores bare `Message`
  and finds by `message.getNonce()` (`:160-180`). Divergent; adds a nonce field to the persisted
  schema under the **same cache key**, so an old blob will not deserialize. **MAJOR (migration break)**.

---

## Pair 2 — `stores/StoreMessagesLoader.kt` vs `stores/StoreMessagesLoader.java`

**SEVERITY: BLOCKER** — 5 blocker-class, 6 major, 4 minor.

#### MISSING FIELDS (reference `:40-59`)
| Reference | Target (`StoreMessagesLoader.kt:43-52`) |
|---|---|
| `boolean backgrounded` (default **true**, `:366`) | **MISSING** — no foreground gating at all |
| `boolean hasConnected` (`:49`) | **MISSING** |
| `Set<Long> detachedChannels` (`:47`, seeded `emptySet`) | **MISSING** |
| `StoreChat.InteractionState interactionState` (`:50`) | **MISSING** |
| `int loadingMessagesRetryJitter = 2000` (`:53`) | **MISSING** |
| `Subscription delayLoadingMessagesSubscription` (`:46`) | **MISSING** (target has only one `retrySubscription`) |
| `Dispatcher dispatcher`, `StoreStream stream` | **MISSING** (target injects a `loader` lambda instead) |
| `SerializedSubject<ChannelChunk> channelMessageChunksSubject` backed by `BehaviorSubject.k0()` (`:359`) | target uses `PublishSubject` (`:45`) — late subscribers lose the last chunk. **MAJOR** |
| `SerializedSubject<Long> scrollToSubject` backed by `BehaviorSubject.l0(null)` (`:363`) | target uses `PublishSubject` (`:46`) — **MAJOR**, and no `clearScrollTo()` |

`ChannelLoadedState`: reference has exactly 5 fields (`:193-197`) — `isInitialMessagesLoaded`,
`isOldestMessagesLoaded`, `isLoadingMessages`, `isTouchedSinceLastJump`, `newestSentByUserMessageId: Long?`.
Target has all 5 **plus** `retryDelayMillis: Long = 1500` and `lastError: Throwable?`
(`StoreMessagesLoader.kt:39-40`) — EXTRA; the reference keeps retry delay **globally**
(`loadingMessagesRetryDelayMillis`), not per channel.

#### MISSING METHODS / BEHAVIOR
- `init(Context)` (`:572-577`) — subscribes to `stream.chat.observeInteractionState()` and
  `stream.messages.getAllDetached()`. **Entirely absent.** Without it the loader is blind to scroll
  position and detachment. **BLOCKER**.
- `handleChatInteraction(InteractionState)` (`:405-409`) — **MISSING**.
- `handleChatDetached(Set<Long>)` (`:398-402`) — **MISSING**.
- `handleBackgrounded(boolean)` (`:543-546`) — **MISSING**.
- `handleVoiceChannelJoined(channelId)` (`:567-569`, `@StoreThread`) — **MISSING**.
- `clearScrollTo()` (`:505-507`) — **MISSING**.
- `tryLoadMessages(delay, force, resetRetry, resetDelay, targetChannelId, targetMessageId)`
  (`:448-499`) — the single scheduling core. Target has no equivalent; `load()`
  (`StoreMessagesLoader.kt:112-120`) is a much thinner gate. Specifically **MISSING**:
  - the `delayLoadingMessagesSubscription` debounce and its cancel-on-`resetDelay` path (`:450-456`)
  - `backgrounded`/`authed` gating (`:473`)
  - the auto-load-more branch: `isAtTop && !isOldestMessagesLoaded` → load older;
    `isAtBottom && detachedChannels.contains(channelId)` → load newer (`:479-493`), including the
    `takeSingleUntilTimeout(observeMessagesForChannel(channelId))` hop to obtain the anchor message.
    Target exposes `loadBefore`/`loadAfter` (`:79-83`) but **nothing calls them** — infinite scroll
    is unreachable. **BLOCKER**.
- `jumpToMessage(channelId, messageId)` (`:579-609`) — reference runs a two-arm flow: (a) wait for the
  channel to be selected (`channelsSelected.observeId()` + `restartWhen`), then take a single
  `observeMessagesForChannel(channelId, messageId)` and scroll; (b) on connection-open, resolve the
  `Channel` with a 1 s/3 s timeout as a fallback. Target reduces it to a single
  `load(LoadRequest(AROUND))` (`StoreMessagesLoader.kt:85-89`). **BLOCKER**.
- `requestNewestMessages()` (`:617-625`) — if the channel isn't detached and initial messages are
  loaded, it emits `SCROLL_TO_LATEST (1L)` on `scrollToSubject` **instead of** re-fetching; only
  otherwise does it force a reload with `targetMessageId = 1L`. Target unconditionally calls
  `loadInitial(selectedChannelId)` (`StoreMessagesLoader.kt:92`). **MAJOR**.
- `handleConnected(false)` only resets state when `hasConnected` was already true (`:556-559`), and it
  always calls `tryLoadMessages(resetDelay = hasConnected)` (`:563`). Target clears all state on any
  disconnect and never re-arms on reconnect beyond `loadInitial` (`:59-70`). **MAJOR**.
- `handleChannelSelected(channelId)` (`:549-553`, `@StoreThread`) first resets
  `isTouchedSinceLastJump` on the **previous** channel (`StoreMessagesLoader$handleChannelSelected$1`),
  then switches. Target just sets and loads (`:72-76`). **MAJOR**.
- `handleLoadMessagesError(channelId)` (`:412-425`) — exponential backoff **with jitter**:
  `delay = delay*2 + Random().nextInt(2000)` capped at `loadingMessagesRetryMax`. Target
  (`StoreMessagesLoader.kt:150`) uses `delay*2` capped at 30 000 — **no jitter**. MINOR.
- `channelLoadedStatesReset()` (`:385-395`) — also unsubscribes `loadingMessagesSubscription` and
  resets the global retry delay, then logs. Target `reset()` (`:102-110`) is close but never logs.
- `log(String)` (`:444-446`) — `AppLog.i("[MessageLoader] " + …)`. **MISSING** entirely (7 call sites).
- `handleLoadedMessages(messages, channelId, messageId, before, after)` (`:436-442`) —
  **JADX BROKEN**: `"Method not decompiled"`, 296 instructions skipped. The exact derivation of
  `isInitial` / `isPresent` / `isAppendingTop` / `isJump` and of `newestSentByUserMessageId` **cannot be
  recovered from this dump**. Target's `complete()` (`StoreMessagesLoader.kt:122-162`) is therefore an
  unverifiable reconstruction; in particular target never populates `newestSentByUserMessageId` at all.
  **BLOCKER — needs smali.**

#### EXTRA / DIVERGENT
- `Direction` enum + `LoadRequest` data class (`StoreMessagesLoader.kt:15-22`) — no reference analogue
  (reference passes `before`/`after` `Long?` directly). Acceptable abstraction.
- `retry()`, `observeChunks()`, `observeScrollTo()`, `reset()` — target-only names; reference exposes
  `get()`, `getScrollTo()` (target aliases both, `:95`, `:97` ✔).
- Constants match: `SCROLL_TO_LAST_UNREAD = 0`, `SCROLL_TO_LATEST = 1`, `isScrollToAction`,
  `messageRequestSize = 50`, retry default 1500, max 30000. ✔

---

## Pair 3 — `stores/StoreMessagesMostRecent.kt` vs `stores/StoreMessagesMostRecent.java`

**SEVERITY: MAJOR** — 5 major, 3 minor.

#### MISSING FIELDS
- `StoreChannels storeChannels` (`:37`) — store dependency, used only by
  `updateParentChannelMostRecentIds`. **MISSING** from target ctor (`StoreMessagesMostRecent.kt:11-14`).

Everything else lines up: `mostRecentIds: HashMap<Long,Long>`, `mostRecentIdsCache: Persister("MOST_RECENT_MESSAGE_IDS")`,
`mostRecentIdsSnapshot`, `observationDeck`. ✔

#### MISSING METHODS / BEHAVIOR
- `handleGuildAdd(Guild)` (`:121-137`) — **MISSING**. New guilds never seed `lastMessageId`. **MAJOR**.
- `handleChannelUnreadUpdate(ModelChannelUnreadUpdate)` (`:88-91`) +
  `mostRecentIdsUpdateFromChannels(ModelChannelUnreadUpdate)` (`:44-50`) reading
  `ModelReadState.getLastMessageId()` — **MISSING**. **MAJOR**.
- `handleThreadCreateOrUpdate(Channel)` (`:146-151`) with the `ChannelUtils.H(channel)` thread filter —
  **MISSING**. **MAJOR**.
- `handleThreadListSync(ModelThreadListSync)` (`:154-164`) — **MISSING**. **MAJOR**.
- `updateParentChannelMostRecentIds(Channel)` (`:69-75`) — forum-post → parent-forum propagation
  (`ForumUtils.canAccessRedesignedForumChannels` + `ChannelUtils.H/j/q` + `storeChannels.getChannel(parent)`).
  **MISSING entirely**. **MAJOR**.
- `ChannelUtils.G(channel)` gate in `mostRecentIdsUpdateFromChannels(Collection)` (`:210`) — target's
  `handleChannelCreateOrUpdate` (`StoreMessagesMostRecent.kt:29`) updates for **every** channel type. **MAJOR**.
- Comparison: reference uses `MessageUtils.compareMessages(existing, candidate) < 0` (`:61`); target
  uses `messageId <= (mostRecentIds[channelId] ?: 0L)` (`:53`). `compareMessages` handles null and
  non-monotonic/local ids. **MAJOR — verify `MessageUtils.compareMessages`.**
- `handleConnectionOpen` thread handling: reference filters `guild.N()` (threads) through
  `ChannelUtils.H` (`:106-115`); target takes `guild.threads` unfiltered
  (`StoreMessagesMostRecent.kt:25`). MINOR.
- Snapshot: reference uses `CollectionExtensionsKt.snapshot$default(mostRecentIds, …)` and
  `Persister.set$default(cache, snapshot, false, …)` (`:174-176`); target does
  `HashMap(mostRecentIds)` + `cache.set(HashMap(snapshot))` (`:47-50`). Equivalent. ✔

#### EXTRA / DIVERGENT
- Target `restore()` (`:18`) reads the persister back into `mostRecentIds`. The reference **never**
  calls `mostRecentIdsCache.get()` — it only writes. Divergent (target is arguably a fix, but it changes
  boot state).
- Target `handlePreLogout()` (`:34-39`) — no reference counterpart. EXTRA.
- Target `getMostRecentIdsInternal()` (`:42`) and `observeRecentMessageIds(channelId)` overload — the
  latter matches reference `:190-205` ✔; the former is EXTRA.

---

## Pair 4 — `stores/StoreMessageState.kt` vs `stores/StoreMessageState.java` (+Kt)

**SEVERITY: MINOR** — 0 blocker, 0 major, 4 minor. Closest pair in the slice.

#### FIELDS — parity ✔
`dispatcher` + `BehaviorSubject<Map<Long, State>> messageStateSubject` (`:24-25`) vs
`dispatcher` + `subject` (`StoreMessageState.kt:8`, `:15`).
`State(visibleSpoilerNodeIndices: Set<Int> = emptySet, visibleSpoilerEmbedMap: Map<Int, Set<String>> = emptyMap)`
matches `:31-32`/`:104-106` exactly. ✔

#### METHODS — verified against the lambda bodies
- `revealSpoiler` / `revealSpoilerEmbed` / `revealSpoilerEmbedData` — all dispatcher-scheduled with the
  `index < 0` guard (`:174-194`). Target `:24-39` matches, **including** the fact that
  `revealSpoilerEmbed` *overwrites* the existing key with `emptySet()`
  (confirmed in `StoreMessageState$revealSpoilerEmbed$1$1.invoke`) and that `revealSpoilerEmbedData`
  unions into the existing set (confirmed in `StoreMessageState$revealSpoilerEmbedData$1$1.invoke`). ✔
- `getMessageState()`, `handleChannelSelected()`, `handleMessageDelete(ModelMessageDelete)`,
  `handleMessageUpdate(ApiMessage)` — all present. ✔
- `resetState(List<Long>)` (`:118-134`) only publishes when at least one id was present; target's
  `if (states.keys.removeAll(...)) publish()` (`:19`) is equivalent. ✔

#### MINOR DIVERGENCES
1. Reference holds state **only** inside the `BehaviorSubject` (`n0()` reads current value, `:119`,
   `:139`); target keeps a separate mutable `HashMap states` plus a publish copy (`:14`, `:46`).
   Equivalent, but target's map is not `@Synchronized` and `updateState` is only dispatcher-confined
   for the `reveal*` paths — `handleMessageDelete`/`handleMessageUpdate` mutate it directly. MINOR.
2. `@StoreThread` annotations present on the reference `handle*`/`resetState`/`updateState`
   (`:117`, `:137`, `:155`, `:160`, `:168`); target annotates the `handle*` set but not `update`/`publish`. MINOR.
3. Target adds `handleMessageDelete(Collection<Long>)` and `handleMessageUpdate(messageId: Long)`
   overloads (`:19`, `:21`). EXTRA.
4. `StoreMessageStateKt.java` is an empty typealias file (9 lines) — nothing to port. ✔

---

## Pair 5 — `stores/StoreMessageUploads.kt` vs `stores/StoreMessageUploads.java` (+Kt)

**SEVERITY: MINOR** — target is a strict superset. 0 blocker, 1 major, 3 minor.

#### FIELDS
Reference: `HashMap<String, MessageUploadState> uploads` only (`:15`). Target adds `snapshot`
(`StoreMessageUploads.kt:17`) and takes `observationDeck` in the ctor (`:9`). EXTRA.

`MessageUploadState`:
| Reference | Target |
|---|---|
| `None` (object) `:25-31` | ✔ `:11` |
| `Preprocessing(numFiles: Int, displayName: String?, mimeType: String?)` `:36-46` | ✔ `:12` — exact match |
| `Uploading(uploads: List<SendUtils.FileUpload>)` `:121-129` | `Uploading(uploads: List<FileUpload>, progress: UploadProgress)` `:13` — **EXTRA `progress` field**, and the element type is `com.discord.utilities.rest.FileUpload`, not `SendUtils.FileUpload`. **MAJOR type divergence** if `SendUtils` is being reconstructed elsewhere. |

#### METHODS
- Reference: `updateState` (private, `@StoreThread`), `bindUpload(nonce, uploads)`,
  `getUploadProgress(nonce)`, `onPreprocessing(nonce, numFiles, displayName, mimeType)`. All 4 present
  in target (`:26-32`, `:19`). ✔
- Reference has **no** `snapshotData()` override and **no** observe API; `getUploadProgress` reads the
  live `uploads` map off-thread (`:193-197`). Target overrides `snapshotData()` (`:45-49`) and adds
  `observeUploadProgress(nonce)` / `observeAll()` (`:21-24`). EXTRA (an improvement).
- Reference **never removes entries** — the map grows unbounded. Target adds `onProgress`,
  `onSuccess`/`onFailure`/`onCancel` cleanup and `clear()` (`:34-43`). EXTRA.
- `StoreMessageUploadsKt.java` is an empty typealias file (`Nonce`) — nothing to port. ✔

---

## Pair 6 — `stores/StorePendingReplies.kt` vs `stores/StorePendingReplies.java`

The correct reference is **`stores/StorePendingReplies.java`** (it exists). `StoreMessageReplies.java`
is a *different* store — see the gap-check below.

**SEVERITY: MINOR** — 0 blocker, 1 major, 5 minor.

#### FIELDS
| Reference (`:22-25`) | Target (`StorePendingReplies.kt:11-23`) |
|---|---|
| `Dispatcher dispatcher` | ✔ |
| `ObservationDeck observationDeck` | ✔ |
| `SnowflakePartitionMap.CopiablePartitionMap<PendingReply> pendingReplies` (`:206`) | `HashMap<Long, PendingReply>` (`:22`) — DIVERGENT container; `snapshotData` uses `fastCopy()` (`:198`) vs a full `HashMap(...)` copy (`:55`). MINOR (perf) |
| `Map<Long,PendingReply> pendingRepliesSnapshot = emptyMap` | ✔ `:23` |

`PendingReply(messageReference: MessageReference, originalMessage: Message, shouldMention: Boolean, showMentionToggle: Boolean)`
— field-for-field match (`:31-34` vs `:15-20`) **except** reference `shouldMention` is a **`var`** with
`setShouldMention` (`:33`, `:126-128`); target uses `val` + `copy()` (`:43`). MINOR/DIVERGENT.

#### METHODS — full parity
`getPendingReply` ✔, `observePendingReply` ✔, `onCreatePendingReply(Channel, Message, shouldMention=true, showMentionToggle=true)` ✔
(defaults confirmed at `:146-153`), `onSetPendingReplyShouldMention` ✔, `onDeletePendingReply` ✔,
`handleMessageDelete(ModelMessageDelete)` ✔, `handlePreLogout()` ✔, `snapshotData()` ✔.
All mutators are dispatcher-scheduled in both. ✔

#### MINOR DIVERGENCES
1. Reference `handleMessageDelete` compares `modelMessageDelete.getMessageIds().contains(pendingReply.getMessageReference().c())`
   (`:164`) — i.e. `MessageReference.messageId`. Target does the same (`:48`) but via a null-safe `in`
   which silently no-ops on null; equivalent. ✔
2. Reference `handlePreLogout` always `markChanged()` (`:172-175`); target only when non-empty (`:54`). MINOR.
3. Target adds `onCreatePendingReply(channelId, guildId, message, …)` overload (`:29-33`). EXTRA.
4. Target types `observePendingReply` as `Observable<PendingReply?>`; reference is
   `Observable<PendingReply>` that can emit null through `connectRx`. MINOR.
5. Reference `getPendingReply` reads the snapshot (`:157`) — target matches ✔; but reference
   `handleMessageDelete` reads the **live** map (`:163`) — target matches ✔.

**MAJOR (cross-store):** `stores/StoreMessageReplies.java` (374 lines) — the *reply-reference resolution*
store — has **no target counterpart at all**. See gap-check §9.5.

---

## Pair 7 — `stores/StorePinnedMessages.kt` vs `stores/StorePinnedMessages.java`

**SEVERITY: MAJOR** — 0 blocker, 2 major, 5 minor.

#### MISSING FIELDS
- `Set<Long> updatedChannelIds` (`:36`, `:98`) — drives the incremental `snapshotData` copy
  (`:209-220`). **MISSING** in target; target rebuilds the whole map each snapshot
  (`StorePinnedMessages.kt:72-76`). MINOR (perf).

#### EXTRA FIELDS
- `loading: HashSet<Long>` (`:14`) and the injected `loader` lambda (`:11`) with a
  **default that returns `emptyList()`** — if `StoreStream` ever fails to wire it, pins silently render
  empty instead of failing loudly. Reference calls `RestAPI.getChannelPins(channelId)` directly
  (`:113-130`). DIVERGENT.

#### MISSING / DIVERGENT BEHAVIOR
- **`snapshot` is mutated outside `snapshotData()`**: `handlePinsUpdate` does
  `snapshot = snapshot - channelId` (`StorePinnedMessages.kt:37`). This writes the published snapshot
  from a handler, breaking StoreV2 snapshot confinement (readers can observe a torn map).
  No reference analogue. **MAJOR**.
- `handlePinsUpdate(channelId)` (`:34-40`) has **no reference counterpart** — the reference
  `StorePinnedMessages` has no `CHANNEL_PINS_UPDATE` handler; the pin list is refreshed only via
  `observeForChannel` → `loadPinnedMessages` dedupe. EXTRA.
- `handlePreLogout()` (`:70`) — no reference counterpart. EXTRA.
- `handleMessageUpdate(channelId, messageId, pinned, merge)` (`:49-56`) — EXTRA overload.
- Insertion of a newly-pinned message: reference `handleMessageUpdate` (`:177-191`) scans by
  `UtcDateTime` timestamp. **This region is mis-decompiled** — the loop
  `while (r8 < list.size() && g2 < j) r8++;` compares only against `list.get(0)`'s timestamp and never
  advances `j`, so the intended insertion index cannot be recovered. Target instead appends and
  `sortByDescending(Message::id)` (`:66`). **DIVERGENT — reference is unreadable here; do not treat
  target as verified.**
- Load ordering: reference stores the API order verbatim (`:104`); target sorts descending by id at
  load (`:29`). MINOR — but note reference's insert logic implies newest-first, so the target's sort is
  a plausible reconstruction.
- `Companion.getMessageIndex(list, messageId)` / `Companion.getMessage(list, messageId)` (`:46-84`) —
  target inlines both as `indexOfFirst` / `firstOrNull` (`:51`, `:23`). Equivalent. MINOR.
- `loadPinnedMessages` is **private** in the reference (`:109`) and dedupes on
  `pinnedMessagesSnapshot.containsKey`; target makes it public and dedupes on `channelId in snapshot`
  **plus** a `loading` set (`:26`). Divergent (target avoids duplicate in-flight requests — an
  improvement). MINOR.
- `getDispatcher()` / `getObservationDeck()` public accessors (`:133-139`) — target keeps both private.
  MINOR (used by the reference's `loadPinnedMessages$2` lambda).
- `handleMessageDeleteBulk(channelId, Collection<Long>)` ✔, `observeForChannel` ✔ (both call
  `loadPinnedMessages` first), `observePinnedMessage` ✔ (both `distinctUntilChanged`).

---

## Pair 8 — `stores/StoreChatInputEditing.kt` vs `stores/StoreChat.java`

**SEVERITY: BLOCKER** — the target file (29 lines) reconstructs roughly 8 % of `StoreChat` (484 lines).

`StoreChatInputEditing` is not a reference class; the reference equivalent is confirmed to be
`stores/StoreChat.java` (it owns `EditingMessage`).

#### MISSING FIELDS (reference `:27-33`)
| Reference | Target |
|---|---|
| `Dispatcher dispatcher` | **MISSING** — target is not dispatcher-confined, uses `@Synchronized` (`:11`, `:14`, `:19`, `:23`) |
| `ObservationDeck observationDeck` / `extends StoreV2` | **MISSING** — target is a plain class with a `BehaviorSubject` |
| `EditingMessage editingMessage` (single, global) | `LinkedHashMap<Long, Message> editing` keyed by channel (`:8`) — DIVERGENT shape |
| `PublishSubject<Event> eventSubject` | **MISSING** |
| `List<Long> expandedBlockedMessageGroups` | **MISSING** |
| `List<Long> expandedBlockedMessageGroupsSnapshot` | **MISSING** |
| `InteractionState interactionState` | **MISSING** |

#### MISSING NESTED TYPES
- `EditingMessage(message: Message, content: CharSequence)` (`:38-47`) — target stores only the
  `Message`; the in-progress **edit text is lost**. **BLOCKER**.
- `Event` sealed hierarchy: `Event.AppendChatText(text)` (`:115`), `Event.ReplaceChatText(text)` (`:168`).
  **MISSING**.
- `InteractionState(channelId, lastMessageId, isTouchedSinceLastJump, scrollState)` (`:321-481`) with
  `isAtTop`/`isAtBottom` (touch-gated, `:371-372`), `isAtTopIgnoringTouch`/`isAtBottomIgnoringTouch`/
  `isNearBottomIgnoringTouch` (`:448-462`), the secondary `LinearLayoutManager` ctor (`:479-481`), and
  `Companion.getScrollState(LinearLayoutManager)` (`:343-358`) with constants
  `NEAR_EDGE_THRESHOLD = 15`, `SCROLLED = 0`, `SCROLLED_BOTTOM = 1`, `SCROLLED_TOP = 2`,
  `SCROLLED_NEAR_BOTTOM = 4` (`:323-327`). **MISSING ENTIRELY** — this is the direct cause of the
  `StoreMessagesLoader` gaps in Pair 2. **BLOCKER**.
  Note: reference `:354` `if ((findLastVisibleItemPosition == itemCount || findLastVisibleItemPosition >= max) ? true : true)`
  is **JADX-BROKEN** (tautology) — the real bottom predicate is not recoverable from this dump.
  Likewise `:372` `this.isAtBottom = (z2 && isAtBottomIgnoringTouch()) ? false : false;` is broken.

#### MISSING METHODS
`observeEvents()` `:263`, `getExpandedBlockedMessageGroups()` `:249`,
`observeExpandedBlockedMessageIds()` `:269`, `getInteractionState()` `:253`,
`observeInteractionState()` `:275` (filter-null + distinct), `getEditingMessage()` `:245`,
`observeEditingMessage()` `:257`, `setInteractionState()` `:293`, `setEditingMessage()` `:289`,
`toggleBlockedMessageGroup(messageId)` `:304`, `appendMention(user, guildId)` `:230-243`
(also fires `StoreGatewayConnection.requestGuildMembers`), `replaceChatText(text)` `:283`,
`snapshotData()` `:299-302`.

Target's `start`/`stop`/`clear`/`get`/`observe` (`:11-25`) have **no reference counterparts** by name;
`start`≈`setEditingMessage(non-null)`, `stop`≈`setEditingMessage(null)`. All dispatcher confinement,
StoreV2 snapshotting, and logout/reset semantics are absent.

---

## 9. Gap-check — reference stores with no (or partial) target counterpart

### 9.1 `stores/StoreMessageReactions.java` (438 lines) — **NO TARGET COUNTERPART. BLOCKER.**
Missing wholesale:
- Fields (`:28-32`): `dispatcher`, `observationDeck`, `userStore: StoreUser`,
  `reactions: Map<Long, Map<String, EmojiResults>>` (messageId → emojiKey → results), plus the snapshot.
- `EmojiResults` sealed type (`:37-232`): `Loading` (`:132`),
  `Failure(channelId, messageId, emoji)` (`:42-45`),
  `Users(users: LinkedHashMap<Long, User>, channelId, messageId, emoji)` (`:143-147`) with `deepCopy()` (`:201`).
- Methods: `ensureReactionResults` `:267`, `fetchReactions(channelId, messageId, emoji)` `:283`,
  `getReactionEmojiRequestParam(emoji)` `:296`, `handleLoadReactionUsersFailure` `:306`,
  `handleReactionUsers(channelId, messageId, emoji, users)` `:317`,
  `deleteEmoji(channelId, messageId, emoji, userId)` `:330`,
  `forceRetryFetchReactions` `:335`, `handleConnectionOpen()` `:341`,
  `handleReactionAdd/Remove/RemoveAll/RemoveEmoji` `:349-397`,
  `observeMessageReactions(channelId, messageId, emoji)` `:399`, `snapshotData()` `:409`.
- `StoreMessageReactionsKt.java` (typealiases) — trivial.

The target has only reaction **count** mutation inlined in `StoreMessages.handleReaction`
(`StoreMessages.kt:322-350`). The whole "who reacted" user-list subsystem — fetch, paging, loading /
failure states, retry — is absent.

### 9.2 `stores/StoreMessageAck.java` (776 lines) — **NO TARGET COUNTERPART.** (Target has
`stores/StoreReadStates.kt` + `workers/MessageAckWorker.kt`; a full StoreReadStates↔StoreMessageAck
audit belongs to the read-state slice, but the following structures are named here so they are not lost.)
Missing structures: `Ack(messageId, viewed, isLockedAck)` (`:63-66`),
`Persister<Map<Long,Ack>> acksPersister` (`:52`), `acksSnapshot` (`:53`),
`Subscription threadSyncSubscription` (`:58`), `restAPI` (`:56`), `stream` (`:57`),
`PendingAck(channelId, ack)` + `PendingAck.EMPTY` (`:255-321`),
`Companion.ThreadAckState { NotThread, Thread(canAckThread) }` (`:161-224`) with
`isNonThreadChannel` / `isThreadAndAbleToAck` / `isThreadAndUnableToAck` (`:235-243`).
Missing methods: `getPendingAck(Observable<Long>, …)` `:363`, `postPendingAck(…)` `:507`,
`getThreadAckStateInternal(...)` `:446`, `observeThreadAckState(channelId)` `:500`,
`observeActiveThreadIdsWithPrevious()` `:471`, `updateThreadAcks(added, removed)` `:559`,
`updateAcks(channelId, ack)` `:546`, `pruneAcks()` `:527`, `internalAck(channel, …)` `:465`,
`ack(channelId, local, viewed)` `:591`, `markUnread`, `getAll` `:595` / `getAllInternal` `:600` /
`getForChannel` `:604`, `observeAll` / `observeForChannel`, `handleChannelSelected` `:609`,
`handleConnectionOpen` `:623`, `handleGuildCreate` `:632`.

### 9.3 `stores/StoreSlowMode.java` (232 lines) — **NO TARGET COUNTERPART. BLOCKER for chat input.**
Missing: fields `messageSendNextSendTimes` / `threadCreateNextSendTimes` (`:31`, `:34`) and their
`BehaviorSubject<HashMap<Long,Long>>` publishers (`:32`, `:35`), the per-channel cooldown-observable
caches `channelMessageSendCooldownObservables` / `channelThreadCreateCooldownObservables` (`:28-29`),
`clock`, `stream`, `COOLDOWN_BUFFER_MS = 1000` (`:26`).
Missing type `Type { MessageSend, ThreadCreate }` (`:52-82`).
Missing methods: `getChannelCooldownObservable(channelId, type)` (`:99-174`) — permission bypass via
`PermissionUtils.hasBypassSlowmodePermissions`, a 1 s-tick countdown that terminates at 0, `.replay(1)`
with auto-removal on unsubscribe; `removeChannelCooldownObservable` (`:188`);
`onCooldownInternal(channelId, cooldownMs, type)` (`:177`); `observeCooldownSecs(channelId?, type)` (`:198`);
`onCooldown(channelId, cooldownMs, type)` (`:209`, adds the 1 s buffer);
`onMessageSent(channelId)` (`:215`) and `onThreadCreated(parentChannelId)` (`:224`) — both read
`channel.rateLimitPerUser` via `StoreChannels.findChannelByIdInternal`.
Target only *classifies* `MessageResult.Slowmode` in `MessageSendCoordinator.kt:242` and drops it —
nothing stores or counts down a cooldown, so the chat input can neither disable itself nor show a timer.

### 9.4 `stores/StoreChat.java` — covered in Pair 8. **BLOCKER.**

### 9.5 `stores/StoreMessageReplies.java` (374 lines) — **NO TARGET COUNTERPART. MAJOR/BLOCKER.**
(Distinct from `StorePendingReplies`.) Missing: `MessageCache<K,V> extends LinkedHashMap` with
`removeEldestEntry → size() > 1000` (`:59-100`); `MessageState { Deleted, Unloaded, Loaded(message) }`
(`:105-191`); fields `repliedMessagesCache`, `channelMap: HashMap<Long, Set<Long>>`,
`repliedChannelMessagesCacheSnapshot`, `repliedMessagesCacheSnapshot`, `storeMessages` dependency,
`Companion.NO_RESULTS` (`:35-42`); methods `getCachedChannelMessages` `:207`, `updateCache` `:252`,
`deleteMessage` `:198`, `processMessage(ApiMessage|Message)` `:213`/`:343`,
`processReferencedMessage` `:217` (incl. the type-19 recursion), `snapShotChannelMessages` `:230`,
`snapShotAllMessages` `:225`, `observeMessageReferencesForChannel` `:316`, `getAllMessageReferences` `:264`,
`handleLoadMessages(Collection)` `:269`, `handleMessageCreate` `:281`, `handleMessageUpdate` `:306`,
`handleMessageDelete(ModelMessageDelete)` `:289`, `snapshotData` `:323`.
Without this, inline reply previews (referenced-message resolution, "original message was deleted",
"loading") cannot render.

### 9.6 `utilities/messagesend/MessageQueue.java` vs target `utilities/messagesend/MessageQueue.kt`

**SEVERITY: MAJOR** (queue core is faithful; the *payload* half is missing).

| Reference field (`:51-58`) | Target (`MessageQueue.kt:11-17`) |
|---|---|
| `ContentResolver contentResolver` | **MISSING** — target's queue is transport-agnostic (`sender` lambda, `:8`) |
| `ExecutorService executorService` | replaced by `MessageQueueScheduler` (`:9`) — DIVERGENT, equivalent |
| `Clock clock` | `scheduler.now()` — equivalent |
| `InflightRequest inFlightRequest` (baseRequest + networkSubscription + drainListener) `:97-122` | split into `current` + `sendSubscription` (`:12-13`) — equivalent |
| `boolean isDraining` | implicit via `current != null` — equivalent |
| `Backoff networkBackoff = Backoff(5000, 3600000, …)` (`:132`) | `networkRetryMs` doubling capped at `MESSAGE_TIMEOUT_MS` (`:16`, `:107`) — **MISSING the shared `Backoff` type**; roughly equivalent. MINOR |
| `ArrayDeque<MessageRequest> queue` | ✔ `:11` |
| `Subscription retrySubscription` | `retry: MessageQueueCancellable` ✔ |
| `DEFAULT_MESSAGE_TIMEOUT_MS = 3600000` | ✔ `:138` |
| `DEFAULT_NETWORK_INITIAL_FAILURE_RETRY_MS = 5000` | ✔ `:139` |
| `DEFAULT_RETRY_MS = 100` (`:50`) | **MISSING** — target's fallback for a missing `retryAfterMs` is `1000L` (`MessageSendCoordinator.kt:241-242`). MINOR |

Retry **policy is faithful** (verified against `MessageQueue$processNextRequest$listener$1.invoke2`):
only `RateLimited` (timer, re-`addFirst`) and `NetworkFailure` (`Backoff.fail`, re-`addFirst`) retry;
everything else completes and calls `backoff.succeed()` then `processNextRequest()`. Target matches at
`MessageQueue.kt:103-112`. ✔ `DrainListener`'s `AtomicBoolean` idempotency (`:76`, `:86-91`) is replaced
by the `attempt` counter guard (`:101`) — equivalent. ✔
`enqueue` ✔, `cancel(requestId)` ✔ (both handle in-flight and queued), `handleConnected()` ✔ (target
additionally resumes non-network waits, `:39-47` — divergent/better), timeout-drop loop ✔ (`:70-72`).
Target adds `cancelAll()` (`:50-64`) — EXTRA.

**MISSING — the request-execution half (BLOCKER for attachments/commands):**
- `doSend(MessageRequest.Send, DrainListener)` (`:151-157`) — **JADX BROKEN**
  (`"Method not decompiled"`, 361 instructions). Multipart/attachment send path is unrecoverable from
  this dump.
- `doEdit(MessageRequest.Edit, DrainListener)` (`:135-142`).
- `doSendApplicationCommand(MessageRequest.SendApplicationCommand, DrainListener)` (`:159-208`) —
  `SendUtils.getSendCommandPayload` + `Preprocessing`/`ReadyToSendCommand` progress callbacks +
  multipart or JSON dispatch. **Target has no `SendApplicationCommand` request type at all.**
- `handleError(Error, DrainListener, Message?)` (`:211-252`) — the canonical error→result mapping.
- `handleSuccess(ApiMessage, DrainListener)` (`:262-264`).

**`MessageRequest` shape divergence (MAJOR):**
Reference `MessageRequest.Send` carries `message: Message`, `attachments: List<Attachment<?>>`,
`activity: Activity`, `onPreprocessing: (SendPayload.Preprocessing) -> Unit`,
`onProgress: (List<SendUtils.FileUpload>) -> Unit` (`MessageRequest.java:67-124`);
`Edit` carries `channelId`, `messageId`, `content`, `allowedMentions` (`:30-59`);
plus a third variant `SendApplicationCommand(applicationCommandSendData, attachments, message, onPreprocessing, onProgress)` (`:142-207`).
Target `MessageRequest.kt:9-17` reduces both variants to `(requestId, attemptTimestamp, onCompleted)`
and drops `SendApplicationCommand` entirely — no payload, no preprocessing/progress hooks. Upload
progress must therefore come from `StoreStream.sendUploads`, outside the queue.

**`MessageResult` shape divergence (MAJOR):**
| Reference (`MessageResult.java`) | Target (`MessageResult.kt`) |
|---|---|
| `Success(message: ApiMessage)` `:125-126` | `data object Success` — payload dropped (reconciliation moved to `MessageSendCoordinator.Update.Reconciled`) |
| `CaptchaRequired(error: Error, channelId: Long, nonce: String)` `:41-44` | `CaptchaRequired(siteKey: String?, rqtoken: String?)` — **incompatible**; `StoreMessages.resendMessageWithCaptcha(channelId, nonce, payload)` (`StoreMessages.java:614`) depends on the reference shape |
| `AutoModBlock(error: Error, message: Message)` `:17-19` | `data object AutoModBlock` — payload dropped, cannot surface the blocked message / rule |
| `UnknownFailure(error: Error)` `:154-155` | `UnknownFailure(cause: Throwable?)` — DIVERGENT (`Error` vs `Throwable`) |
| `Slowmode(cooldownMs)` `:109` / `RateLimited(retryAfterMs)` `:93` / `ValidationError(message)` `:183` / `NetworkFailure` / `NoValidContent` / `Timeout` / `UserCancelled` | ✔ all present |

---

## Summary table

| Pair | BLOCKER | MAJOR | MINOR |
|---|---|---|---|
| 1 StoreMessages (+2 holders) | 9 | 7 | 6 |
| 2 StoreMessagesLoader | 5 | 6 | 4 |
| 3 StoreMessagesMostRecent | 0 | 6 | 3 |
| 4 StoreMessageState | 0 | 0 | 4 |
| 5 StoreMessageUploads | 0 | 1 | 3 |
| 6 StorePendingReplies | 0 | 1* | 5 |
| 7 StorePinnedMessages | 0 | 2 | 5 |
| 8 StoreChatInputEditing / StoreChat | 3 | 2 | 1 |
| 9 gap-check (Reactions / Ack / SlowMode / MessageReplies / MessageQueue) | 4 | 4 | 3 |
| **Total** | **21** | **29** | **34** |

\* the missing `StoreMessageReplies` store is counted under §9.5.

## JADX-broken regions (do not guess — pull smali)
1. `StoreMessages.trackFailedLocalMessageResolved` — 277 instructions skipped.
2. `StoreMessagesLoader.handleLoadedMessages` — 296 instructions skipped. Blocks verification of
   chunk-flag derivation and `newestSentByUserMessageId`.
3. `MessageQueue.doSend` — 361 instructions skipped. Blocks the attachment/multipart send path.
4. `StoreMessagesHolder.updateDetachedState:209` — `z5 = (...) ? false : false` (dead ternary).
5. `StoreMessagesHolder.removeReaction:180` — `(!me || isMe) ? false : false` (dead ternary).
6. `StorePinnedMessages.handleMessageUpdate:177-191` — degenerate timestamp insertion loop.
7. `StoreChat$InteractionState$Companion.getScrollState:354` — `(a || b) ? true : true` (tautology);
   and `InteractionState.<init>:372` — `(z2 && isAtBottomIgnoringTouch()) ? false : false`.
