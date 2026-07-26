# Parity Audit — Guild-related Stores

TARGET: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (Discord Android 126.21, JADX)

Read-only audit. No target file modified.

---

## 1. `stores/StoreGuilds.kt` vs `stores/StoreGuilds.java` (+ `StoreGuildsKt.java`)

`StoreGuildsKt.java` is only a typealias holder (`ApiGuild`, `ApiGuildMember`, `ClientGuild`, `ClientGuildMember`) — no runtime behavior; target expresses the same via `import ... as` aliases (`StoreGuilds.kt:4,6`). No gap.

### MISSING FIELDS
| Ref | Target | Note |
|---|---|---|
| `HUGE_GUILD_SIZE = 2000` (`StoreGuilds.java:62`) | absent | drives partition-map snapshot path |
| `emptyComputedMap` (`:81`), `emptyRoles` (`:82`) | absent | ref returns these *identical instances* from `observeComputed(guildId)` / `observeRoles(guildId)` so `distinctUntilChanged()` dedupes by reference; target allocates `orEmpty()` each time (`StoreGuilds.kt:180,189`) — still equal-by-value, so behaviorally OK. MINOR. |
| `guildMembers: Map<Long, Map<Long, ApiGuildMember>>` (`:64`) | present (`StoreGuilds.kt:26`) | OK |
| `dispatcher` used to schedule `handleGuildMemberCommunicationEnabled` (`:501`) | field declared (`StoreGuilds.kt:17`) but **never used** | see below |

Persister keys, snapshot fields, and update sources (`GuildsUpdate`, `ComputedMembersUpdate`) all match (`StoreGuilds.kt:262-266` vs `StoreGuilds.java:83-86,850-852`).

### MISSING METHODS / BEHAVIOR
1. **`initClearCommunicationDisabledObserver(Context)` — ABSENT.** Ref `StoreGuilds.java:334-354` + `:614` (called from `init`). It `combineLatest`s `observeCommunicationDisabledGuildMembers()` with a 10-second `Observable.interval`, filters members whose timeout has expired, and feeds them to `StoreGuilds$initClearCommunicationDisabledObserver$2` → `handleGuildMemberCommunicationEnabled`. Without it, timed-out members never auto-clear. **BLOCKER.**
2. **`handleGuildMemberCommunicationEnabled` is not dispatcher-confined and drops the permission side effect.** Ref (`:500-502`, `:221-232`) schedules on `dispatcher` and, when `userId == meId`, calls `StoreStream.getPermissions().handleGuildMemberAdd(member)`. Target `StoreGuilds.kt:154-158` runs inline, no permission recompute. **MAJOR.**
3. **`handleGuildMember` default-preservation semantics are INVERTED.** Ref signature is `handleGuildMember(member, guildId, isFullGuildMember = false)` and the branch is `if (!isFullGuildMember && old != null) { copy(bio = old.bio, banner = old.banner) }` (`:474-476`; mask `5119` over `GuildMember.a(...)` selects params 10/11 = `bio`, `banner` — confirmed against `api/guildmember/GuildMember.java:31-46,49-62`). Target `StoreGuilds.kt:111-116` uses `preserveProfile: Boolean = false` and preserves **only when true**, and preserves **`avatar` as well**. So by default the target *destroys* cached bio/banner on every partial member update, and when enabled it clobbers avatar too. **BLOCKER.**
4. **`Actions` object — ABSENT.** Ref `StoreGuilds.java:91-168`: `Actions.requestMembers(AppComponent, Observable<String>, autocomplete)` (1 s debounce, `@`-prefix stripping, blank filter, gateway `requestGuildMembers`) and `Actions.requestRoleMembers(guildId, roleId, appComponent, restApi, storeGatewayConnection)` (`RestAPI.getGuildRoleMemberIds` → gateway). No equivalent anywhere in target (`grep requestGuildMembers` → 0 hits). **MAJOR.**
5. **`observeFromChannelId(channelId)` — ABSENT.** Ref `:661-673`.  **MAJOR.**
6. **`snapshotData` diverges in two ways** (target `StoreGuilds.kt:47-60` vs ref `:790-830`):
   - Ref persists `guilds MINUS StoreStream.getLurking().getLurkingGuildIdsSync()` (`:798`) — lurked guilds are never written to the `STORE_GUILDS_V34` persister. Target persists everything (`StoreGuilds.kt:53`). Since `StoreLurking` is missing entirely, this is a consequence of gap #8 below. **MAJOR.**
   - Ref uses `SnowflakePartitionMap.CopiablePartitionMap.fastCopy()` for any guild with ≥ `HUGE_GUILD_SIZE` (2000) computed members, and *rewrites the live map in place* to the partition map (`:817-825`). Target always does a full `HashMap` copy (`StoreGuilds.kt:58`). `SnowflakePartitionMap` does not exist in the target tree. **MAJOR** (O(n) copy per snapshot on large guilds).
   - Ref calls `super.snapshotData()`; target's `snapshotData` has no `super` call — need to confirm `StoreV2.snapshotData` is a no-op in target (it is declared open in `stores/StoreV2.kt`). MINOR.
