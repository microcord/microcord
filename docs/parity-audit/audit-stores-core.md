# Parity audit — core runtime/infrastructure stores

TARGET  = `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE = `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (JADX, Discord Android 126.21)

Read-only audit. No target files were modified.

---

## 1. `stores/Dispatcher.kt` vs `stores/Dispatcher.java` (+ `DispatchHandler.java`)

Reference fields: `scheduler`, `assertCleanDispatches`, `dispatchHandlers: ArrayList<DispatchHandler>`, `dispatcherThread: Thread?`.
Target has all four (`Dispatcher.kt:7-11`). Method set matches 1:1
(`schedule`, `registerDispatchHandlers`, `unregisterDispatchHandlers`, `onDispatchEnded`, private `assertCleanDispatch` inlined into `schedule`).

### MISSING FIELDS
- none.

### MISSING METHODS/BEHAVIOR
- **MINOR** — Reference logs via `AppLog.g` / `Logger.e$default` (`Dispatcher.schedule`, `Dispatcher.assertCleanDispatch`); target uses `android.util.Log.e/w` (`Dispatcher.kt:15,18`). Loses the app crash-reporting breadcrumb path. Cosmetic w.r.t. store semantics.
- **MINOR** — Reference `assertCleanDispatch()` is a separate private method; target inlines the `Thread.currentThread() === dispatcherThread` test into `schedule` (`Dispatcher.kt:17`). Behaviourally identical.

### EXTRA/DIVERGENT
- **MINOR** — `Dispatcher.kt:22` calls `StoreThreadScheduler.bindCurrentThread()` on *every* dispatch. Reference records the store thread id once, inside the `ThreadFactory` in `StoreStream.<init>` (`StoreStream.java:869-876`, `Companion.setSTORE_THREAD_ID`). Target's per-dispatch rebinding is a design substitution, not a semantic loss, but it silently makes *any* scheduler (including an immediate/test scheduler) the "store thread", which weakens the confinement assertion.

### `DispatchHandler`
- Reference is a standalone interface `DispatchHandler.java` with `void onDispatchEnded()`. Target declares it in `Store.kt:7-9` as `fun interface DispatchHandler`. Equivalent. No gap.

**Severity totals — pair 1: BLOCKER 0, MAJOR 0, MINOR 3.**

---

## 2. `stores/Store.kt` vs `stores/Store.java`

Reference `Store` (open class): `getPrefs()`, `getPrefsSessionDurable()` (both `public final`, delegating to `SharedPreferencesProvider.INSTANCE`), `public void init(Context)`.

### MISSING FIELDS
- none.

### MISSING METHODS/BEHAVIOR
- none.

### EXTRA/DIVERGENT
- **MINOR** — Visibility narrowing: reference `getPrefs()` / `getPrefsSessionDurable()` are **public**; target `Store.kt:17,20` declares them `protected`. Reference code outside the store hierarchy can call `store.getPrefs()`; target cannot. No current target caller depends on it.
- **MINOR** — `Store.kt:11-14` declares the `@StoreThread` annotation with `@Target(AnnotationTarget.FUNCTION)` only. Reference `StoreThread` is applied to methods only in practice, so equivalent; but the annotation lives in its own file in the reference (`stores/StoreThread.java`) — file-layout divergence only.

**Severity totals — pair 2: BLOCKER 0, MAJOR 0, MINOR 2.**

---

## 3. `stores/StoreV2.kt` vs `stores/StoreV2.java`

Reference: `abstract class StoreV2 extends Store implements DispatchHandler, ObservationDeck.UpdateSource`, field `Set<UpdateSource> updateSources = HashSet()`, methods `assertStoreThread()` (private), `getUpdateSources()`, `markChanged()`, `markChanged(vararg)`, `markUnchanged(UpdateSource)`, `onDispatchEnded()`, `snapshotData()`, nested `MarkChangedDelegate<T>(value, overrideUpdateSource)`.

Target `StoreV2.kt` mirrors all of these.

### MISSING FIELDS
- none.

### MISSING METHODS/BEHAVIOR
- none missing structurally.

### EXTRA/DIVERGENT
- **MAJOR** — **Assertion semantics inverted from log-only to throwing.**
  Reference `StoreV2.assertStoreThread()` (`StoreV2.java:58-64`) compares `Thread.currentThread().getId()` against `StoreStream.Companion.getSTORE_THREAD_ID()` and, on mismatch, only calls `Logger.e(...)` — execution continues.
  Target `StoreV2.kt:12,22` calls `StoreThreadScheduler.assertCurrentThread()`, which is `check(...) { "Dispatch not run on store thread" }` (`StoreThreadScheduler.kt:27-29`) and **throws `IllegalStateException`**. Any off-thread `markChanged()` that the reference tolerates will crash the target. This changes shipped-phase failure modes.
- **MINOR** — Reference `markChanged()`, `markChanged(vararg)`, `markUnchanged()` are **public final**; target declares them `protected` (`StoreV2.kt:11,16,21`). Reference allows an outer coordinator to mark a foreign store changed; target forbids it.
- **MINOR** — `MarkChangedDelegate.setValue` in the target adds `StoreThreadScheduler.assertCurrentThread()` (`StoreV2.kt:42`). The reference delegate performs **no** thread assertion at all (`StoreV2.java:38-51`); the assertion happens only downstream in `markChanged()`. Given the throwing assert above, delegate writes off-thread now crash where the reference did not.
- **MINOR** — Reference `markChanged(vararg)` calls `assertStoreThread()` *then* `markChanged()` (double assert). Target calls `markChanged()` which asserts once. Equivalent.

**Severity totals — pair 3: BLOCKER 0, MAJOR 1, MINOR 3.**

---

## 4. `stores/StoreV2DispatchHandler.kt` vs `stores/StoreV2DispatchHandler.java`

