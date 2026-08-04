# Voice, media, and gateway stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: read-only JADX and Vineflower exports of Discord Android 126.21.
This supersedes the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Current classification | Open findings |
| --- | --- | ---: |
| `StoreVoiceParticipants` | PARTIAL | 1 MAJOR, 2 MINOR |
| voice/media store family | PARTIAL | 5 MAJOR, 5 MINOR |
| stream previews | PARTIAL | 1 MAJOR, 2 MINOR |
| sticker settings duplication | RESOLVED | 0 |
| notification policy | PARTIAL | see `audit-stores-auth-nav.md` |
| `StoreGatewayConnection` / socket | PARTIAL | 3 MAJOR, 4 MINOR |
| 83-event reference dispatch surface | STRUCTURALLY IMPLEMENTED | behavioral destinations not fully audited |
| **Total in this report** | **PARTIAL** | **10 MAJOR, 13 MINOR** |

No store in this report is deleted/declared complete because live voice and
device behavior remains unverified.

## 1. StoreVoiceParticipants

### Resolved since 2026-07-26

- This is now a concrete Store, not an interface shim
  (`StoreVoiceParticipants.kt:35-58`).
- `VoiceUser` contains and derives the reference participant fields: streams,
  me/member/context/booster/speaking, nickname, application stream, server/self
  mute/deafen, stage request state, display name and connection
  (`StoreVoiceParticipants.kt:60-117`).
- Ten-source participant creation, private/guild user resolution, stream-context
  fan-in, selected participant subject and channel projection are implemented
  (`StoreVoiceParticipants.kt:119-271`).
- StoreStream constructs it with real selected-channel and stream-context sources
  (`StoreStream.kt:702-714`).

### Open

- **MAJOR:** StoreStream never calls `voiceParticipants.init(context)`. The init
  subscription that clears a focused participant on voice-channel changes is
  therefore absent (`StoreVoiceParticipants.kt:249-254`; no production call site).
- **MINOR:** source throttles are 250 ms/1 s; exact 126.21 operator scheduler and
  intervals need comparison (`StoreVoiceParticipants.kt:216-224,262-263`).
- **MINOR:** private-call nickname resolution additionally consults target
  `NicknameUtils`; verify against reference recipient naming.

## 2. Voice/media store family

The July `VoiceStoreContracts` shim was replaced by concrete files:

- `StoreVoiceStates.kt`
- `StoreVoiceSpeaking.kt`
- `StoreVoiceChannelSelected.kt`
- `StoreMediaSettings.kt`
- `StoreAudioManagerV2.kt`
- `StoreRtcConnection.kt`
- `StoreMediaEngine.kt`
- `StoreCalls.kt`
- `StoreCallsIncoming.kt`
- `StoreVideoStreams.kt`
- `StoreApplicationStreaming.kt`
- `StoreStreamRtcConnection.kt`

`ACTIVE_STATES` is corrected to `CONNECTING, ACTIVE, RECONNECTING, PAUSED`
(`StoreApplicationStreaming.kt:66-70`). `VoiceConfiguration.toMediaEngineVoiceConfig`
exists and is consumed by VoiceTransport (`VoiceTransport.kt:140-155`).

`VoiceTransport` is the production composition root. It builds native media,
RTC, stream RTC and audio stores against real controllers, publishes them to UI,
and routes settings/native callbacks (`rtcconnection/VoiceTransport.kt:38-183`).
StoreStream installs it with NetworkMonitor, RTC region, stores and Gateway send
callbacks (`StoreStream.kt:936-1001`).

### Open major findings

- **MAJOR:** `StoreRtcConnection` is a controller-backed state machine rather
  than the reference Store subclass with its complete listener/debug/subject
  surface. It does not extend `Store`/`StoreV2` (`StoreRtcConnection.kt:33-53`),
  so its mutation and observation ordering must be validated independently.
- **MAJOR:** `StoreMediaEngine` is substantially smaller than the reference and
  relies on `VoiceTransport.MediaEngineControllerImpl`. Persistence/subject/
  Krisp/OpenSLES/PTT/video-device behavior is distributed across the two files;
  no fresh semantic-field inventory proves all reference state exists.
- **MAJOR:** `StoreAudioManagerV2`, media settings and RTC stores are split across
  VoiceTransport/controller seams. A method-presence count is not behavioral
  proof for device switching, reconnect, mute/deafen and teardown.
- **MAJOR:** StoreStream's `storesV2` adds `StoreStreamRtcConnection` and
  `StoreAudioManagerV2` after creating `StoreV2DispatchHandler`
  (`StoreStream.kt:769-880,976-978`). The list is mutable so additions are visible,
  but this unusual ordering needs a regression test that dirty voice stores are
  actually snapshotted/notified.
- **MAJOR:** no ARM64 or live-backend end-to-end call/Go Live validation covers
  join/move/reconnect, Bluetooth/wired switching, video, screen share, overlay,
  background and teardown. Existing native lifecycle evidence is x86_64.

### Minor structural divergences

- Several stores are plain `Store` or controller classes where the reference
  uses StoreV2/subjects, changing snapshot boundaries.
- VoiceTransport owns muted-user/volume maps rather than the same reference
  class.
- Media engine/audio initialization occurs in VoiceTransport construction rather
  than the reference deferred StoreStream init order.
- Target adds no-op controller defaults for JVM tests; accidentally constructing
  a store outside VoiceTransport can create inert behavior without failing.
- Connection timing listeners from reference `ConnectionTimeStats` remain absent
  (also tracked in `audit-stores-core.md`).