7. **`handleGuildRemove` removes `guildsJoinedAt`; reference does not.** Ref `:516-522` calls `handleGuildUnavailable`, `handleGuild(remove)`, `handleGuildRoles(remove)`, `handleGuildMembers(remove)` — `guildsJoinedAt` is *never* pruned. Target `StoreGuilds.kt:104-105` removes it. Divergent (target arguably correct, but not parity). **MINOR.**
8. **`handleGuildMemberRemove` under-marks.** Ref `handleGuildMembers(guildId, userId, null, true)` (`:242-250`) marks **both** `GuildsUpdate` (raw map removal) and `ComputedMembersUpdate`. Target `StoreGuilds.kt:127-130` marks only `ComputedMembersUpdate`. **MINOR.**
9. **`observeSortedRoles` comparator is inverted on the tiebreak.** Ref uses `RoleUtils.ROLE_COMPARATOR` (`utilities/guilds/RoleUtils.java:28-41`): descending `position`, then **ascending snowflake timestamp**. Target `StoreGuilds.kt:192-194` uses `thenByDescending { it.id }` → newest role first. **MAJOR** (visible role-list ordering).
10. **`observeVerificationLevel` nullability.** Ref `:776-786` coerces `null → GuildVerificationLevel.NONE`. Target `StoreGuilds.kt:198-199` returns `Observable<GuildVerificationLevel?>`. **MINOR.**
11. **`observeJoinedAt(guildId)` nullability.** Ref `:899-913` maps through `GuildMemberUtilsKt.getJoinedAtOrNow(...)` (non-null, falls back to *now*). Target `StoreGuilds.kt:196` returns `Observable<Long?>`. `GuildMemberUtilsKt` does not exist in target. **MAJOR.**
12. **`observeCommunicationDisabledGuildMembers` source set differs.** Ref `:618-651` combines `observeGuildIds()` × `observeComputed()`. Target `StoreGuilds.kt:200-202` reads `guildsSnapshot.keys` *directly inside the map lambda* — i.e. it is not a declared dependency of the observable, so the list will not re-emit when only the guild set changes. **MAJOR** (stale emissions).
13. **`Guild.merge` lives in the wrong place and differs.** Ref implements it as `models/guild/Guild.java:509+` (`Guild.merge(apiGuild)`); target inlines it as a private extension `StoreGuilds.kt:224-259`. Field-level differences confirmed against the ref constructor order (`models/guild/Guild.java:74-117`):
    - ref passes literal `0` for `memberCount` (`:602` argument list) — memberCount from the API guild is discarded; target sets `api.memberCount` (`StoreGuilds.kt:243`).
    - ref default-masks `stickers` (mask `4` → bit 2) so merge always yields `emptyList()`; target sets `api.stickers.orEmpty()` (`StoreGuilds.kt:227`).
    - ref sets `name = guild.x()` unconditionally; target does `api.name.ifEmpty { name }` (`StoreGuilds.kt:228`).
    Target is arguably "better" but this is a parity divergence. **MINOR** each; **MAJOR** collectively if any consumer relies on `memberCount == 0` from merge.
14. **`handleGuildMembersChunk` signature mismatch.** Ref takes `GuildMembersChunk` (`:510-513`). Target takes `(guildId, Collection<ApiGuildMember>)` (`StoreGuilds.kt:132`). Same for `handleThreadMembersUpdate` / `handleThreadMemberListUpdate` / `handleGuildScheduledEventUsersFetch` — ref takes the raw gateway DTOs (`ThreadMembersUpdate`, `ThreadMemberListUpdate`, `List<ApiGuildScheduledEventUser>`) and does the null-filtering `mapNotNull` internally (`:540-592`). Target pushes that responsibility to `StoreStream`. Acceptable refactor **if** `StoreStream` performs the same `mapNotNull`; not verified in this slice. **MINOR / needs follow-up.**
15. **`reset()`** (`StoreGuilds.kt:62-73`) is EXTRA — reference `StoreGuilds` has no reset; logout teardown is handled elsewhere. Harmless, but note the target's `reset()` also clears the three persisters, which the reference never does.

### SEVERITY: **BLOCKER** (items 1, 3)

---

## 2. `stores/StoreGuildsSorted.kt` vs `stores/StoreGuildsSorted.java`

### MISSING FIELDS
| Ref (`StoreGuildsSorted.java:39-45`) | Target |
|---|---|
| `guildStore: StoreGuilds` | **MISSING** — target has no store dependency at all (`StoreGuildsSorted.kt:8-11` takes only `observationDeck`, `dispatcher`) |
| `lurkingGuildStore: StoreLurking` | **MISSING** (store does not exist) |
| `positions: List<ModelGuildFolder>` | present (`:29`) |
| `entries` / `entriesSnapshot` | present (`:30-31`) |
| — | EXTRA: `guilds`, `unavailableGuildIds`, `lurkingGuildIds`, `mutedGuildIds`, `guildsJoinedAt` (`:32-36`) — push-model mirrors of what ref pulls from stores |