Reference fields: `storesV2: List<StoreV2>`, `observationDeck: ObservationDeck` (default `ObservationDeckProvider.get()`).
`onDispatchEnded()` — accumulate `updateSources`, `snapshotData()`, `onDispatchEnded()` per dirty store, then `observationDeck.notify(set)` if non-empty.

Target `StoreV2DispatchHandler.kt:6-22` is a faithful, line-for-line equivalent, including the default `ObservationDeckProvider.get()` and the `@StoreThread` annotation.

### MISSING FIELDS / METHODS / BEHAVIOR
- none.

### EXTRA/DIVERGENT
- none.

**Severity totals — pair 4: BLOCKER 0, MAJOR 0, MINOR 0.** (Full parity.)

---

## 5. `stores/updates/ObservationDeck.kt` + `ObservationDeckProvider.kt`

### `ObservationDeck`

Reference constructor: `ObservationDeck(logger: Logger = AppLog.g, logLevel: LogLevel = LogLevel.NONE)` (`ObservationDeck.java:114-120`).
Reference fields: `logger: Logger`, `logLevel: LogLevel`, `observers: List<Observer>`, companion `LOG_CATEGORY = "ObservationDeck"`, `ON_UPDATE_EMPTY: Function0<Unit>`.

#### MISSING FIELDS
- **MAJOR** — `logger: Logger` constructor parameter/field. Target `ObservationDeck.kt:6-8` only takes `logLevel`. Everything the reference logs is unreachable.
- **MINOR** — companion `LOG_CATEGORY` constant — absent.
- **MINOR** — companion `ON_UPDATE_EMPTY` shared no-op. Target allocates a fresh `{}` lambda in `markStale()` (`ObservationDeck.kt:25`). Functionally equal.

#### MISSING METHODS/BEHAVIOR
- **MAJOR** — `private fun logBreadcrumb(String)` (`ObservationDeck.java:145-149`) — absent in target. Reference calls it in `connect(Observer,Boolean)`, `disconnect`, and at `notify` START/END/observer-removal. All VERBOSE-level observability is gone.
- **MAJOR** — `private fun logNotifyError(Throwable, Set<UpdateSource>)` (`ObservationDeck.java:151-180`) — absent. This is the method that reports which update sources and which observers were live when a notify crashed.
- **MINOR** — Reference `Observer.name` default: when `observerName == null` the anonymous observer sets `name = String.valueOf(hashCode())` (`ObservationDeck.java:200`). Target `ObservationDeck.kt:51` stores the nullable `observerName` verbatim, so `toDebugLogString()` prints `"Unknown"` instead of the hash. Divergent debug output.

#### EXTRA/DIVERGENT
- **MAJOR** — `notify()` error handling is **inverted / not present in the reference**. Reference `ObservationDeck.notify` (`ObservationDeck.java:291-319`) has **no** try/catch — an observer throwing propagates. Target `ObservationDeck.kt:95-108` wraps the loop in `try/catch` and *swallows* the throwable whenever `logLevel != NONE`, rethrowing only when `logLevel == NONE`. Since the default is `NONE` the production path happens to match, but the non-default path silently eats exceptions **and never calls `logNotifyError`**, which is presumably what the original Kotlin did. Behaviourally divergent under any non-default log level.
- **MINOR** — Reference `connectRx(vararg)` captures the observer into a `Ref$ObjectRef` and disconnects in `doOnUnsubscribe` (`.w(...)`); target uses `emitter.setCancellation { disconnect(observer) }` (`ObservationDeck.kt:68`). `setCancellation` fires on unsubscribe *and* terminal events; `doOnUnsubscribe` fires only on unsubscribe. Minor lifecycle difference.
- **MINOR** — Reference stale removal in `notify` does `observers.remove(i); i += -1;` then `i++` (net: re-examine same index). Target does `removeAt(index)` without advancing (`ObservationDeck.kt:100`). Equivalent.

### `ObservationDeckProvider`
Reference: object with `Lazy INSTANCE$delegate`, `public static ObservationDeck get()`.
Target `ObservationDeckProvider.kt:3-8`: `object` + `by lazy(::ObservationDeck)` + `@JvmStatic fun get()`. **Full parity, no gaps.**

**Severity totals — pair 5: BLOCKER 0, MAJOR 4, MINOR 6.**

---

## 6. `stores/StoreStream.kt` vs `stores/StoreStream.java`  ⚠ largest gap

Reference `StoreStream.java` is 3317 lines: **145 store fields** + `Dispatcher`, `Clock`, `Scheduler`, `ConnectionTimeStats`, `StoreAudioManagerV2.VideoUseDetector`, `BehaviorSubject<Boolean> initialized`, `List<StoreV2> storesV2` (**106 entries**), `StoreV2DispatchHandler`, static `STORE_THREAD_ID`, `isInitialized`, `collector$delegate`, `STORE_THREAD_NAME`, `LAST_LOGGED_NOTIFICATION_PERMISSIONS_KEY`, `DAY_IN_MILLS`.

Target `StoreStream.kt` is 86 KB but constructs only **36 stores**; `storesV2` (`StoreStream.kt:282-312`) has **29 entries** vs 106.

### MISSING FIELDS — stores present in reference `StoreStream` and absent from target (106)

