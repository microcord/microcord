# Message stores re-audit - 2026-08-04

Target root: `app/src/main/java/com/discord/`

References: read-only JADX and Vineflower exports of Discord Android 126.21.
This supersedes the 2026-07-26 report. No production code was modified.

## Current result

| Surface | Classification | Open findings |
| --- | --- | ---: |
| `StoreMessages` and holders | PARTIAL | 1 BLOCKER, 5 MAJOR, 5 MINOR |
| `StoreMessagesLoader` | PARTIAL | 1 BLOCKER, 3 MAJOR, 3 MINOR |
| most-recent/state/uploads/pending/pins | PARTIAL | 3 MAJOR, 8 MINOR |
| `StoreChat` / editing | PARTIAL | 1 BLOCKER, 2 MAJOR, 2 MINOR |
| reactions/replies/slow mode | PARTIAL | 2 MAJOR |
| queue/request/result | PARTIAL | 2 MAJOR, 3 MINOR |
| **Total** | **PARTIAL** | **3 BLOCKER, 17 MAJOR, 21 MINOR** |

## 1. StoreMessages and holders

### Resolved since 2026-07-26

- StoreMessages now exposes connection/READY/interaction handlers, send,
  command send, resend/captcha resend, boot resend, captcha-failure sweep, edit,
  delete, local cancel and completion observation
  (`StoreMessages.kt:647-1301`).
- The installed `MessageSendCoordinator` owns the per-channel queues and is
  connected during StoreStream startup (`StoreMessages.kt:693-725`;
  `StoreStream.kt:1009-1022`). This is a composition change, not a missing queue.
- Holder identity, incremental snapshots, detached-channel rejection, reaction
  dedupe, null/ordered reaction maps, cache enablement, 60-second deduped
  persistence, active-channel guards, nonce reconciliation and Vineflower-derived
  detach/trim behavior are implemented (`StoreMessages.kt:62-451`).
- Boot restore uses the correct synthetic-default interpretation: both holders
  restore with cache enabled (`StoreMessages.kt:673-691`).
- Reconnect resumes queues and READY sets the current user
  (`StoreMessages.kt:733-754`).

### Open blockers

- **BLOCKER:** the send path documents three fields that remain local-only:
  explicit `mentions`, `application`, and `messageActivity` are attached to the
  optimistic row but absent from the queued request body
  (`StoreMessages.kt:894-959`). Sends using activity/application metadata can
  render optimistically and reach the server with different content.

### Other open findings

- **MAJOR:** StoreMessages owns a second projection layer (`messagesSnapshot` /
  StoreV2) over holders that already publish serialized subjects. Reference is a
  plain Store composed directly from holder streams; exact emission ordering is
  not established (`StoreMessages.kt:647-653,1233-1265`).
- **MAJOR:** target deliberately clears synchronized message history on logout
  (`StoreMessages.kt:884-890`; holder `:290-313`). Reference clears only locals.
  This is justified by account isolation but is a parity divergence.
- **MAJOR:** `getMessage`/observation can include local messages where reference
  synchronous lookup reads only the synced holder. Reply/edit callers need a
  focused contract check (`StoreMessages.kt:1267-1289`).
- **MAJOR:** local holder persistence uses target `Persister`/`LocalEntry` schema
  under the reference key rather than reference SharedPreferences/Gson. Existing
  126.21 local-message blobs are incompatible (`StoreMessages.kt:487-641`).
- **MAJOR:** the target coordinator is also observed by StoreStream and widgets
  (`StoreStream.kt:1069-1091`; `WidgetChatInput.kt:220`; incoming share). Store
  mutation is centralized in StoreMessages, but completion/progress consumers
  need a duplicate-side-effect audit.
- **MINOR:** nonce index includes channel id, intentionally strengthening the
  reference nonce-only key (`StoreMessages.kt:72-80`).
- **MINOR:** target natural `TreeMap<Long,...>` ordering is documented as DEX-
  equivalent, but local negative/synthetic id fixtures should pin it.
- **MINOR:** target reset/cancel helpers and multiple convenience overloads expand
  the reference API.
- **MINOR:** holder synchronization is stronger than reference subject-only
  confinement.
- **MINOR:** target error mapping routes through the global captcha interceptor,
  which intentionally differs from per-send 126.21 captcha ownership.

## 2. StoreMessagesLoader

### Resolved since 2026-07-26