### MISSING METHODS / BEHAVIOR
1. **`State` data class — ABSENT.** Ref `StoreGuildsSorted.java:248-335` (`mutedGuilds`, `joinedAt`, `userSettingsGuildPositions`). Target uses a 6-arg `update(...)` (`StoreGuildsSorted.kt:64-80`). **MINOR** (structural), but see #2.
2. **`observeStores()` + `init()` subscription — ABSENT.** Ref `:420-449` builds `Observable.combineLatest(guilds.observeGuilds(), userGuildSettings.observeMutedGuildIds().map{toSet}, guilds.observeJoinedAt(), userSettings.observeGuildFolders())`, and `init()` (`:536-541`) subscribes it through `computationLatest` + **`leadingEdgeThrottle(1, SECONDS)`**. The target has no `init()` override at all; instead `StoreStream.updateSortedGuilds()` (`StoreStream.kt:1567-1579`) pushes on every dispatch. **The 1-second leading-edge throttle is gone** → the sorted-guild rebuild now runs on every gateway event instead of at most 1 Hz. **MAJOR** (perf/regression).
3. **Lurking is hard-wired to empty.** `StoreStream.kt:1574` passes `emptySet()` for `lurkingGuildIds`. Ref `ensureValidPositions` (`:352-396`) removes lurked guilds from folders and re-inserts them at the head as singletons, and `rebuildSortedGuilds` depends on that. **BLOCKER** for lurk feature parity (root cause is the missing `StoreLurking`, §8).
4. **Fallback ordering diverges.** Ref `updatePositions` (`:476-514`) sorts by: muted-last, then `joinedAt` **ascending** (`longValue - longValue2`), then guild name (case-insensitive). Target `fallbackPositions()` (`StoreGuildsSorted.kt:86-91`) sorts muted-last, then **`thenByDescending(joinedAt)`**, then name, then id. **Note: the reference decompile at `:492-495` reads `getJoinedAt().get(l)` twice (both operands use `l`, never `l2`), which makes the joinedAt tiebreak a no-op. I cannot tell from JADX output whether this is a decompiler artifact or a genuine Discord bug — flagging explicitly rather than guessing.** Target's descending order is definitely not what the (readable part of the) reference expresses. **MAJOR.**
5. **`ensureValidPositions` insertion order.** Ref iterates `guildsInternal.values()` (HashMap order, nondeterministic) inserting each at index 0. Target sorts ascending, reverses, then inserts at 0 (`StoreGuildsSorted.kt:95-96`) — deterministic. Divergent but defensible. **MINOR.**
6. **`snapshotData` missing `super.snapshotData()`** (ref `:562`). Target `:82-84`. **MINOR.**
7. **`rebuildSortedGuilds` uses `getValue` (throws) where ref uses `checkNotNull`** — same crash semantics. No gap.
8. `Entry.Folder` / `Entry.SingletonGuild` / `asModelGuildFolder()` match (`StoreGuildsSorted.kt:12-27` vs ref `:50-243`). No gap.
9. `handlePositions` is `@StoreThread internal` in target (`:57-62`) vs `private` in ref — needed because `StoreStream.kt:1329` calls it. Acceptable.

### SEVERITY: **BLOCKER** (item 3; MAJOR-only if lurking is intentionally descoped, in which case downgrade to MAJOR on items 2 & 4)

---

## 3. `stores/StoreGuildSelected.kt` vs `stores/StoreGuildSelected.java`

### MISSING FIELDS
| Ref (`StoreGuildSelected.java:27-34`) | Target (`StoreGuildSelected.kt:10-16`) |
|---|---|
| `stream: StoreStream` | **MISSING** |
| `guildStore: StoreGuilds` | **MISSING** |
| `analyticsStore: StoreAnalytics` | **MISSING** |
| `selectedGuildIds: LeastRecentlyAddedSet<Long>` (cap 5) | `LinkedHashSet<Long>` + hand-rolled eviction (`:14`, `:69-75`). `LeastRecentlyAddedSet` does not exist in the target tree. Behaviorally equivalent for `add`/`remove`/iteration order; **the persisted Persister payload type changes** (`Persister<LeastRecentlyAddedSet<Long>>` → `Persister<LinkedHashSet<Long>>`) under the same key `STORE_GUILD_SELECTED_V5` → **cache-format incompatibility**. **MAJOR.** |
| `selectedGuildIdsCache`, `selectedGuildIdsSnapshot` | present |

`UNSELECTED = 0L`, `MAX_SAVED_GUILD_IDS = 5`, key `STORE_GUILD_SELECTED_V5` all match.