`ArchivedThreadsStore`, `StoreAnalytics`, `StoreApplication`, `StoreApplicationAssets`, `StoreApplicationCommandFrecency`, `StoreApplicationCommands`, `StoreApplicationInteractions`, `StoreApplicationStreamPreviews`†, `StoreApplicationStreaming`, `StoreAudioManagerV2`, `StoreAudioManagerV2.VideoUseDetector`, `StoreAuditLog`, `StoreAutocomplete`, `StoreBans`, `StoreCalls`, `StoreCallsIncoming`, `StoreChangeLog`, `StoreChannelConversions`, `StoreChannelFollowerStats`, `StoreChat`, `StoreClientDataState`, `StoreClientVersion`, `StoreConnectionOpen`, `StoreConnectivity`, `StoreContactSync`, `StoreDirectories`, `StoreDynamicLink`, `StoreEmbeddedActivities`, `StoreEmoji`, `StoreEmojiCustom`, `StoreEmojiGuild`, `StoreEntitlements`, `StoreExperiments`, `StoreExpressionPickerNavigation`, `StoreExpressionSuggestions`, `StoreForumPostMessages`, `StoreForumPostReadStates`, `StoreGameParty`, `StoreGatewayConnection`, `StoreGifPicker`, `StoreGifting`, `StoreGooglePlayPurchases`, `StoreGooglePlaySkuDetails`, `StoreGuildBoost`, `StoreGuildIntegrations`, `StoreGuildJoinRequest`, `StoreGuildMemberCounts`, `StoreGuildMemberRequester`, `StoreGuildMemberVerificationForm`, `StoreGuildProfiles`, `StoreGuildRoleMemberCounts`, `StoreGuildRoleSubscriptions`, `StoreGuildScheduledEvents`, `StoreGuildStickers`, `StoreGuildTemplates`, `StoreGuildVoiceRegions`, `StoreGuildWelcomeScreens`, `StoreGuildsNsfw`, `StoreInstantInvites`, `StoreInviteSettings`, `StoreLibrary`, `StoreLocalActionComponentState`, `StoreLurking`, `StoreMFA`, `StoreMaskedLinks`, `StoreMediaEngine`, `StoreMediaFavorites`, `StoreMediaSettings`, `StoreMentions`, `StoreMessageAck`, `StoreMessageReactions`, `StoreMessageReplies`, `StoreNotices`, `StoreNotificationUpsells`, `StoreNotifications`†, `StoreNux`, `StoreOutboundPromotions`, `StorePaymentSources`, `StorePhone`, `StoreRequestedStageChannels`, `StoreReviewRequest`, `StoreRtcConnection`, `StoreRtcRegion`, `StoreSearch`, `StoreSlowMode`, `StoreSpotify`, `StoreStageChannelSelfPresence`, `StoreStageChannels`, `StoreStickers`, `StoreStreamRtcConnection`, `StoreSubscriptions`, `StoreThreadDraft`, `StoreThreadMembers`, `StoreThreadMessages`, `StoreThreadsActive`, `StoreThreadsActiveJoined`, `StoreThreadsJoined`, `StoreUserConnections`, `StoreUserRequiredActions`, `StoreUserSurvey`, `StoreVideoStreams`, `StoreVoiceChannelSelected`, `StoreVoiceParticipants`†, `StoreVoiceSpeaking`, `StoreVoiceStates`, plus non-store deps `ConnectionTimeStats`, `Clock` (present), `StoreDynamicLink`.

† `StoreNotifications.kt`, `StoreApplicationStreamPreviews.kt`, `StoreVoiceParticipants.kt` **exist as files** in the target `stores/` directory but are **never constructed or wired into `StoreStream`** (`grep` over `StoreStream.kt` returns no hits). They are dead code today.

Severity: the whole block is **BLOCKER** for any phase that claims those subsystems; **MAJOR** for the rest. See the "which of these are load-bearing for already-shipped phases" note below.

Also missing:
- **MAJOR** — `ConnectionTimeStats` field + `connectionTimeStats.addListener(rtcConnection/streamRtcConnection/mediaEngine)` wiring (`StoreStream.java:1105-1108`).
- **MINOR** — static `STORE_THREAD_ID` / `STORE_THREAD_NAME` / `setSTORE_THREAD_ID` (moved to `StoreThreadScheduler.kt` in the target — acceptable substitution).
- **MINOR** — static `isInitialized: Boolean` guard; target uses a nullable `instance` (`StoreStream.kt:1621`) — equivalent.
- **MINOR** — `LAST_LOGGED_NOTIFICATION_PERMISSIONS_KEY`, `DAY_IN_MILLS` constants (only used by `maybeLogNotificationPermissionStatus`, see below).
- **MINOR** — `collector$delegate` lazy singleton; target uses `@Volatile instance` — equivalent.

### MISSING METHODS/BEHAVIOR

**Init order.** Reference splits init into three phases; the target collapses them into one.
- `Companion.initialize(Application)` (`StoreStream.java:836-849`): `Rules.setEmojiDataProvider(emojis)` → `collector.init(app)` → install `RestAPI.AppHeadersProvider.{authTokenProvider, fingerprintProvider, localeProvider}` → `collector.deferredInit(app)` → `isInitialized = true`.
  - **MAJOR** — `Rules.setEmojiDataProvider(...)` absent (no `StoreEmoji` in target).
  - **MINOR** — target installs headers through a local `RequiredHeadersInterceptor.HeadersProvider` (`StoreStream.kt:1602-1609`) instead of the static `RestAPI.AppHeadersProvider` slots. Equivalent effect.
- `init(Application)` (`StoreStream.java:2369-2376`): `authentication.init` → `userSettingsSystem.init` → `emojis.initBlocking` → `userSettings.init` → `ChannelSelector.Companion.init(this, dispatcher, deck)` → `new StoreUIEventHandler(app, mediaEngine, channels, voiceChannelSelected, stageChannels, guildSettings)`.
  - **MAJOR** — `emojis.initBlocking(context)` missing.
  - **MAJOR** — `ChannelSelector.Companion.init(...)` missing.
  - **MAJOR** — `StoreUIEventHandler` construction missing.
  - **MINOR** — order differs: target `init()` (`StoreStream.kt:334-492`) runs `userSettingsSystem.init` first, then REST/gateway/coordinator wiring, then `authentication.init`, `accessibility.init`, `userSettings.init`. Reference `authentication.init` is first.
