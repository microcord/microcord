# Core stores parity re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References:

- JADX: `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\`
- Vineflower: `C:\Users\manti\Desktop\projeler\discorddecompile\out\com\discord\`

This supersedes the 2026-07-26 core report. It compares the current target, not
the historical 36-store graph. Production code was not changed.

## Current result

| Surface | Current classification | Open findings |
| --- | --- | ---: |
| `Dispatcher` / `DispatchHandler` | PARTIAL | 2 MINOR |
| `Store` / `StoreThread` | PARTIAL | 1 MINOR |
| `StoreV2` | PARTIAL | 1 MINOR |
| `StoreV2DispatchHandler` | IMPLEMENTED | 0 |
| `ObservationDeck` / provider | PARTIAL | 2 MINOR |
| `StoreStream` | PARTIAL | 4 BLOCKER, 8 MAJOR, 5 MINOR |
| `StoreAccessibility` | PARTIAL | 3 MINOR |
| `StoreUserSettingsSystem` | PARTIAL | 3 MAJOR, 4 MINOR |
| READY hydration | PARTIAL | 1 BLOCKER, 3 MAJOR, 2 MINOR |
| **Total** | **PARTIAL** | **5 BLOCKER, 14 MAJOR, 19 MINOR** |

Counts are findings, not missing-method counts. One finding can cover a coherent
family such as the ten absent StoreStream stores.

## 1. Dispatcher and base store

### Verified

- `Dispatcher` retains the reference fields and dispatch lifecycle: scheduler,
  clean-dispatch flag, handler list, remembered dispatcher thread, scheduled
  action, then `onDispatchEnded()` (`stores/Dispatcher.kt:6-41`; reference
  `stores/Dispatcher.kt`, `schedule` / `assertCleanDispatch`).
- `DispatchHandler.onDispatchEnded()` is equivalent despite living in
  `Store.kt:7-9` rather than a separate file.
- `Store.init(Context)` and both preference providers exist
  (`stores/Store.kt:16-23`).

### Open

- **MINOR:** `Dispatcher` still reports missing handlers and dirty nested
  dispatches through `android.util.Log` (`Dispatcher.kt:13-19`) rather than the
  reference `AppLog`/`Logger` path. Store ordering is unchanged, but app-level
  breadcrumbs/sinks do not receive these diagnostics.
- **MINOR:** `Dispatcher.schedule()` calls
  `StoreThreadScheduler.bindCurrentThread()` on every dispatch
  (`Dispatcher.kt:20-24`). The reference records the thread id in the dedicated
  thread factory. This makes an alternate scheduler silently redefine the store
  thread and weakens confinement diagnostics.
- **MINOR:** inherited preference access is `protected` in `Store.kt:17-21`; the
  reference getters are public final. No current production caller needs the
  wider visibility.

## 2. StoreV2 and dispatch completion

### Resolved since 2026-07-26

- Off-thread `markChanged()` no longer throws. `StoreV2.assertStoreThread()` logs
  through `AppLog` and continues (`StoreV2.kt:12-27`), matching DEX/Vineflower.
- `MarkChangedDelegate.setValue` no longer adds an independent throwing thread
  assertion (`StoreV2.kt:59-64`).
- `StoreV2DispatchHandler` still snapshots each dirty store before clearing its
  sources and notifying the deck (`StoreV2DispatchHandler.kt:6-21`).

### Open

- **MINOR:** `markChanged`, vararg `markChanged`, and `markUnchanged` remain
  `protected` (`StoreV2.kt:29-43`); the reference methods are public final.
  Existing target orchestration does not call them from outside subclasses.

## 3. ObservationDeck

### Resolved since 2026-07-26

- Breadcrumb and notify-error paths now exist (`updates/ObservationDeck.kt:35-40,
  91-140`).
- Anonymous observers now default their name to `hashCode().toString()`
  (`ObservationDeck.kt:45-55`).
- The old claim that the reference had no catch in `notify` was wrong. DEX and
  Vineflower show the guarded notify loop: `ERROR`/`VERBOSE` logs and absorbs;
  `NONE` rethrows. The target now matches (`ObservationDeck.kt:98-120`).
- Provider lazy-singleton behavior remains equivalent
  (`ObservationDeckProvider.kt:3-8`).

### Open

- **MINOR:** the reference injects/stores a `Logger` (default `AppLog.g`); the
  target has only `logLevel` and writes directly to `android.util.Log`
  (`ObservationDeck.kt:7-10,123-140`). Log gating and exception behavior match,
  but app logging sinks do not.
- **MINOR:** `connectRx` uses `Emitter.setCancellation`
  (`ObservationDeck.kt:61-74`) instead of the reference unsubscribe hook. This
  can disconnect on terminal cancellation as well as explicit unsubscribe.

## 4. StoreStream graph and lifecycle

### Updated counts

- The historical `36/145` construction count is obsolete.
- The current `storesV2` declaration contains **101** entries
  (`StoreStream.kt:769-879`); `StoreStream.init` adds
  `StoreStreamRtcConnection` and `StoreAudioManagerV2`
  (`StoreStream.kt:976-978`). Current total: **103/106 reference `storesV2`
  entries (97.2%)**.
- The current graph constructs or obtains **135/145 reference StoreStream store
  instances (93.1%)**, counting the four stores owned by `VoiceTransport` and
  mapping target renames/consolidations to their reference responsibilities.
  The ten absent reference stores are:
  `StoreAutocomplete`, `StoreChannelFollowerStats`, `StoreClientVersion`,
  `StoreDirectories`, `StoreGifting`, `StoreGuildRoleMemberCounts`,
  `StoreGuildVoiceRegions`, `StoreLibrary`, `StoreOutboundPromotions`, and
  `StoreDynamicLink`.
- The target uses one eager `StoreChannelMembers` and one eager
  `StoreThreadMembers` where the reference names them `lazyChannelMembersStore`
  and `lazyThreadMembersStore`; these are mappings, not additional missing
  stores.

### Resolved since 2026-07-26

- Store utilities, `StoreGatewayConnection`, `StoreConnectivity`,
  `StoreConnectionOpen`, `StoreClientDataState`, notifications, experiments,
  thread/chat/reaction/reply/mention/slow-mode stores, social stores, billing,
  stage, and voice stores are now constructed and published.
- `dispatchSubscribe` restores `onBackpressureBuffer` and named diagnostics
  (`StoreStream.kt:1481-1501`).
- `initGatewaySocketListeners()` now routes the typed event surface, including
  threads, voice/calls/streams, interactions, scheduled events, user-required
  actions, subscriptions, payment sources, and forum unreads
  (`StoreStream.kt:1503-1850`).
- Connected/ready fan-out now covers the reference destinations
  (`StoreStream.kt:1852-1874`).
- Emoji data initialization and `Rules.setEmojiDataProvider` are restored in the
  load-bearing order (`StoreStream.kt:1110-1125`).
- Fingerprint fan-out, `NetworkMonitor`, background observation, message-loader
  wiring, `ChannelSelector.init`, and broad READY fan-out now exist
  (`StoreStream.kt:936-1378,2694-2778,2991-2997`).
- READY hydration errors clear `StoreClientDataState` and reset the gateway
  (`StoreStream.kt:2694-2703`).

### Open blockers

- **BLOCKER:** the ten reference stores listed above remain absent. Their live
  effects include autocomplete state, channel follower statistics, minimum
  client/version enforcement, directory/hub state, gifts, role member counts,
  guild voice-region state, library applications, outbound promotions, and
  dynamic-link state.
- **BLOCKER:** initialization readiness is still hard-coded. Production calls
  `initialized.onNext(true)` after queueing cache restore
  (`StoreStream.kt:1268-1286`), and tests do the same (`:1289-1325`). The
  reference combines experiment initialization, authentication, selected-channel
  initialization, and `Persister.isPreloaded()` before publishing readiness
  (Vineflower `StoreStream.kt:338-349`). The target can report ready before the
  queued restore has completed.
- **BLOCKER:** pre-logout remains conflated with a full graph reset.
  `handlePreLogoutInternal` resets user/guild/channel/message/thread/voice state
  (`StoreStream.kt:2535-2581`), while reference `handlePreLogout` only notifies
  its 19 designated stores (Vineflower `StoreStream.kt:980-999`). This changes
  timing and observer-visible state during logout.
- **BLOCKER:** `resetForLogout()` remains a target-only second reset path
  (`StoreStream.kt:2646-2691`). It deliberately isolates failures and clears
  persisted account state, but has no reference StoreStream counterpart and
  duplicates much of pre-logout. Security-motivated account isolation is valid;
  its ordering and duplicate notifications are not reference-verified.

### Open major/minor findings

- **MAJOR:** there is still no reference-equivalent `deferredInit(Application)`
  boundary. Its responsibilities are distributed through `init(Context)`
  (`StoreStream.kt:936-1287`), and absent-store initialization cannot occur.
- **MAJOR:** `startStoreInitializationTimer`, `ConnectionTimeStats` and its three
  listeners, and `maybeLogNotificationPermissionStatus` have no target
  equivalents. Startup timeout/connection timing/notification-permission
  telemetry therefore diverge.
- **MAJOR:** `StoreUIEventHandler` is not constructed. The target instead installs
  feature-specific static bindings in `StoreStream.init`; no audit proves those
  bindings cover the complete reference UI event-handler contract.
- **MAJOR:** background handling updates `isBackgrounded` and
  `StoreConnectivity` (`StoreStream.kt:1133-1136,1254-1258`) but does not call
  `StoreMessagesLoader.handleBackgrounded`, which the reference does
  (Vineflower `StoreStream.kt:446-453`).
- **MAJOR:** message user processing updates `StoreUser` only
  (`StoreStream.kt:1707-1716,1892-1912`; `StoreUser.kt:79-87`). The reference also
  synthesizes guild-member adds for message authors/mentions
  (`StoreStream.processMessageUsers` / `synthesizeGuildMemberAdd`). Profile/member
  caches can therefore remain incomplete after a message supplies member data.
- **MAJOR:** `handleGuildCreateOrUpdateInternal` directly fans out the incoming
  guild (`StoreStream.kt:2780-2807`) and does not call the READY hydrator or its
  error reset path. The reference hydrates `GUILD_CREATE` and calls
  `handleHydrateError()` on failure.
- **MAJOR:** READY auth handling calls `setAuthed` and
  `authentication.handleConnectionOpen` (`StoreStream.kt:2706-2708`) rather than
  the reference's explicit `handleAuthState(AuthState.from(token, emptySet()))`
  before `setAuthed`. Equivalence depends on `StoreAuthentication` internals and
  is not established here.
- **MAJOR:** target READY accepts a nullable `payload.userSettings` and silently
  skips both settings stores (`StoreStream.kt:2709-2713`). The reference passes
  the whole payload and requires user settings.
- **MINOR:** initialization ordering differs substantially from the reference
  three-phase initialize/init/deferredInit sequence; individual load-bearing
  orders documented above are correct, but the whole order has no parity proof.
- **MINOR:** startup and READY timing use `BootLog` markers rather than the
  reference `TimeElapsed` app logs (`StoreStream.kt:1002,1259-1286,2695-2776`).
- **MINOR:** the target retains a non-reference helper overload
  `handleConnectionOpen(ModelUserSettings, List<Guild>)`
  (`StoreStream.kt:1442-1446`).
- **MINOR:** `StoreThreadScheduler.storeThreadId` substitutes for the reference
  StoreStream companion field. This is structurally different but supplies the
  same production thread identity.
- **MINOR:** StoreStream accessors are broad but not a one-for-one reproduction;
  absent-store accessors necessarily remain absent (`StoreStream.kt:3009-3177`).

## 5. StoreAccessibility

### Resolved since 2026-07-26

- All semantic fields remain present (`StoreAccessibility.kt:13-24`).
- `snapshotData` now publishes accessibility analytics
  (`StoreAccessibility.kt:86-96`).
- Snapshot access now returns the reference alias rather than a clone
  (`StoreAccessibility.kt:26`).
- Font-scale flags are cumulative as in the reference: the target only adds the
  increased/decreased flags (`StoreAccessibility.kt:88-94`).

### Open

- **MINOR:** monitor subscription is still raw and untagged
  (`StoreAccessibility.kt:30-35`), unlike reference `appSubscribe`; errors are
  not routed through the app error handler and the subscription is not retained.
- **MINOR:** target-only `reset()` and `initForTesting()` remain
  (`StoreAccessibility.kt:37-40,69-77`). `reset()` is reachable only through the
  target logout machinery.
- **MINOR:** explicit `StoreThreadScheduler.assertCurrentThread()` calls are
  additional diagnostics (`StoreAccessibility.kt:54-84`). They now log rather
  than throw, so they no longer change failure behavior.

## 6. StoreUserSettingsSystem

### Verified

- Settings fields, persisted keys, sync flags, sampling and distinct behavior,
  theme retention, locale derivation, and font-scale callback are present
  (`StoreUserSettingsSystem.kt:13-83`).

### Open

- **MAJOR:** reference `setLocale` mutates synchronously on the caller's store
  thread; target always schedules (`StoreUserSettingsSystem.kt:65-71`). Target
  consequently duplicates locale/theme write logic in login/READY handlers
  (`:90-102,123-135`).
- **MAJOR:** reference named handlers `handleUserSettingsThemeUpdate` and
  `handleUserSettingsLocaleUpdate` are absent; their behavior is folded into two
  combined handlers. Independent update/notification ordering is not preserved.
- **MAJOR:** `handleConnectionOpen` takes `ModelUserSettings` rather than the
  reference `ModelPayload` (`StoreUserSettingsSystem.kt:85-88`), enabling the
  nullable READY skip described above.
- **MINOR:** target-only `defaultTheme`, `restore`, and `reset` remain
  (`StoreUserSettingsSystem.kt:24-52,115-121`).
- **MINOR:** combined handlers notify only when the value changes
  (`StoreUserSettingsSystem.kt:93-101,126-134`); reference setters mark changed
  whenever a non-null synchronized value is handled.
- **MINOR:** target pre-logout applies locale/font scale synchronously
  (`StoreUserSettingsSystem.kt:104-113`); reference routes font scale through a
  nested scheduled setter.
- **MINOR:** `DEFAULT_IS_CLIENT_SYNC_ENABLED` is inlined as `true` rather than
  retained as a named companion constant (`StoreUserSettingsSystem.kt:30-31`).

## 7. READY hydration

### Resolved since 2026-07-26

- `HydrateResult` and `HydrateGuildResult`, cache snapshotting, omitted-hash error
  handling, and StoreStream reset-on-error are implemented
  (`ReadyPayloadHydrator.kt:13-147`; `StoreStream.kt:2694-2703`).
- Metadata omission now carries the same reference scalar set
  (`ReadyPayloadHydrator.kt:102-131`).
- Channel omission preserves cached channels and accepts only non-zero incoming
  `lastMessageId` updates (`ReadyPayloadHydrator.kt:150-161`).
- Missing users no longer drop members or presences; member/presence hydration
  preserves the object and replaces only `user` when resolvable
  (`ReadyPayloadHydrator.kt:169-175`).
- Recipient ids always take precedence when present
  (`ReadyPayloadHydrator.kt:163-167`).
- Focused regression coverage exists in
  `app/src/test/java/com/discord/stores/ReadyPayloadHydratorTest.kt:17-70`.

### Open

- **BLOCKER:** reference cache snapshots custom emoji and stickers from
  `StoreEmojiCustom` and `StoreGuildStickers` separately (Vineflower
  `ReadyPayloadUtils.kt:761-895`). Target cache reads `StoredGuild.emojis` and
  `StoredGuild.stickers` (`ReadyPayloadHydrator.kt:139-147,177-224`). Gateway
  emoji/sticker updates are routed to their dedicated stores, not `StoreGuilds`
  (`StoreStream.kt:1524-1532`), so updates received after READY can be lost on a
  later metadata-omitted READY.
- **MAJOR:** target user hydration seeds from `payload.me` and inline guild
  members in addition to `payload.users` (`ReadyPayloadHydrator.kt:37-51`). The
  reference `hydrateUsers` uses only `payload.users`; precedence differs when
  duplicate user objects disagree.
- **MAJOR:** target substitutes a per-StoreStream cache for the reference object
  singleton (`ReadyPayloadHydrator.kt:13-17`). This is safer for isolated graphs,
  but cross-instance/static semantics differ.
- **MAJOR:** `hydrate(payload): ModelPayload` remains a compatibility adapter that
  converts `Error` back to the original payload (`ReadyPayloadHydrator.kt:29-31`).
  Production StoreStream uses `hydrateReadyPayload`, but any caller of the adapter
  bypasses the reset/error contract.
- **MINOR:** reference exposes `hydrateUsers` and the general nine-argument
  `hydrate` merge as public methods; target folds them into private helpers.
- **MINOR:** target class/constructor shape differs from reference
  `ReadyPayloadUtils` object and requires only guild/channel stores. This matters
  to structural parity but is secondary to the custom emoji/sticker behavior.

## Decompiled-reference caveats

- The old report marked `ReadyPayloadUtils.hydrateGuild` unreadable because JADX
  skipped 247 instructions. Vineflower recovers it at
  `discorddecompile/out/com/discord/stores/ReadyPayloadUtils.kt:610-743`; the
  omitted metadata/channels/roles branches above are verified from that body.
- Vineflower still renders the channel-merge lambda passed at
  `ReadyPayloadUtils.kt:735,740` as `<unrepresentable>`. Its behavior is verified
  independently by the recovered `Channel.hydrate` body at `:25-66`: copy a
  non-zero incoming `lastMessageId`, otherwise retain the cached channel.
- Vineflower still renders the readiness `Func4` as `<unrepresentable>` at
  `StoreStream.kt:338-349`. Its four inputs and output publication are verified;
  the exact Boolean expression is not. This report does not claim the target can
  reproduce that predicate without DEX/smali inspection.

## Verification gaps

- No Gradle task was run for this documentation-only re-audit.
- `ReadyPayloadHydratorTest` covers omitted metadata/channel behavior, but no test
  covers custom emoji/sticker updates between READY payloads.
- No focused test proves initialization remains false until all four reference
  readiness sources settle.
- No focused test compares pre-logout observer ordering with 126.21.