### MISSING METHODS / BEHAVIOR
1. **`observeSelectedGuild(): Observable<Guild>` — ABSENT.** Ref `:119-123` — observes on **two** update sources `{this, guildStore}` and resolves `guildStore.getGuilds()[selectedGuildId]` (`StoreGuildSelected$observeSelectedGuild$1:25`). **MAJOR.**
2. **`dispatchSampleGuildIdSelected(guildId)` — ABSENT.** Ref `:52-54`. **MAJOR.**
3. **`snapshotData` drops two behaviors** (target `:63-67` vs ref `:137-155`):
   - `analyticsStore.trackGuildViewed(selectedGuildIds.last())` (`:142`) — **MISSING**. **MAJOR.**
   - the persisted set is filtered to exclude `stream.getLurking().getLurkingGuildIdsSync()` (`:143-153`) — **MISSING** (target persists the raw set, `:66`). **MAJOR** (consequence of missing `StoreLurking`).
4. **`handlePreLogout` semantics differ.** Ref (`:99-102`) only `clear()` + `markChanged()`; the subsequent `snapshotData` re-inserts `0L` and rewrites the cache. Target (`:54-61`) additionally calls `selectedGuildIdsCache.clear(persist = true)` and pre-inserts `UNSELECTED`. Net effect is close, but the target wipes the persister eagerly. **MINOR.**
5. **`handleConnectionOpen` filter.** Ref removes via `StoreGuildSelected$handleConnectionOpen$1` (returns `true` when the id is absent from `payload.guilds`; the decompiled lambda body at `:29-55` is a loop-with-early-return that JADX renders opaquely — I could not read whether `0L`/`UNSELECTED` is explicitly spared). Target `:38-42` explicitly spares `UNSELECTED`. Cannot confirm parity from JADX output — **flagged, not asserted.**
6. **`init` vs `restore`.** Ref `init` calls `super.init(context)` then clear+addAll+markChanged (`:105-111`). Target splits into `init(context) = restore()` (`:30-36`). Equivalent, `super.init` omitted. **MINOR.**
7. `getSelectedGuildId()` / `getRecentlySelectedGuildIds()` / `getSelectedGuildIdInternal()` / `observeSelectedGuildId()` / `observeRecentSelectedGuildIds()` / `set()` / `handleGuildRemove()` / `handleGuildSelected()` all match.
8. `handleGuildRemove` early-returns on `guild.unavailable` in target (`:45`); ref (`:85-90`) has **no** such guard — an unavailable-guild GUILD_DELETE will deselect in ref but not in target. **MINOR/MAJOR** depending on gateway wiring.

### SEVERITY: **MAJOR**

---

## 4. `stores/StoreExpandedGuildFolders.kt` vs `stores/StoreExpandedGuildFolders.java`

### MISSING FIELDS
None. `openFolderIds`/`openFolderIdsSnapshot`/`openFolderIdsCache` + key `CACHE_KEY_OPEN_FOLDERS` all match (`StoreExpandedGuildFolders.kt:12-14,53` vs ref `:19-23,80`).

### MISSING METHODS / BEHAVIOR
1. `openFolder`/`closeFolder` (dispatcher-scheduled), `handleFolderOpened`/`handleFolderClosed`, `getOpenFolderIds`, `observeOpenFolderIds`, `snapshotData` (persist=true) — all present and matching.
2. **`observeOpenFolderIds()` adds `distinctUntilChanged()`** (target `:18`); ref `:56-58` does **not**. Divergent (target strictly better). **MINOR.**
3. **`restore()` clears first** (target `:36`); ref `init` (`:50-54`) only `addAll`s and does not call `super.init(context)`. Divergent. **MINOR.**
4. **`reset()`** (target `:41-45`) is EXTRA — reference has no logout reset for this store, so open-folder state survives logout in the reference. **MINOR (EXTRA).**

### SEVERITY: **MINOR**

---

## 5. `stores/StoreGuildSubscriptions.kt` vs `stores/StoreGuildSubscriptions.java`

This is a **near-total rewrite**, not a reconstruction.

### MISSING FIELDS
| Ref (`StoreGuildSubscriptions.java:18-20`) | Target |
|---|---|
| `storeStream: StoreStream` | **MISSING** |
| `dispatcher: Dispatcher` | **MISSING** (target takes `observationDeck` + an `onSubscription` callback, `StoreGuildSubscriptions.kt:12-15`) |
| `subscriptionsManager: GuildSubscriptionsManager` | **MISSING** — the entire `utilities/lazy/subscriptions/` package (`GuildSubscriptionsManager`, `GuildChannelSubscriptionsManager`, `GuildMemberSubscriptionsManager`, `GuildThreadSubscriptionsManager`, `GuildSubscriptions`) is absent from the target tree |
| — | EXTRA: `ranges`, `rangesSnapshot`, `events: PublishSubject<GuildSubscriptionPayload>` (`:16-18`), `GuildSubscriptionPayload` data class (`:7-10`) |

