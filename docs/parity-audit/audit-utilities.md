# Parity Audit — `com.discord.utilities`

Target: `C:\Users\manti\Desktop\projeler\manticordrebuild` (57 Kotlin files, 3,791 lines under `utilities/`)
Reference: `C:\Users\manti\StudioProjects\discord-jadx` (Discord Android 126.21), 416 non-synthetic classes under `utilities/`
Phases per `PLAN.md`: 0–5 DONE, 6–8 IN PROGRESS, 9–14 NOT STARTED.
Read-only audit. Nothing in the target was modified.

Method: counted non-synthetic (`*.java` without `$`) reference classes per subpackage against the
target's `*.kt` count in the same subpackage path, then deep-read the priority areas and traced their
actual call sites in `stores/`, `widgets/`, and `restapi/`.

---

## 1. Subpackage coverage table

`ref` = non-synthetic reference classes, `tgt` = target `.kt` files in the same package path.
**0%** rows are entirely absent from the target.

| ref | tgt | subpackage | note |
| ---: | ---: | --- | --- |
| 28 | 0 | **search** | 0% — phase 12, expected |
| 28 | 1 | textprocessing | 3.6% — see §2.4, BLOCKER for phase 7 |
| 27 | 7 | view | 26% — missing `view/validators/**` (5), `view/extensions/**` (6), `ToastManager`, `TextWatcher` |
| 14 | 1 | lazy | 7% — subscription managers relocated to `stores/StoreGuildSubscriptions.kt` |
| 13 | 0 | **collections** | 0% — `ShallowPartition*`, `SnowflakePartitionMap`, `SparseMutableList` back the member list |
| 11 | 0 | **analytics** | 0% — out of parity scope, noted only |
| 11 | 1 | voice | phase 11, expected |
| 9 | 0 | **channel** | 0% — `ChannelSelector`, `GuildChannelIconUtilsKt`, `ChannelPermissionUtilsKt` — phase 6 |
| 9 | 0 | **rx** | 0% — `ObservableExtensionsKt.appSubscribe`/`restSubscribeOn`, `LeadingEdgeThrottle` |
| 9 | 1 | billing | phase 13, expected |
| 9 | 1 | views | |
| 8 | 0 | **guilds** | 0% — `RoleUtils`, `GuildUtilsKt`, `RoleIconUtils` — phase 6/8 |
| 8 | 0 | **mg_recycler** | 0% — all adapter infrastructure |
| 7 | 0 | **images** | 0% — `MGImages`, `MGImagesConfig`, `RoundAsCirclePostprocessor` |
| 7 | 1 | permissions | 14% — only `PermissionUtils`; see §2.1 |
| 7 | 2 | rest | 29% — no `RestAPI` wrapper, no `SendUtils`; see §2.3 |
| 7 | 2 | time | 29% — no `TimeUtils`, `TimeSpan`, `NtpClock`; see §2.6 |
| 6 | 0 | **auth** | 0% — `AuthUtils`, `RegistrationFlowRepo`, SmartLock — phase 5 marked DONE |
| 6 | 0 | **debug** | 0% |
| 6 | 0 | **fcm** | 0% — phase 9, expected |
| 6 | 1 | recycler | 17% — no `DiffCreator`, `DiffKeyProvider`, `SpeedOnScrollListener` |
| 6 | 1 | spans | |
| 5 | 0 | **premium** | phase 13, expected |
| 4 | 0 | **coroutines** | 0% |
| 4 | 0 | **guildscheduledevent** | phase 12, expected |
| 4 | 0 | **resources** | 0% |
| 4 | 0 | **system** | 0% |
| 4 | 1 | accessibility | 25% — `AccessibilityUtils` (announcements, heading roles) missing |
| 4 | 2 | websocket | |
| 4 | 4 | intent | best-covered subpackage |
| 3 | 0 | **animations / colors / embed / experiments / gifting / guildautomod / media / presence / systemlog / user** | 0% each — `presence` and `user` matter for phase 8 |
| 3 | 1 | captcha | 33% — payload holder only, no hCaptcha flow |
| 3 | 1 | logging | 33% — target's `BootLog` is unrelated to ref `Logger`/`LoggingProvider` |
| 3 | 6 | messagesend | over-count: target split the queue into 6 files; behavior gap in §2.2 |
| 2 | 0 | **auditlogs / color / contacts / device / directories / extensions / kryo / message / networking / press / receiver / sms / video / viewbinding / viewcontroller** | 0% each — `networking` (Backoff, NetworkMonitor) and `message` (`MessageUtils`) matter for phase 7 |
| 2 | 1 | error | 50% — `RestApiError` covers `Error`; no `AppCancellationException` |
| 2 | 1 | file | |
| 2 | 1 | streams | |
| 2 | 2 | cache / persister | |
| 2 | 3 | lifecycle | |
| 1 | 0 | **anim, application, applicationcommands, birthday, bugreports, buildutils, connectedaccounts, context, dimen, display, drawable, duration, email, features, font, fragment, frecency, fresco, friendsuggestions, guildhash, guildmember, handoff, hubs, icon, integrations, locale, navigation, notices, platform, spotify, ssl, stage, stateful, string, surveys, testing, textview, threading, threads, toolbar, uri** | 0% each — **`icon`, `uri`, `string`, `guildmember`, `frecency`, `fresco`, `locale`** affect shipping phases |
| 1 | 1 | apng, attachments, dimmer, io, phone, simple_pager, stickers | |
| 1 | 4 | notifications | phase 9 pre-work |

Aggregate: 416 reference classes → 57 target files; ~62 of 108 reference subpackages are at 0%.

---

## 2. Deep comparison of subpackages the shipping phases depend on

### 2.1 `utilities/permissions/**` — 1 of 7 classes

Reference: `PermissionUtils` (318 lines) + `PermissionsContextsKt`, `ManageGuildContext` (247),
`ManageMessageContext` (236), `ManageUserContext` (252), `PermissionsManager`, `VideoPermissionsManager`.
Target: `permissions/PermissionUtils.kt` (89 lines) only.

