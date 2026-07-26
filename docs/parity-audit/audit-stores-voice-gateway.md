# Parity Audit — Voice / Media / Misc Stores + Gateway Store

TARGET  = `C:\Users\manti\Desktop\projeler\manticordrebuild\app\src\main\java\com\discord\`
REFERENCE = `C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\` (Discord Android 126.21, JADX)

Read-only audit. No target files modified.

---

## Executive summary

| # | Pair | Target LOC | Ref LOC | Target is | Severity |
|---|------|-----------:|--------:|-----------|----------|
| 1 | StoreVoiceParticipants | 23 | 451 | **contract-only interface** | BLOCKER |
| 2 | VoiceStoreContracts (11 ref stores) | 76 | 5 016 | **DTO shims for 3 of 11; 8 absent entirely** | BLOCKER |
| 3 | StoreApplicationStreamPreviews | 8 | 258 | **sealed-interface shim** | BLOCKER |
| 4 | StickerAnimationSettingsStore | 46 | (StoreUserSettings.java) | **duplicate shadow store** | MAJOR |
| 5 | NotificationPolicy | 78 | (no ref class; ref logic JADX-broken) | **synthesized abstraction** | MAJOR (unverifiable) |
| 6 | Gateway store (GatewaySocket/GatewayEventRouter/StoreStream) | 389+21+~150 | 1 542 | **socket real, store layer collapsed** | BLOCKER |

Gap counts by severity: **BLOCKER 4**, **MAJOR 2**, **MINOR 3** (enumerated per pair below).

---

## 1. `stores/StoreVoiceParticipants.kt` vs `stores/StoreVoiceParticipants.java`

**STUB DETECTION: PLACEHOLDER / CONTRACT ONLY.**
Target (`StoreVoiceParticipants.kt:8`) is `object StoreVoiceParticipants` containing a *single nested interface* `VoiceUser`. It does not extend `Store`, has no constructor, no `StoreStream` dependency, no subjects, no `init`. Reference (`StoreVoiceParticipants.java:41`) is `public final class StoreVoiceParticipants extends Store`.

### MISSING FIELDS (store level)
| Reference field (`StoreVoiceParticipants.java`) | Type | Target |
|---|---|---|
| `selectedParticipantSubject` :42 | `BehaviorSubject<Long>` seeded `0L` (:336) | **MISSING** |
| `stream` :43 | `StoreStream` | **MISSING** (no store dependency at all) |

### MISSING FIELDS (`VoiceUser`)
Reference `VoiceUser` (`:48-72`) is a 11-arg data class with 24 backing fields. Target `VoiceUser` (`StoreVoiceParticipants.kt:9-22`) is an interface with 11 members.

| Reference field | Type / nullability | Target status |
|---|---|---|
| `user` :69 | `User` | present (`:10`) |
| `voiceState` :71 | `VoiceState?` | present (`:11`) |
| `isRinging` :60 | `boolean` | present (`:15`) |
| `streams` :68 | `StoreVideoStreams.UserStreams?` | **MISSING** — target replaces with two loose `Int?` (`callStreamId` :17, `applicationStreamId` :18). Loses the `UserStreams` aggregate that the rest of the voice pipeline keys off. |
| `isMe` :57 | `boolean` | **MISSING** |
| `guildMember` :53 | `GuildMember?` | present (`:12`) |
| `watchingStream` :72 | `String?` (StreamKey) | **MISSING** |
| `streamContext` :67 | `StreamContext?` | present (`:19`) |
| `isBooster` :54 | `boolean` (derived `guildMember.premiumSince != null`, :358/:378) | **MISSING** |
| `voiceConfiguration` :70 | `StoreMediaSettings.VoiceConfiguration?` (private ctor arg) | **MISSING** |
| `_isSpeaking` :49 | `boolean` (private raw input) | **MISSING** (target exposes only derived `isSpeaking` :14) |
| `isSpeaking` :65 | derived: `voiceState != null && _isSpeaking && !serverMute && !serverDeaf` (:89) | present but **NOT derived** — target declares it an abstract input, so the server-mute/deaf suppression at :89 is lost. **MAJOR** |
| `nickname` :66 | `String?` = `guildMember.nick` (:90) | **MISSING** |
| `applicationStream` :50 | `ModelApplicationStream?` = `streamContext.stream` (:93) | **MISSING** |
| `applicationStreamId` :51 | `Integer?` = `streams.applicationStreamId` (:92) | present as free member, no derivation |
| `callStreamId` :52 | `Integer?` = `streams.callStreamId` (:91) | present as free member, no derivation |
| `isServerMuted` :63 | `boolean` = `voiceState.e()` (:94) | **MISSING** |
| `isSelfMuted` :62 | `boolean` — **isMe ? voiceConfiguration.isSelfMuted() : voiceState.h()** (:96-107) | **MISSING** |
| `isMuted` :58 | `isServerMuted \|\| isSelfMuted` (:108) | present (`:20`) but **WRONG**: target computes `voiceState.selfMute \|\| voiceState.mute`, ignoring the me-user override from `VoiceConfiguration`. **MAJOR** |
| `isServerDeafened` :64 | `boolean` = `voiceState.b()` (:109) | **MISSING** |
| `isSelfDeafened` :61 | `boolean` — isMe ? `voiceConfiguration.isSelfDeafened()` : `voiceState.g()` (:111-122) | **MISSING** |
| `isDeafened` :55 | `isServerDeafened \|\| isSelfDeafened` (:123) | present (`:21`), same me-user override bug as `isMuted`. **MAJOR** |
| `isRequestingToSpeak` :59 | `d.y0(voiceState).isRequestingToSpeak()` (:124) | **MISSING** |
| `isInvitedToSpeak` :56 | `== StageRequestToSpeakState.REQUESTED_TO_SPEAK_AND_AWAITING_USER_ACK` (:125) | **MISSING** |
| `isConnected` :275 | `voiceState != null` | present (`:16`) as abstract input, not derived |
| `displayName` :200 | `nickname ?: user.username` | present (`:13`) as abstract input, not derived |

### MISSING METHODS / BEHAVIOR
| Reference method | Target |
|---|---|
| `create(meUser, otherUsers, voiceStates, speakingUsers, ringingUsers, videoStreams, guildMembers, streamSpectators, voiceConfiguration, streamContexts)` :340 — the 10-source fan-in that builds the participant map | **MISSING** |
| `getOtherVoiceUsers(channel, voiceStates)` :387 — DM recipients vs guild-voice branch via `ChannelUtils.B/w` | **MISSING** |
| `getStreamContextsForUsers(userIds)` :404 — `StreamContextService.getForUser` combineLatest | **MISSING** |
| `get(channelId): Observable<Map<Long, VoiceUser>>` :429 | **MISSING** |
| `getStream()` :437 | **MISSING** |
| `init(context)` :442 — subscribes `voiceChannelSelected.observeSelectedVoiceChannelId()` | **MISSING** |
| `selectParticipant(userId: Long?)` :448 | **MISSING** |

**SEVERITY: BLOCKER.** Zero of the store's 7 methods and both fields exist; the participant map is never produced.

---

## 2. `stores/VoiceStoreContracts.kt` vs 11 reference voice/media stores

Target file is 76 lines declaring three `private constructor()` classes that hold **only nested types**:
`StoreApplicationStreaming` (`:7`), `StoreAudioManagerV2` (`:29`), `StoreMediaSettings` (`:36`). Each has a `private constructor()` — i.e. deliberately non-instantiable. **These are DTO namespaces, not stores.**

Cross-check of the whole target tree (`grep -rl` over `**/*.kt`): `StoreVoiceStates`, `StoreVoiceSpeaking`, `StoreVoiceChannelSelected`, `StoreRtcConnection`, `StoreMediaEngine`, `StoreCalls`, `StoreCallsIncoming`, `StoreVideoStreams`, `StoreStreamRtcConnection` → **0 files each**. They do not exist in any form.

Consumers confirm the shim role: `widgets/voice/model/CallModel.kt:21,26,30`, `widgets/voice/controls/AnchoredVoiceControlsView.kt:52`, `views/calls/*` consume these purely as UI value types.

### 2a. StoreVoiceStates — **ABSENT** (ref 299 LOC)
Missing fields: `dirtyGuildIds: HashSet<Long>` :26, `myUserId: Long?` :27, `notifyVoiceStatesUpdated: Function3<Long,Long,Long,Unit>` :28, `observationDeck` :29, `sessionId: String?` :30, `voiceStates: HashMap<Long, HashMap<Long, VoiceState>>` :31, `voiceStatesSnapshot: Map<Long, Map<Long, VoiceState>>` :32.
Missing methods: `clear` :47, `updateVoiceState` :59, `get` :171, `getForChannel` :175, `getInternal$` :191, `handleAuthToken` :196, `handleConnectionOpen` :203, `handleGuildAdd` :221, `handleGuildRemove` :232, `handleVoiceStateUpdate` :240, `observe(guildId)` :245, `observeForPrivateChannels` :249, `observe(guildId, channelId)` :273, `snapshotData` :255. **BLOCKER.**

### 2b. StoreVoiceSpeaking — **ABSENT** (ref 72 LOC)
Missing: `observationDeck` :16, `speakingUsers: HashSet<Long>` :17, `speakingUsersSnapshot: Set<Long>` :18; `getSpeakingUsers` :27, `handleSpeakingUpdates(List<StoreRtcConnection.SpeakingUserUpdate>)` :32, `handleVoiceChannelSelected` :53, `observeSpeakingUsers` :61, `snapshotData` :68. **BLOCKER.**

### 2c. StoreVoiceChannelSelected — **ABSENT** (ref 436 LOC)
Missing fields: `VOICE_CHANNEL_ID_NONE = 0L` :40, `clock` :41, `dispatcher` :42, `lastSelectedVoiceChannelId` :43, `observationDeck` :44, `preselectedVoiceChannelId: Long?` :45, `selectedVoiceChannelId` :46, `sessionId: String?` :47, `stream` :48, `timeSelectedMs` :49. Missing nested `enum JoinVoiceChannelResult` :66.
Missing methods: `clearInternal` :122, `getJoinability` :131, `getVerificationLevelTriggered` :165, `selectVoiceChannelInternal` :204, `validateSelectedVoiceChannel` :270, `clear` :283, `getLastSelectedVoiceChannelId` :287, `getSelectedVoiceChannel` :291, `getSelectedVoiceChannelId` :295, `getTimeSelectedMs` :299, `handleAuthToken` :304, `handleChannelOrThreadCreateOrUpdate` :311, `handleChannelOrThreadDelete` :316, `handleConnectionOpen` :321, `handleGuildMemberAdd` :331, `handleGuildRemove` :339, `handleGuildRoleCreateOrUpdate` :344, `handleGuildRoleRemove` :349, `handleRtcConnectionStateChanged` :354, `handleStreamTargeted` :371, `handleVoiceStateUpdates` :377, `observeSelectedChannel` :396, `observeSelectedVoiceChannelId` :400, `observeTimeSelectedMs` :404, `selectVoiceChannel` :408, `snapshotData` :421.
Note: `observeSelectedVoiceChannelId()` is a hard dependency of `StoreGatewayConnection$ClientState$Companion.initialize` (`StoreGatewayConnection.java:234`) — its absence is why the target's gateway `ClientState` is also missing (see §6). **BLOCKER.**

### 2d. StoreMediaSettings — **DTO ONLY** (target `VoiceStoreContracts.kt:36-76`, ref 784 LOC)
Nested types: `NoiseProcessing` (4 constants, matches ref :48), `SelfMuteFailure` (matches ref :58), `VadUseKrisp` (matches ref :65). `VoiceConfiguration` (`:52-75`) — **all 15 fields present, in reference constructor order, with matching types and defaults**; `DEFAULT_OUTPUT_VOLUME=100f`, `DEFAULT_SENSITIVITY=-50f`, `DEFAULT_VOICE_CONFIG`, `DEFAULT_NOISE_PROCESSING` all present. Field-level parity for `VoiceConfiguration`: **PASS**.
- MISSING METHOD: `VoiceConfiguration.toMediaEngineVoiceConfig(): MediaEngine.VoiceConfig` (ref `StoreMediaSettings.java` VoiceConfiguration metadata, `d2` entry `"toMediaEngineVoiceConfig"`). **MAJOR** — this is the only bridge from settings to the native engine.
- MINOR: ref keeps `isSelfMuted` private (`component1` is private, :74 metadata `HÂ$` marker) with a public `isSelfMuted()` accessor; target exposes it as a public `val`. Behaviourally equivalent; cosmetic.

Missing store fields (all of ref :35-43): `appSoundManager`, `canUseVad: boolean`, `forceSelfMuteReason: SelfMuteFailure?`, `storeChannels`, `storePermissions`, `storeVoiceChannelSelected`, `voiceConfiguration`, `voiceConfigurationCache: VoiceConfigurationCache` (**the persistence handle**), `voiceConfigurationSubject: SerializedSubject<VoiceConfiguration, VoiceConfiguration>`.
Missing all 38 store methods, incl. `init` :530, `handleVoiceChannelSelected` :522, `handleCanUseVad` :426, `updateForceMute` :437, `setSelfMuted` :634, `toggleSelfMuted` :730, `setSelfDeafen` :619, `toggleUserMuted` :734, `setUserOutputVolume` :666, `setUserOffScreen` :659, `setSensitivity` :655, `setOutputVolume` :615, `setVoiceInputMode` :680, `setNoiseProcessing` :608, `toggleNoiseCancellation` :706, `toggleNoiseSuppression` :716, `toggleAutomaticVAD` :691, `toggleAutomaticGainControl` :686, `toggleEchoCancellation` :696, `setVADUseKrisp` :673, `toggleVADUseKrisp` :746, `revertTemporaryDisableKrisp` :588, `toggleEnableVideoHardwareScaling` :701, `updateVoiceParticipantsHidden` :755, `getInputMode` :447, `getUsersMuted` :464, `getUsersOffScreen` :477, `getUsersVolume` :490, `getVoiceConfig` :507, `getVoiceConfiguration` :513, `getVoiceConfigurationBlocking` :517, `getMutedUsers` :460, `getVideoHardwareScalingBlocking` :503, `isSelfMuted` :575, `isSelfDeafened` :562. **BLOCKER.**

### 2e. StoreAudioManagerV2 — **DTO ONLY** (target `VoiceStoreContracts.kt:29-34`, ref 513 LOC)
`State` (target `:30-33`) fields `audioDevices`, `activeAudioDevice` match ref `State` (:73-75) exactly, both `var`. **PASS at field level.**
Missing store fields (ref :34-48): `DEFAULT_STATE` :34, `audioManagerHandler: Handler?` :35, `audioManagerThread: HandlerThread` :36, `channelsStore` :37, `context: Context?` :38, `dispatcher` :39, `experimentsStore` :40, `lastActiveAudioDevice: DiscordAudioManager.AudioDevice?` :41, `observationDeck` :42, `prevMyVideoOn: boolean` :43, `state` :44, `stateSnapshot` :45, `streamRtcConnectionStore` :46, `videoUseDetector: VideoUseDetector` :47, `voiceChannelSelectedStore` :48. Missing nested `VideoUseDetector` class :185.
Missing methods: `getAudioManagerState` :269, `updateActiveAudioDevice` :275, `updateAudioDevices` :286, `updateCurrentMediaVolume` :292, `getLastActiveAudioDevice$` :297, `getState$` :301, `handleRtcConnectionState` :306, `handleVoiceStatesUpdated` :337, `init` :388, `observeAudioManagerState` :402, `selectOutputDevice` :406, `setState$` :468, `snapshotData` :474, `toggleSpeakerOutput` :484, `updateMediaVolume` :488. **BLOCKER.**

### 2f. StoreRtcConnection — **ABSENT** (ref 891 LOC)
Missing 30 fields incl. `connectionStateSubject: SerializedSubject<RtcConnection.StateChange,…>` :65, `qualitySubject: BehaviorSubject<RtcConnection.Quality>` :79, `rtcConnectionMetadataSubject: BehaviorSubject<RtcConnection.Metadata>` :82, `speakingUsersSubject: PublishSubject<SpeakingUserUpdate>` :86, `speakingUpdates` (300 ms buffer, `SPEAKING_UPDATES_BUFFER_MS` :61), `listenerSubject`/`listeners` :73-74, `currentVoiceState: OutgoingPayload.VoiceStateUpdate` :67, `voiceServer` :92, `sessionId` :84, `storeVoiceStates` :89, `storeRtcRegion` :88, `storeAnalytics` :87, `voicePropsTracker` :91, `dpc: DebugPrintableCollection` :70. Missing nested `Listener` :130, `DefaultListener` :109, `SpeakingUserUpdate` :143. Missing all handlers (`checkForVoiceServerUpdate` :275, `createRtcConnection` :310, `destroyRtcConnection` :319, `handleMediaSessionIdReceived` :331, `handleSelfMuted/Deafened/Video` :346-360, `handleUsersMuted/OffScreen/Volume` :367-402, `logChannelJoin/Leave` :414/:441, …). **BLOCKER.**

### 2g. StoreMediaEngine — **ABSENT** (ref 932 LOC)
Missing 24 fields incl. two **persistence handles**: `hasNativeEngineEverInitializedCache: Persister<Boolean>` :78 and `preferredVideoInputDeviceGuidCache: Persister<String>` :91; subjects `isNativeEngineInitializedSubject` :80, `localVoiceStatusSubject` :84, `onKrispStatusSubject: PublishSubject<KrispOveruseDetector.Status>` :88, `openSLESConfigSubject` :89, `pttActiveSubject` :93, `selectedVideoInputDeviceSubject` :95, `videoInputDevicesSubject` :99; `mediaEngineSettingsSubscription: Subscription` :86; constants `DEFAULT_VIDEO_DEVICE_GUID` :74, `MAX_WAIT_FOR_DEVICES_MS=750` :75, `LOCAL_VOICE_STATUS_DEFAULT` :101, `DEFAULT_OPENSLES_CONFIG` :102. Missing nested `EngineListener` :132, `Listener` :205, `DefaultListener` :119. Missing ~30 methods. **BLOCKER.**

### 2h. StoreCalls — **ABSENT** (ref 189 LOC)
Missing `calls: HashMap<Long, ModelCall>` :35, `callsSubject: SerializedSubject<HashMap<Long,ModelCall>,…>` :36, `connectionReady` :37, `selectedChannelId` :38, `selectedGuildId` :39, `stream` :40; methods `call` :105, `callConnect` :58, `callSubjectUpdate` :69, `findCall` :83, `get` :127, `handleCallCreateOrUpdate` :140, `handleCallDelete` :152, `handleChannelSelect` :161, `handleConnectionOpen` :166, `handleConnectionReady` :172, `handleGuildSelect` :177, `ring` :182, `stopRinging` :186. **BLOCKER.**

### 2i. StoreCallsIncoming — **ABSENT** (ref 105 LOC)
Missing `dispatcher` :17, `incomingCallSnapshot: Set<Long>` :18, `incomingCalls: Set<Long>` :19, `observationDeck` :20, `userStore` :21; methods `clearIncomingCall` :36, `getIncomingCalls` :43, `handleCallCreateOrUpdate` :48, `handleCallDelete` :63, `handleVoiceChannelSelected` :69, `observeHasIncoming` :73, `observeIncoming` :90, `removeIncomingCall` :96, `snapshotData` :101. **BLOCKER.**

### 2j. StoreVideoStreams — **ABSENT** (ref 242 LOC)
Missing `observationDeck` :21, `userStreamsByUser: HashMap<Long, UserStreams>` :22, `userStreamsByUserSnapshot` :23; nested `UserStreams` :28 (the type `VoiceUser.streams` should hold, see §1); methods `currentUserStreams` :160, `setUserStreams` :165, `findUserForApplicationStream` :175, `handleApplicationStreamUpdate` :187, `handleApplicationStreamVideoMetadataUpdate` :192, `handleVideoMetadataUpdate` :198, `handleVideoStreamUpdate` :204, `handleVoiceChannelSelected` :209, `handleVoiceStateUpdates` :217, `observeUserStreams` :226, `snapshotData` :231. **BLOCKER.**

### 2k. StoreApplicationStreaming — **DTO ONLY** (target `VoiceStoreContracts.kt:7-27`, ref 533 LOC)
`ActiveApplicationStream` (target `:8-11`) fields `state`, `stream` match ref :46-48.

> **BUG — `ACTIVE_STATES` mismatch.** Target `VoiceStoreContracts.kt:23`:
> `val ACTIVE_STATES = listOf(CONNECTING, ACTIVE, RECONNECTING, ENDED)`
> Reference `StoreApplicationStreaming.java` (`ActiveApplicationStream$State` static init, decompiled at offset :46+37..42):
> `ACTIVE_STATES = listOf(CONNECTING, ACTIVE, RECONNECTING, PAUSED)`
> Target substitutes **ENDED for PAUSED**. `isStreamActive()` therefore returns `true` for a stream that has *ended* and `false` for a *paused* one — inverted in both directions. **MAJOR, and it is a live logic bug reachable today** since `isStreamActive()` is exported.

Missing store fields (ref :28-41): `activeApplicationStream` :28, `breadCrumbs: ArrayDeque<String>` :29, `dispatcher` :30, `observationDeck` :31, `rtcConnectionStore` :32, `storeStream` :33, `streamSpectators: HashMap<String, List<Long>>` :34, `streamSpectatorsSnapshot` :35, `streamViewerTracker: StreamViewerTracker` :36, `streamsByUser: HashMap<Long, ModelApplicationStream>` :37, `streamsByUserSnapshot` :38, `targetStream: ModelApplicationStream?` :39, `userStore` :40, `voiceChannelSelectedStore` :41. Missing nested `StreamViewerTracker` :165.
Missing methods: `addBreadCrumb` :193, `dumpBreadcrumbs` :209, `handleStreamCreateOrUpdate` :216, `stopStreamInternal` :236, `updateActiveApplicationStream` :248, `createStream` :257, `getActiveApplicationStreamKeyInternal$` :262, `getMaxViewersForStream` :272, `getStreamsByUser` :282, `handleConnectionOpen` :287, `handleStreamCreate` :310, `handleStreamCreateRequest` :317, `handleStreamDelete` :324, `handleStreamTargeted` :354, `handleStreamUpdate` :370, `handleStreamWatch` :376, `handleVoiceChannelSelected` :383, `handleVoiceStateUpdate` :403, `isScreenSharing` :444/:530, `isUserStreaming` :449, `observeActiveStream` :454, `observeStreamSpectators` :458, `observeStreamsByUser` :464, `observeStreamsForGuild` :470, `observeStreamsForUser` :476, `snapshotData` :484, `stopStream` :498, `targetStream` :503. **BLOCKER.**

---

## 3. `stores/StoreApplicationStreamPreviews.kt` vs `stores/StoreApplicationStreamPreviews.java`

**STUB DETECTION: CONTRACT ONLY.** Target (`:3`) is `object StoreApplicationStreamPreviews` holding a `sealed interface StreamPreview` with `Fetching` / `Resolved(url: String?)`. Reference is `StoreApplicationStreamPreviews extends StoreV2`.

Nested-type parity: `StreamPreview.Fetching` (target `:5` vs ref :62) and `StreamPreview.Resolved(url: String?)` (target `:6` vs ref :73-74) — **PASS**, incl. nullable `url`.

### MISSING FIELDS
| Reference | Type | Target |
|---|---|---|
| `dispatcher` (ctor :237) | `Dispatcher` | **MISSING** |
| `clock` (ctor :238) | `Clock` | **MISSING** |
| `restAPI` :38 | `RestAPI` (default `RestAPI.Companion.getApi()`, :129) | **MISSING** |
| `observationDeck` :37 | `ObservationDeck` (default `ObservationDeckProvider.get()`) | **MISSING** |
| `streamKeyToPreviewMap` :39 | `Map<String, StreamPreview>` = `HashMap()` (:241) | **MISSING** (the cache) |
| `streamKeyToPreviewMapSnapshot` :40 | `Map<String, StreamPreview>` = `emptyMap()` (:242) | **MISSING** (the snapshot) |
| `fetchAttempts` (:243) | `HashMap<String, Integer>` | **MISSING** (retry-backoff index) |
| `fetchStreamPreviewSubscriptions` :36 | `HashMap<String, Subscription>` | **MISSING** (in-flight handle map) |
| `READ_PREVIEW_DEFAULT_RETRY_DELAY_MS` (Companion :43) | `long` | **MISSING** |

### MISSING METHODS
`fetchStreamPreview` :132; `fetchStreamPreviewIfNotFetching(StreamContext)` :141; `fetchStreamPreviewIfNotFetching(String)` :249; `fetchStreamPreviewIfNotFetching(StreamPreview?, String)` :253; `getStreamKeyToPreviewMap()` :146; `handleFetchStart` :198; `handleFetchSuccess` :205; `handleFetchFailed` :151 (incl. the whole HTTP-429/`retryAfterMs`, 401/403-terminal, `attempts * 10 000 ms` backoff ladder); `observeStreamPreview(ModelApplicationStream)` :213; `observeStreamPreview(String)` :225; `snapshotData` :220. **All 11 missing.**

**SEVERITY: BLOCKER.** No fetching, no caching, no retry, no observation deck registration.

---

## 4. `stores/StickerAnimationSettingsStore.kt` vs reference equivalent

**Reference equivalent located:** there is no `StickerAnimationSettingsStore` class in the reference. The functionality lives in `stores/StoreUserSettings.java`:
- field `stickerAnimationSettingsPublisher: Persister<Integer>` :59, constructed `new Persister<>("CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1", 0)` :101
- `getStickerAnimationSettings()` :281
- `observeStickerAnimationSettings(boolean respectReducedMotion)` :411
- `setStickerAnimationSettings(AppActivity, int)` :586
- reduced-motion source = `StoreAccessibility.observeReducedMotionEnabled()` (:414), backed by `Persister<Boolean>("CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED", false)` (`StoreAccessibility.java:136`)

**STUB DETECTION: NOT a stub — but a DUPLICATE SHADOW STORE.**
The target *already* has the faithful port in `stores/StoreUserSettings.kt:34` (`Persister("CACHE_KEY_STICKER_ANIMATION_SETTINGS_V1", 0)`), `:66 getStickerAnimationSettings`, `:87 observeStickerAnimationSettings`, `:147 setStickerAnimationSettings`, `:109` hydration from `USER_SETTINGS_UPDATE`; and reduced motion in `stores/StoreAccessibility.kt` (`Persister("CACHE_KEY_ACCESSIBILITY_REDUCED_MOTION_ENABLED", false)`). `StickerAnimationSettingsStore.kt` re-reads **the same two cache keys directly via `context.getSharedPreferences("discord_user_settings", MODE_PRIVATE)`** (`:19`, `:35`, `:42`), bypassing `Persister`.

### DEFECTS
1. **Two writers, one key set.** `StickerAnimationSettingsStore.setAnimationSetting` (`:32`) and `setReducedMotion` (`:40`) write the same prefs keys that `Persister` owns. Whichever path writes last wins; the other side's `BehaviorSubject`/`Persister` observable never sees the change. `StoreAccessibility.snapshotData()` calls `reducedMotionCache.set(...)` on every snapshot, so it will silently stomp `StickerAnimationSettingsStore.setReducedMotion`. **MAJOR.**
2. **`initialized` flag (`:12`) is a one-shot latch** — after first read the subjects never re-sync from prefs, so writes made through `StoreUserSettings`/`StoreAccessibility` are invisible to `StickerAnimationSettingsStore.observe()` for the process lifetime. Consumer: `views/sticker/StickerView.kt:86`. **MAJOR.**
3. **MISSING: REST sync.** Reference `setStickerAnimationSettings` :586-592 calls `RestAPIParams.UserSettings.createWithStickerAnimationSettings` when `getIsSyncTextAndImagesEnabled()`. Neither target path does this — `StoreUserSettings.kt:147` only sets the Persister. **MAJOR.**
4. **MISSING: `respectReducedMotion` parameter.** Reference `observeStickerAnimationSettings(boolean)` :411 makes it opt-in; target `StickerAnimationSettingsStore.observe(context)` (`:25`) always applies it, no opt-out. **MINOR.**
5. **Reduced-motion mapping disagreement between the two target copies.** Reference :417-421 maps `setting == 0` → `1` (ANIMATE_ON_INTERACTION) and leaves 1/2 untouched. `StickerAnimationSettingsStore` → `StickerUtils.effectiveAnimationSetting` (`utilities/stickers/StickerUtils.kt:49-50`) implements exactly that — **correct**. But `StoreUserSettings.kt:88-90` maps *any* setting → `STICKER_ANIMATION_NEVER_ANIMATE` when reduced motion is on — **wrong vs reference**, and contradicts the sibling implementation. **MAJOR** (defect is in `StoreUserSettings.kt:89`, surfaced by this pair).

Persistence-handle mismatch: reference uses `Persister` (its own prefs file/serialization); target's shadow store hardcodes `"discord_user_settings"` (`StickerAnimationSettingsStore.kt:9`). I could not read `utilities/persister/Persister.kt` prefs name in the target (grep for `getSharedPreferences` returned nothing in that file) — **cannot confirm the two paths actually address the same SharedPreferences file.** If they don't, the shadow store reads a permanently-empty namespace and always returns the defaults. Flagging as unverified.

**SEVERITY: MAJOR.** (Not BLOCKER: a faithful implementation exists elsewhere in the target; the issue is duplication + divergence.)

---

## 5. `stores/NotificationPolicy.kt` vs reference equivalent

**No `NotificationPolicy` class exists in the reference.** Verified by `grep -rln "NotificationPolicy"` over the whole reference tree → 0 hits. It is a target-invented abstraction.

The reference behaviour it models is split across:
- `stores/StoreNotifications.java` — `handleMessageCreate(Message)` :204, `displayPopup(Message, Channel)` :139, `handleChannelSelected(long)` :193, `INAPP_MESSAGE_WINDOW_MS = 10000` :50, `notificationSettings: Persister<NotificationClient.SettingsV2>` :54
- `stores/StoreMentions.java` — `hasMention(...)` :110, `isMentionableChannel(long)` :171, `computeMentionCounts()` :69
- `utilities/fcm/NotificationClient.SettingsV2` :235-244 (`isEnabled`, `isEnabledInApp`, `isDisableBlink`, `isDisableSound`, `isDisableVibrate`, `isWake`, `isAuthed`, `locale`, `token`)

> ### JADX OUTPUT IS BROKEN — CANNOT VERIFY
> `StoreNotifications.handleMessageCreate` (`StoreNotifications.java:197-210`) decompiled to:
> ```
> /* JADX WARN: Removed duplicated region for block: B:32:0x00d1 */
> Method dump skipped, instructions count: 332
> throw new UnsupportedOperationException("Method not decompiled: …handleMessageCreate…");
> ```
> This is the single method that contains the reference notification-delivery decision. **I will not guess its contents.** Every claim below about `NotificationPolicy.evaluate` correctness is therefore *unverifiable against the reference* and must be re-checked against a working decompile or smali.

### What CAN be stated
- **STUB DETECTION: not a stub.** Target `NotificationPolicy.kt` is a real, fully-implemented pure function (`evaluate` :29, `isAccessible` :63, `isMention` :71) with a 12-field `Input` (`:14-27`). It is wired: `stores/StoreNotifications.kt:59-62`.
- Structural mismatch: the reference has **no separate policy type**; the decision is inline in a `StoreV2` subclass with access to live stores. The target's `Input` (`:14-27`) requires the *caller* to pre-resolve `effectivePermissions`, `currentUserRoleIds`, `guildSettings`, `relationshipType`, `selectedChannelId`, `isDnd` — a materially different contract with 12 opportunities for the call site to pass a stale/wrong value. **MAJOR (architectural divergence).**
- **MISSING: the 10-second in-app window.** Reference `INAPP_MESSAGE_WINDOW_MS = 10000` (`StoreNotifications.java:50`) has no counterpart anywhere in target `NotificationPolicy.kt` or `StoreNotifications.kt`. **MAJOR.**
- **MISSING: `NotificationClient.SettingsV2` sound/vibrate/blink/wake gating.** Target `Delivery` (`:12`) has only `FOREGROUND`/`SYSTEM`; the four presentation flags (`isDisableSound`, `isDisableVibrate`, `isDisableBlink`, `isWake`, ref :236-241) are not modelled in the policy output. **MINOR** (they may be applied downstream in the renderer — not checked).
- **MISSING: mention-count coupling.** Reference routes through `StoreMentions.hasMention` :110 / `isMentionableChannel` :171, which additionally consults `StoreThreadsJoined` (:60) and `StoreMessageAck` (:58). Target `isMention` (`:71-77`) consults only the message payload + `guildSettings` suppress flags — **no thread-joined or ack check**. **MAJOR.**
- **MINOR:** target `isAccessible` (`:63-69`) hardcodes `Channel.GUILD_VOICE`/`GUILD_STAGE_VOICE` requiring `CONNECT`; ref `StoreMentions.isMentionableChannel` :171 exists but its body was not read line-by-line — parity unconfirmed.

**SEVERITY: MAJOR (partly unverifiable — see JADX note).**

---

## 6. Gateway store: target vs `stores/StoreGatewayConnection.java`

### Where the target implements it
There is **no `StoreGatewayConnection` in the target.** The behaviour is split three ways:
- `gateway/GatewaySocket.kt` (389 LOC) — the socket itself (target-side reimplementation; the reference `GatewaySocket` lives in a separate `gateway_release` module and is *not* the reference for this pair)
- `gateway/GatewayEventRouter.kt` (21 LOC) — implements `GatewayEventHandler`, forwards straight to `StoreStream`
- `stores/StoreStream.kt:553-702` — `handleGatewayDispatch(type, data)`, one giant `when` that calls store handlers **directly**

**STUB DETECTION: the socket is real; the *store* is absent.** The reference's entire fan-out layer — 79 `SerializedSubject` fields (`StoreGatewayConnection.java:115-202`) that decouple socket→stores — has been **replaced by direct method calls**. That is an architectural collapse, not a stub, but it deletes every subject, every getter, and the `BatchManager` batching.

### MISSING FIELDS (reference `StoreGatewayConnection.java:115-202`)
All 79 `SerializedSubject`/`Batched` fields are **MISSING**. Notably:
- `connected: SerializedSubject<Boolean,Boolean>` :126 seeded `false` (:375) + `getConnected()` :583 — target has `StoreStream.handleGatewayConnected` but no observable field enumerated here
- `connectionReady` :127 seeded `false` (:376) + `getConnectionReady()` :589
- `voiceStateUpdate: Batched<VoiceState>` :202 — **the only batched subject**, `BatchManager.createBatched(batchManager, "VOICE_STATE_UPDATE", …)` :423. Target has no `BatchManager` at all. **BLOCKER.**
- `guildBanAdd` :136 / `guildBanRemove` :137 / `guildIntegrationsUpdate` :141 are `PublishSubject`-backed (:386, :387, :401) while all others are `BehaviorSubject`-backed — replay semantics differ per event; target preserves none of this.
- Infrastructure fields: `batches: BatchManager` :116, `clientState: ClientState?` :124, `clock: Clock` :125, `coroutineDispatcher: ExecutorCoroutineDispatcher` :128, `executor: ExecutorService` :130 (default `newFixedThreadPool(1)`, :1519), `scheduler: Scheduler` :175 (default `Schedulers.from(executor)`, :1527), `gatewaySocketLogger: AppGatewaySocketLogger` :134, `socket: GatewaySocket?` :177, `stream: StoreStream` :181 — **all MISSING**.
- Nested `ClientState` (:207) with `tokenIfAvailable: String?`, `authed: Boolean`, `clientDataState: StoreClientDataState.ClientDataState`, `getIdentifyData()` :313, `Companion.create(...)` :220, `Companion.initialize(stream, scheduler, callback)` :228 — **MISSING**. The target's `GatewaySocket` takes an `identifyDataProvider: () -> IdentifyData?` lambda (`GatewaySocket.kt:69`) with no equivalent 5-source combineLatest (backgrounded × authedToken × selectedVoiceChannelId × gatewayConnectionConsumers × clientDataState, :232-249). This means **the target never closes the socket when backgrounded with no voice channel and no consumers** (ref :220-226 nulls the token in that case). **BLOCKER.**

### MISSING METHODS
| Reference | Line | Target |
|---|---|---|
| `init(context, networkMonitor)` | :1424 | **MISSING** (no `NetworkMonitor` wiring anywhere in target gateway) |
| `buildGatewaySocket` | :456 | **MISSING** (no analytics/logging interceptors, no `SSLSocketFactory`, no `AnalyticSuperProperties`, no `ConnectionTimeStats.addListener` :468) |
| `handleClientStateUpdate(ClientState)` | :480 | **MISSING** |
| `getIdentifyData()` | :472 | partially — lambda in `GatewaySocket.kt:69` |
| `handleRtcConnectionStateChanged(StateChange)` | :1410 | **MISSING** (drives `streamPing`/`voiceServerPing`) |
| `handlePreLogout()` | :1406 | **MISSING** — ref sends a null `voiceStateUpdate` to leave voice on logout |
| `presenceUpdate(status, since, activities, afk)` | :1431 | present (`GatewaySocket.kt:127`) but **no `clock.currentTimeMillis()` default for `since`** (ref :503) |
| `requestApplicationCommands(...)` | :1435 | **MISSING** (opcode 24 declared but unused) |
| `requestForumUnreads(...)` | :1440 | **MISSING** (opcode 28 declared but unused) |
| `requestGuildMembers(...)` ×4 overloads | :1445-1457 | **MISSING** (opcode 8 declared but unused; default limit 100, :518) |
| `resetOnError()` | :1461 | **MISSING** |
| `simulateReconnectForTesting()` | :1470 | **MISSING** |
| `streamCreate(streamKey, ...)` | :1478 | **MISSING** (opcode 18 unused) |
| `streamDelete(streamKey)` | :1483 | **MISSING** (opcode 19 unused) |
| `streamWatch(streamKey)` | :1489 | **MISSING** (opcode 20 unused) |
| `streamPing(streamKey)` | :535 | **MISSING** (opcode 21 unused) |
| `voiceServerPing()` | :539 | **MISSING** (opcode 5 unused) |
| `callConnect(channelId)` | :547 | **MISSING** (opcode 13 unused) |
| `voiceStateUpdate(...)` | :1504 | **MISSING** (opcode 4 unused) |
| `updateGuildSubscriptions(guildId, GuildSubscriptions)` | :1494 | partial — `GatewaySocket.kt:123` sends a `GuildSubscriptionPayload`, but ref sends per-guild `OutgoingPayload.GuildSubscriptions(serializedRanges, typing, activities, members, threads, threadMemberLists)` :1501. Field-set parity **not confirmed**. |
| `requestIfSessionEstablished(fn)` | :523 | equivalent inline guard `state == State.CONNECTED` |
| 79 `getXxx()` subject accessors | :551-583+ | **ALL MISSING** |

`gateway/io/GatewayPayloads.kt` contains only `Incoming`, `Outgoing`, `Hello`, `IdentifyClientState`, `Identify`, `Resume`, `PresenceUpdate` — **no outgoing payload types** for voice-state-update, call-connect, stream-create/delete/watch/ping, voice-server-ping, request-guild-members, request-application-commands, or request-forum-unreads. 9 of the 21 declared opcodes are dead.

`gateway/opcodes/Opcode.kt` vs `gateway/opcodes/Opcode.java`: **exact match**, all 21 constants and int values. **PASS.**

### GATEWAY EVENT TABLE — reference dispatch → target handler

Reference source: `StoreGatewayConnection.handleDispatch` switch, `StoreGatewayConnection.java:901-1403` (83 distinct event names).
Target source: `StoreStream.handleGatewayDispatch`, `stores/StoreStream.kt:553-701`; parse layer `gateway/io/IncomingParser.kt:55-90`.

| # | Reference event | Reference sink (subject) | Target handler | Parsed? |
|---|---|---|---|---|
| 1 | READY | `ready` :1204 | `StoreStream.kt:555` → `handleConnectionOpenInternal` | yes :56 |
| 2 | USER_SETTINGS_UPDATE | `userSettingsUpdate` :1084 | `StoreStream.kt:556` | yes :57 |
| 3 | USER_GUILD_SETTINGS_UPDATE | `userGuildSettingsUpdate` :1294 | `StoreStream.kt:563` | yes :58 |
| 4 | USER_UPDATE | `userUpdate` :1234 | `StoreStream.kt:568` | yes :59 |
| 5 | USER_NOTE_UPDATE | `userNoteUpdate` :1060 | `StoreStream.kt:572` | yes :77 |
| 6 | USER_REQUIRED_ACTION_UPDATE | `userRequiredActionUpdate` :1000 | **MISSING** | no |
| 7 | USER_CONNECTIONS_UPDATE | `userConnectionUpdate` (Unit) :1306 | **MISSING** | no |
| 8 | USER_PAYMENT_SOURCES_UPDATE | `userPaymentSourcesUpdate` (Unit) :1132 | **MISSING** (listed in `GatewaySocket.kt:387` `EXPECTED_NULL_DATA_EVENTS` but no store case) | n/a |
| 9 | USER_SUBSCRIPTIONS_UPDATE | `userSubscriptionsUpdate` (Unit) :1054 | **MISSING** (same as above) | n/a |
| 10 | FRIEND_SUGGESTION_CREATE | `friendSuggestionCreate` :1186 | `StoreStream.kt:573` | yes :75 |
| 11 | FRIEND_SUGGESTION_DELETE | `friendSuggestionDelete` :1198 | `StoreStream.kt:574` | yes :76 |
| 12 | RELATIONSHIP_ADD | `relationshipAdd` :1012 | `StoreStream.kt:644` | yes :74 |
| 13 | RELATIONSHIP_REMOVE | `relationshipRemove` :1102 | `StoreStream.kt:648` | yes :74 |
| 14 | SESSIONS_REPLACE | `sessionsReplace` :922 | `StoreStream.kt:643` | yes :73 |
| 15 | PRESENCE_UPDATE | `presenceUpdate` :1066 | `StoreStream.kt:635` | yes :71 |
| 16 | PRESENCES_REPLACE | `presenceReplace` :1349 | `StoreStream.kt:639` | yes :72 |
| 17 | GUILD_CREATE | `guildCreate` :1138 | `StoreStream.kt:575` | yes :60 |
| 18 | GUILD_UPDATE | `guildUpdate` :1222 | `StoreStream.kt:575` | yes :60 |
| 19 | GUILD_DELETE | `guildDeleted` :1144 | `StoreStream.kt:576` | yes :60 |
| 20 | GUILD_MEMBER_ADD | `guildMembersAdd` :1032 | `StoreStream.kt:577` | yes :61 |
| 21 | GUILD_MEMBER_UPDATE | `guildMembersAdd` :1194 | `StoreStream.kt:577` | yes :61 |
| 22 | GUILD_MEMBER_REMOVE | `guildMemberRemove` :1174 | `StoreStream.kt:585` | yes :62 |
| 23 | GUILD_MEMBERS_CHUNK | `guildMembersChunk` :958 | `StoreStream.kt:593` | yes :63 |
| 24 | GUILD_MEMBER_LIST_UPDATE | `guildMemberListUpdate` :1048 | `StoreStream.kt:625` | yes :67 |
| 25 | GUILD_ROLE_CREATE | `guildRoleCreate` :1300 | `StoreStream.kt:605` | yes :64 |
| 26 | GUILD_ROLE_UPDATE | `guildRoleUpdate` :1391 | `StoreStream.kt:612` | yes :65 |
| 27 | GUILD_ROLE_DELETE | `guildRoleDelete` :1313 | `StoreStream.kt:619` | yes :66 |
| 28 | GUILD_BAN_ADD | `guildBanAdd` (Publish) :1024 | **MISSING** | no |
| 29 | GUILD_BAN_REMOVE | `guildBanRemove` (Publish) :1337 | **MISSING** | no |
| 30 | GUILD_EMOJIS_UPDATE | `guildEmojisUpdate` :1276 | **MISSING** | no |
| 31 | GUILD_STICKERS_UPDATE | `guildStickersUpdate` :1006 | **MISSING** | no |
| 32 | GUILD_INTEGRATIONS_UPDATE | `guildIntegrationsUpdate` (Publish) :1078 | **MISSING** | no |
| 33 | GUILD_APPLICATION_COMMANDS_UPDATE | `guildApplicationCommands` :1090 | **MISSING** | no |
| 34 | GUILD_JOIN_REQUEST_CREATE | `guildJoinRequestCreateOrUpdate` :912 | **MISSING** | no |
| 35 | GUILD_JOIN_REQUEST_UPDATE | `guildJoinRequestCreateOrUpdate` :972 | **MISSING** | no |
| 36 | GUILD_JOIN_REQUEST_DELETE | `guildJoinRequestDelete` :916 | **MISSING** | no |
| 37 | GUILD_SCHEDULED_EVENT_CREATE | `guildScheduledEventCreate` :928 | **MISSING** | no |
| 38 | GUILD_SCHEDULED_EVENT_UPDATE | `guildScheduledEventUpdate` :988 | **MISSING** | no |
| 39 | GUILD_SCHEDULED_EVENT_DELETE | `guildScheduledEventDelete` :934 | **MISSING** | no |
| 40 | GUILD_SCHEDULED_EVENT_USER_ADD | `guildScheduledEventUserAdd` :1361 | **MISSING** | no |
| 41 | GUILD_SCHEDULED_EVENT_USER_REMOVE | `guildScheduledEventUserRemove` :994 | **MISSING** | no |
| 42 | CHANNEL_CREATE | `channelCreateOrUpdate` :1110 | `StoreStream.kt:627` | yes :68 |
| 43 | CHANNEL_UPDATE | `channelCreateOrUpdate` :1170 | `StoreStream.kt:627` | yes :68 |
| 44 | CHANNEL_DELETE | `channelDeleted` :1114 | `StoreStream.kt:629` | yes :68 |
| 45 | CHANNEL_UNREAD_UPDATE | `channelUnreadUpdate` :1162 | `StoreStream.kt:695` | yes :86 |
| 46 | CHANNEL_RECIPIENT_ADD | `channelRecipientAdd` :1258 | **MISSING** | no |
| 47 | CHANNEL_RECIPIENT_REMOVE | `channelRecipientRemove` :1120 | **MISSING** | no |
| 48 | THREAD_CREATE | `threadCreateOrUpdate` :1272 | `StoreStream.kt:627` | yes :69 |
| 49 | THREAD_UPDATE | `threadCreateOrUpdate` :1369 | `StoreStream.kt:627` | yes :69 |
| 50 | THREAD_DELETE | `threadDelete` :1288 | `StoreStream.kt:629` | yes :69 |
| 51 | THREAD_LIST_SYNC | `threadListSync` :1385 | `StoreStream.kt:630` | yes :70 |
| 52 | THREAD_MEMBER_UPDATE | `threadMemberUpdate` :940 | **MISSING** | no |
| 53 | THREAD_MEMBERS_UPDATE | `threadMembersUpdate` :1216 | **MISSING** | no |
| 54 | THREAD_MEMBER_LIST_UPDATE | `threadMemberListUpdate` :952 | **MISSING** | no |
| 55 | MESSAGE_CREATE | `messageCreate` :1246 | `StoreStream.kt:650` | yes :79 |
| 56 | MESSAGE_UPDATE | `messageUpdate` :1319 | `StoreStream.kt:663` | yes :79 |
| 57 | MESSAGE_DELETE | `messageDelete` :1254 | `StoreStream.kt:688` | yes :80 |
| 58 | MESSAGE_DELETE_BULK | `messageDelete` :1182 | `StoreStream.kt:688` | yes :80 |
| 59 | MESSAGE_ACK | `messageAck` :1282 | `StoreStream.kt:649` | yes :78 |
| 60 | MESSAGE_REACTION_ADD | `messageReactionAdd` :1042 | `StoreStream.kt:669` | yes :81 |
| 61 | MESSAGE_REACTION_REMOVE | `messageReactionRemove` :1264 | `StoreStream.kt:672` | yes :81 |
| 62 | MESSAGE_REACTION_REMOVE_ALL | `messageReactionRemoveAll` :976 | `StoreStream.kt:675` | yes :82 |
| 63 | MESSAGE_REACTION_REMOVE_EMOJI | `messageReactionRemoveEmoji` :904 | `StoreStream.kt:678` | yes :83 |
| 64 | TYPING_START | `typingStart` :1126 | `StoreStream.kt:681` | yes :84 |
| 65 | CALL_CREATE | `callCreateOrUpdate` :1152 | **MISSING** | no |
| 66 | CALL_UPDATE | `callCreateOrUpdate` :1230 | **MISSING** | no |
| 67 | CALL_DELETE | `callDelete` :1156 | **MISSING** | no |
| 68 | VOICE_STATE_UPDATE | `voiceStateUpdate` (**Batched**) :1355 | **MISSING** | no |
| 69 | VOICE_SERVER_UPDATE | `voiceServerUpdate` :1373 | **MISSING** | no |
| 70 | STREAM_CREATE | `streamCreate` :1331 | **MISSING** | no |
| 71 | STREAM_UPDATE | `streamUpdate` :1397 | **MISSING** | no |
| 72 | STREAM_DELETE | `streamDelete` :1343 | **MISSING** | no |
| 73 | STREAM_SERVER_UPDATE | `streamServerUpdate` :1210 | **MISSING** | no |
| 74 | STAGE_INSTANCE_CREATE | `stageInstanceCreate` :1018 | `StoreStream.kt:698` | yes :87 |
| 75 | STAGE_INSTANCE_UPDATE | `stageInstanceUpdate` :1096 | `StoreStream.kt:699` | yes :87 |
| 76 | STAGE_INSTANCE_DELETE | `stageInstanceDelete` :1036 | `StoreStream.kt:700` | yes :87 |
| 77 | INTERACTION_CREATE | `interactionCreate` :1072 | **MISSING** | no |
| 78 | INTERACTION_SUCCESS | `interactionSuccess` :1240 | **MISSING** | no |
| 79 | INTERACTION_FAILURE | `interactionFailure` :1325 | **MISSING** | no |
| 80 | INTERACTION_MODAL_CREATE | `interactionModalCreate` :1379 | **MISSING** | no |
| 81 | APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE | `applicationCommandAutocompleteResult` :946 | **MISSING** | no |
| 82 | EMBEDDED_ACTIVITY_UPDATE | `embeddedActivityInboundUpdate` :964 | **MISSING** | no |
| 83 | FORUM_UNREADS | `forumUnreads` :982 | **MISSING** | no |

**Totals: 44 / 83 routed (53 %). 39 MISSING.**

Extra in target, absent from the reference switch: `CHANNEL_PINS_UPDATE` (`StoreStream.kt:685`, parsed `IncomingParser.kt:85`). The reference has no `channelPinsUpdate` subject and no case for it — this is a target addition, not a regression, but it diverges from 126.21.

### Other gateway-layer notes
- `GatewaySocket.kt:295` `EXPECTED_NULL_DATA_EVENTS = {USER_SUBSCRIPTIONS_UPDATE, USER_PAYMENT_SOURCES_UPDATE}` — these are forwarded to the handler as `Unit` but `StoreStream.handleGatewayDispatch` has no case for either, so they are silently dropped. **MINOR.**
- `GatewayEventRouter.handleDisconnect` (`:17-19`) only reacts when `authenticationFailed`; reference `handleDisconnect` :888-891 is identical (`logout()`). **PASS.**
- Reference `handleConnected`/`handleConnectionReady` push to seeded `BehaviorSubject`s (:375-376) so late subscribers get `false`; target forwards to `StoreStream` methods — replay semantics not verified. **MINOR.**

**SEVERITY: BLOCKER.**

---

## Top 10 gaps (ranked)

1. **BLOCKER** — 39 of 83 gateway events are never routed (`StoreStream.kt:553-701`), incl. the entire voice/call/stream/interaction/thread-member/scheduled-event surface. Table §6.
2. **BLOCKER** — 8 reference stores absent entirely from the target: `StoreVoiceStates`, `StoreVoiceSpeaking`, `StoreVoiceChannelSelected`, `StoreRtcConnection`, `StoreMediaEngine`, `StoreCalls`, `StoreCallsIncoming`, `StoreVideoStreams` (~3 200 LOC).
3. **BLOCKER** — `StoreVoiceParticipants` reduced to an interface: all 7 methods + `selectedParticipantSubject` + 13 derived `VoiceUser` fields gone (`StoreVoiceParticipants.kt:8`).
4. **BLOCKER** — `StoreApplicationStreamPreviews` reduced to a sealed interface: cache, snapshot, `fetchAttempts`, `fetchStreamPreviewSubscriptions`, all 11 methods, and the 429/401/403 retry ladder gone (`StoreApplicationStreamPreviews.kt:3`).
5. **BLOCKER** — `StoreGatewayConnection.ClientState` + `Companion.initialize` (ref :207-349) missing; the 5-source backgrounded/token/voice-channel/consumers/clientDataState gate that closes the socket never runs.
6. **BLOCKER** — `BatchManager` and the `Batched<VoiceState>` VOICE_STATE_UPDATE batching (ref :116, :202, :423) have no target counterpart; `flushBatches(str)` (ref :897) is also gone.
7. **MAJOR (live bug)** — `VoiceStoreContracts.kt:23` `ACTIVE_STATES` lists `ENDED` where the reference lists `PAUSED`; `isStreamActive()` is inverted for both ENDED and PAUSED streams.
8. **MAJOR (live bug)** — `StoreVoiceParticipants.VoiceUser.isMuted`/`isDeafened` (`StoreVoiceParticipants.kt:20-21`) drop the me-user override: reference reads `VoiceConfiguration.isSelfMuted()/isSelfDeafened()` when `isMe` (`StoreVoiceParticipants.java:96-122`). Local mute state will not reflect in the participant tile.
9. **MAJOR (live bug)** — `StoreUserSettings.kt:88-90` maps reduced-motion to `NEVER_ANIMATE`; reference `StoreUserSettings.java:417-421` maps `ALWAYS_ANIMATE`→`ANIMATE_ON_INTERACTION` only. Contradicts the target's own `StickerUtils.effectiveAnimationSetting` (`utilities/stickers/StickerUtils.kt:49`).
10. **MAJOR** — `StickerAnimationSettingsStore.kt` is a duplicate shadow store writing the same two prefs keys that `Persister` owns (`:19/:35/:42` vs `StoreUserSettings.kt:34` and `StoreAccessibility.kt` `reducedMotionCache`), with a one-shot `initialized` latch (`:12`) that permanently desyncs it. Also drops the REST `updateUserSettings` sync (ref `StoreUserSettings.java:586-592`).

## Unverifiable / JADX-broken (do not treat as verified)
- `StoreNotifications.handleMessageCreate` (`StoreNotifications.java:197-210`) — `Method dump skipped, instructions count: 332`. The whole reference notification-delivery decision is unreadable. §5's parity claims against `NotificationPolicy.evaluate` cannot be closed from this decompile.
- `StoreGatewayConnection.<init>` synthetic overload (`:1508-1541`) — `JADX WARN: Illegal instructions before constructor call`. Defaults for `executor`/`scheduler` were recovered from the readable smali-ish dump (:1519, :1527) and are stated with that caveat.
- Target `utilities/persister/Persister.kt` — grep for `getSharedPreferences` returned no hit, so the prefs file backing `Persister` was not identified. Whether `StickerAnimationSettingsStore`'s hardcoded `"discord_user_settings"` (`:9`) addresses the same file as `Persister` is **unconfirmed**.
