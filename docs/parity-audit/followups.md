# Cross-wave follow-ups

Items an agent could not complete because another agent owned the file, or
because a dependency did not exist yet. Each is assigned to the wave that owns
the destination. Append to this file; do not delete an item until it is done.

Source: agent handoff reports. See `PLAN.md` → "Reconstruction Campaign" for the
wave definitions and the file-ownership map.

## `com/discord/primitives` — package must be created

Kotlin metadata proves the original used typealiases that erase to `Long`/`String`:
`UserId`, `GuildId`, `ChannelId`, `MessageId`, `ApplicationId`, `SessionId`,
`RoleId`, `SkuId`, `PlanId`, `StickerId`, `StickerPackId`, `StreamKey`,
`Snowflake`, `ForumTagId`, `GuildBoostSlotId`, `GuildScheduledEventId`.

The REST layer currently uses the raw primitives because the package was outside
its ownership. Create `com/discord/primitives` and migrate — this is a
readability/parity gap, not a behavioral one (they erase identically).

## Retrofit verb↔annotation mapping — SETTLED, do not re-derive

Proven from Retrofit's own dispatch code in the dex2jar
(`discorddecompile/out/i0/z.java:21-77`, `RequestFactory.Builder.parseMethodAnnotation`):

`b`=DELETE, `f`=GET, `g`=HEAD, `n`=PATCH, `o`=POST, `p`=PUT, `m`=OPTIONS,
`h`=@HTTP, `k`=@Headers, `l`=@Multipart, `e`=@FormUrlEncoded.
Parameters: `y`=@Url, `s`=@Path, `t`=@Query, `u`=@QueryMap, `i`=@Header,
`j`=@HeaderMap, `c`=@Field, `d`=@FieldMap, `q`=@Part, `r`=@PartMap, `a`=@Body,
`x`=@Tag.

## Whole packages/modules still missing that others are working around

These are absent from the target entirely. Several agents inlined substitutes
rather than depend on them; each substitute is listed below so it can be removed
when the real module lands.

| Missing module/type | Consequence today | Owning wave |
| --- | --- | --- |
| `com.discord.floating_view_manager` | `FloatingViewGravity` and `FloatingViewManager` were placed in `com.discord.tooltips` instead. Consumers import them from there. **Move them when the module is reconstructed.** | Wave 4 |
| `com.discord.overlay.views.{OverlayDialog, OverlayBubbleWrap}` (399+50 ref lines) | `OverlayAppDialog` derives from `FrameLayout` and implements only dialog-level behavior. `WindowManager` layout params, the dim flag, and drag-disable are **not** implemented | Wave 5 |
| `com.discord.api.auth.OAuthScope` (sealed) | `OAuthPermissionViews` is keyed on the wire scope name instead. All 26 scope→string mappings transcribed; when the type lands, callers pass `scope.scopeName` and the mapping is unchanged | Wave 2c |
| `SimpleRecyclerAdapter`, `RxViewController`, `ModelRichPresence`, `StreamContextService`, `ViewExtensions`, `ObservableExtensionsKt`, `Logger`/`AppLog` | Inlined equivalents in `OverlayMenuBubbleDialog` and `UserAvatarPresenceViewController`; `android.util.Log` used for two error paths | Waves 2a/3/4 |
| `com.discord.media_picker.MediaPicker.Provider`, `com.discord.dialogs.ImageUploadDialog` | `MGImages.requestImageCrop` / `prepareImageUpload` not implemented | Wave 4/5 |
| `com.discord.models.commands.Application` | `IconUtils.setApplicationIcon(ImageView, Application)` / `getApplicationIcon(Application, size)` not implemented; the primitive id-based overload is | Wave 2c/3 |
| `AppToast` | `MGImages.requestDataUrl` error path uses `Log.w`; reference toasts `R.string.avatar_convert_failure_mobile` | Wave 4 |

## A better evidence source exists — re-check anything marked "unverified"

Discovered mid-Wave-2. See `AGENTS.md` → "Reference Interpretation".

`C:\Users\manti\Desktop\projeler\discorddecompile\out\**` holds **4,853
Vineflower-decompiled Kotlin files** from the DEX, and
`discord126.21-dex2jar.jar` supports `javap -p` / `javap -v`. Between them they
recover constructors, `equals`/`hashCode`/`toString`, declaration order, and
annotations that JADX silently omitted.

**Many "JADX broken / could not verify" entries in these reports are probably
resolvable now.** Before re-deriving anything from weak evidence, check there
first. Already resolved this way:

- `ClientStatuses` parameter order is **`desktop, web, mobile`** — the target was
  correct. Confirmed three independent ways (constructor assignment order,
  `equals`, `toString`), plus a single 3-arg constructor in `javap`. Accessors
  are `a()=desktop`, `b()=mobile`, `c()=web`, which is what made the JADX reading
  ambiguous.
- Four `api/**` classes (`ActivityTimestampsKt`, `VoiceStateUtilsKt`,
  `StageInstanceUtilsKt`, `GuildWelcomeChannelUtilsKt`) are **R8-emptied in the
  reference** — `javap` shows zero members and their `@Metadata` `d2` arrays list
  no declarations. Nothing is recoverable; do not create stub files for them.

## Chat rows — degraded because a dependency is absent

Not shortcuts; each is documented in-code and closes when the named thing lands.

| Row | Degradation | Blocked on |
| --- | --- | --- |
| Attachment | Opens via `UriHandler` for all types instead of the fullscreen viewer for images/videos | `widgets/media/WidgetMedia` |
| Game invite, Spotify | Party avatar piles render empty/collapsed; everything else is real. **One-line fix** | `StoreGameParty` not exposed on `StoreStream` |
| Gift, Guild template | Resolve their code directly via REST (real loading/resolved/invalid states) but with no cross-row caching | `StoreGifting`, `StoreGuildTemplates` |
| Guild template | "Use template" button hidden rather than leading nowhere | `WidgetGuildClone` |
| Gift | "Verify" button shown disabled | `WidgetSettingsAccount` |
| Gift | Nitro art + humanised expiry fall back to `IconUtils`/`TimeUtils` | `PremiumUtils`, `GiftingUtils`, `GiftStyleKt` |
| Spotify | Track tap fires the `spotify:track:` intent with no Play Store fallback; album tap unwired | `SpotifyHelper` |
| AutoMod | Footer channel name is a plain tappable run, not a rounded chip | `SimpleRoundedBackgroundSpan`, `ChannelSelector` |
| Sticker | Renders the partial sticker from the entry rather than hydrating it | `StickerUtils.getGuildOrStandardSticker` |

### Seams introduced by the chat-row wave — all inert by default, all documented in code

| Seam | Location | Stands in for |
| --- | --- | --- |
| `ChatListRowNavigation` (8 actions: `launchInviteShare`, `launchGuildSettings`, `joinInvite`, `showGuildScheduledEventDetails`, `showThreadArchiveDurationSheet`, `appendMention`, `toggleEventRsvp`, `updateThreadDraft`) | `WidgetChatListAdapterItemInvite.kt` | 5 missing screens (`WidgetGuildInviteShare`, `WidgetServerSettingsOverview`, `WidgetGuildScheduledEventDetailsBottomSheet`, `WidgetThreadDraftArchiveSheet`, `InviteJoinHelper`) + 3 stores that **exist but are not published by `StoreStream`** (`StoreChat`, `StoreThreadDraft`, `StoreGuildScheduledEvents`) |
| `InviteEmbedStore` + `InviteState` | same file | `StoreInstantInvites`. Row-level counterpart of `entries/InviteEmbedResolver` — **merge when the store lands** |
| `ThemedDrawable` | same file | belongs in `utilities/drawable/DrawableCompat` |
| `voiceParticipants: (Long) -> Observable<Map<Long, VoiceUser>>` | `WidgetChatListAdapterItemCallMessage.kt` | `StoreVoiceParticipants` **exists but `StoreStream` has no accessor** |

**Largest behavioural gap in that slice:** stage-invite speakers and listener count
are always empty — `StoreStageChannels` / `StoreRequestedStageChannels` do not
exist and `StoreVoiceStates` is unreachable via `StoreStream`. The speaker strip
hides itself. Also: scheduled-event `isConnected` is always false
(`StoreVoiceChannelSelected` unreachable); hub e-mail prompt permanently hidden
(`GrowthTeamFeatures` unreconstructed); private-thread boost badge hidden
(`PremiumUtils.BoostFeatureBadgeData` absent); select-menu interaction uses a
`MaterialAlertDialogBuilder` chooser instead of the unreconstructed
`SelectComponentBottomSheet` (honours `min`/`maxValues`, same callback).

**Adapter-contract deviations** (adapter followed, reference deviated from):
`ThreadDraftForm` is 1-arg not `(adapter, flexInputViewModel)` — `AppFlexInputViewModel`
absent, so IME "Next" no longer hands focus to the composer;
`GuildScheduledEventInvite` is 1-arg not `(adapter, store)`; `GuildWelcome` uses
`@JvmOverloads` with `clock = adapter.clock`. `WidgetChatListAdapterItemInviteBase`
was not created; `ChatListRowNavigation.joinInvite` takes its role.

### Third hook-formatting implementation — now two, converge them
`ChatRowText` (in `WidgetChatListAdapterItemMessage.kt`) is the shared one and
absorbed the invite agent's `formatWithHooks`.
`CharSequence.withClickableHook` in `WidgetChatListAdapterItemAutoModBlocked.kt`
still solves it a third way. Fold both into `com.discord.i18n`.

### Misplaced code from this wave
- **`EmbedMedia`** (currently `internal object` at the bottom of
  `WidgetChatListAdapterItemEmbed.kt`) is a port of the reference's
  `utilities/embed/EmbedResourceUtils` + `FileType` — `previewImage`,
  `isPlayable`, `isInlineEmbed`, `externalOpenUrl`, `isAnimated`, `previewUrls`,
  `computeMaximumImageWidthPx`, `calculateScaledSize`, and the order-sensitive
  file-type→icon table. **Move to `com/discord/utilities/embed/`.**
- **Two independent hook-formatting helpers must converge**:
  `withClickableHook` in `WidgetChatListAdapterItemAutoModBlocked.kt` and
  `formatWithHooks` in `WidgetChatListAdapterItemApplicationCommand.kt`. Both
  swap `I18nSpans`' `URLSpan` for a `ClickableSpan`. Fold into `com.discord.i18n`.

### Unverified in this wave
`WidgetChatListAdapterItemEmbed` **fails to decompile in Vineflower**
(`NullPointerException` in `KotlinWriter`), and JADX emits `configureInlineEmbed`
as "Method not decompiled" (234 instructions). The inline-embed branch was
reconstructed from the sibling attachment path plus `InlineMediaView`'s own API —
**check inline GIFV sizing on device.**

## Chat list — inputs with no source yet

Deliberately not built into `ChatListRows`, each because nothing produces its
input. Every one is commented in place.

| Row / field | Blocked on |
| --- | --- |
| Bot UI-kit component rows | `ComponentChatListState` |
| Thread-embed cards, and `shouldShowThreadSpine` (so `ThreadSpineItemDecoration` currently draws nothing) | `StoreThreadMessages` metadata |
| Collapsed blocked-message chunks | `StoreChat` blocked-group expansion |
| `StartOfChatEntry.isTextInVoice`, `canAccessRedesignedForumChannels` | hardcoded `false` — experiment-gated, no `StoreExperiments` |
| `StartOfChatEntry.threadCreatorName` | uses guild nickname only — no `StoreUser` in the builder |
| `StartOfPrivateChatEntry.mutualGuilds` | empty — no `StoreUserMutualGuilds` |

**`onInteractionStateUpdated` is inert.** `StoreStream` never constructs a
`StoreChat`, so `StoreMessagesLoader.init(chat, …)` is never called and
`handleChatInteraction` is unreachable. Interaction-driven pagination cannot
work; a recycler-driven `loadBefore` is the documented stopgap. **Wiring
`StoreChat` into `StoreStream` closes this.**

**Reactions in DMs:** the reference calls `PermissionUtils.can(ADD_REACTIONS, …)`
alone because its `StorePermissions` returns ALL for private channels; ours does
not yet. An explicit `channel.isPrivate()` shortcut is in place and commented —
without it the "+" button is dead in every DM. Remove it once `StorePermissions`
gains the `isPrivate → ALL` branch.

**Doc bug:** `WidgetChatListAdapter`'s KDoc claims
`mentionMeMessageLevelHighlighting` is off "for surfaces such as pinned messages
and search results". The only setter call site in the whole reference is
`WidgetUserMentions.java:1408`. Correct the KDoc.

## Back-press handlers still to register (outside the back-nav agent's ownership)

Both function again now that the activity no longer outranks them, but they use
bespoke `OnBackPressedCallback`s and bypass the priority ordering entirely:

- `widgets/chat/input/WidgetChatInput.kt:97-99` — `expressionBackCallback` should
  become `setOnBackPressed`. Reference analogue: `WidgetChatInputAttachments.kt:375`.
- `widgets/chat/pins/WidgetChannelPinnedMessages.kt:45-53` — always-enabled
  callback; also leaves `isEnabled = false` behind on its fall-through path.

Two `handleCenterPanelBack` branches are deliberately absent, not approximated:
`StoreChannelsSelected.dismissCreateThread()` is `@StoreThread` with no
dispatcher-scheduled public wrapper, and `WidgetCallFullscreen` /
`WidgetCallPreviewFullscreen` do not exist yet.

## CLOSED — audit findings now fixed

- **`AppActivity` never consumed `AppScreenRouter.EXTRA_SCREEN`.** `screenOf` was
  dead code and the activity only ever showed the fragment for the current
  `MainRoute`, so `AppScreenRouter.launch` silently re-entered the tab host —
  leaving **every router-launched screen unreachable** (media viewer, QR scanner,
  `WidgetTosReportViolation`, `EmptyFriendsStateView`). `consumeIntent` now
  branches on `screenOf(intent)` into a new `showScreen(...)`, carrying the
  intent extras through as fragment arguments and adding the transaction to the
  back stack. The media viewer's own `presentScreen()` workaround can be removed.
- **`AppActivity.kt`'s raw `startActivity(ACTION_VIEW)`** for external routes now
  goes through `utilities/uri/UriHandler.handle` — this was the last of the four
  raw `ACTION_VIEW` sites listed below, and the reason Phase 5's "safe external
  URI handling" claim was untrue.

### Still needed for full parity on this
The reference launches the **base** `com.discord.app.AppActivity` (its manifest
line 70, standard launch mode) for screen intents; the target declares only
`AppActivity$Main` and `AppActivity$AppAction`, and `createScreenIntent` targets
`Main`. Adding the base activity to the manifest would let screens run as real
activities rather than back-stacked fragments, which also preserves the chat
list's scroll position while a viewer is open. **Manifest is owned by the
notifications/manifest agent.**

- **Blocked-users row is no longer a dead end.** It is shown, and its callback
  *is* invoked: `StoreStream.kt:904` installs `stateSource`, `:921` installs
  `onUnblock`, `WidgetTabsHost.kt:110` installs `onProfile`, `:103` installs
  `WidgetSettings.onBlockedUsers`. `WidgetSettingsBlockedUsers` is a real
  `ListAdapter` + flipper screen.
- **`WidgetChatStatus`'s "missing" ack gate, detached cap and 500 ms throttle
  were already present and correct** — verified line-for-line against the
  decompile. The audit finding was wrong.

### Still open in the same area
`ChatListRows` does not populate `MessageEntry.replyData`, so **the reply preview
above a reply renders empty**. Needs `StoreMessageReplies`, which the row builder
does not currently take. No test covers it.

### Judgement call recorded: `settings → notifications` stays exposed
`WidgetSettingsNotifications` navigates and renders the recovered layout with
real default state, but `NotificationStore.resolver` is **assigned nowhere**
(`StoreStream` neither constructs nor exposes `StoreNotifications`), so it
honestly *disables* all six toggles rather than silently dropping writes. Kept
exposed as a live route with visible degradation — hiding it would be the same
mistake as the blocked-users row in reverse. **It becomes fully live the moment
`StoreNotifications` is wired into `StoreStream`** (that store currently exists
but is dead code).

## Corrections to earlier audit findings

The audit is evidence, not scripture. Where DEX contradicts it, DEX wins
(AGENTS.md evidence ranking). Recorded so the finding is not "re-fixed" later.

- **The Back bug was NOT "no handlers registered".** `WidgetTabsHost` had a
  working bespoke `OnBackPressedCallback` that delegated to `WidgetHome`; it
  never ran. `AppActivity.onCreate` registered its callback with
  `addCallback(this /* activity LifecycleOwner */, …)`, and **from API 29** the
  activity's `ON_START` is delivered by `ReportFragment.onActivityPostStarted` —
  after every fragment has started. `OnBackPressedDispatcher` iterates
  last-added-first, so the activity sat on top and swallowed every press. On
  API < 28 the identical code works, which is why it read as correct. Fix:
  register the activity callback eagerly with **no `LifecycleOwner`**.
- **All 28 reference `setOnBackPressed` call sites use the default priority**
  (`Integer.MAX_VALUE`, verified via `javap -c`). Ordering therefore falls out of
  `HashMap` iteration of `javaClass.name` keys — and `WidgetTabsHost` sorts
  before `WidgetHome`, which is load-bearing because `WidgetHome` returns `true`
  unconditionally.
- **`HomePanelsLayout` needed no change.** The claim that it "only exposes
  `closePanels()`" was wrong — the reference `PanelLayout` is exactly
  `{openStartPanel, openEndPanel, closePanels}`, and panel state comes from
  inherited public `getSelectedPanel()` / `handleStartPanelState` /
  `handleEndPanelState`.
- **`ObservationDeck.notify` try/catch is REAL.** The audit reported the target
  had added a try/catch the reference lacks, and the wave instruction said to
  remove it. `dexdump classes2.dex` proves `notify` has a **7-entry catch
  table**: the handler at `0x007d` does `logLevel.compareTo(ERROR)`,
  `if-ltz → throw`, else `logNotifyError(t, updates)`, with
  `logBreadcrumb("notify END")` duplicated across all three exits — i.e.
  try/catch/finally. JADX dropped the whole region. The DEX-proven form was
  implemented. Net behavior is unchanged from the target, because at the default
  `LogLevel.NONE` (every construction site in this repo) exceptions still
  propagate.
- **`ChannelUtils.getDisplayName` was already correct.** Verified against the
  reference's `ChannelUtils.c()`: `if (isPrivate() && name.isNullOrEmpty())
  → recipients?.joinToString { it.username } ?: ""`, else `name ?: ""`. A test
  asserting the recipient fallback for a *named* group DM was wrong, not the code.
  The gap the audit recorded is in `stores/StoreChannels.displayName()`, which
  still has to be routed through this function.
- **An unknown dispatch type yields no data at all.** The reference's streaming
  `IncomingParser.assignField` ends its `when (type.hashCode())` chain with
  `reader.skipValue(); return`, leaving the `data` field at its `null` initial
  value — the raw `JsonElement` is never retained. The target's
  `DISPATCH_UNPARSED` marker is behaviourally identical (`GatewaySocket` treats
  it exactly like `null`) and only exists so "unknown type" is distinguishable
  from "known type, empty body" in tests.
- **`leadingEdgeThrottle` is not in `StoreUtilitiesKt`.** `StoreUtilitiesKt`
  contains only `snapshotDirtyKeys`; the operator lives in
  `utilities/rx/ObservableExtensionsKt` and lifts `utilities/rx/LeadingEdgeThrottle`.
- **All four reaction events use `MessageReactionUpdate`.** The target's
  `MessageReactionRemoveAll` / `MessageReactionRemoveEmoji` are inventions and
  are now **dead types** in `api/message/reaction/` — delete them.
- **`CHANNEL_PINS_UPDATE` is confirmed an invention** — zero occurrences in the
  entire reference tree. Removed from parsing/routing;
  `api/channel/ChannelPinsUpdate.kt` is now dead and should be deleted.
- **`USER_SUBSCRIPTIONS_UPDATE` / `USER_PAYMENT_SOURCES_UPDATE` are not in the
  parser table** — they arrive with no `d` and the *socket* substitutes `Unit`.
- **`StoreConnectionOpen` uses `delay(100ms)`, not `debounce`** — confirmed by
  decompiling the obfuscated `rx.Observable.q` operator.
- **`MessageQueue.doSend` is RESOLVED** — androguard disassembly of
  `classes2.dex` and the Vineflower Kotlin decompile agree instruction-for-
  instruction. `validateMessage()` runs **first and returns early**, before the
  body is built and before any `Preprocessing` emission. Then
  `getSendPayload → doOnNext(onPreprocessing/onProgress) → filterIs<ReadyToSend>
  → take(1) → flatMap(REST) → restSubscribeOn(retry = message.nonce != null) →
  appSubscribe`.
- **`Backoff.fail` is a TRIPLING, not a doubling**:
  `current = min(current + (2 * current * jitter), max)`. `minBackoffMs` is the
  seed, not the first delay.
- **`handleError`'s captcha branch short-circuits before the 200000 check** —
  JADX's control-flow flattening made it look like a fall-through.
- **`ModelPayload.Hello.trace` is REAL.** The audit called the gateway copy's
  `trace: List<String>?` an invention; the reference `Hello` does declare it
  (`ModelPayload.java:60`, wire key `_trace`). Only the class's *location* was
  wrong, not its fields.
- **`SpeakerPulseView` "half-size rings" DOES NOT REPRODUCE.** The reference
  computes `layoutParams.width + (int)(2 * (paddingLeft / 2f) * (index + 1))`,
  which is algebraically identical to the target's
  `width + paddingLeft * (index + 1)`. The finding was wrong; the code was left
  unchanged. (The wrong pulse drawable and the reduced-motion source in the same
  finding were real and were fixed.)

## Wiring that silently produces empty/wrong data if missed

| Required wiring | Consequence if missed | Owner |
| --- | --- | --- |
| **`kotlinx.coroutines` is not declared in `libs.versions.toml`** — it resolves transitively via `lifecycle-runtime-ktx` / `work-runtime` | If that turns out to be `implementation`-scoped rather than `api`, both `BatchManager` and `StoreGatewayConnection` fail to compile. Declare it explicitly | Wave 3 |
| `StoreGatewayConnection.observeSelectedVoiceChannelId` defaults to `Observable.just(0L)` pending `StoreVoiceChannelSelected` | A backgrounded client **in a voice channel still drops the socket** | Wave 3 (voice/channel agents) |
| **`RestAPIBuilder` must register `Model.TypeAdapterFactory()`** (reference does it at `RestAPIBuilder.java:98`) | `ModelVoiceRegion`, `ModelAuditLog`, `ModelWebhook`, `ModelConnectionState` **deserialize to empty objects** from REST | REST agent / Wave 3 |
| **Gateway Gson must be the single `InboundGatewayGsonParser.gatewayGsonInstance`** | 20+ sites rebuild the `GsonBuilder` inline. The reference excludes only `STATIC`; the inline copies also exclude `TRANSIENT`, so `@Transient` DTO fields the gateway really sends are being **dropped** | Wave 3 |
| `Rules.setEmojiDataProvider(...)` at app init, before first parse | Named and unicode emoji render as literal text | Wave 3 (`StoreEmoji`) |
| `App.kt:26` → `ClockFactory.init(this)` | No NTP sync; plain device clock | Wave 3 |

## Back-press handlers still outside the registry — owned by `widgets/chat`

`AppActivity` + `BackPressHandlerRegistry` + `AppFragment.setOnBackPressed` are now
the single back-press path, and `WidgetTabsHost`/`WidgetHome` register through it.
Two chat screens still add their own `OnBackPressedCallback` to the activity's
dispatcher:

| File | Callback | Should become |
| --- | --- | --- |
| `widgets/chat/input/WidgetChatInput.kt:97-99` | `expressionBackCallback`, toggled with the expression tray | `setOnBackPressed { if (trayShown) { hideExpressionTray(true); true } else false }` |
| `widgets/chat/pins/WidgetChannelPinnedMessages.kt:45-53` | always-enabled; pops `parentFragmentManager` | `setOnBackPressed { … }`; it also leaves `isEnabled = false` behind on the fall-through path |

They still work today — the activity's callback is now pinned to the bottom of the
dispatcher stack, so fragment-owned callbacks run first — but they bypass the
priority ordering and cannot be reasoned about alongside the registry.

The reference equivalents (`WidgetChatInputAttachments.kt:375`,
`WidgetGifCategory.kt:95`) both use `setOnBackPressed`.

Also still needed, and blocked outside `widgets/home`:

- `WidgetHome.handleCenterPanelBack` cannot dismiss an open thread-creation
  composer. `StoreChannelsSelected.dismissCreateThread()` is annotated
  `@StoreThread` with no dispatcher-scheduled public wrapper, so a UI caller would
  violate store confinement. The reference calls it first in that method.
- The same method cannot re-enter the fullscreen call surface for a connected
  vocal channel; `WidgetCallFullscreen` and `WidgetCallPreviewFullscreen` do not
  exist in the target.

## Atomic cross-package renames — must be done in one edit

- **`ModelGuildMemberListUpdate`**: the reference field is `operations`, not
  `ops`, and `Item` is nested under `Operation`, not the outer class. Left as-is
  because `StoreChannelMembers.kt`, `GatewayContractTest.kt`, and
  `ChannelMemberListStateTest.kt` all reference the current names.
- **`utilities/messagesend/NonceGenerator.kt` is a mislocated duplicate** of the
  reference's `models/domain/NonceGenerator` (which now exists). `MessageQueue`
  should switch and the utilities copy be deleted.
- **`ModelPayload.Hello`**: both copies exist right now. Delete the top-level one
  at `gateway/io/GatewayPayloads.kt:17-20` and use
  `com.discord.models.domain.ModelPayload.Hello`. Only the gateway owner can
  remove theirs.

## Required init-order coupling — will silently degrade if missed

**`Rules.setEmojiDataProvider(...)` must be called during app init, before the
first parse.** `DiscordParser` builds its parsers once and the unicode-emoji rule
captures the catalog pattern at that moment — the same coupling the reference
has. The reference *throws* if the provider is unset; the reconstruction defaults
to an inert empty catalog because no emoji store exists yet.

Until whoever builds `StoreEmoji` wires this up: **named and unicode emoji render
as literal text**; custom emoji work fully. This will not fail loudly.

## Duplicate implementations to converge — two agents solved the same problem

**Discord `{name}` placeholder formatting was built twice in one wave.** The
canonical implementation is `com/discord/i18n/FormatUtils.kt` (the reference's
own first-party package, previously empty in the target): regex `\{(\S+?)\}`,
unknown tokens left literal, `!!hook!!` markers stripped, plus `Context`/`View`
`i18nFormat`/`i18nPlural` extensions. The app-framework agent independently added
`getNamedFormatString` internal to `com.discord.app` as a stopgap. **Delete the
stopgap and route `com.discord.app` through `com.discord.i18n`.** This contract
affects every formatted string in the app, so a single owner matters.

## Blocking gaps discovered during implementation

| Gap | Effect | Owning wave |
| --- | --- | --- |
| **`AndroidManifest.xml` declares none of the runtime permissions** (`RECORD_AUDIO`, `CAMERA`, `READ`/`WRITE_EXTERNAL_STORAGE`, `READ_CONTACTS`) | Every `AppPermissions` request is auto-denied. The audit separately found 20 of 21 `uses-permission`, all `uses-feature`, and the entire `<queries>` block absent | Wave 5 |
| Recovered strings use Discord's `{name}` placeholders, not `%1$s`; `Context.getString(id, args)` leaves them literal | Affects every formatted string in the app. A `getNamedFormatString` stopgap exists inside `com.discord.app` and must move to a shared utility | Wave 2a/4 |
| `utilities/rx/bindToComponentLifecycle` lacks the reference's Fragment-liveness filter (`isAdded && !isRemoving && activity?.isFinishing == false`) | Skipped because `AppFragment` did not exist at the time. It exists now — add the filter | Wave 3 |
| 17 migrated screens override `onViewCreated` without calling super | Left as-is to preserve current behavior; `@CallSuper` was removed from `AppFragment`/`AppDialog` to keep lint clean. Convert them to `onViewBound` | Wave 4 |
| Migrated screens now get `drawUnderSystemBars`, matching the reference | Real visual change — needs an on-device check | Wave 6 |
| `RestCallState.Failure` uses `RestApiError` as a stand-in | The reference's `utilities/error/Error` does not exist in the target yet; switch `Failure` to it when built | Wave 2a |

## Systemic: Gson never runs a Kotlin body-level property initialiser

Two reference bugs share one root cause, and there are probably more.

Gson allocates a DTO either via a Kotlin-emitted no-arg constructor (when every
parameter is defaulted) or via `Unsafe` (when R8 has deleted the constructor
entirely), then injects the parsed fields **afterwards**. Any `val x = <expr>`
in the class body has therefore already run against nulls/zeroes. Only `by lazy`
delegates escape, because they evaluate on first read.

**Audit any first-party DTO with a body-level `val` computed from a constructor
parameter.** Two found and FIXED as deliberate divergences (both computed on read
now; `equals`/`hashCode`/`toString` unchanged):

| DTO | Reference behaviour | Why we diverged |
| --- | --- | --- |
| `ForumTag.customEmojiId` | permanently `null` — forum tags with a **custom** emoji render without it | Restores intended behaviour; `widgets/forums/ForumPostModels.kt:44` feeds this into `MessageReactionEmoji` |
| `ModelSearchResponse.retryMillis` | permanently `0` — `SearchFetcher` re-issues an indexing search after **0 ms** | Reproducing it means a zero-delay retry loop against the live backend, which the project's live-backend safety rules forbid |

## Verified at bytecode level this wave

- **`StoreMediaSettings.setSelfMuted`/`toggleSelfMuted` must call the PUBLIC
  `isSelfMuted()` accessor, not the raw private field.** Vineflower renders
  `this.voiceConfiguration.isSelfMuted`, which *reads as* the private field;
  `javap -c` shows `invokevirtual VoiceConfiguration.isSelfMuted:()Z` — the
  muted-or-deafened accessor. The target had invented an `isSelfMutedRaw`, so
  `setSelfMuted(false)` while deafened early-returned and **never undeafened**.
  Fixed; `isSelfMutedRaw` deleted. This is the second case where the Kotlin
  decompile misled and only bytecode settled it (see also `CallStatus` ordinals).
- **`StoreV2.assertStoreThread` only logs**, and `MarkChangedDelegate.setValue`
  asserts nothing — confirmed at `out/com/discord/stores/StoreV2.kt:13-21`. It now
  reports through `com.discord.app.AppLog` rather than `android.util.Log`, which
  both matches the reference and gives tests a real seam (`android.util.Log` is a
  silent no-op under `unitTests.isReturnDefaultValues = true`).

- **`CallStatus` ordinal order is `ACTIVE_UNJOINED(0)`, `ACTIVE_JOINED(1)`,
  `INACTIVE_UNJOINED(2)`, `INACTIVE_JOINED(3)`** — confirmed by `javap -c`. The
  Vineflower source order is **alphabetical and misleading**; trusting it would
  have silently swapped call states.

## Reference bugs reproduced verbatim — DECISION NEEDED

Preserved for parity and commented in code, but these are genuine defects in the
reference, not intentional behavior. Parity argues for keeping them; correctness
argues for fixing them. Flagged for an explicit call rather than settled quietly.

| Reference bug | Location | Risk if kept |
| --- | --- | --- |
| `SparseMutableList`'s iterator uses `nextIndex() < lastIndex`, so it **never visits the final element** | `utilities/collections/SparseMutableList` | Makes `contains`/`remove` unreliable for the last slot. The reference presumably survived because its callers never hit that path; our reconstruction's call sites may differ |
| `Histogram.getReport` computes the running percentage with integer division | `utilities/collections/Histogram` | Wrong percentages in diagnostics only — low risk |
| **Invite expiry test is inverted** — `getExpirationTime() lcmp currentTimeMillis(); ifge → Invalid`, so an invite is "Resolved" only when its expiry is in the **past**. Confirmed at bytecode level | `WidgetChatListAdapterItemInvite` | Harmless today: the invite endpoint returns no `created_at`/`max_age`, so expiry is 0. Would bite the moment the payload carries a real expiry |
| **`ForumTag.customEmojiId` is always `null`.** It is a stored body-level property (`= emojiId?.toString()`), not a getter — `javap` shows a real backing field, excluded from `equals`/`hashCode`/`toString`, and R8 deleted the constructor entirely because nothing but Gson ever builds one. Gson allocates the class unsafely (no defaults ⇒ no no-arg ctor), so the initialiser never runs | `api/channel/Channel.kt` (`ForumTag`) | Forum tags with a **custom** emoji render without their emoji (`ForumPostModels.kt:44` passes the null id into `MessageReactionEmoji`). Unicode-emoji tags are unaffected. Pinned by `ReadyGuildChannelDtosTest` |
| **`ModelSearchResponse.retryMillis` is always `0`.** Every constructor parameter is defaulted, so Kotlin emits a no-arg constructor; Gson uses it, computes `retryMillis` from a still-null `retryAfter`, and only then injects the parsed fields. (`hits` escapes this because it is `by lazy`.) | `models/domain/ModelSearchResponse.kt` | The reference's `SearchFetcher` re-issues an indexing search after **0 ms** instead of `retry_after` seconds. Bounded only by `indexingRetryCount`. Pinned by `NewModelContractsTest` |

Both of the above are the same root cause — **Gson never runs a Kotlin body-level property
initialiser** — and both are reproduced rather than fixed. If the decision is to fix them,
the one-line change in each case is to make the property a computed `get()`; that changes
no `equals`/`hashCode`/`toString`, and only removes the (always-null/zero) field from
serialisation output.

## Reference oddities reproduced deliberately — pinned by tests, do not "fix"

- **`RadioManager` init.** The reference unchecks every button, then checks index
  0 *only if nothing was checked to begin with* — so a group arriving with a
  selection ends up empty. Every reference caller applies the model-derived
  selection immediately after construction, which masks it.
- **Default tooltip layouts have no `android:id`**, so every default tooltip is
  keyed `View.NO_ID (-1)` in the floating-view map — only one default tooltip can
  exist at a time.

## Known deltas in files owned by another agent

- `ToolbarTitleLayout.setSubtitle` hides on `isNullOrEmpty`; the reference
  `ViewExtensions.setTextAndVisibilityBy` hides on **blank**, so a
  whitespace-only subtitle stays visible in the reference. Also exposes the
  presence dot as `presence` rather than `presenceView`.
- `UserAvatarPresenceView.ViewState` has no `streamContext` field, so
  `UserAvatarPresenceViewController` combines user + presence only and drops the
  reference's third `StreamContextService.getForUser` source.

## Misplaced code to relocate

| Item | Current location | Correct location |
| --- | --- | --- |
| `STATIC_IMAGE_EXTENSION` | hosted in `IconUtils` as `staticImageExtension` | `utilities/string/` |
| `DISCORD_EPOCH` | private const in `IconUtils` **and** in `UserUtils` | `utilities/SnowflakeUtils` |
| `IconUtils.getForEmoji` | added to `IconUtils` | reference builds emoji URLs in `models/domain/emoji/ModelEmojiCustom` |
| `ModelPayload.Hello` | `gateway/io/GatewayPayloads.kt`, with an extra `trace` field the reference lacks | `models/domain/ModelPayload` (nested) |
| `QuoteSpan`, `BulletSpan`, `VerticalPaddingSpan`, `BlockBackgroundSpan`, `SpoilerSpan` | `textprocessing/spans/` | `utilities/spans/` (reference has `SpoilerSpan` in `defpackage`, an obfuscation artifact). **Duplicate-class collision risk if another agent adds `utilities/spans/QuoteSpan`** |
| `TextProcessingResources.kt` — local copies of `ColorCompat.getThemedColor`, `DimenUtils.dpToPixels`, `IconUtils.getMediaProxySize`, `ModelEmojiCustom.getImageUri`, `StringUtilsKt.toPunyCodeASCIIUrl` | `textprocessing/` | their real packages, once those exist. `getMediaProxySize` is now a **5th** copy — see the convergence list above |
| `getNamedFormatString` | `com.discord.app` (stopgap) | delete; use `com.discord.i18n.FormatUtils` |

Sticker URLs deliberately NOT moved: `StickerUtils.getCDNAssetUrl` already matches
the reference exactly, and the reference does not put them in `IconUtils`.

## Converge onto canonical implementations

### `mediaProxySize` — 4 duplicate implementations
All should call `IconUtils.getMediaProxySize`:
`utilities/stickers/StickerUtils.kt:52-59`, `views/PileView.kt:127`,
`views/user/SettingsMemberView.kt:96`,
`widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt`.

### Hand-built CDN URLs — 30 files
All should call `IconUtils`. Nine hardcode `.webp` and therefore ignore the API
28/29 PNG fallback; several ignore the user's animation setting.

**Avatars** — `stores/StoreStream.kt:1027` (this is the audit's "default avatar
yields null" site → `IconUtils.getForUser`),
`views/permissions/ChannelPermissionOwnerView.kt:62`,
`views/user/SettingsMemberView.kt:125,128`,
`widgets/auth/AuthInviteInfoView.kt:124,131`,
`widgets/channels/list/WidgetChannelsList.kt:236`,
`widgets/chat/list/ViewEmbedGameInvite.kt:163`,
`widgets/chat/list/adapter/WidgetChatListAdapter.kt:384`,
`widgets/friends/SuggestedFriendView.kt:40`,
`widgets/guilds/invite/WidgetInviteInfo.kt:158`,
`widgets/guilds/list/WidgetGuildsList.kt:304`,
`widgets/guildscheduledevent/GuildScheduledEventItemView.kt:165`,
`widgets/user/WidgetUserMutualFriends.kt:127`,
`widgets/user/search/ViewGlobalSearchItem.kt:129,174`,
`widgets/user/search/WidgetGlobalSearchDialog.kt:352`

**Per-guild member avatars/banners** — `widgets/channels/UserAkaView.kt:77`,
`widgets/user/WidgetUserMutualGuilds.kt:102`,
`widgets/user/profile/UserProfileHeaderView.kt:105,106`

**Guild icons/banners** — `views/guildboost/GuildBoostConfirmationView.kt:44`,
`widgets/auth/AuthInviteInfoView.kt:69,94`,
`widgets/chat/input/emoji/GuildIcon.kt:16`,
`widgets/guilds/invite/WidgetInviteInfo.kt:117`,
`widgets/guilds/list/WidgetGuildsList.kt:252,281`,
`widgets/guildscheduledevent/GuildScheduledEventItemView.kt:189`,
`widgets/user/WidgetUserMutualFriends.kt:108`,
`widgets/user/WidgetUserMutualGuilds.kt:90`,
`widgets/user/search/ViewGlobalSearchItem.kt:98`

**Channel icons** — `widgets/channels/WidgetChannelTopic.kt:38`,
`widgets/channels/list/WidgetChannelsList.kt:235`,
`widgets/guilds/invite/WidgetInviteInfo.kt:162`,
`widgets/guilds/list/WidgetGuildsList.kt:303`,
`widgets/user/search/ViewGlobalSearchItem.kt:127`

**Emoji** — `utilities/presence/PresenceUtils.kt:293`,
`utilities/textprocessing/TextProcessingResources.kt:68`,
`widgets/botuikit/views/ComponentView.kt:56`,
`widgets/chat/input/emoji/WidgetEmojiPicker.kt:132` (hardcodes `png` instead of
`staticImageExtension` — a real divergence), `widgets/user/profile/UserStatusPresenceCustomView.kt:59`

**Role icons / app assets** — `widgets/roles/RoleIconView.kt:27`,
`widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt:96,126`,
`widgets/chat/list/ViewEmbedGameInvite.kt:170,177,182`

### Temporary stand-ins to collapse when the real type lands
Created because the real dependency does not exist yet. Each is a seam the
owning agent should remove.

| Missing type | Stand-in | Location |
| --- | --- | --- |
| `StoreSlowMode.Type` | `enum class SlowModeType` | `utilities/permissions` |
| `widgets.chat.list.NewThreadsPermissionsFeatureFlag` | `PermissionUtils.newThreadsPermissionsEnabled: (Long) -> Boolean`, defaults `false` | `PermissionUtils.kt` |
| `utilities.PermissionOverwriteUtils` (`denies`/`allows`/`grantsAccessTo`/`deniesAccessTo`) | `internal fun PermissionOverwrite.denies` — **delete when that file lands** | `PermissionUtils.kt` |
| `utilities.platform.Platform` | literal `"Spotify"` | `ActivityUtils` |
| `textprocessing.node.EmojiNode` | Fresco drawee span; unicode emoji as plain text | `PresenceUtils` |
| `utilities.color.ColorCompat` | `ContextCompat.getColor` | `PresenceUtils` |
| `utilities.SnowflakeUtils.DISCORD_EPOCH` | private const (also duplicated in `IconUtils`) | `UserUtils` |
| `utilities.premium.PremiumUtilsKt.grantsAccessToPremiumStickers` | inlined tier test | `PermissionUtils` |
| `b.a.k.b` string renderer | private `{placeholder}` / `!!` / `**bold**` renderer | `PresenceUtils` |

### Converge onto `ChannelUtils` / `PermissionUtils`
Stores and widgets re-invent these predicates and disagree. Highest-severity
first:

- `stores/StoreChannels.kt:77` READY filter uses `filterNot(::isThread)`; the
  reference filters `!isManaged` (`StoreChannels.java:345`).
- `stores/StoreChannels.kt:105` is missing the `if (channel.isManaged()) return`
  guard (`StoreChannels.java:262`).
- `stores/StoreChannels.kt:276` `displayName()` returns `channel.name` with no
  private-channel recipient fallback and no null→`""` → use
  `Channel.getDisplayName()` (affects `:115, :149, :235, :245, :262, :263`).
- `stores/StorePermissions.kt:102,111` still call the deprecated wrappers — no
  stage instances, no thread membership, no `isPrivate → ALL` shortcut. Needs
  `StoreStageInstances` + `StoreThreadsJoined` to exist first
  (`StorePermissions.java:92-95`).
- `stores/StoreChannels.kt:223` `type == GUILD_TEXT` currently **excludes
  forums**.
- Inline predicates to replace: `StoreChannels.kt:207,209,278-279`,
  `StoreReadStates.kt:308`, `StoreUserGuildSettings.kt:275`,
  `StoreChannelsSelected.kt:89`,
  `widgets/channels/memberlist/WidgetChannelMembersList.kt:170`,
  `widgets/voice/model/CallModel.kt:62,81`,
  `views/permissions/ChannelPermissionOwnerView.kt:52`,
  `WidgetChannelSidebarActions.kt:28`, `WidgetChatInput.kt:419`,
  `MessageActionCoordinator.kt:71`, `WidgetChatListAdapter.kt:266,346`,
  `chat/list/model/ChatListRows.kt:66`, `channels/list/ChannelListRows.kt:46,92`,
  `widgets/status/WidgetForumPostStatus.kt:60`, `WidgetThreadStatus.kt:51`.

### `notifyDataSetChanged()` → `MGRecyclerAdapterSimple` / `DiffCreator` — 20 sites
`widgets/voice/feedback/FeedbackView.kt:103`,
`widgets/user/WidgetUserMutualGuilds.kt:69`,
`widgets/user/WidgetUserMutualFriends.kt:75`,
`widgets/user/search/WidgetGlobalSearchDialog.kt:269,298`,
`widgets/user/search/WidgetGlobalSearch.kt:112`,
`widgets/user/profile/UserProfileHeaderView.kt:145`,
`widgets/user/profile/UserProfileConnectionsView.kt:61`,
`views/OverlayMenuBubbleDialog.kt:188`,
`widgets/share/WidgetIncomingShare.kt:114,125`,
`views/stages/StageCardSpeakersView.kt:51`,
`widgets/channels/permissions/PermissionOwnerListView.kt:70`,
`widgets/chat/list/ViewEmbedGameInvite.kt:142`,
`widgets/guilds/create/GuildTemplateChannelsView.kt:36`,
`widgets/guilds/invite/ViewInviteSettingsSheet.kt:119`,
`widgets/chat/input/gifpicker/GifLoadingView.kt:77`,
`widgets/chat/input/emoji/WidgetEmojiPicker.kt:156,182`.

The three inside `MGRecyclerAdapterSimple:89`, `DiffCreator:116`, and
`SelfHealingLinearLayoutManager:41` are intended fallbacks — leave them.

Also: guild-folder drag persistence (Phase 6) should be rebuilt on
`CategoricalDragAndDropAdapter` + `DragAndDropHelper`, and the guild-selection
store should persist through `LeastRecentlyAddedSet` (the audit found it was
changed to `LinkedHashSet` under an unchanged persistence key).

### Raw `ACTION_VIEW` → `UriHandler.handle` — 4 sites
`app/AppActivity.kt:379`, `utilities/captcha/CaptchaHelper.kt:160`,
`widgets/servers/NotificationHighlightsSettingsView.kt:53`,
`widgets/botuikit/views/ButtonComponentView.kt:82`.

### One-line wiring still required
- **`App.kt:26` must become `ClockFactory.init(this)`** or the app keeps the
  plain device clock and there is no NTP sync at all. `ClockFactory.init` has a
  defaulted parameter so the current call site still compiles — it just does
  nothing useful.

### Pending store hooks
`UriHandler.handleOrUntrusted` takes injectable `trustedDomainResolver` and
`untrustedLinkHandler` because `StoreMaskedLinks` and `StoreNotices` do not
exist. Until a store installs them, only the implicit first-party host list
grants trust and an **untrusted link is dropped rather than confirmed**. The
`masked_link_*` strings exist in res but there is no dialog widget yet.
`UriHandler.handleUriSpecialCase` returns false pending
`RoleSubscriptionsLinkingUtil`.

## Deliberate divergences from the reference — do not "fix" these

| Divergence | Reason |
| --- | --- |
| `toString()` redaction on `Message`, `User`, `MeUser`, `ActivitySecrets` | Project security contract; reference generated output printed these |
| `HOST_CDN` developer fallback branch dropped from `IconUtils` | `BuildConfig` is absent from the JADX export; only the production CDN branch is reachable. Inferred `https://cdn.discordapp.com` from `App.onCreate` passing it to `ModelEmojiCustom.setCdnUri` |
| `orientation` attr widened to `format="string\|enum"` | Value was already dead in 126.21; modern AAPT2 rejects the recovered combination |

## Least-corroborated reconstructions — re-verify against DEX/smali

| Item | Why it is uncertain |
| --- | --- |
| `IconUtils.getGiftSplashUrl` | Reconstructed from a register-level JADX dump: `if (r5 <= r6)` → strictly-greater predicate, `d0.t.k.last(…)` → clamp-not-throw |
| `MGImagesConfig` builder writes | `aVar.c = true` → `setDownsampleEnabled`, `bVar.f576b = true` → `setShouldDownscaleFrameToDrawableDimensions`; inferred from field ordering, not names |
| `ClientStatuses` parameter order | Decompile has neither constructor nor `toString`; a transposition silently swaps web and mobile presence |
| `MessageQueue.doSend` | "Method not decompiled"; ordering of `validateMessage()` vs Preprocessing emission unverifiable from Java |
| `StoreNotifications.handleMessageCreate` | "Method dump skipped, instructions count: 332" — the entire notification-delivery decision is unreadable |
| `WidgetChatListActions.configureUI` | "instructions count: 947" — the per-row visibility matrix for the action sheet is unrecoverable from this decompile |
| **`PermissionUtils.computeThreadPermissions`, new-flag branch** | JADX emitted `if (isPrivateThread && !hasJoined) { can(MANAGE_THREADS, perms); }` with the result **discarded and no return**. Reconstructed as `if (isPrivateThread && !hasJoined && !can(MANAGE_THREADS, perms)) return NONE` by symmetry with the legacy branch. Highest-risk reconstruction in the permissions slice — a wrong reading silently grants or denies private-thread access |
| `ManageMessageContext.canAddReactions` | JADX reads `message.isLocal() && (...)`; implemented as `!message.isLocal()` since reacting to an unsent local message is impossible |
| `RoleUtils.getHighestHoistedRole` hoist filter | Decompiled to `(role == null \|\| !role.hoist) ? false : false`; restored as `guildRoles[it]?.hoist == true` |
| `getActivityHeader` platform naming | Reference routes `ActivityPlatform` through `Platform.from()`, which does not exist; PS4/PS5/XBOX/SAMSUNG/SPOTIFY mapped, desktop/android/ios/unknown fall back to "Playing a game" |

## Animated stickers — root cause found and fixed (sticker/rlottie wave)

**`StickerUtils.getCDNAssetUrl` had the wrong parameter defaults, and that alone stopped every
APNG sticker from animating.** The reference signature is
`getCDNAssetUrl(sticker, size = DEFAULT_STICKER_SIZE_PX, passthrough = true)` — proved from
`javap -c` of `getCDNAssetUrl$default`: mask bit `2` substitutes `getDEFAULT_STICKER_SIZE_PX()`,
mask bit `4` substitutes `iconst_1` (**true**). The target declared `size = null,
passthrough = false`. `fetchSticker` calls the defaulted overload
(`getCDNAssetUrl$default(..., 6, null)` in the reference), so the target was downloading
`media.discordapp.net/stickers/<id>.png?passthrough=false`, which the media proxy serves as a
**flattened single-frame PNG**. `ApngDrawable` then had nothing to animate.
Only the two deliberate still-image call sites pass `passthrough = false` (reference
`b/a/y/q0/d.java`, mask `2`); they now do.

Consequences worth knowing:
- `cacheDir/stickers/<id>.png` is keyed by id only, so **an install that already cached a
  flattened PNG keeps showing it** until the app cache is cleared. Same scheme as the reference,
  which never wrote a flattened file there — no migration was invented.
- `DEFAULT_STICKER_SIZE_PX` is now a lazy val over `Resources.getSystem().displayMetrics.density`
  (reference: `DimenUtils.dpToPixels(160)`). **`com.discord.utilities.dimen.DimenUtils` still does
  not exist in the target** — move the constant there when that file lands.
- `StickerUtils` now calls `IconUtils.getMediaProxySize` (one of the four duplicates listed above
  is gone).

**`stores/StickerAnimationSettingsStore.kt` is deleted.** It was a shadow store over a
`SharedPreferences` file (`discord_user_settings`) that nothing else writes — `Persister` stores
Kryo blobs in `filesDir`, not SharedPreferences — behind a one-shot `initialized` latch, so it
returned the compiled-in default forever and the account's sticker-animation setting had no effect.
`StickerView` now reads `StoreStream.getUserSettings().observeStickerAnimationSettings()`, which is
what the reference does (`StickerView.kt:154,184`).

**`libdsti.so` JNI contract is verified, not inferred.** `llvm-nm -D` on all four ABIs shows exactly
seven exports, all short-form (no `__` descriptor suffix), so JNI binds by name only:
`create`, `createCache`, `createWithJson`, `destroy`, `getFrame`, `replaceColors`, `setLayerColor`,
all prefixed `Java_com_discord_rlottie_RLottieDrawable_00024Companion_`. There is no `JNI_OnLoad`.
The reference declares only six — **`destroy` is exported by the library but the reference never
declared or called it**; the target's `external fun destroy(ptr: Long)` is an addition, and its
`(J)V` descriptor is now confirmed by disassembly (`x86_64/libdsti.so+0x49b3f`: null-checks `rdx`,
calls `LottieInfo::~LottieInfo`, then `operator delete`). `createCache` likewise confirmed `(JII)V`.

## Message search + quick switcher (Phase 12) — handoff

### `StoreSearch` and friends: the interface the store wave must implement

Message search needs four stores the project does not have. Rather than build a competing store
under `com.discord.stores`, the search screens talk to interfaces declared in
`widgets/search/SearchStore.kt`, and `widgets/search/DefaultSearchStore.kt` supplies a working
implementation behind `SearchStoreProvider`. **When the real stores land, either implement these
interfaces or delete them and point the widgets at the stores; then call
`SearchStoreProvider.install(...)` once from `StoreStream` and delete `DefaultSearchStore.kt`.**
The split below is the reference's, member for member.

`SearchStore` (ref `StoreSearch`)
- `val storeSearchInput: SearchInputStore`, `val storeSearchQuery: SearchQueryStore`,
  `val storeSearchData: SearchDataStore`
- `fun initForGuild(guildId: Long, searchStringProvider: SearchStringProvider)`
- `fun initForChannel(channelId: Long, searchStringProvider: SearchStringProvider)`
- `fun getDisplayState(): Observable<DisplayState>` — `enum DisplayState { SUGGESTIONS, RESULTS }`
- `fun getHistory(): Observable<Collection<List<QueryNode>>>`
- `fun loadInitial(queryString: String, searchStringProvider: SearchStringProvider)`
- `fun loadMore(oldestMessageId: Long)`
- `fun clearHistory()`, `fun clear()`

`SearchInputStore` (ref `StoreSearchInput`)
- `val currentParsedInput: Observable<List<QueryNode>>`
- `val forcedInput: Observable<List<QueryNode>>`
- `val isInputValid: Observable<Boolean>`
- `fun init(searchStringProvider)`, `fun updateInput(input: String)`, `fun clear()`
- `onFilterClicked(filterType, searchStringProvider, query)`,
  `onFromUserClicked(userSuggestion, fromFilterString, query)`,
  `onMentionsUserClicked(userSuggestion, mentionsFilterString, query)`,
  `onInChannelClicked(channelSuggestion, inFilterString, query)`,
  `onHasClicked(hasAnswerOption, hasFilterString, hasAnswerString, query)`,
  `onQueryClicked(query)`

`SearchQueryStore` (ref `StoreSearchQuery`)
- `val state: Observable<SearchState>`
- `parseAndQuery(searchTarget, queryString, searchStringProvider, includeNsfw, onQueryParsed)` —
  the reference takes the `StoreSearch` itself and calls back into `persistQuery`; the callback
  removes the cycle
- `loadMore(searchTarget, oldestMessageId)`, `clear()`

`SearchDataStore` (ref `StoreSearchData`)
- `get(): Observable<SearchData>`, `init(searchTarget)`, `clear()`

Reference details the implementation must preserve, all currently honoured by `DefaultSearchStore`:
- `DisplayState` follows both inputs: any parsed-input emission drops to `SUGGESTIONS` *and* clears
  the query store; any `queryFetchState != NONE` switches to `RESULTS`.
- `isInputValid` is `nodes.any { it !is FilterNode }` — confirmed at bytecode level
  (`StoreSearchInput$1.call`); JADX renders it as an unreadable `all`-shaped loop.
- Input parsing is `leadingEdgeThrottle(100 ms)` then `distinctUntilChanged` then parse on the
  computation scheduler; validation is a separate `leadingEdgeThrottle(50 ms)` off the AST subject.
- `loadMore` only fires from `COMPLETED` + `hasMore`, and republishes with `hasMore = false` so a
  second scroll cannot double-request.
- `parseAndQuery` gives the search data one second to arrive, then fails the search.
- Guild `SearchData` is `leadingEdgeThrottle(3 s)`; the reference uses
  `ObservableWithLeadingEdgeThrottle.combineLatest`, which does not exist here — a plain
  `combineLatest` plus `leadingEdgeThrottle` stands in.

Also needed from the store wave:
- **`SearchTarget` currently lives at `utilities/search/SearchTarget.kt`.** The reference nests it as
  `StoreSearch.SearchTarget`; the history cache, the fetcher and the query store are all keyed on it,
  so it had to exist before the store did. Move it back when `StoreSearch` lands.
- `StoreGuildsNsfw` is not reachable from `StoreStream`, so the NSFW gate only checks
  `MeUser.nsfwAllowance`; a guild whose NSFW gate has not been agreed still returns age-restricted
  results. Reference: `StoreSearch.includeNsfw` also requires `isGuildNsfwGateAgreed(guildId)`.
- `StoreStream.handleSearchFinish(searchState)` is never called; the reference dispatches every
  search state into `StoreStream` so other surfaces can react.

### New package `com/discord/utilities/search/**` — unowned, created here

The whole search domain layer was missing and is a hard dependency of `widgets/search`. Added:
`query/FilterType`, `query/node/{QueryNode, content/ContentNode, filter/FilterNode,
answer/{AnswerNode, ChannelNode, UserNode, HasNode, HasAnswerOption}}`, `query/parsing/QueryParser`,
`strings/{SearchStringProvider, ContextSearchStringProvider}`, `validation/SearchData`,
`network/{SearchQuery, SearchFetcher, state/{QueryFetchState, SearchState}}`,
`history/{SearchHistoryCache, PersistedSearchHistoryCache}`, `suggestion/{SearchSuggestionEngine,
entries/*}`, `SearchUtils`, `SearchTarget`.

Deviations to close later:
- `FilterNode.render` uses a plain bold `StyleSpan`; the reference uses
  `FontUtils.getThemedFont(context, R.attr.font_display_bold)` plus `TypefaceSpanCompat`, neither of
  which exists in the project.
- `PersistedSearchHistoryCache` replaces `MGPreferenceSearchHistoryCache` (same `Persister` key
  `SEARCH_HISTORY_V2`, same 10-target / 5-query caps).
- The suggestion entries are grouped into one `suggestion/entries/Suggestions.kt` rather than one
  file per class; the FQNs match the reference.

### Enum orders recovered from static initialisers — the decompiled source order is wrong

Vineflower prints these alphabetically. The real ordinals (from the `$VALUES` array) are:
- `FilterType`: `FROM, MENTIONS, HAS, IN` — corroborated by `UserNode.updateQuery` (ordinal 0 maps
  to `author_id`, 1 to `mentions`).
- `HasAnswerOption`: `LINK, EMBED, FILE, VIDEO, IMAGE, SOUND, STICKER` — the icon table is indexed
  by it.
- `SearchSuggestion.Category`: `FILTER, MENTIONS_USER, FROM_USER, HAS, BEFORE_DATE, IN_CHANNEL,
  RECENT_QUERY` — the header-label table is indexed by it. `BEFORE_DATE` has no producer on mobile
  but keeps its slot.
- `QueryFetchState`: `NONE, INDEXING, IN_PROGRESS, LOADING_MORE, COMPLETED, FAILED`.
- `StoreSearch.DisplayState`: `SUGGESTIONS, RESULTS`. `SearchTarget.Type`: `GUILD, CHANNEL`.

### Quick-switcher scoring weights — RECOVERED, previously reported unrecoverable

`audit-widgets-user-friends.md` section 7 says `WidgetGlobalSearchScoreStrategy.java:65,158` are
JADX-broken and "the exact weights **cannot be recovered** from this decompile". **They can.**
`discorddecompile/out/com/discord/widgets/user/search/WidgetGlobalSearchScoreStrategy.kt` decompiles
cleanly, and the one genuinely ambiguous part (the presence `when`, because the class has a
`$WhenMappings` table) was settled with `javap -c`: `scoreUser` calls `Enum.ordinal()` directly, so
the presence scores key off raw `ClientStatus` ordinals, not a switch map. The enum weights were
confirmed a second way from `SearchType.<clinit>` bytecode. Everything is transcribed in
`widgets/user/search/WidgetGlobalSearchScoreStrategy.kt` and pinned by
`WidgetGlobalSearchScoreStrategyTest`.

### `WidgetGlobalSearchDialog` was the wrong feature — split, with a compatibility shim

The target's `WidgetGlobalSearchDialog` was a DM quick-switcher plus a group-DM recipient picker.
The reference class is a thin `AppDialog` host for `WidgetGlobalSearch`. It is now that host; the
recipient picker moved verbatim to `widgets/user/search/WidgetGroupDmPickerDialog.kt`.
`WidgetChannelsList.kt:46,49` still calls `show(fragmentManager, Boolean)`, so a `@Deprecated`
two-arg overload routes `false` to the quick switcher and `true` to the picker. **`WidgetChannelsList`
should call `WidgetGlobalSearchDialog.show(fm)` and `WidgetGroupDmPickerDialog.show(fm)` directly;
then delete the overload.** The picker's real reference home is
`widgets/guilds/invite/WidgetGroupInviteFriends`, which is not reconstructed — fold it in when that
lands.

### Edits made outside the search slice

- `widgets/channels/WidgetChannelSidebarActions.kt` — `onSearch` now takes the `Channel`, not the
  channel id: a guild channel searches its *guild*, a DM searches itself, and the host cannot tell
  them apart from an id. `WidgetHome` assigns it (`openSearch`). This closes audit finding **D2**.
- `widgets/tabs/WidgetTabsHost.kt` — three lines in `consumeOwnedRoute` so a `/query/` App Action
  opens the quick switcher pre-filled instead of being parsed and dropped (audit finding **D4**).
  The prefix construction lives in `WidgetGlobalSearchDialog.showForQuery`. **Not handled:** a query
  carrying `messageText`, which the reference forwards to `WidgetIncomingShare.launch(activity,
  text, query)`; `WidgetIncomingShare` has no text-only entry point yet, so such a link opens the
  switcher and drops the text.

### Reference bug reproduced — DECISION NEEDED

**`QueryNode.Preprocessor.preprocess` never demotes stale answers.** It maps the node list into a
fresh `ArrayList`, demoting `AnswerNode`s that no longer resolve, and then consolidates the
*original* list — discarding the mapped one. JADX (`QueryNode.java:54-65`) and Vineflower agree
instruction for instruction. Consequence: a `from:` user who left the guild, or an `in:` channel
that was deleted, still reaches `SearchQuery.Builder` and contributes `author_id=null` or nothing at
all, instead of degrading to searchable text. Behaviour is preserved; the intended pass is kept as
`QueryNode.Preprocessor.demote` so the fix is one call away.

### Degraded because a dependency is absent

| What | Degradation | Blocked on |
| --- | --- | --- |
| Quick-switcher frecency | `ChannelFrecencyTracker` is fed from `StoreChannelsSelected.observeId()` inside `WidgetGlobalSearchViewModel`, so it only accumulates while a switcher has existed this session and does not persist | tracking belongs in `StoreChannelsSelected` |
| Quick-switcher user rows | `ItemUser.aliases` is always empty, so nicknames are neither matched nor shown as secondary text | a per-guild member-name index (`WidgetGlobalSearchModel.UsersContext.members` in the reference) |
| Guild rail | `Item.connectedToVoice` is always false | `StoreVoiceChannelSelected` |
| Guild rail | `unavailableGuildCount` is never populated | reference `WidgetGlobalSearchGuildsModel` folds unavailable guilds into the last item |
| Quick switcher | add-a-friend / join-server buttons on the no-results card stay hidden | nothing produces those destinations from here yet |
| `WidgetQuickMentionsDialog` | the mentions tab draws its own toolbar inside the pager | `WidgetUserMentions.create(isEmbedded: Boolean)` — `widgets/user` is another wave's file |
| Search and quick switcher | no analytics (`AnalyticsTracker.quickSwitcherOpen()`, search-result tracking) | `AnalyticsTracker` |

### Unverified

- `WidgetSearch` hint strings were matched to resource ids by elimination: `search_in` is used for
  both a guild and a group DM, `search_dm_with` for a 1:1 DM, bare `search` otherwise. The numeric
  ids in the decompile (`2131895350`, `2131895317`, `2131895292`) were not cross-checked against a
  resource table.
- `SearchFetcher`'s indexing retry loop is `repeatWhen { timer(retryMillis) }` plus
  `takeUntil { !isIndexing }`; the reference uses obfuscated rx operators (`j0.l.a.y`,
  `j0.l.e.e.c`) that were read structurally rather than proven. The retry cadence should be checked
  against a guild that is genuinely still indexing.
- Gradle was not run (project rule), so nothing here is compile-verified.

## `contact_sync` + `phone` wave — seams, decisions and one-line wirings

13 of 13 reference classes built (`widgets/contact_sync/**` 9, `widgets/phone/**` 4).
`WidgetContactSyncViewModelKt` is the file-level facade of `WidgetContactSyncViewModel.kt`,
as in the reference.

### libphonenumber — SETTLED, do not add the dependency

`utilities/phone/PhoneUtils`'s two hand-rolled regexes **are** the reference implementation.
`javap -c com.discord.utilities.phone.PhoneUtils` shows the literal `ldc "^[-() \d]+$"`, and
`unzip -l discord126.21-dex2jar.jar | grep i18n/phonenumbers` returns **zero** classes — the
reference APK does not ship libphonenumber at all. No dependency is needed and none was added.

### One-line wirings that make this feature live

| Wiring | Consequence today | Owner |
| --- | --- | --- |
| `StoreStream` never constructs `StorePhone` **or** `StoreContactSync` — neither class is referenced anywhere else in the repo | Both are dead code. Install them into `com.discord.widgets.phone.PhoneCountryCodes.store` and `ContactSyncDependencies.contactSync`, then delete those seams | store wave |
| `StoreUserConnections` / `StoreExperiments` are constructed but private on `StoreStream` (no `@JvmStatic` accessor) | Contact sync can never find an existing `contacts` connection, so it always creates a new one; `isPhoneVerifyCaptchaEnabled` is hardcoded `false` | store wave |
| `WidgetFriendsList.kt:39` hides `R.id.menu_friends_contact_sync` because `onContactSync` is never assigned | The only in-app entry point to the whole flow is invisible. Assign `onContactSync = { WidgetContactSync.launch(requireContext()) }` | `widgets/friends` owner |
| `views/phone/PhoneOrEmailInputView.bind` leaves `onCountryCodeClick` null by default | The reference wires the picker **inside** `bind(fragment)` (`PhoneOrEmailInputView.b()` → `WidgetPhoneCountryCodeBottomSheet.Companion.show(fragment.parentFragmentManager)`), so every phone screen gets it for free. `WidgetContactSync` passes it explicitly; **`widgets/auth` and `widgets/user/phone` still cannot change country** | `views/phone` owner |
| `StoreNux` does not exist | Finishing an onboarding contact-sync flow does not tick the onboarding step (`ContactSyncDependencies.onOnboardingCompleted`) | Wave 8 |
| `utilities/analytics/AnalyticsTracker` does not exist | The whole contact-sync funnel is recorded through `ContactSyncAnalytics`, currently `None`. Six reference signatures are transcribed there | analytics wave |
| `AndroidManifest.xml` still declares no `READ_CONTACTS` | `requestContacts` is auto-denied, so the flow correctly parks on its "needs permission" state and never uploads | Wave 5 (already tracked) |

### Code placed here temporarily

- **`widgets/contact_sync/ContactsProvider.kt`** is the port of the reference's
  `utilities/contacts/ContactsProviderUtils`. Move it to `com/discord/utilities/contacts/` when
  that package lands; keep the interface (it is what makes the flow testable).
- **`widgets/contact_sync/ContactSyncLearnMore.kt`** is a **third** hook-swapping helper (see
  "Third hook-formatting implementation" above). It additionally strips Discord's `$` hook marker,
  which `I18nSpans` does not — `$[Learn More](learnMoreHook)` currently renders as `$Learn More`
  anywhere else that pattern is used. Fold all three into `com.discord.i18n`.
- **`widgets/phone/PhoneCountryCodes.kt`** duplicates `StorePhone`'s `assets/data/countries.json`
  read so the picker works before the store is wired. Delete with the seam.

### Reference bug reproduced — DECISION NEEDED

**`PhoneUtils.getTranslatedStringForCountry` can never resolve a translation.** Verified at
bytecode level: it looks up `country_name_<phoneCountryCode.lowercase()>`, i.e.
`country_name_+1`, which is not a legal resource name, so `getIdentifier` always returns 0 and the
English `name` is used. The 256 recovered `country_name_xx` strings are keyed on **alpha-2** and
are dead in the reference. Reproduced for parity; changing the key to `data.alpha2` is a one-word
fix that lights all 256 translations up.

### Deliberate divergences (documented in code)

- `ContactSyncFriendSuggestionListAdapter` detaches the checkbox listener before `setChecked`.
  The reference reassigns it *after*, so a recycled row fires the **previous** row's callback and
  selects the wrong user — that decides who receives a friend request.
- `WidgetContactSync.getContactSyncModeFromIntent` defaults to `DEFAULT` instead of throwing.
  The reference `Objects.requireNonNull`-casts the extra, which crashes when `ContactSyncUpsellSheet`
  is shown from the friends list (no extra present).
- The captcha retry uses `CaptchaHelper.tryShowCaptcha` directly; the reference's
  `widgets/captcha/WidgetCaptcha` + `ActivityResultLauncher` screen does not exist here.

### Privacy surface — where contact data is handled

`ContactsProvider` is the only class that reads the address book. Its two failure paths log the
exception *class name* and nothing else. Numbers flow provider → `WidgetContactSyncViewModel.onContactsFetched`
→ `RestAPIParams.UploadContacts` (already redacted `toString`) and are never stored. `ViewState`,
`StoreState` and `Item.FriendSuggestionItem` all override `toString` to redact the phone number,
the typed name, the bulk-add token and the address-book nickname. `ContactSyncAnalytics.nameSubmitted`
takes a word count and a character count, never the name.

**Still leaking:** `api/friendsuggestions/FriendSuggestionReason` is a plain data class whose
generated `toString` prints `name` — the contact-book nickname. Add a redacting `toString` there
(file owned by the api wave).

## Guild sidebar — inputs with no source yet

`widgets/guilds/list/GuildsListStoreHooks` is the single seam the rebuilt sidebar reads these
through. Every default is inert (empty stream / disabled feature / no-op), so the degradation is
visible rather than wrong. Install each from app start-up and delete the field.

| Hook | Real source | What is dead until it lands |
| --- | --- | --- |
| `lurkingGuildIds` | `StoreLurking.getLurkingGuildIds()` — **the store exists, `StoreStream` does not publish it** | Lurked guilds are indistinguishable from joined ones and are draggable |
| `selectedVoiceChannelId` | `StoreVoiceChannelSelected.observeSelectedVoiceChannelId()` — **exists, not published** | No guild or folder ever shows the voice-connected ring or the stage glyph |
| `guildJoinRequests`, `pendingGuilds`, `fetchPendingGuilds` | `StoreGuildJoinRequest` (does not exist) | No pending-guilds folder, no join-request badge on a guild icon |
| `guildIdsWithActiveStageEvents` | `GuildIdsWithVisibleStageInstanceModel` | Stage indicator never lights |
| `guildIdsWithActiveScheduledEvents` | `GuildIdsWithActiveEventsModel` | Scheduled-event dot never lights |
| `applicationStreams` | `StreamContextService.getForAllStreamingUsers()` | Go Live indicator never lights |
| `discordHubClicked`, `markDiscordHubClicked` | `StoreDirectories` | Hub sparkle |
| `isHubDiscoveryEnabled`, `isHubDiscoverySparkleEnabled`, `isHubEnabled` | `GrowthTeamFeatures` | The student-hub sidebar row is never emitted |
| `showMemberVerificationGate` | `MemberVerificationUtils.maybeShowVerificationGate(...)` — the object exists but only has `hasVerificationGate` | Tapping a guild that is still under membership review does nothing (deliberately — it must never open the guild) |

**Shape divergence to fold back:** the hook is `applicationStreams: () -> Observable<List<GuildStream>>`
(`guildId`, `channelId`) instead of the reference's `Map<Long, StreamContext>`, because the target's
`utilities/streams/StreamContext` is a stub carrying neither the guild nor the channel. Restore the
reference shape once `StreamContext` does.

`widgets/guilds/list/GuildsListNavigation` and `widgets/guilds/contextmenu/ContextMenuNavigation`
are the same pattern for destinations: `WidgetGuildTemplates` (the "+" row),
`WidgetNavigationHelp` (the "?" row), `WidgetChannelsListItemChannelActions` (DM long-press),
`WidgetLeaveGuildDialog`, `WidgetGuildProfileSheet`, `WidgetGuildFolderSettings`. The rows are shown
and their clicks fire; they simply do not navigate yet.

### Sidebar mismatches worth knowing

- **Mention counts come from `StoreReadStates.getMentionCountsByChannel()`**, not the reference's
  `StoreMentions.observeMentionCounts()` — `StoreMentions` exists but `StoreStream` does not publish
  it. Same 500 ms leading-edge throttle either way.
- **The reference never shows the pending-guilds folder.** `handleStoreState` gates it on
  `emptyList().contains(entry.key)` — an R8-folded constant that is always false, so
  `createPendingGuildsFolder`, `isPendingGuild`, the drag guards and the verification gate are all
  dead code in 126.21. The reconstruction gates it on `pendingGuilds` instead, which is the only
  reading that gives the surrounding code any effect. Documented in
  `GuildListItemBuilder.hasReviewablePendingGuilds`.
- **`BottomNavViewObserver` does not exist**, and `TabsHostBottomNavigationView` offers
  `addHeightChangedListener` with **no matching remove**, so the sidebar samples the bar's height on
  each resume rather than leaking a listener. Add `removeHeightChangedListener` (or the reference's
  observer) and the trailing spacer becomes live.
- **`AddGuildHint` (the first-server tip) is not reconstructed.** It needs `StoreNux`,
  `AnalyticsTracker` and `WidgetHome.setOnGuildListAddHintCreate`, none of which exist.
- **`RoleIconView.showRoleIconToast` names the role but does not render its icon** — the reference
  builds an `EmojiNode` into the toast and `textprocessing/node/EmojiNode` is not reconstructed.
- **`Guild.canUseRoleIcons` is inlined in `RoleIconView`**; it belongs in
  `utilities/guilds/RoleIconUtils`, which does not exist.
- `StoreStream.updateGuildFolders(...)` now has no caller: the sidebar persists through
  `StoreGuildsSorted.setPositions` + `RestAPI.updateUserSettings`, exactly as the reference does.

### Recovered from bytecode this wave — do not re-derive

- **`WidgetGuildsListViewModel.handleStoreState` row order** (JADX: "instructions count: 1289";
  Vineflower fails on the whole class). From `javap -c`: Friends → mentioned DMs → pending-guilds
  folder → lurked guilds → **divider** → guilds/folders → outage row → "+" → hub → (divider + help,
  new accounts only) → bottom-nav spacer. The audit's guessed order was wrong.
- **`WidgetGuildListAdapter.onMove`** (JADX: "instructions count: 362") is fully recovered in the
  Vineflower Kotlin decompile, including the hit zones
  (`|selectedCenterY - targetCenterY| < sourceHeight * 0.2f` ⇒ CENTER, else TOP when the dragged row
  is above the target) and the whole source×target validity matrix.
- **`TargetPosition` ordinals are TOP=0, CENTER=1, BOTTOM=2** — from the `when (ordinal)` mapping to
  `MoveAbove`/`TargetOperation`/`MoveBelow`. Vineflower prints the enum alphabetically.
- **`GuildsDragAndDropCallback.getMovementFlags`** is `makeMovementFlags(UP or DOWN, 0)` gated on
  `DraggableViewHolder.canDrag()`, as the audit inferred.
- **`ChannelUtils.h` is `getSortByNameAndType`** and **`ChannelUtils.D` is `isStageVoiceChannel`**
  (`javap -c`), so the sidebar's mentioned-DM list really is sorted by name/type, not by recency.

## Chat input + bot UI kit — seams opened by the composer wave

`WidgetChatInput` now renders `ChatInputViewModel.ViewState.Loaded` and shows the reference's
guards. Everything below is inert-by-default and documented at the point of use.

| Seam | Location | Stands in for | Consequence while unset |
| --- | --- | --- | --- |
| `ChatInputStoreBindings.isLurking` | `widgets/chat/input/` | `StoreLurking.isLurkingObs` — the store exists, `StoreStream` never constructs it | a lurked guild looks writable until the API rejects the send |
| `ChatInputStoreBindings.threadDraftState` | same | `StoreThreadDraft.observeDraftState` | thread-creation composer sees an empty draft |
| `ChatInputStoreBindings.chatEvents` / `slowModeCooldown` / `joinedThreads` | same | `StoreChat` / `StoreSlowMode` / `StoreThreadsActiveJoined` | no member-list mention insert, no slow-mode countdown, no `#thread` autocomplete |
| `ChatInputGuardActions.{joinGuildAsMember, showFollowSheet, launchAccountVerification, showMemberVerificationGate}` | `widgets/chat/input/ChatInputGuardActions.kt` | `StoreLurking.postJoinGuildAsMember`, `WidgetChannelFollowSheet`, `WidgetUserEmailVerify`/`WidgetUserPhoneManage`, `MemberVerificationUtils.maybeShowVerificationGate` | the guard's **explanation** is shown; its button is **hidden** rather than dead |
| `ChatInputAutocompletables.emojiSetSource` | `widgets/chat/input/autocomplete/` | `StoreEmoji` | falls back to `emoji/LocalEmojiSet` (bundled catalog + `StoreGuilds`), so `:name:` autocomplete works but has no favourites/recents |
| `SelectComponentBottomSheet` fragment result | `widgets/botuikit/views/select/` | the reference sends the interaction inline via `StoreApplicationInteractions` + `StoreLocalActionComponentState` | the chat row performs the REST call instead; optimistic local selection is not recorded |

**Fields deliberately absent from `ChatInputViewModel.ViewState.Loaded`** (present in the reference,
no type in this project): `joinRequestStatus` (`api/guildjoinrequest/ApplicationStatus` +
`StoreGuildJoinRequest`) — so only the "not applied yet" branch of the member-verification card is
reachable, which is the reference's own default branch. `showCreateThreadOption` is hardcoded
`false` pending `utilities/threads/ThreadUtils.canCreateThread`. `maxFileSizeMB` inlines the tier
table that belongs in `utilities/premium/PremiumUtils`.

**New shared utilities created outside `widgets/chat/input/**`** — they sit at their reference paths
and nothing else owned them: `utilities/guilds/GuildVerificationLevelUtils`,
`utilities/guilds/MemberVerificationUtils` (only `hasVerificationGate`),
`utilities/duration/DurationUtils` (only `humanizeCountdownDuration`).

**Proved this wave:** `replaceAutocompleteDataWithServerValues` applies its ranges
**descending by start offset** — `javap -c` shows the synthetic comparator class
`AutocompleteExtensionsKt$replaceAutocompleteDataWithServerValues$$inlined$sortedByDescending$1`.
Ascending would corrupt any message containing two mentions whose replacement differs in length
from the typed text. `IntRange.last` in that map is the **exclusive** end.

### Still missing in `widgets/chat/input/**`
`gifpicker` (17 reference classes — REST endpoints and DTOs exist under `models/gifpicker/**`, but
`StoreGifPicker` does not), `expression` (8 classes — needs `StoreExpressionPickerNavigation`,
`StoreGuildStickers`), `attachments/AttachmentBottomSheet`, `WidgetChatInputAttachments`,
`WidgetChatInputEditText`, `WidgetChatInputTruncatedHint`, `WidgetChatInputMentionsTags`,
`AppFlexInputViewModel`, `applicationcommands/**` and `autocomplete/commands/**` (need
`StoreApplicationCommands`). The composer still hosts an emoji-only tray in place of the
three-tab expression tray.

## Settings / servers wave — store dependencies the integration agent must wire

Every item below is a seam with a working, honest default. Each is one line to close.

| Seam | Where | Consequence today |
| --- | --- | --- |
| `StoreStream.getGuildProfiles()` does not exist (`StoreGuildProfiles` is dead code) | `WidgetServerNotificationsViewModel.observeStoreState(guildProfileSource = …)` | `approximateMemberCount` is unknown, so `isAboveNotifyAllSize` / `isAboveHighlightsSize` are always `false`: the "All messages" radio never shows the `large_guild_notify_all_messages_description` subtext, and the highlights warning card never appears |
| `StoreExperiments` / `GrowthTeamFeatures.isHighlightsSettingsEnabled()` do not exist | `WidgetServerNotificationsViewModel.observeStoreState(highlightsEnabledSource = …)` | The highlights section stays hidden — which is what an account outside the `2022-03_highlights_settings` bucket sees, so it is not a visible regression. `NotificationHighlightsSettingsView` now has a real caller and goes live the moment the flag does |
| `GuildRoleSubscriptionsFeatureFlag` / `StoreGuildRoleSubscriptions` do not exist | `WidgetServerSettingsViewModel.observeStoreState(canManageGuildRoleSubscriptionsSource, hasGuildRoleSubscriptionsSource)` | The monetization section of server settings stays hidden |
| `StoreExperiments` (`2020-12_guild_delete_feedback`) and `widgets/guild_delete_feedback` do not exist | `ServerDeleteViewModel` | Deleting a server dismisses the dialog without offering the feedback sheet |

### Screens that exist but nothing navigates to

Both have a working `launch(context, guildId)` built on `SettingsNavigation`; they just need a caller.

- `WidgetServerNotifications.launch(context, guildId)` — the reference reaches it from the guild
  profile sheet, which this project does not have (`widget_guild_profile_actions.xml` has no host).
- `WidgetServerSettings.launch(context, guildId)` — same host in the reference. It is also the
  destination of the `ChatListRowNavigation.launchGuildSettings` seam already recorded above, which
  can now be pointed at it.

### `WidgetServerSettings.IMPLEMENTED_OPTIONS` is the server-settings equivalent of `WidgetSettings.implementedRouteIds`

The permission gating on every row matches the reference, but a row is only *shown* when its
destination exists. Today that set contains only `server_settings_bans_option`. Add an id — and a
`bindDestination` call — as each of Overview, Moderation, Audit Log, Channels, Integrations,
Security, Vanity URL, Emoji, Community Overview, Enable Community, Members, Roles and Instant
Invites lands, then delete the mechanism.

### Local stand-ins introduced by this wave — move them when the real home exists

| Stand-in | Current location | Belongs in |
| --- | --- | --- |
| `SwipeableItemTouchHelper` + `SwipeRevealConfiguration` (swipe-to-reveal background/icon) | bottom of `widgets/servers/WidgetServerNotifications.kt` | `utilities/views/SwipeableItemTouchHelper` — kept local so two concurrent slices could not both create it |
| `GuildChannelsSort` (`getSortedCategories` / `getSortedVisibleChannels`) | `widgets/servers/GuildChannelsSort.kt` | `utilities/channel/GuildChannelsInfo`, which combines eight stores and is not reconstructed |
| `SettingsNavigation.launch` | `widgets/settings/SettingsNavigation.kt` | Delete once `AppActivity` honours `AppScreenRouter.EXTRA_SCREEN`; it is the same `app_root` transaction `WidgetMedia` already makes |
| `SettingsAppearanceViewModel.{USE_SYSTEM_FONT_SCALE, MIN_FONT_SCALING, MAX_FONT_SCALING, systemFontScalePercent}` | `widgets/settings/SettingsAppearanceViewModel.kt` | `utilities/font/FontUtils`, absent. `AppActivity.USE_SYSTEM_FONT_SCALE` duplicates the first constant |
| `SettingsStrings` hook rendering (`$[label](hookName)`) | `widgets/settings/SettingsStrings.kt` | `com.discord.i18n` — this is the third markup construct the reference's `RenderContext`/`Hook` handles. `NotificationMuteSettingsView`'s literal `String.replace` copy is gone: it now renders through `SettingsStrings` and formats the end time with `TimeUtils.renderUtcDateTime` (MEDIUM/SHORT, matching the reference), styled with `colorControlBrandForeground` rather than the guessed `colorTextLink` |
| `ViewCompat.setAccessibilityHeading` in place of `AccessibilityUtils.setViewIsHeading` | appearance/language screens | `utilities/accessibility/AccessibilityUtils`, absent |

### Corrections to the settings/servers audit report

Checked at bytecode level; do not "re-fix" these.

- **`WidgetServerNotifications` gap #10 was wrong.** The audit said the muted push switch should
  pass "a real message" to `setDisabledTooltip`. `javap` of `CheckedSetting.d(CheckedSetting,
  CharSequence, int)` shows the synthetic default overload discards its argument and calls
  `c(null)`, so the reference genuinely passes `null`: an empty toast and 0.3α, exactly what the
  target already did.
- **Gap #13 was half wrong.** `configureRadio` does not *disable* the "All messages" radio on a
  large server. `CheckedSetting.h(CharSequence, Boolean)` is `setSubtext`, not a tooltip — the
  reference sets `large_guild_notify_all_messages_description` as the row's subtext and leaves it
  selectable. Reproduced as a subtext.
- **`SwipeableItemTouchHelper`'s default `swipeDirs` is `START or END` (12), not 0.** The synthetic
  default constructor pushes `bipush 12` for parameter 2, which is what makes the reference's
  `super(…, mask = 3, …)` call produce a swipeable row at all.

## Channels sidebar rebuild — store wiring still required

The channel list is now the reference's model + 15-viewholder adapter
(`widgets/channels/list/`). Two seam files carry everything `StoreStream` does not
publish; each entry is a one-line change once the accessor exists.

### `list/ChannelListSources.kt` — store inputs

All of these stores **already exist** under `com/discord/stores`; only the
`StoreStream.Companion` accessor is missing (several are private fields on
`StoreStream` today).

| Provider | Needs | Effect while inert |
| --- | --- | --- |
| `voiceUsers`, `selectedVoiceChannelId`, `streamingUserIds` | `VoiceStoreAccess.install(...)` — **already the documented seam**, nothing new needed | voice rows list no participants; joined channel not highlighted |
| `joinedThreads`, `activeJoinedThreads`, `channelsWithActiveThreads` | `StoreStream.getThreadsJoined()`, `getThreadsActiveJoined()`, `getThreadsActive()` | **no thread rows at all**, and text channels never show the "has threads" icon |
| `guildScheduledEvents`, `canCreateAnyEvent` | `StoreStream.getGuildScheduledEvents()` (store exists, not constructed) | no events row, no "happening now" banner |
| `stageChannels` | `StoreStream.getStageChannels()` (store exists, not constructed) | stage rows show no speakers and no audience count |
| `guildJoinRequest` | `StoreStream.getGuildJoinRequests()` (store exists, not constructed) | membership-screening card never appears |
| `canSeeGuildRoleSubscriptions` | `GuildRoleSubscriptionsFeatureFlag` | role-subscription rows hidden |
| `showPremiumGuildHint` | `StoreNux` | boost hint never shows, and dismissing it does not persist |
| `directoryUnreadCounts`, `hubEnabled`/`hubStudyGroupEnabled`/`hubEventsEnabled` | `StoreDirectories`, `GrowthTeamFeatures` | hub guilds render as ordinary guilds |

`WidgetChannelListModel.get()` reads everything else live from `StoreStream`
(`channels`, `channelsSelected`, `guilds`, `permissions`, `userGuildSettings`,
`readStates`, `presences`, `collapsedChannelCategories`, `stageInstances`,
`users`, `guildSelected`).

Two deliberate substitutions, both commented in place:
- DM ordering uses `Channel.lastMessageId` because `StoreMessagesMostRecent` is not
  published; identical ordering except while a DM is being read.
- Mention counts come from `StoreReadStates.getMentionCountsByChannel()` rather than
  `StoreMentions.observeMentionCounts()`.

### `list/ChannelListNavigation.kt` — missing destinations

Live now: `callChannel` (via `VoiceStoreAccess`), `addServerToHub`,
`showChannelNotificationSettings`, `copyToClipboard`.

Inert until the screen is rebuilt: `showGuildProfile` (`WidgetGuildProfileSheet`),
`createChannel` (`WidgetCreateChannel`), `shareGuildInvite`
(`WidgetGuildInviteShare`), `copyGuildInvite` (`StoreInstantInvites`),
`showVoiceChannelSettings` (`WidgetVoiceChannelSettings`),
`showTextChannelSettings` (`WidgetTextChannelSettings`), `showGroupDmSettings`
(`WidgetChannelGroupDMSettings`), `showThreadBrowser` (`WidgetThreadBrowser`),
`showThreadSettings` (`WidgetThreadSettings`), `showGuildRoleSubscriptions`,
`showGuildScheduledEventList`, `showGuildScheduledEventDetails`,
`showMemberVerification` (`MemberVerificationUtils`), `ackPremiumGuildHint`
(`StoreNux`), `customStatusBackground` (`WidgetGlobalStatusIndicatorState`),
`bottomNavHeight` (`BottomNavViewObserver`).

### Now unblocked elsewhere
`widgets/guilds/list/WidgetGuildsList.kt:246` documents a seam for
`WidgetChannelsListItemChannelActions.show(fragmentManager, channelId)`. That class
exists now — wire it.

### Misplaced helpers introduced by this wave
- `Context.themedDrawableRes` (in `WidgetChannelsListItemChannelActions.kt`) →
  `utilities/drawable/DrawableCompat.getThemedDrawableRes`.
- `Context.themedFont` (in `WidgetChannelsListAdapter.kt`) →
  `utilities/font/FontUtils.getThemedFont`.
- `VoiceState.isMutedByAnySource()` (in `WidgetChannelsListAdapter.kt`) →
  `api/voice/state/VoiceStateUtils`. Verified at bytecode level:
  `selfMute || mute || suppress`.
- `WidgetChannelsListItemThreadActions.autoArchiveDurationName` duplicates the table in
  `WidgetChatListAdapterItemThreadDraftForm`; both belong in `ThreadUtils`.
- `WidgetThreadArchiveActions.computeThreadAutoArchiveTimeMs` → `ThreadUtils`.
- Plural formatting goes through `com.discord.i18n.FormatUtils` with an explicit
  `"#"` argument; the reference's `StringResourceUtilsKt.getI18nPluralString` does
  not exist yet.

### Known behavioural deltas vs the reference
- The MFA notice renders `two_fa_guild_mfa_warning` without its tappable
  `[Resolve](onClick)` hook — `com.discord.i18n` has no hook binding yet.
- The channel sheet's "Browse threads" row is not gated on
  `ThreadUtils.isThreadsEnabled(guildId)` (no `ThreadUtils`); it is gated on
  channel type and NSFW only.
- Voice participant names use the 2-arg `GuildMember.getNickOrUsername(member, user)`;
  the reference's 4-arg overload (user, member, channel, guild) does not exist.

## Wave: `widgets/user` + `widgets/status` state layer (user-sheet / rich-presence agent)

### `StoreStream` accessors this wave needs — highest-value wiring in the report

`StoreStream` constructs all of these but publishes none of them. Every one is a one-line
`@JvmStatic fun getX() = requireInstance().x`, and each closes a *named* behavioural gap:

| Missing accessor | What stays dead without it |
| --- | --- |
| `getVoiceStates()` | user sheet: server mute / deafen / move / disconnect never appear (`adminViewState` sees no voice state) |
| `getVoiceChannelSelected()` | user sheet: `showVoiceSettings` is always false, so per-user volume and mute are unreachable; `PrivateCallLauncher` cannot join a channel |
| `getMediaSettings()` | user sheet: `muted` / `selfMuted` / `selfDeafened` / `outputVolume` are defaults; `toggleMute`/`toggleDeafen`/`setUserOutputVolume` are no-ops |
| `getCalls()` | `PrivateCallLauncher` cannot ring |
| `getMediaEngine()` | `PrivateCallLauncher.launchVideoCall` cannot open the camera |
| `getUserConnections()` | platform rich presence never offers "Connect Xbox/PlayStation/…" |
| `getExperiments()` | PlayStation account-integration gate reads false |
| `getApplication()` (`StoreApplication` does not exist at all) | `ModelRichPresence.primaryApplication` is always null → game cards fall back to presence assets only |

Every consumer already takes the store as a constructor parameter with an inert default and a KDoc
line naming the gap, so wiring is *only* the accessor plus passing it at the construction site
(`WidgetUserSheetViewModelStoreState.observeStoreState`,
`ViewHolderUserRichPresence.setRichPresence`, `PrivateCallLauncher`).

### New shared code placed outside `widgets/user` (new files, no edits to others' files)

- `utilities/platform/Platform.kt` — the 15-value connected-service enum. **`NONE` is declared last**,
  not alphabetically: Vineflower prints the constants alphabetically but `getProfileUrl`'s ordinal
  switch proves the `$VALUES` order. Same trap as `CallStatus`.
- `utilities/presence/StageCallRichPresencePartyData.kt` — `StageCallRichPresencePartyData` plus
  `Activity.stageChannelRichPresencePartyData`. The unpack is `stage:<guildId>:<channelId>:<flagsHex>:<stageInstanceId>`
  with party size `[speakers, participants]`; flags `1`=speaker, `2`=partnered, `4`=verified. JADX
  cannot decompile `unpackStageChannelParty` at all — reconstructed from Vineflower plus the
  readable `packStageChannelPartyId`.
- `utilities/integrations/SpotifyHelper.kt` — real implementation; **removes the stopgap** in
  `WidgetChatListAdapterItemSpotifyListenTogether.launchSpotifyTrack`, which should now delegate.
  `registerSpotifyBroadcastReceivers` is absent: `utilities/receiver/spotify/*` does not exist.
- `utilities/user/UserProfileUtils.kt` — `getPremiumSince`, `getBoostingSince`,
  `guildBoostMonthsSubscribed`, `isGuildBooster`, `mutualGuildsById`.

### `utilities/streams/StreamContext` was widened (existing file edited)

It was a two-member stub. The reference is a `data class` with eight fields; the interface now
declares all eight (`guild`, `joinability`, `user`, `userNickname`, `isCurrentUserParticipating`,
`isSelfStream` added). **Nothing in the project constructs one**, so this is source-compatible
today — but it stays an interface only until `StreamContextService` is reconstructed, at which
point it should become the reference's data class and `StoreVoiceParticipants.applicationStream`
should switch from `StreamContext.ApplicationStream` to `ModelApplicationStream`.

### `StoreStream` user-sheet wiring removed

`WidgetUserSheet` no longer has `stateSource` / `onNoteSave` / `onRelationshipAction` /
`State` / `NoteState` / `RelationshipAction`; it owns a real `WidgetUserSheetViewModel`.
`StoreStream.observeUserSheet` and the four hook assignments were deleted. `StoreStream.blockUser`
is now unreferenced — the sheet's overflow sheet (`onMore`) should call it.

### Reconstructed from weak evidence — verify on device

| Item | Why |
| --- | --- |
| `WidgetUserSheetViewModel.handleStoreState` | 782 instructions, "Method dump skipped" in JADX; whole class fails in Vineflower. Reconstructed from the `ViewState.Loaded` field semantics plus the readable `createAdminViewState`/`createConnectionsViewState`. |
| `createAdminViewState`'s kick rule | JADX drops the outer negation on that branch, so it literally reads "show kick when you *cannot* kick". Implemented as the reading consistent with the other six flags: guild → `canKick`; group DM → `isGroupOwner && !isMe`. |
| `WidgetUserSheet.configureProfileActionButtons` | 437 instructions, undecompilable in both tools. Derived from the layout's buttons and the relationship types. |
| `ViewState.Loaded.guildSectionHeaderText` | No "Roles in {guild}" format string survives in the recovered resources; the sheet uses the guild name and falls back to `R.string.roles`. |

### Still missing in these packages after this wave

`WidgetPruneUsers(+ViewModel)`, `WidgetUserPasswordVerify`, all six of `user/account`,
`user/email`, `user/phone`, `UserMutualGuildsManager`, `UserNameFormatterKt`,
`DraweeSpanStringBuilderExtensionsKt`, `RightToLeftGridLayoutManager`,
`WidgetUserProfileEmptyListItem`, `WidgetUserMentionsViewModel`,
`WidgetUserSetCustomStatusViewModel`, `WidgetUserStatusSheetViewModel`,
`WidgetForumPostStatusViewModel`, and everything in `widgets/friends` flagged by the audit.

`user/presence` is complete except that the stage card's join button is inert: it takes an
`onJoinStage` lambda standing in for `StageChannelJoinHelper.lurkAndJoinStage`
(`widgets/stage` is unreconstructed). The sheet does not pass one yet.

`WidgetUserSheetViewModel` omits two reference fields because their types do not exist:
`StoreState.userStageRoles` / `myStageRoles` (`widgets.stage.StageRoles`) and
`ViewState.Loaded.stageViewState` (`UserProfileStageActionsView.ViewState`). Both are commented
in place.

## Shared-utility packages now EXIST — collapse the stand-ins onto them

Built this wave under `com/discord/utilities/`. Everything below is a mechanical
substitution: delete the local copy, import the real one. None of the listed files
were touched (14 agents writing concurrently); this is the convergence list.

### New packages

| Package | Contents |
| --- | --- |
| `utilities/color/` | `ColorCompat` (`getColor`, `getThemedColor`, `setStatusBarColor`, `setStatusBarColorResourceId`, `setStatusBarTranslucent`, `isColorDark`, `createDefaultColorStateList`, `getColorHexFromColorInt`, `removeAlphaComponent`) and `ColorCompatExtensions.kt` (`Fragment.getColor`, `Fragment.getThemedColor`, `TextView.setDrawableColor`, `Drawable.setTint`, `ImageView.tintWithColor`/`tintWithColorResource`) |
| `utilities/dimen/` | `DimenUtils` — top-level `dpToPixels(Int)`, `dpToPixels(Float)`, `pixelsToDp(Int)` |
| `utilities/drawable/` | `DrawableCompat` — `Context/View.getThemedDrawableRes`, `Context.getDrawable(res, tint, isGlobalTint)`, `TextView.setCompoundDrawablesCompat` (Int and Drawable overloads) |
| `utilities/embed/` | `EmbedResourceUtils`, `FileType` |
| `utilities/message/` | `MessageUtils` |
| `utilities/premium/` | `PremiumUtils` (+ nested `BoostFeatureBadgeData`), `PremiumTierExtensions.kt` (`grantsAccessToPremiumStickers`, `grantsAccessToCustomTagAndAnimatedAvatarFeatures`) |
| `utilities/gifting/` | `GiftStyle`, `ModelGift.getCustomStyle`/`hasCustomStyle`, `GiftingUtils` |
| `utilities/spans/` | `QuoteSpan`, `BulletSpan`, `BlockBackgroundSpan`, `VerticalPaddingSpan`, `SpoilerSpan`, `TypefaceSpanCompat`, `FontColorSpan`, `SimpleRoundedBackgroundSpan` (joining the existing `ClickableSpan`) |
| `utilities/systemlog/` | `SystemLogCapture`, `SystemLogUtils` (+ `Tombstone`) |
| `utilities/extensions/` | `PendingIntentExtensions.kt` — `immutablePendingIntentFlag`, `mutablePendingIntentFlag` |
| `utilities/SnowflakeUtils.kt` | `DISCORD_EPOCH`, `SNOWFLAKE_TIMESTAMP_SHIFT`, `toTimestamp`, `fromTimestamp`, `atPreviousMillisecond`, `Long.timestampPart`, `String.toSnowflake` |
| `utilities/guilds/GuildUtils.kt` | `computeShortName`, `List<GuildMember>.asMap`, `getMaxFileSizeMB(guild)` |
| `com/discord/primitives/Primitives.kt` | all 17 typealiases |

**Package-name note.** The wave brief said `utilities/colors/`; the reference has
`utilities/color/ColorCompat` — its `utilities/colors/` is `ColorPickerUtils` /
`RepresentativeColors`, a different thing. `color/` was used. Files already written
by the concurrent voice / announcements / debugging waves import
`com.discord.utilities.color.ColorCompat` and `com.discord.utilities.dimen.dpToPixels`,
which confirms it — those imports were dangling until now. `FontColorSpan` and
`SimpleRoundedBackgroundSpan` sit in `utilities/textprocessing` in the reference;
they were placed in `utilities/spans/` per the brief and to avoid colliding with the
textprocessing owner.

### Migration list — stand-in to replacement

| Stand-in | Location | Replace with |
| --- | --- | --- |
| `internal object EmbedMedia` (~190 lines) | `widgets/chat/list/adapter/WidgetChatListAdapterItemEmbed.kt:715-904` | `EmbedResourceUtils` + `FileType`. `previewImage(e)` becomes `e.getPreviewImage()`, `isInlineEmbed(e)` becomes `e.isInlineEmbed()`, `externalOpenUrl(e)` becomes `e.getExternalOpenUrl()`; `isAnimated`, `previewUrls` (now `getPreviewUrls`), `computeMaximumImageWidthPx`, `calculateScaledSize`, `getFileDrawable` are same-named. The extensions are object members, so callers use `with(EmbedResourceUtils) { … }`. `EmbedMedia.dpToPixels` at `:865` rounds; `DimenUtils.dpToPixels` truncates, as the reference does — `MAX_EMBED_IMAGE_HEIGHT_PX` at `:667` shifts by at most 1px |
| `private const val DISCORD_EPOCH`, 8 copies | `utilities/icon/IconUtils.kt:48`, `utilities/user/UserUtils.kt:43`, `utilities/time/TimeUtils.kt:35`, `utilities/messagesend/NonceGenerator.kt:7`, `models/domain/NonceGenerator.kt:30`, `stores/StoreAuditLog.kt:385`, `stores/StoreMessageAck.kt:412`, `stores/StoreNotifications.kt:247`, `stores/StoreReadStates.kt:534`, `widgets/channels/threads/browser/ThreadBrowserThreadView.kt:251` | `SnowflakeUtils.DISCORD_EPOCH`. The hand-rolled arithmetic at `StoreAuditLog.kt:392`, `StoreMessageAck.kt:424,427`, `StoreReadStates.kt:390,528,529`, `StoreNotifications.kt:240`, `ThreadBrowserThreadView.kt:257`, `UserUtils.kt:215`, `IconUtils.kt:320` is exactly `toTimestamp` / `fromTimestamp` / `atPreviousMillisecond` |
| 29 private `themedColor(...)` helpers | `app/AppActivity.kt:515`, `dialogs/ImageUploadDialog.kt:104`, `utilities/textprocessing/TextProcessingResources.kt:23`, `views/ThemedColor.kt:18` plus its users `views/segmentedcontrol/CardSegment.kt:62`, `views/user/SettingsMemberView.kt:117`, `views/user/UserAvatarPresenceView.kt:165`; `views/calls/CallEventsButtonView.kt:73`, `views/guildboost/GuildBoostSubscriptionUpsellView.kt:110`, `views/permissions/ChannelPermissionOwnerView.kt:79`, `views/SearchInputView.kt:95`, `views/UsernameView.kt:130`, `views/VoiceUserLimitView.kt:68`, `widgets/botuikit/views/select/SelectComponentView.kt:99`, `widgets/channels/list/WidgetChannelsList.kt:449`, `widgets/channels/memberlist/ChannelMembersListAdapter.kt:148`, `widgets/channels/threads/browser/ThreadBrowserThreadView.kt:220`, `widgets/chat/input/autocomplete/adapter/AutocompleteItemViewHolder.kt:193`, `widgets/chat/input/autocomplete/adapter/CommandHeaderViewHolder.kt:52`, `widgets/chat/input/WidgetChatInput.kt:647`, `widgets/chat/list/ViewReplySpline.kt:101`, `widgets/forums/ForumBrowserPostView.kt:226`, `widgets/guildscheduledevent/buttonconfiguration/DirectoryButtonConfiguration.kt:50`, `widgets/guildscheduledevent/buttonconfiguration/GuildButtonConfiguration.kt:79`, `widgets/guildscheduledevent/GuildScheduledEventDateView.kt:74`, `widgets/guild_role_subscriptions/tier/create/GuildRoleSubscriptionMemberPreview.kt:49`, `widgets/servers/community/CommunityGetStartedInformationView.kt:76`, `widgets/stickers/WidgetGuildStickerSheet.kt:216`, `widgets/tabs/TabsHostBottomNavigationView.kt:176`, `widgets/user/profile/UserProfileHeaderView.kt:160` | `ColorCompat.getThemedColor(context, attr)` — identical `TypedValue` + `resolveAttribute(…, true)` + `.data`. **`views/ThemedColor.kt` should be deleted outright** |
| 25 private `dpToPixels` / `dp(…)` / `Int.dp` helpers | `views/calls/StageCallSpeakerView.kt:65`, `views/calls/VideoCallParticipantView.kt:346`, `views/PileView.kt:128`, `views/stages/StageCardSpeakersView.kt:45`, `widgets/botuikit/views/ButtonComponentView.kt:85`, `widgets/botuikit/views/select/SelectComponentBottomSheetItemViewHolder.kt:76`, `widgets/changelog/WidgetChangeLogSpecial.kt:249`, `widgets/channels/list/WidgetChannelsList.kt:464`, `widgets/channels/UserAkaView.kt:68`, `widgets/chat/input/autocomplete/adapter/AutocompleteItemViewHolder.kt:191`, `widgets/chat/input/sticker/WidgetStickerPicker.kt:415`, `widgets/chat/list/actions/WidgetChatListActions.kt:140`, `widgets/chat/list/adapter/WidgetChatListAdapterItemEmbed.kt:865`, `widgets/chat/list/adapter/WidgetChatListAdapterItemReactions.kt:114`, `widgets/chat/list/adapter/WidgetChatListAdapterItemStart.kt:197`, `widgets/chat/list/ViewReplySpline.kt:99`, `widgets/forums/ForumBrowserPostView.kt:236`, `widgets/forums/ForumPostTagView.kt:39`, `widgets/guilds/list/WidgetGuildsList.kt:220`, `widgets/nux/WidgetNavigationHelp.kt:64`, `widgets/voice/fullscreen/grid/PrivateCallBlurredGridView.kt:69`, `widgets/voice/fullscreen/grid/PrivateCallGridView.kt:85`, `utilities/textprocessing/TextProcessingResources.kt:30,39`, `utilities/stickers/StickerUtils.kt:24` | `com.discord.utilities.dimen.dpToPixels`. Two behaviour deltas to expect: copies using `roundToInt` shift by up to 1px (the reference truncates), and copies keyed on a *view's* `resources` stop following a per-context density override (the reference resolves all of them off `Resources.getSystem()`) |
| `internal object ThemedDrawable` | `widgets/chat/list/adapter/WidgetChatListAdapterItemInvite.kt:469`, used at `:325` and `WidgetChatListAdapterItemThreadEmbed.kt:226` | `Context.getThemedDrawableRes(attr)` / `View.getThemedDrawableRes(attr)` |
| local themed-drawable resolvers | `widgets/channels/list/WidgetChannelsListAdapter.kt:1356`, `widgets/channels/list/WidgetChannelsListItemChannelActions.kt:261`, `widgets/user/presence/ViewHolderPlatformRichPresence.kt:155` | same |
| inline null-safe id sign compare | `stores/StoreMessagesMostRecent.kt:175-191` | `MessageUtils.compareMessages` / `MessageUtils.SORT_BY_IDS_COMPARATOR` |
| `ChatRowText.systemMessageUserJoin`, `.systemMessageRoleSubscriptionPurchase`, `WELCOME_MESSAGES` | `widgets/chat/list/adapter/WidgetChatListAdapterItemMessage.kt:978-1032, 1100-1115` | `MessageUtils.getSystemMessageUserJoin(context, messageId)` and `MessageUtils.getSystemMessageRoleSubscriptionPurchase(context, guildName, authorName, data, render)`. The `render` parameter exists precisely for `ChatRowText` — pass `{ ctx, res, args -> format(ctx, res, args, hooks) }` and the clickable tier name is preserved |
| `internal fun computeShortName` | `widgets/directories/DirectoryUtils.kt:157`, used at `DirectoryEntryViewHolder.kt:27` | `com.discord.utilities.guilds.computeShortName` |
| `internal fun computeGuildInitials` | `widgets/hubs/HubGuildText.kt:14` | same |
| `PremiumUtils.getMaxFileSizeMB` stand-in | `widgets/chat/input/ChatInputViewModel.kt:269-271` | `PremiumUtils.getMaxFileSizeMB(user)` / `PremiumUtils.getGuildMaxFileSizeMB(tier)` |
| boost badge hidden, upsell TODO | `widgets/chat/list/adapter/WidgetChatListAdapterItemThreadDraftForm.kt:156,166` | `PremiumUtils.getBoostFeatureBadgeDataForGuildFeature(guild, GuildFeature.PRIVATE_THREADS, context, onLockedClick = …)`, yielding `BoostFeatureBadgeData(onClickListener, iconColor, text, textColor)` |
| inlined premium-sticker tier test | `utilities/permissions/PermissionUtils.kt` | `PremiumTier.grantsAccessToPremiumStickers()` |
| gift art and expiry falling back to `IconUtils`/`TimeUtils` | `widgets/chat/list/adapter/WidgetChatListAdapterItemGift.kt` | `PremiumUtils.getNitroGiftIcon`, `PremiumUtils.getNitroGiftBackground`, `GiftingUtils.getTimeString`, `GiftingUtils.generateGiftUrl` |
| `ColorCompat.setStatusBarColor` recorded as absent | `widgets/media/WidgetMedia.kt:763` | `ColorCompat.setStatusBarColor(activity, color, forceDarkUi)` |
| plain tappable run instead of a chip | `widgets/chat/list/adapter/WidgetChatListAdapterItemAutoModSystemMessageEmbed.kt:89` | `SimpleRoundedBackgroundSpan` |
| `textprocessing/spans/{QuoteSpan, BulletSpan, BlockBackgroundSpan, VerticalPaddingSpan, SpoilerSpan}` | `utilities/textprocessing/spans/` | the identical classes now in `utilities/spans/`. `SpoilerSpan`'s third constructor argument is now defaulted (`isRevealed = false`); everything else is source-compatible. **Delete `textprocessing/spans/` after re-pointing `Rules.kt` and `node/*`** |
| `TextProcessingResources.kt` local `themedColor` and `dpToPixels` | `utilities/textprocessing/TextProcessingResources.kt:23,30,39,42` | `ColorCompat.getThemedColor`, `dimen.dpToPixels`. Its `toPunyCodeASCIIUrl` / `getMediaProxySize` / `getImageUri` copies belong to `string` / `IconUtils` / `ModelEmojiCustom`, as already recorded above |
| raw `PendingIntent` flag arithmetic | `utilities/notifications/NotificationRenderer.kt:191,197` | `immutablePendingIntentFlag()` / `mutablePendingIntentFlag()` |
| literal `"Spotify"` | `utilities/presence/ActivityUtils.kt:21` | `Platform.SPOTIFY.properName` — `utilities/platform/Platform` already exists, so this follow-up is stale |
| duplicate `typealias ForumTagId` | `api/channel/Channel.kt:10`, imported by `widgets/chat/list/adapter/WidgetChatListAdapterItemStart.kt:12` and `widgets/chat/list/entries/StartEntries.kt:4` | `com.discord.primitives.ForumTagId`; delete the `api.channel` copy |

Two follow-ups recorded earlier in this file are already closed:
`utilities.platform.Platform` and `STATIC_IMAGE_EXTENSION` / `toPunyCodeASCIIUrl`
(in `utilities/string/StringUtils.kt`) both exist.

### Deliberately not built, and why

- **`PremiumUtils.findPurchaseForSkuName`, `getPlanPriceText`, `getSkuPrice`** and
  **`GiftingUtils.buyGift`** need `SkuDetails`/`Purchase` flows plus
  `GooglePlayBillingManager`, `StoreGooglePlayPurchases` and `StoreGooglePlaySkuDetails`,
  none of which exist. Everything non-billing in both objects is present.
- **`SystemLogReport`** only forwards a recovered tombstone to `AnalyticsTracker` and
  `FirebaseCrashlytics`. `SystemLogCapture` and `SystemLogUtils.fetchLastTombstone`,
  which hold all the recovery logic, are present.
- **`GiftingUtils.getTimeString`** formats `duration_hours` / `duration_mins` directly
  because `utilities/resources/DurationUtils` and `DurationUnit` do not exist. Route it
  through them when they land — it is the same two string/plural pairs.
- **`EmbedResourceUtils.computeMaximumImageWidthPx`** uses
  `resources.displayMetrics.widthPixels`; the reference uses
  `DisplayUtils.getScreenSize(context).width()` from the absent `utilities/display`.
  Equivalent for a full-screen activity, different in multi-window.
- `GuildUtilsKt.createApiGuild`, `getGuildRole`, `getMaxVoiceBitrateKbps` and
  `isFullyGatedGuildRoleSubscriptionGuild` need `StoreStream`, the 46-argument
  `api.guild.Guild` constructor, and `utilities/voice/Bitrate`.
- `utilities/media/{AppSound, AppSoundManager}`, `utilities/lazy/{requester, subscriptions}`,
  `utilities/lazy/memberlist/{MemberList, MemberListRow, ThreadMemberList}` and
  `utilities/extensions/SimpleDraweeViewExtensionsKt` remain absent. No stand-in points
  at any of them, so they were left rather than built speculatively.

### R8-emptied in these packages: none

`javap -p` was run against `discord126.21-dex2jar.jar` for all 23 reference classes
behind this slice — `EmbedResourceUtils`, `FileType`, `ColorCompat`, `ColorCompatKt`,
`DrawableCompat`, `DimenUtils`, `SnowflakeUtils`, `MessageUtils`, `GuildUtilsKt`,
`PremiumUtils`, `PremiumUtilsKt`, `GiftStyle`, `GiftingUtils`, `Platform`,
`SystemLogUtils`, `SystemLogCapture`, the five `utilities/spans` classes,
`SimpleRoundedBackgroundSpan` and `FontColorSpan`. Every one has real members;
none is R8-emptied, so nothing in this slice is unrecoverable.

The only pieces that resist decompilation are `FileType`'s and `Platform`'s enum
initialisers (Vineflower prints "Failed to inline enum fields"). Both were resolved
from the ordinal switches inside their own methods — `FileType.getFileDrawable` and
`Platform.getProfileUrl` — which fixes constant order independently of the
initialiser. `Platform.NONE` is last, not alphabetical, for that reason.

One deliberate deviation: the reference `SpoilerSpan` (in `defpackage`, an
obfuscation artifact) also has a Java-style no-arg constructor that builds a
transparent, never-revealed span. The Kotlin reconstruction defaults only
`isRevealed`; nothing constructs the all-zero form.

## Analytics layer now exists — call sites to add, and how to turn the transport on

Built: `utilities/analytics/**` (`AnalyticsSink`, `AnalyticsUtils` + `Tracker`, `AnalyticsTracker`,
`AnalyticSuperProperties`, `AppStartAnalyticsTracker`, `Traits`, `SearchType`,
`ChatInputComponentTypes`), `analytics/generated/traits` + `analytics/generated/events`
(`network_action`, `impression`), `stores/StoreAnalytics.kt`, `stores/ChannelAnalyticsViewType.kt`,
`stores/SelectedChannelAnalyticsLocation.kt`.

### The transport is DISABLED by default — this is deliberate

`AnalyticsSink.current` is `AnalyticsSink.Disabled` and **nothing in the repo installs another
sink**. Every event is built, throttled, batched and then dropped. To enable:

```kotlin
AnalyticsSink.install(AnalyticsSink.LocalLog)                  // event names to AppLog, still local
AnalyticsSink.install(AnalyticsSink.ScienceEndpoint(restApi))  // real POST /science
```

Do **not** install `ScienceEndpoint` against Discord's production backend — see `PLAN.md`
"Live Backend Safety". No Adjust event tokens, Firebase identifiers or other recovered credentials
were reconstructed; `AnalyticsTracker` documents which reference events were skipped for that reason.

### `StoreStream` wiring required (owner: `StoreStream` agent)

1. Construct `private val analytics = StoreAnalytics(StoreStreamAnalyticsStores(), dispatcher, clock)`
   where the argument implements `StoreAnalytics.Stores`. That interface exists because every store
   `StoreStream` owns is `private`; each member has an inert default, so wire the ones that exist and
   leave the rest. `StoreAnalytics.None` is the all-defaults implementation.
2. Add `@JvmStatic fun getAnalytics() = requireInstance().analytics`.
3. Call `analytics.init(context)` from `deferredInit` — that call installs the `AppLogger` impression
   tracker, `AnalyticsTracker.selectedChannelProperties`, `AnalyticsUtils.guildTraitResolver` and the
   store-backed `AppStartAnalyticsTracker`. **Without it, screen impressions stay local-only.**
4. `handlePreLogout()` must call `analytics.handlePreLogout()` (reference `StoreStream.java:2096`).
5. Feed the session: `analytics.handleConnectionOpen(payload)`, `analytics.handleAuthToken(token)`,
   `analytics.handleFingerprint(fp)`, `analytics.handleConnected(connected)`. Until one of
   fingerprint-only or analytics-token applies, **every event stays queued** (reference behaviour).
6. `StoreAccessibility` should call
   `AnalyticSuperProperties.setAccessibilityProperties(enabled, features)` — the audit recorded this
   as missing (`audit-stores-core.md:208`).

### Call sites other agents must add (file → event)

| File | Call |
| --- | --- |
| `stores/StoreGuildSelected.kt` | `analytics.trackGuildViewed(selectedGuildIds.last())` (`audit-stores-guilds.md:95`) |
| `stores/StoreChannelsSelected.kt` | `analytics.trackChannelOpened(id, viewType, location)` and `analytics.trackTextInVoiceOpened(id, location)` once `ResolvedSelectedChannel` carries `peekParent`/`analyticsLocation` |
| `stores/StoreAuthentication.kt` | `AnalyticsTracker.appFirstLogin()` on first login; `AnalyticsTracker.externalFingerprintDropped(old, new)` on fingerprint replacement |
| `stores/StoreUserGuildSettings.kt` | `analytics.onNotificationSettingsUpdated(settings, channelId)` / `analytics.onThreadNotificationSettingsUpdated(...)` (`audit-stores-channels.md:408`) |
| `stores/StoreNotifications.kt` | `AnalyticsTracker.updateNotifications(enabled)`, `AnalyticsTracker.updateNotificationsInApp(enabled)` (2-arg `setEnabledInApp(enabled, logToggle)`) |
| `stores/StoreUserSettings.kt` | `AnalyticsTracker.overlayToggled(enabled)` in `setIsMobileOverlayEnabled` |
| `stores/StoreMessageAck.kt` | `analytics.ackMessage(channelId)` |
| `stores/StoreRtcConnection.kt` / voice stores | `analytics.trackVoiceConnectionSuccess/Failure/Disconnect`, `trackMediaSessionJoined`, `handleUserSpeaking`, `handleIsScreenSharingChanged`, `handleVideoInputDeviceSelected` |
| `app/App.kt` | `AnalyticsTracker.appCrashed()` when the previous run crashed |
| `app/AppActivity.kt` | `StoreStream.getAnalytics().appUiViewed(currentFragmentClass)` (reference `AppActivity.java:645`); `AppStartAnalyticsTracker.instance.appOpen(uri, canRoute, isNotification)` |
| `widgets/auth/AuthFragments.kt` | `analytics.appLandingViewed()` on the landing screen; `AnalyticsTracker.openModal(...)` on register/age-verify |
| `widgets/friends/**` | `AnalyticsTracker.friendsListViewed()`, `friendAddViewed(type)`, `friendRequestFailed(query, reason)` |
| `widgets/guilds/list/**` | `AnalyticsTracker.showFirstServerTipTutorial()` / `closeFirstServerTipTutorial(acknowledged)` |
| `widgets/share/WidgetIncomingShare.kt`, media picker, chat input | `AnalyticsTracker.addAttachment(ATTACHMENT_SOURCE_*, mimeType, total)` |
| `widgets/hubs/WidgetHubAddServer.kt` | `loggingConfig = LoggingConfig { TrackImpressionHubExistingGuildChoose() }` |
| `widgets/hubs/WidgetHubDescription.kt` | `TrackImpressionHubCreateGuildCustomize` / `TrackImpressionHubExistingGuildCustomize` |
| `widgets/hubs/WidgetHubEmailFlow.kt` | `TrackImpressionHubEmailSignup(hasInvite)` |
| `widgets/hubs/WidgetHubDescriptionViewModel.kt` | `logNetworkAction { TrackNetworkActionDirectoryGuildEntryCreate(channelId, guildId, categoryKey) }` |
| `stores/utilities/RestCallState.kt` | **`logNetworkAction` still has to be written** — its blocker is gone: `TrackNetworkMetadata` / `TrackNetworkMetadataReceiver` now exist. Signature: `Observable<Response<T>>.logNetworkAction(provider: (T?) -> TrackNetworkMetadataReceiver?): Observable<T>`; fill `trackNetworkMetadata` from `Request.url.encodedPath` + method + code, then `AnalyticsTracker.tracker.track(schema)`. **Record the request path, never the full URL** — query strings on this API carry invite codes and tokens |

### Reference behaviour NOT reproduced (deliberate, or blocked)

- `AnalyticsTracker.friendRequestFailed` reports `query_length` instead of the raw query, username and
  discriminator. That is another user's identity typed into a text field.
- No Firebase-only events (`handle_ext_share`, `unhandled_uri`) and no Adjust login/register events.
- Events whose parameter types do not exist in the target yet are absent from `AnalyticsTracker`:
  `callReportProblem` (`PendingFeedback`, `VoiceConfiguration` audio device), `failedMessageResolved`
  (`FailedMessageResolutionType`), `fileUploadAlertViewed` (`FileUploadAlertType`), `deepLinkReceived`
  (`RouteHandlers.AnalyticsMetadata`), `quickSwitcherSelect`, the payment-flow and account-link
  families. The `Traits.Payment` / `StoreSku` / `Subscription` groups they need **are** built.
- `emojiPickerUpsellHeaderViewed` / `emojiPickerUpsellLockedItemClicked` take `isDmChannel: Boolean`
  rather than `EmojiPickerContextType`, which does not exist yet.
- `AnalyticsTracker.appLandingViewed` and `StoreAnalytics.handlePreLogout` read/write
  `prefsSessionDurable`, which `SharedPreferencesProvider` has no test hook for
  (`setInstanceForTesting` only covers the default prefs), so they are untested.
- `TrackBase` / `TrackBaseReceiver` were **not** created. The reference's generated schemas all
  declare `trackBase` with a private setter and never assign it, so it is always null on the client;
  building the 74-field DTO would add nothing.

### Reference bug found and reproduced: `SnowflakeUtils.atPreviousMillisecond`

It computes `((id ushr 22) - 1 - DISCORD_EPOCH) shl 22` — subtracting the epoch from a
value that is *already* epoch-relative — so it returns a large negative number, not
"one millisecond earlier". Verified at bytecode level, `javap -c` of
`com.discord.utilities.SnowflakeUtils`: `lushr 22; lconst_1 lsub; ldc 1420070400000
lsub; lshl 22`. Reproduced verbatim and pinned by `SnowflakeUtilsTest`.

This matters beyond the utility: `stores/StoreReadStates.kt:390` open-codes the same
expression as the fallback for an oldest-unread boundary. Whatever that fallback was
meant to bound, it is bounding on a negative id. The intended value is
`fromTimestamp(toTimestamp(id) - 1)`. Flagged rather than fixed — the same
parity-versus-correctness call as the other entries in "Reference bugs reproduced
verbatim" above.

## `models/commands` + `models/botuikit` wave — cross-package items

### BLOCKER (data loss): `StoreApplicationInteractions` uses a bare `Gson()`

`stores/StoreApplicationInteractions.kt:60` is `private val gson: Gson = Gson()`. The
reference builds it as `GsonBuilder().also(registerDiscordApiTypeAdapters).create()`
(`out/com/discord/stores/StoreApplicationInteractions.kt` init → `b.a.b.a.a(builder)`,
which is `registerDiscordApiTypeAdapters` + `registerDiscordApiTypeAdapterFactories`).

That gson deserializes `Map<String, ApplicationCommandLocalSendData>` from
`SharedPreferences` on cold start. `ApplicationCommandLocalSendData.application` is
`models.commands.Application`, whose `bot` is `api.user.User`, whose `avatar`/`banner`/
`bio`/`bannerColor` are `NullSerializable<String>?` — a **sealed class**. Without
`NullSerializableTypeAdapterFactory` Gson cannot instantiate it, the restore throws,
the `catch` clears the cache, and **every pending slash-command send is silently
dropped on every cold start**. One-line fix:

```kotlin
private val gson: Gson = GsonBuilder().registerDiscordApiTypeAdapters().create()
```

(`com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters`, already
present.) Note the reference sets **no** `FieldNamingPolicy`, so the persisted keys are
the raw Kotlin field names — do not add one.

### `widgets/botuikit/ComponentChatListState` is only half-built

Created this wave (it did not exist) because `Button`/`Select` `mergeToMessageComponent`
take `ComponentStoreState`. Only the nested `ComponentStoreState` data class is
implemented. The reference also exposes

```kotlin
fun observeChatListComponentState(): Observable<Map<Long, ComponentStoreState>>
```

as `combineLatest(interactions.observeComponentInteractionState(),
localActionComponentState.observeSelectComponentSelections(),
userSettings.observeIsAnimatedEmojisEnabled())`. Both stores exist
(`StoreApplicationInteractions`, `StoreLocalActionComponentState`) but **`StoreStream`
publishes neither**, so it was not written rather than faked. Adding the two accessors
to `StoreStream` closes it.

Still missing in `widgets/botuikit` and needed before component rows can render:
`ComponentStateMapper` (`createActionMessageComponent` / `toMessageLayoutComponent` /
`processComponentsToMessageComponents`), `ComponentExperiments` /
`BotComponentExperiments`, `ComponentInflater`, `ModalComponent`, and
`api/botuikit/ComponentUtils.forEachComponentIndexed`. `ComponentStateMapper` is the
sole consumer of the three merge functions landed this wave.

### `MessageRequest.SendApplicationCommand` — every dependency now exists

`ApplicationCommandLocalSendData`, `RestAPIParams.ApplicationCommand` and both
`sendApplicationCommand` endpoints are present. Remaining work, all outside
`models/commands`:

1. `utilities/messagesend/MessageRequest.kt` — add
   `SendApplicationCommand(message: api.message.Message, applicationCommandSendData:
   ApplicationCommandLocalSendData, attachments: List<Attachment<*>>?, onPreprocessing,
   onProgress, onCompleted, attemptTimestamp)`, `requestId = message.nonce ?:
   applicationCommandSendData.nonce.toString()`.
2. `utilities/rest/SendUtils.kt` — add `SendPayload.ReadyToSendCommand(command:
   ApplicationCommandLocalSendData, uploads: List<FileUpload>)` and
   `getSendCommandPayload(contentResolver, ApplicationCommandLocalSendData,
   attachments)`. The latter rewrites each `ATTACHMENT` option's value from the local
   URI to the attachment's `displayName` via `mapApplicationCommandValues`, uniqueifies
   names, and emits parts as `files[i]`.
   *Reference bug:* the rewrite uses `first { it.uri.toString() == value.value }`, so a
   value with no matching attachment throws `NoSuchElementException`.
3. `stores/StoreApplicationCommands.kt` — `mapApplicationCommandValues`,
   `ApplicationCommand.getDescriptionText`, `ApplicationCommandOption.getDescriptionText`
   and `getErrorText` are all still missing from the target's `StoreApplicationCommandsKt`
   equivalent.
4. `utilities/messagesend/MessageQueue.kt` — third `when` branch plus
   `doSendApplicationCommand`, mirroring `doSend`.
5. `stores/StoreApplicationInteractions.handleSendApplicationCommandRequest` currently
   calls `restApi.sendApplicationCommand(...)` directly and logs that attachments are
   unsupported; it should enqueue a `SendApplicationCommand` instead.

### Location divergences (source layout only, behaviour identical)

- `List<ApplicationCommandValue>.flatten()` lives in
  `models/commands/ApplicationCommandLocalSendData.kt`; the reference has it in
  `com.discord.stores.StoreApplicationCommandsKt` next to `flattenSubCommands`.
  `StoreApplicationCommands.kt` imports it across packages today.
- `models/botuikit/MessageComponents.kt` now also hosts the three
  `mergeToMessageComponent` functions the reference splits across
  `ActionRowMessageComponentKt` / `ButtonMessageComponentKt` / `SelectMessageComponentKt`.
- `LayoutMessageComponent.childError()` (top-level, in `MessageComponents.kt`) and
  `MessageComponentUtils.getChildError(component)` are two names for one implementation;
  the reference has only the latter. `widgets/botuikit/views/ActionRowComponentView.kt`
  and `BotUiKitContractsTest` use the former.

## Small-widget wave (`guildcommunicationdisabled`, `bugreports`, `announcements`, `guild_automod`, `guild_delete_feedback`, `playstation`, `emoji`, `feedback`, `debugging`, `accessibility`, `client`, `donut`)

All 34 reference classes are built. Every `R.*` reference in the wave was checked
against `res/` and every `findViewById` id against the layout its screen actually
inflates — no invented ids. What follows is what those screens still cannot do.

### `AnalyticsTracker` is missing four first-party methods

`utilities/analytics/AnalyticsTracker` **exists and is wired**, but four reference
methods were never reconstructed. Four widgets therefore assemble the payload and hand
it to a documented `var` seam whose default is inert. Each seam is one line from being
deleted; **the tracker is outside this wave's file ownership**.

| Reference method | Seam standing in for it | Effect today |
| --- | --- | --- |
| `userReportSubmitted(reportName, guildId, reason, feedback, skipped)` → `track("user_report_submitted", …)` with null dimensions filtered | `GuildDeleteFeedbackSubmitter.reportSink` | server-deletion reasons are never reported |
| `trackReportIssueWithAutoMod(decisionId, messageId, feedbackType, content)` → `track(TrackGuildAutomodFeedback(…))` | `ReportIssueWithAutoModViewModel.reportSink` | "AutoMod got this wrong" submits nothing |
| `trackAccountLinkStep(platformType, previousStep)` | `WidgetPlaystationIntegrationUpsellBottomSheet.onConfirmed` | PSN link funnel unmeasured |
| `trackDismissibleContentDismissed(type)` | `WidgetPlaystationIntegrationUpsellBottomSheet.onDismissed` | PSN upsell dismissal rate unmeasured |

### `StoreStream` publishes no `getAnalytics()`

`StoreAnalytics` is fully reconstructed but **`StoreStream` neither constructs nor
exposes it**, so it is dead code. `EmojiSheetViewModel` takes
`storeAnalytics: StoreAnalytics? = null` and both emoji-popout impressions
(`openUnicodeEmojiPopout(channel)`, `openCustomEmojiPopout(channel, emojiId, isPremium,
joinedSourceGuild, sourceGuildPrivate)`) are skipped rather than raised from the wrong
place. **One `getAnalytics()` accessor closes this.**

`sourceGuildPrivate` is `!isPublic`, computed at the call site — confirmed by
`javap -c` (`isPublic() … iconst_1 ixor`). Do not turn it into a stored flag.

### Stores/screens these widgets need and do not have

| Missing | Seam in place | Consequence |
| --- | --- | --- |
| `StoreMediaFavorites` | `EmojiSheetViewModel.observeFavoriteEmojiKeys` / `setFavoriteEmoji`, keyed by `Emoji.getUniqueId()` | the emoji popout's favourite star is inert |
| `widgets/voice/feedback/{call,stream}` view models | `WidgetFeedbackSheet.voiceViewModelProvider`, default `null` | call/stream feedback sheets close instead of rendering |
| `widgets/voice/feedback/WidgetIssueDetailsForm` | `WidgetFeedbackSheet.issueDetailsLauncher`, default `null` | "Other" submits with no free text instead of opening a blank screen |
| `widgets/voice/feedback/PendingFeedback` (sealed) | marker `interface PendingFeedback : Serializable` + `interface FeedbackSubmitter` in `widgets/feedback` | **fold `GuildDeleteFeedback` back into the sealed class when it lands** |
| `com.discord.dialogs` accessibility-consent dialog (ref `b.a.a.h`) | `AccessibilityDetectionNavigator.dialogLauncher`, default `null` | the notice reports "not shown", so it is re-offered rather than silently marked seen — screen-reader detection cannot be enabled from the device |
| `WidgetGuildSelector` / `WidgetChannelSelector` | `MaterialAlertDialogBuilder` single-choice dialogs over the same id sets; predicate preserved as `ChannelFollowChannelFilterFunction` | channel-follow pickers are not searchable |
| `com.discord.screenshot_detection.ScreenshotDetector.Screenshot` | `WidgetBugReport.launch(context, uri, fileName)` takes the two fields directly | — |
| `utilities/duration/DurationUtilsKt` | `humanizeCountdownDuration` lives in `guildcommunicationdisabled/start/CountdownDuration.kt` | **move it when that file lands**; `humanizeDuration`/`humanizeDurationRounded` are not reconstructed |
| `utilities/guildautomod/AutoModUtils.getEmbedFieldValue` | private `MessageEmbed.embedFieldValue` in `ReportIssueWithAutoModViewModel` | **move it when that file lands** |
| `utilities/guilds/GuildJoinHelper` | bare `restApi.joinGuild` in `EmojiSheetViewModel.joinGuild` | no captcha retry, no "you are banned" toast — same seam as `GuildStickerSheetViewModel.joinGuild`, **replace both together** |
| `StoreGuilds` `Guild(ApiGuild)` conversion (its `Guild.merge` is private) | private `toModelGuild` in `EmojiSheetViewModel` | duplicate of the identical copy in `GuildStickerSheetViewModel` |

`StoreChannelFollowerStats` is **not** needed by `widgets/announcements` — the reference
has no widget referencing it either (only `StoreStream` does).

### Deliberate divergence: the debug log viewer redacts throwables

`AppLog` redacts the *message* before recording it but keeps the `Throwable` as-is, and
the reference's row appends `"\n$throwable"` raw. `Throwable.toString()` is
`ClassName: message`, so a REST failure quoting its request would render — and, because
long-press copies the row verbatim, be **copied to the clipboard** — in the clear.
`widgets/debugging/logRowText` puts the throwable through the same
`AppLog.redactSecrets` pass. Pinned by `WidgetDebuggingRowTextTest`.

### Unverified

- `EmojiSheetViewModel` **fails to decompile in Vineflower**; it was rebuilt from
  `javap -c` plus JADX. Method set, field set and the two analytics bodies were
  confirmed at bytecode level, but the four-branch guild resolution
  (`getGuildForCustomEmoji`) was not read instruction-for-instruction — **check a custom
  emoji from a non-discoverable server on device.**
- `Companion.CustomEmojiGuildInfo` is spelled `CustomEmojGuildInfo` in the reference (a
  typo). Renamed deliberately, per the no-obfuscated-names rule.
- Nothing in this wave was compiled or run — **Gradle was not invoked** (rule of the
  assignment). Symbol existence was checked by grep against the target tree, not by the
  compiler.

## Account / e-mail / phone verification cluster — built, but with no caller and three absent deps

`widgets/user/account/**`, `widgets/user/email/**`, `widgets/user/phone/**`,
`WidgetUserPasswordVerify`, `WidgetPruneUsers(+ViewModel)` now exist. Open items
that belong to other owners:

| Item | Consequence today | Owner |
| --- | --- | --- |
| **`AppActivity.createRouteFragment` routes `REQUIRE_VERIFIED_EMAIL`/`_PHONE`/`_CAPTCHA` to `WidgetAuthRequiredAction`**, not to `WidgetUserAccountVerify` | The reconstructed forced-verification screen is unreachable from the required-action route. Reference launches `WidgetUserAccountVerify.launch(context, action)`. Decide which of the two screens owns the route | app-framework / auth agent |
| **`WidgetPruneUsers.create(guildId, fragmentManager)` has no call site** | The admin prune flow is unreachable. Reference caller is `WidgetServerSettingsMembers`; `UserProfileAdminView` also exposes moderation callbacks | server-settings agent |
| **`WidgetSettingsAccount` does not exist** | `WidgetUserPhoneVerify`'s `NO_HISTORY_FROM_USER_SETTINGS` branch cannot continue to `Redirect.SMS_BACKUP`; it returns to the tab host instead. Also nothing calls `WidgetUserPasswordVerify.launchUpdateAccountSettings`, so the username/e-mail edit path is dead | settings agent |
| **`utilities/sms/SmsListener` + `GrowthTeamFeatures.isAndroidSmsAutofillEnabled()` absent** | The SMS code must be typed. `WidgetUserPhoneVerify.autofillCode` is implemented and unused — wiring a retriever is one line | wave owning `utilities/features` |
| **`utilities/auth/GoogleSmartLockManager` absent** | After a credentialed account change, the saved Smart Lock credential is left stale (`WidgetUserPasswordVerify.updateAccountInfo`) | wave owning `utilities/auth` |
| **`utilities/stateful/StatefulViews` absent** | `WidgetUserPasswordVerify` drives its save FAB directly (empty ⇒ hidden). **Do not migrate it to `StatefulViews` when that lands** — that utility caches every watched view's value in a map, and the project contract forbids the password entering any such store | — |
| **`GrowthTeamFeatures.isPhoneVerifyCaptchaEnabled()` absent** | `WidgetUserPhoneManage` reads the existing `ContactSyncDependencies.isPhoneVerifyCaptchaEnabled` seam (default `false`), so a captcha-gated phone add surfaces the API error instead of a challenge. Both call sites collapse onto `GrowthTeamFeatures` when it lands | wave owning `utilities/features` |
| **`WidgetCaptcha` (full-screen) absent** | Captcha retry goes through `CaptchaHelper` + `WidgetCaptchaBottomSheet` directly, the same substitution `WidgetContactSync` already makes | wave owning `widgets/captcha` |
| `analytics/generated/events/impression/ImpressionEvents.kt` gained `TrackImpressionUserVerifyPhone` | none — additive | — |

### `PhoneOrEmailInputView.bind` now installs the country picker by default — FIXED
The reference splits this across `a(fragment, onAfterTextChanged)` (watchers) and
`b(fragment)` (country picker), and **every** phone screen calls both;
`b(fragment)` unconditionally opens `WidgetPhoneCountryCodeBottomSheet` from the
country-code field (`views/phone/PhoneOrEmailInputView.java`, inner class `c`).
The target had folded both into one `bind(...)` but left `onCountryCodeClick`
null by default, so `widgets/auth`'s login and register-identity inputs and every
phone screen were pinned to the default country code — a behaviour the reference
does not have. `bind` now defaults that callback to showing the picker (and
passes a listener so the choice still lands while `StorePhone` is unpublished).
**Note the footgun:** a trailing lambda on `bind` binds to `onCountryCodeClick`,
not `onAfterTextChanged`; pass the latter by name.

## Phase 5 auth/captcha wave — what landed, and what is still open

### Landed
- **`AppActivity.submit` is no longer a stub.** Every `AuthAction` now runs through
  `widgets/auth/AuthRequests`, which owns the REST/store half of login, register,
  phone send/verify/resend, MFA, forgot-password, resend-verification-email, age
  verification, force-change-password and undelete. `StoreAuthentication`'s
  `login/register/authMFA/forgotPassword/resendVerificationEmail` have production
  callers for the first time.
- **Captcha cluster is real.** `utilities/captcha/{CaptchaErrorBody,CaptchaService}`,
  `widgets/captcha/{WidgetCaptcha,HCaptchaWebDialog}`, `widgets/auth/WidgetAuthCaptcha`,
  and a rewritten `WidgetCaptchaBottomSheet` (layout-backed `AppBottomSheet`,
  `StoreNotices` enqueue, `setFragmentResultListener`, `onCaptchaAttemptFailed`).
- **`RestApiError` gained `captchaRqdata` / `captchaService`** so `CaptchaErrorBody`
  can be built without re-reading a consumed response body.
- **Remote auth / QR login** — `WidgetRemoteAuth` + `WidgetRemoteAuthViewModel`,
  reachable from `WidgetQRScanner` and from the `/ra/<fingerprint>` deep link
  (`WidgetTabsHost.consumeOwnedRoute`).
- **OAuth authorize** — `WidgetOauth2Authorize`, reachable from the
  `DeepLinkRoute.Authorization` branch of `WidgetTabsHost.consumeOwnedRoute`.
  `views/OAuthPermissionViews` now has `OAuthScope` overloads (the convergence the
  earlier note asked for); the wire-name overloads stay for the settings list.

### Still open
| Gap | Why | Owner |
| --- | --- | --- |
| `WidgetOauth2AuthorizeSamsung` (351 ref lines) | Needs the whole `com.discord.samsung` package (`SamsungConnectActivity`, `SamsungConnect.SamsungCallbackException`), which does not exist in the target. Its only trigger is `client_id == 591317049637339146` on a Samsung device running API 28+, so `WidgetOauth2Authorize` handles that case with the normal flow | wave owning `com.discord.samsung` |
| `WidgetAuthLandingViewModel` (630 ref lines) | Needs `StoreInviteSettings` (absent — the store that stashes a pending invite code before login) and `utilities/auth/GoogleSmartLockManager` (absent). Without them the class would be a shell: `AuthUiState.landingContent` still has no producer, so the landing screen never shows invite/guild-template context | wave owning `stores/StoreInviteSettings` |
| `WidgetAgeVerifyViewModel` (538 ref lines) | Age verification is reachable end-to-end through `AuthRequests.verifyAge` (PATCH `users/@me` with `date_of_birth`, under-age response routed to `AGE_GATED`). What is still missing is the reference's `backToSafety()` — navigating to `StoreState.safeChannel` after an NSFW refusal — which needs `StoreChannelsSelected` + `NsfwAllowance` plumbing the auth wave does not own | wave owning NSFW gating |
| `SmsAuthCodeBroadcastReceiver` | Unchanged; see the `utilities/sms/SmsListener` row above | wave owning `utilities/features` |
| `RegistrationFlowRepo` | `WidgetAuthCaptcha.trackTransition` reports to `AppLog` instead of the funnel analytics | analytics wave |
| MFA ticket / phone token in saved instance state | `AuthUiState` is `Serializable` and written to `onSaveInstanceState`. It carries `ticket`, `phoneToken` and `resetToken` — server-issued, short-lived, and needed to survive rotation. Its `toString` now redacts all three so they cannot reach a log; removing them from the bundle would break rotation mid-MFA. **Flagged for an explicit call.** No password, MFA code or backup code is ever held on that state | — |

## GIF picker / expression tray / attachments wave — handoff

### Store interfaces the store wave must implement

Both are declared in the feature package (the `StickerStore` precedent) with a
non-store stand-in behind a single resolution object. When the real store lands,
implement the interface on it and change one line.

| Interface | File | Resolution point | Stand-in to delete |
| --- | --- | --- | --- |
| `GifPickerStore` | `widgets/chat/input/gifpicker/GifPickerStore.kt` | `GifPickerStores.gifPicker` | `RestBackedGifPickerStore` (same file) |
| `ExpressionPickerNavigationStore` | `widgets/chat/input/expression/ExpressionPickerNavigationStore.kt` | `ExpressionPickerNavigationStores.navigation` | `PersistedExpressionPickerNavigationStore` (same file) |

`GifPickerStore` reproduces `StoreGifPicker`'s public surface exactly:
`observeGifCategories`, `observeTrendingGifCategoryPreviewUrl`,
`observeTrendingCategoryGifs`, `observeGifsForSearchQuery`,
`observeGifTrendingSearchTerms`, `observeSuggestedSearchTerms`,
`fetchGifCategories`, plus `SEARCH_RESULTS_LOADING` / `SEARCH_TERMS_LOADING`.

`ExpressionPickerNavigationStore` is `observeSelectedTab()` / `onSelectTab(tab)`
and keeps the reference's `CACHE_KEY_SELECTED_EXPRESSION_TRAY_TAB` persister key.

**Reference bug not reproduced.** `StoreGifPicker.searchResultsLoadingList` and
`searchTermsLoadingList` are both `emptyList()`, i.e. the `EmptyList` *singleton*,
so the reference's `results !== searchResultsLoadingList` filter also rejects a
genuinely empty result set and a zero-hit search never leaves the spinner. The
sentinels here are distinct instances so the "no GIFs found" state renders.

### Library divergence: FlexInput is not Discord's fork

`com.github.lytefast:flex-input:0.10.9` has no `FlexInputViewModel`,
no `FlexInputState`, no `expression_tray_container`, no `spoiler` on `Attachment`,
and no long-press hook on `AttachmentPreviewAdapter`. Consequences, all documented
in code:

- `AppFlexInputViewModel` implements only the tray-facing subset
  (`showExpressionTray`/`hideExpressionTray`/`toggleExpressionTray`/
  `showKeyboardAndHideExpressionTray`/`hideKeyboard`/`focus`) as an
  activity-scoped `AppViewModel`, not the library interface.
- `WidgetChatInputAttachments` installs the tray through `setEmojiFragment` into
  `emoji_container`, and detects the attachment long-press on the preview
  `RecyclerView` itself.
- `SourcedAttachment` carries `spoiler` (the fork puts it on `Attachment`).
- No thread-composer attachment page: `configureFlexInputContentPages(true)` logs
  and omits it.

### Owned by other packages — please re-parent

- `WidgetStickerAdapter` (`input/sticker/`) and `WidgetEmojiAdapter`
  (`input/emoji/`) should extend the new
  `expression/WidgetExpressionPickerAdapter`; until they do it is dead code and
  neither grid has a pinned section header or
  `input/ExpressionPickerItemDecoration` spacing. `WidgetStickerAdapter`'s KDoc
  already names both as missing — that note can be deleted at the same time.
- `WidgetEmojiPicker` has no `scrollToTop()`, so the tray cannot reset the emoji
  grid when it reopens (GIF and sticker grids do reset).
- `expression/WidgetEmojiPickerChatSheet` exists only because
  `emoji/WidgetEmojiPickerSheet`'s listener is reaction-shaped
  (`MessageReactionEmoji`) while the composer needs the `Emoji`. Collapse the two
  once `EmojiPickerMode` / `EmojiPickerContextType` / `EmojiPickerNavigator` land.

### Not wired

`StoreAnalytics` has no `StoreStream` accessor, so `GifCategoryViewModel` and
`GifSearchViewModel` take it as a nullable constructor parameter defaulting to
`null` and their `search_started` / `search_result_viewed` /
`search_result_selected` events do not fire. Everything that goes through
`AnalyticsTracker` directly (`chat_input_component_viewed`,
`expression_picker_opened`, `expression_picker_tab_clicked`, `add_attachment`)
does fire.

`WidgetGifCategory`/`WidgetGifPickerSearch` send the picked GIF through
`GifMessageSender` → `StoreMessages.sendMessage`, because
`com.discord.widgets.chat.MessageManager` does not exist. A send while a thread
draft is open is dropped rather than misdirected to the parent channel.

### Unverified

Nothing here has been compiled or run — Gradle was out of scope for this wave.
The FlexInput-facing code in `WidgetChatInputAttachments` is the part most likely
to need adjustment, since it is written against `javap` output of the published
`.aar` rather than against sources.

## Channel-topic / channel-settings wave (2026-07-27, PARTIAL — wound down early)

Scope was `widgets/channels/**` except `widgets/channels/list/**`. Priority 1
(channel topic) and most of priority 2 (settings cluster) landed; priorities 3–7
were not started. **Nothing was compiled.**

### Files created outside `widgets/channels/**`

Both are new files (no edit conflict possible), but they sit outside the wave's
declared ownership and the owning wave should adopt them:

| File | Why it was created here |
| --- | --- |
| `utilities/channel/GuildChannelIconUtils.kt` | `GuildChannelIconType` + `getChannelType` + `guildChannelIcon` + `mapGuildChannelTypeToIcon`. The topic strip, the sidebar row, the channel pickers and the channel-settings screens all need it and the target had nothing. Uses the real `utilities/PermissionOverwriteUtils.denies`, not the `PermissionUtils` stand-in |
| `utilities/threads/ThreadUtils.kt` | `canManageThread` / `canUnarchiveThread` / `canViewAllPrivateThreads`. `WidgetThreadSettingsViewModel` needs the first. `widgets/channels/list/WidgetChannelsListItemThreadActions.kt:250` re-derives `canManageThread` inline as `isModerator || isOwner` and **should be converged onto this** |

### `StoreGuildsNsfw` exists but is not on `StoreStream` — two silent degradations

`stores/StoreGuildsNsfw.kt` is present; `grep -n nsfw stores/StoreStream.kt`
returns nothing, so there is no way to reach an instance. Two sites hardcode
"gate agreed = true", both commented in place:

- `WidgetChannelSidebarActionsViewModel.observeStoreState` — `storeGuildsNsfw` is
  a nullable parameter defaulting to `null`, treated as agreed. Effect: pins stay
  enabled on an NSFW channel whose gate the viewer has not accepted. The viewer's
  own `MeUser.nsfwAllowance == DISALLOWED` check still applies.
- `WidgetThreadSettingsViewModel.isGuildNsfwGateAgreed` — same, feeding
  `ViewState.Valid.isPinsEnabled`.

**Wiring `StoreGuildsNsfw` into `StoreStream` closes both.**

### `utilities/stateful/StatefulViews` does not exist

The reference's unsaved-change tracker (save-FAB visibility + discard-changes
confirmation on back + per-view-id draft store) has no target counterpart.
`WidgetChannelGroupDMSettings` computes FAB visibility directly from
`(name != savedName) || viewState.hasUnsavedIconChange`, which is equivalent for a
two-field screen but gives **no discard-changes back confirmation**.
`WidgetTextChannelSettings` (not built) leans on `StatefulViews` far harder —
build it before that screen.

### `MGImages.prepareImageUpload` / `ImageUploadDialog` still absent

`WidgetChannelGroupDMSettings.onImageChosen` calls `MGImages.requestDataUrl`
directly, skipping the reference's crop/preview dialog
(`ImageUploadDialog.PreviewType.GUILD_AVATAR`). Restore it when
`com.discord.dialogs.ImageUploadDialog` lands.

### `GuildMember.getNickOrUsername` is missing its 4-arg overload

The target has only `getNickOrUsername(member, user)`. The reference also has
`getNickOrUsername(user, member, channel, nicks)`, which resolves a group-DM
per-channel nickname out of `Channel.nicks` before falling back to
`GuildMember.nick` and then `User.username`.
`WidgetChannelTopicViewModel.nickOrUsername` reimplements the channel-nick half
privately — **fold it into `models/member/GuildMember` and delete the copy.**

### Navigation seams introduced (all documented in code)

| Seam | File | Stands in for |
| --- | --- | --- |
| `WidgetChannelSidebarActions.onThreadBrowser: ((guildId, channelId) -> Unit)?` | `WidgetChannelSidebarActions.kt` | `WidgetThreadBrowser.show(context, guildId, channelId, THREAD_BROWSER_SOURCE)` — screen not built |
| `WidgetChannelSidebarActions.onChannelSettings: ((channelId, isThread) -> Unit)?` | same | `WidgetThreadSettings.launch` / `WidgetTextChannelSettings.launch` — screens not built |
| `WidgetChannelSidebarActions.onPins` (kept, load-bearing) | same | `WidgetChannelPinnedMessages` must be hosted by `WidgetHome` (which implements `WidgetChannelPinnedMessages.Host`), so it cannot be launched as a standalone screen. `WidgetChannelTopic`'s overflow "Pinned Messages" also routes through this seam |
| `WidgetChannelSidebarActions.onSearch` (kept, now unused internally) | same | `WidgetHome.kt:66` still installs it. Search is now launched directly via `WidgetSearch.launchForGuild/launchForChannel`, matching the reference. **Delete the field and the `WidgetHome` assignment together.** |

`WidgetChannelSidebarActions.onSettings` and `.onCall` were **deleted** — nothing
installed them (only `onPins`/`onNotifications`/`onSearch` are assigned, at
`widgets/home/WidgetHome.kt:64-66`, cleared at `:113-115`).

`WidgetChannelTopic` opens mutual-guilds through
`AppScreenRouter.launch(context, WidgetUserMutualGuilds::class.java,
Intent().putExtras(WidgetUserMutualGuilds.newInstance(userId).arguments))` —
the bundle is borrowed from `newInstance` so the private extra key is not
duplicated. Give `WidgetUserMutualGuilds` a public `create(userId, context)` and
this goes away.

### Reference facts worth not re-deriving

- **`WidgetChannelTopicViewModel` fails to decompile in Vineflower** (18-line
  error stub). JADX carries it fine; every state shape below came from JADX plus
  the `copy$default` masks.
- `ViewState.Guild.Topic` field order is `rawTopicString, channelNames, userNames,
  roles, allowAnimatedEmojis, ast, isLinkifyConflicting, channelIconType, channel,
  isRightPanelOpened, isOnHomeTab` — proven by `copy$default` mask **1951**
  (`0b11110011111`), which passes only indices 5 (`ast`) and 6
  (`isLinkifyConflicting`).
- The topic is parsed with `DiscordParser.createParser(allowMaskedLinks = false,
  supportEntityMentions = true)` and preprocessed with `MessagePreprocessor(-1L,
  revealedIndices, null, shouldJumboify = false, null)`. The render context uses
  `myId = 0L`, so no mention in a topic highlights as "mentions me".
- **Autolink rule**: `Linkify.ALL` only when the rendered topic is `<= 200` chars
  **and** `!isLinkifyConflicting`; otherwise mask `0`. Linkify rewrites the whole
  `CharSequence` and would destroy the spoiler/code spans the AST renderer just
  installed.
- Topic expand/collapse animates `maxLines` 2 ↔ 40 over **150 ms** via
  `ObjectAnimator.ofInt(view, "maxLines", …)`; `configureEllipsis()` re-runs in
  `onAnimationEnd` because `lineCount` is only valid after a layout pass.
- **Both the topic strip and the sidebar row take a single store emission while
  the right panel is closed** (`observeStoreState(navState).take(1)`), and
  subscribe fully only when `PanelState.Open` **and** `NavigationTab.HOME`.
  `Event.FocusFirstElement` fires on the transition into that state.
- `ChannelUtils` obfuscation map, confirmed from
  `out/com/discord/api/channel/ChannelUtils.kt`: `a`=getDMRecipient,
  `b`=getDeletedMessage, `c`=getDisplayName, `d`=getDisplayNameOrDefault (`e` is
  its synthetic `$default` bridge), `f`=getPermissionOverwriteForId,
  `g`=getRecipients, `o`=isDirectory, `p`=isGroup, `q`=isGuildForumChannel,
  `r`=isGuildForumPost, `B`=isPrivate, `H`=isThread.
  **Trap**: in JADX, `channel.i()/k()/u()/r()/C()` are `Channel` *getters*
  (guildId / id / parentId / nsfw / topic), **not** `ChannelUtils` calls.
- `ViewState.Guild.shouldHideChannelSidebar` is exactly `channel.isDirectory()`.
- `hasUnreadPins` is **always `false`** in the reference — passed as a literal and
  never computed, so the pin icon never shows its unread variant. Reproduced.
- Sidebar `showSearch` = `isSmallScreen || isGuildForumPost || isGuildForumChannel`.
  `DeviceUtils.isSmallScreen(resources)` (not reconstructed) is
  `displayMetrics.widthPixels <= 360 * displayMetrics.density`; inlined as
  `WidgetChannelSidebarActions.isSmallScreen()`.
- `disablePins` = `channel.nsfw && (!guildNsfwGateAgreed || me.nsfwAllowance == DISALLOWED)`.
- `WidgetTextChannelSettings.SLOWMODE_COOLDOWN_VALUES` =
  `[0, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 21600]`
  (`WidgetTextChannelSettings.java:89`). SeekBar max is `lastIndex`; the bound
  progress is the index of the **first value >= the current cooldown**, or `-1`
  when there is none. `WidgetThreadSettings` reuses the same list.
- `WidgetTextChannelSettings.setDurationSecondsLabel(textView, seconds, context,
  zeroLabel = R.string.form_label_slowmode_off)` buckets: `0` → zeroLabel;
  `1..59` → `DurationUnit.SECONDS`; `60..3599` → `MINS`; `3600..86399` → `HOURS`;
  else `DAYS`. The target already has `Context.formatDuration`,
  `TextView.setDurationText` and `DurationUnit` in `utilities/resources/`.
- `ChannelSettings.createFromNotificationSettings` resolves the channel override
  first and falls back to the guild-level mute state.
- `ChannelGroupDMSettingsViewModel.ViewState.Valid.currentIconUrl`:
  when `hasUnsavedIconChange`, `editedIconUrl ?: IconUtils.getDefaultForGroupDM(id)`;
  otherwise `IconUtils.getForChannel(channel)`. `isDefaultPhoto` is
  `editedIconUrl == null || editedIconUrl == getDefaultForGroupDM(id)`.
  On save, the icon sent is `editedIconUrl.orEmpty()` when there is an unsaved
  change (empty string clears it) and `originalIconUrl` otherwise, so a rename
  alone never drops the icon.
- Decoded resource ids used by this wave (source:
  `discord-jadx/app/src/main/res/values/public.xml`; all verified present in the
  target): `2131623959`=`menu_private_channel_sidebar`,
  `2131364402`=`menu_private_channel_sidebar_pinned_messages`,
  `2131364400`=`…_copy_id`, `2131364401`=`…_customize_gorup` (the typo is in the
  reference), `2131364399`=`…_close`, `2131892526`=`leave_group_dm`,
  `2131892533`=`leave_group_dm_title`, `2131892527`=`leave_group_dm_body`,
  `2131887681`=`close_dm`, `2131364555`=`notice_ok`,
  `2130969885`=`notice_theme_positive_red`,
  `2131623956`=`menu_main_group_settings`, `2131364393`=`menu_leave_group`,
  `2131887604`=`channel_settings`, `2131893141`=`mute_conversation`,
  `2131896591`=`unmute_conversation`, `2131889198`=`form_label_mobile_dm_muted`,
  `2131889199`=`form_label_mobile_dm_muted_until`, `2131893003`=`minimum_size`,
  `2131895235`=`saved_settings`, `2131165302`=`avatar_size_xxlarge`,
  `2131165293`=`avatar_size_large`, `2131623967`=`menu_text_channel_settings`,
  `2131364369`=`menu_channel_settings_delete`,
  `2131364370`=`menu_channel_settings_reset`, `2131888404`=`delete_thread`,
  `2131888378`=`delete_channel_body`, `2131889270`=`form_label_slowmode_off`,
  `2131893818`=`pins_disabled_nsfw`,
  `2131888366`=`default_failure_to_perform_action_message`,
  `2131896393`=`thread_settings`, `2131896394`=`thread_settings_updated`,
  `2131558963`=`widget_channel_topic`.
- Channel-icon drawables (all present in the target's `res/drawable/`):
  `ic_channel_text`, `ic_thread`, `ic_channel_announcements`, `ic_channel_forum`,
  `ic_channel_forum_post`, `ic_channel_text_nsfw`,
  `ic_channel_announcements_nsfw`, `ic_thread_locked`, `ic_voice_nsfw`,
  `ic_channel_forum_nsfw`, `ic_channel_text_locked`,
  `ic_channel_announcements_locked`, `ic_channel_forum_locked`, `ic_hub_24dp`,
  `ic_channel_voice_grey_header_24dp`, `ic_direct_message_header`.
  Note `NSFW.Thread` and `Locked.Thread` **share** `ic_thread_locked`, and
  `ForumPost` / `NSFW.ForumPost` / `Locked.ForumPost` all share
  `ic_channel_forum_post`.
- `GuildChannelIconType.Locked.*` is derived, not stored: the `@everyone`
  overwrite (`permissionOverwrites.first { it.id == guildId }`) denying
  `VIEW_CHANNEL` (1024).
- `WidgetChannelTopic` sets an **8dp** icon end margin for a group-DM avatar
  (`R.dimen.avatar_size_large`) and **4dp** for a drawable channel icon.
- The reference's `getChannelType` calls `isGuildForumPost(parentChannel = null)`,
  which can never be true. Reproduced verbatim rather than "fixed"; the branch is
  present and commented.


## Wave: `utilities/**` gap-fill (channel / media / streams / resources / kryo) — 2026-07-27

### Landed, but needs a caller

| New file | Needs |
| --- | --- |
| `utilities/channel/ChannelSelector.kt` | **Every open-coded `StoreStream.selectChannel(...)` should move to `ChannelSelector.getInstance().selectChannel(...)`.** Sites: `widgets/channels/list/WidgetChannelsList.kt:220,223`, `widgets/chat/list/adapter/WidgetChatListAdapterItemAutoModBlocked.kt:129`, `…ItemAutoModSystemMessageEmbed.kt:106,109,157`, `…ItemMessage.kt:405,602`, `…ItemSystemMessage.kt:156,329`, `widgets/guilds/list/WidgetGuildsListViewModel.kt:106`. Channel-mention taps in chat specifically want `findAndSet`, not `selectChannel` — that is the whole point of the class (it waits for the channel to hydrate and announces inaccessible links). |
| `utilities/channel/GuildChannelsInfo.kt` | No consumer yet. `WidgetChannelsList` should build its rows from `GuildChannelsInfo.get(guildId)` + `getSortedVisibleChannels`. |
| `utilities/channel/PermissionLabelOverrides.kt` | No consumer. Belongs to the channel-permission editor. |
| `utilities/channel/ChannelNotificationSettingsUtils.kt` | No consumer. Belongs to the channel notification-settings sheet. |
| `utilities/kryo/{LeastRecentlyAddedSetSerializer,SnowflakePartitionMapSerializer}.kt` | **Not registered.** `utilities/persister/PersisterConfig.init` must call `Persister.setKryoConfig { kryo -> kryo.register(LeastRecentlyAddedSet::class.java, LeastRecentlyAddedSetSerializer()); kryo.register(SnowflakePartitionMap::class.java, SnowflakePartitionMapSerializer()) }` before `Persister.init`. I ran out of session before making that edit. Until then both types round-trip through Kryo's field serializer. |
| `utilities/resources/**` | No consumer. `formatInviteExpireAfterString` belongs to the invite-settings sheet; `MillisecondsFormatter.formatDurationInMillis` to slow-mode / timeout pickers. |
| `utilities/accessibility/AccessibilityUtils.kt` | `setViewIsHeading` should be applied to every screen title; nothing calls it yet. |

### `StoreStream` edits made by this wave (small, deliberate)

- `create()` now calls `ChannelSelector.init(dispatcher, deck)`.
- New `@JvmStatic fun handleThreadCreateOrUpdate(thread: Channel)` — `ChannelSelector.findAndSetThread` needs to ingest a REST-fetched thread; there was no public seam.
- New private `playAppSound(sound)` helper guarded on `::applicationContext.isInitialized`, wired into `StoreMediaSettings.playSound` and `StoreVoiceChannelSelected.playSound`. **The two `playSound = {}` stand-ins are now live**; the guard exists because unit tests build the graph with no `Application` and `AppSoundManager` needs one.
- `StoreVoiceParticipants.streamContextForUser` is now wired to a lazily-built `StreamContextService`.

### Stand-ins collapsed

- **`StreamContext` is now the reference data class**, not an interface. `StreamContext.ApplicationStream` is deleted; `stream` is `com.discord.models.domain.ModelApplicationStream`. `StoreVoiceParticipants.applicationStream` was retyped accordingly. `views/user/UserAvatarPresenceView.ViewState.streamContext` now has a producer (`StreamContextService.getForUser`) — `UserAvatarPresenceViewController` should add it as its third source.
- **`utilities/PermissionOverwriteUtils.kt` now exists** (`allows`, `denies`, `deniesAccessTo`, `grantsAccessTo`, `computeAllowDenyUpdateBits`). Delete the local copies: `utilities/permissions/PermissionUtils.kt:507` (`internal fun PermissionOverwrite.denies`), `stores/StoreChannelMembers.kt:346`, and wire `widgets/stage/sheet/WidgetStageRaisedHandsBottomSheetViewModel.kt:117` to `computeAllowDenyUpdateBits`.

### Still missing, with evidence paths

Reference root for all of these: `discorddecompile/out/com/discord/utilities/`.

| Target path to create | Reference source | Note |
| --- | --- | --- |
| `utilities/debug/{DebugPrintBuilder,DebugPrintable,DebugPrintableCollection,DebugPrintableRef,HistoricalProcessExitReason}.kt` | `debug/*.kt` | 0 of 6 present. Self-contained. |
| `utilities/hubs/HubUtils.kt` | `hubs/HubUtilsKt.kt` | Blocked: needs `widgets/hubs/{WidgetHubAddServerConfirmationDialog,AddServerConfirmationArgs,DescriptionResult}`, none of which exist. |
| `utilities/directories/{DirectoryUtils,DirectoryUtilsKt}.kt` | `directories/*.kt` (219 + 114 lines) | 0 of 3. |
| `utilities/billing/**` | `billing/*.kt` (12 files) | Only `GooglePlayInAppSku.kt` present. Phase 13. |
| `utilities/channel/ChannelInviteLaunchUtils.kt` | `channel/ChannelInviteLaunchUtils.kt` | Blocked: `WidgetGuildInviteShare`, `GroupInviteFriendsSheet`, `WidgetGroupInviteFriends` all absent. |
| `utilities/channel/permissions/ChannelPermissionsAddMemberUtils.kt` | `channel/permissions/*.kt` | Not attempted. |
| `utilities/coroutines`, `system`, `threading`, `auth`, `contacts`, `device`, `press`, `receiver`, `sms`, `video`, `viewbinding`, `animations`, `experiments`, `guildautomod`, `bugreports`, `connectedaccounts` | same-named dirs | All still 0%. |

### `utilities/lazy/**` — do NOT rebuild from the reference without a plan

The reference shape and the target shape have **diverged deliberately** and rebuilding blindly breaks three files:

- `lazy/subscriptions/**` (7 reference classes) and `lazy/requester/GuildMemberRequestManager` were **relocated into `stores/StoreGuildSubscriptions.kt` and `stores/StoreGuildMemberRequester.kt`**. They are implemented. Recreating them under `utilities/lazy/` would be a competing implementation with duplicate `GuildSubscriptions` types.
- `lazy/memberlist/ChannelMemberList.kt` in the target is an **invented immutable data class** plus a `MemberListRow` sealed class carrying a raw `GuildMember`. The reference `ChannelMemberList` is a *mutable* `SparseMutableList`-backed structure with `add`/`insert`/`delete`/`sync`/`invalidate`/`rebuildMembers`/`setGroups`, and its `MemberListRow` is a presentation model (`Member(userId, name, isBot, tagText, tagVerified, presence, color, avatarUrl, showOwnerIndicator, premiumSince, isApplicationStreaming, userFlags)`, `RoleHeader(roleId, roleName, memberCount)`, `StatusHeader(rowId, type, memberCount)`).
  Converting requires rewriting `stores/StoreChannelMembers.kt` and `widgets/channels/memberlist/ChannelMemberListRows.kt` in the same change. **One owner, one commit.**
  Also missing: `MemberList` interface, `ThreadMemberList`, `ChannelMemberListKt`.

### `utilities/fcm/**`

Reference has 6 classes; the target has `fcm/NotificationClient.kt` and has relocated `NotificationData`/`NotificationRenderer`/`NotificationActionReceiver` into `utilities/notifications/`. `NotificationCache` and `NotificationType` have no counterpart. Decide whether `utilities/notifications` or `utilities/fcm` is canonical before adding more.

## `widgets/guild_role_subscriptions` — wave in progress (session 1 of 2)

Reconstruction of the 38 missing classes was started but **not finished**; see the
handoff report for the exact file list. Everything below is a request against a
file this wave does not own.

### Types this package needs that do not exist yet

| Missing type | Reference path | Consequence today | Owning wave |
| --- | --- | --- | --- |
| `utilities/billing/PremiumUtilsKt.getFormattedPriceUsd` | `com/discord/utilities/billing/PremiumUtilsKt` | `PriceTierViewHolder` and `GuildRoleSubscriptionTierViewHolder` call `widgets/stickers/StickerPackFormatting.formattedPriceUsd`, which is the same reconstruction in the wrong package. Converge when the real file lands | billing |
| `utilities/view/recycler/PaddedItemDecorator` | same | `WidgetPriceTierPickerBottomSheet` carries a private `InsetDividerItemDecoration`; the benefits list will need the same | Wave 4 |
| `utilities/handoff/MobileWebHandoff` + `utilities/intent/StaticChannelRoutes` | same | `RoleSubscriptionsLinkingUtil.performHandoff` is a **var seam** defaulting to a plain `UriHandler.handle(..., forceExternal = true)` — the web page opens **unauthenticated**. `StaticChannelRoutes.ROLE_SUBSCRIPTIONS.route` is inlined as `"role-subscriptions"` (verified from the reference enum) and the `/channels/{guildId}/{route}` regex is local | intent/handoff |
| `widgets/servers/creator_monetization_eligibility/CreatorMonetizationCountryAllowlist` | same | `GuildRoleSubscriptionsFeatureFlag` takes `isUserInEligibleCountry: () -> Boolean = { false }`. A **non-monetisable guild's owner therefore never sees the settings entry point** | server-settings |
| `widgets/servers/guild_role_subscription/**` (`WidgetServerSettingsGuildRoleSubscriptionTierList`, `WidgetServerSettingsEmptyGuildRoleSubscriptions`, `model/PayoutStatusMedia`) | same | `WidgetGuildRoleSubscriptionPlanSetup.onSetupFinished` / `.onSetupAbandoned` are `var` seams defaulting to no-ops, so the wizard just closes. `GuildRoleSubscriptionUtilsKt` deliberately **omits** `PayoutStatus.getStatusMedia()` — its mapping is transcribed in the handoff report | server-settings |
| `widgets/channels/WidgetChannelPickerBottomSheet`, `widgets/channels/WidgetCreateChannel` | same | Needed by `WidgetGuildRoleSubscriptionTierBenefit` to pick the channel a `ChannelBenefit` points at. Not yet written | channels |
| `widgets/chat/input/emoji/EmojiPickerNavigator` | same | Needed by the same screen. `WidgetEmojiPickerSheet` exists but is hardwired to `EmojiPickerContextType.Chat` and returns a `MessageReactionEmoji`, not an `Emoji` — it cannot be reused as-is | chat-input |

### `StoreSubscriptions` is still not wired into `StoreStream`

`WidgetGuildRoleSubscriptionEntryPointViewModel` takes it as
`StoreSubscriptions? = null`. With it null, `hasUserActiveSubscription` stays
`null` and the `role_subscription_listing_upsell_page_viewed_v3` analytics event
never fires. No UI is affected. One-line fix once `StoreStream` constructs it.

### `AnalyticsTracker.guildRoleSubscriptionUpsellOpened` is missing

The reference builds the generated schema object
`TrackRoleSubscriptionListingUpsellPageViewedV3`
(`analyticsSchemaTypeName = "role_subscription_listing_upsell_page_viewed_v3"`,
fields `roleSubscriptionGroupListingId`, `roleSubscriptionListingIds`,
`isPremiumMember`, plus `location` on `TrackBase`). The generated analytics
classes are not reconstructed, so the entry-point view model sends the same event
name and keys through `AnalyticsTracker.tracker.track(event, properties)`.
Replace with a real `AnalyticsTracker` method when the generated events land.

### `UriHandler.handleUriSpecialCase` is the hook this package needs

`utilities/uri/UriHandler.kt:210` is a `private fun … = false` whose own comment
says it is for "the role-subscription handoff". Route it to
`RoleSubscriptionsLinkingUtil.tryPerformHandoff(context, uri)` — that function is
now implemented and is otherwise unreferenced.

### `StoreGuildRoleSubscriptions.isGuildEligibleForTrials` can be closed

`GuildRoleSubscriptionsFeatureFlag` now exists, so the store's
`isGuildEligibleForTrials: (Long) -> Boolean = { false }` stopgap can become
`GuildRoleSubscriptionsFeatureFlag.INSTANCE::isGuildEligibleForGuildRoleSubscriptionTrials`.
Until that is done **free trials are never fetched**, so the free-trial row in
the benefits editor will always render as disabled.

## Server-settings emoji wave — cross-file requests

Built this wave: `widgets/servers/WidgetServerSettingsEmojis.kt` and
`widgets/servers/WidgetServerSettingsEmojisEdit.kt`. Both are complete against the
reference except for the upload path below.

### Needed from the shell owner (`widgets/servers/WidgetServerSettings.kt`)

Add the emoji row's destination and mark it implemented:

```kotlin
bindDestination(R.id.server_settings_emojis_option) {
    WidgetServerSettingsEmojis.launch(it.context, guildId)
}
```
plus `R.id.server_settings_emojis_option` in `IMPLEMENTED_OPTIONS`. The existing
`bindOption(..., permissions.canManageEmojisAndStickers)` gate already matches the
reference and needs no change.

### Blocking gap — emoji upload

`WidgetServerSettingsEmojis`'s "Upload Emoji" button is **disabled**, not wired. It
needs all three of:

- `com.discord.utilities.images.MGImages.prepareImageUpload(uri, mimeType, fragmentManager, fragment, action, previewType)`
- `com.discord.utilities.images.MGImages.requestDataUrl(context, uri, mimeType, action)`
- `com.discord.dialogs.ImageUploadDialog` (+ its `PreviewType.EMOJI`)

none of which exist in the target. `RestAPIParams.PostGuildEmoji(name, image)` and
`RestAPIInterface.postGuildEmoji` **do** exist and are currently unused. When the
three land, override `onImageChosen`/`onImageCropped` on the screen and re-enable the
button; the reference's default alias is `"emoji_" + (emojiCount + 1)`.

This is the same missing dependency that degrades
`widgets/servers/WidgetServerSettingsOverview.kt` (guild icon/banner/splash upload).

### Confirmed absent from the reference — do not build it

**126.21 has no server-settings *stickers* screen.** `discorddecompile/out/com/discord/widgets/servers/`
contains no `*Sticker*` class; sticker UI is only `widgets/chat/input/sticker/` (picker)
and `widgets/stickers/` (sheets/upsells). The audit inventory was right, and any future
"server settings → stickers" row would be an invention.

### Deliberate divergences recorded in code

- **Emoji image sizing.** The reference passes `R.dimen.emoji_size` — the raw *resource
  id* `2131165401` — as Fresco's pixel width and height, so it never downscales the
  emoji bitmap. The reconstruction resolves the dimen to real pixels
  (`resources.getDimensionPixelSize`). Visually identical, far less bitmap memory.
  This is a reference bug, reproduced-with-fix rather than bug-for-bug; flag it if the
  project would rather keep it verbatim.
- **`Item.EmojiSection.key`** is `"EMOJI_SECTION_$titleId"`. The reference's key for
  that item is not recoverable — Vineflower elides the data class body and JADX does not
  show the field initialiser. Distinct-per-section is what `DiffUtil` requires, so this
  is safe, but it is not *verified* parity.

### Reference behaviours reproduced that look like bugs but are not

- **Alias sanitising, not validation.** `WidgetServerSettingsEmojisEdit` strips every
  character outside `[^A-Za-z0-9_]` and then pads with `_` until the name is at least 2
  characters. An alias typed as `!` saves as `__`. Regex transcribed verbatim from the
  reference (`WidgetServerSettingsEmojisEdit.java:42`).
- **Each section is newest-first.** Static and animated lists are reversed
  *independently* before the section headers are inserted.
- **Unavailable emoji stay listed**, greyed (`setGrayscale(true)`) and at alpha 100/255,
  rather than being filtered out.
- **The empty state is a list row** (`EmojiEmpty`), not a flipper page, so the upload
  header stays on screen for a guild with no emoji.

### Resource ids decoded this wave (via `/tmp/resid.sh`)

Decoding is slow; several agents need the same values. `sh /tmp/resid.sh <int>...`
resolves against `discord-jadx/app/src/main/res/values/public.xml`. All of the
following were additionally confirmed to exist in the **target** `app/src/main/res`.

| int | type | name |
| --- | --- | --- |
| 2131559292 | layout | `widget_server_settings_emojis` |
| 2131559293 | layout | `widget_server_settings_emojis_edit` |
| 2131559294 | layout | `widget_server_settings_emojis_empty` |
| 2131559295 | layout | `widget_server_settings_emojis_header` |
| 2131559296 | layout | `widget_server_settings_emojis_item` |
| 2131559297 | layout | `widget_server_settings_emojis_section` |
| 2131365180 | id | `server_settings_emojis_avatar` |
| 2131365182 | id | `server_settings_emojis_edit_alias` |
| 2131365184 | id | `server_settings_emojis_name` |
| 2131365185 | id | `server_settings_emojis_nickname` |
| 2131365187 | id | `server_settings_emojis_overflow` |
| 2131365188 | id | `server_settings_emojis_username` |
| 2131365189 | id | `server_settings_emojis_username_avatar` |
| 2131366251 | id | `widget_server_settings_emojis_section` |
| 2131366252 | id | `widget_server_settings_emojis_upload` |
| 2131366253 | id | `widget_server_settings_emojis_upload_description` |
| 2131364406 | id | `menu_server_settings_emoji_delete` |
| 2131623962 | menu | `menu_server_settings_emojis_edit` |
| 2131888749 | string | `emoji` |
| 2131888797 | string | `emoji_section` (positional `%1$s - %2$s`, **not** a `{name}` string) |
| 2131888798 | string | `emoji_slots_available` (`{count} available`) |
| 2131891173 | string | `guild_settings_emoji_upload_to_server_message` (`{count}`, `{maxSize}`) |
| 2131895233 | string | `save_media_success_mobile` |
| 2131755089 | plurals | `emoji_slots_available_count` (`{count} slot`/`{count} slots`) |
| 2131165300 | dimen | `avatar_size_standard` |
| 2131165401 | dimen | `emoji_size` (32dp) |

Previously decoded and still useful (from the moderation/security/vanity wave):
`2131896462`=`tooltip_community_feature_disabled`, `2131889122`=`form_help_verification_level`,
`2131889094`=`form_help_explicit_content_filter`, `2131897046`=`verification_level_medium_criteria`,
`2131897042`=`verification_level_high_criteria`, `2131893064`=`moderation`,
`2131895592`=`server_settings_updated`, `2131895387`=`security`,
`2131890729`=`guild_security_req_mfa_turn_off`, `2131890730`=`guild_security_req_mfa_turn_on`,
`2131890724`=`guild_security_req_mfa_body`, `2131888462`=`disable`, `2131888824`=`enable`,
`2131100382`=`status_red_500`, `2131100304`=`status_green_600`,
`2131897026`=`vanity_url_help_extended`, `2131897023`=`vanity_url_header_uses`,
`2131897021`=`vanity_url`, `2131755449`=`vanity_url_header_uses_uses`,
`2131365274`=`server_settings_vanity_input`, `2131888486`=`discard_changes`,
`2131888489`=`discard_changes_description`, `2131893499`=`okay`.

## SECURITY H-1 — fragment injection via exported AppActivity$Main — PARTIALLY FIXED 2026-07-27

Full finding in `security-review.md` (H-1). Summary: `AppScreenRouter.createScreenIntent`
targeted the **exported** `AppActivity$Main`, and `AppActivity.consumeIntent` honoured
`EXTRA_SCREEN` unconditionally, instantiating the named fragment with the sending intent's own
extras as arguments. Any installed app could therefore display any fragment with arguments it
chose. `WidgetIncomingShare` made it exploitable into auth-token exfiltration.

FIXED (coordinator, this session):
- `AppScreenRouter.createScreenIntent` now targets the non-exported base `AppActivity`. The
  manifest already declared it for exactly this purpose; the router simply never used it.
- `AppActivity.consumeIntent` refuses to read `EXTRA_SCREEN` when running as `Main`.

STILL OPEN — do not assume H-1 is closed until these are done:

1. **`WidgetIncomingShare` still does not validate URI schemes.** It reads
   `arguments.getStringArrayList("uris")` and passes each to `contentResolver.openInputStream`
   as our UID. The `EXTRA_SCREEN` route into it is closed, but `AppActionActivity` is exported
   for `ACTION_SEND` / `ACTION_SEND_MULTIPLE`, so **verify whether a malicious `ACTION_SEND`
   carrying a `file:///data/data/<pkg>/...` URI reaches the same code**. If it does, this is the
   same exfiltration with the same user interaction and the H-1 fix does not stop it.
   `ShareContractParser.isSafeAttachment` reportedly exists — confirm it is actually applied on
   the SEND path, do not assume.
2. **`widgets/bugreports/WidgetBugReport.kt:183`** does
   `resolver.openInputStream(uri)?.use { it.readBytes() }`. The reviewer flagged but did not
   verify where that URI comes from. If it is `arguments`, it is a second exfiltration path.
3. **Defence in depth not yet added:** replace the `Class` extra with a string checked against an
   allowlist of routable screens. Java serialization of a `Class` records only the FQN, so an
   attacker declaring a same-named stub gets our class resolved. Non-exported targeting makes
   this unreachable today, but the allowlist is what makes it safe against a future re-export.
4. `AppLog`'s redaction key list does not include `phone` / `phoneNumber` — contact-sync surface
   unreviewed.

Nobody should mark H-1 resolved without closing item 1.

## Test-suite repair wave (unit tests) — findings for other owners

### Real main-source bugs found by tests, already FIXED here
- **`api/guildscheduledevent/GuildScheduledEventTypeAdapters.kt`** — both adapters called
  `reader.nextInt()` *inside* the `firstOrNull { it.apiValue == reader.nextInt() }` predicate, so
  the reader advanced once per enum constant. Any `status`/`entity_type` whose value was not the
  first constant's ran off the end of the value and into the next field name
  (`Expected an int but was NAME at $.entity_type`). Every `GuildScheduledEvent` with
  `entity_type != 0` failed to parse — i.e. **every real scheduled event**. Now reads once.
- **`widgets/chat/input/emoji/EmojiPickerViewModel.filter`** — case-folded with
  `Locale.getDefault()`. In Turkish `"GRIN".lowercase()` is `"grın"`, which matches no emoji name,
  so emoji search returned nothing on a Turkish-locale device. The reference folds emoji names with
  **`Locale.ROOT`** (`out/com/discord/stores/StoreEmoji.kt:207-217`). Now `Locale.ROOT`.
  **Audit the rest of the app for `lowercase(Locale.getDefault())`/`uppercase(...)` on identifiers,
  wire values, file extensions and URLs** — the unit-test JVM runs `-Duser.language=tr`, so this
  class of bug surfaces there but not on an en-US device.

### `StoreStream.initForTesting` now installs the chat wiring on the store thread
`installChatStoreWiring()` subscribes to `observeSelectedVoiceChannelId()`. `ObservationDeck.connect`
invokes the new observer's `onUpdate` **while holding the deck monitor**, and that reaches
`dispatcher.schedule`. Production is unaffected (the real dispatcher is asynchronous), but any
synchronous test dispatcher deadlocks: the caller holds the deck monitor and waits for the store
thread, whose `onDispatchEnded` needs that same monitor. `initForTesting` therefore wraps the call
in `dispatcher.schedule { … }`. `initialize()` is unchanged. **Do not "simplify" this back.**

### Reference behaviour confirmed at source level — do not re-derive
- **`StoreV2.assertStoreThread` only logs.** `out/com/discord/stores/StoreV2.kt:13-21`:
  `Logger.e$default(AppLog.g, "markChanged() should be run from the Store Dispatcher", IllegalStateException(...))`.
  Two tests asserting a *thrown* `IllegalStateException` were stale and were rewritten to assert
  the log plus the snapshot boundary.
- **`AnchoredVoiceControlsView` camera content description is inverted in the reference.**
  `CAMERA_ON → 0x7f120520` = `camera_on` ("Turn On Camera"); otherwise `0x7f12051f` = `camera_off`.
  Ids read from `discord-jadx/app/src/main/res/values/public.xml:16221-16222`. The target already
  reproduces it; the test was corrected to match.
- **`EmbedType` ordinals are `ARTICLE 0, IMAGE 1, VIDEO 2, TWEET 3, LINK 4, HTML 5, FILE 6,
  GIFV 7, RICH 8, APPLICATION_NEWS 9, UNKNOWN 10`** — identical in target and reference.
  `getPreviewImage` maps `{0,1,3} → thumbnail`, `{2,7} → thumbnail ?: video`, `{4,5} → null`,
  else `image`. So an **`EmbedType.IMAGE` embed previews from `thumbnail`, never from `image`.**
- **Stage rich-presence party id `stage:<guild>:<channel>:<flagsHex>:<instance>` is hex.**
  `out/com/discord/utilities/presence/ActivityUtilsKt.kt` packs with `Long.toString(f, 16)` and
  unpacks with `Long.parseLong(s, 16)`; bits are `1 = speaker`, `2 = partnered`, `4 = verified`.
  (`0xa` is therefore *partnered only* — a test claiming it meant `verified|speaker` was wrong.)
- **`StoreApplicationCommands.handleQueryCommandsUpdate` appends built-ins.** The reference
  (`out/com/discord/stores/StoreApplicationCommands.kt:503-522`) adds
  `getBuiltInCommands().filter { it.name.startsWith(query, ignoreCase = true) }` after the server's
  matches, so `/sh` yields the server hit **and** `shrug`.

### Test-harness hazards that will bite the next person
- **`Persister.persistAll()` is process-wide.** It walks a static registry that outlives every test
  class — including `StoreStream`'s singleton graph — and writes each persister into whatever
  `filesDir` the *current* `Persister.init` set. A foreign persister sharing a cache key silently
  clobbers the file under test. `GuildStoreGraphTest` was failing exactly this way
  (`STORE_GUILD_SELECTED_V5` overwritten with `StoreStream`'s empty set). Stores that persist with
  `persist = true` need no `persistAll()` at all. `ReadyUserSocialStoresTest` still calls it twice
  and is order-dependent for the same reason.
- **`AppLog` cannot reach logcat under unit tests.** With `unitTests.isReturnDefaultValues = true`,
  `Log.getStackTraceString` returns `null` and `AppLog.println`'s non-null parameter throws NPE.
  Any test that raises `AppLog.minimumPriority` **must** also set `AppLog.sink` (that is what the
  field is for) and restore both in a `finally`.
- **`Kryo` reconstructs `LeastRecentlyAddedSet` through `CollectionSerializer`**, which uses the
  Kotlin-generated **no-arg** constructor — so the restored set's `maxSize` is the declared default
  `3`, not the `5` its owner passed. Verified by direct round-trip against `classes.jar`. Harmless
  today (nothing persists more than 3), but a 4th or 5th remembered guild would be evicted on
  restore. The reference has the identical shape and the identical latent bug.

## Server-settings members slice — RESEARCH COMPLETE, NO CODE WRITTEN (wound down)

Nothing was created for `servers/settings/members/**`, `WidgetServerSettingsEditMember`,
`WidgetServerSettingsEditMemberRolesAdapter`, or `WidgetServerSettingsTransferOwnership`.
The reference behaviour below was fully decoded and verified against the target; a fresh
agent can implement straight from it without re-reading the decompile.

### `WidgetPruneUsers` has no caller

`widgets/user/WidgetPruneUsers.kt` + `WidgetPruneUsersViewModel.kt` exist but nothing
invokes them. The reference's only caller is `WidgetServerSettingsMembers`, via the
overflow menu:

- menu res: `R.menu.menu_server_settings_members` when `canKick`, else `R.menu.menu_empty`
- item id: `R.id.menu_server_settings_members_prune`
- action: `WidgetPruneUsers.create(guildId, parentFragmentManager)` — signature confirmed
  present in the target at `widgets/user/WidgetPruneUsers.kt:143`.

`canKick` here is `guild.isOwner(meUser.id) || PermissionUtils.canAndIsElevated(
Permission.KICK_MEMBERS, guildPermissions, meUser.mfaEnabled, guild.mfaLevel)`.

### Everything the members slice needs already exists in the target

Verified present, no new store/REST/util work required:

- `StoreGuilds.observeComputed(guildId): Observable<Map<Long, GuildMember>>` (`:344`)
- `StoreGuilds.observeRoles(guildId): Observable<Map<Long, GuildRole>>` (`:356`)
- `StoreUser.observeUsers(userIds, fetchMissing = false)` (`:122`)
- `StoreGatewayConnection.requestGuildMembers(guildId, query, userIds, limit)` (`:350`),
  reached via `StoreStream.getGatewayConnection()` — note the reference calls this
  `getGatewaySocket()`, the target's accessor is `getGatewayConnection()`
- `RoleUtils.ROLE_COMPARATOR`, `getHighestRole(map, GuildMember?)`,
  `getHighestRole(map, Collection<Long>?)`, `rankIsHigher`, `rankEquals`,
  `getRoleColor(role, context, defaultColor)`, `getOpaqueColor(role, defaultColor)`
- `PermissionUtils.canManageGuildMembers(isOwner, userMfaEnabled, guildMfaLevel, permissions)`
- `ManageUserContext.from(guild, actingUser, targetUser, actingUserRoles, userRoles,
  myPermissions, guildRoles)` with `canManage()`, `canKick`, `canBan`, `canChangeNickname`,
  `canManageRoles`, `canDisableCommunication`
- `GuildMember.getNickOrUsername(member, user)` (companion, `models/member/GuildMember.kt:92`)
- `views/user/SettingsMemberView.configure(user: User, guildMember: GuildMember?)`
- `widgets/roles/RolesListView.updateView(roles, roleDefaultColor, guildId)`
- REST: `changeGuildMember`, `changeGuildNickname`, `kickGuildMember`, `banGuildMember`,
  `disableGuildCommunication`, `transferGuildOwnership`

**No member-enumeration gap.** The reference does not paginate a REST member list: it
renders whatever `StoreGuilds.observeComputed(guildId)` already holds and pushes the search
term to the gateway (`requestGuildMembers`, debounced 750 ms) so the store fills in. The
target supports that exactly. A "loading more" state is therefore not part of this screen in
126.21 — do not invent one.

## From the app-settings slice (WidgetSettingsPrivacy / WidgetSettingsVoice)

Both screens are built and wired to real stores. These are the seams they had to
leave open because the dependency does not exist or is not published.

| Missing dependency | Consequence today | Where it is worked around |
| --- | --- | --- |
| **`StoreStream.getMediaEngine()`** — `StoreMediaEngine` exists but is not published (RTC transport is Phase 11) | `WidgetSettingsVoice` **hides** three things rather than showing them dead: the whole **OpenSL ES** section (needs `getOpenSLESConfig()`/`setOpenSLESConfig()`), the **"Test microphone"** button and the **live input meter** (both need `getLocalVoiceStatus()`). The manual sensitivity slider still works because it only writes. | `WidgetSettingsVoice.configureOpenSlEs()` and `configureVoiceSensitivity()`, both commented |
| **`com.discord.utilities.voice.PerceptualVolumeUtils`** | Ported verbatim into `WidgetSettingsVoice`'s companion as `amplitudeToPerceptual`/`perceptualToAmplitude`. `StoreAudioManagerV2` already carries a **private copy of the inverse** (`perceptualToAmplitude`, ~line 176). **Two copies now — converge into `utilities/voice`.** | `WidgetSettingsVoice.Companion` |
| **`com.discord.utilities.voice.DiscordOverlayService`** (only the abstract `com.discord.overlay.OverlayService` exists) | The overlay toggle persists `setIsMobileOverlayEnabled` and runs the full `MANAGE_OVERLAY_PERMISSION` flow correctly, but cannot start/stop the overlay immediately — it appears on the next voice connection instead. | `WidgetSettingsVoice.onOverlayToggled()`, an empty documented method |
| **`RestAPI.HarvestState`** | Declared locally as `SettingsPrivacyViewModel.HarvestState` (`NeverRequested` / `LastRequested`). Semantics match the reference: a failed/404 status read means *never requested*, never an error — a user who has never asked gets a 404 and must still be able to ask. **Move to `utilities/rest` when that type lands.** | `WidgetSettingsPrivacy.kt` |
| **`StoreStream.getContactSync()`** — `StoreContactSync` exists, no accessor | `toggleContactSync` cannot call `backgroundUploadContacts()`, so the first upload waits for the next scheduled sync; `deleteContactSync` cannot call `clearDismissStates()`. Dropping the connection itself works. | `WidgetSettingsPrivacy.toggleContactSync` / `deleteContactSync` |
| **`WidgetSettingsAccount`** | The "basic service" row's `[Disabling or Deleting your account](onClick)` link opens the help article instead of the account screen. Swap for `WidgetSettingsAccount.launch(context, true)` when it lands. | `WidgetSettingsPrivacy.openAccountSettings()` |

### Entry points ready for `WidgetSettings.kt`
`WidgetSettingsPrivacy.launch(context)` and `WidgetSettingsVoice.launch(context)`
(both via `SettingsNavigation`). Privacy is complete; Voice is partial as above.

### `CheckedSetting.isChecked` — settled convention for all agents
`views/CheckedSetting` is a **Kotlin** class implementing the **Java** interface
`android.widget.Checkable`. It declares `override fun isChecked()` and
`override fun setChecked(checked: Boolean)`, plus its own
`setChecked(checked, notifyListener)`.

Both call styles are already in committed code:
`isChecked()` at `widgets/guilds/invite/ViewInviteSettingsSheet.kt:109` and
`widgets/guilds/invite/WidgetGuildInviteShare.kt:235`; the property form
`x.isChecked = v` at `widgets/servers/WidgetServerNotifications.kt:192,201,206`
and `widgets/servers/NotificationHighlightsSettingsView.kt:39`.

**Which form resolves was NOT settled — it needs one Gradle run.** Kotlin only
synthesises properties for Java-declared accessors, and here the accessor is a
Kotlin override of a Java method. Until someone compiles:

- **Prefer the explicit `isChecked()` / `setChecked(v, notify)` forms** — they are
  valid under either reading.
- **Behavioural fact that is easy to miss:** the single-argument
  `setChecked(Boolean)` (and therefore the property-write form, if it resolves)
  delegates to `setChecked(v, notifyListener = false)`. **Writing it does NOT fire
  the checked listener.** Any code relying on a write to trigger the listener is
  wrong.

## Audit-log slice — handoff from the `widgets/servers/auditlog` agent (2026-07-27)

The four `widgets/servers/auditlog/**` classes are complete:
`WidgetServerSettingsAuditLog`, `…Adapter`, `…Filter`, `…FilterAdapter`.
Nothing in that package is a placeholder. Requests to other owners follow.

### `WidgetServerSettings.kt` — one line still needed (owner: server-settings shell)

`WidgetServerSettings.kt` already calls
`WidgetServerSettingsAuditLog.launch(it.context, guildId, model.guild.name)`,
but `R.id.server_settings_option_audit_log` is **not** in its `IMPLEMENTED_OPTIONS`
set, so the row stays hidden and the screen is unreachable. Add that id and the
audit log becomes live — same shape as the blocked-users dead-end recorded above.

### Deliberate divergence: the audit-log screen re-checks its own permission

`WidgetServerSettingsAuditLog.Model` gained an `Unauthorized` state gated on
`PermissionUtils.can(Permission.VIEW_AUDIT_LOG, guildPermissions)`. The reference
has no such check — it trusts the shell never to offer the row — which leaves a
stale log on screen if the permission is revoked while it is open. Owners and
administrators need no separate test: `computeNonThreadPermissions` already
resolves both to `Permission.ALL`.

### Reference bug carried forward, NOT fixed: `StoreAuditLog.recordDeletedTarget`

`stores/StoreAuditLog.kt:315` reads `if (typeChange?.oldValue == 0)`. The channel
`type` old-value is parsed as a **Long** by `ModelAuditLogEntry.ValueFactory`
(`JsonToken.NUMBER -> reader.nextLongOrNull()`), so the comparison is
`Long == Int` and is **always false**. Consequence: a deleted *text* channel is
recorded without its leading `#`, and every later entry referencing that channel
renders the bare name.

This slice did **not** touch it — `stores/` is outside the audit-log agent's
ownership and the bug is already reproduced-and-commented in place. The fix is
one line (`(typeChange?.oldValue as? Long)?.toInt() == 0`), and it belongs to the
`stores/` owner. Note the *screen* does not share the bug: 
`WidgetServerSettingsAuditLog.Model.resolveChannelName` casts to `Long` and calls
`isTextyChannelType(type.toInt())`, so a channel deleted while the screen is open
is prefixed correctly; only the store's scraped `deletedTargets` fallback is wrong.

### `utilities/auditlogs` is complete — do not re-derive its string tables

`AuditLogUtils` (511 lines) and `AuditLogChangeUtils` (981 lines) already hold
every action name, per-target change-string table, permission-name mapping and
the numbered change summary. The widgets do no string mapping of their own.
Anyone touching audit-log text should edit those two files, not the widgets.

## Guild moderation wave — audit log + members (2026-07-27)

### Package layout divergence from the task brief — follow the reference

The brief assumed `widgets/servers/{auditlog,members,roles,bans,integrations,invites,emoji,
stickers,widget,moderation}`. The reference's actual layout is:

| feature | reference package |
| --- | --- |
| audit log | `widgets/servers/auditlog/**` (matches) |
| members | `widgets/servers/settings/members/**` |
| invites | `widgets/servers/settings/invites/**` + `widgets/servers/WidgetServerSettingsInstantInvitesActions` |
| roles | `widgets/servers/WidgetServerSettings{Roles,RolesAdapter,EditRole}` (root) + `widgets/servers/role_members/**` |
| bans, moderation, integrations, emoji | `widgets/servers/` root, no subpackage |
| stickers | **no server-settings sticker screen exists in 126.21** |
| widget (server widget) | **no such screen in 126.21**; `WidgetServerSettingsSecurity` and `…VanityUrl` occupy that area |

The reconstruction follows the reference.

### `utilities/auditlogs/` now exists — two consumers, one seam closed

`AuditLogUtils` and `AuditLogChangeUtils` were created. **`StoreAuditLog`'s two injected
transform lambdas no longer default to identity** — they now default to
`AuditLogChangeUtils::transformPermissionChange` / `::transformPermissionOverride`, so
permission bitfield changes render as per-permission allow/deny/reset lists. The
`GuildAdminStoresTest` construction site passes only `(dispatcher, deck, { null })`, so it
picks up the real transforms too; if that test asserted the identity behaviour it needs
updating.

### `AuditLogChangeUtils` is complete in structure, partial in one function

Everything is faithful except `getChangeTextWithParams`, whose reference body is ~2000
decompiled lines of duplicated try/catch. The reconstruction implements the same contract
(one `when` over the change key, wrapped in one catch that drops the line and logs) and
covers every branch that changes the *value* representation: channel `type`, permission
`allow`/`deny`/`reset`, role `$add`/`$remove`, channel-referencing guild keys, `owner_id`,
`communication_disabled_until`, `prune_delete_days`, `afk_timeout`, `bitrate`, `color`,
and the generic `{oldValue}`/`{newValue}` default. Two reference branches are **not**
reproduced because their dependencies do not exist in the target:

| Missing dependency | Consequence | Where |
| --- | --- | --- |
| `MediaSinkWantsManager.VideoQualityMode` + `getStringForVideoQualityMode` | a `video_quality_mode` change renders the raw number instead of "Auto"/"720p" | `AuditLogChangeUtils.getChangeTextWithParams` |
| `utilities/resources/DurationUtilsKt.formatInviteExpireAfterString` | an invite `max_age` change renders raw seconds instead of "7 days" | same |
| `utilities/font/FontUtils.getThemedFont` + `utilities/spans/TypefaceSpanCompat` wiring | the `01 — ` line numbers in the change block are coloured but not monospaced, so multi-line blocks do not align | `AuditLogChangeUtils.getChangeSummary` |

### Missing screens that leave a reconstructed control hidden

- **`WidgetServerSettingsTransferOwnership`** does not exist. `WidgetServerSettingsEditMember`
  computes `canTransferOwnership` correctly but keeps the row `GONE`, because the reference's
  confirmation (type the server name) lives entirely in that screen and transferring without it
  would be a destructive one-tap action. The verified/partnered guard
  (`transfer_ownership_protected_guild`) is documented in place. **Un-hide the row when that
  screen lands.**

### `StoreStream` does not expose two stores this wave needed

`getEmojiGuild()` and `getGuildRoleMemberCounts()` have **no accessor on `StoreStream`**
(`StoreEmojiGuild` is constructed at `StoreStream.kt:243` and registered, but never exposed;
`StoreGuildRoleMemberCounts` is not constructed at all). `WidgetServerSettingsEmojis` and
`widgets/servers/role_members/**` cannot be built until they are.

### Reference bug confirmed at bytecode level and reproduced

`StoreAuditLog.recordDeletedTarget` compares a deleted channel's `type` (parsed as `Long`)
against a boxed `Integer.valueOf(0)`, so **a deleted text channel never renders its leading
`#`**. Left reproduced, commented in the store, and the audit-log UI renders it as-is.

### Vineflower mis-decompiled a loop that would have silently broken permissions

`AuditLogChangeUtils.calculatePermissionChange` prints as
`var2 = 0; while (true) { if (var2 <= 63) break; … }` — an inverted condition that would make
the loop body dead and every permission change render as nothing. `javap -c` shows
`102: iload_2; 103: bipush 63; 105: if_icmpgt 174`, i.e. a real `for (i in 0..63)`.
**Do not trust Vineflower's `$VF: Unable to resugar Kotlin loop` blocks.**

### Divergence taken deliberately in `WidgetServerSettingsMembers`

The reference seeds its role filter `if (!isRecreated)`. The subject is a fragment field, so
after a configuration change it is a fresh instance with no value, and `combineLatest` never
emits — the rotated screen stays blank. Seeded unconditionally instead; the restored spinner
overwrites it with the user's saved selection immediately after its adapter is attached.
Documented in code.

### Resource-id decoding harness

`/tmp/resmap.json` (decimal id → `[type, name]`, built from the JADX project's
`res/values/public.xml`) plus `/tmp/look2.py` rewrite every `21xxxxxxxx` literal in a
Vineflower file into `R.<type>.<name>` and flag names absent from the target's
`res/values/*.xml`. `/tmp/check.py` validates `R.*.*` references in target Kotlin files
against the real resources. Rebuild them with the snippets in this wave's handoff if `/tmp`
is cleared. Note: `look2.py` only scans `values/*.xml`, so `/*MISSING*/` on a
drawable/layout/id/menu is a false positive, and `check.py` reports `R.style.AppTheme_PopupMenu`
as missing although `AppTheme.PopupMenu` exists (dot-to-underscore mangling).

## Home + tabs wave — cross-file requests

### `PanelsChildGestureRegionObserver` now exists — register your scrollable children with it

`com/discord/panels/PanelsChildGestureRegionObserver.kt` is new and is the reference's
`b.a.o.b`. Contract (all main-thread):

```kotlin
PanelsChildGestureRegionObserver.Provider.get()      // process-wide, WeakReference-held
  .register(view)      // start tracking; keyed by view.id, duplicate ids are logged + ignored
  .unregister(view)    // stop tracking and republish immediately
  .addGestureRegionsUpdateListener(l) / .removeGestureRegionsUpdateListener(l)
  .getCurrentGestureRegions(): List<Rect>
fun interface GestureRegionsListener { fun onGestureRegionsUpdate(gestureRegions: List<Rect>) }
```

`WidgetHome` will consume it (`onResume` add + replay, `onPause` remove, forward to
`OverlappingPanelsLayout.setChildGestureRegions`). **The three registration sites are owned by
other agents and are still missing** — until they land, the panels still steal every horizontal
drag inside these views:

| File | Views to register | Reference |
| --- | --- | --- |
| `widgets/directories/WidgetDirectoryChannel.kt` | `tabs` (TabLayout) and `view_pager` (ViewPager2), registered/unregistered from a `bindGestureObservers(isVisible: Boolean)` that `WidgetHome.maybeToggleChannel` calls | `WidgetDirectoryChannel.java:194-212` |
| `widgets/chat/input/WidgetChatInput.kt` | the attachment strip | `WidgetChatInput.java:753,886` |
| `widgets/voice/call/WidgetVoiceCallInline.kt` | the inline call tile | `WidgetVoiceCallInline.java:301,329` |

`WidgetDirectoryChannel` additionally needs a `bindGestureObservers(isVisible: Boolean)` entry
point, because the reference hosts the directory **inside `WidgetHome`'s centre panel**
(`R.id.widget_home_panel_directory`, which the recovered `widget_home_panel_center.xml` already
declares) rather than on a separate screen.

### Stores the home cluster needs but `StoreStream` does not publish

`StoreGuildWelcomeScreens`, `StoreLurking` and `StoreGuildsNsfw` all exist as classes and are
never constructed or exposed by `StoreStream`. The home cluster injects them where it can and
falls back to inert defaults. Wire them up and delete these seams:

| Seam | Location | Inert default today |
| --- | --- | --- |
| `WidgetHomeViewModel.storeLurking` | companion `@JvmField var` | never lurking → guild welcome sheet never shown |
| `WidgetHomeViewModel.storeGuildsNsfw` | companion `@JvmField var` | gate always "agreed" → NSFW gate never shown |
| `WidgetHomeViewModel(storeGuildWelcomeScreens = …)` | constructor param, default `null` | welcome screen never fetched |
| `WidgetHomeModel.guildNsfwGateAgreed` | companion `@JvmField var` | `isNsfwUnConsented` always false |
| `WidgetHomePanelNsfw.onGuildNsfwGateAllowed` / `.onGuildNsfwGateDenied` | companion `@JvmField var` | accepting the gate is not persisted |
| `WidgetHomePanelLoading.onHomeShown` | companion `@JvmField var` | `StoreAnalytics.appUiViewed(WidgetHome)` never raised — `StoreAnalytics` exists but `StoreStream` neither builds nor publishes it |
| `WidgetHomeViewModel.hasUserSeenChannelOnboarding` | companion `@JvmField var`, default `true` | `ChannelOnboardingManager` unreconstructed |
| `WidgetHomeViewModel.guildRoleSubscriptionUpsellCanShow` / `…Shown` | companion `@JvmField var`, default `false` | `GuildRoleSubscriptionOnboardingUpsellManager` unreconstructed |

`WidgetHomeModel.observeInitialized()` uses `StoreChannels.observeInitializedForAuthedUser()`
because the reference's `StoreStream.isInitializedObservable` has no companion accessor here —
`StoreStream.observeInitialized()` exists but is an **instance** method with no reachable
instance. Adding a `@JvmStatic` companion accessor would let this be exact.

### Misplaced code to relocate (home/tabs wave)

| Item | Current location | Correct location |
| --- | --- | --- |
| `Toolbar.positionUnreadCountView(view)` and `Toolbar.getNavigationIconView()` | to be inlined in `widgets/home/WidgetHomeHeaderManager` | `utilities/toolbar/ToolbarUtilsKt` (reference package; does not exist in the target) |
| `isSmallScreen(resources)` = `widthPixels <= 360 * density` | to be inlined in `widgets/home/WidgetHomeHeaderManager` | `utilities/device/DeviceUtils` (reference `DeviceUtils.isSmallScreen`, verified in the Vineflower decompile) |
| forum-redesign experiment lookup (`2022-03_android_forum_channel_redesign`, bucket 1) | private in `StoreStream`, needed again by `WidgetHome.maybeToggleChannel` | `widgets/forums/ForumUtils.canAccessRedesignedForumChannels` |

### `AnalyticsTracker.surveyViewed(surveyId)` is missing

`WidgetMainSurveyDialog.onSurveyViewed` is a seam because the reference's
`AnalyticsTracker.surveyViewed` has no counterpart in this project's `AnalyticsTracker`. Add the
event and point the seam at it rather than reusing `openModal`.

### `WidgetGlobalSearch` is now reachable only through `WidgetGlobalSearchDialog`

`WidgetTabsHost` no longer hosts a search *tab*: the reference's `widget_tabs_host.xml` has no
`widget_tabs_host_search` container (confirmed from `WidgetTabsHostBinding`'s nine fields) and
its search button opens `WidgetGlobalSearchDialog`. The container still exists in the recovered
target layout and is now hidden in `onViewBound`. It can be deleted from the layout by whoever
owns `res/layout/widget_tabs_host.xml`.

## Guild scheduled events — wave handoff (session 1 of 2, INCOMPLETE)

`widgets/guildscheduledevent/**` plus a new `utilities/guildscheduledevent/`.
**The package does not compile as left.** Two classes are referenced but not yet
written — `WidgetPreviewGuildScheduledEvent` and
`WidgetGuildScheduledEventLocationSelect`. A fresh agent must build them first;
their required public surface is listed below.

### Seams this wave CLOSED for other owners

- **`ChatListRowNavigation.showGuildScheduledEventDetails` can be wired.**
  The entry point is
  `WidgetGuildScheduledEventDetailsBottomSheet.showForGuild(fragmentManager, eventId)`
  (`@JvmStatic`). Hub/directory rows use
  `showForDirectory(fragmentManager, eventId, guildId, channelId)`; a link that
  resolves to an invisible event uses `handleInvalidEvent(context)`.
  **Blocked until `WidgetPreviewGuildScheduledEvent` exists**, because the sheet
  references it from the "start event" button.
- **Scheduled-event chat rows should read the store, not the payload.**
  `StoreGuildScheduledEvents.isMeRsvpedToEvent(guildId, eventId)` /
  `observeIsMeRsvpedToEvent(...)` give the real RSVP;
  `StoreVoiceChannelSelected.getSelectedVoiceChannelId()` compared against
  `event.channelId` gives the real `isConnected`. Both are used exactly that way
  in `GuildScheduledEventDetailsViewModel` and `GuildScheduledEventListViewModel`.
  `WidgetChatListAdapterItemGuildScheduledEventInvite.kt` still passes
  `isConnected = false`.
- **`WidgetHubEventViewHolder` can drop its workaround.**
  `GuildScheduledEventItemView.configureInDirectoryEventList(eventData, cardClickListener,
  secondaryButtonOnClickListener, primaryButtonOnClickListener, shareButtonOnClickListener)`
  now exists and does what that holder hand-rolls. `widgets/hubs` owns that file.

### Cross-package requests from this wave

| Request | Why | Owner |
| --- | --- | --- |
| Move `GuildScheduledEventUser` from `com.discord.stores` into `com.discord.widgets.guildscheduledevent` | The reference declares it there. A `typealias` at `widgets/guildscheduledevent/GuildScheduledEventUser.kt` restores the name today; delete it when the store file can move the declaration | stores wave |
| Expose an `ApiGuild -> models.guild.Guild` conversion (`StoreGuilds.merge` is private) | Four call sites now hand-build a 3-field `Guild`: `EmojiSheetViewModel.toModelGuild`, `WidgetHubEventViewHolder`, `GuildScheduledEventItemView.configureInDirectoryEventList`, `GuildScheduledEventDetailsViewModel.observeStores` | stores wave |
| `StoreDirectories` | `GuildScheduledEventDetailsViewModel.observeStores` cannot resolve a **directory-sourced** event: the reference reads `StoreDirectories.getGuildScheduledEventsForChannel(channelId)`. The branch falls back to the event store, so a hub event for an unjoined guild will not open. One-expression fix when the store lands | directories wave |
| `DirectoryUtils.maybeJoinAndGoToGuild(fragment, event, isInGuild, guildId, channelId, shouldRsvp, onRsvp)` | The details sheet's directory primary button currently just selects the guild instead of joining it | directories wave |
| `ChannelInviteLaunchUtils.inviteToChannel(fragment, channel, source, eventId)` + `WidgetGuildInviteShare` | `GuildScheduledEventUtilities.launchInvite` copies the event URL in **both** branches. The `canShare` branch is kept as a seam so only its body changes | guilds/invite wave |
| `WidgetMobileReports.launchGuildScheduledEventReport(context, guildId, eventId)` | "Report Event" in `WidgetGuildScheduledEventDetailsExtrasBottomSheet` is deliberately inert; only `MobileReportsViewModel` and its sub-views exist | reports wave |
| `Tags` / `TagsBuilder` (`utilities/textprocessing`) | `GuildScheduledEventDescriptionParser.getRenderContext` resolves mention names from guild **nicknames only**. The reference seeds its own scan with an empty `TagsBuilder().build()`, so this is near-parity, not a regression | textprocessing wave |
| Add `isEmpty()` and `Companion.create(entityType, location)` to `api/guildscheduledevent/GuildScheduledEventEntityMetadata` | The reference has both (`b()` = `location == null`; `a(type, location)` returns a location-carrying metadata only for `EXTERNAL`). `GuildScheduledEventModel.toUpdateRequestBody` inlines the first; the not-yet-written `GuildScheduledEventSettingsViewModel` needs the second | api wave |
| Apply the `kotlin-parcelize` plugin | `GuildScheduledEventDetailsArgs` implements `Parcelable` by hand (matching the reference's generated wire format) purely because the plugin is absent | build wave |
| `WidgetGuildProfileSheet` | `WidgetGuildScheduledEventUpsellBottomSheet` (not yet written) needs `show(fm, true, guildId, 0L, true)` for its non-COMMUNITY branch | guilds/profile wave |

### Facts confirmed at bytecode level — do not re-derive

- `EventDetailsRsvpUsersFetchState` ordinals are **`LOADING(0), ERROR(1), EMPTY(2),
  SUCCESS(3)`** — Vineflower prints them alphabetically, which is wrong.
- `GuildScheduledEventSettingsViewModel.DateError` ordinals are
  **`INVALID_VIEW_STATE(0), START_DATE_IN_PAST(1), END_DATE_IN_PAST(2),
  END_DATE_BEFORE_START_DATE(3)`** — also not alphabetical.
- `GuildScheduledEventDetailsSource` is `Directory(0), Guild(1)`;
  `EventDetailsSection` is `EVENT_INFO(0), RSVP_LIST(1)`.
- `GuildScheduledEventUtilities` permission masks:
  `canCreateAnyEvent` / `canCreateExternalEvent` / guild-wide `canStartEvent` use
  `MANAGE_EVENTS` (`8589934592`); the channel floor is `MANAGE_EVENTS|VIEW_CHANNEL`
  (`8589935616`); stage channels additionally require `START_STAGE_EVENT`
  (`8610906128` = `MANAGE_EVENTS|MANAGE_CHANNELS|MUTE_MEMBERS|MOVE_MEMBERS`); voice
  channels require `START_VOICE_EVENT` (`8590984192` =
  `MANAGE_EVENTS|VIEW_CHANNEL|CONNECT`). `canShareEvent` is
  `CREATE_INSTANT_INVITE` (`1`) plus `canEveryoneRole(VIEW_CHANNEL)` plus
  `!member.pending`. All already exist as named constants in
  `api/permission/Permission.kt`.
  `ChannelUtils.D` = `isStageVoiceChannel`, `ChannelUtils.J` = `isVoiceChannel`
  (read from their `checkNotNullParameter` label strings).
- Decoded drawables (`public.xml`): `2131231002` = `bg_guild_scheduled_event_list_item_connected_dark`,
  `…003` = `…connected_primary`, `…004` = `…connected_secondary`, `…005` = `…dark`,
  `…006` = `…floating`, `…011` = `…primary`, `…012` = `…secondary`.
  **The pre-existing target `configureInChatList` used `primary`/`connected_primary`;
  the reference uses `secondary`/`connected_secondary` there, and
  `primary`/`connected_primary` only in the event list. Corrected in this wave.**
- Decoded strings/layouts: `2131890088` = `guild_event_invite_embed_join_server_button_label`,
  `2131890086` = `guild_event_interested_notification`,
  `2131890081` = `guild_event_end_prompt_title`, `2131890079` = `guild_event_end_prompt_cancel`,
  `2131890080` = `guild_event_end_prompt_confirm`, `2131755157` = `guild_events_plural_number`,
  `2130968914` = `colorBackgroundTertiary`, `2131165458` = `guild_scheduled_event_icon_corner_radius`,
  `2131165300` = `avatar_size_standard`, `2131558539` = `guild_scheduled_event_item_view`,
  `2131558540` = `guild_scheduled_event_list_item`,
  `2131558541` = `guild_scheduled_event_rsvp_user_list_adapter_item`,
  `2131559151` = `widget_guild_scheduled_event_list_bottom_sheet`,
  `2131559086` = `widget_end_stage_bottom_sheet` (the end-**event** sheet reuses the
  end-**stage** layout), `2131559154` = `widget_guild_scheduled_event_upsell_bottom_sheet`,
  `2131559153` = `widget_guild_scheduled_event_settings`.
  Settings date-error toasts `2131890112`, `2131890111`, `2131890076` are **not yet decoded**.
- `GuildScheduledEventDescriptionParserKt` and `GuildScheduledEventExternalLocationParserKt`
  are **R8-emptied in the reference** (`javap` shows zero members). Do not create stub
  files for them; they are excluded from the class count.
- The reference's obfuscated `b.a.t.b.b.e` is `SimpleMarkdownRules`; its surviving
  instance method `d()` is `createTextRule()` (used by the external-location parser),
  and `a(boolean, boolean)` is `createSimpleMarkdownRules`.
- `DiscordParser.createParser$default(false, true, false, false, false, 24, null)`
  means `allowMaskedLinks=false, supportEntityMentions=true, renderBlockQuotes=false`.
- **Reference behaviour reproduced deliberately:** the RSVP row's avatar fallback
  omits the discriminator (`IconUtils.getForUser$default(..., 28, null)`), so an
  avatar-less attendee always renders default avatar 0.
- **JADX-broken, inferred not read:** the "Delete Event" row visibility in
  `WidgetGuildScheduledEventDetailsExtrasBottomSheet` decompiles to a degenerate
  `false ? 0 : 8`. Implemented as `canStartEvent && !isLive`. Confirm with `javap -c`.
- **Vineflower cannot decompile** `GuildScheduledEventDetailsViewModel`,
  `GuildScheduledEventListViewModel`, `WidgetGuildScheduledEventDetailsBottomSheet`,
  `WidgetGuildScheduledEventDetailsExtrasBottomSheet` and
  `WidgetGuildScheduledEventLocationSelectViewModel` (`KotlinWriter` NPE). Use JADX
  plus `javap -c` for those five; both `observeStores` lambdas were recovered from
  bytecode and are documented in the files that were built.

---

## androidTest — build config requests

Filed 2026-07-27 by the instrumentation-layer agent. **The agent did not edit
`app/build.gradle.kts` or `gradle/libs.versions.toml`** — these are the exact changes the
coordinator needs to apply before the tests below can be written and run.

### What already exists (measured, not assumed)

| Item | State |
| --- | --- |
| `app/src/androidTest/` source set | **Exists**, 10 Kotlin files under `java/com/discord/**` |
| `testInstrumentationRunner` | **Declared** — `app/build.gradle.kts:20`, `androidx.test.runner.AndroidJUnitRunner` |
| `androidTestImplementation(libs.androidx.junit)` | Present — `androidx.test.ext:junit:1.1.5` |
| `androidTestImplementation(libs.androidx.espresso.core)` | Present — `androidx.test.espresso:espresso-core:3.5.1` |
| `androidx.test:core` (`ApplicationProvider`, `ActivityScenario`) | Present transitively via `ext:junit:1.1.5` |
| `androidx.test:runner` (the artifact the runner class lives in) | **Not declared explicitly.** Connected tests have passed before, so it resolves transitively, but it must not stay implicit |
| `androidx.test:rules` (`GrantPermissionRule`, `ServiceTestRule`) | **Absent** |
| `androidx.test.uiautomator` | Absent (not needed) |
| `androidTest` manifest / custom runner | None, and none needed |

So the answer to "does androidTest infrastructure exist at all" is **yes** — this layer is not
a greenfield. What is missing is `rules`, an explicit `runner`, and the small set of extras the
prioritised tests need.

### Paste-ready: `gradle/libs.versions.toml`

Under `[versions]`:

```toml
androidxTestCore = "1.5.0"
androidxTestRunner = "1.5.2"
androidxTestRules = "1.5.0"
androidxTestOrchestrator = "1.4.2"
workTesting = "2.9.1"       # must equal the `work` version already declared (2.9.1)
```

Under `[libraries]`:

```toml
androidx-test-core        = { group = "androidx.test", name = "core",        version.ref = "androidxTestCore" }
androidx-test-runner      = { group = "androidx.test", name = "runner",      version.ref = "androidxTestRunner" }
androidx-test-rules       = { group = "androidx.test", name = "rules",       version.ref = "androidxTestRules" }
androidx-test-orchestrator= { group = "androidx.test", name = "orchestrator",version.ref = "androidxTestOrchestrator" }
androidx-work-testing     = { group = "androidx.work", name = "work-testing",version.ref = "workTesting" }
```

### Paste-ready: `app/build.gradle.kts`

Replace the two existing `androidTestImplementation` lines with:

```kotlin
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestUtil(libs.androidx.test.orchestrator)
```

And inside `android { testOptions { … } }`, alongside the existing `unitTests` block:

```kotlin
    testOptions {
        unitTests.isReturnDefaultValues = true

        // Each instrumentation test gets a fresh process. The store graph is a set of
        // process-global singletons (StoreStream, ApplicationProvider, AppLog.minimumPriority,
        // NotificationActionReceiver.callbacks), so without this one test's dispatcher state
        // decides the next test's result and a startup-crash test cannot be honest.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"

        // Startup + native-load tests are slower than the 120 s default on WSA.
        animationsDisabled = true
    }
```

Why each entry:

- **`androidx.test:runner` explicit** — `testInstrumentationRunner` names
  `androidx.test.runner.AndroidJUnitRunner`; relying on it arriving through espresso's POM
  means an espresso bump can silently break every instrumentation task.
- **`androidx.test:rules`** — `GrantPermissionRule` is required for any test touching
  `CAMERA`/`RECORD_AUDIO`/`POST_NOTIFICATIONS`; without it the camera-capture and notification
  tests are auto-denied on API 23+ and assert nothing.
- **`androidx.work:work-testing`** — `NotificationActionReceiver.onReceive` calls
  `WorkManager.getInstance(context).enqueue(...)`
  (`utilities/notifications/NotificationActionReceiver.kt:66`). The app has **no**
  `Configuration.Provider` and no `androidx.startup` WorkManager initializer entry in the
  manifest, so `WorkManager.getInstance` throws `IllegalStateException("WorkManager is not
  initialized properly")` inside instrumentation. `WorkManagerTestInitHelper` is the only way
  to exercise the receiver's mark-read/reply branches, and it additionally lets the test read
  back the enqueued `Data` (channel id, message id, auth generation) instead of guessing.
- **Orchestrator** — see the comment in the block above; process-global store singletons make
  per-test isolation a correctness requirement here, not a nicety.

### Separate, non-blocking finding

`WorkManager` is never initialised in production either. `app/src/main/AndroidManifest.xml` has
no `androidx.startup.InitializationProvider` entry and `com.discord.app.App` does not implement
`Configuration.Provider`, yet `NotificationActionReceiver` calls `WorkManager.getInstance`.
Unless AGP's default manifest merge is supplying the provider from the `work-runtime` AAR (it
normally does — verify in the merged manifest), the mark-read and reply notification actions
throw on the first tap. This is an app defect, not a test defect; it belongs to whoever owns
Phase 9.

## `widgets/friends` wave — cross-file requests

Raised while rebuilding `com/discord/widgets/friends/**` against the Vineflower tree.

### Owned by other agents / waves

| Request | Why | Owner |
| --- | --- | --- |
| **`widgets/tabs/WidgetTabsHost.kt` has two now-unused imports** (`WidgetFriendsAdd`, `WidgetFriendsAddById`, lines 33-34). Warnings only, not errors | `installCrossScreenNavigation` no longer wires the friends list; `WidgetFriendsList` and `WidgetFriendsAddById` own their navigation and captcha | tabs owner |
| **`WidgetTabsHost` should call `WidgetFriendsList.onTabSelected()`** | The reference registers an `OnTabSelectedListener` (`widgets/tabs/OnTabSelectedListener`, **not reconstructed**) and the host keeps every tab's fragment resumed, toggling container visibility only. `WidgetFriendsList` currently observes `StoreTabsNavigation.observeSelectedTab()` itself as a stand-in. Remove that when the listener interface lands | tabs owner |
| **`FriendUserUi` / `FriendRelationship` / `FriendRelationshipUi` / `BlockedUser*` in `widgets/friends/FriendsUi.kt` belong to the blocked-users screen** | The friends list no longer uses them — it works on real `User`/`Presence`/`FriendSuggestion` models. They exist only for `widgets/settings/account/WidgetSettingsBlockedUsers` and the `StoreStream` block that feeds it. Move them into `widgets/settings/account/` | settings owner |
| **`widgets/channels/WidgetGroupInviteFriends` + `widgets/channels/invite/GroupInviteFriendsSheet` (and `GroupInviteFriendsSheetFeatureFlag`) do not exist** | `menu_friends`'s `menu_friends_start_group` item is therefore forced `isVisible = false` in `WidgetFriendsList.configureToolbar`. Layouts `widget_group_invite_friends.xml` and `group_invite_friends_bottom_sheet.xml` are already recovered. Flip the item visible when they land | channels wave |
| **`AnalyticsTracker` has no `nearbyConnected()`** | Reference `NearbyManager.buildClient` fires it when the Nearby permission is granted. Not called; add the tracker method and call it from `NearbyManager`'s permission callback | analytics owner |
| **`AnalyticsTracker.friendRequestFailed(query, reason)` is 2-arg; the reference is 4-arg** (`input, username, discriminator, reason`) | `WidgetFriendsAddById` passes `(source, "Invalid Username")` and loses the parsed username/discriminator | analytics owner |
| **`WidgetCaptchaBottomSheet.resolvableFragments` names only `WidgetTabsHost` / `WidgetIncomingShare`** | So `enqueue(...)` installs the fragment-result listener on the tab host, never on the screen that asked. `WidgetFriendsList.handleCaptchaError` therefore calls `show(...)` + `registerForResult(this, ...)` directly instead of `enqueue`, which is what the reference uses. Either widen `resolvableFragments` or make `enqueue` take the target fragment | captcha owner |
| **`com.discord.i18n.FormatUtils` has no positional overload** | Discord strings carry *named* `{token}`s but the reference renders several of them positionally (`b.a.k.b.b(context, resId, args)`). Three call sites now discover the token name by regex and fill it: `utilities/rest/RestAPIAbortMessages.formatPositional`, `WidgetFriendsListAdapter.formatCount`, and the `{name}`/`{discordTag}`/`{username}` sites in `WidgetFriendsAddById`. Fold a `format(template, vararg positional)` into `FormatUtils` and delete all three | i18n owner |

### New shared files created by this wave (previously missing)

- `restapi/RestAPIAbortCodes.kt` — full verbatim transcription of the reference object (95 codes).
- `utilities/rest/RestAPIAbortMessages.kt` — the abort-code → string map, `handleAbortCodeOrDefault`, and `ResponseResolver.getRelationshipResponse`. Both were listed as MISSING and are needed by `widgets/user/usersheet`, `widgets/contact_sync` and the chat send path too; those should switch to them instead of re-deriving.

### Nearby friends: Google Play Services dependency is NOT installed

`NearbyManager` is fully reconstructed (state machine, `u:<userId>` payload encoding, self-filtering,
de-duplication, `CONNECTION_ERROR = 99` / `PERMISSION_DENIED = 98`) **except** its transport. The
reference drives `com.google.android.gms.nearby.messages` from
`com.google.android.gms:play-services-nearby` (Discord 126.21 shipped 17.0.0); nothing under
`com.google.android.gms` is in `gradle/libs.versions.toml`, and that API's backend was retired in
December 2023.

The transport is expressed as `NearbyManager.Transport` with a single install point
(`NearbyManager.Transport.factory`, default `null`). With no transport the screen reports
`Disconnected(PERMISSION_DENIED)`, which renders exactly the reference's
`add_friend_nearby_stopped` copy plus its retry button — i.e. the same thing the reference shows
when the user declines the Nearby consent dialog. **Nothing else in the nearby screen is stubbed.**

To make the scan live: add `play-services-nearby` to the catalog and install a `Transport` that
wraps `MessagesClient.publish/subscribe`. Decision deferred because the dependency could not be
build-verified (no Gradle in this session).

## `widgets/settings/account/**` — research complete, NO code written (2026-07-27)

An agent verified every dependency for the seven account screens and then wound down before
creating any file. **Nothing was written; nothing is half-built.** The findings below are the
expensive part and should be reused rather than re-derived.

### Missing shared type: `com.discord.utilities.auth.AuthUtils`

The reference's `AuthUtils` supplies two things the account screens need, and neither exists as a
shared type in the target:

- `AuthUtils.createEmailValidationManager(TextInputLayout)` — already reconstructed, but as an
  `internal fun` in `com/discord/widgets/hubs/HubValidators.kt`, whose own KDoc says it belongs in
  `utilities/auth`. `WidgetSettingsAccountEmailEdit` is the **second** consumer, so this is now a
  concrete two-caller case for moving it.
- `AuthUtils.createDiscriminatorInputValidator(lengthErrorRes, zeroErrorRes)` — **not
  reconstructed at all.** Needed by `WidgetSettingsAccountUsernameEdit`. It is a
  `BasicTextInputValidator` pair over the 4-digit discriminator field: reject a value whose length
  is not 4 (`R.string.premium_change_discriminator_length_error`) and reject `0000`
  (`R.string.premium_change_discriminator_zero_error`).

### Missing: `com.discord.utilities.auth.GoogleSmartLockManager`

No counterpart in the target (`find -name "GoogleSmartLock*"` returns nothing). Three reference
call sites in this cluster, all of them credential-manager niceties rather than core behaviour:

| Reference call site | What is lost |
| --- | --- |
| `WidgetSettingsAccountEmailEdit.onViewBoundOrOnResume` sets `smartLockRepo.pendingIdChange = meUser.email` | Smart Lock is not told the account id is about to change |
| `WidgetSettingsAccountChangePassword` success then `googleSmartLockManager(context)?.updateAccountInfo(null, newPassword)` | The saved credential is not updated after a password change |
| `WidgetSettingsAccount.onActivityResult(requestCode == 4008)` then `GoogleSmartLockManager.Companion.handleResult(...)` | Smart Lock resolution result is dropped |

Each account screen can be built without it; degrade and comment in place.

### Dependencies CONFIRMED PRESENT (do not re-check)

- `com/discord/utilities/view/validators/{ValidationManager, Input, InputValidator,
  BasicTextInputValidator}` — full and faithful. `ValidationManager.setErrors(Map<FieldName,
  List<String>>): Collection<FieldName>` is what drives inline per-field API errors, and
  `Input.TextInputLayoutInput` already drops the error icon when the layout uses a password toggle.
- `com/discord/widgets/user/WidgetUserPasswordVerify.kt` — complete, including
  `registerForResult(fragment) { }`, `launchUpdateAccountSettings(context, launcher, email,
  username, discriminator, emailToken)` (all four optional), `launchUpdatePhoneNumber`, and
  `launchRemovePhoneNumber`. **This is the whole confirm-with-password flow; do not rebuild it.**
- `RestAPIParams.UserInfo` — field order `avatar, email, emailToken, password, newPassword,
  username, pushToken, code, discriminator, flags, dateOfBirth`, `pushProvider = "gcm"`,
  `toString()` already redacted.
- `RestAPIParams.{UserEmailConfirmCode, DisableAccount, ConnectedAccountNameOnly}`,
  `models/domain/ConnectionModels.ModelEmailChangeConfirm` (redacted `toString`).
- `StoreNotifications.getPushToken()`, `StoreAuthentication.onPasswordChanged(token)`,
  `StoreUserConnections.State` (`Loading` / `ConnectedAccounts`, implements `List<ConnectedAccount>`
  by delegation), `AnalyticsTracker.nameSubmitted(numWords, numChars)`.
- `views/LoadingButton` (`setText`, `setIsLoading`, `setIconVisibility`), `views/ScreenTitleView`,
  `utilities/rx/withDimmer(dimmer, delay = 300L)`.

### API-shape gotcha

`RestAPIInterface.updateConnectionName(connection, connectionId, body)` returns
`Observable<Response<ConnectedAccount>>` — **wrapped in `retrofit2.Response`**, unlike the
reference's bare `Observable<ConnectedAccount>`. `WidgetSettingsAccountContactsNameEdit` must
unwrap it. The reference also calls it on `RestAPI.apiSerializeNulls` (clearing the contact name
sends a literal null `name`), which this project does not publish — the same seam already
documented for `WidgetEnableGuildCommunication.serializeNullsApi` and
`WidgetServerSettingsOverview.serializeNullsApi`. **Three sites now want one
`RestAPI.apiSerializeNulls`.**

### Bytecode-confirmed enum ordinal (trap)

`WidgetDisableDeleteAccountDialog.Mode` — Vineflower prints the constants in the order
`DELETE, DISABLE`, which is **wrong**. `javap -p -c` on the dex2jar shows the static initialiser
assigning `DISABLE` first:

    DISABLE(0) = (header=disable_account, body=disable_account_body, confirm=disable)
    DELETE(1)  = (header=delete_account,  body=delete_account_body,  confirm=delete)

`onDisableClicked` branches on `ordinal`: `0 -> RestAPI.disableAccount`, `1 -> deleteAccount`.
Trusting the Vineflower source order would have **swapped account disable and account deletion** —
i.e. silently deleted accounts. The dialog is also `setCancelable(false)`.

### Decoded resource ids (all verified to exist in the target)

Layouts: `2131559323 widget_settings_account`, `2131559325
widget_settings_account_change_password`, `2131559326 widget_settings_account_contacts_name_edit`,
`2131559327 widget_settings_account_edit` (used by **UsernameEdit**), `2131559328
widget_settings_account_email_edit`, `2131559329 widget_settings_account_email_edit_confirm`,
`2131559070 widget_disable_delete_account_dialog`.

Ids: `2131363145 edit_account_username_wrap`, `2131363138 edit_account_discriminator_wrap`,
`2131363141 edit_account_email_wrap`, `2131362339 change_password_new_password_input`.

Strings: `2131896797 user_settings`, `2131896821 user_settings_account_change_username_title`,
`2131896816 user_settings_account_change_email_title`, `2131896823
user_settings_account_email_description`, `2131896804
user_settings_account_change_email_confirm_description`, `2131896806
user_settings_account_change_email_confirm_resend`, `2131896803
user_settings_account_change_email_confirm_code_sent`, `2131896808
user_settings_account_change_email_confirm_title`, `2131897012 username_required`, `2131893974
premium_change_discriminator_length_error`, `2131893977 premium_change_discriminator_zero_error`,
`2131894515 premium_upsell_tag_active_mobile`, `2131893660 password_required`, `2131893662
password_requirements_empty`, `2131896542 two_fa_token_required`, `2131895235 saved_settings`,
`2131887525 change_password`, `2131887915 contact_sync_update_name_title`, `2131886321
add_email_banner_title`, `2131886323 add_email_short`, `2131897074 verify_your_email`, `2131897059
verify`, `2131892987 mfa_sms_auth_current_phone`, `2131892988 mfa_sms_auth_sales_pitch`,
`2131892991 mfa_sms_disabled_no_email`, `2131892992 mfa_sms_disabled_partner`, `2131888373
delete_account_transfer_ownership`, `2131888374 delete_account_transfer_ownership_body`,
`2131896538 two_fa_remove`, `2131896927 user_settings_mfa_enable_code_body`, `2131896489
two_fa_auth_code`, `2131896930 user_settings_mfa_removed`, `2131896933 user_settings_my_account`,
`2131888463 disable_account`, `2131888464 disable_account_body`, `2131888462 disable`, `2131888370
delete_account`, `2131888371 delete_account_body`, `2131888369 delete`.

### Behavioural notes worth keeping

- `WidgetSettingsAccountEmailEdit` has **two modes** on one layout, chosen by the
  `extra_should_confirm_email` intent extra. Confirm mode uses the `settings_account_next` Button,
  shows `edit_account_email_description`, and routes to `WidgetSettingsAccountEmailEditConfirm`
  after `POST users/@me/email`. Non-confirm mode uses the save FAB and goes straight to
  `WidgetUserPasswordVerify`. Which mode is chosen comes from the
  `2022-01_email_change_confirmation` user experiment **ANDed with `meUser.isVerified`**, evaluated
  in `WidgetSettingsAccount`, not in the email screen.
- `WidgetSettingsAccountChangePassword.handleError` sets inline field errors first and only
  suppresses the toast when `ValidationManager.setErrors` handled *every* returned field. The
  target's `RestApiError` has **no per-error toast suppression**, so this needs the same
  `context = null` plus explicit `showToasts` workaround already used in
  `WidgetServerSettingsVanityUrl`.
- `WidgetDisableDeleteAccountDialog` maps API codes to fields: `50018` to the password field,
  `60008` to the MFA field, anything else to a toast. On success it calls
  `StoreStream.getAuthentication().logout()`.
- `WidgetSettingsAccount` rows for MFA enrolment (`WidgetEnableMFASteps`), backup codes
  (`WidgetSettingsAccountBackupCodes`) and SMS backup (`WidgetEnableSMSBackupDialog`) are **not**
  part of this cluster and none of those three classes exists in the target yet.
- `WidgetSettingsAccount.Model` is `(meUser, pendingMFAState, ownsAnyGuilds, contactSyncConnection)`,
  built from `observeMe` + `getMFA().observeState()` + `getGuilds().observeGuilds()` +
  `getUserConnections().observeConnectedAccounts()` + the
  `2021-04_contact_sync_android_main` user experiment. `ownsAnyGuilds` gates disable/delete behind
  a "transfer ownership first" notice.

## Convergence pass 2026-07-27 - what was collapsed, and what is left

Mechanical duplicate-collapse pass. No behaviour intended to change; every delta found is listed
below. Gradle was not run (project rule), so **nothing here is compile-verified**.

### Collapsed onto the canonical implementation

| Duplicate | Canonical | Notes |
| --- | --- | --- |
| `internal object EmbedMedia` (~190 lines, bottom of `WidgetChatListAdapterItemEmbed.kt`) | `utilities/embed/EmbedResourceUtils` + `FileType` | Callers repointed: `WidgetChatListAdapterItemEmbed`, `WidgetChatListAdapterItemAttachment`, `WidgetMedia.launchArgs`. Receiver-style members need `with(EmbedResourceUtils) { ... }` |
| `internal object ChatRowText` (`WidgetChatListAdapterItemMessage.kt`) | new `com/discord/i18n/Hooks.kt` - `object I18nHooks` + top-level `data class Hook` | 7 chat-row files repointed. `systemMessageUserJoin` / `systemMessageRoleSubscriptionPurchase` / `WELCOME_MESSAGES` moved to `utilities/message/MessageUtils` |
| `CharSequence.withClickableHook` (`WidgetChatListAdapterItemAutoModBlocked.kt`) | `com.discord.i18n.withClickableHook` | gained an optional `@ColorInt color` parameter, defaulting to `null` = previous behaviour |
| `ContactSyncLearnMore`'s hand-rolled `URLSpan` swap + `$`-stripping | same | now 4 lines delegating to `withClickableHook` |
| `getNamedFormatString` + `app/AppStrings.kt` | `com.discord.i18n.FormatUtils` / `Context.i18nFormat` | **file deleted**; its 2 call sites (`AppFatalCrash`, `AppFragment:291`) converted from positional to named args |
| 8 x `private const val DISCORD_EPOCH` plus ~10 open-coded shifts | `utilities/SnowflakeUtils` | see deltas |
| ~30 private `themedColor(...)` helpers | `ColorCompat.getThemedColor` (raw `.data` bodies) or `views/ThemedColor.resolve` (resource-aware bodies) | see "ThemedColor kept" |
| 12 x themed-drawable resolvers (`ThemedDrawable`, `Context.themedDrawableRes`, `View.themedDrawableRes`, 8 x `themedDrawable`) | `utilities/drawable/DrawableCompat.getThemedDrawableRes` | |
| 23 x private `dp` / `dpToPixels` / `Int.dp` | `com.discord.utilities.dimen.dpToPixels` | |
| 6 x `MEDIA_PROXY_SIZES` tables + `mediaProxySize` | `IconUtils.getMediaProxySize` | tables were byte-identical |
| `typealias ForumTagId` in `api/channel/Channel.kt` | `com.discord.primitives.ForumTagId` | |

### Behavioural deltas found - decisions taken

- **`views/ThemedColor.kt` was NOT deleted.** It is not equivalent to `ColorCompat.getThemedColor`:
  it resolves a theme attribute pointing at a **ColorStateList** through `ContextCompat.getColor`,
  where `ColorCompat` returns the raw `TypedValue.data` (a string-pool index) - which is what the
  reference does. CSL-valued theme attributes demonstrably exist in this project's own themes
  (`chat_input_button_tint_colors_*`, `chat_send_btn_*`, `button_color_selector`). The safer one was
  kept. The ~10 private helpers with the *resource-aware* body were pointed at `ThemedColor`; only
  the ~20 raw-`.data` helpers were pointed at `ColorCompat`. **A decision is needed on whether to
  accept the reference's behaviour and delete `ThemedColor`; that is ~26 call sites.**
- **`SnowflakeUtils.atPreviousMillisecond` reference bug**, re-confirmed at bytecode level this pass
  (`javap -c`: `lushr 22; lconst_1 lsub; ldc2_w 1420070400000 lsub; lshl 22`).
  `StoreReadStates.kt:390` now calls it instead of open-coding it - **bug included,
  byte-identical** - with an in-code comment naming the intended value. Not fixed.
- **`StoreMessageAck.previousSnowflake` is the CORRECT version of the same function**
  (`((s ushr 22) - 1).coerceAtLeast(0) shl 22`, no epoch subtraction). Deliberately NOT converged
  onto `atPreviousMillisecond`. Two semantics for "previous snowflake" now coexist, one
  buggy-for-parity and one correct.
- **`TimeUtils.parseSnowflake` uses `lshr`, `SnowflakeUtils.toTimestamp` uses `lushr` - in the
  reference too** (`javap -c` of both). They differ only for a negative id, which
  `atPreviousMillisecond` can produce. Only the epoch constant was shared; the function was left
  alone and the reason is documented in code.
- `StoreMessageAck.timestampToSnowflake` / `StoreReadStates.timestampToSnowflake` clamp to 0 where
  `SnowflakeUtils.fromTimestamp` does not. Expressed as
  `fromTimestamp(t.coerceAtLeast(DISCORD_EPOCH))`, provably identical on both branches.
- **`dpToPixels` rounding**: 6 sites rounded (`WidgetChangeLogSpecial`, `ViewReplySpline`,
  `WidgetNavigationHelp`, `EmbedMedia.dpToPixels`, and `SelectComponentBottomSheetItemViewHolder`
  which used `+ 0.5f`). The canonical **truncates**, as the reference does. Up to a 1px shift there.
- **`dpToPixels` density source**: every converged helper read a *view's*
  `resources.displayMetrics.density`; the canonical reads `Resources.getSystem()`. Identical unless
  a per-context density override is active.
- **`IconUtils.getMediaProxySize` is `first { it >= size }` and THROWS** above 4096.
  `SuggestedFriendView`, `GuildSubscriptionRoleImageUploadView` and `TextProcessingResources` used
  `firstOrNull { ... } ?: last()`, i.e. clamped. All inputs are view pixel sizes or dimen resources,
  so the throw is unreachable in practice - but it is a real difference.
- **`I18nSpans` had a real bug and it is fixed.** `applyInline` used
  `SpannableStringBuilder.replace()` over the whole match, and `SPAN_EXCLUSIVE_EXCLUSIVE` spans
  fully covered by a replacement are dropped - so a bolded masked link lost its `URLSpan`, and
  merely swapping the two passes would have lost the `StyleSpan` instead. It now deletes only the
  two delimiters, leaving the label's character range (and every span already anchored to it)
  intact, which makes the two passes order-independent. `MASKED_LINK` also now consumes an optional
  leading `$`, so Discord's hook marker no longer renders as a literal `$` anywhere - which is what
  `ContactSyncLearnMore` used to strip by hand.
- **`MessageUtils.getSystemMessageRoleSubscriptionPurchase` had a latent bug**: it built the
  `months` argument with the positional `getQuantityString(res, quantity, quantity)`, but the
  recovered plurals carry Discord's `{#}` / `{count}` token, not `%d`. It now uses
  `I18nHooks.plural`. It had **no callers** before this pass, so nothing regressed.

### Started and NOT finished

Nothing was left half-migrated: every symbol deleted had all its call sites updated, verified by
tree-wide grep. The items below were **not started**.

1. `textprocessing/spans/{QuoteSpan, BulletSpan, BlockBackgroundSpan, VerticalPaddingSpan,
   SpoilerSpan}` to `utilities/spans/`. **These are NOT identical implementations** - diffed this
   pass. The `utilities/spans` versions are the more faithful reconstructions: `BulletSpan` there
   has **two overloaded constructors** (not one with defaults; a two-argument call is ambiguous and
   must use named arguments) and rasterises through a shared `Path` at **1.2x radius** on a hardware
   canvas instead of `drawCircle`; `QuoteSpan` differs only cosmetically; `SpoilerSpan`'s third
   argument is now defaulted. Importers to repoint: `utilities/textprocessing/node/{BlockBackgroundNode,
   BlockQuoteNode, BulletListNode, HeaderNode, SpoilerNode}.kt`,
   `widgets/changelog/ChangeLogParser.kt`,
   `widgets/chat/list/adapter/WidgetChatListAdapterItemMessage.kt:46`.
   **Expect a visible bullet-rendering change.**
2. `computeShortName` - 2 copies (`widgets/directories/DirectoryUtils.kt:157`, used by
   `DirectoryEntryViewHolder.kt:27`; `computeGuildInitials` in `widgets/hubs/HubGuildText.kt:14`,
   used by `HubDomainViewHolder.kt:52`) to `com.discord.utilities.guilds.computeShortName`
   (`utilities/guilds/GuildUtils.kt:18`). Diff the three bodies first.
3. `app/AppMediaPicker.kt` + `MediaPickerRequest` to `com.discord.media_picker.MediaPicker` +
   `RequestType`. Only consumer is `app/AppFragment.kt:230-285`. `MediaPicker` exposes a `Provider`
   interface (`requireContext`, `getImageFile`, `startActivityForResult`, `onError`,
   `onMediaSelected`) so the fragment must implement it - **not a textual swap**. Verify the request
   codes match before assuming equivalence. Separately, `MediaPicker` itself has **two** crop entry
   points, `requestImageCrop` (:107) and `requestCrop` (:235) - diff and fold.
4. `OverlayAppDialog : FrameLayout` (`views/OverlayMenuBubbleDialog.kt:35`) to
   `com.discord.overlay.views.OverlayDialog` (exists now). Its private `VoiceUserAdapter` (:181) to
   `utilities/views/SimpleRecyclerAdapter`. This one **does** change behaviour: WindowManager layout
   params, the dim flag and drag-disable are currently absent.
5. `views/user/UserAvatarPresenceViewController` to `utilities/viewcontroller/RxViewController`
   (exists at `utilities/viewcontroller/RxViewController.kt:23`).
6. `MGImages.kt` `Log.w` error path to `AppToast` with `R.string.avatar_convert_failure_mobile`.
   Confirm the catch set against the decompile - the reference toasts on `IOException`,
   `IllegalStateException` **and** `NullPointerException`.
7. `AppFragment.drawUnderSystemBars`, `WidgetMedia.resizeToFit*`,
   `VoiceControlsSheetView.isGestureNavigationEnabled` to `utilities/display/DisplayUtils`
   (all present there: `drawUnderSystemBars`, `resizeToFit`, `resizeToFitScreen`,
   `isGestureNavigationEnabled`).
8. `stores/StoreMessagesMostRecent.kt:175-191` inline id comparison to
   `MessageUtils.compareMessages` / `SORT_BY_IDS_COMPARATOR`.
9. `utilities/messagesend/NonceGenerator.kt` is still a mislocated duplicate of
   `models/domain/NonceGenerator`. Only the epoch constant was converged. The two APIs differ
   (`() -> Long` vs `Clock`), and `StoreStream:424` + `MessageSendCoordinator:43` use the utilities
   one. The nonce arithmetic is provably identical between them.
10. `utilities/textprocessing/TextProcessingResources.dpToPixels` (both overloads) was
    **deliberately left**: its `systemDensity()` wraps `Resources.getSystem()` in
    `runCatching { ... }.getOrDefault(1f)`, which is load-bearing for the JVM unit tests
    (`unitTests.isReturnDefaultValues = true` makes `Resources.getSystem()` return null).
    `DimenUtils` reads it unguarded at class-init, so converging would break
    `DiscordMarkdownRulesTest` / `MessageTextRendererTest`. **Give `DimenUtils`' `DENSITY` the same
    guard first, then converge.**
11. `widgets/guilds/list/WidgetGuildsList.kt:220` `dpToPixels(dp): Float` returns a float; left
    alone, since converging would truncate.
12. Further themed-attribute inlines, found but outside the brief's list:
    `app/AppActivity.kt:535 themedResourceId`,
    `views/channelsidebar/GuildChannelSideBarActionsView.kt:73`,
    `views/channelsidebar/PrivateChannelSideBarActionsView.kt:42`,
    `views/guildboost/GuildBoostConfirmationView.kt:81`,
    `views/guildboost/GuildBoostProgressView.kt:49`,
    `widgets/guild_role_subscriptions/GuildSubscriptionRoleImageUploadView.kt themedResource`.
13. `views/user/SettingsMemberView.kt:157 internal fun mediaProxySize` was kept only because
    `widgets/channels/UserAkaView.kt:18` imports it across packages. Point `UserAkaView` at
    `IconUtils.getMediaProxySize`, then delete it.

### Facts confirmed this pass - do not re-derive

- `IconUtils.staticImageExtension` and `utilities/string/STATIC_IMAGE_EXTENSION` are the same
  predicate (SDK 28/29 gives `png`). Either is safe.
- All six `MEDIA_PROXY_SIZES` tables in the tree were byte-identical to `IconUtils`'.
- CSL-valued theme attributes really do exist in `res/values/styles.xml`; a theme attribute is not
  guaranteed to resolve to a literal colour.
- `unitTests.isReturnDefaultValues = true` is set in `app/build.gradle.kts:49`, so
  `Resources.getSystem()` is null under JVM unit tests.

## Wave: `widgets/guilds/invite` — cross-file requests (2026-07-27)

### One-line installs the owning agent must add

| Where | Line | Why |
| --- | --- | --- |
| App start-up (wherever `ChatListRowNavigation` is installed) | `ChatListRowNavigation.launchInviteShare = { ctx, fm, guildId, channelId, source -> WidgetGuildInviteShare.launch(ctx, fm, guildId, channelId, source = source) }` | **Closes the documented `launchInviteShare` seam.** `WidgetGuildInviteShare` now exists; the seam's `(Context, FragmentManager, Long, Long?, String) -> Unit` shape matches, but `source` must be passed **by name** because `launch` has defaulted parameters between `channelId` and `source`. |
| `widgets/chat/list/adapter/WidgetChatListAdapterItemInvite.kt` | delete the local `InviteEmbedStore` + `InviteState` stand-in | **`StoreInstantInvites` exists and is published on `StoreStream`** (`StoreStream.getInstantInvites()`), with the same `InviteState` sealed hierarchy (`Loading` / `LoadFailed` / `Invalid` / `Resolved(invite)`), `observeInvite(key)` and `fetchInviteIfNotLoaded(code, eventId, onError)`. Key with `ModelInvite.getInviteStoreKey(code, eventId)`. `entries/InviteEmbedResolver` merges into the same call. **Chat owner.** |

### `StoreInviteSettings` does not exist and cannot be created from `widgets/`

The reference's `com.discord.stores.StoreInviteSettings` (298 lines, decompiles cleanly at
`discorddecompile/out/com/discord/stores/StoreInviteSettings.kt`) has **no counterpart in this
project** and `StoreStream` has no `getInviteSettings()` accessor.

A complete, field-for-field stand-in now lives at
`widgets/guilds/invite/InviteSettingsSource.kt` — session `ModelInvite.Settings` subject, the
`InviteCode` Parcelable + pending-code subject, `getInvitableChannels(guildId)`,
`generateInvite`, `generateInviteDefaultChannel`, `getInviteGuildExperiment`,
`handleGuildSelected`, `LOCATION_DEEPLINK` / `LOCATION_JOIN`.

**Stores wave:** move the file to `stores/StoreInviteSettings.kt`, construct it in `StoreStream`,
call `handleGuildSelected(guildId)` from the guild-selection handler, add
`@JvmStatic fun getInviteSettings()`, and delete `InviteSettingsSource.Companion.instance`.

### Stores that exist as files but are **not wired into `StoreStream`**

`StoreLurking`, `StoreGuildWelcomeScreens`, `StoreGuildProfiles`, `StoreAnalytics` are all dead
code — `grep -n "lurking|welcomeScreens|guildProfiles|analytics" stores/StoreStream.kt` returns
nothing. Consequences already hit in this wave:

- every `StoreAnalytics`-backed invite analytic (`inviteCopied`, `inviteShareClicked`,
  `inviteSent(invite, message, location)`, `inviteResolved`) is a marked no-op in
  `widgets/guilds/invite/GuildInviteUiHelper.kt` and `WidgetGuildInviteShareViewModel`;
- the join/welcome cluster will need `StoreLurking` and `StoreGuildWelcomeScreens`.

### Code parked in `widgets/guilds/invite` that belongs elsewhere

| Symbol | Currently | Belongs in |
| --- | --- | --- |
| `Guild.toApiGuild()` (`WidgetInviteModel.kt`) | invite package | `utilities/guilds/GuildUtils.kt` — reference `GuildUtilsKt.createApiGuild` |
| `humanizeDurationRounded(Context, Long)` (`GuildInviteUiHelper.kt`) | invite package | `utilities/duration/DurationUtils.kt` |
| `formatInviteExpireAfterString`, `DurationUnit`, `Context.formatDuration` (`GuildInviteUiHelper.kt`) | invite package | `utilities/resources/` (package does not exist yet) — reference `DurationUtilsKt` + `DurationUnit` |
| `IntentUtils.performChooserSendIntent` | inlined in `GuildInviteUiHelper.shareLink` | `utilities/intent/IntentUtils` |

### Missing helpers this wave had to work around

- `UserNameFormatterKt.getSpannableForUserNameWithDiscrim(user, nick, context, 6 attrs)` does not
  exist. `InviteSuggestionsAdapter` uses `UserUtils.getUserNameWithDiscriminator(colour)` instead,
  so the discriminator's *font* and *size* runs are missing (colour is applied).
- `MemberVerificationUtils.maybeShowVerificationGate` + `WidgetGuildJoinRequest` do not exist:
  `InviteJoinHelper.completeJoin` logs and falls through instead of gating. **Restore the early
  return when the gate screen lands.**
- `WidgetCallPreviewFullscreen` / `WidgetVoiceBottomSheet` / `StageChannelJoinHelper` do not exist:
  a vocal invite selects the channel and stops rather than connecting.

### Corrections to `audit-widgets-guilds.md`

- **§3.4 is wrong about invite error codes.** The audit says "only error code `30001` is
  special-cased … other invite error codes fall through to the generic 'expired' copy" and rates it
  MAJOR. The **reference does exactly the same** — `WidgetInviteInfo.configureUIFailure` has a
  single `if (response.code == 30001)` branch and nothing else
  (`discorddecompile/out/.../WidgetInviteInfo.kt:449-500`). Not a gap. What *was* wrong and is now
  fixed: the guild limit is `if (me.isPremiumTier2) 200 else 100`, not a caller-supplied
  `guildLimit` parameter; the failure text size is `uikit_textsize_medium`, not `_large`; and the
  empty-guild image is `getThemedDrawableRes(R.attr.img_poop, R.drawable.img_poop_dark)`, not a
  hardcoded drawable.

## botuikit — cross-file requests

Recorded 2026-07-27 by the botuikit agent. Research-only wave; **no source files were
created or modified by this agent.**

### No ViewHolder edit is required — the hook already exists

The expected cross-file request (a chat-list ViewHolder that must call the component
renderer) is **already wired** and needs no change:

- `widgets/chat/list/adapter/WidgetChatListAdapter.kt:136` maps
  `ChatListEntry.BOT_UI_COMPONENT` to `WidgetChatListAdapterItemBotComponentRow(this)`.
  Reference: `WidgetChatListAdapter.kt:219` dispatches view type `36` to the same class.
- `widgets/chat/list/adapter/WidgetChatListAdapterItemBotComponentRow.kt:27` implements
  `com.discord.widgets.botuikit.views.ComponentActionListener` and overrides both
  `onButtonComponentClick` (line 50) and `onSelectComponentClick` (line 70).
- It renders through `adapter.botUiComponentProvider` (line 44).
- `widgets/chat/list/entries/ChatListEntry.kt:311` provides `createBotComponentEntries`.

Owners of `widgets/chat/**` should **not** remove or rename `botUiComponentProvider`
on `WidgetChatListAdapter`, the `BOT_UI_COMPONENT` entry type, or
`createBotComponentEntries`. These three are the entire contract between the chat list
and `widgets/botuikit/**`.

### Wire-int reference table (confirmed against `<clinit>` bytecode)

Any code outside `api/botuikit/**` that hardcodes a component type or button style must
use these values. See the botuikit handoff report for the full derivation.

- `ComponentType` ordinal == wire `type`: `UNKNOWN`=0, `ACTION_ROW`=1, `BUTTON`=2,
  `SELECT`=3, `TEXT`=4.
- `ButtonStyle` ordinal == wire value: `UNKNOWN`=0, `PRIMARY`=1, `SECONDARY`=2,
  `SUCCESS`=3, `DANGER`=4, `LINK`=5.
- `TextStyle` ordinal != wire value: ordinals are `SMALL`=0, `PARAGRAPH`=1, `UNKNOWN`=2;
  wire values are `SMALL`=1, `PARAGRAPH`=2, `UNKNOWN`=null.

### Missing helpers the botuikit slice still depends on

None blocking — but these reference classes have no target counterpart and are listed in
the botuikit handoff report as remaining work: `widgets/botuikit/ComponentStateMapper`,
`widgets/botuikit/ComponentExperiments` + `BotComponentExperiments`, and
`widgets/botuikit/ModalComponent` (+ its `bot_uikit_text_input` binding and the
`TEXT` / `TextComponent` render path, which is modal-only).

## Settings/servers wave — 2026-07-27 session

### `com.discord.utilities.stateful.StatefulViews` is mislocated

The reference's save-FAB / unsaved-changes helper is `com.discord.utilities.stateful.StatefulViews`,
used by **every** "edit then press save" screen (server overview, vanity URL, edit role, edit member,
edit integration, emoji edit, and all the account edit screens). `utilities/` was outside this wave's
ownership, so it was reconstructed faithfully at `widgets/settings/StatefulViews.kt`.
**Move it to `utilities/stateful/` and update the imports** — the class name, API and behaviour
already match the reference, including the quirks:

- `get(id, default)` records the server value and returns the pending edit if there is one, so a
  store emission mid-edit never clobbers typing;
- a *required* field whose edited value is `null` falls back to the default, while a field passed to
  `addOptionalFields` keeps the `null`;
- `hasAnythingChanged` compares a `SpannableStringBuilder` by its text, because an `EditText` hands
  back a spanned value the screen rendered as a plain `String`;
- the discard-confirmation Back handler is one-shot (`AtomicBoolean`), then re-issues Back.

Unit-tested (map half only) at `app/src/test/java/com/discord/widgets/settings/StatefulViewsTest.kt`.
The spannable comparison is deliberately not covered: `android.text.SpannableStringBuilder` is an
unimplemented android.jar stub under `unitTests.isReturnDefaultValues = true`.

### `RestAPI.apiSerializeNulls` — now needed by three call sites

`RestAPIBuilder.build(serializeNulls = true)` already supports it; nothing publishes the second
client. Until the store graph does, three screens carry their own seam and silently fail to *clear*
a nullable field:

| Seam | Effect while unpublished |
| --- | --- |
| `widgets/guildcommunicationdisabled/start/WidgetEnableGuildCommunication.serializeNullsApi` | clearing a timeout sends an empty body |
| `widgets/servers/WidgetServerSettingsOverview.serializeNullsApi` | clearing the AFK or system channel leaves the old one in place |
| `widgets/settings/account/WidgetSettingsAccountContactsNameEdit` (not built yet) | clearing the contacts name |

**Publish one `RestAPI.apiSerializeNulls` and delete all three seams.**

### `StoreInstantInvites.getInvites` cannot express "not fetched yet"

The reference returns a **nullable** map (`null` = not fetched); ours returns
`invitesSnapshot[guildId].orEmpty()`, and `observeInvites` is `distinctUntilChanged` on top. For a
guild with zero invites the post-fetch `markChanged()` therefore produces **no emission at all**, so
a store-driven screen would spin on "loading" forever; for a guild with invites it would flash
"No invites" first. `StoreBans.observeBans` already gets this right, which is what makes the
asymmetry easy to miss.

Because of this, `widgets/servers/settings/invites/WidgetServerSettingsInstantInvites` owns its own
`GET /guilds/{id}/invites` instead of reading the store. **Fix is one line** — drop the `.orEmpty()`
and let `observeInvites` emit a nullable map — after which that screen should be switched back onto
the store.

### `WidgetChannelSelector` / `WidgetGuildSelector` do not exist

`WidgetServerSettingsOverview`'s AFK-channel and system-channel pickers use a
`MaterialAlertDialogBuilder.setItems` chooser instead of the reference's searchable
`WidgetChannelSelector` screen (the same stopgap `WidgetChannelFollowSheet` already carries).
Replace both when that screen lands.

### Image upload is the single biggest blocker left in server settings

`MGImages.requestImageCrop` / `MGImages.prepareImageUpload` / `MGImages.requestDataUrl` and
`com.discord.dialogs.ImageUploadDialog` are all unreconstructed. Consequences, each commented in
code:

- `WidgetServerSettingsOverview` — guild icon is read-only, and the banner/splash sections are
  **hidden** rather than shown with a dead upload button;
- `WidgetServerSettingsEmojis` — "Upload Emoji" is disabled; `RestAPIParams.PostGuildEmoji` and
  `postGuildEmoji` are consequently unused.

### `NotificationStore` seam can now be deleted

`StoreStream.kt:926` assigns `NotificationStore.resolver = { notifications }` **and**
`StoreStream.getNotifications()` exists, so `widgets/settings/NotificationStore.kt` is redundant.
`WidgetSettingsNotifications` / `WidgetSettingsNotificationsOs` should call
`StoreStream.getNotifications()` directly, and both the seam and the `StoreStream` assignment should
go. Their toggles are live today — the old "visibly disabled" degradation no longer applies, and the
judgement call recorded earlier in this file is now closed.

### Reference bugs found this session — reproduced, not fixed

| Reference bug | Location | Decision |
| --- | --- | --- |
| `onConfigure` sets the disabled overlay from `isSyncing OR !isEnabled`, then `showSyncingUI(isSyncing)` immediately re-assigns the same view from `isSyncing` alone — so a merely **disabled** integration never dims | `widgets/servers/WidgetServerSettingsIntegrationsListItem.kt` | reproduced bug-for-bug, commented in place |
| The invite-expiry countdown computes days as `millis / 86_400_000 % 24`, so an invite longer than 24 days wraps | `widgets/servers/settings/invites/WidgetServerSettingsInstantInvites.kt` | reproduced; unreachable via Discord's UI (longest offered max age is 7 days) |

### Decoded resource ids — do not re-derive

Vineflower prints raw resource ints. There was a decoder at `/tmp/resid.sh`; it is three lines —
`printf '0x%08x'` the int, then grep that id out of
`C:/Users/manti/StudioProjects/discord-jadx/app/src/main/res/values/public.xml`, which maps every id
to its type and name. Recreate it rather than guessing names. Confirmed this session, all present in
the target:

`2131896462` tooltip_community_feature_disabled · `2131889122` form_help_verification_level ·
`2131889094` form_help_explicit_content_filter · `2131897046` verification_level_medium_criteria ·
`2131897042` verification_level_high_criteria · `2131893064` moderation ·
`2131895592` server_settings_updated · `2131895387` security ·
`2131890729` guild_security_req_mfa_turn_off · `2131890730` guild_security_req_mfa_turn_on ·
`2131890724` guild_security_req_mfa_body · `2131100382` status_red_500 ·
`2131100304` status_green_600 · `2131888462` disable · `2131888824` enable ·
`2131897021` vanity_url · `2131897023` vanity_url_header_uses ·
`2131897026` vanity_url_help_extended · `2131755449` vanity_url_header_uses_uses (plurals) ·
`2131888486` discard_changes · `2131888489` discard_changes_description · `2131893499` okay

**Placeholder names differ from the argument names Vineflower shows.** Verified against
`res/values/strings.xml`: `minimum_size` takes `{size}` once for both dimensions, not
`{width}`/`{height}`; `verification_level_*_criteria` take `{min}`, not `{minutes}`;
`vanity_url_help_extended` takes `{url}`; `vanity_url_header_uses` takes `{uses}`. Always read the
actual string before choosing a `SettingsStrings.render` key.

Also absent from the target, checked: there is no `avatar_size_extra_extra_large` dimen — the
XX-large avatar dimen is `avatar_size_xxlarge`.

### Constants and gates confirmed this session

- `GuildVerificationLevel` = NONE(0) LOW(1) MEDIUM(2) HIGH(3) HIGHEST(4);
  `GuildExplicitContentFilter` = NONE(0) SOME(1) ALL(2) — both match the radio order in
  `widget_server_settings_moderation.xml`, so `values()[index]` is safe there.
- `systemChannelFlags` is a **suppression** mask: 1 = suppress join notifications,
  2 = suppress premium-subscription messages, 8 = suppress join-notification replies. Each switch in
  Overview shows the **inverse** of its bit, and the save path rebuilds the mask.
- `WidgetServerSettingsOverview.STATE_ID_NOTIFICATION_DEFAULT = 90001` — the reference's literal
  non-view key for the default-notification radio group.
- AFK timeouts offered: 60, 300, 900, 1800, 3600 seconds. Integration grace periods: 1, 3, 7, 14, 30
  days.
- Vanity-URL "code already taken" is API error **50020**. The reference suppresses the toast for it
  and shows `change_vanity_url_error` inline; this project's `RestApiError` has no per-error toast
  suppression, so that call passes `context = null` and renders every failure itself.
- Per-screen gates, all read from the reference rather than assumed:
  **Moderation / Integrations / Edit integration / Overview** = `isOwner || canAndIsElevated(MANAGE_GUILD)`;
  **Vanity URL** = `isOwner || can(MANAGE_GUILD)` — the *plain* check, no MFA elevation;
  **Security** = **owner only**, permissions irrelevant, and `isMfaEnabled` is `guild.mfaLevel > 0`;
  **Edit integration** additionally requires `integration.enabled`;
  **Emoji** = `canAndIsElevated(MANAGE_EMOJIS_AND_STICKERS)` with **no owner bypass and no read-only
  mode**;
  **Instant invites** = **no gate at all** in the reference — access is controlled solely by the
  shell row behind Manage Server.
- **There is no server-settings stickers screen in 126.21.** The decompile tree contains no
  `*Sticker*` class under `widgets/servers/`; sticker UI lives in `widgets/chat/input/sticker/` and
  `widgets/stickers/`. The audit inventory was right; do not build one.
- **`WidgetDisableDeleteAccountDialog.Mode` ordinals are `DISABLE(0), DELETE(1)`** — confirmed by
  `javap -p -c` on the dex2jar static initialiser. Vineflower prints them as `DELETE, DISABLE`, which
  is wrong, and `onDisableClicked` dispatches on `ordinal`. Trusting the decompiler here would delete
  accounts when the user asked to disable one.

### `CheckedSetting.isChecked` — use the call form

`CheckedSetting` is a **Kotlin** class overriding Java's `Checkable`, so `isChecked()` is a Kotlin
function and synthetic-property access (`view.isChecked`, `view.isChecked = x`) is not reliably
available. Every file in this wave now uses `isChecked()` and `setChecked(value, notifyListener)`,
which are valid under either reading. **Other packages still use the property form** — e.g.
`widgets/auth/AuthFragments.kt:286,307` and `widgets/contact_sync/WidgetContactSync.kt:215` — sweep
them the next time the build is run.

### `WidgetServerSettings.IMPLEMENTED_OPTIONS` is the wiring switch

`widgets/servers/WidgetServerSettings.kt` hides any row whose destination has not been reconstructed.
As each remaining section lands, add its id to `IMPLEMENTED_OPTIONS` **and** add a `bindDestination`
call. Still hidden: `server_settings_option_channels`, `server_settings_roles_option`, the community
section, and the guild-role-subscription section. Delete the whole mechanism once they all exist.

The same pattern governs the user-settings screen: `WidgetSettings.implementedRouteIds` +
`bindRoute`. Rows still hidden there: account, profile, authorized apps, connections, QR scanner,
nitro/boost/gifting/role-subscriptions, changelog, support, upload-debug-logs, acknowledgements,
developer options.

## User profile — verified bit table and cross-file requests (profile agent, 2026-07-27)

This wave produced **no code**. It was cut short; the deliverable is the verified
evidence below plus the ordered remaining-work list. Nothing was left half-written.

### User public-flag bit positions — CONFIRMED AT BYTECODE LEVEL. Do not re-derive.

`javap -p -c -cp discord126.21-dex2jar.jar com.discord.utilities.user.UserUtils`.
The private `hasFlag(int flags, int targetFlag)` is `(flags and targetFlag) == targetFlag`
(bytecode `iand` / `if_icmpne`) — an **all-bits-set** test, not `!= 0`. The `User`
overload is `hasFlag(publicFlags or flags, targetFlag)`: public flags and private flags
are OR-ed into one namespace before testing.

| Accessor | Bit | Value | Bytecode operand |
| --- | --- | --- | --- |
| `isStaff` | 0 | 1 | `iconst_1` |
| `isPartner` | 1 | 2 | `iconst_2` |
| `isHypeSquad` (events) | 2 | 4 | `iconst_4` |
| `isBugHunterLevel1` | 3 | 8 | `bipush 8` |
| `isMfaSMSEnabled` | 4 | 16 | `bipush 16` |
| `isHypesquadHouse1` (Bravery) | 6 | 64 | `bipush 64` |
| `isHypesquadHouse2` (Brilliance) | 7 | 128 | `sipush 128` |
| `isHypesquadHouse3` (Balance) | 8 | 256 | `sipush 256` |
| `isPremiumEarlySupporter` | 9 | 512 | `sipush 512` |
| `hasUnreadUrgentMessages` | 13 | 8192 | `sipush 8192` |
| `isBugHunterLevel2` | 14 | 16384 | `sipush 16384` |
| `isVerifiedBot` | 16 | 65536 | `ldc_w 65536` |
| `isVerifiedDeveloper` | 17 | 131072 | `ldc_w 131072` |
| `isCertifiedModerator` | 18 | 262144 | `ldc_w 262144` |
| `isHttpInteractionsBot` | 19 | 524288 | `ldc_w 524288` |

Bits 5, 10, 11, 12, 15 are **not read anywhere** in 126.21. There is no
`TEAM_PSEUDO_USER`, `SPAMMER`, or `ACTIVE_DEVELOPER` accessor in this build — do not add one.

Derived predicates, also bytecode-confirmed:
- `isVerifiedBot` = `hasFlag(65536) || isSystemUser || id == -1L` (Clyde).
- `isInHypesquadHouse` = house1 || house2 || house3.
- `isStatusVisible(User, …)` re-OR-s `publicFlags or flags` and delegates to the `(Int, …)`
  overload; the HTTP-interactions-bot branch inside it tests `524288` again.

**`com/discord/utilities/user/UserUtils.kt` in the target already matches this table
exactly, constant for constant** (`FLAG_STAFF=1` … `FLAG_HTTP_INTERACTIONS_BOT=524288`),
including the all-bits-set `hasFlag` and the OR of public+private flags. No change needed.

### `PremiumTier` ordinals — CONFIRMED from `<clinit>`

`javap -p -c … com.discord.api.premium.PremiumTier` `$VALUES` store order:

`NONE=0`, `PREMIUM_GUILD_SUBSCRIPTION_ONLY=1`, `TIER_0=2`, `TIER_1=3`, `TIER_2=4`.

The target enum declares them in exactly this order. Premium-driven badge predicates:
- `isPremium` = `premiumTier != PREMIUM_GUILD_SUBSCRIPTION_ONLY && premiumTier != NONE`
  (so a boost-only subscriber does **not** get the Nitro badge).
- `isPremiumTier2` = `premiumTier == TIER_2`.
- `canUsePremiumStickers` = `PremiumUtilsKt.grantsAccessToPremiumStickers(premiumTier)`.

### Two structural facts that change the shape of this work

1. **There is no full-screen user profile in 126.21.** `find` over the whole Vineflower
   tree returns no `WidgetUserProfile` fragment. The bottom sheet
   `widgets/user/usersheet/WidgetUserSheet` *is* the profile; "full profile" is the sheet
   expanded, plus `WidgetUserMutualGuilds` / `WidgetUserMutualFriends` as separate
   fragments. `widgets/settings/profile/WidgetSettingsUserProfile` is the *own-profile
   editor* and belongs to the settings agent. **Do not create a `WidgetUserProfile`.**
2. **There are no pronouns in 126.21.** `grep -rl pronoun` over the entire reference
   decompile returns zero files, and `api/user/UserProfile` has exactly seven fields:
   `application`, `connectedAccounts`, `guildMember`, `mutualGuilds`, `premiumGuildSince`,
   `premiumSince`, `user`. "About Me" is `guildMember.bio` falling back to `user.bio`.
   Any pronouns UI would be an invention.

### Cross-file requests — User profile

| File | Symbol | Exact edit |
| --- | --- | --- |
| `stores/StoreStream.kt` | `getGuildsSorted()` / `getGuilds()` | Needed by `UserMutualGuildsManager` (below). If `getGuildsSorted()` is unpublished, add `@JvmStatic fun getGuildsSorted() = requireInstance().guildsSorted`. |
| `widgets/user/usersheet/WidgetUserSheet.kt` | `handleEvent` `else` branch | Route `Event.LaunchDisableCommunication` / `LaunchEnableCommunication` to `widgets/guildcommunicationdisabled` once that package lands. Today they only reach `AppLog.i`, so **timeout/untimeout is unreachable from the sheet**. Kick and ban are correctly routed to `WidgetKickUser.show` / `WidgetBanUser.show`, which keep their confirmation dialogs. |
| `widgets/stage/usersheet/UserProfileStageActionsView` | new | `WidgetUserSheetViewModel` has the two stage fields commented out awaiting this type (already recorded in the earlier user-sheet wave note). |


## Server-settings Roles slice — research complete, NO code written (wind-down)

An agent researched `widgets/servers` Roles fully and wrote **no files**. Everything
below is verified evidence so the next agent does not re-derive it.

### Missing dependencies this slice needs

| Missing | Needed by | Note |
| --- | --- | --- |
| `com.discord.utilities.colors.ColorPickerUtils` | `WidgetServerSettingsEditRole` colour picker | Package does not exist in the target. The `com.jaredrummler:colorpicker:1.1.0` dependency **is** declared (`libs.versions.toml:71`, `app/build.gradle.kts:72`) and cached. The reference builds the dialog through a **patched** copy of the library with extra theming setters (obfuscated fields `s,r,x,o,v,p,w,y,q` on `Builder`) that stock 1.1.0 does not have. Reproduce with the stock `ColorPickerDialog.Builder` API only (verified from the sources jar): `newBuilder()`, `setColor`, `setPresets(int[])`, `setDialogTitle`, `setDialogType(TYPE_PRESETS/TYPE_CUSTOM)`, `setAllowPresets`, `setAllowCustom`, `setShowAlphaSlider`, `setShowColorShades`, `setColorShape`, `setSelectedButtonText`, `setPresetsButtonText`, `setCustomButtonText`, `setDialogId`, `create()`, `show(FragmentActivity)`; result via `setColorPickerDialogListener(ColorPickerDialogListener)` (deprecated but functional) or the host Activity implementing it. Discord's theming is **not** reproducible without patching the library — record as a deliberate divergence. |
| `com.discord.widgets.chat.list.NewThreadsPermissionsFeatureFlag` | `WidgetServerSettingsEditRole.Model.useNewThreadPermissions` | Already stubbed as `PermissionUtils.newThreadsPermissionsEnabled: (Long) -> Boolean`, defaults `false`. With `false` the reference **relabels** the three thread rows to the interim strings — see the report. |
| `GuildCommunicationDisabledGuildsFeatureFlag` (`widgets/guildcommunicationdisabled/start`) | `Model.hasGuildCommunicationDisabledFeature`, gates the `role_settings_moderate_member` row | Check whether that package now exists; otherwise default `false` and hide the row. |
| `WidgetServerSettingsMembers` | `WidgetServerSettingsRolesAdapter.RoleListHelpItem` | `R.string.help_roles_description` contains a masked link `[Members](onMembersClick)` whose hook opens `WidgetServerSettingsMembers.create(context, guildId)`. That screen does not exist — render the text with the link inert until it lands. |

### `StatefulViews` is only used for the role NAME on EditRole
`WidgetServerSettingsEditRole` does **not** batch permissions behind the save FAB.
Every permission/hoist/mentionable/colour change calls `patchRole` immediately
(`RestAPIParams.Role.createWithRole(role)` + the one changed field). Only
`edit_role_name` feeds `StatefulViews`, and the save FAB (`edit_role_save`) commits
just the trimmed name, toasting `R.string.form_label_role_enter_name` when blank.
Do not "improve" this into a batched form — it would change write semantics.

---

## Forums — cross-file requests

Raised 2026-07-27 by the forums agent while researching the forum-browser cluster.
Nothing in this section was applied; every item lives in a file owned by another agent.

### 1. `app/src/main/java/com/discord/utilities/rx/ObservableExtensions.kt` — add `combineLatest` overloads
`WidgetForumBrowserViewModel.Companion.observeStoreState` combines **17** observables in one
`Observable.combineLatest(..., Function17)` call. The reference keeps these in
`com.discord.utilities.rx.ObservableCombineLatestOverloadsKt` (arities 6..20, each taking a
`FunctionN`). The target has no such file and RxJava 1's `Observable.combineLatest` tops out at 9
sources plus an `Observable<List>`/`FuncN` form.
**Exact need:** a new file `utilities/rx/ObservableCombineLatestOverloads.kt` (or an addition to
`ObservableExtensions.kt`) exposing at minimum:
```kotlin
fun <T1,…,T17,R> combineLatest(o1: Observable<T1>, …, o17: Observable<T17>,
    combineFunction: (T1,…,T17) -> R): Observable<R>
```
implemented over `Observable.combineLatest(listOf(o1..o17)) { args -> … }`. Owner: utilities/rx wave.

### 2. `app/src/main/java/com/discord/stores/StoreStream.kt` — forum-browser accessors
`WidgetForumBrowserViewModel`'s default constructor arguments read 17 stores off `StoreStream`.
The wiring block in `docs/parity-audit/storestream-wiring.md` ("Thread + forum stores") is still
**unapplied**. In addition to the accessors listed there, the forum browser needs
`StoreStream.getGuildsNsfw()` (used by `handleStoreState` to build `NsfwPanelState` via
`isGuildNsfwGateAgreed(guildId)`); `StoreGuildsNsfw` is listed MISSING in
`audit-stores-channels.md`. Owner: stores/StoreStream coordinator.

### 3. `docs/parity-audit/storestream-wiring.md` §"DECISION REQUIRED" — experiment names now recovered
`canAccessForumChannels` was left as `{ false }` because the experiment key was unknown. It is
recovered from the Vineflower decompile of `ForumUtils` and can be flipped on now that
`StoreExperiments` exists in the target:
- **create** posts / forum channels: `getGuildExperiment("2021-07_threads_only_channel", guildId, false)?.bucket == 1`
- **browse** the redesigned forum UI: `getGuildExperiment("2022-03_android_forum_channel_redesign", guildId, false)?.bucket == 1`
These are two *different* experiments; `StoreForumPostReadStates.canAccessForumChannels` takes the
first one. Owner: stores/StoreStream coordinator.

### 4. `app/src/main/java/com/discord/api/channel/Channel.kt` — confirm forum-tag accessors exist
`WidgetForumBrowserViewModel.createPostItem` needs, on the **parent** forum channel,
`availableTags: List<ForumTag>` (reference `Channel.d()`) and, on the **post** channel,
`appliedTags: List<Long>` (reference `Channel.c()`), plus `ForumTag.id` (`c()`),
`ForumTag.emojiName` (`a()`). `ForumTag` exists in the target (used by
`widgets/forums/ForumPostModels.kt`), but the two `Channel` list fields were not verified.
If either is absent, add it before the forum browser can render tags. Owner: api/models wave.

### 5. `widgets/chat/**` (owned elsewhere) — forum post preview parsing
`ForumBrowserPostView.configureFirstMessage` must run
`DiscordParser.parseChannelMessage(context, content, renderContext, MessagePreprocessor,
ParserOptions.FORUM_POST_FIRST_MESSAGE, isEdited)` into a `DraweeSpanStringBuilder`
(audit item 11, MAJOR). `ParserOptions.FORUM_POST_FIRST_MESSAGE` and the `MessageRenderContext`
builder live outside `widgets/forums/`. Request: expose a reusable
`MessageRenderContext` factory + the `FORUM_POST_FIRST_MESSAGE` parser option so the forum browser
can stop assigning `message.content` raw. Owner: chat/markdown wave.

## Voice feedback — cross-file requests

Raised 2026-07-27 by the `widgets/voice/feedback/**` research pass. Nothing in this
section was edited by that agent; every item is a request against a file it does not own.

### `com.discord.stores.StoreAnalytics` — two missing sinks

`app/src/main/java/com/discord/stores/StoreAnalytics.kt` has `trackShowCallFeedbackSheet(Long)`
(line ~700) but is missing the two submit sinks the reference declares. Confirmed by
`javap -p com/discord/stores/StoreAnalytics.class` on `discord126.21-dex2jar.jar`:

```
public final void trackCallReportProblem(com.discord.widgets.voice.feedback.PendingFeedback$CallFeedback);
public final void trackStreamReportProblem(com.discord.widgets.voice.feedback.PendingFeedback$StreamFeedback);
private final java.lang.String getStreamFeedbackReasonFromIssue(com.discord.widgets.voice.feedback.FeedbackIssue);
```

Exact edit requested, in `StoreAnalytics.kt`, adjacent to `trackShowCallFeedbackSheet`:

- add `fun trackCallReportProblem(feedback: PendingFeedback.CallFeedback)`
- add `fun trackStreamReportProblem(feedback: PendingFeedback.StreamFeedback)`
- add `private fun getStreamFeedbackReasonFromIssue(issue: FeedbackIssue): String`
- the reference also has `trackShowStreamFeedbackSheet`-equivalent behaviour folded into
  `StreamFeedbackSheetViewModel`'s constructor; check `javap -c` before adding a new name.

**Security constraint carried from the task brief:** `PendingFeedback.*.issueDetails` is free
user text. These sinks must never write `issueDetails` to a log, breadcrumb, or `toString()`
that reaches a log. The analytics path must stay disabled by default; do not wire feedback
into an already-enabled tracker as part of adding these methods.

Until they exist, `CallFeedbackSubmitter` / `StreamFeedbackSubmitter` /
`CallFeedbackSheetViewModel` / `StreamFeedbackSheetViewModel` cannot compile.

### `WidgetFeedbackSheet.voiceViewModelProvider` is a seam to delete

`app/src/main/java/com/discord/widgets/feedback/WidgetFeedbackSheet.kt:192` declares

```kotlin
var voiceViewModelProvider: (WidgetFeedbackSheet, FeedbackType) -> FeedbackSheetViewModel? = ...
```

a process-global mutable hook standing in for the two voice view models. The reference has no
such field: `WidgetFeedbackSheet` holds three `by appViewModels` delegates
(`viewModelCallFeedbackSheet$2`, `viewModelStreamFeedbackSheet$2`,
`viewModelGuildDeleteFeedbackSheet$2` all exist as synthetic classes in the DEX).
Once `CallFeedbackSheetViewModel` and `StreamFeedbackSheetViewModel` land, delete
`voiceViewModelProvider` and replace the `when (feedbackType)` at line 76 with the three
delegates. Owner of `widgets/feedback/WidgetFeedbackSheet.kt` should make this edit.

### Argument-key bug is reference behaviour — keep it

`WidgetFeedbackSheet.ARG_STREAM_FEEDBACK_STREAM_KEY` is `"ARG_STREAM_FEEDBACK_STREAM_KEY "`
**with a trailing space**, confirmed by `javap -constants`. The target already reproduces it
(`WidgetFeedbackSheet.kt:182`). `StreamFeedbackSheetNavigator.show` must write the same
trailing-space key or the sheet reads a null stream key. Do not "fix" either side in isolation.

## Stickers — user-reported bugs, 2026-07-27 (coordinator)

Two bugs the user reported directly: tapping a sticker opens no popup, and animated stickers do
not animate. Research by the stickers agent; conclusions below are its findings, with one fix
applied by the coordinator.

### FIXED — `utilities/stickers/StickerUtils.fetchSticker` never created its cache directory

The reference does `if (!dir.exists()) dir.mkdir()` before handing off to the downloader; ours did
not. `FileOutputStream` will not create a missing parent, so **every** sticker download failed, and
the failure is only logged at warn level — `StickerView` simply leaves its placeholder up forever.
PNG stickers were unaffected because they render through the media proxy rather than this download
path, which is exactly why the symptom presented as "animated stickers don't work". `mkdirs()`
applied with a comment. **Needs device confirmation**, since a second cause may sit behind it (below).

### Ruled out — do not re-investigate these

- `StickerFormatType` ordinals are correct: `UNKNOWN(-1)=0, PNG(1)=1, APNG(2)=2, LOTTIE(3)=3`,
  confirmed against `<clinit>`. `StickerType` likewise. `StickerFormatTypeTypeAdapter` *is*
  registered with Gson, so `format_type` does deserialise.
- CDN URL construction is correct, including `passthrough = true` by default and the `.json` vs
  `.png` suffix per format. Do not "fix" it — `passthrough=false` would make the media proxy
  re-encode to a single still frame, which would *cause* the reported bug.
- No missing Gradle coordinate. The Lottie path does not use `com.airbnb.android:lottie`; it uses
  the project's own native rlottie bridge, whose JNI symbols live in `libdsti.so`.

### Still open, in priority order

1. **Runtime-verify the rlottie native path** before changing any source. If
   `RLottieDrawable.create()` returns `0L`, the drawable is returned with no frames and no error is
   surfaced. PLAN.md Phase 1 already records that rlottie native rendering is not runtime-verified.
   Check logcat for `RLottieDrawable`.
2. **`widgets/chat/list/adapter/WidgetChatListAdapterItemSticker.kt` — cross-file request.** The
   reference resolves the *full* sticker before binding: it runs
   `StickerUtils.getGuildOrStandardSticker(id, false).take(1)` and calls `setSticker` and
   `setOnClickListener` **inside** the subscribe callback, falling back to the entry's partial.
   Ours binds synchronously on the `StickerPartial`, so `StickerSheets.show` must do a second REST
   round trip, and if that call fails the user gets `WidgetUnknownStickerSheet` instead of the real
   popout — which reads as "the popup doesn't open". Reference:
   `out/com/discord/widgets/chat/list/adapter/WidgetChatListAdapterItemSticker.kt`.
   Owner: whoever holds `widgets/chat/list/adapter`.
3. **`views/sticker/StickerView.setOnClickListener` is silently order-dependent.** It forwards the
   listener to whichever child view matches `sticker?.formatType`, with `else -> Unit`. If `sticker`
   is null or `UNKNOWN` at that moment, the listener is dropped with no trace and the row is never
   clickable. This faithfully mirrors the reference, so it is correct-by-parity — but it means
   fixing item 2 may fix the tap bug as a side effect. Verify `this.sticker` is non-null at the
   call site before concluding anything else.
4. Check `WidgetChatListItem.onConfigure` does not install a row-level click listener that shadows
   the child's. Unverified.
5. Move `getGuildOrStandardSticker` from `widgets/stickers/StickerSheets.kt` to
   `utilities/stickers/StickerUtils.kt`, where the reference declares it. That file's own header
   comment already asks for this.

Note: the sticker popouts are **not** missing. `StickerSheets`, `WidgetStickerSheet`,
`WidgetGuildStickerSheet` and `WidgetUnknownStickerSheet` all exist and are wired end to end from
`WidgetChatListAdapter.onStickerClicked`. Audit that path; do not rebuild it.

## Notices — StoreStream / app-shell requests (2026-07-27, notices+premium agent)

The notice **queue, persistence, popup and dialog layers are complete**. Verified
file-by-file, no rebuild needed:

| File | State |
| --- | --- |
| `stores/StoreNotices.kt` (347 ln) | Complete. `PriorityQueue` ordered `compareBy({ it is PassiveNotice }, { -it.priority }, { it.requestedShowTimestamp })`; per-notice `sinceLastPeriodMs` cooldown against the persisted `NOTICES_SHOWN_V2` seen-cache; per-priority `delayPeriodMs` cooldown via `lastShownTimes`; adaptive poll rate floored at 500 ms; `PassiveNotice.shouldShow` widens to every priority `<= its own` |
| `widgets/notice/NoticePopup.kt` (335 ln) | Complete. `enqueue` builds a `StoreNotices.Notice(priority = PRIORITY_INAPP_NOTIFICATION, delayPeriodMs = 1000)`; decor-view attach, cancellable auto-dismiss animator, drawee-span body, sticker, top-right icon |
| `widgets/notice/NoticeAlertView.kt` (187 ln), `NoticePopupChannel.kt` (264 ln), `WidgetNoticeDialog.kt` (325 ln), `WidgetNoticeNuxOverlay.kt` (46 ln), `WidgetNoticeNuxSamsungLink.kt` (96 ln) | Present |
| `utilities/notices/NoticeBuilders.kt` (148 ln) | Complete for both `StoreNotices.Dialog.Type` values (`REQUEST_RATING_MODAL`, `DELETE_CONNECTION_MODAL`) |

**Two wiring gaps remain, both outside the notice agent's ownership. Until BOTH are
closed no notice ever renders, silently.**

1. **`NoticeBuilders.install()` is never called.** `app/App.kt:onCreate` calls
   `AppLog.init` ... `WidgetMaskedLinksDialog.installUntrustedLinkHandler()` but not
   `NoticeBuilders.install()`. Without it `StoreNotices.dialogPresenter` stays
   `null`, `Dialog.Type.buildPassiveNotice`'s `show` lambda returns `false`, and
   every dialog-backed notice stays queued forever. **Add `NoticeBuilders.install()`
   to `App.onCreate`, next to `WidgetMaskedLinksDialog.installUntrustedLinkHandler()`.**
   Owner: app-shell agent.

2. **Nothing subscribes to `StoreNotices.getNotices()`.** Grepped the whole tree: the
   only callers of `StoreStream.getNotices()` are `requestToShow` / `markSeen` /
   `markDialogSeen` producers. `StoreNavigation` has **no `launchNotice`** — its
   public surface is panel/route/intent only (`StoreNavigation.kt:58-123`). The
   reference's `StoreNavigation.launchNotice` is the host: it combines `getNotices()`
   with the currently visible `AppComponent`, checks that `notice.validScreens`
   contains that screen's class, and calls `notice.show(activity)` on the main
   thread; a `null` emission is what tells it to stop trying. Needed shape:

   - `StoreNavigation.launchNotice(): Observable<StoreNotices.Notice?>` — `getNotices()`
     observed on the main thread.
   - Host (`AppActivity` or `WidgetHome`): on each emission, if
     `notice.validScreens == null || notice.validScreens.any { it.isInstance(visibleComponent) }`
     then `notice.show(activity)`. A `false` return must leave the notice queued.

   Owner: `StoreStream`/`StoreNavigation` agent plus the `AppActivity` agent.
   `StoreNotices.DEFAULT_VALID_SCREENS` is currently `[WidgetHome]` only; the
   reference also lists `WidgetCallFullscreen`, which does not exist yet — add it
   when it lands or notices will be suppressed during a call instead of shown over it.

## Premium (Nitro / boost) — blockers found 2026-07-27

### The five billing/premium stores exist but are dead code
`StoreSubscriptions`, `StoreEntitlements`, `StoreGooglePlaySkuDetails`,
`StoreGifting`, `StoreGooglePlayPurchases` are all present under `stores/` and all
declare real classes — but **`StoreStream` neither constructs nor exposes any of
them**. Grepping `StoreStream.kt` for those five names returns a single TODO comment
at `:1141` and nothing else.

**Request to the StoreStream agent:** construct and expose

- `getSubscriptions()` -> `StoreSubscriptions`
- `getEntitlements()` -> `StoreEntitlements`
- `getGooglePlaySkuDetails()` -> `StoreGooglePlaySkuDetails`
- `getGifting()` -> `StoreGifting`
- `getGooglePlayPurchases()` -> `StoreGooglePlayPurchases`

Nothing in the premium screen cluster can be built until these are reachable — every
screen's `observeStores()` is a `combineLatest` over three or more of them.

### `GooglePlayBillingManager` does not exist
`utilities/billing/` contains only `GooglePlayInAppSku.kt`. The reference's
`utilities/billing/GooglePlayBillingManager` — the `BillingClient` owner that both
`StoreGooglePlayPurchases` and `StoreGooglePlaySkuDetails` drive — is absent. This is
part of why the purchase path fails closed today, and it should **stay** absent until
Manticord-owned Play products exist. See the SKU note below.

### Package placement decision needed before the screens are written
The task assigned `widgets/premium/**`, but **the reference has no such package**. The
screens live at `widgets/settings/premium/**`, `widgets/settings/guildboost/**` and
`widgets/servers/guildboost/**` — all three inside currently-owned trees
(`widgets/settings/**`, `widgets/servers/**`). All three subdirectories are **absent
from the target**, so creating them collides with no existing file, but the ownership
rule as written forbids it. Recommendation: build in the reference-accurate packages
rather than invent `com.discord.widgets.premium`. Confirm with the coordinator first.

### Confirmed at `<clinit>` bytecode (`javap -c` over `discord126.21-dex2jar.jar`)
- `com.discord.api.premium.PremiumTier`: `NONE=0`, `PREMIUM_GUILD_SUBSCRIPTION_ONLY=1`,
  `TIER_0=2`, `TIER_1=3`, `TIER_2=4`. Target `api/premium/PremiumTier.kt` matches.
- `com.discord.api.premium.SubscriptionInterval`: `MONTHLY=0`, `YEARLY=1`.
- `com.discord.api.premium.PriceTierType`: `UNKNOWN=0` (wire `-1`),
  `GUILD_ROLE_SUBSCRIPTIONS=1` (wire `1`).
- **There is no `PremiumGuildTier` and no `PremiumType` type in the reference.** The
  dex2jar archive contains no such class. Guild boost tier is a plain `int` on the
  guild plus `utilities/premium/GuildBoostTierAmounts`. Do not create either enum.
- `com.discord.models.domain.premium.SubscriptionPlanType`: 17 constants, ordinals
  0..16, in exactly the order the target already declares them. Constructor is
  `(price: Int, interval: SubscriptionInterval, intervalCount: Int, planTypeString: String?, planId: Long, premiumTier: PremiumTier?)`
  — matches the target exactly, including all 17 prices and plan-id snowflakes.
- **Reference bug, already reproduced correctly by the target — do not "fix":**
  `PREMIUM_GUILD_PLANS` is
  `{PREMIUM_GUILD_MONTH, PREMIUM_3_MONTH_TIER_2, PREMIUM_6_MONTH_TIER_2, PREMIUM_GUILD_YEAR}`.
  The two Nitro tier-2 multi-month plans stand in for `PREMIUM_GUILD_3_MONTH` /
  `PREMIUM_GUILD_6_MONTH`, which end up in no set at all. Proven by resolving the
  `aload` locals in `<clinit>`: `aload 4` -> `PREMIUM_GUILD_MONTH`, `aload_0` ->
  `PREMIUM_3_MONTH_TIER_2`, `aload_1` -> `PREMIUM_6_MONTH_TIER_2`, `aload 11` ->
  `PREMIUM_GUILD_YEAR`.

### All premium layouts and support views are already imported
Present in `res/layout/`: `widget_settings_premium`, `widget_choose_plan` plus its
three adapter items, `widget_settings_boost`, `widget_settings_gifting`,
`widget_server_boost_status`, `widget_server_boost_confirmation`,
`widget_guild_boost_transfer`, `widget_claim_outbound_promo`,
`widget_outbound_promo_terms`, `widget_premium_upsell`,
`widget_settings_premium_price_change_notice`, `view_premium_marketing`,
`view_premium_settings_tier_1`, `view_premium_settings_tier_2`,
`view_premium_settings_perks`, `view_premium_guild_progress`,
`view_premium_guild_progress_bar`, `view_settings_boosted_boost_listitem`,
`view_settings_boosted_guild_listitem`, `view_settings_boosted_header_listitem`,
`view_settings_boosted_sample_guild`, `view_settings_premium_guild_marketing_header`,
`view_settings_premium_guild_no_guilds`, `view_nitro_boost_perks`,
`view_nitro_boost_perks_list_item`, `view_gift_entitlement_list_item`,
`view_gift_sku_list_item`, `view_gift_outbound_promo_list_item`,
`view_no_gifts_list_item`, `view_guild_boost_confirmation`,
`layout_premium_settings_payment`, `layout_premium_guild_status_features_user`, and
all eight boost/premium dialog layouts.

Also already reconstructed: `views/premium/{AccountCreditView, GiftSelectView, ShinyButton}`,
`views/guildboost/{GuildBoostConfirmationView, GuildBoostMarketingView, GuildBoostProgressView, GuildBoostSubscriptionUpsellView}`,
`utilities/premium/{PremiumUtils, PremiumTierExtensions}`,
`utilities/gifting/{GiftStyle, GiftingUtils}`,
`api/premium/{PremiumTier, PremiumTierTypeAdapter, PriceTierType, PriceTierTypeAdapter, SubscriptionPlan, OutboundPromotion}`,
`models/domain/premium/SubscriptionPlanType`.

**Only the fragments, view models and adapters are missing.**

### SKU withholding must survive the screen work
`utilities/billing/GooglePlayInAppSku.kt`'s divergence is intact and **must stay**:
`GooglePlayInAppSku.skuId` is `null` until `InAppSkuConfig.installSkuIds(...)` is
called with Manticord-owned ids, and nothing calls it anywhere in the tree. Screens
may render and price products from `SkuDetails`, but any code path that would start a
purchase must check `skuId != null` (equivalently `InAppSkuConfig.isInstalled`) and
disable the CTA — never hardcode a recovered Discord SKU id. Note that the *Play
product-id strings* (`premium_month_tier_1`, ...) are deliberately retained: they are
the artwork keys `GiftingUtils.getGiftIcon` selects on, and the same strings are
already published as `SubscriptionPlanType.planTypeString`.

### Ordered remaining work for the premium cluster
Reference paths are relative to
`C:\Users\manti\StudioProjects\discord-jadx\app\src\main\java\com\discord\`.

1. `stores/StoreStream.kt` — construct + expose the five stores listed above. **Blocks everything below.** Owner: StoreStream agent.
2. `app/App.kt` — add `NoticeBuilders.install()`. Owner: app-shell agent.
3. `stores/StoreNavigation.kt` + `app/AppActivity.kt` — add `launchNotice` and the host subscription described above.
4. `widgets/settings/premium/SettingsPremiumViewModel.kt` — ref `widgets/settings/premium/SettingsPremiumViewModel.java`. `observeStores` over subscriptions + entitlements + sku details + users; `cancelSubscription`. Needs (1).
5. `widgets/settings/premium/WidgetSettingsPremium.kt` — ref `widgets/settings/premium/WidgetSettingsPremium.java` + layout `widget_settings_premium.xml`, `view_premium_settings_tier_1/2.xml`, `view_premium_settings_perks.xml`, `layout_premium_settings_payment.xml`. Six premium-subscription callbacks and six guild-boost callbacks; `showCancelConfirmationAlert`.
6. `widgets/settings/premium/ChoosePlanViewModel.kt` — ref `widgets/settings/premium/ChoosePlanViewModel.java`.
7. `widgets/settings/premium/WidgetChoosePlanAdapter.kt` — ref `widgets/settings/premium/WidgetChoosePlanAdapter.java` + layouts `widget_choose_plan_adapter_{header,divider,plan}_item.xml`.
8. `widgets/settings/premium/WidgetChoosePlan.kt` — ref `widgets/settings/premium/WidgetChoosePlan.java` + `widget_choose_plan.xml`. **Purchase CTA must be disabled when `InAppSkuConfig.isInstalled` is false.**
9. `widgets/settings/guildboost/SettingsGuildBoostViewModel.kt` — ref `widgets/settings/guildboost/SettingsGuildBoostViewModel.java`. Boost slot list + tier progress.
10. `widgets/settings/guildboost/WidgetSettingsGuildBoostSubscriptionAdapter.kt` — ref same-named `.java` + `view_settings_boosted_{header,guild,boost}_listitem.xml`.
11. `widgets/settings/guildboost/SettingsGuildBoostSampleGuildAdapter.kt` — ref same-named `.java` + `view_settings_boosted_sample_guild.xml`.
12. `widgets/settings/guildboost/WidgetSettingsGuildBoost.kt` — ref same-named `.java` + `widget_settings_boost.xml`, `view_settings_premium_guild_marketing_header.xml`, `view_settings_premium_guild_no_guilds.xml`.
13. `widgets/servers/guildboost/GuildBoostViewModel.kt` — ref `widgets/servers/guildboost/GuildBoostViewModel.java`.
14. `widgets/servers/guildboost/GuildBoostPerkView.kt` + `GuildBoostPerkViewAdapter.kt` — ref same-named `.java` + `view_nitro_boost_perks.xml`, `view_nitro_boost_perks_list_item.xml`.
15. `widgets/servers/guildboost/WidgetGuildBoost.kt` — ref same-named `.java` + `widget_server_boost_status.xml`.
16. `widgets/servers/guildboost/GuildBoostInProgressViewModel.kt` + `WidgetGuildBoostConfirmation.kt` — ref same-named `.java` + `widget_server_boost_confirmation.xml`, `view_guild_boost_confirmation.xml`.
17. `widgets/servers/guildboost/GuildBoostTransferInProgressViewModel.kt` + `WidgetGuildBoostTransfer.kt` — ref same-named `.java` + `widget_guild_boost_transfer.xml`.
18. `widgets/settings/premium/SettingsGiftingViewModel.kt` + `WidgetSettingsGifting.kt` + `WidgetSettingsGiftingAdapter.kt` + `WidgetSettingsGiftingOutboundPromosAdapter.kt` — ref same-named `.java` + `widget_settings_gifting.xml`, `view_gift_{sku,entitlement,outbound_promo}_list_item.xml`, `view_no_gifts_list_item.xml`. **Gift purchase must fail closed on a null `GooglePlayInAppSku.skuId`.**
19. `widgets/settings/premium/ClaimOutboundPromoViewModel.kt` + `ClaimStatus.kt` + `WidgetClaimOutboundPromo.kt` + `WidgetOutboundPromoTerms.kt` + `OutboundPromosPreviewFeatureFlag.kt` — ref same-named `.java` + `widget_claim_outbound_promo.xml`, `widget_outbound_promo_terms.xml`.
20. `utilities/billing/GooglePlayBillingManager.kt` — ref `utilities/billing/GooglePlayBillingManager.java`. **Do not build until Manticord-owned Play products exist**; without it the purchase path fails closed by construction.
21. `widgets/settings/WidgetSettings.kt` — unhide the nitro / boost / gifting rows once 5, 12 and 18 land (rows are currently in the hidden list).

## Media viewer — cross-file requests

Filed 2026-07-27 by the media-viewer agent while diagnosing the user-reported bug
"clicking on image doesn't open proper activity". None of these files are owned by
`widgets/media/**`, so they were **not** edited.

### 1. `app/src/main/java/com/discord/widgets/chat/list/adapter/WidgetChatListAdapterItemEmbed.kt`

**This is the root cause of the reported bug.** Owner: whoever holds `widgets/chat/list/adapter`.

Symbol: `private fun configureEmbedImage(embed: MessageEmbed)`, the click listener at
lines **420-423**.

Current target code:

```kotlin
image.setOnClickListener { view ->
    val externalUrl = with(EmbedResourceUtils) { embed.getExternalOpenUrl() }
    if (externalUrl != null) UriHandler.handleOrUntrusted(view.context, externalUrl, embed.title)
}
```

The reference has an `else` branch that the target dropped. Reference evidence,
`discord-jadx/app/src/main/java/com/discord/widgets/chat/list/adapter/WidgetChatListAdapterItemEmbed.java:568-578`
(`configureEmbedImage$$inlined$apply$lambda$1`):

```java
String externalOpenUrl = EmbedResourceUtils.INSTANCE.getExternalOpenUrl(MessageEmbed.this);
if (externalOpenUrl != null) {
    UriHandler.handleOrUntrusted(view.getContext(), externalOpenUrl, messageEmbed.j());
} else {
    WidgetMedia.Companion.launch(view.getContext(), messageEmbed);
}
```

Exact edit required:

```kotlin
image.setOnClickListener { view ->
    val externalUrl = with(EmbedResourceUtils) { embed.getExternalOpenUrl() }
    if (externalUrl != null) {
        UriHandler.handleOrUntrusted(view.context, externalUrl, embed.title)
    } else {
        WidgetMedia.launch(view.context, embed)
    }
}
```

`WidgetMedia` is already imported at line 56, and
`WidgetMedia.launch(Context, MessageEmbed)` already exists
(`widgets/media/WidgetMedia.kt:643`). No other change is needed.

Why this is the bug: `EmbedResourceUtils.getExternalOpenUrl()`
(`utilities/embed/EmbedResourceUtils.kt:94-98`) returns non-null only for
`EmbedType.VIDEO` and `EmbedType.FILE`. Every **image** embed — a bare image link,
`EmbedType.IMAGE`, and also `GIFV`/`RICH`/`LINK`/`ARTICLE` — therefore falls through
to an empty lambda and the tap does nothing at all. Its own KDoc at
`EmbedResourceUtils.kt:90` already says "or null to open the media viewer"; the caller
just never does.

### 2. `app/src/main/java/com/discord/api/message/embed/…` / `TypeAdapterRegistrar` — blocks verification of fix 1

`EmbedType` is not registered with Gson (`api/.../TypeAdapterRegistrar.kt:42-62`; already
listed in `docs/parity-audit/README.md` "Cross-cutting defects"). Every embed's `type`
deserialises to `null`, which means:

- `getExternalOpenUrl()` hits its `else` branch for **every** embed, so after fix 1 even a
  `VIDEO` embed will open the in-app viewer instead of the source URL.
- `WidgetMedia.launchArgs(MessageEmbed)` (`widgets/media/WidgetMedia.kt:742-758`) derives
  `progressiveMediaUri` from `embed.type`, so a video embed opened from chat will show only
  its poster frame with no playback.
- `EmbedResourceUtils.getPreviewImage()` is type-driven, so the viewer may receive a null
  preview URL.

Fix 1 is still correct and still repairs the image case. This item must be closed before
the video-embed path can be called at parity.

### 3. No change requested for `WidgetChatListAdapterItemAttachment.kt`

Verified correct against the reference. `openAttachment` at lines 191-196 routes
`IMAGE`/`VIDEO` to `WidgetMedia.launch(context, attachment)` and `FILE` to
`UriHandler.handleOrUntrusted(context, attachment.url)`, which matches
`WidgetChatListAdapterItemAttachment.java:79-90`. Audio is `MessageAttachmentType.FILE`
in 126.21 (the enum has only `VIDEO`, `IMAGE`, `FILE` — confirmed from `<clinit>`
bytecode, ordinals `VIDEO=0, IMAGE=1, FILE=2`), so handing audio to `UriHandler` is
reference behaviour, not a gap.

### 4. `app/src/main/java/com/discord/app/AppActivity.kt` — informational, no edit needed

`AppActivity.consumeIntent` already honours `AppScreenRouter.EXTRA_SCREEN`
(lines 404-441), so the fallback comment inside `widgets/media/WidgetMedia.kt`
`presentScreen` is stale. The media-viewer owner will collapse `presentScreen` onto
`AppScreenRouter.launch` in a follow-up; no activity change is required.

## Chat list — verified state, 2026-07-27

**The entry-type → ViewHolder mapping is TOTAL.** All 45 reference entry-type constants are
declared, every one is mapped in `WidgetChatListAdapter.onCreateViewHolder`, and every holder class
and layout exists. Nothing can reach the `else -> throw invalidViewTypeException` branch. All 45
constant values were confirmed against `javap -p -constants` (they are `const val`, so the
`ConstantValue` attributes are the arbiter, not `<clinit>`). Type `27` does not exist in the
reference either — the gap in our file is correct, not an omission.

**Warning for anyone reading git status on this slice:** a snapshot taken mid-session showed eight
`entries/*.kt` files plus `ChatListFeatureFlags`, `FragmentLifecycleListener`,
`ThreadSpineItemDecoration` and `adapter/WidgetChatListItem.kt` as deleted. They are not. A prior
agent deleted-then-rewrote them and the snapshot caught the middle. Verify against disk.

### Eight row types are mapped but never constructed

These are missing *features*, not crashes — the holder exists, nothing builds the entry:
`BLOCKED_MESSAGES`(10), `GUILD_WELCOME`(25), `THREAD_EMBED`(35), `BOT_UI_COMPONENT`(36),
`THREAD_DRAFT_FORM`(37), `THREAD_STARTER_DIVIDER`(40), `STICKER_GREET`(41),
`STICKER_GREET_COMPACT`(42).

**Six of the eight are unblocked by one file.** `model/WidgetChatListModelMessages.kt` (reference:
`out/com/discord/widgets/chat/list/model/WidgetChatListModelMessages.kt`) is the real row builder
and the sole producer of them. Our `model/ChatListRows.kt` is a reduced stand-in sourcing from a
plain `State` instead of the reference's nine-way store combine. **Decide up front whether the new
file replaces `ChatListRows` or wraps it — do not leave both producing rows.** Its companion
`model/WidgetChatListModelTop.kt` feeds the header state it composes with.

### Callback signatures other clusters wire into

All on `widgets/chat/list/adapter/WidgetChatListAdapter`:

- **botuikit** — `val botUiComponentProvider: ComponentProvider`; row calls
  `onBotUiComponentClicked(applicationId, messageId, messageFlags, componentIndex, customId, values)`,
  adapter fills guild/channel and forwards to `EventHandler.onBotUiComponentClicked(applicationId,
  guildId, channelId, messageId, messageFlags, componentIndex, customId, values)`. Entry is
  `BotUiComponentEntry(message, applicationId, guildId, messageComponents)`.
- **stickers** — `onStickerClicked(message, sticker)`,
  `EventHandler.onSendGreetMessageClicked(channelId, channelType, sticker)`,
  `EventHandler.onSystemMessageCtaClicked(message, channel, sticker)`.
- **media** — there is **no** EventHandler callback for the image viewer.
  `WidgetChatListAdapterItemAttachment` calls `WidgetMedia.launch(context, attachment)` directly,
  and `...ItemEmbed` imports it the same way. Keep `WidgetMedia.launch(Context, MessageAttachment)`
  source-compatible, or coordinate a change to both call sites. The only media-ish handler member is
  `onQuickDownloadClicked(uri, fileName): Boolean`.

### Remaining files, ordered (reference root `out/com/discord/widgets/chat/list/`)

1. `model/WidgetChatListModelMessages.kt` — highest value; unblocks 6 of the 8 dead row types.
2. `model/WidgetChatListModelTop.kt`
3. `adapter/WidgetChatListAdapterEventsHandler.kt` — we inline an anonymous handler in
   `WidgetChatList.kt`; read its `$WhenMappings` sibling too.
4. `adapter/WidgetChatListAdapterItemInviteBase.kt` — refactor only; we duplicate its logic across
   `...ItemInvite` / `...ItemStageInvite` / `...ItemGuildScheduledEventInvite`.
5. `utils/EmbeddedMessageParser.kt` — needed by reply previews and thread-embed rendering.
6. `ChatListItemMessageAccessibilityDelegate.kt` — message rows currently expose no custom a11y
   actions; PLAN.md's UI rules require it.
7. `actions/{EmojiItem,EmojiViewHolder,MoreEmojisViewHolder,WidgetChatListActionsEmojisAdapter}.kt`
8. `actions/MessageActionDialogs.kt`
9. `sheet/WidgetApplicationCommandBottomSheet{,ViewModel}.kt` — our application-command row has
   nowhere to navigate without it.
10. `PublishActionDialog{,ViewModel}.kt`
11. `ChatListFeatureFlags.kt` exists with **zero callers**; wire from `StoreExperiments` when it
    lands, then gate THREAD_EMBED emission on `ViewThreadsFeatureFlag`.

Items 1–2 are the only ones that change what the user sees. Everything from 5 down is additive and
parallelisable.

---

## Fullscreen call helpers — cross-file requests

Raised 2026-07-27 while inventorying the three `WidgetCallFullscreenViewModel` collaborators
(`VideoPlayerIdleDetector`, `VideoPermissionsManager`, `VoiceEngineServiceController`). None of the
three were written; these are the blockers a later agent hits on the first line.

1. **`stores/StoreRtcConnection.kt` — `connectionState` has the wrong element type.**
   Symbol: `StoreRtcConnection.connectionState`.
   Target (`StoreRtcConnection.kt:120`): `val connectionState: Observable<Boolean>`, fed by
   `connectionStateSubject.onNext(connected)` at line 226.
   Reference (`javap com.discord.stores.StoreRtcConnection`):
   `private final rx.Observable<RtcConnection$StateChange> connectionState;` plus
   `SerializedSubject<RtcConnection$StateChange, RtcConnection$StateChange> connectionStateSubject`.
   Exact edit: change the subject and the observable to carry `RtcConnection.StateChange`
   (`state: RtcConnection.State`, `metadata: RtcConnection.Metadata?` — both already declared in
   `rtcconnection/RtcConnection.kt:120`), emitting from the `RtcConnection.Listener`
   `onStateChange` rather than a derived boolean. A boolean cannot express `Disconnected`,
   `RtcConnecting`, `NoRoute`, or `RtcConnected`, and `VoiceEngineServiceController` switches its
   entire notification and overlay behavior on exactly those. Also add
   `getQuality(): Observable<RtcConnection.Quality>` and
   `observeRtcConnectionMetadata(): Observable<RtcConnection.Metadata>`, both present in the
   reference and both consumed by `WidgetCallFullscreenViewModel`.
   Owner: whoever holds `stores/StoreRtcConnection.kt` (it was dirty in the working tree on
   2026-07-27, so an agent is likely already in it).

2. **`utilities/rx/ObservableExtensions.kt` — missing `ObservableWithLeadingEdgeThrottle`.**
   Symbol: `ObservableWithLeadingEdgeThrottle.combineLatest(o1, o2, o3, Func3, 300L, MILLISECONDS)`.
   The reference `VoiceEngineServiceController` builds its notification stream with this exact call
   (verified in `VoiceEngineServiceController$notificationDataObservable$1$1`, bytecode offsets
   58-64: `ldc2_w 300l`, `TimeUnit.MILLISECONDS`,
   `ObservableWithLeadingEdgeThrottle.combineLatest:(Lrx/Observable;Lrx/Observable;Lrx/Observable;Lrx/functions/Func3;JLjava/util/concurrent/TimeUnit;)Lrx/Observable;`).
   The target has `Observable<T>.leadingEdgeThrottle(windowDuration, timeUnit)` at
   `ObservableExtensions.kt:169` but no combineLatest form.
   Exact edit: add a 3-arity `combineLatest` that zips the three sources and applies
   `leadingEdgeThrottle(windowDuration, timeUnit)` to the combined stream — i.e.
   `Observable.combineLatest(a, b, c) { x, y, z -> f(x, y, z) }.leadingEdgeThrottle(300L, MILLISECONDS)`.
   Semantics that matter: the *first* combination in each 300 ms window is emitted immediately
   (leading edge) so the notification updates instantly on mute/deafen, and the rest of the window
   is dropped so a burst of voice-state changes cannot thrash the foreground notification.
   Owner: whoever holds `utilities/rx/`.

3. **`utilities/permissions/` — `PermissionsManager` does not exist.**
   The reference has a one-method injectable wrapper
   `class PermissionsManager { fun can(permission: Long, computedPermissions: Long?): Boolean =
   PermissionUtils.can(permission, computedPermissions) }` whose sole purpose is to let
   `VideoPermissionsManager` be constructed with a fake in tests. The target has the `PermissionUtils`
   object (`utilities/permissions/PermissionUtils.kt:66`) but no wrapper. Whoever builds
   `VideoPermissionsManager` owns creating it — it is a strictly-required 4-line type, not a
   cross-file request. Noted here only so it is not mistaken for a missing dependency.

4. **`utilities/voice/` — `VoiceEngineForegroundService` and `DiscordOverlayService` do not exist.**
   `VoiceEngineServiceController` cannot be written without them; it is a thin driver over their
   companion APIs. Neither is in the target (`utilities/voice/` holds only
   `VoiceChannelJoinability.kt` and `VoiceChannelJoinabilityUtils.kt`). Required companion surface,
   read from `javap com.discord.utilities.voice.VoiceEngineForegroundService$Companion`:
   - `var onDisconnect: () -> Unit`, `var onToggleSelfMute: () -> Unit`, `var onToggleSelfDeafen: () -> Unit`
   - `startForegroundAndBind(Connection, CharSequence, CharSequence, Boolean, Boolean, Boolean, Long, Long?, Boolean, Boolean)`
   - `stopForegroundAndUnbind(Connection)`, `startStream(Connection, Intent)`, `stopStream(Connection)`
   - `stageInviteAckIntent(Context, Long, Boolean)`, `stageInviteAckPendingIntent(Context, Long, Boolean)`
   and `class Connection(context: Context)` with fields `connection: ServiceConnection`,
   `context: Context`, `service: VoiceEngineForegroundService?`, `isUnbinding: Boolean`.
   `DiscordOverlayService.Companion` needs `launchForVoice(Context)` and `launchForClose(Context)`.
   SECURITY: `stageInviteAckPendingIntent` must be created with `FLAG_IMMUTABLE` on API 23+; the
   notification's public/lock-screen visibility must not carry the channel name or participant list
   beyond what `VoiceEngineNotificationBuilder` does in the reference.

---

# Student Hubs — build spec

Written 2026-07-27 by the hubs/directories survey agent, for a fresh agent to build from.
Reference paths below are relative to
`C:\Users\manti\Desktop\projeler\discorddecompile\out\com\discord\` (the Vineflower Kotlin
decompile) unless stated otherwise. Target paths are relative to
`app/src/main/java/com/discord/`.

## 0. Read this first: most of it already exists

A session on 2026-07-26 (21:39-22:11) built 35 files under `widgets/hubs/` plus
`api/hubs/HubModels.kt` and was killed by a usage limit mid-cluster. **Do not start from zero.**
The work is high quality (redacted `toString`s, documented divergences, real view models). The
job is to *verify, finish and wire*, not to rewrite.

What is present and looks complete:

| Target file | Covers reference classes |
| --- | --- |
| `api/hubs/HubModels.kt` | `api/hubs/EmailVerification.kt`, `EmailVerificationCode.kt`, `GuildInfo.kt`, `WaitlistSignup.kt` |
| `widgets/hubs/HubArgs.kt` | the `intent_args_key` args delegate (reference keeps this in `com.discord.app`) |
| `widgets/hubs/HubEmailModels.kt` | `HubEmailArgs.kt`, `HubEmailEntryPoint.kt`, `HubEmailState.kt` |
| `widgets/hubs/HubAuthenticationModels.kt` | `HubAuthenticationArgs.kt`, `HubAuthenticationState.kt`, `AuthenticationResult.kt` |
| `widgets/hubs/HubWaitlistModels.kt` | `HubWaitlistArgs.kt`, `HubWaitlistResult.kt`, `HubWaitlistState.kt` |
| `widgets/hubs/HubDomainModels.kt` | `HubDomainArgs.kt`, `DomainsState.kt`, `DomainGuildInfo.kt`, `MultipleDomainResult.kt` |
| `widgets/hubs/HubDescriptionModels.kt` | `HubDescriptionArgs.kt`, `HubDescriptionState.kt`, `DescriptionResult.kt` |
| `widgets/hubs/HubAddNameModels.kt` | `HubAddNameArgs.kt`, `AddNameState.kt` |
| `widgets/hubs/HubAddServerModels.kt` | `HubAddServerState.kt`, `AddServerConfirmationArgs.kt` |
| `widgets/hubs/RadioSelectorModels.kt` | `RadioSelectorItem.kt`, `RadioSelectorItems.kt` |
| `widgets/hubs/{HubDomainViewHolder,RadioSelectorViewHolder,DiscordHubAddServerViewHolder}.kt` | same names |
| `widgets/hubs/WidgetHub{EmailFlow,Authentication,Waitlist,Domains,DomainSearch,Description,AddName,AddServer,AddServerConfirmationDialog}.kt` and their `*ViewModel.kt` | same names |
| `widgets/hubs/WidgetRadioSelectorBottomSheet.kt` | same name |
| `widgets/hubs/{HubGuildText,HubTextHooks,HubValidators,HubRestCalls,HubStoreContracts}.kt` | project-local helpers, no 1:1 reference file |
| `models/hubs/DirectoryEntryCategory.kt` | `models/hubs/DirectoryEntryCategory.kt` + `DirectoryEntryCategoryKt.kt` |

**REST is already done.** `restapi/RestAPIInterface.kt` declares all three hub endpoints and
`restapi/RestAPIParams.kt` declares `VerifyEmail`, `VerifyEmailCode`, `HubWaitlist`,
`AddServerBody` — all three secret-carrying bodies already override `toString` with `<redacted>`.
Do not re-derive them.

## 1. Files still to build

`widgets/hubs/events/` is the incomplete sub-package: only `HubEventsModels.kt` exists, covering
`HubEventsArgs`, `HubGuildScheduledEventData`, `HubEventsPage`, `WidgetHubEventsState` and
`HubEventsEventListener`. Still missing, in dependency order:

1. `widgets/hubs/events/WidgetHubEventsViewModel.kt` — ref `widgets/hubs/events/WidgetHubEventsViewModel.kt`
2. `widgets/hubs/events/WidgetHubEventsPageViewHolder.kt` — ref same name (base holder)
3. `widgets/hubs/events/WidgetHubEventViewHolder.kt` — ref same name
4. `widgets/hubs/events/WidgetHubEventsPageHeaderViewHolder.kt` — ref same name
5. `widgets/hubs/events/WidgetHubEventsPageFooterViewHolder.kt` — ref same name
6. `widgets/hubs/events/WidgetHubEventsPageAdapter.kt` — ref same name
7. `widgets/hubs/events/WidgetHubEventsPage.kt` — ref same name (fragment)

Data source: `RestAPIInterface.getDirectoryGuildScheduledEvents(channelId, type)` — already
declared — where `type` is `DirectoryEntryType.GuildScheduledEvent.key == 1`.

Also absent from the target and needed by the hub screens:

| Missing | Reference | Effect today |
| --- | --- | --- |
| `utilities/features/GrowthTeamFeatures` | `utilities/features/GrowthTeamFeatures` | `isMultiDomainEnabled()` has no source, so `VerifyEmail.allowMultipleGuilds` is hardcoded. Also the reason the chat-row wave records "hub e-mail prompt permanently hidden" |
| `utilities/hubs/HubUtilsKt` | `utilities/hubs/HubUtilsKt.kt` | hub-name/domain helpers inlined into `HubValidators.kt` / `HubGuildText.kt` |
| `api/guild/GuildHubType` + `GuildHubTypeTypeAdapter` | `api/guild/GuildHubType.kt`, `GuildHubTypeTypeAdapter.kt` | `Guild.hubType` cannot be typed; a hub guild is indistinguishable from a normal one |
| `analytics/utils/hubs/HubGuildScheduledEventClickType` | same path | no analytics on hub event CTAs (the whole `analytics/generated` package is absent — 28 hub-related event classes) |
| `stores/StoreDirectories` | `stores/StoreDirectories.kt` | see the directory spec, section 7 |

## 2. API endpoints (all already declared in `restapi/RestAPIInterface.kt`)

| Verb + path | Function | Body | Returns |
| --- | --- | --- | --- |
| `POST guilds/automations/email-domain-lookup` | `verifyEmail` | `RestAPIParams.VerifyEmail` | `Response<EmailVerification>` |
| `POST guilds/automations/email-domain-lookup/verify-code` | `verifyEmailCode` | `RestAPIParams.VerifyEmailCode` | `Response<EmailVerificationCode>` |
| `POST hub-waitlist/signup` | `joinHubWaitlist` | `RestAPIParams.HubWaitlist` | `WaitlistSignup` |

Bodies (constructor order is load-bearing — it is the reference's):

- `VerifyEmail(email: String, guildId: Long? = null, allowMultipleGuilds: Boolean = false, useVerificationCode: Boolean = true)`
- `VerifyEmailCode(email: String, code: String, guildId: Long?)`
- `HubWaitlist(email: String, school: String)`

Responses:

- `EmailVerification(guildsInfo: List<GuildInfo>, hasMatchingGuild: Boolean)`
- `GuildInfo(icon: String?, id: Long, name: String)`
- `EmailVerificationCode(guild: Guild, joined: Boolean)`
- `WaitlistSignup(email: String, emailDomain: String, school: String)`

Default-argument masks confirmed from the reference call sites (a `$default` mask is the set of
parameters that were *omitted*, so these prove the defaults rather than the passed values):

- `WidgetHubEmailViewModel.submitEmail` calls `VerifyEmail(email, ..., mask = 10)` -> bits 1 and 3 ->
  `guildId` and `useVerificationCode` defaulted, so `useVerificationCode` really does default to
  `true` and the first lookup carries no guild id.
- `WidgetHubAuthenticationViewModel.resendEmail` calls `VerifyEmail(email, guildId, multiDomain, mask = 8)`
  -> bit 3 only, so the resend carries the guild id chosen on the domain-picker screen.

## 3. School-email verification, end to end

`WidgetHubEmailFlow` **fails to decompile in Vineflower** (`NullPointerException` in
`KotlinWriter`, same failure class as `WidgetChatListAdapterItemEmbed`). Everything below is
derived from the two view models, the three state classes, and `WidgetHubEmailFlow$WhenMappings`
(which proves the screen switches on `HubEmailEntryPoint`). **The page-flip wiring inside that one
fragment is the only part of this flow that is not directly evidenced — check it on device.**

1. **Entry.** `WidgetHubEmailFlow` is opened with
   `HubEmailArgs(guildName, guildMemberCount, entryPoint)`. `entryPoint` selects the copy:
   `Onboarding` (registration funnel), `Invite` ("Join N others in the Student Hub for X" — the
   only case that reads `guildName`/`guildMemberCount`), `Default`.
2. **Email entry.** The user types a school email. The fragment holds it in the `EditText` only;
   the view model receives it as a method parameter.
3. **Domain lookup.** `WidgetHubEmailViewModel.submitEmail(email)` calls
   `POST guilds/automations/email-domain-lookup` with
   `VerifyEmail(email, allowMultipleGuilds = GrowthTeamFeatures.isMultiDomainEnabled())`.
   The result lands in `HubEmailState.verifyEmailAsync: RestCallState<EmailVerification>`.
4. **Branch on the response** — this is the whole state machine:
   - `guildsInfo` non-empty, so `HubEmailState.hasMultipleDomains` is true: push
     `WidgetHubDomains` (a `RadioSelectorItems` list of `DomainGuildInfo`), and on selection
     continue with `HubAuthenticationArgs(email, guildId = chosen)`. `WidgetHubDomainSearch` is
     the search variant of the same picker.
   - `hasMatchingGuild == true` with one domain: straight to `WidgetHubAuthentication` with
     `HubAuthenticationArgs(email, guildId = null)` — the server already knows which hub.
   - no matching guild: the **waitlist** page. `WidgetHubWaitlist` collects the school name and
     posts `hub-waitlist/signup` with `HubWaitlist(email, school)`. The result comes back as
     `HubWaitlistResult` and is stored into `HubEmailState.waitlistResult`, which flips
     `WidgetHubEmailFlow` to its confirmation page. `WaitlistSignup.emailDomain` is echoed back by
     the server and is display-only.
5. **Code entry.** `WidgetHubAuthentication` shows a code field.
   `WidgetHubAuthenticationViewModel.verifyCode(code)` calls
   `POST guilds/automations/email-domain-lookup/verify-code` with
   `VerifyEmailCode(email, code, guildId)`. Result goes to `HubAuthenticationState.authenticationAsync`.
   `resendEmail()` re-issues the *lookup* call (there is no separate resend endpoint) and lands in
   a **separate** `resendEmailAsync` field, because a resend must show a toast without disturbing
   the code field's error state.
6. **Success.** `EmailVerificationCode(guild, joined)`. `joined == false` means the code was valid
   but the user is not yet in the hub, so the caller joins/selects `guild`. The screen finishes
   with `AuthenticationResult(guildId)` (a `MultipleDomainResult`, delivered back through the
   `intent_args_key` extra).
7. **After joining.** `WidgetHubAddName` prompts for a hub nickname
   (`HubAddNameArgs`/`AddNameState`); `WidgetHubAddServer` + `WidgetHubAddServerConfirmationDialog`
   + `WidgetHubDescription` are the add-a-server-to-the-hub flow, which is the write half of the
   directory cluster.

### Security requirements for this flow — MANDATORY

The email address and the verification code are the only secrets in this cluster. They must be
held **only** in: the live `EditText`, a method parameter, a view-model field, and the request
body. Specifically:

- Never `Log`/`AppLog` them, never put them in a breadcrumb.
- Never write them to `SharedPreferences` or a `Persister`.
- Never write them to `onSaveInstanceState`. **Related: read the do-not-migrate note in this file
  about `StatefulViews` caching watched view values — a `StatefulViews` registration on the email
  or code field would persist it into saved state. Do not register those fields.**
- Never include them in an analytics payload. All 28 generated hub analytics events exist in the
  reference; when `analytics/generated` is reconstructed, `TrackNetworkActionHubEmailVerify*`
  must carry the *status and latency* only, never the address or code.
- Every `toString` that could reach them must redact. Already correct in `RestAPIParams`
  (`VerifyEmail`, `VerifyEmailCode`, `HubWaitlist`) and in `HubAuthenticationArgs`.

**Two live defects found during this survey — fix them first:**

1. `widgets/hubs/HubEmailModels.kt:65` — `HubEmailState` is a `data class` with
   `val email: String?` and **no `toString` override**, so the compiler-generated `toString`
   prints the address. Add
   `override fun toString(): String = "HubEmailState(selectedGuildId=$selectedGuildId, waitlistResult=$waitlistResult, verifyEmailAsync=$verifyEmailAsync, email=<redacted>)"`.
2. `widgets/hubs/HubAuthenticationModels.kt:69` — `HubAuthenticationState` has the same problem
   with `val email: String?`. Same fix.

Both are one-line edits and neither changes `equals`/`hashCode`/`copy`. The reference does print
these values; redacting is a **deliberate divergence**, permitted by `AGENTS.md` ("Redact secrets
even if the reference generated `toString` did not").

Also note `HubAuthenticationArgs` is a `Parcelable` carrying the email, and the reference passes it
as a fragment argument — which the fragment manager *does* persist across process death.
Reproduced as-is because the code-entry screen cannot function without knowing which address it is
confirming; its `toString` is redacted and nothing else touches it. Flagged here so it is a
recorded decision rather than an oversight.

## 4. Confirmed enum ordinals

Confirmed from `<clinit>` in `javap -c -p -cp discord126.21-dex2jar.jar`. Vineflower prints enum
constants alphabetically, which is wrong and misleading for all of these.

`com.discord.models.hubs.DirectoryEntryCategory` — `(ordinal, key, titleRes, idealSize)`:

| Ordinal | Name | key | idealSize |
| --- | --- | --- | --- |
| 0 | `Home` | -1 | 70 |
| 1 | `Uncategorized` | 0 | default (`Int.MAX_VALUE`) |
| 2 | `SchoolClub` | 1 | 100 |
| 3 | `Class` | 2 | default |
| 4 | `StudySocial` | 3 | 50 |
| 5 | `SubjectMajor` | 4 | default |
| 6 | `Misc` | 5 | default |
| 7 | `HQSocial` | 1 | default |
| 8 | `HQErgs` | 2 | default |
| 9 | `HQMisc` | 3 | default |
| 10 | `HQArchives` | 5 | default |

The target's `models/hubs/DirectoryEntryCategory.kt` **already matches this exactly** — verified,
do not "fix" it. Note the deliberate key collision between the school and HQ sets; `findByKey`
disambiguates with the `isHQ` flag, never globally.

`com.discord.api.directory.DirectoryEntryType`: `Guild(key=0)` is ordinal 0,
`GuildScheduledEvent(key=1)` is ordinal 1. Target matches.

`com.discord.widgets.hubs.HubEmailEntryPoint`: the Vineflower `<clinit>` assigns `Onboarding`,
then `Invite`, then `Default`, but that is register-allocation order, not ordinal order — the
`$VALUES` array it builds is `arrayOf(Onboarding, Invite, Default)`. **This enum's ordinals were
not independently confirmed at bytecode level and nothing load-bearing depends on them** (it is
never parcelled by ordinal — `HubEmailArgs` writes `entryPoint.name`). The target declares
`Default, Invite, Onboarding`; if a future change starts depending on the ordinal, confirm it
against `<clinit>` first.

`com.discord.utilities.directories.DirectoryUtils.DirectoryServerMenuOptions`:
`Edit(0)`, `Remove(1)`, `Report(2)` — proven by the reference's own
`when (option.ordinal) { 0 -> edit; 1 -> remove; 2 -> report }` dispatch.

---

# Directory — build spec

Same session, same reference roots. **The directory cluster is substantially built**; this section
records what was verified, what was deliberately left out, and what a fresh agent must still do.

## 5. What exists in the target

All 19 files under `widgets/directories/` plus `api/directory/DirectoryEntry.kt`,
`models/hubs/DirectoryEntryCategory.kt`, and
`views/directories/{ServerDiscoveryItem,ServerDiscoveryHeader}.kt`. Every layout the cluster
inflates exists: `widget_directory_channel.xml`, `widget_directory_category.xml`,
`widget_directories_search.xml`, `directory_entry_list_item.xml`,
`directory_add_server_list_item.xml`, `directory_section_header_list_item.xml`,
`directory_channel_tab.xml`. All eight REST endpoints exist. `GuildPreview` carries
`featurableInDirectory`, `approximateMemberCount`, `approximatePresenceCount`.
`Channel.isDirectory()` and `Channel.isHQDirectoryChannel()` exist in `api/channel/ChannelUtils.kt`.

Target to reference mapping:

| Target | Reference |
| --- | --- |
| `api/directory/DirectoryEntry.kt` | `api/directory/{DirectoryEntryType,DirectoryEntryGuild,DirectoryEntryEvent}.kt` |
| `widgets/directories/DirectoryUtils.kt` | `utilities/directories/DirectoryUtils.kt` + `DirectoryUtilsKt.kt` (**mislocated — see section 7**) |
| `widgets/directories/DirectoriesStore.kt` | `stores/StoreDirectories.kt` (**stand-in — see section 7**) |
| everything else under `widgets/directories/` | the same-named file under `widgets/directories/` |

## 6. Endpoints and permission gating for add/remove server

| Verb + path | Function | Notes |
| --- | --- | --- |
| `GET channels/{channelId}/directory-entries` | `getDirectoryEntries` | the browse list |
| `GET channels/{channelId}/directory-entries?type=1` | `getDirectoryGuildScheduledEvents` | the hub events page |
| `GET channels/{channelId}/directory-entries/counts` | `getEntryCounts` | `Map<categoryKey, count>`, drives the tab strip |
| `GET channels/{channelId}/directory-entries/search` | `searchServers` | `?query=` |
| `POST channels/{channelId}/directory-entry/{guildId}` | `addServerToHub` | body `AddServerBody(description, primaryCategoryId)` |
| `PATCH channels/{channelId}/directory-entry/{guildId}` | `modifyServerInHub` | same body; the "edit listing" action |
| `DELETE channels/{channelId}/directory-entry/{guildId}` | `removeServerFromHub` | |
| `GET guilds/{guildId}/directory-entries/broadcast` | `getDirectoryEntryBroadcastInfo` | scheduled-event broadcast, unused by this cluster |

**Permission gating, as implemented and as the reference does it:**

- *May I add a server to this hub?* `PermissionUtils.can(Permission.SEND_MESSAGES, permissionsByChannel[directoryChannelId])`.
  Adding a listing posts into the directory channel, so it is the channel's send permission, not a
  guild-level one. Drives `ViewState.hasAddGuildPermissions`, which gates the `AddServer` row
  (`WidgetDirectoryCategory.categoryRows`), the empty-state "Add your server" card, and the copy of
  the search screen's no-results state.
- *May I edit or remove this listing?* `PermissionUtils.can(Permission.ADMINISTRATOR, guildPermissions[listedGuildId])`,
  i.e. am I an admin **of the listed server**, not of the hub. Computed into
  `ViewState.adminGuildIds` and carried per-row as `DirectoryEntryData.hasEditPermissions`, which
  is what `onOverflowClicked(..., isServerOwner)` receives.
- The long-press menu is therefore `[Edit, Remove]` for an admin of the listed server and
  `[Report]` for everyone else — never a mix. Confirmed against
  `utilities/directories/DirectoryUtils.SERVER_OWNER_MENU_OPTIONS` /
  `NON_SERVER_OWNER_MENU_OPTIONS`.
- `Remove` always shows a `SimpleConfirmationDialog` first
  (`hub_entry_remove` / `hub_entry_remove_body` with a `{guildName}` hook).

Ranking helpers (reference `utilities/directories/DirectoryUtilsKt`): `MINIMUM_MEMBER_COUNT = 5`,
`MINIMUM_SIZE = 5`, `MAXIMUM_SIZE = 200`, `MAX_RECOMMENDATION_COUNT = 5`,
`MAX_HOME_CATEGORY_COUNT = 5`. `sortByAdded` (the "Newest" section) filters
`featurableInDirectory == true && memberCount >= 5`, sorts by `createdAt` descending, takes 5.
`sortByRanking`/`rank` (distance from the category's `idealSize`) is **not ported** — nothing in
the built surface calls it; port it when the recommendations rail is built.

## 7. Directory work still to do, in order

1. **`stores/StoreDirectories.kt` does not exist.** `widgets/directories/DirectoriesStore.kt`
   declares the interface the directory screens need and `RestDirectoriesSource` is an explicitly
   temporary REST-only stand-in (no `Dispatcher` confinement, no `snapshotData`, no `Persister`,
   no gateway invalidation). `widgets/hubs/HubStoreContracts.kt` declares the *other* half of the
   same store for the hub screens. **They are two halves of one reference store**
   (`stores/StoreDirectories.kt`); build it, publish it on `StoreStream`, and delete both
   stand-ins plus `DirectoryStores.install`.
2. **`utilities/directories/` package does not exist.** Move `sortByAdded`,
   `hasMinimumMemberCount`, `DirectoryServerOptions` and the option enum out of
   `widgets/directories/DirectoryUtils.kt` into `utilities/directories/DirectoryUtils.kt` +
   `DirectoryUtilsKt.kt`, and add the unported `rank` / `sortByRanking`.
3. **`Channel.DIRECTORY` (type 14) is still a live dead-end.** `docs/parity-audit/README.md`
   records that directory rows open the ordinary chat surface. `WidgetDirectoryChannel` now
   exists, but routing a selected directory channel to it lives in `widgets/home` /
   `widgets/channels`, which this agent did not own. See the cross-file request below.
4. `DirectoryNavigation` (in `widgets/directories/DirectoryUtils.kt`) has five `null`-by-default
   hooks; each affordance is hidden while its hook is null, so nothing leads nowhere. Assign them
   from the owning packages: `addServerToHub` and `editHubEntry` from `widgets/hubs`,
   `inviteToHub` from `widgets/guilds/invite`, `reportHubEntry` from `widgets/mobile_reports`,
   `promptForHubName` from `widgets/hubs`.
5. `DirectoryServerOptions` uses a `MaterialAlertDialogBuilder` chooser because Discord's shared
   "simple actions" bottom sheet does not exist in the target. Swap when it lands — same option
   set, same ordering, same destructive confirmation.
6. `WidgetDirectoryChannel` renders Loading and Error inside the *empty* container, because
   `widget_directory_channel.xml` carries no separate spinner or error view and inventing resource
   ids is not allowed. Revisit if a recovered layout variant turns out to have them.
7. `contextPropertiesOf` in `widgets/directories/DirectoryUtils.kt` is a port of the reference's
   `RestAPI.jsonObjectOf`; it belongs on `utilities/rest/RestAPI`.
8. `computeShortName` in the same file is a port of
   `utilities/guilds/GuildUtilsKt.computeShortName`; `models/guild/Guild.shortName` only performs
   the first of its three steps and cannot be reused. Converge them in `utilities/guilds/`.
9. `HQ_DIRECTORY_CHANNEL_ID` is duplicated as a private const in
   `WidgetDirectoriesSearch.companion` and in `api/channel/ChannelUtils.kt`. Promote the
   `ChannelUtils` one to `internal` and delete the copy.
10. No tests cover this cluster. The cheapest meaningful ones: `sortByAdded` (filter, order, cap),
    `DirectoryEntryCategory.findByKey` across the key collision, and the
    `hasAddGuildPermissions` / `adminGuildIds` derivation in
    `WidgetDirectoriesViewModel.buildViewState`.

## Hubs and directories — cross-file requests

| File | Symbol | Exact edit |
| --- | --- | --- |
| `widgets/home/WidgetHome.kt` (or wherever the centre panel picks a fragment for the selected channel) | centre-panel fragment selection | When the selected channel satisfies `com.discord.api.channel.isDirectory()`, show `com.discord.widgets.directories.WidgetDirectoryChannel` instead of the chat fragment. This closes live dead-end #2 in `docs/parity-audit/README.md` |
| `app/DeepLinkParser.kt` and its route handler | `/discovery` route | Route to `WidgetDirectoriesSearch` via `WidgetDirectoriesSearch.discoveryIntent()` (already written). Currently parsed and dropped — live dead-end #3 |
| `stores/StoreStream.kt` | new accessor | Add `getDirectories(): StoreDirectories` once item 1 above lands, then delete `DirectoryStores` and the hub-side stand-in in `widgets/hubs/HubStoreContracts.kt` |
| `utilities/features/GrowthTeamFeatures` (does not exist) | `isMultiDomainEnabled()` | Needed by `WidgetHubEmailViewModel` and `WidgetHubAuthenticationViewModel` for `VerifyEmail.allowMultipleGuilds`; currently hardcoded |
| `api/guild/Guild.kt` (+ `TypeAdapterRegistrar`) | `hubType: GuildHubType` | Create `api/guild/GuildHubType` + its type adapter (the reference has both) so a hub guild is distinguishable; the guild sidebar's `DiscordHubViewHolder` sparkle state and the hub-only menu items depend on it |
| `widgets/mobile_reports/WidgetMobileReports` | `launchDirectoryServerReport(context, guildId, entityId, channelId)` | Assign `DirectoryNavigation.reportHubEntry` to it; until then the Report menu item is hidden |
| `widgets/guilds/invite/WidgetGuildInviteShare` | — | Assign `DirectoryNavigation.inviteToHub`; until then the empty-state "Invite friends" card is hidden |

## Media viewer — corrections and status, 2026-07-27 (coordinator)

**FIXED — the image-tap bug the user reported.** `WidgetChatListAdapterItemEmbed.kt` dropped the
`else` branch of the embed image click listener. `EmbedResourceUtils.getExternalOpenUrl()` returns
non-null only for `VIDEO` and `FILE`, so every actual image — pasted image link, `GIFV`, `RICH`,
`LINK`, `ARTICLE` — fell through to an empty lambda and the tap did nothing. Reference (JADX
`WidgetChatListAdapterItemEmbed.java:568-578`) calls `WidgetMedia.launch(context, embed)` there.
`WidgetMedia` was already imported. Applied; needs device confirmation.

**CORRECTION — `EmbedType` IS registered with Gson.** The media agent reported it as unregistered
and filed that as blocking verification of the video-embed path. It looked at
`TypeAdapterRegistrar.kt:42-62`; the registration is at **line 113**
(`.registerTypeAdapter(EmbedType::class.java, EmbedTypeTypeAdapter())`), with both imports at
53-54 and `api/message/embed/EmbedTypeTypeAdapter.kt` present. **Do not "fix" this — there is
nothing to fix.** Ignore that followups item.

Still genuinely open, and the media agent's other findings do stand:
- `EmbedType` ordinals 2 and 7 (assumed `VIDEO` and `GIFV` by `WidgetMedia.launchArgs(embed)`) were
  taken from the decompiler, not `<clinit>`. Confirm with `javap -c` before trusting the video path.
- **There is no media pager in 126.21.** `WidgetMedia` is a single-media fragment; there is no
  adapter and no attachment-list-plus-selected-index contract. Do not build one. Its full intent
  contract (6 extras) is in the media agent's handoff.
- `WidgetChatListAdapterItemAttachment` is correct and was explicitly cleared — do not re-audit it.
- `utilities/intent/IntentUtils.kt` does not exist; `WidgetMedia.performChooserSendIntent` inlines
  it. That is the only genuine gap left in the media package.

## Mobile reports — cross-file requests and one resolved non-issue, 2026-07-27

### RESOLVED — `api/report/InAppReportsMenu.kt` field naming is CORRECT. Do not change it.

The mobile-reports agent flagged that the reference declares five of these fields snake_case
(`root_node_id`, `success_node_id`, `postback_url`, `menu_name`, `should_submit_data`) while our
target uses camelCase, and worried the wire names would not match — which would silently leave
`rootNodeId` at 0 and kill the whole flow with `ViewState.Invalid`. It could not locate the Gson
policy and asked whoever resumed to check.

Checked: `restapi/RestAPIBuilder.kt:54` sets `FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`. Our
`rootNodeId` therefore serialises to `root_node_id` — the same wire name the reference's own
`root_node_id` field produces under the same policy (already lowercase-with-underscores, so the
policy leaves it unchanged). The reference's inconsistency is Kotlin style in the original source,
not a second naming policy. `RestAPIParams.kt:41` already documents this convention for the project.

**No `@SerializedName` is needed and no rename is needed.** Ignore that item.

### Scope corrections — these change what should be built

- **"Report a message" is dead in 126.21.** Grepping the reference for `WidgetMobileReports` yields
  exactly three call sites: stage channel (`WidgetVoiceSettingsBottomSheet`), directory server
  (`DirectoryUtils.showServerOptions`), and scheduled event
  (`WidgetGuildScheduledEventDetailsExtrasBottomSheet`). `WidgetChatListActions` inflates
  `R.id.dialog_chat_actions_report` but never configures or clicks it — the row is inert in the
  shipping build. **Keep it inert.** Build `MobileReportArgs.Message` and the `ReportType.Message`
  submit branch anyway; both are fully implemented in the reference.
- **There is no "report a user" or "report a guild" report type.** `ReportType` has exactly four
  values. Blocking a user is an *element* inside a report, not a report type.
- **There is no free-text-entry node type.** No `text_input`/`textarea` element exists in 126.21.
  Node text comes from the node's own `header`/`subheader`/`info` strings.
- **There is only ONE node type.** `ReportNode` has no `type` field; variation comes entirely from
  which `elements[].type` strings are present. `ViewState.Menu.genNodeState()` is the whole mapping.
- **No report store is needed.** The reference has none; the ViewModel calls `RestAPI` directly and
  composes existing stores.
- `ReportsFeatureFlag` has zero callers — reconstruct for structural parity, do not wire it.

### Cross-file requests — three inert entry points, each owned by another agent

All three target files already carry a comment noting the missing launcher.

1. `widgets/voice/sheet/WidgetVoiceSettingsBottomSheet.kt` (see its comment at line 134) — in the
   report menu-item click handler call
   `WidgetMobileReports.launchStageChannelReport(requireContext(), viewState.channel.id)`.
   Ref: `widgets/voice/sheet/WidgetVoiceSettingsBottomSheet.java:211-214`.
2. `widgets/guildscheduledevent/WidgetGuildScheduledEventDetailsExtrasBottomSheet.kt` (comment at
   line 120) — call
   `WidgetMobileReports.launchGuildScheduledEventReport(requireContext(), event.guildId, event.id)`.
   Ref: `.../WidgetGuildScheduledEventDetailsExtrasBottomSheet.java:188-191`.
3. `widgets/directories/DirectoryUtils.kt` (comment at line 30) — in `showServerOptions` call
   `WidgetMobileReports.launchDirectoryServerReport(context, entry.guild.id,
   entry.directoryChannelId, entry.channelId)`.
   Ref: `utilities/directories/DirectoryUtils$showServerOptions$2.java:66`.

**Security:** the submission body carries message, channel and guild ids, and `MenuAPIResponse` /
`ReportNode` are plain data classes whose generated `toString` would dump the entire node graph.
Never pass any of them to a logger. The reference does not, and neither should we.
