# Parity Audit — channel / read-state stores

TARGET  `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (JADX, Discord Android 126.21)

Scope: 6 store pairs + gap-check of 12 adjacent reference stores.
Read-only audit. No target file was modified.

---

## Cross-cutting finding: dispatcher confinement is largely unannotated

`@StoreThread` (defined `stores/Store.kt` in target) exists but is barely applied in this slice.
Occurrence counts (target `@StoreThread` vs reference `StoreThread`):

| store | target | reference |
|---|---|---|
| StoreChannels | **0** | 27 |
| StoreChannelsSelected | 1 | 16 |
| StoreChannelMembers | 6 | 12 |
| StoreCollapsedChannelCategories | **0** | 2 |
| StoreUserGuildSettings | 4 | 11 |
| StoreReadStates | 10 | 6 |

`StoreChannels.kt` has **zero** confinement annotations despite mutating `channelsByGuild`,
`channelNames`, `threadsById` from every handler — SEVERITY **MAJOR** on its own.

---

## PAIR 1 — `stores/StoreChannels.kt` vs `stores/StoreChannels.java` (+ `StoreChannelConversions.java`)

Reference read in full (1039 lines). Two methods are **not decompilable** by JADX and could not be
verified (stated explicitly, not guessed):
- `StoreChannels.handleGroupDMRecipient(ChannelRecipient, boolean)` — ref:405-411
  `"Method dump skipped, instructions count: 320" / UnsupportedOperationException`.
- `StoreChannels.storeThread$app_productionGoogleRelease(Channel, long, Channel)` — ref:917-1004
  smali-level dump only; readable enough to recover intent, but the `Channel.a(...)` copy mask
  (`-131077`) cannot be mapped to field names with confidence.

### MISSING FIELDS
All 18 reference instance fields are present with matching types. **No field gaps.**
(`channelNames`, `channelNamesSnapshot`, `channelsByGuild`, `channelsByGuildSnapshot`,
`channelsByIdSnapshot`, `channelsCache`, `dispatcher`, `getLurkingGuildIds`, `handledReadyPayload`,
`initializedForAuthedUser`, `isAuthenticated`, `isConnectionOpen`, `isStoreInitTimedOut`,
`observationDeck`, `observeChannelPermissionsForGuild`, `preloadedThreads`, `threadsById`,
`threadsByIdSnapshot`, plus `Companion.PRIVATE_CHANNELS_ID` and `InitializedUpdateSource`.)

Divergence: target `StoreChannels.kt:15-18` gives **default values** to `observeChannelPermissionsForGuild`
(`{ Observable.just(emptyMap()) }`), `getLurkingGuildIds` (`{ emptyList() }`) and `isAuthenticated`
(`{ false }`). Reference ctor (ref:1007) has **no defaults** on these three — only `channelsCache`
defaults. A silently-empty-permissions default makes `observeDefaultChannel` return nothing if the
wiring is ever forgotten. **MINOR.**

### MISSING METHODS / BEHAVIOR

| # | Reference member | Target | Sev |
|---|---|---|---|
| 1.1 | `findChannelByIdInternal$app(long)` @StoreThread (ref:136) — reads **live** `threadsById`/`channelsByGuild` | **ABSENT**. Target has only `findChannelById` (`StoreChannels.kt:173`) and it reads the **live mutable** maps, i.e. it is the *internal* variant published as public API. Reference's public `findChannelById` (ref:121) reads `threadsByIdSnapshot`/`channelsByGuildSnapshot`. Off-dispatcher callers now touch un-snapshotted `HashMap`s. | **BLOCKER** (data race + missing method) |
| 1.2 | `getChannelsForGuildInternal$app(long): Map<Long,Channel>?` @StoreThread (ref:213) | **ABSENT**. Target has only the snapshot-based `getChannelsForGuild` (`:175`). | MAJOR |
| 1.3 | `observeDirectShareCandidates(Context)` (ref:734-774) — filters non-empty, orders by `ChannelFrecencyTracker` from `StoreChannelsSelected.getFrecency()`, keeps DMs only, `take(ShortcutManagerCompat.getMaxShortcutCountPerActivity(context))` | **ABSENT** entirely. Direct-share / launcher-shortcut targets cannot be produced. | MAJOR |
| 1.4 | `handleChannelOrThreadCreateOrUpdate` early guard `if (ChannelUtils.y(channel)) return;` (ref:262) — `y` = `isManaged` (`ChannelUtils.java:367`, `channel.b() != 0`) | **ABSENT** (`StoreChannels.kt:104`). Managed (bot-owned/app) channels are now stored. | MAJOR |
| 1.5 | `handleConnectionOpen` private-channel filter `!ChannelUtils.y(it)` = **not managed** (ref:345) | Target `StoreChannels.kt:77` filters `.filterNot(::isThread)` — **wrong predicate**. Managed private channels are admitted; thread-typed private channels are dropped (reference keeps them). | MAJOR |
| 1.6 | `storeThread(thread, fallbackGuildId = 0, parentChannel: Channel? = null)` — **public** `internal`, 3 params, nsfw derived from `parentChannel?.r()` else `getChannelInternal(thread.guildId, thread.parentId)?.r()` else `thread.r()` (ref:917) | Target `StoreChannels.kt:240` is **private, 2 params**, no `parentChannel` override, and resolves the parent via `channelsByGuild[resolvedGuildId]` rather than `thread.guildId`. Callers outside the class cannot seed a thread with a known parent. | MAJOR |
| 1.7 | `handleMessagesLoaded(List<Message>)` (ref:461) | Target `:158` takes `Collection<Channel>` — caller must pre-extract `message.thread`. Signature divergence; also target routes through `handleDiscoveredThreads` which **adds every discovered thread to `preloadedThreads`**, whereas reference does that only in `handleMessagesLoaded`, not `handleSearchFinish`. | MAJOR |
| 1.8 | `handleSearchFinish(SearchState)` (ref:478) — walks `searchState.threads` **and** `searchState.hits[].thread` | Target `:160` takes `Collection<Channel>`; the `hits[].thread` path is lost unless the caller flattens it, and it wrongly populates `preloadedThreads`. | MAJOR |
| 1.9 | `handleThreadListSync(ModelThreadListSync)` (ref:516) | Target `:153` takes `(guildId, threads)`. Signature divergence only; behavior equivalent. | MINOR |
| 1.10 | `observeThreadsFromMessages(List<Message>)` (ref:847) | Target `:226` takes `Collection<Long> threadIds`. Signature divergence. | MINOR |
| 1.11 | `observeDefaultChannel(guildId)` (ref:686) — `observeChannelPermissionsForGuild(id).switchMap { observeChannelsForGuild(id, type=0).map { … } }`, i.e. **re-emits when channels change**, and sorts with `ChannelUtils.getSortByNameAndType` | Target `:220` reads `channelsByGuildSnapshot[guildId]` **inside the permissions `map`** — never re-subscribes to the channel stream, so it will not re-emit when a channel is added/removed/renamed. It also sorts by `position, id` instead of name+type, and hard-codes `VIEW_CHANNEL = 1L shl 10` (`:288`) instead of `Permission.VIEW_CHANNEL`. | **BLOCKER** (stale observable) |
| 1.12 | `ChannelUtils.getDisplayName` (`ChannelUtils.java:128`) — joins recipients **only when `isPrivate(channel)` and name is null-or-empty**, `?: ""` | Target `displayName` (`:276`) joins recipients whenever `name == null`, ignores empty-string names, ignores `isPrivate`, and joins on `it.username` rather than the reference's `ChannelUtils$getDisplayName$1` projection. | MAJOR |
| 1.13 | `handleChannelOrThreadCreateOrUpdate` writes only when `!areEqual(channel, existing)` (ref:279) and re-runs `storeThread(child, guildId, channel)` for **every** child thread when nsfw flips (ref:288) | Target `:110-120` always writes and mutates only `nsfw` on child threads via `entry.setValue(copy(nsfw=…))`, skipping the guildId re-resolution the reference does. | MINOR |
| 1.14 | `handleGuildAdd` (ref:414) has **no** `unavailable` guard | Target routes through `storeGuild` (`:229-230`) which returns early on `guild.unavailable`. Divergent (arguably a fix, but a behavior change on `GUILD_CREATE` for unavailable→available transitions). | MINOR |
| 1.15 | reference `handleChannelOrThreadDelete` calls `markChanged()` **only** on actual removal, up to 3 times (ref:298-318) | Target `:129` calls it unconditionally. | MINOR |
| 1.16 | `snapshotData` uses `CollectionExtensionsKt.snapshot(...)` (copy-on-write w/ capacity+loadFactor) (ref:903-907) | Target `:44` uses `toMap()`. Semantically OK, allocation profile differs. | MINOR |
| 1.17 | reference `handleConnectionOpen` stores private channels **verbatim** (ref:352) | Target `:75-83` hydrates `recipients` from `payload.users + payload.me` keyed by id. **EXTRA** behavior not in reference. | MINOR (extra) |
| 1.18 | reference has **no** `reset()` | Target `:54-66` adds `reset()`. **EXTRA** (logout path is modelled differently in the reconstruction). Verify no double-clear. | MINOR (extra) |
| 1.19 | reference has **no** public `preloadThread(Channel)` | Target `:162` adds it. **EXTRA**. | MINOR (extra) |
| 1.20 | `@StoreThread` on ~20 methods (`updateInitializationState`, `findChannelByIdInternal`, `findChannelsByCategoryInternal`, `getChannelInternal`, `getChannelNamesInternal`, `getChannelsByGuildInternal`, `getChannelsByIdInternal`, `getChannelsForGuildInternal`, `getGuildChannelInternal`, `getThreadsByIdInternal`, `getThreadsForChannelInternal`, `getThreadsForGuildInternal`, all `handle*`, `init`, `snapshotData`, `storeThread`) | **0 annotations** in `StoreChannels.kt`. | MAJOR |

### `StoreChannelConversions.java` — **MISSING ENTIRELY**
Reference `stores/StoreChannelConversions.java` (29 lines, fully decompiled):
`extends StoreV2`; field `private long selectedChannelId`;
`@StoreThread handleChannelCreateOrUpdate(Channel?)` — if `channel.s() != 0 && selectedChannelId == channel.s()`,
calls `ChannelSelector.Companion.getInstance().selectChannel(0L, channel.id, null, null)`
(auto-follow when the currently-selected channel is converted into a new channel);
`@StoreThread handleChannelSelected(long)`.
Grep of the whole target tree for `ChannelConversions` / `ChannelSelector`: **0 hits.**
**SEVERITY: MAJOR.**

### Also absent from the target tree (grep, 0 hits each)
`ChannelUtils` (the entire `com.discord.api.channel.ChannelUtils` helper — target inlines ad-hoc
predicates `isThread`/`isPrivate` at `StoreChannels.kt:278-283`, missing `isManaged`, `isGuildTextyChannel`,
`isTextOrVoiceChannel`, `isArchivedThread`, `isAnnouncementChannel`, `isThreaded`, `getSortByNameAndType`,
`getDisplayNameOrDefault`, `hasFlag/isPinned`, `isSystemDM`, `isHQDirectoryChannel`, `isStageVoiceChannel`,
`shouldPlayJoinLeaveSounds`), `ChannelFrecencyTracker`, `FrecencyTracker`.

### SEVERITY — PAIR 1: **BLOCKER**
BLOCKER 2 · MAJOR 10 · MINOR 9 · (2 JADX-unverifiable)

---

## PAIR 2 — `stores/StoreChannelsSelected.kt` vs `stores/StoreChannelsSelected.java`

Reference read in full (917 lines). No broken JADX regions in this file.
This is the **worst pair in the slice**: the target is a ~107-line rewrite of a 917-line store and
drops the entire two-level selection model.

### MISSING FIELDS

| Reference field (ref:44-67) | Target | Sev |
|---|---|---|
| `frecency: ChannelFrecencyTracker` (ref:50, initialised from `frecencyCache.get()`, ref:611) + public `getFrecency()` (ref:726) | **ABSENT** — and `StoreChannels.observeDirectShareCandidates` depends on it (gap 1.3) | MAJOR |
| `frecencyCache: Persister<ChannelFrecencyTracker>("CHANNEL_HISTORY_V3", ChannelFrecencyTracker())` (ref:51,609) | **ABSENT** — second persistence handle entirely missing | MAJOR |
| `handledReadyPayload: boolean` (ref:52) | **ABSENT** | MAJOR |
| `initializedForAuthedUser: boolean` (ref:53) | **ABSENT** | **BLOCKER** |
| `isStoreInitTimedOut: boolean` (ref:54) | **ABSENT** | MAJOR |
| `previouslySelectedChannel: ResolvedSelectedChannel` (ref:56, default `Uninitialized`) | Target has `previousIdSnapshot: Long = UNSELECTED` (`:22`) — **type downgrade** from full state to bare id | MAJOR |
| `selectedChannel: ResolvedSelectedChannel` (ref:57, **non-null**, default `Uninitialized`) | Target `selectedChannel: Channel? = null` (`:20`) — **type + nullability + default all differ**; `Uninitialized` vs `null` vs `Unselected` are three distinct reference states collapsed to one | **BLOCKER** |
| `userChannelSelections: Map<Long, UserChannelSelection>` (ref:63) | Target `selections: HashMap<Long, Long>` (`:19`) — **type downgrade**; loses thread-draft selections, peek-parent, analytics location | **BLOCKER** |
| `stream: StoreStream` (ref:62) — used for `getAuthentication().getAuthState()` and `getAnalytics()` | **ABSENT**; replaced by an `onResolvedChannelChanged: (Channel?) -> Unit` callback (`:17`) that no reference member corresponds to | MAJOR |
| `validateSelectedChannelSubscription: Subscription?` (ref:64) | **ABSENT** (see 2.b) | **BLOCKER** |
| `Companion.InitializedUpdateSource` (ref:66) | **ABSENT** | MAJOR |
| `ID_UNAVAILABLE = -1` (ref:46), `ID_UNINITIALIZED = -2` (ref:47), `ID_THREAD_DRAFT = -3` (ref:45) | **ABSENT**; target companion (`:101-105`) has only `UNSELECTED = 0` plus a target-invented `PRIVATE_CHANNELS_ID = 0` | MAJOR |
| `selectedChannelIdsCache: Persister<Map<Long,Long>>("CACHE_KEY_SELECTED_CHANNEL_IDS", HashMap())` (ref:58,605) | Present as `selectionsCache` (`:23`) — key matches | ok |
| `dispatcher`, `observationDeck`, `storeChannels`, `storeGuildSelected`, `storePermissions` | Present (`:12-16`) | ok |

### MISSING NESTED TYPES — both sealed hierarchies are **ABSENT** from the target tree
(grep for `ResolvedSelectedChannel` / `UserChannelSelection` / `SelectedChannelAnalyticsLocation` /
`ChannelAnalyticsViewType` across the whole target tree: **0 hits**.)

- `ResolvedSelectedChannel` (ref:84-358), sealed with 5 cases:
  `Channel(channel, peekParent: Long?, analyticsLocation: SelectedChannelAnalyticsLocation?)` — plus
  derived `analyticsViewType = if (peekParent != null) PEEK_VIEW else FULL_VIEW` (ref:103-108);
  `ThreadDraft(parentChannel, starterMessageId: Long?, threadStartLocation: String?)` + `hasStarterMessage()`;
  `Unselected` / `Unavailable` / `Uninitialized` objects.
  Members: `getId()` (ref:328 → channel id / -3 / 0 / -1 / -2), `getChannelOrParent()` (ref:318),
  `getMaybeChannel()` (ref:347).
- `UserChannelSelection` (ref:363-589), sealed with 3 cases:
  `SelectedChannel(channelId, peekParent: Long? = null, analyticsLocation: … = null)`,
  `ThreadDraft(parentChannelId, starterMessageId: Long?, locationThreadStart: String?)`,
  `Unselected`. Members: `getId()` (ref:555), `resolveWithChannel(Channel)` (ref:568).
- `SelectedChannelAnalyticsLocation` (`stores/SelectedChannelAnalyticsLocation.java`) and
  `ChannelAnalyticsViewType` (`stores/ChannelAnalyticsViewType.java`) — both **ABSENT**.

**SEVERITY: BLOCKER.** Thread drafts (`openCreateThread` UI flow), channel peeking, and all
channel-open analytics are unrepresentable.

### MISSING METHODS / BEHAVIOR

| # | Reference member | Target | Sev |
|---|---|---|---|
| 2.a | `validateSelectedChannel()` @StoreThread (ref:699-710) — unsubscribes the previous `validateSelectedChannelSubscription`, then **reactively** `ObservationDeck.connectRx([storeGuildSelected, storeChannels, storePermissions])` → `ObservableExtensionsKt.computationLatest` → `distinctUntilChanged` → `appSubscribe(… onNext = onSelectedChannelResolved)` | **ABSENT.** Target replaces it with a synchronous private `resolve(guildId)` (`:83-99`) called inline from each handler. Selection therefore never re-validates when permissions or channels change asynchronously, and the subscription-cancellation contract is gone. | **BLOCKER** |
| 2.b | `onSelectedChannelResolved(ResolvedSelectedChannel)` @StoreThread (ref:645-665) — updates `previouslySelectedChannel` only when the id changed; writes back a `UserChannelSelection.SelectedChannel` keyed by the channel's guild; `FrecencyTracker.track(frecency, channelId)`; `analytics.trackTextInVoiceOpened(...)` when `isVoiceChannel`; `analytics.trackChannelOpened(id, analyticsViewType, analyticsLocation)`; `updateInitializationState()`; `markChanged()` | **ABSENT.** Target `resolve()` does the previous-id bookkeeping inline, and does **no** frecency tracking and **no** analytics. | **BLOCKER** |
| 2.c | `updateInitializationState()` @StoreThread (ref:687-696) — `authState != null && storeChannels.initializedForAuthedUser && (selections.isNotEmpty() ‖ handledReadyPayload ‖ isStoreInitTimedOut) && selectedChannel !is Uninitialized` → `markChanged(InitializedUpdateSource)` | **ABSENT** | **BLOCKER** |
| 2.d | `observeInitializedForAuthedUser()` (ref:830-870) — `storeChannels.observeInitializedForAuthedUser().switchMap { if (it) connectRx(InitializedUpdateSource){initializedForAuthedUser} else just(false) }` | **ABSENT** | **BLOCKER** |
| 2.e | `observeResolvedSelectedChannel()` (ref:876) | **ABSENT** | **BLOCKER** |
| 2.f | `openCreateThread(guildId, parentChannelId, parentMessageId: Long?, threadStartLocation: String?)` @StoreThread (ref:885) | **ABSENT** | MAJOR |
| 2.g | `dismissCreateThread()` @StoreThread (ref:713-724) | **ABSENT** | MAJOR |
| 2.h | `handleGuildAdd(Guild)` @StoreThread (ref:789) — validates if the added guild is the selected one | **ABSENT** | MAJOR |
| 2.i | `handleStoreInitTimeout()` @StoreThread (ref:812-818) — sets `isStoreInitTimedOut`, `updateInitializationState()`, re-validates if still `Uninitialized` | **ABSENT** | MAJOR |
| 2.j | `getFrecency(): ChannelFrecencyTracker` (ref:726) | **ABSENT** | MAJOR |
| 2.k | `trySelectChannel(guildId, channelId, peekParent: Long?, analyticsLocation: SelectedChannelAnalyticsLocation?)` @StoreThread (ref:905) — **no-ops** when the current `SelectedChannel.channelId` already equals `channelId` | Target `:40` is `trySelectChannel(guildId, channelId)` — **2 of 4 params missing**, no idempotence guard, and it wraps in `dispatcher.schedule` whereas the reference is `@StoreThread` (caller-dispatched). | MAJOR |
| 2.l | `isValidResolution(channel, guildId, perms)` (ref:626) = `guildId match && ChannelUtils.isTextOrVoiceChannel(channel) && PermissionUtils.hasAccess(...)` | Target `:86` omits the **`isTextOrVoiceChannel`** predicate — a category/stage/directory channel can be resolved as selected. | MAJOR |
| 2.m | `getFirstAvailableChannel(map, guildId, perms)` (ref:614-624) — filters `guildId match && ChannelUtils.isGuildTextyChannel && hasAccess`, sorts with `ChannelUtils.getSortByNameAndType(Channel.Companion)` | Target `:87-90` filters `type != CATEGORY` (admits voice/stage/directory) and sorts by `position, id`. **Wrong filter and wrong comparator** — a different default channel will be picked. | MAJOR |
| 2.n | `resolveSelectedChannel(...)` (ref:668-684) returns `Uninitialized` when `!storeChannelsInitializedForAuthedUser`, `Unselected` when guildId==0, `Unavailable` when no fallback exists | Target `:83-99` cannot express those three states; falls back to `null` for all. | **BLOCKER** |
| 2.o | `init()` @StoreThread (ref:821) = `loadFromCache(); validateSelectedChannel()` | Target overrides `init(context)` (`:25`) with `restore()` only — **no initial validation**, so nothing is selected until the first gateway event. | MAJOR |
| 2.p | `handleChannelOrThreadCreateOrUpdate(Channel)` (ref:747) — validates iff `channel.id == selectedChannel.id` | Target `:56-58` validates iff `channel.guildId == selectedGuildId` — **different trigger condition** (fires on unrelated channels, misses the selected channel when it moves guilds). | MAJOR |
| 2.q | `handleGuildRemove(Guild)` (ref:797-804) — validates iff the currently-selected channel belonged to the removed guild; does **not** mutate `userChannelSelections` | Target `:65-68` **removes** the guild's selection and force-resolves to `PRIVATE_CHANNELS_ID`. Divergent. | MAJOR |
| 2.r | `handleConnectionOpen(ModelPayload)` (ref:763-786) — also sets `handledReadyPayload = true` and ends with `validateSelectedChannel()` | Target `:48-53` never sets `handledReadyPayload` (field absent) and calls the synchronous `resolve(...)`. | MAJOR |
| 2.s | `snapshotData()` @StoreThread (ref:892-902) — persists **`frecencyCache`** (`Persister.set(frecency, false)`) **and** `selectedChannelIdsCache.set(map, true)` | Target `:79-81` persists only the selections map. Frecency is never written. | MAJOR |
| 2.t | `loadFromCache()` @StoreThread (ref:631-641) — maps cached `Long` → `UserChannelSelection.SelectedChannel(id)` | Target `restore()` (`:27`) putAll's raw Longs; also calls `markChanged()` which the reference does not. | MINOR |
| 2.u | `observeId()` / `observePreviousId()` (ref:826, 872) — **no** `distinctUntilChanged` | Target `:34-36` adds `.distinctUntilChanged()`. | MINOR |
| 2.v | reference has **no** `handlePermissionsChanged(guildId)` and **no** `reset()` | Target adds both (`:59-61`, `:70-77`). `handlePermissionsChanged` is the target's substitute for the reference's reactive `storePermissions` update source. **EXTRA/DIVERGENT.** | MINOR (extra) |
| 2.w | `@StoreThread` on 16 members | Target has **1** (`:42`). | MAJOR |

### SEVERITY — PAIR 2: **BLOCKER**
BLOCKER 9 · MAJOR 20 · MINOR 4

---
### Addendum to Pairs 1-2: unwired / dead target API

Grep of the whole target tree shows these `StoreChannels` members have **no caller at all**, so even
where the method body exists the behavior is unreachable:
`handleSearchFinish`, `handleGroupDMRecipient`, `preloadThread`, `onGroupCreated`,
`observeDefaultChannel`, `observeThreadsFromMessages`, `observeIds`.
`StoreStream.kt:1372` calls `channels.handleStoreInitTimeout()` but there is no
`channelsSelected.handleStoreInitTimeout()` counterpart (method absent — gap 2.i).
`StoreStream.kt:1541` routes `guild.unavailable` to `channelsSelected.handleGuildSelected()`,
an invention with no reference analogue.

---
## PAIR 3 — `stores/StoreChannelMembers.kt` vs `stores/StoreChannelMembers.java`

JADX damage (explicit): `StoreChannelMembers$MemberListIdCalculator.computeIdFromOverwrites`
(ref:140-209) is emitted as *"Code decompiled incorrectly"* + `UnsupportedOperationException`;
the smali dump is readable and was used as behavior evidence only. `makeGroup` (ref:474-490) is
partially mangled (`(r4 = guildRole.g()) == null) ? "" : ""` — the role-name string is lost), so the
role-name path is inferred, not literal.

The target is not a port; it is a rewrite against a different row model (raw `api.GuildMember`)
instead of the reference's fully-resolved presentation rows.

### MISSING FIELDS / DEPENDENCIES  (reference fields ref:54-64)
| Reference | Target |
|---|---|
| `dispatcher: Dispatcher` | **ABSENT** — target ctor is `(observationDeck, getRole)` (`StoreChannelMembers.kt:16-19`) |
| `storeGuilds: StoreGuilds` | **ABSENT** — replaced by `getRole: (Long, Long) -> GuildRole?` with default `{ _,_ -> null }` (`:18`), which silently disables role names |
| `storeUser: StoreUser` | **ABSENT** — no user resolution at all |
| `getChannel: (Long) -> Channel` | **ABSENT** |
| `getGuildMemberCount: (Long) -> Int` | **ABSENT** (used for the empty-list fallback) |
| `getPresence: (Long) -> Presence` | **ABSENT** |
| `isUserStreaming: (Long) -> Boolean` | **ABSENT** |
| `markChangedTrigger: PublishSubject<Unit>` + ctor `leadingEdgeThrottle(1s).appSubscribe { dispatcher.schedule { markChanged() } }` (ref:363-366) | **ABSENT** — target calls `markChanged()` directly on every op (`:48,54,69,84,102,109`); no 1 s coalescing, so a re-render per member-list frame |
| `memberLists: Map<Long, Map<String, ChannelMemberList>>` | **wrong type**: `LinkedHashMap<Long, MutableMap<String, MutableState>>` with a private `MutableState` data class (`:20-27`) |
| `memberListsSnapshot` | present (`:28`) but the element type is a different `ChannelMemberList` |
| `observationDeck` | present |

Supporting-type divergence (`utilities/lazy/memberlist/ChannelMemberList.kt`): target is a plain
`data class` (listId, groups, groupIndices, onlineCount, memberCount, rows). Reference
`ChannelMemberList implements MemberList` with `SparseMutableList<MemberListRow> rows` (chunked,
size 100), `SortedMap<Integer,String> groupIndices`, `Map<String,MemberListRow> groups`,
`initialSize`, `logger`, a copy-constructor, and mutating API
`add/insert/update/delete/invalidate/sync/setGroups/rebuildMembers/getSize` — **none** present.
`onlineCount`/`memberCount` are target inventions.

`MemberListRow`: reference has `Member(userId, name, isBot, tagText, tagVerified, presence, color,
avatarUrl, showOwnerIndicator, premiumSince, isApplicationStreaming, userFlags)`,
`RoleHeader(roleId, roleName, memberCount)`, `StatusHeader(rowId, Type{ONLINE,OFFLINE}, memberCount)`.
Target has only `Member(member: GuildMember)` and `GroupHeader(group, name?)` — **all 12 presentation
fields and both header types are gone.**

### MISSING METHODS / BEHAVIOR
- `Companion.makeRowMember(userId, guildMembers, users, presence, isUserStreaming, showOwnerIndicator)`
  (ref:116-124) — nick-or-username, bot/system tag string-res, verified-bot, role color
  (`!= -16777216` -> null), `IconUtils.getForGuildMemberOrUser`, premiumSince, `flags or publicFlags`.
  **ABSENT — BLOCKER.**
- `makeRowMember(guildId, userId, allowOwnerIndicator)` (ref:505-508) incl. owner check
  `guild.ownerId == userId`. **ABSENT.**
- `makeGroup(guildId, group)` (ref:474-490) -> `RoleHeader` / `StatusHeader(ONLINE|OFFLINE)`.
  Target `Item.row()` (`:148-154`) always builds `GroupHeader`. **ABSENT/DIVERGENT.**
- `makeRow(guildId, item)` (ref:493-501) — folded into `Item.row` with different output. DIVERGENT.
- `@StoreThread allowOwnerIndicator(guildId)` (ref:370-396) — suppress the owner crown when a hoisted
  role already grants ADMINISTRATOR. **ABSENT entirely.**
- `throttleMarkChanged()` (ref:510-513). **ABSENT.**
- `getChannelMemberList(guildId, channelId): ChannelMemberList` (ref:515-523) — **non-null**, computes
  the list id itself, falls back to `ChannelMemberList(listId, getGuildMemberCount(guildId), AppLog.g)`.
  Target `get(guildId, memberListId): ChannelMemberList?` (`:112`) takes a **pre-computed id from the
  caller** and returns null. **BLOCKER** (signature + contract change).
- `observeChannelMemberList(guildId, channelId): Observable<ChannelMemberList>` (ref:574-578).
  Target `observe(guildId, memberListId): Observable<ChannelMemberList?>` (`:115`) — nullable,
  different key. **BLOCKER.**
- `MemberListIdCalculator` object (ref:134-234): `computeMemberListId(channelsProvider, channelId,
  storeGuilds)`, `computeIdFromOverwrites(List<PermissionOverwrite>)`, `toUnsignedLong(Int)`.
  Target moved this to `companion.computeMemberListId(channel, roles)` (`:178`), and its only caller
  is UI (`widgets/channels/memberlist/WidgetChannelMembersList.kt:137`). Behavioral deltas:
  - Reference gates on `PermissionUtils.canEveryone(VIEW_CHANNEL, channel, null, roles)`, which
    returns **true when the @everyone role is missing** (`PermissionUtils.java:158-159`). Target
    treats a missing role as perms `0L` -> **false** (`:180`). **Inverted** for guilds whose
    @everyone role is not yet loaded.
  - `canEveryone` recurses into the parent for threads and returns **false when parentChannel == null**.
    Target has **no thread branch** -> a thread yields `EVERYONE_ID` instead of the overwrite hash.
  - Murmur3: reference `j0.l.e.m.a(str,0,len,0)` is murmur3_32 over UTF-8, seed 0; target's
    hand-rolled `murmur3` (`:193-215`) matches, little-endian tail included. **No gap.**
  - Empty overwrite key -> `""` in both. **No gap.**
- `MemberListUpdateLogger` object (ref:250-326): `opLogs`, `MAX_UPDATE_COUNT = 20`,
  `ERROR_TAG = "MemberListUpdateError"`, `logUpdate(update)`, `dumpLogs(guildId, listId, e)` ->
  FirebaseCrashlytics + `Logger.e(AppLog.g, ...)`. **ABSENT.**
- `MemberListUpdateException(Exception)` (ref:239-245). **ABSENT.**
- `handleGuildMemberListUpdate` (ref:526-553): calls `logUpdate`, wraps all ops in
  `try/catch -> dumpLogs`, ends with `memberList.setGroups(groups, ::makeGroup)` then
  `throttleMarkChanged()`. Target (`:31-50`) has no logging, **no try/catch** (an out-of-range op
  propagates), computes `onlineCount`/`memberCount`/row sizing inline, and only creates a list on
  `Operation.Sync`.
- Per-op handlers `handleSync/handleUpdate/handleInsert/handleDelete/handleInvalidate` (ref:408-470)
  are folded into `MutableState.apply` (`:124-138`). Reference delegates blindly to
  `ChannelMemberList`; target adds **invented** bounds checks that `invalidate()` (null the whole
  list) on out-of-range Update/Insert/Delete (`:130-132`). Reference `handleSync` also logs a
  `"... INSTANTIATE"` breadcrumb on list creation (ref:446) — absent.
- `handleGuildRemove(guildId)` present (`:53`) but uses `markChanged()`, not `throttleMarkChanged()`.
- `handleGuildRoleCreateOrUpdate(guildId)` (ref:563-572) — recomputes `allowOwnerIndicator` and calls
  `rebuildMembers` on **every** list, rebuilding all member rows (role color / crown). Target
  `handleGuildRoleCreateOrUpdate(guildId, role: GuildRole)` (`:88`) only patches `GroupHeader.name`.
  **MAJOR:** different signature + member rows never rebuilt. (Target caller `StoreStream.kt:609,616`
  passes the role; reference `StoreStream.java:1936` passes only guildId.)
- `snapshotData()` present (`:118`) but **does not call `super.snapshotData()`** (ref:582).
- `@StoreThread` present on the target's public handlers; **absent** on the private helpers
  `apply/ensureSize/invalidate/row/snapshot` (`:124-173`), all store-thread-confined in the reference.

### EXTRA / DIVERGENT
- `reset()` (`:106`, wired at `StoreStream.kt:1428,1463`) — **reference has no `reset()`**; member
  lists are dropped per-guild only.
- `handleGuildMemberUpdate(guildId, member)` (`:58`) and `handleGuildMemberRemove(guildId, userId)`
  (`:73`) — **not in reference**; they exist only because target rows embed a raw `GuildMember`.
  `handleGuildMemberRemove` sets the row to `null` without shrinking the list, desyncing `rows.size`
  from the group counts.
- `MutableState` (`:20`), `onlineCount`/`memberCount`/`groupIndices` computed in `snapshot()` (`:156`)
  — reference keeps `groupIndices` live via `setGroups` and has no online/member counters.
- `GuildMember.detached()` defensive copy (`:175`) — reference deep-copies via the
  `ChannelMemberList(ChannelMemberList)` copy ctor instead.

### SEVERITY — PAIR 3: **BLOCKER**
BLOCKER 6 · MAJOR 8 · MINOR 4

---

## PAIR 4 — `stores/StoreCollapsedChannelCategories.kt` vs `stores/StoreCollapsedChannelCategories.java`

Reference decompiles cleanly (152 lines, 0 broken regions). Structurally faithful; the gaps are
persistence format and lifecycle.

### FIELDS
| Reference (ref:27-30) | Target |
|---|---|
| `collapsedCategories: Map<Long, Set<Long>>` = `HashMap()` | `collapsed: HashMap<Long, MutableSet<Long>>` (`:14`) — renamed, ok |
| `collapsedCategoriesSnapshot` = `HashMap()` | `snapshot: Map<Long, Set<Long>>` = `emptyMap()` (`:15`) — renamed; default equivalent |
| `dispatcher`, `observationDeck` | present |
| `private const CACHE_KEY_COLLAPSED_CATEGORIES = "STORE_COLLAPSED_CATEGORIES_V2"` (ref:25) | `const val CACHE_KEY` (`:52`) — same value, but **public** vs private |
| *(no Persister — uses `Store.getPrefs()`)* | **EXTRA** `cache: Persister<Map<Long, Set<Long>>>` ctor param (`:12`) |

**MAJOR #1 — persistence handle mismatch.** Reference persists through `SharedPreferences` via
`Companion.fromCache(prefs)` / `Companion.toCache(prefs, map)` using
`SharedPreferenceExtensionsKt.getStringEntrySetAsMap` / `putStringEntrySetAsMap` (ref:40-50).
Target uses a Kryo-file `Persister`. Same key string, **incompatible on-disk format**;
`Companion.fromCache` / `toCache` have no target equivalent.

### METHODS
- `getCollapsedCategories()` present (`:23`).
- `observeCollapsedCategories(guildId)` present (`:24`) — matches
  `...$observeCollapsedCategories$1` (`snapshot[guildId] ?: emptySet()`, `.distinctUntilChanged()`).
- `setCollapsedCategory(guildId, categoryId, collapsed)` present (`:26`). Reference schedules
  `setCollapsedState`, a separate private helper (ref:67-85); target inlines it. Semantics equal.
- `@StoreThread handleConnectionOpen(payload)` (ref:92-119) present (`:33`) and semantically
  equivalent (prune guilds absent from READY, prune category ids absent from each guild's channels,
  drop emptied guilds). **MINOR:** target lacks the `@StoreThread` annotation and calls
  `markChanged()` unconditionally where the reference marks only on actual mutation.
- **MAJOR #2 — `init(Context)`** (ref:123-128): `@StoreThread`, `super.init(context)`,
  `collapsedCategories = Companion.fromCache(getPrefs())`, `markChanged()`. Target
  `override fun init(context) = restore()` (`:17`) — **no `@StoreThread`, no `super.init(context)`**,
  and hydration is exposed as `internal fun restore()` invoked out-of-band from
  `StoreStream.kt:480,508`. `restore()` also **merges** into the existing map (`:19`) instead of
  replacing it, so stale guild entries survive a re-hydrate.
- `snapshotData()` (ref:141-151): `super.snapshotData()` -> `toCache(prefs, ...)` -> deep copy.
  Target (`:47-50`) does the copy and `cache.set(...)` but **omits `super.snapshotData()`**.

### EXTRA / DIVERGENT
- `internal fun reset()` (`:41`) clearing the map **and `cache.clear()`**, wired to logout at
  `StoreStream.kt:1430,1466`. The reference class has **no `reset()`** and reference
  `StoreStream.java` never resets it (only refs: field 158, ctor 974, `handleConnectionOpen` 1672,
  getter 2607) — collapsed-category state is intentionally durable across logout.
  **Behavioral regression.**
- Ctor arity differs: target `(dispatcher, observationDeck, cache = Persister(...))` vs reference
  `(dispatcher, observationDeck)`.

### SEVERITY — PAIR 4: **MAJOR**
BLOCKER 0 · MAJOR 2 · MINOR 6

---

## PAIR 5 — `stores/StoreUserGuildSettings.kt` vs `stores/StoreUserGuildSettings.java`

Reference: 414 lines, read fully; 8 `$`-lambda files read as behavior evidence.
**JADX quality: clean** — no `Method not decompiled` / `Code decompiled incorrectly` anywhere in the
class or its lambdas. `StoreUserGuildSettingsKt.java` **does not exist**. `@Metadata d2` enumerates
15 fields + 24 methods; all are accounted for below. Target is 281 lines.

### MISSING FIELDS / DEPENDENCIES
| # | Reference (field, `.java:line`) | Target | Sev |
|---|---|---|---|
| F1 | `RestAPI restApi` (:61, default `RestAPI.Companion.getApi()` :145) | replaced by ctor lambdas `updateGuildSettings` / `updateThreadSettings` (`.kt:23-26`), **defaulted to `Observable.empty()` and never wired** (`StoreStream.kt:175-180`) | **BLOCKER** |
| F2 | `StoreAnalytics analytics` (:49) | replaced by `trackSettingsUpdated: (SettingsUpdateType, Long?) -> Unit` (`.kt:27`), defaulted to no-op, never wired | MAJOR |
| F3 | `StoreThreadsJoined storeThreadsJoined` (:63) | replaced by `getJoinedThreadFlags: (Long) -> Int = { 0 }` (`.kt:22`), never wired -> `oldFlags` always 0 | MAJOR |
| F4 | `StoreChannels storeChannels` (:62) | `getChannel: (Long) -> Channel?` (`.kt:21`), wired to `channels::findChannelById`; reference uses `findChannelByIdInternal$app` (store-thread map, not snapshot) | MINOR |
| F5 | `ObservationDeck` default `ObservationDeckProvider.get()` (:145) | no default (`.kt:20`) | MINOR |
| F6 | `Persister<Set<Long>> guildsToHideMutedChannelsInCache` (:57) | renamed `hiddenMutedChannelsCache` (`.kt:30`); key strings match (`STORE_SETTINGS_USER_GUILD_V6`, `STORE_SHOW_HIDE_MUTED_CHANNELS_V2`) | MINOR |

Present and correct: `guildSettings` (`.kt:63`), `guildsToHideMutedChannelsIn` (`.kt:64`),
`guildSettingsSnapshot` (`.kt:65`, `emptyMap()`), `guildsToHideMutedChannelsInSnapshot` (`.kt:66`,
`emptySet()`), `eventSubject` PublishSubject (`.kt:67`), `recomputeSettingsSubscription` (`.kt:68`),
`guildSettingsCache` (`.kt:28`). No ObservationDeck sub-sources exist in the reference — parity OK.
Payload DTOs `UserGuildSettingsUpdate` / `ChannelOverrideUpdate` / `ThreadMemberSettingsUpdate`
(`.kt:39-61`) are field-exact vs `RestAPIParams.UserGuildSettings` (8), `.ChannelOverride` (4),
`.ThreadMemberSettings` (3) — **no gap**.

### MISSING METHODS / BEHAVIOR
**BLOCKER**
1. **All 8 setters are dead no-ops in production.** `submitGuildUpdate` (`.kt:241`) and
   `submitThreadUpdate` (`.kt:251`) call injected lambdas that default to `Observable.empty()` and
   are never supplied at `StoreStream.kt:175-180`. Reference `updateUserGuildSettings` (:205) calls
   `restApi.updateUserGuildSettings(guildId, params)`; `updateThreadMemberSettings` (:200) calls
   `restApi.updateThreadMemberSettings(channelId, params)`. Consequence: `setGuildMuted`,
   `setChannelMuted`, `setGuildFrequency`, `setChannelFrequency`, `setGuildToggles`
   (suppress @everyone/@roles, mobile push), `setNotifyHighlights`, `setChannelNotificationFlags`,
   `setChannelNotificationsDefault` all silently do nothing; `eventSubject` **never emits**;
   `observeEvents()` is permanently dead.
2. Same root, second leg: the thread mute/frequency path never reaches
   `PATCH /channels/{id}/thread-members/@me`.

**MAJOR**
3. `observeGuildSettings(guildId)` (`.kt:86`) returns `Observable<ModelNotificationSettings?>`;
   reference (:379-389) returns **non-null**, substituting `new ModelNotificationSettings()` when
   absent (defaults `isMuted=false`, `isMobilePush=true`, `messageNotifications=FREQUENCY_UNSET`).
   Downstream null-handling contract is inverted.
4. **No error surfacing.** Every setter accepts `context: Context`
   (`.kt:126,145,160,170,187,194,197,210`) and **never uses it**. Reference wraps every REST call in
   `ObservableExtensionsKt.ui(restSubscribeOn(...))` + `appSubscribe(..., context, ...)`.
5. **No `restSubscribeOn` / `.ui()`.** Target subscribes on the caller thread (`.kt:241,251`) and
   emits `Event.SettingsUpdated` inside `dispatcher.schedule` (store thread); reference emits on the
   main thread from the `appSubscribe` success callback.
6. **Analytics lost.** Reference `$updateUserGuildSettings$1` ->
   `analytics.onNotificationSettingsUpdated(notifSettings, channelId)` (`StoreAnalytics.java:650`);
   `$updateThreadMemberSettings$1` -> `analytics.onThreadNotificationSettingsUpdated(channelId,
   parentChannelId, threadMember.a(), oldFlags)` (`StoreAnalytics.java:659`). Target's
   `trackSettingsUpdated(type, channelId)` (`.kt:244,253`) drops the settings object, the parent
   channel, and both flag values.
7. **`parentChannelId` dropped.** Reference threads `channel.u()` (parentId) through
   `updateThreadMemberSettings(context, channelId, parentChannelId, ...)` (:200, :315). Target's
   `submitThreadUpdate(channel, update, oldFlags)` (`.kt:250`) never reads it — and `oldFlags` and
   `update` are **unused parameters** (dead code).
8. **Extra optimistic local write:** `.kt:243` feeds the REST response back via
   `handleGuildSettingUpdated(listOf(settings))`. The reference does **not** — it relies solely on
   the gateway `GUILD_SETTINGS_UPDATE` dispatch. Changes ordering and can resurrect stale state.

**MINOR**
9. `settingsUpdateType` (`.kt:269`) != reference `getSettingsUpdateType` (:149). Reference:
   thread->`THREAD`, else `ChannelUtils.F` (isTextChannel = private{1,3} u guildTexty{0,5,10,11,12,14,15})
   ->`CHANNEL`, **else `CATEGORY`**. Target maps only `type==4`->`CATEGORY`, so voice(2)/stage(13)/
   store(6) are mislabelled. Analytics-label impact only.
10. `setChannelNotificationFlags` (`.kt:160-161`) checks only `type == GUILD_FORUM`; reference (:329)
    additionally gates on `ForumUtils.canAccessRedesignedForumChannels(guildId)`.
11. `getGuildSettingsInternal()` (`.kt:80`) lacks `@StoreThread`; reference has it (:215). All other
    confinement is correct (`handleConnectionOpen` `.kt:95`, `handleGuildSettingUpdated` `.kt:103`,
    `handleGuildSettings` `.kt:106` vs ref :224, :240, :153); target additionally asserts at runtime
    (`.kt:108,218`) — improvement.
12. `observeGuildSettings()` (`.kt:85`) appends `.distinctUntilChanged()`; reference (:261) has none.
    `observeHideMutedChannels` (`.kt:88`), `observeMutedGuildIds` (`.kt:91`), `observeEvents`
    (`.kt:83`) match reference semantics exactly.
13. `snapshotData()` (`.kt:228`) omits `super.snapshotData()` (ref :372). Harmless in the target
    (`StoreV2.snapshotData` is `Unit`) but structurally divergent. Persist flags match reference.
14. `init(context)` (`.kt:70`) delegates to `restore()`, which additionally calls
    `scheduleMuteRecompute()` (`.kt:76`) — reference `init` (:247) only restores caches +
    `markChanged()`, no timer. `init` is also **dead**: `StoreStream` calls `guildSettings.restore()`
    directly (`StoreStream.kt:481,509`) and never `init(context)`.
15. `handleConnectionOpen` (`.kt:97`) early-returns on a null `userGuildSettings`; reference (:227)
    asserts non-null.

**EXTRA**
16. `reset()` (`.kt:216-226`) — the reference class has **no** `reset`/logout hook; it relies on the
    global `Persister` reset. Target additionally `clear(persist=true)`s both persisters.
    Called from `StoreStream.kt:1431,1467`.
17. `restore()` as a public-internal entry point (`.kt:72`), companion cache-key constants
    (`.kt:278-279`), `Channel.isThread()` extension (`.kt:275`, equivalent to `ChannelUtils.H`).

**CONFIRMED NON-GAPS:** the reference has **no** `handleGuildRemove`,
`handleChannelCreate/Update/Delete`, `handleGuildAdd`, `isGuildMuted`, `isChannelMuted`,
`getNotificationLevel`, nor a `snapshotData` sub-source. The gateway entry point is
`handleGuildSettingUpdated` (present `.kt:104`, wired `StoreStream.kt:564`). Mute-expiry recompute is
present in both (ref :168-176 + `recomputeMuteConfigs` :181 vs target `scheduleMuteRecompute`
`.kt:259`), including the `Long.MAX_VALUE` sentinel and the `dispatcher.scheduler` timer. Neither
prunes settings for removed guilds. `NotificationPolicy.kt` has no counterpart in this reference
class (its logic lives in `NotificationClient` / `ModelNotificationSettings`) — out of scope.

### SEVERITY — PAIR 5: **BLOCKER**
BLOCKER 2 · MAJOR 6 (+F1-F3) · MINOR 9 (+F4-F6)

**Top fix order:** wire `updateGuildSettings`/`updateThreadSettings` to `RestAPI` with
`restSubscribeOn().ui()` and context-bound error handling at `StoreStream.kt:175`; wire
`getJoinedThreadFlags` to `StoreThreadsJoined` and pass `channel.parentId` through
`submitThreadUpdate`; restore the non-null default in `observeGuildSettings(guildId)`.

---

## PAIR 6 — `stores/StoreReadStates.kt` vs `stores/StoreReadStates.java`

### Architecture note (read first)
The reference splits this domain across **three** stores: `StoreReadStates` (unread channel/guild id
sets + unread marker, extends plain `Store`), `StoreMessageAck` (ack map, persister, thread acks,
auto-ack pipeline, `StoreV2`), and `StoreMentions` (mention counts, `StoreV2`). The target **merges
all three** into `StoreReadStates.kt`. Gaps below are judged against that merged surface; the last
section isolates what reference `StoreReadStates` itself delegates.

### JADX decompile quality (explicit)
- `StoreReadStates.java:125` — **decompiled incorrectly**:
  `z2 = (channel2 == null || !ChannelUtils.r(...)) ? false : false;` collapses to constant `false`.
  `@Metadata d2` + `ChannelUtils.r == isGuildForumPost(channel, parent)` show the real value is the
  forum-post flag. Target's `forumPost` (`.kt:234`) is the correct reading.
- `StoreMessageAck.java:616-629` — `handleConnectionOpen(ModelPayload)` is **"Method not decompiled"**
  (413 instructions skipped). Its READY ack-seeding behavior **could not be verified**; conclusions
  about it are inferred from `Ack(ModelReadState,...)` + `updateAcks` only.
- `StoreMessageAck$getPendingAck$2.AnonymousClass1.call` (:380-434) — **"Code decompiled incorrectly"**,
  but the smali dump is complete and readable; used as evidence for the `isLockedAck` path.
- `StoreMessageAck.java:710` and `StoreMentions.java:132-146, 288-305` — same JADX ternary/`continue`
  bug (`? false : false`, unreachable `if`). Intent recovered from context, not literal source.

### MISSING FIELDS / STATE
| # | Reference | Target | Sev |
|---|---|---|---|
| F1 | `StoreMentions.mentionedMessages: HashMap<MessageId, Message>` (:50) — live mention index | ABSENT; mentions derived only from `readStates.values.mentionCount` (`.kt:223`) | **BLOCKER** |
| F2 | `StoreMentions.privateChannels: HashSet<Long>` (:54) — every DM message counts as a mention (`hasMention` :127) | ABSENT; DMs contribute 0 mentions | **BLOCKER** |
| F3 | `StoreMentions.myId`, `myRoleIds`, `selectedChannelId`, `serverInitReadStates` (:51-56) | ABSENT | **BLOCKER** |
| F4 | `StoreMentions.countsCache = Persister("CHANNEL_MENTION_COUNTS_V6")` (:447) + `countsSnapshot` / `countsEmpty` | no mention persister; counts lost across restart | MAJOR |
| F5 | `StoreMessageAck.threadSyncSubscription: Subscription` (:58) | ABSENT | MAJOR |
| F6 | `Inputs.activeJoinedThreads` / `activeForumThreads` / `selectedVoiceChannelId` / `selectedVoiceChannelAt` (`.kt:42-45`) exist but are **never populated** — `StoreStream.kt:1584-1593` omits all four | wrong default (always empty/0) | **BLOCKER** |
| F7 | `Unread` / `Unread.Marker` = `com.discord.models.application.Unread` with `Unread(Marker, List<Message>)` + `Companion.getTotalMessages` (Unread.java:200,28) and `Marker(channelId, messageId, mostRecentMessageId)` (Unread.java:118) | re-declared as nested types (`.kt:29-32`); **both secondary constructors missing** — count can never be computed | MAJOR |
| F8 | `Ack.viewed` set `true` by `getPendingAck` (smali :425) | field exists (`.kt:24`) but **never written non-default** | MAJOR |
| F9 | `Ack.isLockedAck` set `true` by `markUnread` -> `getPendingAck(..., isLockedAck=true)` | field exists but **no producer anywhere in target** | **BLOCKER** |
| F10 | `StoreMessageAck.Companion.ThreadAckState` (`Thread(canAckThread)` / `NotThread`) + `isThreadAndUnableToAck` (:161-245) | ABSENT | MAJOR |
| F11 | `StoreMessageAck.PendingAck` + `PendingAck.EMPTY` (:255) | ABSENT | MINOR |
| — | reference `unreadChannelIds`/`unreadGuildIds` are `SerializedSubject<Set<Long>>` (:53-54) | replaced by ObservationDeck snapshots — **equivalent, not a gap** | — |

### MISSING METHODS / BEHAVIOR
| # | Reference symbol | Target state | Sev |
|---|---|---|---|
| M1 | `StoreMessageAck.markUnread(channelId, messageId)` (:730) — posts a *locked* ack at the older message, never acks the newest id | `.kt:162-168` calls `ackRequest(channelId,false,false)`, and `StoreStream.kt:236-245` ignores the passed messageId and acks `mostRecentId` -> **mark-unread tells the server the channel is fully read**, then stores a non-locked ack that auto-ack immediately overwrites | **BLOCKER** |
| M2 | `StoreMessageAck.handleChannelSelected()` (:609) clears `isLockedAck` on all acks | ABSENT | **BLOCKER** |
| M3 | `StoreMentions.computeMentionCounts()` (:69) + `hasMention()` (:110) + `isMentionableChannel()` (:171) + `processMarkUnread` (:407) + `handleMessageCreateOrUpdate` / `handleMessageDeleted` / `handleThreadMembersUpdate` / `handleGuildAdd` / `handleGuildMemberAdd` / `handleChannelSelected` / `removeAllMessagesForChannel` | none present. No self-author skip, no edited-timestamp skip, no blocked-user skip, no unjoined-thread skip, no `@me`/`@role`+`suppress_roles`/`@everyone`+`suppress_everyone`, no selected-channel suppression | **BLOCKER** |
| M4 | `StoreMessageAck.updateThreadAcks(old,new)` (:559) + `observeActiveThreadIdsWithPrevious()` (:471) — seeds thread acks from `ThreadUtils.getThreadAckMessageTimestamp`, drops acks for left threads | ABSENT; combined with F6, **no thread or forum post can ever be unread** (`.kt:236-237` early-returns) | **BLOCKER** |
| M5 | `StoreMessageAck.init` auto-ack pipeline (:672-728): at-bottom + loading-settled + not-detached + not-directory + `!ack.isLockedAck`, 500 ms debounce, store-thread confined | reimplemented in the **view layer** at `WidgetChatList.kt:82-88` with no `isAtBottomIgnoringTouch`, no locked-ack guard, no directory guard, no debounce, not `@StoreThread` | **BLOCKER** |
| M6 | `StoreReadStates.computeUnreadMarker()` (:156-205): marker from live-vs-`take(1)` ack pair honoring `isLockedAck`, count recomputed continuously from `observeMessagesForChannel`, `markAsRead` skip, `doOnUnsubscribe { clearMarker() }` | `.kt:143-147 updateUnreadMarker` is push-only from `StoreStream.kt:1306-1309` with count hardcoded `0`/`1`; no live count, no locked-ack branch, **no clearMarker on channel switch** (`clearMarker` only fires inside `markAsRead`, `.kt:157`) | MAJOR |
| M7 | `observeUnreadCountForChannel(channelId, since)` (:293-333) counts loaded messages newer than the ack, filtered by `since` | `.kt:104-109` returns **0 or 1** from `recentMessageIds` — never a real count | MAJOR |
| M8 | `StoreMessageAck.handleMessageCreate(message)` (:643) — acks the channel when *I* send a message | ABSENT; own messages leave the channel unread | MAJOR |
| M9 | `StoreMessageAck.pruneAcks()` (:527) via `handleGuildCreate()` (:632) | ABSENT; acks map grows unbounded / stale | MAJOR |
| M10 | `StoreMessageAck.handleThreadCreateOrUpdate(channel)` (:662) — acks the forum parent when I create a thread | ABSENT | MAJOR |
| M11 | `StoreMentions.observeTotalMentions()` (:390) — total badge count | ABSENT (target has `getMentionCountsByChannel/ByGuild` only) | MAJOR |
| M12 | `getUnreadMarker(channelId)` (:256) uses `.filter { marker.channelId == j }` | `.kt:101-102` uses `.map { ... else Unread() }` -> emits a spurious empty `Unread` for other channels | MINOR |
| M13 | `getThreadAckStateInternal` guard inside `updateAcks` (:547) — refuses acks for un-ackable threads | `.kt:211-216 updateAck` has no such guard | MAJOR |
| M14 | `StoreMessageAck.handlePreLogout()` (:653) unsubscribes thread sync | `.kt:171 handlePreLogout() = Unit` | MINOR |
| M15 | `PermissionUtils.hasAccess` (:307) uses `VIEW_CHANNEL\|CONNECT` (1049600) for voice/stage and short-circuits `true` for private channels | `.kt:238` uses bare `VIEW_CHANNEL` (1024) for all types | MINOR |
| M16 | reference `computeUnreadIds` **excludes private channels entirely** (`!ChannelUtils.B(channel2)`, :126) — DM unread surfaces via mention counts | `.kt:235,250` includes DMs in `unreadChannelIds` | DIVERGENT |
| M17 | reference filters unread with plain `isChannelMuted` only (:127) — no guild-level mute, no category chain | `.kt:239 isPlainUnreadMuted` adds guild `isMuted` + a 2-level parent-override chain | DIVERGENT |
| M18 | reference thread ack fallback: `if (ack != null \|\| !isThread)` (:129) — threads with no ack are skipped | `.kt:244-247` falls back to parent ack, then guildJoinedAt, then `clock.currentTimeMillis()` | DIVERGENT |

**@StoreThread confinement:** broadly correct (`handle*`, `recomputeUnreadIds`, `putReadState`,
`updateAck` annotated; `markAsRead`/`markUnread` route via `dispatcher.schedule`). Gaps: `restore()`
(`.kt:68`) and `clearMarker()` (`.kt:297`) mutate store state and call `markChanged()` without
`@StoreThread`; `override fun init(context) = restore()` (`.kt:66`) is **dead code** — `StoreStream`
never calls `readStates.init(...)` (line 305 only registers it in the `storesV2` dispatch list) and
calling it off the store thread would throw in `StoreThreadScheduler.assertCurrentThread()`
(`.kt:220`).

**snapshotData() / reset():** `.kt:189-199` and `.kt:174-187` are present and correct in shape
(`super.snapshotData()` is a no-op in target `StoreV2.kt:26`, so omitting it is fine). `.kt:197`
persists the marker with `persist = true` on **every** dispatch snapshot; reference persists via
`Persister.set$default(..., false, ...)` (batched) — MINOR I/O regression.

### EXTRA / DIVERGENT
- `mentionCountsByGuild` + `getMentionCountsByGuild()` (`.kt:55,90`) — reference aggregates per-guild
  in the widget layer, not the store. Harmless extra.
- `ObservationDeck`/`StoreV2` migration of `StoreReadStates` (reference extends plain `Store` with
  `SerializedSubject`s) — deliberate, acceptable.
- `reset()` / `handlePreLogout()` on `StoreReadStates` — reference `StoreReadStates` has neither.
  Extra, good.
- `ackRequest: (Long, Boolean, Boolean) -> Unit` injection replaces `RestAPI` + `StoreStream`
  coupling — acceptable, but see M1: the third param (`clearLock`) and the message id are both
  discarded by the `StoreStream.kt:236` implementation.
- `Inputs` / `updateUnreadInputs` push model replaces the reference's 9-way
  `ObservableWithLeadingEdgeThrottle.combineLatest` (:92-97) with its 3 s / 1 s throttles.
  Acceptable, but the four unpopulated fields (F6) make it functionally lossy today.
- M16 + F2 together are a **semantic inversion**: reference gives DMs a numeric mention badge and no
  unread dot; target gives DMs an unread dot and (only) the server-supplied `mention_count`, never
  incremented by live `MESSAGE_CREATE`.

### What reference `StoreReadStates` relies on from `StoreMentions` / `StoreMessageAck`
Reference `StoreReadStates` owns **only** the unread channel/guild id sets and the unread marker.
From `StoreMessageAck` (read-only consumption):
1. `observeAll(): Observable<Map<ChannelId, Ack>>` — the sole ack source for `computeUnreadIds`
   (`StoreReadStates.java:84`) and `observeUnreadCountForChannel` (:295).
2. `Ack.getMessageId()` — the read cursor compared against `messagesMostRecent` (:131, :314).
3. `Ack.isLockedAck()` — decides whether the marker anchors on the live ack or the `take(1)` snapshot.
4. `ack(channelId, false, false)` — `markAsRead` delegates the whole ack round-trip, including
   thread-ack legality and most-recent-id resolution (:290).
5. Implicitly `updateThreadAcks` / `pruneAcks` / `ThreadAckState` keep the ack map correct for
   threads; `StoreReadStates` assumes "ack absent => thread not ackable" at :129.
6. `StoreMessageAck.init`'s auto-ack pipeline is what actually clears unread state as the user reads.

From `StoreMentions`: reference `StoreReadStates` has **no compile-time dependency** — no import, no
call. The relationship is complementary at the UI layer: `StoreReadStates` supplies the unread *dot*
(guild channels only, DMs excluded at :126); `StoreMentions.observeMentionCounts()` /
`observeTotalMentions()` supplies the numeric *badge* including all DM traffic. `StoreMentions` in
turn depends on `StoreMessageAck.getAllInternal()` (:75, :172). Any target reconstruction that merges
the three **must** reproduce the `StoreMentions` pipeline (M3) or the badge is server-snapshot-only.

### SEVERITY — PAIR 6: **BLOCKER**
BLOCKER 9 · MAJOR 12 · MINOR 5 · DIVERGENT-by-design 3

---

## PAIR 7 — GAP CHECK of 12 adjacent reference stores

Method: for each reference class, its `@Metadata d2` signature list + field/method inventory was
recovered, then the whole target tree (`app/src/main/java/com/discord/`, all packages) was grepped
for the class name, key method names, distinctive constants, and plausible Kotlin renames.

**JADX quality note:** only three bodies in these 12 files are opaque —
`StoreMessageAck.java:628` (`handleConnectionOpen`, "Method dump skipped, instructions count: 413"),
`StoreMessageAck$getPendingAck$2.AnonymousClass1.call` (:434), and
`StoreThreadMembers.java:122` (`handleThreadMemberListUpdate`). Signatures and fields are intact
everywhere; only those three bodies could not be read.

### 1. `StoreMentions.java` — **PARTIAL** — MAJOR
Target: `stores/StoreReadStates.kt:54-55, 80-81, 88-91, 223-228` (`mentionCountsByChannel/ByGuild`,
`getMentionCountsByChannel/ByGuild`), plus REST-only recent mentions at
`stores/StoreStream.kt:1719 getMentions(before)`.
Target derives counts **only** from server `ModelReadState.mentionCount` (`StoreReadStates.kt:223`).
Absent: `computeMentionCounts()`, `hasMention(long, User, UtcDateTime, List, List, Boolean)`,
`hasMention(Message)`, `isMentionableChannel`, `removeAllMessagesForChannel`, the
`mentionedMessages`/`myRoleIds`/`privateChannels`/`serverInitReadStates` fields,
`handleMessageCreateOrUpdate`, `handleMessageDeleted`, `handleGuildMemberAdd`, `handleThreadListSync`,
`handleThreadMembersUpdate`, `processMarkUnread$app`, `observeTotalMentions()`, `countsCache`.
Consequence: no optimistic/local mention badge updates, no role/@everyone mention detection, no
per-guild total-mention observable, no blocked-user filtering (`StoreUserRelationships` dependency
is gone).

### 2. `StoreMessageAck.java` — **PARTIAL** — MAJOR
Target: `stores/StoreReadStates.kt:24 (Ack), 50, 76-77, 94-97, 112-168, 189-216`; REST ack wired at
`stores/StoreStream.kt:236-246`; retry worker `workers/MessageAckWorker.kt` +
`stores/StoreStream.kt:1034-1048`; gateway at `stores/StoreStream.kt:649`.
Present: `Ack(messageId,isLockedAck,viewed)`, acks map + Persister (`ACKS_CACHE_KEY` vs reference
`MOST_RECENT_ACKS_V...`), `getAllAcks`/`getAck`, `observeAllAcks`/`observeAck`, `handleConnectionOpen`,
`handleMessageAck`, `markAsRead`, `markUnread`, `handlePreLogout`.
Absent — the whole **auto-ack pipeline**: `init(Context)` subscription combining
`StoreChannelsSelected.ResolvedSelectedChannel` x `StoreMessagesLoader.ChannelLoadedState` x
`StoreChat.InteractionState`, `getPendingAck(Observable,boolean,boolean)`,
`PendingAck`/`PendingAck.EMPTY`, `postPendingAck(Observable,int)`, `pruneAcks()`,
`handleChannelSelected()`, `handleGuildCreate()`, `handleMessageCreate(Message)`,
`handleThreadCreateOrUpdate(Channel)`, `internalAck(Channel,boolean,boolean)`,
`ack(long,boolean,boolean)`.
Absent — the whole **thread-ack layer**: `Companion.ThreadAckState` (`NotThread`/`Thread(canAckThread)`),
`isNonThreadChannel`, `isThreadAndAbleToAck`, `isThreadAndUnableToAck`, `getThreadAckStateInternal`,
`observeThreadAckState(long)`, `observeActiveThreadIdsWithPrevious()`, `updateThreadAcks(Set,Set)`,
`threadSyncSubscription`.
Also: target `markUnread` ignores the reference's message-list + `ThreadAckState` combine, and never
sets `viewed`.

### 3. `StoreThread.java` — **PRESENT (full)** — n/a
The reference "store" is only a marker annotation (`public @interface StoreThread`).
Target: `stores/Store.kt:14 annotation class StoreThread`, used pervasively (`Dispatcher.kt:28,33,38`,
`StoreReadStates.kt:111`, ...) and backed by an actual enforcement helper
`stores/StoreThreadScheduler.kt:26 assertCurrentThread()` that the reference lacks.

### 4. `StoreThreadDraft.java` — **MISSING** — MINOR
Zero hits for `ThreadDraft`, `draftState`, `autoArchiveDuration`, `shouldDisplayNameError`, or a
thread-scoped `isSending`. `widgets/chat/input/MessageDraftsRepo.kt` is message drafts only.
Absent: `ThreadDraftState(isPrivate, autoArchiveDuration, threadName, shouldDisplayNameError,
isSending)`, `setDraftState`, `setDraftSending`, `clearDraftState`, `observeDraftState`.
No thread-creation UI exists in the target either (no `WidgetThreadCreate`/`WidgetThreadBrowser`),
so this only matters once thread creation is implemented.

### 5. `StoreThreadMembers.java` — **MISSING** — MAJOR
No thread-keyed `memberLists`, no `observeThreadMembers(long)` anywhere (zero grep hits).
Named lookalikes are unrelated guild-member handlers and are **dead code** (no dispatcher calls them):
`stores/StoreGuilds.kt:135,138`, `stores/StoreUser.kt:86,90`, `stores/StoreUserPresence.kt:103,105`.
Root cause: `gateway/io/IncomingParser.kt:55-89` does not parse `THREAD_MEMBER_UPDATE`,
`THREAD_MEMBERS_UPDATE`, or `THREAD_MEMBER_LIST_UPDATE`; there is no
`ThreadMembersUpdate`/`ThreadMemberListUpdate` model in `api/thread/ThreadModels.kt` (only
`ThreadMetadata`, `MuteConfig`, `ThreadMember`).
Absent: `handleThreadMemberListUpdate`, `handleThreadMembersUpdate`, `handleThreadCreateOrUpdate`,
`handleThreadDelete`, `handleConnectionOpen`, `observeThreadMembers`.
Blocks `widgets/channels/memberlist/ChannelMemberListRows.kt:43 JoinLeaveThread` (join state can
never be computed); `WidgetChannelMembersList.kt:167 onJoinLeaveThreadClicked` is null-defaulted.

### 6. `StoreThreadMessages.java` — **MISSING** — MAJOR
No `ThreadState`, no `threads` map, no `MAX_THREAD_MESSAGE_COUNT`, no per-thread message-count or
most-recent-message tracking. `api/channel/Channel.kt:38 var messageCount` is the raw API field only,
never maintained.
Absent: `getThreadMessageCount(long)`, `observeThreadCountAndLatestMessage()` / `(long)`,
`getOrAddState`, `updateFromGuild`, `updateFromThread`, `deleteForGuild`, `deleteForParentChannel`,
`handleMessageCreate/Update/Delete`, `handleMessagesLoaded(ChannelChunk)`,
`handleThreadCreateOrUpdate`, `handleThreadDelete`, `handleThreadListSync`,
`handleGuildCreate/Delete`, `handleChannelDelete`, `handleConnectionOpen`.
`models/thread/dto/ModelThreadListSync.kt:11 mostRecentMessages` is parsed but never consumed
(`StoreStream.kt:630-632` forwards only `threads` to `StoreChannels`).
Thread preview rows in `ThreadBrowserThreadView.kt` / `ChatListRows.kt` have no count/last-message
source.

### 7. `StoreThreadsActive.java` — **PARTIAL** — MAJOR
Folded into `stores/StoreChannels.kt`: `threadsById` (`:22`), `threadsByIdSnapshot` (`:32`),
`storeThread` (`:240`), `handleThreadCreateOrUpdate` (`:125`), `handleThreadListSync` (`:153`),
`handleChannelOrThreadDelete` (`:129`), `getThreadsByIdInternal` (`:181`),
`getThreadsForChannelInternal` (`:189`), `getThreadsForGuildInternal` (`:191`),
`observeThreadsFromMessages` (`:226`).
Absent: the `threadsByGuild` two-level map; **`threadSyncedGuilds`** and
`observeThreadSyncedGuilds()` / `observeIsThreadSyncedGuild(long)`; the **forum-thread index**
(`forumThreadsById`, `getAllForumThreadsByIdInternal`, `observeAllActiveForumThreadsById()`);
`getActiveThreadsForChannel(long, Long)`, `getActiveThreadsForGuild(long)`,
`getChannelsWithActiveThreadsByGuild(long)`, `observeActiveThreadsForChannel`,
`observeActiveThreadsForGuild`, `observeAllActiveThreadsById`,
`observeChannelsWithActiveThreadsByGuild`, `rebuildThreadByIdMaps`, `syncThreadsFromChannelStore`,
`handleGuildDelete(long)` thread pruning by guild (target prunes on `handleGuildRemove` `:100` only).
`StoreReadStates.Inputs.activeForumThreads` (`StoreReadStates.kt:44`) is **never populated** —
`StoreStream.kt:1584-1594` omits it, so forum-post unreads are permanently dropped at
`StoreReadStates.kt:237`.

### 8. `StoreThreadsActiveJoined.java` — **PARTIAL (shell only)** — **BLOCKER**
Only the data shape survives: `stores/StoreReadStates.kt:34 data class ActiveJoinedThread(channel,
muted)` (reference has `channel, joinTimestamp, muted`) and the input slot
`StoreReadStates.kt:45 activeJoinedThreads`.
That input is **never filled** (`StoreStream.kt:1584-1594` omits it), so `StoreReadStates.kt:236`
(`if (channel.isThread() && !forumPost && joined == null) return@forEach`) unconditionally discards
every non-forum thread from unread/mention computation. **Thread unread badges are dead today.**
Absent: the entire store — `saveThreads`, `updateThread`,
`getActiveJoinedThreadsForChannelInternal`, `getActiveJoinedThreadsInternal`,
`handleChannelCreateOrUpdate`, `handleConnectionOpen`, `handleGuildCreate`, `handleGuildDelete`,
`handleThreadCreateOrUpdateOrDelete`, `handleThreadListSync`, `handleThreadMemberUpdate`,
`handleThreadMembersUpdate`, `observeActiveJoinedThreadsChannelsForGuild`,
`observeActiveJoinedThreadsForChannel`, `observeActiveJoinedThreadsForGuild`,
`observeAllActiveJoinedThreadsById`, `observeAllActiveJoinedThreadsChannelsById`.

### 9. `StoreThreadsJoined.java` — **MISSING** — **BLOCKER**
No `JoinedThread`, no `joinedThreads` map, no `ALL_FLAGS`/`NOTIFICATION_FLAGS` constants anywhere.
Absent: `getAllJoinedThreads()`, `getAllJoinedThreadsInternal`, `getJoinedThread(long)`,
`hasJoined(long)`, `hasJoinedInternal(long)`, `observeJoinedThread(long)`, `observeJoinedThreads()`,
`saveThreads(Guild)`, `deleteThreads(long)`, `handleConnectionOpen`, `handleGuildCreate/Delete`,
`handleSearchFinish(SearchState)`, `handleThreadCreateOrUpdate/Delete`, `handleThreadListSync`,
`handleThreadMemberUpdate`, `handleThreadMembersUpdate`.
Concrete downstream breakage: `stores/StoreUserGuildSettings.kt:22 getJoinedThreadFlags: (Long) -> Int
= { 0 }` and `:25 updateThreadSettings` are **never overridden** — `StoreStream.kt:175-180`
constructs `StoreUserGuildSettings(dispatcher, clock, observationDeck, getChannel = ...)` only. So
`submitThreadUpdate` (`StoreUserGuildSettings.kt:250`) always computes from flags=0 and no-ops the
REST call. (This is the same root cause as Pair 5 / F3.)

### 10. `ArchivedThreadsStore.java` — **MISSING** — MAJOR
Only a UI leaf exists: `widgets/channels/threads/browser/ThreadBrowserThreadView.kt:65,106,240
ThreadData.ArchivedThread` (a pure view model with no producer). No REST endpoints for archived
threads exist either (grep for `thread` under `restapi/` returns only `Thread::sleep` in
`RateLimitInterceptor.kt:12`).
Absent: `ThreadListingState` (`Uninitialized`/`Loading`/`Listing(threads,hasMore,isLoadingMore)`/
`Error`), `ThreadListingType` enum (`ALL_ARCHIVED_PUBLIC_THREADS`, `ALL_ARCHIVED_PRIVATE_THREADS`,
`MY_ARCHIVED_PRIVATE_THREADS`) with `fetchNext(long, List<Channel>)`,
`Companion.getLastArchiveTimestamp(List<Channel>)`, `fetchListing`, `fetchGuildForumThreadListing`,
`loadAndObserveThreadListing`, `loadAndObserveGuildForumThreadListing`,
`observeGuildForumThreadListing`, `handleConnectionOpen`, and the `fetchSubscriptions`/`listings`
state. Thread browser + forum browser cannot be built without it.

### 11. `StoreSlowMode.java` — **MISSING** — MAJOR
Zero hits for `SlowMode`/`slowMode` as a store. What exists is unrelated or dead:
- `utilities/messagesend/MessageResult.kt:5 Slowmode(cooldownMs)` + `MessageSendCoordinator.kt:242`
  (error-code 20016 reaction only) -> toast at `widgets/chat/input/WidgetChatInput.kt:116`.
- `widgets/chat/overlay/WidgetChatOverlay.kt:21 slowmodeSeconds` is **never assigned** —
  `StoreStream.kt:399-415` builds `WidgetChatOverlay.State` with `channelRateLimit` only, so the
  countdown at `:74` never renders.
- `models/domain/MessagePayloads.kt:10-11 messageSendCooldownMs / threadCreateCooldownMs`
  (`ModelTypingResponse`) are parsed but **never read** — this is exactly the reference's
  `onCooldown` feed.
Absent: `Type.MessageSend` / `Type.ThreadCreate`, `messageSendNextSendTimes` /
`threadCreateNextSendTimes` + their `BehaviorSubject`s, `channelMessageSendCooldownObservables` /
`channelThreadCreateCooldownObservables`, `getChannelCooldownObservable`,
`removeChannelCooldownObservable`, `onCooldownInternal`, `onCooldown(long,long,Type)`,
`onMessageSent(long)`, `onThreadCreated(long)`, `observeCooldownSecs(Long, Type)`,
`COOLDOWN_BUFFER_MS = 1000`, and the `PermissionUtils.hasBypassSlowmodePermissions(Long, Type)`
bypass check. Users in slowmode channels get no countdown, only a post-hoc 429 toast.

### 12. `StoreVoiceChannelSelected.java` — **MISSING** — **BLOCKER**
No store, no getters, no observable. Grep for `VoiceChannelSelected` / `selectVoiceChannel`: zero.
`stores/VoiceStoreContracts.kt` holds only inert model shells (`StoreApplicationStreaming`,
`StoreAudioManagerV2`, `StoreMediaSettings`); `stores/StoreVoiceParticipants.kt` is a 23-line
`VoiceUser` interface.
The only trace is the read-state input pair `stores/StoreReadStates.kt:42-43 selectedVoiceChannelId /
selectedVoiceChannelAt`, which is **never populated** (`StoreStream.kt:1584-1594`), so voice-channel
text unreads are always suppressed at `StoreReadStates.kt:240-242`.
Absent: `VOICE_CHANNEL_ID_NONE`, `selectedVoiceChannelId` / `lastSelectedVoiceChannelId` /
`timeSelectedMs` / `preselectedVoiceChannelId` / `sessionId`, `selectVoiceChannel(long)`,
`selectVoiceChannelInternal(long, boolean)`, `JoinVoiceChannelResult`, `getJoinability(long)`
(only the enum `utilities/voice/VoiceChannelJoinability.kt` exists),
`getVerificationLevelTriggered(Guild)`, `validateSelectedVoiceChannel()`, `clear()`/`clearInternal()`,
`getSelectedVoiceChannel()`, `getSelectedVoiceChannelId()`, `getLastSelectedVoiceChannelId()`,
`getTimeSelectedMs()`, `observeSelectedChannel()`, `observeSelectedVoiceChannelId()`,
`observeTimeSelectedMs()`, `handleAuthToken`, `handleChannelOrThreadCreateOrUpdate/Delete`,
`handleConnectionOpen`, `handleGuildMemberAdd`, `handleGuildRemove`,
`handleGuildRoleCreateOrUpdate/Remove`, `handleRtcConnectionStateChanged(RtcConnection.State)`,
`handleStreamTargeted(String)`, `handleVoiceStateUpdates(VoiceState)`.
Gateway side is also absent: `gateway/io/IncomingParser.kt` has no `VOICE_STATE_UPDATE` /
`VOICE_SERVER_UPDATE` dispatch case (`Opcode.kt:9-10` defines the opcodes but nothing sends or
receives them). Nothing in the app can join a voice channel; `views/JoinVoiceChannelButton.kt` and
`widgets/voice/**` are unreachable.

### PAIR 7 SUMMARY
| # | Store | Status | Severity |
|---|---|---|---|
| 1 | StoreMentions | PARTIAL (server counts only) | MAJOR |
| 2 | StoreMessageAck | PARTIAL (no auto-ack, no thread acks) | MAJOR |
| 3 | StoreThread (annotation) | PRESENT | — |
| 4 | StoreThreadDraft | MISSING | MINOR |
| 5 | StoreThreadMembers | MISSING | MAJOR |
| 6 | StoreThreadMessages | MISSING | MAJOR |
| 7 | StoreThreadsActive | PARTIAL (folded into StoreChannels) | MAJOR |
| 8 | StoreThreadsActiveJoined | PARTIAL (data class only, unwired) | **BLOCKER** |
| 9 | StoreThreadsJoined | MISSING | **BLOCKER** |
| 10 | ArchivedThreadsStore | MISSING | MAJOR |
| 11 | StoreSlowMode | MISSING | MAJOR |
| 12 | StoreVoiceChannelSelected | MISSING | **BLOCKER** |

BLOCKER 3 · MAJOR 7 · MINOR 1 · PRESENT 1

---

## CROSS-CUTTING ROOT CAUSES

1. **`StoreStream.kt:1582-1595 updateReadStateInputs()` omits four of ten `StoreReadStates.Inputs`
   fields** (`activeJoinedThreads`, `activeForumThreads`, `selectedVoiceChannelId`,
   `selectedVoiceChannelAt`). The unread logic at `StoreReadStates.kt:236-242` is already written
   correctly but silently discards **all** thread and voice-channel unreads. Single highest-leverage
   fix in the slice.
2. **`gateway/io/IncomingParser.kt:55-89` never parses** `THREAD_MEMBER_UPDATE`,
   `THREAD_MEMBERS_UPDATE`, `THREAD_MEMBER_LIST_UPDATE`, `VOICE_STATE_UPDATE`, or
   `VOICE_SERVER_UPDATE`; the corresponding API models (`ThreadMemberUpdate`, `ThreadMembersUpdate`,
   `ThreadMemberListUpdate`) do not exist in `api/thread/ThreadModels.kt`.
3. **Defaulted ctor lambdas that are never overridden** silently disable whole subsystems:
   `StoreUserGuildSettings.kt:22,25` (thread flags + thread settings REST),
   `StoreChannelMembers.kt:18` (`getRole`), `StoreChannels.kt:15-17`
   (`observeChannelPermissionsForGuild`, `getLurkingGuildIds`, `isAuthenticated`).
   A default that makes a feature vanish is worse than a missing parameter.
4. **`com.discord.api.channel.ChannelUtils` does not exist in the target.** Every store re-invents
   its own channel predicates, and they disagree with the reference in at least four places
   (isManaged, isGuildTextyChannel, isTextOrVoiceChannel, getDisplayName).
5. **`@StoreThread` is applied to 21 members across the slice vs 74 in the reference.** Confinement
   is documentation-plus-assertion in this codebase; unannotated mutators are unreviewable.
6. **Dead code:** `handleSearchFinish`, `handleGroupDMRecipient`, `preloadThread`, `onGroupCreated`,
   `observeDefaultChannel`, `observeThreadsFromMessages`, `observeIds` (StoreChannels);
   `readStates.init`, `guildSettings.init` (never called); `StoreGuilds.kt:135,138`,
   `StoreUser.kt:86,90`, `StoreUserPresence.kt:103,105`.

## OVERALL TOTALS

| Pair | BLOCKER | MAJOR | MINOR | Verdict |
|---|---|---|---|---|
| 1 StoreChannels | 2 | 10 | 9 | BLOCKER |
| 2 StoreChannelsSelected | 9 | 20 | 4 | BLOCKER |
| 3 StoreChannelMembers | 6 | 8 | 4 | BLOCKER |
| 4 StoreCollapsedChannelCategories | 0 | 2 | 6 | MAJOR |
| 5 StoreUserGuildSettings | 2 | 9 | 12 | BLOCKER |
| 6 StoreReadStates | 9 | 12 | 5 | BLOCKER |
| 7 Gap-check (12 stores) | 3 | 7 | 1 | BLOCKER |
| **TOTAL** | **31** | **68** | **41** | **BLOCKER** |

Plus 5 reference regions that JADX could not decompile and were **not** guessed at:
`StoreChannels.handleGroupDMRecipient`, `StoreChannels.storeThread` (partial),
`StoreChannelMembers.MemberListIdCalculator.computeIdFromOverwrites` (partial),
`StoreChannelMembers.makeGroup` (partial), `StoreMessageAck.handleConnectionOpen`,
`StoreMessageAck$getPendingAck$2` (partial), `StoreThreadMembers.handleThreadMemberListUpdate`.

