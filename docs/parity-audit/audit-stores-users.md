# Parity Audit — User & Social Stores (PLAN Phase 8)

TARGET root: `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE root: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (JADX, Discord Android 126.21)

Notes on JADX quality encountered:
- `StoreUserPresence.flattenPresence(long)` and `StoreUserPresence.updateSelfPresence(...)` are emitted as **raw smali-style bodies** with `throw new UnsupportedOperationException("Method not decompiled")`. I read the register-level listing and reconstructed semantics; where the listing is ambiguous I say so explicitly rather than guess.
- `StoreUserPresence.getApplicationActivity` / `observeApplicationActivity$1` have JADX's known broken `continue`-before-`if` firstOrNull pattern; semantics (firstOrNull by applicationId) are unambiguous.
- `StoreUserSettings.getIsSyncTextAndImagesEnabled(boolean)` is a JADX mis-naming of the Kotlin setter `setIsSyncTextAndImagesEnabled`.

---

## 1. `stores/StoreUser.kt` vs `stores/StoreUser.java` — SEVERITY: **MAJOR**

### MISSING FIELDS
| Reference | Target | Note |
|---|---|---|
| `SnowflakePartitionMap.CopiablePartitionMap<User> users` (StoreUser.java:66, ctor :481) | `LinkedHashMap<Long, User>` (StoreUser.kt:29) | Reference relies on `users.fastCopy()` (StoreUser.java:426) — an O(partition) copy-on-write snapshot. Target does a full `LinkedHashMap(users)` copy on every dispatch (StoreUser.kt:142). **MAJOR** (snapshot cost is O(n) per dispatch on the store thread). |
| `UserRequestManager userRequestManager` (StoreUser.java:65, :484) | `requestUsers: (Set<Long>, (ApiUser)->Unit)->Unit` + `requestedUserIds: HashSet<Long>` (StoreUser.kt:26,30) | Target re-implements dedupe inline. Reference's manager is `synchronized`, stores `Subscription`s, and unsubscribes on request end (`UserRequestManager.onRequestEnded`). Target never cancels in-flight requests. **MINOR**. |
| — | `requestedUserIds` cleared in `handleConnectionOpen` / `reset` (StoreUser.kt:50,136) | EXTRA, harmless. |

`EMPTY_ME_USER` matches (StoreUser.java:73 vs StoreUser.kt:225). Cache key `STORE_USERS_ME_V13` matches. `MeUpdate` / `UsersUpdate` update sources match.

### MISSING METHODS / BEHAVIOR
1. **`updateUser` merge semantics DIVERGE.** Reference `StoreUser.updateUser` (StoreUser.java:120-126) *always* constructs `new CoreUser(user)` and replaces — no field merging, and it will **downgrade a stored `MeUser` to a `CoreUser`**. Target `updateUser` (StoreUser.kt:150-161) merges via `mergeCore`/`mergeMe` (StoreUser.kt:180-212) preserving `MeUser` identity and null-serializable fields. This is a *deliberate improvement*, but it changes observable behavior: in the reference, after any `handleUserUpdated`, `users[meId]` is a `CoreUser` (no email/phone/token), and downstream `is MeUser` checks fail. **MAJOR / DIVERGENT** — decide and document.
2. **`handleMessageCreateOrUpdate` diverges** (ref StoreUser.java:264-279 vs target StoreUser.kt:76-84):
   - Reference iterates `message.t()` = **mentions only**; it does **not** update `message.author`. Target updates author too (StoreUser.kt:77). EXTRA.
   - Reference urgency flag test is `(message.flags & 16) != 0` — raw `Message.l()` bitmask **16**, not an `isUrgent()` helper. Target uses `message.isUrgent()` (models `Message`); verify the model helper is `flags and 16`.
   - Reference marks only `MeUpdate` (StoreUser.java:277) and does **not** write `me` back into `users`. Target writes `users[me.id] = me` and marks `MeUpdate, UsersUpdate` (StoreUser.kt:81-82). DIVERGENT.
   - Reference has **no idempotency guard**; target adds `me.flags and URGENT == 0` (StoreUser.kt:79). EXTRA (correct).
3. **Gateway handler signature drift** (each is a call-site break for a faithful StoreStream):
   - `handleGuildMembersChunk(GuildMembersChunk)` (StoreUser.java:245) → target `handleGuildMembersChunk(List<GuildMember>)` (StoreUser.kt:70).
   - `handleGuildScheduledEventUsersFetch(List<ApiGuildScheduledEventUser>)` (StoreUser.java:253; unwraps `.c()` and skips nulls) → target `(List<ApiUser>)` (StoreUser.kt:71).
   - `handleThreadMemberListUpdate(ThreadMemberListUpdate)` (StoreUser.java:308) / `handleThreadMembersUpdate(ThreadMembersUpdate)` (StoreUser.java:327) → target `(List<GuildMember>, List<Presence>)` (StoreUser.kt:86,90).
   - `handleMessagesLoaded(StoreMessagesLoader.ChannelChunk)` (StoreUser.java:282) → target `(List<Message>)` (StoreUser.kt:85).
   - `handleFriendSuggestionsLoaded(List<api.FriendSuggestion>)` / `handleFriendSuggestionCreate(api.FriendSuggestion)` (StoreUser.java:214,220) → target takes `ApiUser` / `List<ApiUser>` (StoreUser.kt:72-73).
   **MAJOR** as a group (API contract), MINOR individually if StoreStream adapts (it does).
4. **MISSING: connection-open observability/analytics.** Reference `handleConnectionOpen` emits `AppLog.i("Discovered N initial users.")` and `AppLog.g(meId, email, username+discriminator)` — the latter is the **user-identification hook for logging/crash attribution** (StoreUser.java:203-209). Target has neither (StoreUser.kt:46-57). **MINOR**.
5. **MISSING: `super.init(context)`.** Reference `init` calls `super.init(context)` then restores the cache (StoreUser.java:364-369). Target `init` calls only `restore()` (StoreUser.kt:34-36) — if `Store.init` does anything (prefs wiring), it is skipped. **MINOR** — verify target `Store.init`.
6. **`fetchMissing` dispatcher confinement diverges.** Reference `getUsers(Collection, boolean)` calls `fetchMissing` **synchronously on the caller thread** (StoreUser.java:450) despite `@StoreThread`; `observeUsers` likewise inside `doOnNext` (StoreUser.java:461). Target wraps both in `dispatcher.schedule` (StoreUser.kt:113,125). Target is *safer*; note as DIVERGENT (behavioral: reference fires the REST request immediately, target defers a tick).
7. **`observeMe(emitEmpty)` comparison diverges.** Reference filters `meUser != EMPTY_ME_USER || emitEmpty` using **reference identity** (`!=` on objects, StoreUser.java:385). Target uses structural `it != EMPTY_ME_USER` (StoreUser.kt:119). A restored-from-cache me-user that is structurally empty passes in the reference but is filtered in the target. **MINOR**.
8. **`snapshotData` guard is a no-op in the target.** Reference checks `getUpdateSources().contains(UsersUpdate)` / `contains(MeUpdate)` only (StoreUser.java:425,428). Target adds `|| this in updateSources` (StoreUser.kt:142-143) — and target `StoreV2.markChanged(vararg)` always adds `this` (StoreV2.kt:18-21, matching reference StoreV2), so **both branches always execute**, forcing a full users copy and a `meCache.set` on every single dispatch. **MAJOR** (persistence + copy churn).
9. **EXTRA: `reset()`** (StoreUser.kt:132-139) has no reference counterpart — the reference clears me only via `handleAuthToken(null)` (StoreUser.java:152). Reference also **never clears `users`** on logout. EXTRA (arguably a fix); verify no consumer depends on user cache surviving logout.
10. **EXTRA (target-only):** `getUsers(Collection, fetchMissing)` default arg, `observeUsers` `distinctUntilChanged` (reference relies on `o.a(collection)` transformer), `putUser`, `mergeCore`/`mergeMe`/`merge` helpers.

### EXTRA / DIVERGENT
- Target ctor param order `(notifyUserUpdated, dispatcher, observationDeck, meCache, requestUsers)` vs reference `(notifyUserUpdated, dispatcher, observationDeck, meCache)` — extra `requestUsers` param replaces `UserRequestManager`.
- Reference `handleAuthToken` marks changed **before** assigning (StoreUser.java:154-155) — irrelevant ordering.

---

## 2. `stores/StoreUserProfile.kt` vs `stores/StoreUserProfile.java` — SEVERITY: **MAJOR**

### MISSING FIELDS
| Reference (StoreUserProfile.java) | Target (StoreUserProfile.kt) |
|---|---|
| `StoreStream storeStream` (:32, :110) | **MISSING** — replaced by `propagateMember: ((GuildMember, Long?) -> Unit)?` (:20) |
| `RestAPI restAPI` (:31) | replaced by `fetchProfile` lambda (:13-19) |
| `HashMap<Long, UserProfile> profiles` (:28) | present (:22) |
| `HashSet<Long> profilesLoading` (:29) | present (:23) |
| `Map<Long, UserProfile> profilesSnapshot` (:30) | present (:25) |
| — | **EXTRA**: `profileErrors: HashSet<Long>` (:24), `profilesLoadingSnapshot` (:26), `profileErrorsSnapshot` (:27) |

`EMPTY_PROFILE` reference is `new UserProfile(null×7, Opcodes.LAND=141)` (:25) — 141 = 0b10001101 → default params 0,2,3,7. Target uses `UserProfile()` (:108). Equivalent **only if** the target `UserProfile` data class has the same defaults for the non-defaulted slots (1,4,5,6). **Verify** — potential silent mismatch.

### MISSING METHODS / BEHAVIOR
1. **BLOCKER-adjacent: `handleUserProfile` does not propagate to `StoreStream`.** Reference `fetchProfile$1$1$1.invoke2` calls `storeStream.handleUserProfile(userProfile, guildId)` (`StoreUserProfile$fetchProfile$1.java`), which fans the profile out to StoreUser/StoreGuilds/etc. and is what actually clears `profilesLoading` via `StoreUserProfile.handleUserProfile`. Target replaces this with `handleUserProfile(profile)` + an optional `propagateMember` callback (StoreUserProfile.kt:56-58) that only forwards `profile.guildMember`. Any other fan-out the reference `StoreStream.handleUserProfile` performs (user update, mutual guilds, connected accounts, premium since, …) is **MISSING**. **MAJOR** — must be cross-checked against `StoreStream.handleUserProfile` in the reference.
2. **`handleUser` field-copy diverges.** Reference builds `new UserProfile(b(), d(), user, f(), e(), null, a())` (StoreUserProfile.java:66) — note the **6th positional arg is forced to `null`** and there is no 8th arg (the 8-arg ctor's last slot is dropped). Target does `profile.copy(user = user, guildMember = null)` (StoreUserProfile.kt:103). Whether "6th positional" == `guildMember` depends on the target's `UserProfile` field order — **verify positionally**; if the reference's nulled field is not `guildMember`, this is a silent data bug. **MAJOR (unverified)**.
3. **`handleFailure` diverges.** Reference only does `profilesLoading.remove(userId)` — **no `markChanged()`**, no error state (StoreUserProfile.java:56-58). Target removes from loading, adds to `profileErrors`, and `markChanged()` (StoreUserProfile.kt:92-97). EXTRA/DIVERGENT (target is a behavior improvement; error-state observers do not exist in 126.21).
4. **`fetchProfile` guard + markChanged diverges.** Reference: `if (profilesLoading.contains(userId)) return; profilesLoading.add(userId)` then fires REST — **no `markChanged()`** (`StoreUserProfile$fetchProfile$1.invoke2`). Target adds `markChanged()` and clears `profileErrors` (StoreUserProfile.kt:47-49). EXTRA.
5. **Reference does `.r()` (distinctUntilChanged) on the REST observable before subscribing** (`restAPI.userProfileGet(...).r()`). Target's lambda does not. MINOR.
6. **MISSING: `handlePreLogout` has no reference counterpart** — reference `StoreUserProfile` has **no** logout/reset path at all; profiles survive logout. Target adds `handlePreLogout` (StoreUserProfile.kt:77-83). EXTRA (fix).
7. **EXTRA target API with no reference equivalent:** `getUserProfiles()` (:30), `getProfilesLoading()` (:31), `observeProfileError(userId)` (:32), `observeUserProfiles()` (:38). Reference exposes only `getUserProfile(long)` and `observeUserProfile(long)`.
8. **MISSING: `super.snapshotData()`** — reference calls it (StoreUserProfile.java:98); target does not (StoreUserProfile.kt:85). Currently a no-op in `StoreV2`, so cosmetic. MINOR.

---

## 3. `stores/StoreUserNotes.kt` vs `stores/StoreUserNotes.java` — SEVERITY: **MAJOR**

### MISSING FIELDS
- Reference `RestAPI restAPI` (StoreUserNotes.java:30) → target `loadNote` / `saveNote` lambdas (StoreUserNotes.kt:11-12). Acceptable injection.
- `notesByUserId: Map<Long, UserNoteState>` (ref :27) / snapshot (ref :28) — present in target (:20-21). Match.
- Sealed hierarchy `UserNoteState {Empty, Loading, Loaded(note)}` — matches (ref :35-121, target :14-18).

### MISSING METHODS / BEHAVIOR
1. **BLOCKER: `observeUserNote` default state is wrong.** Reference `StoreUserNotes$observeUserNote$1.invoke` returns `UserNoteState.**Loading**.INSTANCE` when the map has no entry. Target returns `UserNoteState.**Empty**` (StoreUserNotes.kt:26). Every profile sheet opens showing "no note" instead of a loading state before the fetch resolves. **BLOCKER** (user-visible).
2. **`saveNote` signature drops `Context`.** Reference `saveNote(Context context, long userId, String note)` (StoreUserNotes.java:182) passes `context` into `appSubscribe` so REST failures surface as a UI error toast. Target `saveNote(userId, note)` (StoreUserNotes.kt:40) has an **empty failure handler** (`{}`) — save failures are silently swallowed. **MAJOR**.
3. **`saveNote` request body diverges.** Reference sends `RestAPIParams.UserNoteUpdate(note)` verbatim. Target's StoreStream wiring sends `note.takeUnless(String::isBlank)` (StoreStream.kt:207-209) i.e. `null` for a blank note. May be intentional (delete-note), but it is **not** what 126.21 does. **MINOR / verify**.
4. **`saveNote` optimistic update ordering.** Reference `StoreUserNotes$saveNote$1` runs on success and (per the constructor args) calls `updateNoteInternal` inside a dispatcher schedule — target matches (StoreUserNotes.kt:44).
5. **MISSING: no `handlePreLogout` in reference.** Reference clears notes only on `handleConnectionOpen` (StoreUserNotes.java:161-164). Target adds `handlePreLogout` (StoreUserNotes.kt:53). EXTRA (fix).
6. **MISSING: `super.snapshotData()`** (ref :189) not called in target (:58). Cosmetic. MINOR.
7. `handleNoteUpdate(ModelUserNote.Update)`, `handleRequestUserNoteSuccess`, `handleRequestUserNoteError`, `updateNoteInternal` blank-check — all match.

---

## 4. `stores/StoreUserRelationships.kt` vs `stores/StoreUserRelationships.java` (+`Kt`) — SEVERITY: **MINOR**

### FIELDS — full parity
`observationDeck`, `relationshipsCache: Persister<Map<Long,Int>>` keyed `"STORE_USER_RELATIONSHIPS_V9"` (ref :263 / target :12,81), `relationshipsState`, `relationshipsStateSnapshot`. `UNLOADED_RELATIONSHIPS_SENTINEL = hashMapOf(-1L to -1)` (`StoreUserRelationshipsKt.java`) matches target `UNLOADED_SENTINEL` (StoreUserRelationships.kt:82). Sealed `UserRelationshipsState {Unloaded, Loaded(relationships)}` matches.

### MISSING METHODS / BEHAVIOR
1. **MISSING: `ensureRelationshipLoaded()` side-effect.** Reference `ensureRelationshipLoaded` (StoreUserRelationships.java:120-131) **unconditionally assigns** `relationshipsState = Loaded(...)` — so a `handleRelationshipRemove` while `Unloaded` transitions the store to `Loaded(emptyMap)` **without** `markChanged()`. Target's `update{}` (StoreUserRelationships.kt:71-78) returns early and leaves `Unloaded`. Observable difference: after a stray REMOVE pre-READY, reference's `getRelationshipsState()` becomes `Loaded` on the next snapshot; target stays `Unloaded`. **MINOR** (bug-parity).
2. **`observe(userId)` adds `distinctUntilChanged` in target.** Reference `observe(long)` deliberately does **not** call `.r()` (StoreUserRelationships.java:290-304) — it re-emits on every relationships change. Target adds `.distinctUntilChanged()` (StoreUserRelationships.kt:53). MINOR (target is better; changes emission counts).
3. **`init()` thread annotation diverges.** Reference `init()` is **public, un-annotated** and calls `markChanged()` (which asserts store thread at runtime) (StoreUserRelationships.java:193-201). Target annotates `@StoreThread` + asserts (StoreUserRelationships.kt:22-24). MINOR.
4. **`init()` copies in target, aliases in reference.** Reference stores the Persister's map instance directly (`new Loaded(map)`); target wraps in `LinkedHashMap(it)` (:26). MINOR (target safer).
5. **`snapshotData` diverges on the Loaded copy type.** Reference snapshots into a **`HashMap`** and persists that (StoreUserRelationships.java:245-247); target uses `LinkedHashMap` (:65). Iteration-order-sensitive consumers would differ. MINOR.
6. **MISSING: `super.snapshotData()`** (ref :242) not called in target (:63). Cosmetic.
7. **EXTRA (target-only):** none of consequence — `handlePreLogout`, `handleConnectionOpen`, `handleRelationshipAdd/Remove`, `observe()`, `observe(Collection)`, `observeForType(Int)`, `observeUserRelationshipsState()`, `getRelationships()`, `getRelationshipsState()` all present and semantically matching.

---

## 5. `stores/StoreUserPresence.kt` vs `stores/StoreUserPresence.java` (+`Kt`) — SEVERITY: **BLOCKER**

### MISSING FIELDS
| Reference (StoreUserPresence.java) | Target (StoreUserPresence.kt) |
|---|---|
| `SnowflakePartitionMap.CopiablePartitionMap<Presence> presences` (:55, :145) | `LinkedHashMap<Long, Presence>` (:30) — loses `fastCopy()`; full copy each dispatch (:156). **MAJOR** perf. |
| `HashMap<Long, Map<Long, TimestampedPresence>> userGuildPresences` (:58) | present (:31). Match. |
| `StoreStream stream` — **required, non-null** (:57, ctor :138-144) | `StoreStream? = null` (:24) + `publishLocalPresence` / `resolveCustomEmoji` lambdas (:25-26). DIVERGENT. |
| `Clock clock`, `ObservationDeck observationDeck`, `MeUser meUser`, `localPresence`, `localPresenceSnapshot`, `presencesSnapshot` | all present. Match. |
| `ME_GUILD_ID = Long.MAX_VALUE` in `StoreUserPresenceKt` (file-private) | target companion `const val ME_GUILD_ID` (:235). Match. |
| `LocalPresenceUpdateSource` (:48, private static, no Companion accessor) | `@JvmField val LocalPresenceUpdateSource` (:236) — public. EXTRA visibility. |
| — | **EXTRA:** `TimestampedPresence` is a public nested data class in both. Match. |

### MISSING METHODS / BEHAVIOR
1. **BLOCKER: custom-status emoji resolution is stubbed.** Reference `getCustomStatusActivityFromSetting` (StoreUserPresence.java:296-322):
   - `emojiId != null` → `stream.getEmojis().getCustomEmojiInternal(id)` → `ActivityEmoji(id.toString(), customEmoji.name, customEmoji.isAnimated)`; **null if the emoji is not in StoreEmoji**.
   - `emojiName != null` → `stream.getEmojis().unicodeEmojiSurrogateMap[emojiName]` → `ActivityEmoji(null, unicode.surrogates, false)`; **null if not found**.
   Target (`StoreUserPresence.kt:207-232`) default `resolveCustomEmoji` returns `ActivityEmoji(id.toString(), null, false)` (no name, never animated) and falls back to `ActivityEmoji(null, emojiName, false)` **without** the surrogate-map lookup. The `StoreEmoji` store dependency is entirely absent. **BLOCKER** — custom statuses render wrong emoji.
2. **BLOCKER: custom-status expiry check is wrong.** Reference: `expiresAt == null || TimeUtils.parseUTCDate(expiresAt) - clock.currentTimeMillis() > 0` (StoreUserPresence.java:301). Target: `setting.expiresAt?.let { ModelMuteConfig(it).endTimeMs ?: 0L }?.let { it <= clock.currentTimeMillis() } == true` (StoreUserPresence.kt:209) — abuses `ModelMuteConfig` as a date parser. `TimeUtils.parseUTCDate` is **MISSING** from this path. **BLOCKER**.
3. **MAJOR: `snapshotData` gateway publish is indirected and lossy.** Reference (StoreUserPresence.java:745-747): `StoreGatewayConnection.presenceUpdate$default(StoreStream.Companion.getGatewaySocket(), localPresence.status, null, localPresence.activities, null, 10, null)` — 5 args (status, since, activities, afk) with defaults. Target calls `publishLocalPresence(localPresence)` (:158) wired to `gateway?.presenceUpdate(presence.status, activities = presence.activities.orEmpty())` (StoreStream.kt:184-185) — **`activities` null is collapsed to an empty list**, and `since`/`afk` defaults must be verified. Also, the reference publishes even when `gateway` is unset (it goes through the companion); target no-ops if `gateway == null`. **MAJOR**.
4. **MAJOR: `handleGuildMemberRemove` parameter order is swapped.** Reference `handleGuildMemberRemove(long j /*guildId*/, long j2 /*userId*/)` — body is `userGuildPresences.get(j2).remove(j)` then `flattenPresence(j2)` (StoreUserPresence.java:570-575), i.e. **(guildId, userId)**. Target is `handleGuildMemberRemove(userId, guildId)` (StoreUserPresence.kt:65). Target's own StoreStream call site is consistent (`presences.handleGuildMemberRemove(it.user.id, it.guildId)`, StoreStream.kt:588) but it is the **opposite** of `guilds.handleGuildMemberRemove(guildId, userId)` on the adjacent lines 586-587 — a live footgun. **MAJOR**.
5. **MAJOR: thread handlers lose the member→user association.** Reference `handleThreadMemberListUpdate` uses `threadMemberListUpdate.a()` (guildId) and **`threadListMember.c()`** (the member's userId) — *not* `presence.user.id` (StoreUserPresence.java:618-629); same for `handleThreadMembersUpdate` with `augmentedThreadMember.g()` (StoreUserPresence.java:632-643). Target takes `(guildId, List<ApiPresence>)` and resolves the user from `presence.user?.id ?: presence.userId` (StoreUserPresence.kt:83-86,103-106) — presences in thread payloads frequently omit `user`, so those updates are **dropped**. **MAJOR**.
6. **DIVERGENT: `updateActivity` removes by the wrong key.** Reference: if `activity != null` → `replaceActivityInList(activity, ...)` which removes by **`activity.type`**, else `removeActivityInList(type, ...)` (StoreUserPresence.java:786-790). Target always filters by the **`type` parameter** (StoreUserPresence.kt:114). Differs whenever `type != activity.type`. Also reference removes only the **first** match (`indexOf`-style, StoreUserPresence.java:324-343); target removes **all** matches. **MINOR**.
7. **DIVERGENT: `updateSelfPresence` markUnchanged condition.** Reference: `if (!markLocal && PresenceUtils.getCustomStatusActivity(localPresence) == null) markUnchanged(LocalPresenceUpdateSource)` — it inspects the **resulting local presence** (StoreUserPresence.java:467-473). Target: `if (!markLocal && settings?.customStatus == null) markUnchanged(...)` (StoreUserPresence.kt:204) — inspects the **incoming settings**. These differ when settings carry a custom status that resolves to `null` (expired/unknown emoji) and vice-versa. **MAJOR**.
8. **DIVERGENT: `updateSelfPresence` custom-status branch.** Reference (StoreUserPresence.java:427-442) applies replace/remove **only when `settings.customStatus != null`**, else keeps `localPresence.activities` — target's `?:` chain (StoreUserPresence.kt:192-198) reproduces this, but note the target's `+ custom` **appends** whereas reference's `replaceActivityInList` removes-then-appends — equivalent given the preceding filter. OK.
9. **`flattenPresence` change-detection diverges.** Reference (raw block `L9e`–`Lca`) compares the existing presence against `status`, the sorted activities, and the **raw `clientStatuses` (possibly `null`)** while *storing* the `OFFLINE,OFFLINE,OFFLINE` fallback — so a presence with null clientStatuses re-`put`s and `markChanged()`s on **every** update. Target compares the already-defaulted `flattened` object (StoreUserPresence.kt:181), suppressing those. **MINOR** (target better; changes emission counts).
10. **`flattenPresence` sort:** reference uses `PresenceUtils.ACTIVITY_COMPARATOR` = `compareBy(type, ActivityUtilsKt.isRichPresence, createdAt)` then `.reversed()`. Target's inline comparator (StoreUserPresence.kt:177-179) + `isRichPresence` (:243-246) **matches** `ActivityUtilsKt.isRichPresence` (`!isCustomStatus && (assets != null || party != null || state non-empty || details non-empty)`). PARITY OK.
11. **EXTRA: `handleGuildAdd` / `handlePresenceReplace` userId fallback.** Reference resolves the user strictly from `presence.f()` and **skips** the presence if null (StoreUserPresence.java:556-567, 584-600). Target adds `?: presence.userId` (StoreUserPresence.kt:61, 73). EXTRA (harmless/better).
12. **EXTRA: `reset()`** (StoreUserPresence.kt:142-153) — reference has **no** reset/logout path for presence. EXTRA.
13. **MISSING: `getPresences()` returning the live partition map** (`StoreUserPresence.java:522`) and `getStream()`/`getObservationDeck()` public accessors — target has `getPresencesInternal()` (:42) and a public `observationDeck`/`stream` (:23-24). Equivalent.
14. **MISSING: `handleConnectionOpen` uses `RecyclerView.FOREVER_NS` (== `Long.MAX_VALUE`)** for the ME guild — matches target's `ME_GUILD_ID`. OK.
15. `getApplicationActivity`, `observeAllPresences`, `observePresenceForUser`, `observePresencesForUsers`, `observeApplicationActivity`, `observeLocalPresence`, `handleSessionsReplace`, `handleUserSettingsUpdate`, `handleGuildRemove`, `clearPresences` — present and matching.

---

## 6. `stores/StoreUsersTyping.kt` vs `stores/StoreUserTyping.java` — SEVERITY: **BLOCKER**

Class is **renamed** (`StoreUserTyping` → `StoreUsersTyping`). MINOR but breaks name-based parity.

### MISSING FIELDS
| Reference (StoreUserTyping.java) | Target (StoreUsersTyping.kt) |
|---|---|
| `RestAPI restAPI` (:35) | **MISSING** entirely |
| `StoreStream stream` (:36) | replaced by `currentUserId: () -> Long` (:20) |
| `HashMap<Long, Set<Long>> typingUsers` (:37) | present (:24) |
| `HashMap<Long, Map<Long, Subscription>> typingUsersRemoveCallbacks` (:38) | `HashMap<Long, MutableMap<Long, TypingCancellable>>` (:25). Equivalent. |
| `typingUsersSnapshot` (:39) | present (:26) |
| — | **EXTRA:** `standalone: Boolean` (:22) + `TypingScheduler`/`TypingCancellable` fun-interfaces (:11-15) + a static `Executors.newSingleThreadScheduledExecutor` (:105) — reference uses `Observable.just(x).delay(10, SECONDS)` on the Rx computation scheduler (StoreUserTyping.java:98). DIVERGENT threading model. |

### MISSING METHODS / BEHAVIOR
1. **BLOCKER: `setUserTyping(channelId)` is entirely MISSING.** Reference (StoreUserTyping.java:158-163) guards `channelId == 0L`, then `restAPI.setUserTyping(channelId, EmptyBody())` under the `"typingEvent"` tag. Without it the local user **never sends typing indicators**. There is no equivalent anywhere in the target. **BLOCKER**.
2. **MISSING: `handleTypingStop(TypingUser)`** (StoreUserTyping.java:52-60). Private in the reference and only reached from the 10s delay, so the target's inline removal (StoreUsersTyping.kt:44) is functionally equivalent — but note the reference removes from `typingUsers` **without** removing the callback entry from `typingUsersRemoveCallbacks`, leaking a stale `Subscription` map entry. Target cleans up (:43). EXTRA (fix). MINOR.
3. **`handleMessageCreate` signature + behavior diverge.** Reference `handleMessageCreate(api.Message)` reads `message.e()` (author) and `message.g()` (channelId) (StoreUserTyping.java:63-76) and does **not** cancel the pending removal subscription. Target `handleMessageCreate(channelId: Long, userId: Long?)` (StoreUsersTyping.kt:55) cancels the task first. **MINOR** (signature MAJOR for call-site parity).
4. **`observeTypingUsers` / `observeTypingUsersForChannels` shape.** Reference filters *after* `connectRx` via `.map{}` then `.r()` (StoreUserTyping.java:114-156); target does the filter **inside** the `connectRx` producer (StoreUsersTyping.kt:65,68). Semantically equivalent; the reference re-evaluates the whole snapshot map on every deck notification while the target evaluates only the requested slice. MINOR.
5. **MISSING: `super.snapshotData()`** (ref :168) not called in target (:84). Cosmetic.
6. **EXTRA: `clear()` / `reset()`** (StoreUsersTyping.kt:71,82) — reference has **no** typing reset on logout/reconnect; typing state persists. EXTRA (fix). Called from StoreStream.kt:1417,1476.
7. **EXTRA: `standalone` mode + `publishStandalone()` + the `constructor(clock: Clock)` shim** (StoreUsersTyping.kt:29-31, 96-101) — this manually re-runs `snapshotData()`/`onDispatchEnded()`/`observationDeck.notify()` outside the dispatch loop, i.e. **bypasses the dispatcher confinement contract**. No reference analogue. Flagged as a temporary hack in the code comment; it must be removed when StoreStream wiring is finished. **MAJOR** (architecture).
8. `handleTypingStart` self-user skip (ref :81-85 via `stream.getUsers().getMeInternal().id`) → target `currentUserId()` (:36). Match. 10s timeout matches.

---

## 7. `stores/StoreUserAffinities.kt` vs `stores/StoreUserAffinities.java` — SEVERITY: **MINOR**

### FIELDS — parity
`dispatcher`, `observationDeck`, `affinities`, `affinitiesSnapshot`, both seeded to `ModelUserAffinities(emptyList(), emptyList())` (ref :84-85 / target :13-14,53). Match.

### DIVERGENCES
1. **Visibility drift:** reference `observeUserAffinities()` is **private** (StoreUserAffinities.java:47) and `handleUserAffinitiesFetchSuccess` is **private** (:42). Target makes both public (StoreUserAffinities.kt:17,32). MINOR.
2. **EXTRA: `getUserAffinities()`** (StoreUserAffinities.kt:16) — no reference equivalent.
3. **EXTRA: `handlePreLogout()`** (StoreUserAffinities.kt:39) — reference has **no** logout/reset for affinities. EXTRA (fix).
4. **`fetchUserAffinities` is inlined into `handleConnectionOpen`.** Reference keeps a private `fetchUserAffinities()` (StoreUserAffinities.java:36-38) using `RestAPI.Companion.getApi().getUserAffinities()` with a `StoreUserAffinities.class` log tag and **no error handler**. Target inlines it and passes an empty failure lambda (StoreUserAffinities.kt:23-29). Equivalent; the named method is MISSING. MINOR.
5. **MISSING: `super.snapshotData()`** (ref :75) not called (target :45). Cosmetic.
6. `observeAffinityUserIds()` and `snapshotData()` deep-copy semantics match exactly.

---

## 8. `stores/StoreFriendSuggestions.kt` vs `stores/StoreFriendSuggestions.java` (+`Kt`) — SEVERITY: **MAJOR**

`StoreFriendSuggestionsKt.java` is **empty** (no file-level members) — nothing to port.

### MISSING FIELDS
| Reference (StoreFriendSuggestions.java) | Target (StoreFriendSuggestions.kt) |
|---|---|
| `StoreStream storeStream` (:23) | **MISSING** (assigned but unread in the decompile; likely used only for construction ordering) |
| **`FriendSuggestionsFetcher friendSuggestionsFetcher`** (:22) **+ public getter `getFriendSuggestionsFetcher()`** (:48) | **MISSING** — replaced by a `fetchSuggestions` lambda (:16). The fetcher is **publicly exposed** in the reference and is re-invocable by UI (`WidgetFriendsList` style pull-to-refresh). **MAJOR**. |
| `Map<Long, FriendSuggestion> suggestions` (:24) / `suggestionsSnapshot` (:25) | present (:19,21) |
| — | **EXTRA:** `loading: Boolean` + `loadingSnapshot` (:20,22) |

`FriendSuggestionsFetcher` semantics that are **MISSING** from the target's lambda: it holds a `fetchSubscription` and **de-duplicates concurrent fetches** (`if (subscription == null || subscription.isUnsubscribed())`, `FriendSuggestionsFetcher.maybeFetch`). The target's `handleConnectionOpen` fires unconditionally when `friendSuggestionCount > 0` and `updateFriendSuggestions` has no such guard. **MAJOR**.

### MISSING METHODS / BEHAVIOR
1. **`handleConnectionOpen` diverges.** Reference: `suggestions.clear(); if (count > 0) fetcher.maybeFetch(); markChanged()` (StoreFriendSuggestions.java:53-60). Target additionally sets `loading = count > 0` and calls the fetch lambda (StoreFriendSuggestions.kt:30-39). EXTRA loading state.
2. **`handleFriendSuggestionCreate` hydration is EXTRA.** Reference does **not** touch StoreUser (StoreFriendSuggestions.java:63-70) — StoreStream separately dispatches `StoreUser.handleFriendSuggestionCreate`. Target calls `hydrateUsers(listOf(...))` from inside the store (StoreFriendSuggestions.kt:44), creating a **cross-store call on the store thread from within a handler**. Works, but inverts the reference's dispatch topology. **MINOR**.
3. **`handleFriendSuggestionsLoaded` keying diverges.** Reference keys by `friendSuggestion.b().getId()` — the **api user id** (StoreFriendSuggestions.java:92); target keys by `converted.user.id` (the `CoreUser` id, :70). Same value. OK.
4. **EXTRA (no reference equivalent):** `isLoading()` (:25), `observeFriendSuggestions()` (:26), `handlePreLogout()` (:80). The reference exposes only `getFriendSuggestions()` — consumers observe via the ObservationDeck store handle, not a dedicated `observe*`. EXTRA.
5. **`handleFriendSuggestionsLoadFailure`** matches (clear + markChanged); target also resets `loading`. OK.
6. **MISSING: `super.snapshotData()`** (ref :99). Cosmetic.
7. **MISSING: `convertApiFriendSuggestion` as a named private method** (ref :38-42) — target uses an extension `ApiFriendSuggestion.toModel()` (:92). Semantics identical (`CoreUser(suggestedUser)`, `reasons.firstOrNull()?.name`). OK.

---

## 9. `stores/StoreUserSettings.kt` vs `stores/StoreUserSettings.java` — SEVERITY: **BLOCKER**

### FIELDS — near-parity
All 6 subjects and all 8 Persisters match by key and default:
`RESTRICTED_GUILD_IDS`, `STORE_SETTINGS_FOLDERS_V1`, `STORE_SETTINGS_ALLOW_ANIMATED_EMOJIS`(true), `CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1`(0), `STORE_SETTINGS_ALLOW_GAME_STATUS`(true), `CACHE_KEY_STICKER_SUGGESTIONS`(true), `STORE_SETTINGS_ALLOW_ACCESSIBILITY_DETECTION`(false), `STORE_SETTINGS_AUTO_PLAY_GIFS`(true). `friendDiscoveryFlagsSubject` seeded to `0` in both. `contactSyncUpsellShown: Boolean?` and `expireCustomStatusSubscription` (target `customStatusExpiration`) present.

MISSING/EXTRA fields:
- Reference has **no** `customStatus` backing field and **no** `getCustomStatus()`. Target adds both (StoreUserSettings.kt:40,53). EXTRA.
- Target injects `SharedPreferences` (`preferences`, :23) instead of using `Store.getPrefs()`. DIVERGENT but acceptable.

### MISSING METHODS / BEHAVIOR — **the whole server-write half of this store is absent**
1. **BLOCKER: `Companion.updateUserSettings(AppActivity, RestAPIParams.UserSettings, Integer successStringResId)`** (StoreUserSettings.java:69-78) is **MISSING**. Every setting change in 126.21 is pushed to `PATCH /users/@me/settings` through this; the target has **no REST path at all**.
2. **BLOCKER: all `setIsXxx(AppActivity, boolean)` overloads lost their server-sync half.** Target's setters (StoreUserSettings.kt:125-150) write only local prefs/persisters. **MISSING** the `if (getIsSyncTextAndImagesEnabled() && appActivity != null) Companion.updateUserSettings(...)` tail on:
   - `setIsAnimatedEmojisEnabled` (ref :471-477 → `createWithAllowAnimatedEmojis`)
   - `setIsAttachmentMediaInline` (ref :479-487 → `createWithInlineAttachmentMedia`)
   - `setIsEmbedMediaInlined` (ref :515-523 → `createWithInlineEmbedMedia`)
   - `setIsRenderEmbedsEnabled` (ref :535-545 → `createWithRenderEmbeds`)
   - `setStickerAnimationSettings` (ref :584-590 → `createWithStickerAnimationSettings`)
   - `setIsShowCurrentGameEnabled` (ref :556-561 → `createWithShowCurrentGame`, **no sync-gate**)
   - `setIsDeveloperMode` (ref :506-513 → `createWithDeveloperMode` + `R.string.theme_updated` success toast)
   **BLOCKER**.
3. **BLOCKER: entirely MISSING public API:**
   - `observeConsents(): Observable<Consents>` with `onErrorReturn { Consents.DEFAULT }` (ref :305-314)
   - `setDefaultGuildsRestricted(AppActivity, boolean, Collection<Long>)` (ref :436-440)
   - `setExplicitContentFilter(AppActivity, int)` (ref :442-446)
   - `setFriendDiscoveryFlags(AppActivity, int)` (ref :448-452)
   - `setFriendSourceFlags(AppActivity, Boolean, Boolean, Boolean)` (ref :454-458)
   - `setRestrictedGuildId(AppActivity, long, boolean)` / `setRestrictedGuildIds(AppActivity, Collection<Long>, long, boolean)` (ref :567-582)
   - `updateContactSyncShown()` (ref :592-594)
   - `updateCustomStatus(ModelCustomStatusSetting): Observable<ModelUserSettings>` — uses `RestAPI.getApiSerializeNulls()` so a null status actually clears server-side (ref :596-598)
   - `expireCustomStatus()` (ref :108-110) — on expiry the reference **calls the API** to clear the status; the target only clears it locally (StoreUserSettings.kt:183-186). **BLOCKER**.
4. **MAJOR: `setIsAccessibilityDetectionAllowed` signature + behavior.** Reference returns `Observable<ModelUserSettings>` from `RestAPI.updateUserSettings(createWithAllowAccessibilityDetection(...))` and only calls `updateAllowAccessibilityDetectionInternal` **in the `doOnNext`** (StoreUserSettings.java:460-469). Target returns `Unit`, persists immediately, no REST (StoreUserSettings.kt:141-144).
5. **MAJOR: `updateAllowAccessibilityDetectionInternal` no longer persists.** Reference does `Persister.set(allowAccessibilityDetectionPublisher, z)` **then** `dispatcher.schedule{...}` (StoreUserSettings.java:209-212). Target only dispatches (StoreUserSettings.kt:167-169), so the persister is written only from `setIsAccessibilityDetectionAllowed`.
6. **MAJOR: `observeStickerAnimationSettings` reduced-motion mapping is wrong.** Reference (StoreUserSettings.java:413-425): override **only when `setting == 0` (ALWAYS_ANIMATE)** and then return **`1` (STICKER_ANIMATION_ANIMATE_ON_INTERACTION)**. Target (StoreUserSettings.kt:88-90) returns **`STICKER_ANIMATION_NEVER_ANIMATE` (2)** whenever reduced motion is on, regardless of the stored setting. Verified against `ModelUserSettings.java:21-23`. **MAJOR** (user-visible).
7. **MAJOR: `handleUserSettings` handles a field the reference does not, and misses none.** Reference (StoreUserSettings.java:112-181) routes through the **public setters** (`setIsEmbedMediaInlined(null, v)` etc.) so each also fires the REST sync when a non-null activity is supplied — with `null` activity it degenerates to local writes. Target writes prefs directly (StoreUserSettings.kt:102-109) — equivalent *today* only because the REST half is missing. Target additionally handles `settings.allowAccessibilityDetection` (:121) which the reference **does not**. EXTRA.
8. **`handleConnectionOpen` signature diverges.** Reference takes `ModelPayload` and unwraps `payload.userSettings` (StoreUserSettings.java:283-288) and is **not** `@StoreThread`; target takes `ModelUserSettings` and is `@StoreThread` (StoreUserSettings.kt:92-93). MINOR.
9. **MAJOR: observe* lost `ObservableExtensionsKt.computationLatest(...)`.** Reference wraps every subject/persister observable in `computationLatest` (backpressure-latest on the computation scheduler) before `.r()` — `observeCustomStatus`, `observeExplicitContentFilter`, `observeFriendDiscoveryFlags`, `observeFriendSourceFlags`, `observeGuildFolders`, `observeIsDefaultGuildsRestricted`, `observeIsRenderEmbedsEnabled`, `observeRestrictedGuildIds` (StoreUserSettings.java:316-408). The target uses bare `.distinctUntilChanged()` (StoreUserSettings.kt:68-85) — **emissions now stay on the producer thread**, which for the subjects is the store thread. **MAJOR** (threading contract).
10. **MISSING: `AnalyticsTracker.overlayToggled(enabled)`** inside `setIsMobileOverlayEnabled` (StoreUserSettings.java:530). Target's setter is a bare `putBoolean` (:131). **MINOR**.
11. **MISSING: `setDeveloperModeInternal`** as a distinct private (ref :204-206); folded into the target's `putBoolean`. Cosmetic.
12. **DIVERGENT: `updateLocalCustomStatus` is `synchronized` in the reference** (StoreUserSettings.java:214) — target's `updateCustomStatusLocal` is not (StoreUserSettings.kt:171). MINOR.
13. **DIVERGENT: the expiry timer.** Reference uses `TimeUtils.parseUTCDate(expiresAt)` and `ClockFactory.get().currentTimeMillis()` (ref :224); target hand-rolls a `SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")` and uses `System.currentTimeMillis()` (StoreUserSettings.kt:176-182) — **does not use the injected `Clock`, and the fixed format will fail to parse ISO-8601 offsets or non-millisecond precision** that `TimeUtils.parseUTCDate` tolerates. **MAJOR**.
14. **DIVERGENT: expiry guard.** Reference only arms the timer when `setting != CLEAR && expiresAt != null` (ref :221-223); target arms it whenever `expiresAt` parses (StoreUserSettings.kt:176). MINOR.
15. **EXTRA: `reset()`** (StoreUserSettings.kt:153-165) and `initForTesting()` (:47) — no reference equivalents. Reference `init` only pushes `shouldRenderEmbedsSubject` (ref :298-303); the target also seeds accessibility detection. EXTRA.
16. **EXTRA: `getIsAnimatedEmojisEnabled()`** (:55) and `getGuildFolders()` (:73) — not present in the reference.