- **BLOCKER (missing method)** — `deferredInit(Application)` (`StoreStream.java:1113-1115` + `StoreStream$deferredInit$1`) has **no target equivalent**. Its body performs ~57 `store.init(context)` calls and 10 `dispatchSubscribe` wirings, of which the target reproduces only the subset for stores it owns, inside `init()` and an inline `dispatcher.schedule { … restore() … }` block (`StoreStream.kt:471-489`). Missing entirely from the target:
  `experiments.init`, `mediaEngine.init`, `gatewaySocket.init(ctx, networkMonitor)`, `navigation.init(ctx)`, `nux.init`, `guildsNsfw.init`, `mediaSettings.init` (×2), `messageAck.init`, `notifications.init`, `rtcConnection.init(ctx, networkMonitor)`, `voiceChannelSelected.init`, `voiceSpeaking.init`, `voiceParticipants.init`, `connectivity.init(networkMonitor)`, `clientVersion.init`, `analytics.init`, `notices.init`, `userConnections.init`, `changeLogStore.init`, `reviewRequestStore.init`, `spotify.init`, `streamRtcConnection.init(networkMonitor)`, `maskedLinks.init`, `rtcRegion.init`, `stickers.init`, `googlePlayPurchases.init`, `phone.init`, `applicationInteractions.init`, `stageSelfPresence.init`, `contactSync.init`, `audioManagerV2.init`, `outboundPromotions.init`, `guildsSorted.init` (called twice in the reference).
  - Also missing from `deferredInit`: `NetworkMonitor` construction, `startStoreInitializationTimer(networkMonitor)`, `initGatewaySocketListeners()`, the `d.d.a()` (backgrounded) subscription, and the `Func4` combine that feeds `initialized.onNext(...)`.
  - **MAJOR** — target sets `initialized.onNext(true)` **unconditionally at the end of `init()`** (`StoreStream.kt:490`) and again in `initForTesting` (`:517`). The reference computes `initialized` from a 4-way `Observable.combineLatest` inside `deferredInit` (`StoreStream$deferredInit$1`, Func4 block; JADX truncated the exact predicate — the exact combination of the four sources is **not readable** in the decompile, only that it is a `Func4` feeding `BehaviorSubject.onNext`). Target's semantics ("initialized as soon as construction finishes") therefore differ from the reference ("initialized once N readiness signals agree").
- **MAJOR (missing method)** — `dispatchSubscribe(Observable<T>, String, Function1<T,Unit>)` (`StoreStream.java:1118-1123`, applies `onBackpressureBuffer()` + names the subscription). Target uses bare `.subscribe { … }` (`StoreStream.kt:387,433,467`), losing backpressure buffering and the named-subscription diagnostics.
- **MAJOR (missing method)** — `initGatewaySocketListeners()` (`StoreStream.java:2380-2460`): **78** named `dispatchSubscribe` wirings off `StoreGatewayConnection`. Target replaces this with a `when(type)` string switch in `handleGatewayDispatch` (`StoreStream.kt:553-702`) covering **45** event names. Missing event routing (reference listener name → target): `guildApplicationCommands`, `streamGuildIntegrationUpdate`, `streamGuildJoinRequestCreateOrUpdate`, `streamGuildJoinRequestDelete`, `streamBanAdd`, `streamBanRemove`, `streamThreadMemberUpdate`, `streamThreadMembersUpdate`, `threadMemberListUpdate`, `streamVoiceStateUpdate`, `streamVoiceServerUpdate`, `streamGroupDMRecipientAdd`, `streamGroupDMRecipientRemove`, `streamCallDelete`, `streamCallCreateOrUpdate`, `streamEmojisUpdate`, `streamStickersUpdate`, `streamUserRequiredActionUpdate`, `streamUserPaymentSourcesUpdate`, `streamUserSubscriptionsUpdate`, `streamStreamCreate`, `streamStreamUpdate`, `streamStreamDelete`, `streamStreamServerUpdate`, `interactionCreate`, `interactionSuccess`, `interactionFailure`, `interactionModalCreate`, `applicationCommandAutocompleteResult`, `guildScheduledEventCreate/Update/Delete/UserAdd/UserRemove`, `embeddedActivityInboundUpdate`, `forumUnreads`. (Target *does* have `GUILD_MEMBER_LIST_UPDATE` and `CHANNEL_PINS_UPDATE`, which the reference routes as `guildMemberListUpdate` / via `StorePinnedMessages`.)
- **MAJOR (missing method)** — `handleHydrateError()` (`StoreStream.java:1991-1994`): `clientDataState.clear(); gatewaySocket.resetOnError();`. Target has **no error path at all** — `handleConnectionOpenInternal` (`StoreStream.kt:1484-1515`) assumes hydration always succeeds because `ReadyPayloadHydrator.hydrate` returns a `ModelPayload`, not a `HydrateResult`. See pair 9.
- **MAJOR (missing method)** — `handleConnected(boolean)` reference also drives `connectionOpen.handleConnected(z)` and `connectivity`; target `handleGatewayConnected` (`StoreStream.kt:544-549`) only routes to `channels`, `messages`, `messagesLoader`, `messageSendCoordinator`.
- **MAJOR (missing method)** — `handleConnectionReady(boolean)` (`StoreStream.java:1723-1734`) routes to 8 stores (`rtcConnection`, `calls`, `connectivity`, `guildSubscriptions`, `guildMemberRequesterStore`, `spotify`, `applicationCommands`, `applicationInteractions`). Target `handleGatewayConnectionReady` (`StoreStream.kt:550`) routes to **1** (`guildSubscriptions`).
- **MAJOR (missing methods)** — `handleBackgrounded(boolean)` (`:1531`), `handleFingerprint(String)` (`:1750`) — **no target equivalent** (`grep` for `ackgrounded|ingerprint` in `StoreStream.kt` finds only the header provider at `:1604`).
- **MAJOR (missing methods)** — `startStoreInitializationTimer(NetworkMonitor)` (`:2484`), `maybeLogNotificationPermissionStatus(Application, Clock)` (`:2462`) — absent.
- **MAJOR (missing methods)** — `processMessageUsers(Message)` (`:2473`) and `synthesizeGuildMemberAdd(...)` (`:2503`) — absent from target. The reference synthesises `GUILD_MEMBER_ADD` from message authors/mentions; target's `MESSAGE_CREATE` path does not.
- **MINOR** — `handleAuthState(AuthState)` exists in both (`StoreStream.java:1518` vs target private `handleAuthStateInternal`, `StoreStream.kt:1349`), but the reference version delegates only to `authentication.handleAuthState` while the target's does more (token/generation bookkeeping) — target superset, acceptable.
- **MINOR** — Reference exposes ~100 `Companion.getXxx()` static accessors + ~150 `getXxx$app_productionGoogleRelease()` internal accessors. Target exposes ~30 `@JvmStatic` accessors (`StoreStream.kt:1679+`). Direct consequence of the missing stores.