- Behavior subjects replay chunks and scroll targets; loaded state has the five
  reference fields (`StoreMessagesLoader.kt:67-126`).
- Loader init observes StoreChat interaction and all detached channels, and
  StoreStream installs those dependencies (`StoreMessagesLoader.kt:156-165`;
  `StoreStream.kt:1334-1353`).
- Edge-driven before/after pagination, foreground/auth/connection gates,
  connection reset, voice-channel load, clear-scroll, jittered retry, newest
  behavior and the two-arm cross-channel jump flow are implemented
  (`StoreMessagesLoader.kt:167-658`).
- Vineflower was used for the formerly broken chunk-flag/newest-user-message
  derivation; current `complete` logic is no longer a JADX guess.

### Open

- **BLOCKER:** StoreStream never forwards background lifecycle to
  `messagesLoader.handleBackgrounded`; it forwards only to connectivity
  (`StoreStream.kt:1254-1258`). Loader therefore leaves `backgroundedOrNull` null
  and treats the process as foreground (`StoreMessagesLoader.kt:134-145`).
  Background message-history fetches are not suppressed.
- **MAJOR:** the initial reference default is backgrounded=true; target uses a
  nullable compatibility state specifically because wiring is absent. This is a
  second observable consequence of the same root cause, retained here as a
  design divergence rather than another blocker.
- **MAJOR:** loader subscriptions created in `init` are not retained or disposed
  (`StoreMessagesLoader.kt:161-165`); repeated initialization would duplicate
  pagination triggers.
- **MAJOR:** error paths log to `android.util.Log`, not app logging, and some
  subscriptions use empty error consumers.
- **MINOR:** target `LoadRequest`/`Direction` is a new request abstraction.
- **MINOR:** retry and reset logs/formats are not exact reference diagnostics.
- **MINOR:** synchronization replaces dispatcher confinement for much loader
  state; behavior is safe but structurally different.

## 3. Most-recent, state, uploads, pending replies, and pins

### StoreMessagesMostRecent

Guild/channel/thread/unread/message handlers, forum-parent propagation, channel
type gate, StoreChannels dependency and DEX-verified numeric comparison now exist
(`StoreMessagesMostRecent.kt:29-165`; constructed with channels at
`StoreStream.kt:370-373`).

- **MAJOR:** reference writes but does not restore `MOST_RECENT_MESSAGE_IDS`;
  target current class no longer exposes restore, but logout/READY ordering with
  the persisted field needs verification against the reconstructed read-state
  campaign.
- **MINOR:** parent lookup uses published `getChannel` inside store-thread mutation
  (`StoreMessagesMostRecent.kt:133-139`) rather than a live internal accessor.

### StoreMessageState

Reference spoiler state and handlers remain substantially implemented.

- **MINOR:** separate mutable map plus subject copy replaces subject-owned state.
- **MINOR:** helper confinement/extra overloads differ.

### StoreMessageUploads

Preprocessing/upload/progress and cleanup are wired from the coordinator
(`StoreStream.kt:1069-1091`).

- **MAJOR:** `Uploading` adds `UploadProgress` and uses top-level
  `utilities.rest.FileUpload` rather than the reference nested `SendUtils` type.
- **MINOR:** snapshots/observation and terminal cleanup are target extensions.

### StorePendingReplies

Reference fields and operations are present.

- **MINOR:** full HashMap copies replace partition-map copies.
- **MINOR:** `shouldMention` is immutable/copy-based rather than a mutable field.
- **MINOR:** nullability and no-op marking differ.

### StorePinnedMessages

The former snapshot-confinement defect is fixed: pins update mutates the working
map and forces reload; only `snapshotData` publishes (`StorePinnedMessages.kt:37-53,
85-89`). Loader is supplied by StoreStream (`StoreStream.kt:447-453`).

- **MAJOR:** `CHANNEL_PINS_UPDATE` refetch is target-only; reference 126.21 has no
  equivalent StoreGateway route.
- **MAJOR:** insertion ordering remains reconstructed as descending snowflake id
  because the JADX loop is broken (`StorePinnedMessages.kt:71-80`). Vineflower/
  DEX must confirm timestamp/index semantics before closure.
- **MINOR:** target sorts API load results indirectly only on incremental insert;
  reference preserves API order.
- **MINOR:** loading-set dedupe/force reload and pre-logout clear are additions.

## 4. StoreChat and editing

### Resolved since 2026-07-26