The manager tracks `subscribedGuilds`, `typingGuilds`, `activityGuilds`, `threadGuilds`, `pendingEmissions` (`GuildSubscriptionsManager.java:21-29`). **None of that state exists in the target.**

### MISSING METHODS / BEHAVIOR
1. **`handleGuildSelect(guildId)` — ABSENT.** Ref `:93-102`: `subscribeTyping` + `subscribeActivities` + `subscribeThreads` for the newly selected guild. **BLOCKER** — typing indicators, presence/activity updates and thread sync for the selected guild are never requested.
2. **`handleSubscribeMember(guildId, userId)` / `handleUnsubscribeMember(...)` — ABSENT.** Ref `:110-126`. **BLOCKER.**
3. **`subscribeUser(guildId, userId)` / `unsubscribeUser(...)` — ABSENT.** Ref `:149-155`. **BLOCKER.**
4. **`subscribeThread(guildId, threadId)` — ABSENT.** Ref `:142-147`. **MAJOR.**
5. **`subscribeChannelRange(guildId, channelId, IntRange)` — renamed and re-scoped.** Ref `:134-140` dispatcher-schedules into `subscriptionsManager.subscribeChannel`. Target exposes `handlePanelOpened` / `handleViewportChanged` / `handlePanelClosed` (`:21-33`) called directly from `StoreStream.kt:1314-1319`. Guard `guildId <= 0 || channelId <= 0` is preserved (`:69`). **MAJOR** (API shape).
6. **`handleConnectionReady(false)` branch — WRONG.** Ref `:82-84`: on disconnect it calls `subscriptionsManager.retainAll([selectedGuildId, rtcConnection.connectedGuildId])` — i.e. drops all subscriptions except the selected guild and the voice-connected guild. Target `:59-62` **returns immediately** when `!ready` and only re-publishes on `ready`. **BLOCKER.**
7. **`handleConnectionReady(true)` incomplete.** Ref also calls `handleGuildSelect(selectedGuildId)` before `queueExistingSubscriptions()` (`:75-79`). Target only re-publishes stored ranges. **BLOCKER.**
8. **`handlePreLogout()` — renamed to `reset()`** (`:43-50`). Ref `:104-108` calls `subscriptionsManager.reset()`. Target also emits empty payloads per guild, which the ref does via the manager's `onChange`. Approximately equivalent. **MINOR.**
9. **`snapshotData` does not flush.** Ref `:128-132` calls `subscriptionsManager.flush()` — the batched `pendingEmissions` are drained exactly once per dispatch cycle. Target `:64-66` only rebuilds `rangesSnapshot`; emissions happen eagerly inside `publish()` (`:76-84`) *per mutation*, not batched. **MAJOR** (gateway spam).
10. **`RangeComputer.computeRanges` diverges.** Ref (`:40-61`) always includes `0 until chunkSize` when `first/chunkSize > 0`, then steps `first..last` by `chunkSize` using `until` (half-open, so `[0,100)`, `[100,200)`…). Target (`:90-98`) builds `0..99`, `100..199`… (closed ranges), always prepends chunk `0`, and uses `(visibleRange.last - 1)` for the last chunk. Boundary behavior differs at exact chunk edges and for empty ranges (target returns `[INITIAL_RANGE]`, ref returns `[]`). **MAJOR.**
11. **`markChanged()` on every manager call** in ref (`:80,84,90,101,107,116,125`); target only marks inside `publish`/`handleGuildRemove`/`reset`. **MINOR.**
12. `handleGuildRemove(guildId)` present in both (`:36-41` vs ref `:87-91`). OK.
13. **EXTRA:** `observeSubscriptions(): Observable<GuildSubscriptionPayload>` (`:56`) and `observe(guildId)` (`:54-55`) — no reference equivalent (ref publishes via the `onChange` `Function2` callback).

### SEVERITY: **BLOCKER**

---

## 6. `stores/StorePermissions.kt` vs `stores/StorePermissions.java`

### MISSING FIELDS
| Ref (`StorePermissions.java:44-56`) | Target (`StorePermissions.kt:12-26`) |
|---|---|
| `storeThreadsJoined: StoreThreadsJoined` | **MISSING** |
| `storeStageInstances: StoreStageInstances` | **MISSING** |
| `storeUser`, `storeChannels`, `storeGuilds`, `observationDeck` | present |
| `permissionsForGuilds` / `permissionsForChannels` / 3 snapshots / 2 persisters | present, keys match (`STORE_GUILD_PERMISSIONS_V5`, `STORE_CHANNEL_PERMISSIONS_BY_GUILD_V5`) |
| `permissionsForChannels: Map<Long, Map<Long,Long>>` (mutable inner) | target declares `HashMap<Long, Map<Long, Long>>` (`:23`) — inner map immutable. Ref mutates via `h0.plus`/`h0.minus` reassignment too, so equivalent. OK. |