---

## 10. GAP CHECK — reference stores with no target counterpart

Searched the entire target tree (`app/src/main/java/com/discord/**.kt`) for class names, behavior keywords, and dependency edges.

| Reference store | Target status | Evidence |
|---|---|---|
| **`StoreUserConnections.java`** (27.8 KB) | **MISSING — no trace.** `grep -ri UserConnections` over target `.kt` → **0 hits**. Reference holds `State {Loading, ConnectedAccounts(List<ConnectedAccount>)}`, `stateSnapshot`, `dispatcher`, `observationDeck`, `stream`, plus a full `List<ConnectedAccount>` delegating `State` subclass. No connected-accounts store, no `ConnectedAccount` fetch/refresh, in the target. `widgets/user/profile/UserProfileConnectionsView.kt` exists but is fed from `UserProfile` only. **BLOCKER for the connections UI.** |
| **`StoreUserSurvey.java`** (12.4 KB) | **MISSING — model only.** Target has `api/user/UserSurvey.kt` and `ModelPayload.userSurvey` (ModelPayload.kt:70,99,130,136,234) but **no store**. Reference store fields: `clock`, `dispatcher`, `observationDeck`, `restApi`, `sharedPreferences`, `storeGuilds`, `storePermissions`, `storeUser`, `userSurvey`; methods `handleConnectionOpen`, `fetchUserSurvey`, `handleUserSurveyFetchSuccess`, `meetsGuildRequirements`, `setLastFetched`, `getUserSurvey`, `observeUserSurvey`. All **MISSING**. **MAJOR.** |
| **`StoreSpotify.java`** (21.2 KB) | **MISSING.** Only hit is a stub `override fun getSpotifyToken(): String? = null` (StoreStream.kt:1608) plus unrelated `ActivityPlatform.SPOTIFY` / message-embed enums. Reference store: `clock`, `dispatcher`, `expireStateSub`, `publishStateTrigger: BehaviorSubject<Unit>`, `spotifyApiClient`, `spotifyState: SpotifyState(track, playing, position, start)`, `stream`; methods `publishState()`, `startStateExpiration(long)`. Spotify rich-presence publishing is entirely **MISSING**. **MAJOR.** |
| **`StoreGameParty.java`** (16.5 KB) | **MISSING — no trace.** `grep -ri GameParty` → **0 hits**. Reference: `parties: HashMap<String, HashSet<Long>>`, `partiesSnapshot`, `userParties: HashMap<Long, HashMap<Long, String>>`, deps `observationDeck`, `storeUser`, `storeUserPresence`; handlers `handleConnectionOpen`, `handleGuildCreateOrSync`, `handlePresenceUpdate(Presence)` / `(Presence, long)`, `handlePresenceReplace`, `handleThreadMemberListUpdate`, `handleThreadMembersUpdate`; API `observeUsersForPartyId(String)`; `snapshotData`. Party-join / "X others in party" UI is unsupported. **MAJOR.** |
| **`StoreContactSync.java`** (13.6 KB) | **MISSING — settings flag only.** Hits are `ModelUserSettings.contactSyncUpsellShown`, `StoreUserSettings.contactSyncUpsellShown`, a `DeepLinkParser` route, and `WidgetFriendsList` — **no store**. Reference: `clock`, `connectionsStore`, `contactsFetcher`, `dismissState: HashMap<String,Boolean>`, `dismissStateCache: Persister<Map<String,Boolean>>`, `dispatcher`, `experimentsStore`, `noticesStore`, `restAPI`, `uploadTimestampCache: Persister<Long>`, `userSettingsStore`, `usersStore`; methods `init`, `handleConnectionOpen`, `handlePostConnectionOpen`, `backgroundUploadContacts`, `createContactSyncNotice`, `clearDismissStates`, `dismissUpsell`, `dismissFriendsListUpsell`, `getFriendsListUpsellDismissed`, `setContactSyncUploadTimestamp`. All **MISSING**. Also depends on `StoreExperiments` and `StoreNotices`, neither of which exists in the target. **MAJOR.** |
| **`StoreMaskedLinks.java`** (10.6 KB) | **MISSING — no trace.** `grep -ri MaskedLinks` → **0 hits**. Reference: `userTrustedDomains: Set<String>` + snapshot, deps `dispatcher`, `storeChannelsSelected`, `storeUserRelationships`; `Companion.getDomainName(String)`, `Companion.isImplicitlyTrustedDomain(String)`, `init`, `isTrustedDomain(String, String)` / private `isTrustedDomain(String)`, `trustDomain(String)`, `snapshotData`. **Security-relevant**: without it, masked-link phishing warnings are absent entirely. **BLOCKER.** |

---

## SUMMARY TABLE

| # | Pair | BLOCKER | MAJOR | MINOR | Overall |
|---|---|---|---|---|---|
| 1 | StoreUser | 0 | 4 | 6 | MAJOR |
| 2 | StoreUserProfile | 0 | 3 | 4 | MAJOR |
| 3 | StoreUserNotes | 1 | 1 | 4 | BLOCKER |
| 4 | StoreUserRelationships | 0 | 0 | 6 | MINOR |
| 5 | StoreUserPresence | 2 | 6 | 5 | BLOCKER |
| 6 | StoreUsersTyping | 1 | 2 | 5 | BLOCKER |
| 7 | StoreUserAffinities | 0 | 0 | 5 | MINOR |
| 8 | StoreFriendSuggestions | 0 | 2 | 4 | MAJOR |
| 9 | StoreUserSettings | 3 | 6 | 6 | BLOCKER |
| 10 | Gap check (6 stores) | 2 | 4 | 0 | BLOCKER |
| | **TOTAL** | **9** | **28** | **45** | |