- Full `StoreChat` state, `EditingMessage(content)`, event hierarchy, blocked
  groups, InteractionState/scroll bits, mention warming, replace text and snapshot
  behavior exist (`StoreChat.kt:28-201`).
- StoreStream wires interaction state to the loader and gateway member request
  (`StoreStream.kt:376-380,1334-1353`).
- Vineflower, not the broken JADX tautologies, is the source for
  `InteractionState.getScrollState` (`StoreChat.kt:97-121`).

### Open

- **BLOCKER:** production composer/editing still uses the duplicate target-only
  `StoreChatInputEditing`, not `StoreChat.EditingMessage`
  (`StoreStream.kt:456,1142-1144`; `ChatInputViewModel.kt:19,56`;
  `WidgetChatInput.kt:795`). In-progress edit text is not owned by the reference
  store and StoreChat's editing APIs have no production callers.
- **MAJOR:** `StoreChatInputEditing` keeps per-channel messages but not editable
  content; recreation can lose typed edit text.
- **MAJOR:** `StoreChat.handlePreLogout` is target-added and participates in the
  full reset; reference lifecycle needs ordering comparison.
- **MINOR:** mention text uses reconstructed username/discriminator formatting.
- **MINOR:** some event methods publish directly rather than through dispatcher;
  reference also treats them as one-shot events, but thread origin should be
  verified.

## 5. Reactions, replies, and slow mode

The July "missing wholesale" findings are obsolete:

- `StoreMessageReactions` is constructed, in `storesV2`, and receives READY/add/
  remove/all/emoji events (`StoreStream.kt:383-385,829-831,1734-1752,2765`).
- `StoreMessageReplies` is constructed, in `storesV2`, and receives load/create/
  update/delete/logout (`StoreStream.kt:382,829,1228,1712,1723,1905,2606`).
- `StoreSlowMode` is constructed, feeds input cooldown, and receives send success/
  failure (`StoreStream.kt:430,924-926`; `StoreMessages.kt:1135-1153`).

- **MAJOR:** presence/wiring was verified, but these three large stores were not
  fully re-compared field-by-field in this pass.
- **MAJOR:** reaction fetch uses an injected nullable REST provider; current
  behavior when API is absent/fails needs reference loading/failure fixtures.

## 6. MessageQueue, MessageRequest, and MessageResult

### Resolved since 2026-07-26

- Request payloads now include `Send`, `Edit`, and `SendApplicationCommand`, with
  attachments, activity, preprocessing and progress callbacks
  (`MessageRequest.kt:26-106`).
- Queue implements send/edit/command execution, validation, multipart/JSON,
  canonical success/error mapping, timeout, Backoff, reconnect and cancellation
  (`MessageQueue.kt`).
- Result payloads now carry canonical API message, captcha error/channel/nonce,
  AutoMod message/error, and typed unknown error (`MessageResult.kt:24-76`).
- `MessageQueue.doSend` was verified from Vineflower/DEX; the old JADX blocker is
  retired.

### Open

- **MAJOR:** coordinator ownership moves messageQueues/executor from StoreMessages
  into `MessageSendCoordinator`; this is behaviorally plausible but not structural
  parity, and queue lifetime/account reset needs explicit tests.
- **MAJOR:** global OkHttp captcha interception intentionally changes the
  reference per-request captcha result/resend flow. Queue still models
  `CaptchaRequired`, but most challenged responses are intercepted before it.
- **MINOR:** target queue scheduler/backoff abstractions differ from reference
  executor fields.
- **MINOR:** target adds `cancelAll` and reconnect behavior beyond reference.
- **MINOR:** retry default and diagnostics should remain pinned by queue tests.

## Decompiled-reference caveats

- Formerly broken `StoreMessagesHolder.updateDetachedState`, reaction removal,
  `StoreMessagesLoader.handleLoadedMessages`, `MessageQueue.doSend`, and
  `StoreChat.InteractionState` regions were checked with Vineflower and no longer
  remain "unverifiable" solely because JADX failed.
- Pinned-message insertion ordering remains unresolved and is explicitly open.
- Failed-message analytics payload from `trackFailedLocalMessageResolved` was not
  re-derived in this pass; no parity claim is made for that telemetry.

## Verification gaps

- Documentation-only re-audit; no Gradle/device/live-backend checks ran.
- No production test asserts loader background gating.
- No recreation test proves edit text survives through StoreChat.
- Offline/reconnect, attachment, captcha, AutoMod, command, and queue-timeout
  behavior still need a live/device regression matrix.