### MISSING METHODS / BEHAVIOR
1. **The permission computation itself is a different algorithm. BLOCKER.**
   - Ref `computeChannelPermissions` (`:81-103`) calls `PermissionUtils.computePermissions(meId, channel, parentChannel, guild.ownerId, member, roles, stageInstancesForGuildInternal(guildId), storeThreadsJoined.hasJoinedInternal(channel.id))` and short-circuits to `Permission.ALL` when `ChannelUtils.isPrivate(channel)`.
   - Ref `updateGuildPermissions` (`:162-180`) calls `PermissionUtils.computeNonThreadPermissions(meId, guildId, ownerId, member, roles, null)`.
   - Target (`StorePermissions.kt:96-116`) calls `PermissionUtils.computeGuildPermissions(...)` / `computeChannelPermissions(userId, guildId, ownerId, member, roles, channel, parent)` — **no stage-instance map, no thread-joined flag, no `Permission.ALL` DM short-circuit**. Confirmed: target `utilities/permissions/PermissionUtils.kt` exposes only `computeGuildPermissions` and `computeChannelPermissions`; the reference exposes `computeNonThreadPermissions`, `computePermissions`, `computeThreadPermissions` (`utilities/permissions/PermissionUtils.java:185,194,223`). Stage-channel speaker permissions and private-thread membership permissions are therefore wrong.
2. **`updateChannelPermissions(channel)` — ABSENT.** Ref `:148-159` recomputes a **single** channel and merges it into the guild's map. Target's `handleChannelOrThreadCreateOrUpdate` (`:82`) recomputes the *entire guild* instead. **MAJOR** (perf + `markChanged` fan-out).
3. **`handleChannelOrThreadDelete` diverges.** Ref `:203-212` removes just that channel id from the guild's map. Target `:83` recomputes the whole guild — a deleted channel would only disappear because it is no longer in `storeChannels`. **MAJOR.**
4. **`handleThreadListSync(ModelThreadListSync)` — ABSENT.** Ref `:334-340`. **MAJOR.**
5. **`handleThreadMemberUpdate(ThreadMemberUpdate)` — ABSENT.** Ref `:343-349`. **MAJOR.**
6. **`handleThreadMembersUpdate(ThreadMembersUpdate)` — ABSENT.** Ref `:352-388` (recomputes only when *me* is in the added/removed lists). **MAJOR.**
7. **`handleMessagesLoaded(List<Message>)` — ABSENT.** Ref `:249-266`. **MAJOR.**
8. **`handleSearchFinish(SearchState)` — ABSENT.** Ref `:275-321`. *(Note: JADX failed to decompile this method to Java — it is emitted as raw smali-like bytecode with `throw new UnsupportedOperationException("Method not decompiled")`. The bytecode is readable enough to see it mirrors `handleMessagesLoaded`, but I am flagging that the decompile is broken rather than asserting exact semantics.)* **MAJOR.**
9. **`handleStageInstanceChange(StageInstance)` — ABSENT.** Ref `:324-331`. **MAJOR.**
10. **`recomputeAllPermissions` diverges.** Ref (`:110-139`) groups **all threads** by guild from `storeChannels.getThreadsByIdInternal()`, then iterates `channelsByGuild.keys` *excluding guildId 0* and calls `updateGuildPermissions(guildId, threadsForThatGuild)`. Target (`:62-67`) iterates `storeGuilds.getGuildsInternal().keys` and does not exclude 0 / does not pre-group threads. Different guild set (guilds with no channels vs channels with no guild). **MAJOR.**
11. **`updateGuildPermissions` guard differs.** Ref returns early if `meId == 0` **or** `channelsByGuild[guildId] == null` **or** `guild == null` (`:165-167`), and only `markChanged()`s when a value actually changed (`:171,176`). Target (`:97-99,115`) returns on missing guild / `userId == 0` but `markChanged()`s unconditionally at the end. **MINOR.**
12. **`restoreFromCache()` is inlined into `init()`** in target (`:30-34`); ref keeps it as a private `@StoreThread` method (`:142-145`). No behavioral gap. **MINOR.**
13. **`handleConnectionOpen(ModelPayload)` overload is EXTRA** (`:70-71`) — ref only has the no-arg form (`:215-218`). Harmless.
14. **`reset()` is EXTRA** (`:36-42`) — ref has no reset. Note target's `reset()` clears the persisters. **MINOR (EXTRA).**
15. **`getPermissionsByChannelInternal()` (no-arg, flattened) is EXTRA** (`:48-49`) — ref only has the per-guild overload (`:191-194`). Used by `StoreStream.kt:1586`. Harmless but note it allocates a full flatten on every call.
16. **`snapshotData` uses different copy helpers.** Ref uses `CollectionExtensionsKt.snapshot(...)` and pre-sizes the flattened `HashMap` (`:439-455`). Target uses `toMap()`/`mapValues` (`:87-94`). Equivalent. `handleChannelCreateOrUpdate`/`handleChannelDelete` aliases (`:84-85`) are EXTRA. **MINOR.**
17. `observeAllPermissions` / `observeChannelPermissionsForGuild` / `observePermissionsForAllChannels` / `observePermissionsForChannel` / `observePermissionsForGuild` all present. Target adds `.distinctUntilChanged()` to `observePermissionsForChannel` (`:58`) where ref does not (`:427-429`); ref's `observeAllPermissions` also lacks it, matching target. **MINOR.**

