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

## Reference bugs reproduced verbatim — DECISION NEEDED

Preserved for parity and commented in code, but these are genuine defects in the
reference, not intentional behavior. Parity argues for keeping them; correctness
argues for fixing them. Flagged for an explicit call rather than settled quietly.

| Reference bug | Location | Risk if kept |
| --- | --- | --- |
| `SparseMutableList`'s iterator uses `nextIndex() < lastIndex`, so it **never visits the final element** | `utilities/collections/SparseMutableList` | Makes `contains`/`remove` unreliable for the last slot. The reference presumably survived because its callers never hit that path; our reconstruction's call sites may differ |
| `Histogram.getReport` computes the running percentage with integer division | `utilities/collections/Histogram` | Wrong percentages in diagnostics only — low risk |

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