## 3. StoreApplicationStreamPreviews

### Resolved since 2026-07-26

- Concrete StoreV2, cache/snapshot, attempts, in-flight subscriptions, pull APIs,
  observations and 429/401/403/linear retry behavior now exist
  (`StoreApplicationStreamPreviews.kt:33-142`).
- StoreStream constructs, snapshots and publishes it
  (`StoreStream.kt:495,811,3057`). Fullscreen participant rows call fetch
  (`ParticipantsListItemGenerator.kt:102`).

### Open

- **MAJOR:** retry timers use default Rx timer scheduler
  (`StoreApplicationStreamPreviews.kt:109-116`) rather than an injected scheduler;
  clock/testing and process-background behavior are not deterministic.
- **MINOR:** default fetch resolves global `RestAPI.api` eagerly in the lambda
  (`StoreApplicationStreamPreviews.kt:33-39`) rather than nullable API handling.
- **MINOR:** terminal failures retain attempts/map entries indefinitely; compare
  cleanup/lifetime with reference after a stream ends.

## 4. Sticker animation settings

`StickerAnimationSettingsStore.kt` no longer exists. The duplicate shadow store,
hard-coded SharedPreferences namespace, two-writer race and stale one-shot latch
are resolved by deletion.

Canonical behavior now lives only in `StoreUserSettings`: same Persister key,
reduced-motion ALWAYS-to-ON_INTERACTION mapping and server sync
(`StoreUserSettings.kt:70-75,174-187,277-288`).

## 5. NotificationPolicy

Notification behavior is now covered in `audit-stores-auth-nav.md`, where the
formerly JADX-broken `StoreNotifications.handleMessageCreate` was re-audited
from Vineflower. It remains PARTIAL because target input lacks joined-thread and
selected-voice-channel state.

## 6. StoreGatewayConnection and GatewaySocket

### Resolved since 2026-07-26

- Concrete StoreGatewayConnection owns seeded connection state, typed event
  subjects, one-shot PublishSubjects and batched voice state
  (`StoreGatewayConnection.kt:97-264`).
- `ClientState` combines backgrounded, auth token, selected voice channel,
  connection consumers and StoreClientDataState; it closes/reopens the socket
  with session preservation (`StoreGatewayConnection.kt:104-138,277-314`).
- `BatchManager`/`Batched` coalesce `VOICE_STATE_UPDATE` and flush before the next
  dispatch (`StoreGatewayConnection.kt:140-143,239-244,459-467`).
- Outgoing voice/call/member/application-command/forum/stream/ping/subscription
  APIs are implemented (`StoreGatewayConnection.kt:316-433`) and GatewaySocket
  has their payloads/opcodes.
- Pre-logout leaves voice; hydrate reset and reconnect test hooks exist
  (`StoreGatewayConnection.kt:422-433`).
- Guild subscriptions are observed and sent (`StoreGatewayConnection.kt:296-300`).

### Event counts

- Reference dispatch surface: **83** event names.
- Current IncomingParser: **83/83 reference events typed**; no raw `JsonElement`
  fallback. It also recognizes current target additions such as auth session and
  embedded activity v2 events (`gateway/io/IncomingParser.kt`).
- Current StoreGatewayConnection has a typed `handleDispatch` case/subject for
  the reference surface (`StoreGatewayConnection.kt:459-596`).
- StoreStream installs buffered listeners for the reconstructed destinations
  (`StoreStream.kt:1481-1850`).

This is structural routing coverage. It does not prove every destination handler
matches reference semantics.

### Open

- **MAJOR:** StoreGatewayConnection accepts a prebuilt GatewaySocket from
  StoreStream instead of owning `buildGatewaySocket`; reference analytics,
  `ConnectionTimeStats`, SSL/config logging and init ordering are distributed or
  absent (`StoreGatewayConnection.kt:271-301`; `StoreStream.kt:1232-1259`).
- **MAJOR:** event destinations are incomplete where reference stores remain
  absent, notably client version, outbound promotions, dynamic links, library,
  guild voice regions and other gaps listed in the core inventory.
- **MAJOR:** target adds event routes absent from 126.21 (for example embedded
  activity v2 and auth-session change). These may be valid modern backend support
  but are not reference parity.
- **MINOR:** `presenceUpdate` converts null activities to empty
  (`StoreGatewayConnection.kt:325-332`). Reference preserves nullable activities
  through default arguments; clearing vs unchanged semantics may differ.
- **MINOR:** several subscription error handlers are empty
  (`StoreGatewayConnection.kt:283-300`), so lifetime failures can silently stop
  socket-state or guild-subscription updates.
- **MINOR:** fixed single-thread pool/coroutine dispatcher is disposed only by
  explicit `dispose`; StoreStream process lifetime currently owns it.
- **MINOR:** `CHANNEL_PINS_UPDATE`/other current additions are deliberate extra
  behavior and should not be counted among the 83 reference events.

## Decompiled-reference caveats

- `StoreNotifications.handleMessageCreate` is recovered from Vineflower; it is
  no longer left unverifiable in this report.
- StoreGatewayConnection constructor defaults and event subject choices were
  checked against Vineflower, not inferred from the broken JADX constructor.
- Voice store completeness is not inferred from class existence. The open
  semantic-field/runtime findings above remain until detailed per-store and
  device audits close them.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend checks ran.
- No test confirms StoreVoiceParticipants focus resets on channel move; current
  production wiring omits its init.
- No full destination-handler fixture table follows all 83 routed events through
  final stores.
- No ARM64/live call/video/screen-share regression matrix exists.