### `handleConnectionOpen` (READY) routing

Reference `handleConnectionOpen(ModelPayload)` (`StoreStream.java:1642-1718`) calls **69** store handlers after a successful hydrate, and wraps the whole thing in `TimeElapsed` + `AppLog.i("[StoreStream] Processed ready payload in …")`.
Target `handleConnectionOpenInternal` (`StoreStream.kt:1484-1515`) calls **17**.

Present in reference, absent in target: `userConnections`, `userRequiredAction`, `userSurvey`, `guildMemberCounts`, `guildJoinRequests`, `lurking`, `stageChannels`, `threadsActive`, `threadsJoined`, `threadsActiveJoined`, `voiceStates`, `applicationStreaming`, `customEmojis`, `voiceChannelSelected`, `mentions`, `rtcConnection`, `analytics`, `experiments`, `messages`, `messageAck`, `calls`, `mediaEngine`, `gameParty`, `guildMemberRequesterStore`, `reviewRequestStore`, `connectionOpen`, `messageReactions`, `spotify`, `changeLogStore`, `streamRtcConnection`, `rtcRegion`, `clientDataState`, `phone`, `threadMessages`, `archivedThreads`, `contactSync`, `guildStickers`, `outboundPromotions`, `lazyThreadMembersStore`, `connectionTimeStats`, `guildScheduledEvents`, `embeddedActivities`, `applicationCommands`, `applicationInteractions`, `forumPostMessages`, `forumPostReadStates`.
- **MAJOR** — reference calls `handleAuthState(AuthState.Companion.from(authToken, emptySet()))` **in addition to** `authentication.setAuthed(authToken)` (`StoreStream.java:1646-1649`); target only calls `authentication::setAuthed` (`StoreStream.kt:1489`).
- **MINOR** — `TimeElapsed` + ready-payload timing log absent (target uses `BootLog.record(CACHE_HYDRATION_*)` instead).
- **EXTRA/DIVERGENT, MINOR** — target has a *second*, non-reference overload `handleConnectionOpen(ModelUserSettings, List<Guild>)` (`StoreStream.kt:523-527`) with no counterpart in the reference.

### `handlePreLogout` / logout & reset

Reference `handlePreLogout()` (`StoreStream.java:2096-2117`) — **19** calls: `gatewaySocket`, `guildSelected`, `guildSubscriptions`, `authentication`, `gifting`, `spotify`, `paymentSources`, `subscriptions`, `userSettingsSystem`, `notifications`, `analytics`, `tabsNavigation`, `userRelationships`, `messages`, `emojis`, `stickers`, `pendingReplies`, `messageAck`, `applicationInteractions`.

Target `handlePreLogoutInternal()` (`StoreStream.kt:1395-1437`) — 30+ calls, but:
- **MAJOR** — missing `gatewaySocket.handlePreLogout()`, `gifting`, `spotify`, `paymentSources`, `subscriptions`, `notifications`, `analytics`, `emojis`, `stickers`, `messageAck`, `applicationInteractions` (all belong to stores the target does not own).
- **EXTRA/DIVERGENT, MAJOR** — the target's `handlePreLogoutInternal` also performs a **full store reset** (`users.reset()`, `guilds.reset()`, `channels.reset()`, `permissions.reset()`, `presences.reset()`, `readStates.reset()`, `navigation.reset()`, …, `StoreStream.kt:1421-1436`). The reference's `handlePreLogout` performs **no** reset — the reference relies on `Persister.reset()` + prefs clearing driven from `StoreAuthentication`/`WidgetSettings` paths, not from `StoreStream`. Target has conflated "pre-logout" with "reset", so a pre-logout signal in the target wipes caches earlier than the reference does.
- **EXTRA/DIVERGENT, MAJOR** — `resetForLogout()` (`StoreStream.kt:1444-1481`) **has no reference counterpart**. There is no `resetForLogout`, `reset`, or equivalent in `StoreStream.java` (verified by grep for `reset|Logout|clear()`). It calls `Persister.reset()`, `preferences.edit().clear().commit()`, `sessionDurablePreferences.edit().clear().commit()`, and then a second full reset pass. This is target-invented behaviour; it also duplicates ~25 of the calls already made by `handlePreLogoutInternal`.

### Init/construction order
- Reference `StoreStream()` constructs stores in a dependency-ordered sequence that ends with `dispatcher.registerDispatchHandlers(storeV2DispatchHandler)` and three `connectionTimeStats.addListener` calls (`StoreStream.java:1103-1108`).
- Target uses Kotlin property initialisers in declaration order (`StoreStream.kt:126-313`) plus an `init { dispatcher.registerDispatchHandlers(storeV2DispatchHandler) }` block (`:330-332`). Ordering is internally consistent; only the missing-store subset diverges.
- **MINOR** — `Dispatcher` is constructed with `assertCleanDispatches` defaulted to `false` in both (`StoreStream.kt:1668`, `StoreStream.java:881`). Match.

