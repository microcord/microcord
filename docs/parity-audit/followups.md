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