### SEVERITY: **BLOCKER** (item 1)

---

## 7. `stores/StoreStageInstances.kt` vs `stores/StoreStageInstances.java`

The closest-to-parity file in this slice.

### MISSING FIELDS
None. `stageInstancesByChannel` (`LinkedHashMap`), `stageInstancesByChannelSnapshot`, `stageInstancesByGuild` (`LinkedHashMap`), `stageInstancesByGuildSnapshot`, `observationDeck` — all match (`StoreStageInstances.kt:11-15` vs ref `:25-29,174-181`).

### MISSING METHODS / BEHAVIOR
1. **`handleConnectionOpen` signature + missing `markChanged`.** Ref `:95-105` takes `ModelPayload` and does **not** call `markChanged()` after the loop (each `handleGuildAdd` → `handleStageInstanceCreateOrUpdate` marks individually — so a payload with **zero** stage instances leaves the cleared maps unmarked, i.e. a stale snapshot). Target `:33-39` takes `List<Guild>` and marks unconditionally. Target is a bug-fix; parity divergence. **MINOR.**
2. **`handleGuildRemove` early-exit differs.** Ref `:119-131` returns *without* `markChanged()` when the removed guild had no stage instances. Target `:45-49` always marks. **MINOR.**
3. **`observeStageInstanceForChannel` nullability.** Ref `:151-153` emits `StageInstance?` (nullable, `Observable<StageInstance>` with nulls). Target `:25-26` **filters nulls out** — subscribers never learn a stage instance ended. **MAJOR.**
4. **`observeStageInstances` / `observeStageInstancesForGuild`** match (no `distinctUntilChanged` in either). OK.
5. **`getStageInstances*` accessors** all present and matching.
6. **`snapshotData` missing `super.snapshotData()`** — ref also omits it (`:165-172`). No gap.
7. **`reset()` is EXTRA** (`:64-70`) — ref has no reset. **MINOR (EXTRA).**
8. **`StoreThreadScheduler.assertCurrentThread()` calls** (`:34,66,78,85`) are EXTRA hardening; ref relies on the `@StoreThread` annotation only. Harmless.
9. `handleStageInstanceCreate`/`Update`/`Delete`, `handleGuildAdd`, `handleChannelDelete` all match. Target's `delete(guildId: Long?, channelId)` mirrors ref's `handleDelete(Long, long)` including the nullable guildId. OK.

### SEVERITY: **MINOR** (item 3 is MAJOR in isolation)

---

## 8. Gap-check: reference stores with no target counterpart

Method used: `grep -ril <name>` over `app/src/main/java --include=*.kt` in the target tree, plus a directory listing of `com/discord/stores/`.