**Severity totals — pair 6: BLOCKER 1 (`deferredInit` absent) + 106 missing stores (BLOCKER for the subsystems they back), MAJOR 17, MINOR 9.**

---

## 7. `stores/StoreAccessibility.kt` vs `stores/StoreAccessibility.java`

Reference fields: `accessibilityFeatures: EnumSet`, `accessibilityFeaturesSnapshot: EnumSet`, `accessibilityMonitor`, `dispatcher`, `fontScaledDown`, `fontScaledUp`, `isDetectionAllowed`, `observationDeck`, `reducedMotionCache: Persister<Boolean>`, `reducedMotionEnabled`.
Target `StoreAccessibility.kt:17-23` has **all ten**. Constructor defaults match (`ObservationDeckProvider.get()` / `AccessibilityMonitor.INSTANCE`).

### MISSING FIELDS
- none.

### MISSING METHODS/BEHAVIOR
- **MAJOR** — `snapshotData()` in the reference (`StoreAccessibility.java:102`) calls
  `AnalyticSuperProperties.INSTANCE.setAccessibilityProperties(isScreenreaderEnabled(), getAccessibilityFeatures())`.
  Target `StoreAccessibility.kt:85-96` omits it. `com/discord/utilities/analytics/` does not exist in the target at all, so the analytics super-property is never published.
- **MINOR** — Reference `init()` subscribes via `ObservableExtensionsKt.appSubscribe(..., StoreAccessibility.class, ...)` (`StoreAccessibility.java:54`); target uses a raw `.subscribe {}` (`StoreAccessibility.kt:31`) — no error handler, no lifecycle tag, leaked subscription.

### EXTRA/DIVERGENT
- **MINOR** — `getAccessibilityFeatures()` reference returns the snapshot **by reference** (`StoreAccessibility.java:46-48`); target returns `accessibilityFeaturesSnapshot.clone()` (`StoreAccessibility.kt:25`). Safer, but a divergence (callers can no longer observe aliasing).
- **MINOR** — `snapshotData()` in the target *removes* `CHAT_FONT_SCALE_INCREASED` / `CHAT_FONT_SCALE_DECREASED` when the flag is false (`StoreAccessibility.kt:90-93`). The reference only ever **adds** them (`StoreAccessibility.java:94-99`); it never removes. If `AccessibilityState.features` ever carries one of those flags, the two implementations disagree.
- **MINOR** — Target adds `internal fun reset()` (`StoreAccessibility.kt:69-76`). No `reset()` exists on the reference `StoreAccessibility`. Called only from the target-invented `StoreStream.resetForLogout` (`StoreStream.kt:1449`).
- **MINOR** — Target adds `internal fun initForTesting()` (`:36-39`). No reference counterpart.
- **MINOR** — Target adds explicit `StoreThreadScheduler.assertCurrentThread()` at the top of `updateDetectionAllowed`, `updateFontScale`, `reset`, `updateMonitoredAccessibilityState` (`:55,62,70,80`). Reference relies on `markChanged()`'s internal (non-throwing) assert. See pair 3 — these now throw.
- **NOTE (behaviour equivalence confirmed)** — `updateFontScale`: reference branches on `scale == 100 || scale == -1` / `> 100` / `< 100`; target's `scale != -1 && scale > 100` / `scale != -1 && scale < 100` produces identical results for all inputs. Not a gap.

**Severity totals — pair 7: BLOCKER 0, MAJOR 1, MINOR 6.**

---

## 8. `stores/StoreUserSettingsSystem.kt` vs `stores/StoreUserSettingsSystem.java`

Reference fields: `dispatcher`, `onFontScaleUpdated: Function1<Integer,Unit>`, `settings: Settings` (lateinit), companion `DEFAULT_IS_CLIENT_SYNC_ENABLED = true`. Nested `Settings(theme: String, _locale: String?, fontScale: Int)` with derived `locale`.
Reference constructor: `(onFontScaleUpdated, dispatcher)` — prefs come from inherited `getPrefs()` / `getPrefsSessionDurable()`; deck from `ObservationDeckProvider.get()`.

### MISSING FIELDS
- **MINOR** — companion `DEFAULT_IS_CLIENT_SYNC_ENABLED` constant absent (target inlines `true` at `:30,31`).

### MISSING METHODS/BEHAVIOR
- **MAJOR** — `setLocale(String?, Boolean, Function0?)` in the reference is a **synchronous, non-dispatched** public method (`StoreUserSettingsSystem.java:327-341`) that mutates `settings`, writes `CACHE_KEY_LOCALE`, and `markChanged()` inline. It is invoked directly from `handleUserSettingsLocaleUpdate` on the store thread. Target's `setLocale` (`StoreUserSettingsSystem.kt:65-71`) wraps everything in `dispatcher.schedule { }`, so it can no longer be composed inside a `@StoreThread` handler; the target therefore has to duplicate the write logic in `handleUserSettings` (`:124-135`) and `handleLoginResult` (`:90-102`). Two independent code paths that must be kept in sync — a real divergence.
- **MAJOR** — `handleUserSettingsThemeUpdate(String?)` and `handleUserSettingsLocaleUpdate(String?)` (`StoreUserSettingsSystem.java:162-175`) are absent as named methods. Their logic is inlined into `handleUserSettings` / `handleLoginResult`. Reference `handlePreLogout` also composes `setLocale(null, …)` + `setFontScale(-1)` (`:272-277`); the target reimplements both inline (`:105-113`).
- **MINOR** — `setThemeInternal(String, Boolean, Function0?)` — reference has both `setTheme` (dispatched) and the private `setThemeInternal` (store-thread). Target has only `setTheme` with the internal body inlined into the schedule lambda (`:54-63`). Behaviourally the same.
- **MINOR** — `access$getSettings$p` / `throwUninitializedPropertyAccessException` guards on every getter. Target uses `lateinit var settings` (`:24`) — same crash-on-early-read semantics.