**BLOCKER — @everyone fallback is inverted.**
`utilities/permissions/PermissionUtils.kt:22`
```kotlin
var permissions = roles?.get(guildId)?.permissions ?: Permission.NONE
```
Reference `PermissionUtils.java:35-38` (`applyEveryone`) returns `Permission.DEFAULT`
(`521942851137`) when the `@everyone` role is absent from the map, not `NONE`. During partial
hydration — before `GUILD_CREATE` roles land, or for any guild whose roles map was not persisted —
the target computes zero permissions for every member, so `hasAccess()` fails and the channel list
renders empty. This is a silent, timing-dependent wrongness on a phase-6 shipping screen.

**BLOCKER — MFA elevation is absent entirely.**
Reference exposes `isElevated(long, boolean, int)` / `isElevated(boolean, int)` /
`canAndIsElevated(...)` (`PermissionUtils.java:145-147, 257-269`) gated on
`Permission.ELEVATED = 1125549875262L` and the guild's `mfa_level`. The target's
`api/permission/Permission.kt` does not even define `ELEVATED`, `MANAGEMENT_PERMISSIONS`,
`MODERATOR_PERMISSIONS`, `MODERATE_STAGE_CHANNEL`, `START_STAGE_EVENT`, `START_VOICE_EVENT`
(compare `api/permission/Permission.java:21,25,37-38,49-50`). Every moderation affordance in an
MFA-required guild is therefore ungated.

**MAJOR — lurker permissions not modelled.** Reference `computePermissions`
(`PermissionUtils.java:207-221`) masks a lurker's permissions to `66560`, or to
`Permission.AllowList.LURKER_STAGE_CHANNEL` (`4331734016`) for a `PUBLIC` stage instance, using
`GuildMemberUtilsKt.isLurker` and the `stageInstances` map. The target's
`computeChannelPermissions` accepts no `stageInstances` argument and never checks lurker state.

**MAJOR — thread permissions use the wrong rule set.**
`utilities/permissions/PermissionUtils.kt:62-71` only checks `SEND_MESSAGES_IN_THREADS`.
Reference `computeThreadPermissions` (`PermissionUtils.java:223-249`) branches on
`NewThreadsPermissionsFeatureFlag`; the legacy branch additionally grants `SEND_MESSAGES` from
`CREATE_PRIVATE_THREADS` / `CREATE_PUBLIC_THREADS`, and the new branch clears `SEND_MESSAGES` with
mask `-2049` rather than `and SEND_MESSAGES.inv()` on an already-modified value. The target also
derives "has joined" from `channel.member == null` (line 68) whereas the reference takes
`hasJoinedThread` as an explicit caller-supplied argument.

**MAJOR — seven public helpers missing**, each with live call sites in the reference:
`canEveryone` / `canEveryoneRole` / `canRole` (channel-list "private channel" lock icon),
`hasAccessWrite`, `hasBypassSlowmodePermissions` (phase 7 slow mode), `computeChannelOverwrite`
(channel permission editor), `canManageGuildMembers` (member action sheet).

**MAJOR — the three permission Context classes (735 reference lines) are absent.**
`ManageMessageContext` decides per-message delete/pin/edit/react/report visibility;
`ManageUserContext` decides kick/ban/timeout/nickname/role-change; `ManageGuildContext` decides
invite/settings entries. The target's message and user action sheets consequently make these
decisions inline or not at all.

**MINOR** — `PermissionUtils.kt:44` matches the `@everyone` overwrite on
`type == ROLE && id == guildId`; the reference `applyEveryoneOverwrites` matches on id only.

### 2.2 `utilities/messagesend/**` — MessageQueue, retry/backoff