| Reference store | Target status | Behavior lost |
|---|---|---|
| **`StoreGuildMemberCounts.java`** | **MISSING** — no file, no `guildMemberCounts`, no `observeApproximateMemberCount`, no `getApproximateMemberCount` anywhere. (`approximateMemberCount` hits in the target are only `api/guild/Guild.kt`, `ModelInvite.kt`, and two invite widgets — unrelated.) | `guildMemberCounts: HashMap<Long,Int>` + snapshot; `handleConnectionOpen`, `handleGuildCreate`, `handleGuildDelete`, `handleGuildMemberAdd` (increment), `handleGuildMemberRemove` (decrement), `observeApproximateMemberCount(guildId)`, `snapshotData`. **BLOCKER** — guild member counts never update live. |
| **`StoreGuildMemberRequester.java`** | **MISSING** — no file; `requestGuildMembers` has **0 hits** in the target tree. | `channelPendingMessages: TreeMap<Long, Map<Long, Message>>`, `requestManager: GuildMemberRequestManager`, `isConnected`; `guildMemberExists`, `sendRequests`, `handleConnectionOpen`, `handleConnectionReady`, `handleGuildMembersChunk`, `handleLoadMessages`, `performQueuedRequests`, `queueRequest`, `requestForMessages`. **BLOCKER** — unknown message authors are never lazily fetched from the gateway; chat will show raw IDs / "Unknown User". |
| **`StoreGuildsNsfw.java`** | **MISSING** — no file. (`nsfw` hits are the `Guild`/`Channel`/`User` model fields and `NsfwAllowance`, not the gate store.) | `guildIdsAllowed: Set<Long>` + snapshot, `GUILDS_ALLOWED_KEY` SharedPreferences persistence, `toGuildIdSet`/`toStringSet`, `allow(guildId)`, `deny(guildId)` (dispatcher-scheduled), `isGuildNsfwGateAgreed`, `observeIsGuildNsfwGateAgreed`, `init`, `snapshotData`, `storeChannels` dependency. **MAJOR** — NSFW guild gate never shown/remembered. |
| **`StoreGuildProfiles.java`** | **MISSING** — no file. | `guildProfilesState: Map<Long, GuildProfileData>` + snapshot, nested `GuildProfileData(fetchState: FetchStates, data: GuildPreview)`; `fetchGuildProfile`, `fetchIfNonexisting`, `handleGuildProfileFetchStart/Success/Failed`, `observeGuildProfile`, `snapshotData`. **MAJOR.** |
| **`StoreGuildWelcomeScreens.java`** | **MISSING** — no store file. (`welcomeScreen` hits are only the `api/guild/welcome/GuildWelcomeScreen.kt` DTO, `models/guild/Guild.kt` field, and the merge in `StoreGuilds.kt` — the DTO exists, the store does not.) | `guildWelcomeScreensState: HashMap<Long, State>` + snapshot, `guildWelcomeScreensSeen: HashSet<Long>` + snapshot, sealed `State{Fetching, Failure, Loaded(GuildWelcomeScreen)}`; `fetchIfNonexisting`, `handleGuildWelcomeScreen`, `handleGuildWelcomeScreenFetchStart/Failed`, `handleGuildJoined`, `hasWelcomeScreenBeenSeen`, `markWelcomeScreenShown`, `getGuildWelcomeScreen`, `observeGuildWelcomeScreen`, `snapshotData`. **MAJOR.** |
| **`StoreLurking.java`** | **MISSING** — no file. The only `lurking` hits are `StoreChannels.kt`, `StoreGuildsSorted.kt` (parameter name), and `widgets/guilds/list/GuildFolderReorder.kt`, all of which receive `emptySet()` from `StoreStream.kt:1574`. | `lurkedGuilds: Map<Long, LurkContext>`, `lurkedGuildsSubject: BehaviorSubject`, `lurkRequest: LurkRequest?`, `sessionId: String?`, `guildsStore`, `stream`; `LurkContext(guildId, shouldNavigate)`, `LurkRequest(guildId, channelId)`, `Companion.isLurking(...)`; `startLurking`, `startLurkingInternal`, `startLurkingAndNavigate`, `stopLurkingInternal`, `postJoinGuildAsMember`, `postLeaveGuild`, `getLurkingGuildIds()`, `getLurkingGuildIdsSync()`, `isLurking$app_productionGoogleRelease(guild)`. **BLOCKER** — cascades into `StoreGuilds.snapshotData` (§1.6), `StoreGuildSelected.snapshotData` (§3.3), and `StoreGuildsSorted.ensureValidPositions` (§2.3). |
| **`StoreGuildVoiceRegions.java`** | **MISSING** — no file; `voiceRegion` has **0 hits**. | `guildVoiceRegionsState: HashMap<Long, State>` + snapshot, sealed `State{Fetching, Failure, Loaded(List<ModelVoiceRegion>)}`; `fetchIfNonexisting`, `handleGuildVoiceRegions`, `handleGuildVoiceRegionsFetchStart/Failed`, `getGuildVoiceRegions`, `observeGuildVoiceRegions`, `snapshotData`. **MAJOR.** |

Supporting utilities also absent (referenced by the above and by §1/§3/§5):
`utilities/lazy/subscriptions/*` (`GuildSubscriptionsManager` and its 4 sub-managers), `utilities/collections/LeastRecentlyAddedSet`, `utilities/collections/SnowflakePartitionMap`, `utilities/guilds/RoleUtils`, `utilities/guildmember/GuildMemberUtilsKt` — all **0 hits** in the target tree.

---

## Severity roll-up

| Pair | BLOCKER | MAJOR | MINOR | Verdict |
|---|---|---|---|---|
| 1. StoreGuilds | 2 | 8 | 6 | BLOCKER |
| 2. StoreGuildsSorted | 1 | 2 | 4 | BLOCKER |
| 3. StoreGuildSelected | 0 | 5 | 4 | MAJOR |
| 4. StoreExpandedGuildFolders | 0 | 0 | 3 | MINOR |
| 5. StoreGuildSubscriptions | 4 | 4 | 3 | BLOCKER |
| 6. StorePermissions | 1 | 9 | 5 | BLOCKER |
| 7. StoreStageInstances | 0 | 1 | 4 | MINOR |
| 8. Gap-check (7 stores) | 3 | 4 | 0 | BLOCKER |
| **Total** | **11** | **33** | **29** | |