### EXTRA/DIVERGENT
- **MAJOR** — Signature change: reference `handleConnectionOpen(ModelPayload)` extracts `payload.getUserSettings()` itself (`StoreUserSettingsSystem.java:255-260`). Target takes `ModelUserSettings` directly (`:86`). Callers must now unwrap; `StoreStream.kt:1491-1493` does so with `payload.userSettings?.let { … }`, so a **null `userSettings` in the READY payload silently skips theme/locale sync**, whereas the reference dereferences it unconditionally (`m.checkNotNullExpressionValue`).
- **MAJOR** — Target adds `internal fun reset()` (`:116-121`) and `private var defaultTheme` (`:25`) plus `internal fun restore(defaultTheme)` (`:45-52`). None of the three exist in the reference; `reset()` restores `Settings(defaultTheme, null, -1)` and is only called by the target-invented `StoreStream.resetForLogout` / `handlePreLogoutInternal`.
- **MINOR** — Target constructor takes 5 params (`onFontScaleUpdated, dispatcher, observationDeck, preferences, sessionDurablePreferences`, `:13-19`) vs reference's 2. Injectable prefs/deck for testability; deviates from the reference contract but preserves defaults.
- **MINOR** — Target `handleUserSettings` / `handleLoginResult` batch theme+locale into a single `settings` copy and only `markChanged()` if the result differs (`:96,129`). Reference calls `setThemeInternal` and `setLocale` independently, each unconditionally calling `markChanged()` when the value is non-null and sync is enabled — i.e. the reference emits an update even when the value is unchanged. Observable emission count differs.
- **MINOR** — Target `handlePreLogout` (`:105-113`) writes `preferences.edit { remove(KEY_LOCALE); putInt(KEY_FONT_SCALE, -1) }` and calls `onFontScaleUpdated(-1)` directly. Reference routes through `setFontScale(-1)`, which schedules a *nested* dispatch (`StoreUserSettingsSystem.java:305-311`). Target's version is synchronous; reference's is deferred one dispatch.
- **MINOR** — `observeSettings(sample)`: reference throttles with `.P(1500, MILLISECONDS)` (throttleLast) then `.r()` (distinctUntilChanged). Target matches exactly (`:32-36`). No gap. Target injects `observationDeck` instead of `ObservationDeckProvider.get()` — minor.

**Severity totals — pair 8: BLOCKER 0, MAJOR 4, MINOR 7.**

---

## 9. `stores/ReadyPayloadHydrator.kt` vs `stores/ReadyPayloadUtils.java` (+ READY handling in `StoreStream.java`)

Reference `ReadyPayloadUtils` is a **Kotlin `object`** (singleton, `INSTANCE`) with a **static mutable cache**; the target is a **per-StoreStream class** taking `(StoreGuilds, StoreChannels)`.

### MISSING FIELDS
- **BLOCKER** — `private static final HashMap<Long, GuildCache> cache` + `public getCache()` (`ReadyPayloadUtils.java:41,359-361`). The reference *snapshots every currently-cached guild* into this map at the start of `hydrateReadyPayload` (`:399-431`) and reads from it inside `hydrateGuild`. Target has no cache at all — it queries `guildStore`/`channelStore` inline per guild (`ReadyPayloadHydrator.kt:37-39`).
- **BLOCKER** — `GuildCache(guild, emojis: List<GuildEmoji>?, stickers: List<Sticker>?, channels: List<Channel>?, roles: List<GuildRole>?)` data class — absent. Target carries no emoji or sticker state at all.
- **BLOCKER** — `sealed HydrateResult { Success(payload: ModelPayload) | Error }` (`ReadyPayloadUtils.java:252-329`) — absent. Target `hydrate()` returns a bare `ModelPayload` (`ReadyPayloadHydrator.kt:14`), so the **error branch does not exist**.
- **BLOCKER** — `sealed HydrateGuildResult { Success(guild: Guild) | Error }` (`:172-249`) — absent, same reason.