Target files are structurally richer than the reference count suggests (6 files, 484 lines vs the
reference's 3 files, 803 lines), and `MessageQueue.kt` itself is a genuine reimplementation, not a
stub. The gaps are behavioral.

**BLOCKER — attachment sends bypass the queue entirely.**
`MessageSendCoordinator.execute` (`utilities/messagesend/MessageSendCoordinator.kt:191-200`) only
ever calls `api.editMessage(...)` or `api.sendMessage(channelId, body)`. There is no
attachment/multipart branch. Attachment sends instead run in `stores/StoreStream.kt:1119-1215`
(`startUpload`) with their own ad-hoc `retryWhen` at `stores/StoreStream.kt:1170-1180`. Reference
`MessageQueue.doSend` routes *all* sends — text and multipart — through the single per-channel
queue via `SendUtils.getSendPayload`. Consequences:
- Attachment sends are not serialised against text sends in the same channel, so ordering can invert.
- `stores/StoreStream.kt:1170-1180` maps only `429` and `NETWORK`/`TIMEOUT` to a retry; slow-mode
  (`code == 20016`) falls through to `else -> null` and fails permanently. Reference
  `MessageQueue.handleError` (`MessageQueue.java:217-223`) treats `20016` as
  `MessageResult.Slowmode` and requeues.
- That `retryWhen` has no attempt cap and no 1-hour message timeout, so a persistent 429 retries
  forever. Reference bounds every request by `DEFAULT_MESSAGE_TIMEOUT_MS = 3600000`
  (`MessageQueue.java:46, 278-283`).

**MAJOR — `MessageResult.Success` carries no message.**
`utilities/messagesend/MessageResult.kt:23` — `data object Success`. Reference
`MessageResult.Success` wraps `com.discord.api.message.Message` (`MessageResult.java:125-136`), and
`MessageQueue.handleSuccess` (`MessageQueue.java:262-264`) is the single reconciliation point. The
target works around this by mutating `Operation.optimistic` and emitting `Update.Reconciled`
*inside* `execute`'s `map` (`MessageSendCoordinator.kt:194-198`), i.e. before the queue has decided
the request succeeded — a cancel racing the response can emit a reconciliation for a cancelled send.

**MAJOR — `MessageRequest` is a thin shell.**
`utilities/messagesend/MessageRequest.kt:8-18` — `Send` and `Edit` carry only
`requestId`, `attemptTimestamp`, `onCompleted`. Reference `MessageRequest.Send` carries
`message`, `activity`, `attachments`, `onPreprocessing`, `onProgress` and a `validateMessage()`
(`MessageRequest.java:67-137`); `Edit` carries `channelId`, `messageId`, `content`,
`allowedMentions`. The payload lives in a side map (`MessageSendCoordinator.operations`) keyed by
request id, so a queued request is not self-describing and cannot be persisted or replayed.
`MessageRequest.SendApplicationCommand` is absent — expected, phase 12.

**MAJOR — `utilities/networking/Backoff` is missing (0 of 2 classes).**
`utilities/messagesend/MessageQueue.kt:105-109` open-codes doubling from 5,000 ms capped at
3,600,000 ms. Reference constructs `new Backoff(5000L, 3600000L, 0, false, null, 28, null)`
(`MessageQueue.java:132`) and, critically, `processNextRequest` refuses to start any request while
`networkBackoff.isPending()` (`MessageQueue.java:275`) — a process-wide gate. The target's gate is
per-`MessageQueue` (`current != null`), so channel B keeps hammering the network while channel A is
backing off. `NetworkMonitor` is also missing, so `handleConnected()` has no connectivity source;
grep finds no caller in the target outside `MessageSendCoordinator.handleConnected`.

**MINOR — retry re-queue position differs.** On `RateLimited`/`NetworkFailure` the reference pushes
the request back with `queue.addFirst(request)` and clears the in-flight slot
(`MessageQueue$processNextRequest$listener$1.java:52-56, 124-128`). The target keeps it as `current`
and re-sends (`MessageQueue.kt:114-121`). Observably close, but a request enqueued during the retry
window is ordered differently.

### 2.3 `utilities/rest/**` and `restapi/**`

Reference `utilities/rest/RestAPI.java` is 2,759 lines and `SendUtils.java` 676. The target has no
`RestAPI` wrapper at all; `restapi/RestAPIInterface.kt` (256 lines) declares **47 endpoints** and is
called directly from stores. Reference `RestAPIInterface.java` is a JADX-degraded file with 0
matches for `@GET|@POST|...` — the annotations did not survive decompilation, so **I cannot give a
reliable reference endpoint count**; do not treat "47" as a percentage of anything.

**MAJOR — `SendUtils` has no counterpart as a queue input.** The target's replacement
(`utilities/rest/MessageUploadPayload.kt`, 128 lines) builds a correct multipart body with
per-file and aggregate progress, and `AttachmentRequestBody.kt` (47 lines) is a faithful cancellable
body. But `SendUtils.SendPayload.Preprocessing` / `ReadyToSend` staging — which is what lets the
reference emit preprocessing progress *through the queue* — has no equivalent; the target emits it
directly from `StoreStream`.

**MAJOR — `RestAPIParams` coverage.** Target `restapi/RestAPIParams.kt` is 217 lines against the
reference's much larger file; a field-by-field diff was out of scope for this slice, but note
`PLAN.md` rule 11 requires every semantic field.

**Not a gap:** `restapi/http/RateLimitInterceptor.kt` (80 lines) implements per-route + global
429 handling with `X-RateLimit-Reset-After` pre-emption. The reference has no equivalent
interceptor (it handles rate limits at the `Error`/store layer). This is a deliberate improvement,
not a regression — flagging it only so it is not mistaken for reference behavior.

**MINOR — `RestApiError.Type` is missing three reference members.**
`utilities/error/RestApiError.kt:27-43` omits `CAPTCHA_KNOWN_FAILURE`, `IMAGE_NOT_FOUND`,
`AUTOMOD_MESSAGE_BLOCKED`, all present in `utilities/error/Error.java:564-568`. The target
compensates by matching numeric code `200000` for AutoMod, so behavior is close.
Otherwise `RestApiError` is a good reconstruction of `Error` (886 lines), including
`retry_after` seconds→ms conversion matching `Error.java:312`, and it adds token redaction
(`RestApiError.kt:128-130`) that satisfies `PLAN.md` rule 9.

### 2.4 `utilities/textprocessing/**` — 1 of 28 classes — **BLOCKER, drives every chat row**

Reference: a `simpleast` AST pipeline — `DiscordParser` (parser construction with 5 option flags),
`Rules` (block quotes, fenced code blocks with per-language syntax highlighting for Kotlin, Crystal,
JavaScript, TypeScript, Python, Rust and more, spoilers, masked links, headers, bullet lists,
timestamps, named/unicode emoji, soft hyphen, emoticon unescape), `AstRenderer`,
`MessagePreprocessor` (jumbo-emoji sizing, quote merging, spoiler collection, link conflict
detection), `MessageRenderContext` (14 constructor parameters incl. click callbacks),
`MessageUnparser`, `Tags`/`TagsBuilder`, `SimpleRoundedBackgroundSpan`, and 15 `node/**` classes.

Target: `utilities/textprocessing/MessageTextRenderer.kt`, 176 lines, a single regex pass.

Concretely missing, all reachable from an ordinary chat message today:

| Syntax | Reference | Target |
| --- | --- | --- |
| `\|\|spoiler\|\|` | `Rules.java:91` `PATTERN_SPOILER` + `SpoilerNode` | not parsed — literal `\|\|` shown |
| ` ```lang\ncode``` ` | `Rules.java:354-464`, per-language highlighting | not parsed |
| `> quote` / `>>> block` | `Rules.java:76` `PATTERN_BLOCK_QUOTE` | not parsed |
| `<t:1234:R>` | `Rules.java:84` `PATTERN_TIMESTAMP` + `TimestampNode` | not parsed — raw `<t:…>` shown |
| bare `https://…` | `Rules.java:85` `PATTERN_URL` + `UrlNode` | not linkified in message body |
| `[text](url)` | `Rules.java:87` `PATTERN_MASKED_LINK` | not parsed |
| `<https://…>` no-embed | `Rules.java:89` | not parsed |
| `:named_emoji:` / unicode emoji | `Rules.java:80,82` + `EmojiNode` | not parsed |
| `# Header`, `* list` | `Rules.java:69,94` | not parsed |
| jumbo emoji sizing | `MessagePreprocessor` | fixed size, `MessageTextRenderer.kt:126` |
| clickable mentions | `UserMentionNode`/`ChannelMentionNode`/`RoleMentionNode` render contexts | styled only, `MessageTextRenderer.kt:98-109`, no click target |
| markdown escapes `\*` | `Rules` escape rule | not handled |

**Additional correctness bugs in the target implementation:**
- `MessageTextRenderer.kt:150-165` — `applyInlineMarkdown` runs *after* custom-emoji image spans
  were attached at `:135-147`, and `applyMarkup` calls `text.replace(range)` on the
  `DraweeSpanStringBuilder`. Deleting the `**`/`__` delimiters shifts every later offset, so an
  emoji image span that follows a bold run is left pointing at the wrong characters.
- `MessageTextRenderer.kt:154` — the inline-code rule applies a `TypefaceSpan` but does not stop the
  other markup rules from running inside the code span, so `` `**x**` `` renders bold inside code.
- `MessageTextRenderer.kt:37-53` — `render` reaches into `StoreStream.getGuilds()/getUsers()/
  getChannels()` singletons from inside the renderer. The reference injects a `MessageRenderContext`
  built by the caller, which is what makes the renderer testable and thread-safe.

Phase 7 is marked IN PROGRESS, so this is not a broken DONE claim, but every item in its checklist
that touches "message content" is affected. Treat as the single highest-value gap in this slice.

### 2.5 `utilities/images/**` (0/7), `utilities/icon/**` (0/1), `utilities/fresco/**` (0/1)

**BLOCKER — no `IconUtils`; CDN URLs are hand-built in 30+ widget files.**
Reference `icon/IconUtils.java` (888 lines) is the single source for every avatar, guild icon,
channel icon, banner, splash, role icon, application icon and asset URL. Confirmed target
duplicates, each with a hard-coded extension and size:
`stores/StoreStream.kt:918`, `views/guildboost/GuildBoostConfirmationView.kt:44`,
`views/permissions/ChannelPermissionOwnerView.kt:62`, `views/user/SettingsMemberView.kt:127,130`,
`widgets/auth/AuthInviteInfoView.kt:69,94,124,131`,
`widgets/channels/list/WidgetChannelsList.kt:234-235`, `widgets/channels/UserAkaView.kt:77`,
`widgets/chat/list/adapter/WidgetChatListAdapter.kt:384`,
`widgets/guilds/list/WidgetGuildsList.kt:251,280,302-303`,
`widgets/user/profile/UserProfileHeaderView.kt:105-106`, and ~18 more.

Specific behaviors lost:
- **Default avatars.** `IconUtils.java:673-683` (`getForUser` with discriminator) falls back to the
  `discriminator % 5` default-avatar asset. `stores/StoreStream.kt:918` is
  `user.avatar?.let { … }` — a user with no avatar gets `null` and renders blank. Grep for
  `defaultAvatar` across the whole target returns nothing.
- **Default group-DM / guild icons.** `IconUtils.DEFAULT_ICON`,
  `DEFAULT_ICON_BLURPLE`, `getDefaultForGroupDM` (`IconUtils.java:50-51, 452-456`) — absent.
- **Animated-icon gating.** `IconUtils.getImageExtension(hash, allowAnimated)` /
  `isImageHashAnimated` (`IconUtils.java:590-594, 631`) picks `gif` only when the hash starts with
  `a_` *and* the user's animation setting allows it. Target sites hard-code `webp` unconditionally
  (e.g. `WidgetGuildsList.kt:251`), so animated guild icons and avatars never animate.
- **Media-proxy size quantisation** is copy-pasted three times rather than shared:
  `utilities/stickers/StickerUtils.kt:52-59`, `views/PileView.kt:127`,
  `views/user/SettingsMemberView.kt:96`. The reference has one `MEDIA_PROXY_SIZES` array
  (`IconUtils.java:67`). Sites that build URLs without going through any of the three (most of the
  30 listed above) request unquantised sizes, which the CDN will not serve from cache.
- `StringUtilsKt.getSTATIC_IMAGE_EXTENSION()` (device-dependent webp/png) — absent, extensions hard-coded.

**MAJOR — no Fresco configuration.** `app/App.kt:29` calls bare `Fresco.initialize(this)`.
Reference `images/MGImagesConfig.java` sets a bitmap memory cache of `maxHeapSize / 3`
(`MGImagesConfig.java:35`), a 40 MB disk cache split across `app_images_cache` and
`app_images_cache_small` (`:32-34, 60, 70-71`), and an `onTrimMemory` handler (`:117`). The target's
`App.kt:37` `onTrimMemory` does not touch the image pipeline. On low-memory devices this is a
visible difference (cache thrash / OOM), not just a tuning detail.

**MAJOR — `MGImages` absent.** `setImage` overloads, `ChangeDetector` (which prevents redundant
re-loads when a recycled row rebinds with the same URI), `setRoundingParams`,
`RoundAsCirclePostprocessor`, `getImageRequest`. Without `ChangeDetector`, list scrolling re-issues
image requests on every bind.

### 2.6 `utilities/time/**` (2/7) and `utilities/date/**`

There is no `utilities/date/**` in the reference; date formatting lives in `time/TimeUtils.java`
(365 lines) plus `time/TimeSpan.java` (116). Neither exists in the target.

**MAJOR — chat timestamps are wrong.**
`widgets/chat/list/adapter/WidgetChatListAdapter.kt:316-320`:
```kotlin
val rendered = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time))
```
Reference `TimeUtils.toReadableTimeStringEN` (`TimeUtils.java:293-325`) renders
`"Today at 3:42 PM"`, `"Yesterday at 3:42 PM"`, or a full localized date-time for anything older,
using a day-boundary `Calendar` walk; for non-English locales `toReadableTimeString`
(`TimeUtils.java:169-179`) delegates to `DateUtils.getRelativeDateTimeString(context, t,
DAY_IN_MILLIS, WEEK_IN_MILLIS, FORMAT_NUMERIC_DATE)`. The target shows a bare clock time with no
date qualifier at all, on every message row.

**MINOR — `ClockFactory` has no NTP clock.**
`utilities/time/ClockFactory.kt:11-12` is `clock = Clock(System::currentTimeMillis)`. Reference
`ClockFactory.init` builds a Kronos `KronosClock` and wraps it in `NtpClock`
(`ClockFactory.java:init`). Since `MessageQueue`'s 1-hour timeout and nonce generation both key off
this clock, device clock skew is uncorrected. `Clock`/`ClockFactory` are otherwise API-compatible.

Also missing: `TimeUtils.parseSnowflake` / `millisToSnowflake` (the target open-codes
`snowflakeTimestamp` in `widgets/chat/list/model/ChatListRows.kt:116-117` and
`widgets/channels/threads/browser/ThreadBrowserThreadView.kt:103`),
`toFriendlyString`/`toFriendlyStringSimple` (call duration, thread age),
`getLocalizedMonthName`, `renderUtcDate*`, `TimeSpan`.

### 2.7 `utilities/mg_recycler/**` (0/8) and `utilities/recycler/**` (1/6)

Entirely missing: `MGRecyclerAdapter`, `MGRecyclerAdapterSimple`, `MGRecyclerViewHolder`,
`MGRecyclerDataPayload`, `SingleTypePayload`, `DragAndDropAdapter`,
`CategoricalDragAndDropAdapter`, `DragAndDropHelper`; plus `recycler/DiffCreator`,
`DiffKeyProvider`, `SelfHealingLinearLayoutManager`, `SpaceBetweenItemDecoration`,
`SpeedOnScrollListener`, `PaddedItemDecorator`.

Impact, MAJOR: the target has 18 `notifyDataSetChanged()` call sites and only 11 files touching
`DiffUtil`/`ListAdapter`. The reference routes list updates through `DiffCreator` +
`MGRecyclerDataPayload.getKey()` so rows animate and scroll position is preserved.
`SpeedOnScrollListener` is what suppresses image loading during fast fling — its absence is a
scroll-performance regression on the chat and member lists.
`DragAndDropAdapter`/`CategoricalDragAndDropAdapter` back guild-folder reordering, which phase 6
lists as implemented ("folders/order/drag persistence").

### 2.8 `utilities/error/**` (1/2), `utilities/logging/**` (0/3 equivalent), `utilities/analytics/**` (0/11)

- **error** — `RestApiError.kt` is a solid `Error` reconstruction (see §2.3). `AppCancellationException`
  is missing; the target throws `java.util.concurrent.CancellationException`
  (`utilities/rest/AttachmentRequestBody.kt:28`) instead, which is not distinguishable from a
  coroutine cancellation at the subscribe site. MINOR.
- **logging** — **MAJOR.** The target's `utilities/logging/BootLog.kt` is a boot-progress ring buffer,
  unrelated to the reference. Reference `Logger` (224 lines) + `LoggingProvider` + `AppGatewaySocketLogger`
  give tagged `v/d/i/w/e` with a swappable sink; the reference `Backoff.TimerScheduler` and the
  gateway both log through it (`networking/Backoff.java:83-91`). The target has 21 raw
  `android.util.Log` calls across 7 files (`gateway/GatewaySocket.kt`, `stores/Dispatcher.kt`,
  `stores/StoreStream.kt`, `utilities/view/text/LinkifiedTextView.kt`, …) with no central sink,
  no redaction layer, and no way to capture logs for a bug report. Note this interacts with
  `PLAN.md` rule 9 — a raw `Log` call cannot enforce token redaction.
- **analytics** — 0 of 11. Out of parity scope per the task brief. Recording it as **absent, expected**:
  `AnalyticsTracker`, `AnalyticSuperProperties`, `Traits`, `AdjustConfig`, `InstallReferrer`,
  `AppStartAnalyticsTracker`. One caveat: the reference's `SelectedChannelAnalyticsLocation` is a
  *parameter* of `ChannelSelector.selectChannel` (`channel/ChannelSelector.java:158`), so the
  navigation signature will need it when `ChannelSelector` is reconstructed.

### 2.9 `utilities/channel/**` (0/9), `guilds/**` (0/8), `user/**` (0/3), `presence/**` (0/3)

All four are 0%. These are formatting/derivation helpers that shipping screens call directly.

**BLOCKER (phase 6) — `ChannelSelector` missing.** Reference `channel/ChannelSelector.java` owns
`selectChannel`, `gotoChannel`, `findAndSet`, `findAndSetDirectMessage`, `findAndSetThread`,
`selectPreviousChannel`, `previewVoiceChannel`, `openCreateThread`. Phase 6 claims channel selection
is implemented; in the target this logic is spread across `stores/StoreChannelsSelected.kt` and the
widgets, and `findAndSet*` (the deep-link "open this channel id, fetching it if unknown" path) has
no single owner. Cross-check against the phase-5 deep-link exit criteria.

**MAJOR — `GuildChannelIconUtilsKt` missing.** `getChannelType` / `guildChannelIcon` /
`mapGuildChannelTypeToIcon` (`channel/GuildChannelIconUtilsKt.java:22,167,172`) map a channel to its
icon accounting for NSFW, locked (no `VIEW_CHANNEL` for `@everyone`), announcement, stage, forum and
thread variants. Grep for `guildChannelIcon` in the target returns nothing.

**MAJOR — `guilds/RoleUtils` missing.** `getHighestHoistedRole`, `getHighestRole`, `getRoleColor`,
`getOpaqueColor`, `isDefaultColor`, `ROLE_COMPARATOR`, `rankIsHigher`
(`guilds/RoleUtils.java:50-160`). The target has only
`models/member/GuildMember.kt:65 getHighestRoleIconRole`. Consequences on shipping screens:
member-list names are not coloured by highest hoisted role, and role rank comparisons
(needed for "can I kick this member") have no implementation. `widgets/roles/RolesListView.kt:69-70`
re-derives opaque colour locally.

**MAJOR — `user/UserUtils` missing.** `getUserNameWithDiscriminator`, `padDiscriminator`,
`getDiscriminatorWithPadding`, `compareUserNames`, the badge predicates (`isStaff`, `isPartner`,
`isVerifiedBot`, `isHypeSquad*`, `isBugHunterLevel*`, `isCertifiedModerator`, `isPremium*`),
`SYSTEM_USER_ID = 643945264868098049`, `CLYDE_BOT_USER_ID = -1`, `isNewUser`/`isAccountOldEnough`
(`user/UserUtils.java:34-270`). Phase 8 lists profiles as implemented; profile badges cannot be
correct without these flag predicates.

**MAJOR — `presence/PresenceUtils` missing.** `getStatusText`, `getStatusDraweeSpanStringBuilder`
(custom status with inline emoji), `setPresenceText`, `getPrimaryActivity`,
`getCustomStatusActivity`, `getStreamingActivity`, `getSpotifyListeningActivity`,
`ACTIVITY_COMPARATOR`, `isMobile(ClientStatuses)`, `getActivityHeader`
(`presence/PresenceUtils.java:77-409`). The target only sets a status dot
(`widgets/channels/memberlist/ChannelMembersListAdapter.kt:88`,
`widgets/channels/list/WidgetChannelsList.kt:231`) and toggles a rich-presence row's visibility
(`ChannelMembersListAdapter.kt:93`); no status *text*, no custom-status emoji, no mobile indicator.
Phase 8 claims "Presence, activities, typing, and status controls".

Also missing here: `guildmember/GuildMemberUtilsKt.isLurker` (needed by §2.1),
`message/MessageUtils.getNickOrUsernames` (per-channel nick resolution, which
`MessageTextRenderer.kt:41-45` re-implements without the `ChannelRecipientNick` list),
`frecency/**` (emoji/GIF recency ordering).

### 2.10 Cross-cutting: `utilities/uri/**` (0/1) and `utilities/rx/**` (0/9)

**BLOCKER (phase 5, marked DONE) — no `UriHandler`.** Reference `uri/UriHandler.java` exposes
`handle(context, url, …)` and `handleOrUntrusted(context, url, untrustedUrl)` (`:85, 95`), which
show the untrusted-domain confirmation dialog and route through Chrome Custom Tabs when the user
setting allows. The target instead does raw `startActivity(Intent(ACTION_VIEW, …))` at
`app/AppActivity.kt:134`, `widgets/botuikit/views/ButtonComponentView.kt:82` and
`widgets/servers/NotificationHighlightsSettingsView.kt:53`. Two consequences:
1. Phase 5's "Safe external URI handling" exit criterion is not met — there is no untrusted-link
   warning, and `StringUtilsKt.toPunyCodeASCIIUrl` (IDN homograph normalisation,
   `string/StringUtilsKt.java:117`) is absent.
2. `StoreUserSettings.getIsChromeCustomTabsEnabled()` exists
   (`stores/StoreUserSettings.kt:58`) and is bound to a settings toggle
   (`widgets/settings/WidgetSettingsApp.kt:57`) but is **read by nothing else** — the switch is inert.

**MAJOR — no `rx/ObservableExtensionsKt`.** The reference funnels essentially every subscription
through `appSubscribe(observable, klass, context, onNext, onError, …)` and `restSubscribeOn`, which
attach lifecycle unsubscription, a default error handler, and `Logger` reporting. The target
subscribes directly, and at least one site swallows errors outright:
`utilities/messagesend/MessageSendCoordinator.kt:181`
```kotlin
api.setUserTyping(channelId).subscribeOn(Schedulers.io()).subscribe({}, {})
```
Also missing: `LeadingEdgeThrottle` / `ObservableWithLeadingEdgeThrottle` (typing indicator and
search debounce), `OperatorBufferedDelay`, `ObservableCombineLatestOverloadsKt`.

### 2.11 `utilities/view/**` (7/27) and `utilities/auth/**` (0/6)

`view/text/LinkifiedTextView.kt` (131 lines) and `SimpleDraweeSpanTextView.kt`, the `rounded/`
trio, `NestedScrollableHost` and `MaxHeightRecyclerView` are all faithful — no complaints.

**MAJOR — `view/validators/**` (5 classes) and `utilities/auth/AuthUtils` are missing, and
phase 5 is DONE.** Reference `ValidationManager.setErrors(Map<String, List<String>>)`
(`view/validators/ValidationManager.java:48`) is what maps server-side field errors from
`Error.Response.messages` onto the right `TextInputLayout`. `AuthUtils` supplies the
`@StringRes`-backed email/password/phone/discriminator validators
(`auth/AuthUtils.java:45-117`) plus `encodeTotpSecret`/`generateNewTotpKey`.

The target re-implements this inline in `widgets/auth/AuthFragments.kt` with **hard-coded English
literals**, e.g.:
- `widgets/auth/AuthFragments.kt:313` — `"Enter a valid backup code."`
- `widgets/auth/AuthFragments.kt:435` — `"Current password is required."`
- `widgets/auth/AuthFragments.kt:436` — `"Password must be at least 6 characters."`

15 such literals in `widgets/auth/`. This breaks localization parity on a phase that is marked DONE
(`PLAN.md` UI section: accessibility and localization are parity requirements). Server-side field
errors *are* plumbed (`app/AppActivity.kt:199` → `AuthUiState.fieldErrors`), so the mapping half is
present; only the client-side validator set and its string resources are missing.

Also missing from `view/`: `ToastManager` (the reference's single toast owner, which prevents toast
stacking), `view/extensions/ViewExtensions` + `RecyclerViewExtensionsKt` + `ImageViewExtensionsKt`,
`view/text/TextWatcher`/`FragmentLifecycleTextWatcher`, `view/grid/FrameGridLayout`.

---

## 3. Thin stubs — target utilities materially weaker than the reference

Ranked. `file:line` is the target; the reference behavior follows.

| # | Target `file:line` | What it does | What the reference does | Sev |
| --- | --- | --- | --- | --- |
| 1 | `utilities/textprocessing/MessageTextRenderer.kt:33-35` | one 6-alternative regex covering user/role/channel mentions, custom emoji, `@everyone/here` | `Rules.java` — ~40 rules incl. spoilers, fenced code with per-language highlighting, block quotes, `<t:>` timestamps, URLs, masked links, headers, lists, named + unicode emoji, escapes | BLOCKER |
| 2 | `utilities/textprocessing/MessageTextRenderer.kt:150-165` | 5 regex passes that `replace()` into a builder that already holds image spans | `AstRenderer` walks a parsed AST; spans are applied to immutable node ranges | BLOCKER |
| 3 | `utilities/permissions/PermissionUtils.kt:22` | `roles?.get(guildId)?.permissions ?: Permission.NONE` | `PermissionUtils.java:35-38` returns `Permission.DEFAULT` when `@everyone` is absent | BLOCKER |
| 4 | `utilities/permissions/PermissionUtils.kt:62-71` | thread perms from `SEND_MESSAGES_IN_THREADS` only | `PermissionUtils.java:223-249` — feature-flag branch, `CREATE_*_THREADS`→`SEND_MESSAGES`, mask `-2049`, caller-supplied `hasJoinedThread` | MAJOR |
| 5 | `utilities/permissions/PermissionUtils.kt:9-89` (whole file) | 4 public functions | 20 public functions + 3 permission Context classes (735 lines) | MAJOR |
| 6 | `utilities/messagesend/MessageRequest.kt:8-18` | `Send`/`Edit` carry only id + timestamp + callback | `MessageRequest.java:30-137` — full payload, attachments, progress callbacks, `validateMessage()` | MAJOR |
| 7 | `utilities/messagesend/MessageResult.kt:23` | `data object Success` | `MessageResult.java:125-136` — `Success(Message)`; also `CaptchaRequired(error, channelId, nonce)` and `AutoModBlock(error, message)` carry context the target drops (`MessageResult.kt:27-28`) | MAJOR |
| 8 | `utilities/messagesend/MessageQueue.kt:105-109` | inline `retryMs * 2` doubling | `networking/Backoff` with jitter, failure threshold, `isPending()` process-wide gate (`MessageQueue.java:132, 275`) | MAJOR |
| 9 | `utilities/time/ClockFactory.kt:11-12` | `Clock(System::currentTimeMillis)` | `ClockFactory.java` builds a Kronos `KronosClock` → `NtpClock` | MINOR |
| 10 | `utilities/logging/BootLog.kt:26-52` | boot-progress ring buffer, unrelated to reference | `logging/Logger.java` (224 lines) — tagged levelled logging with swappable sink | MAJOR |
| 11 | `utilities/captcha/CaptchaHelper.kt:20-27` | payload data class only | reference `CaptchaHelper` owns the hCaptcha activity round-trip and `CAPTCHA_KEY` constant used by `MessageQueue.java:237` | MAJOR |
| 12 | `utilities/stickers/StickerUtils.kt:52-59` | private `mediaProxySize`, duplicated in `views/PileView.kt:127` and `views/user/SettingsMemberView.kt:96` | one shared `IconUtils.MEDIA_PROXY_SIZES` / `getMediaProxySize` (`IconUtils.java:67, 274`) | MAJOR |
| 13 | `utilities/persister/PersisterConfig.kt:10-16` | `Persister.init(context, clock, Observable.never())` — the third argument is a permanently-empty stream | reference `PersisterConfig` wires a real trigger source; verify what `Observable.never()` was standing in for before phase 6 closes | MINOR |
| 14 | `utilities/voice/VoiceChannelJoinability.kt:1-9` | bare 5-value enum | reference computes joinability from permissions, user limit, video capacity — phase 11, **expected** | expected |
| 15 | `utilities/streams/StreamContext.kt:1-18` | interface with 2 properties | reference `StreamContext` is a full data model — phase 11, **expected** | expected |
| 16 | `utilities/phone/PhoneUtils.kt:30-35` | two regex predicates | reference `PhoneUtils` uses libphonenumber for region-aware formatting/validation | MINOR |

Nothing in the target matched the "returns the raw string / returns true / empty catch" pattern
literally — there are no `TODO`/`FIXME`/`stub` markers anywhere in the 472 target `.kt` files. The
stubs above are stubs by *scope*, not by placeholder syntax, which makes them easy to miss when
grading a phase as DONE.

---

## 4. Subpackages entirely MISSING but required by a DONE or IN PROGRESS phase

| Subpackage | ref classes | Required by | Severity |
| --- | ---: | --- | --- |
| `utilities/icon` | 1 (888 lines) | phases 5–8 — every avatar/icon on every shipping screen | BLOCKER |
| `utilities/uri` | 1 | phase 5 **DONE** — "Safe external URI handling" | BLOCKER |
| `utilities/channel` | 9 | phase 6 — `ChannelSelector`, channel icons, permission labels | BLOCKER |
| `utilities/networking` | 2 | phase 7 — `Backoff` gating, `NetworkMonitor` → `handleConnected()` | MAJOR |
| `utilities/guilds` | 8 | phases 6/8 — `RoleUtils` role colour, hoisting, rank comparison | MAJOR |
| `utilities/user` | 3 | phase 8 **claims profiles done** — `UserUtils` badges, name formatting | MAJOR |
| `utilities/presence` | 3 | phase 8 **claims presence done** — `PresenceUtils` status text, activities | MAJOR |
| `utilities/images` | 7 | phases 6–8 — `MGImagesConfig` Fresco cache, `MGImages.ChangeDetector` | MAJOR |
| `utilities/mg_recycler` | 8 | phases 6–8 — all list adapters | MAJOR |
| `utilities/rx` | 9 | phases 5–8 — `appSubscribe` error handling, throttling | MAJOR |
| `utilities/auth` | 6 | phase 5 **DONE** — `AuthUtils` validators, TOTP helpers | MAJOR |
| `utilities/view/validators` | 5 | phase 5 **DONE** — `ValidationManager` server-error mapping | MAJOR |
| `utilities/collections` | 13 | phase 6 — partitioned collections behind the lazy member list | MAJOR |
| `utilities/message` | 2 | phase 7 — `MessageUtils.getNickOrUsernames`, system-message rendering | MAJOR |
| `utilities/guildmember` | 1 | phase 6 — `isLurker`, needed by `computePermissions` | MAJOR |
| `utilities/string` | 1 | phases 5–8 — `getSTATIC_IMAGE_EXTENSION`, `toPunyCodeASCIIUrl`, `filenameSanitized` | MAJOR |
| `utilities/logging` (as `Logger`) | 3 | all phases — plus `PLAN.md` rule 9 redaction | MAJOR |
| `utilities/recycler` (5 of 6) | 6 | phases 6–8 — `DiffCreator`, `SpeedOnScrollListener` | MAJOR |
| `utilities/frecency` | 1 | phase 7 — emoji/GIF recency ordering | MINOR |
| `utilities/locale` | 1 | phases 5–8 — `LocaleManager`, used by `TimeUtils` for primary-locale selection | MINOR |
| `utilities/fresco` | 1 | phase 8 — `GrayscalePostprocessor` (offline avatars) | MINOR |
| `utilities/resources` | 4 | phases 5–8 — string/plural helpers | MINOR |
| `utilities/analytics` | 11 | — out of scope, but `SelectedChannelAnalyticsLocation` is a `ChannelSelector` parameter | noted |
| `utilities/search`, `premium`, `billing`, `fcm`, `gifting`, `guildscheduledevent`, `stage`, `hubs`, `directories`, `surveys`, `spotify`, `integrations`, `connectedaccounts`, `contacts`, `sms`, `bugreports`, `video`, `media` | 60+ | phases 9–13 | expected (phase not started) |

---

## 5. JADX reliability notes

Where the decompile is broken I did not guess:

- `messagesend/MessageQueue.java:151-157` — `doSend` is a `Method not decompiled` stub
  ("instructions count: 361"). My description of the send path is reconstructed from
  `doEdit` (`:135-141`), `doSendApplicationCommand` (`:159-207`), `handleError` (`:211-252`),
  `processNextRequest` (`:273-293`) and the drain listener
  (`MessageQueue$processNextRequest$listener$1.java:146-175`), all of which decompiled cleanly.
  **The exact ordering inside `doSend` — specifically where `validateMessage()` is invoked and
  where the `Preprocessing` payload is emitted relative to the network call — is not verifiable
  from this export.** Confirm against smali before implementing it.
- `restapi/RestAPIInterface.java` — Retrofit annotations did not survive; 0 matches for
  `@GET|@POST|@PUT|@PATCH|@DELETE`. **No reference endpoint count is available**, so the target's
  47 endpoints cannot be expressed as a coverage percentage. Method names and signatures did
  survive and remain usable for a name-by-name diff.
- `permissions/PermissionUtils.java:40-63, 65-127` — `applyEveryoneOverwrites` and
  `applyRoleOverwrites` contain unreachable `continue`/`break` sequences after assignment
  (a known JADX pattern for Kotlin `firstOrNull { }`). I read them as `firstOrNull { it.id == x }`,
  which matches the Kotlin metadata signatures in the `@Metadata` block. The *bit arithmetic*
  (`(deny & perms) ^ perms | allow`) is unambiguous and is what I compared against.
- `icon/IconUtils.java:493-570` (`getGiftSplashUrl`, `getApplicationIcon`) is emitted as raw
  register-level code. I relied on the clean overloads (`getForUser`, `getForGuild`, `withSize`,
  `getMediaProxySize`, `getImageExtension`) for all claims in §2.5.
- `textprocessing/Rules.java` decompiled cleanly for the pattern constants (`:76-94`); the rule
  *construction* bodies are heavily lambda-split, so I cite only the patterns, not the parse order.

---

## 6. Recommended order (highest parity value first)

1. `utilities/icon/IconUtils` + `utilities/string/StringUtilsKt` — unblocks 30+ widget call sites,
   restores default avatars and animated-icon gating. Mechanical, low risk.
2. `utilities/textprocessing` on `simpleast` — the largest single correctness gap; every chat row.
3. `utilities/permissions` — fix the `@everyone` `DEFAULT` fallback first (one line, BLOCKER),
   then `isElevated` + the `Permission` constants, then the three Context classes.
4. `utilities/uri/UriHandler` — closes a phase-5 DONE claim and a security-relevant gap; also makes
   the existing Custom Tabs setting functional.
5. Route attachment sends through `MessageQueue`; add `utilities/networking/Backoff` + `NetworkMonitor`.
6. `utilities/time/TimeUtils.toReadableTimeString` — small, visible on every message.
7. `utilities/presence/PresenceUtils` + `utilities/user/UserUtils` + `utilities/guilds/RoleUtils` —
   closes most of phase 8's claimed surface.
8. `utilities/mg_recycler` + `utilities/recycler/DiffCreator` — before more adapters are written
   against `notifyDataSetChanged`.
9. `utilities/auth/AuthUtils` + `view/validators` — replaces the 15 hard-coded English strings.
10. `utilities/logging/Logger` — needed to enforce `PLAN.md` rule 9 across the 21 raw `Log` sites.