### MISSING METHODS/BEHAVIOR
- **BLOCKER** — `hydrateReadyPayload(payload, StoreGuilds, StoreChannels, StoreEmojiCustom, StoreGuildStickers): HydrateResult` (`:387-465`). Target's `hydrate(payload)` covers only the `StoreGuilds` + `StoreChannels` half. `StoreEmojiCustom` and `StoreGuildStickers` do not exist in the target, so **custom emoji and guild sticker state are never carried across a READY**.
- **BLOCKER** — `hydrateGuild(Guild, List<Presence>?, Map<Long, GuildMember>?): HydrateGuildResult` (`:372-378`) is **not decompilable** — JADX emits `throw new UnsupportedOperationException("Method not decompiled: …")` with "Method dump skipped, instructions count: 247". **Its exact body is unreadable in this decompile; I am not guessing at it.** What *is* readable from `hydrateReadyPayload`'s call site: it consumes the per-index `guildPresences[i]` list and a `GuildMember` map keyed by `member.user.id`, reads `cache[guild.id]`, returns `Error` on failure, and the returned `Guild` is collected into `payload.withGuilds(guilds)`. The target's per-guild merge (`ReadyPayloadHydrator.kt:36-49`) is a *reimplementation from first principles*, not a port — it cannot be verified equivalent from this decompile.
- **BLOCKER** — `hydrate(unhydratedGuild, oldGuild, oldCustomEmojis, oldStickers, oldChannels, oldRoles, presences, members, onHydrateChannel: (Channel, Channel) -> Channel): Guild` (`:572-636`). This is the real merge routine. It carries over from `oldGuild`, when the incoming READY guild is sparse: `afkChannelId`, `afkTimeout`, `banner`, `defaultMessageNotifications`, `description`, `explicitContentFilter`, `features`, `icon`, `maxVideoChannelUsers`, `mfaLevel`, `name`, `ownerId`, `preferredLocale`, `premiumSubscriptionCount`, `premiumTier`, `publicUpdatesChannelId`, `region`, `rulesChannelId`, `splash`, `systemChannelId`, `vanityUrlCode`, `verificationLevel` and more (22+ scalar fields, `Guild.a(...)` masks `339865671, 16377`). Target `ReadyPayloadHydrator.kt:40-48` carries over only **five**: `roles`, `name`, `ownerId`, `icon`, `channels`. All other cached guild scalars are silently dropped on a re-READY.
- **BLOCKER** — the `onHydrateChannel` callback + `hydrate(Channel, Channel)` (`ReadyPayloadUtils.java:335-337`): when a READY channel arrives with `lastMessageId == 0`, the reference **restores `lastMessageId` from the cached channel** (`Channel.l()` == `lastMessageId`, confirmed at `api/channel/Channel.java:283-285`). Target has no per-channel merge at all — it either uses `guild.channels` wholesale or falls back to the entire cached channel list (`ReadyPayloadHydrator.kt:45`). Unread state will be wrong after a reconnect where the gateway omits `last_message_id`.
- **MAJOR** — `hydrateUsers(ModelPayload): ModelPayload` (`:468-555`) is a **public, independently callable** method in the reference; the target folds it into `hydrate()` with no separate entry point.
- **MAJOR** — reference `hydrateUsers` builds the user map **only from `payload.users`** (`:474-479`) and returns the payload unchanged if `users == null`. Target additionally seeds the map from `payload.me` and from inline `guild.members[].user` (`ReadyPayloadHydrator.kt:17-21`) and always proceeds. Target is a superset — divergent input contract.

### EXTRA/DIVERGENT
- **MAJOR** — `hydratePresence`: reference (`ReadyPayloadUtils.java:350-357`) **never drops a presence** — if `presence.userId` is missing or unresolvable it returns `Presence.a(presence, …, 47)` (a copy with the user field cleared). Target `ReadyPayloadHydrator.kt:76-80` returns **`null`** and the caller `mapNotNull`s it away, so presences are **silently discarded**. Also, the target mutates `userId` and `guildId` on the copy; the reference only touches the `user` field.
- **MAJOR** — `hydrateGuildMember`: reference (`:380-385`) **never drops a member** — if `member.userId` is null or unresolvable it returns the member unchanged. Target `ReadyPayloadHydrator.kt:70-74` returns **`null`** → member dropped. Also the target rewrites `guildId`; the reference does not.
- **MAJOR** — `hydrate(Channel, Map<Long,User>)` (recipients): reference (`:557-570`) rebuilds `recipients` from `recipientIds` **whenever `recipientIds != null`**, unconditionally overwriting. Target `ReadyPayloadHydrator.kt:64-68` **skips** the rebuild when `channel.recipients` is already non-empty. Divergent precedence.
- **MAJOR (StoreStream side)** — reference `StoreStream.handleConnectionOpen` branches on `HydrateResult` and calls `handleHydrateError()` → `clientDataState.clear(); gatewaySocket.resetOnError();` (`StoreStream.java:1643-1717,1991-1994`). Target `StoreStream.kt:1486` has no branch — a hydration failure cannot be signalled, and the gateway is never reset on error. There is a **second** reference call site at `StoreStream.java:1827` (inside `handleGuildCreateOrUpdate`) that also falls back to `handleHydrateError()`; the target's `handleGuildCreateOrUpdateInternal` (`StoreStream.kt:1518-1529`) has no equivalent.
- **MINOR** — Target's `hydrate` short-circuits with `payload.guilds ?: return payload` (`:15`). Reference short-circuits in `hydrateUsers` on `payload.users == null` instead. Different guard.
- **MINOR** — Naming: `ReadyPayloadUtils` (object) → `ReadyPayloadHydrator` (class). Any reference-parity code searching for `ReadyPayloadUtils.INSTANCE` will not find it.

**Severity totals — pair 9: BLOCKER 7, MAJOR 7, MINOR 2.**

---

## Aggregate

| Pair | BLOCKER | MAJOR | MINOR |
|---|---|---|---|
| 1. Dispatcher / DispatchHandler | 0 | 0 | 3 |
| 2. Store | 0 | 0 | 2 |
| 3. StoreV2 | 0 | 1 | 3 |
| 4. StoreV2DispatchHandler | 0 | 0 | 0 |
| 5. ObservationDeck + Provider | 0 | 4 | 6 |
| 6. StoreStream | 1 (+106 missing stores) | 17 | 9 |
| 7. StoreAccessibility | 0 | 1 | 6 |
| 8. StoreUserSettingsSystem | 0 | 4 | 7 |
| 9. ReadyPayloadHydrator / ReadyPayloadUtils | 7 | 7 | 2 |
| **Total** | **8 (+106)** | **34** | **38** |

### Explicitly unreadable in the decompile (no guesses made)
1. `ReadyPayloadUtils.hydrateGuild(Guild, List<Presence>, Map<Long,GuildMember>)` — JADX bailed out ("Method not decompiled", 247 instructions). The single most important merge routine in pair 9 could not be compared.
2. The `Func4` predicate inside `StoreStream$deferredInit$1` that feeds `initialized.onNext(...)` — JADX emitted a truncated `Code restructure failed` block. The exact readiness condition is unknown.
3. `Guild.a(...)` / `Channel.a(...)` / `Presence.a(...)` / `GuildMember.a(...)` copy-mask arguments are positional and obfuscated; field names above were resolved only where an accessor could be confirmed (e.g. `Channel.l()` → `lastMessageId`, `api/channel/Channel.java:283`). Where the mask covers a range of fields I described the count, not invented names.
