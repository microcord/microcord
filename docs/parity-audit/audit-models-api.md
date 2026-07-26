# Parity Audit — models / api / restapi / gateway / nullserializable

- **TARGET**: `C:\Users\manti\Desktop\projeler\manticordrebuild` (Kotlin reconstruction)
- **REFERENCE**: `C:\Users\manti\StudioProjects\discord-jadx` (JADX decompile, Discord Android 126.21)
- Date: 2026-07-26. Read-only audit; no target file was modified.
- Method: reference non-synthetic `.java` files (`$`-named excluded) matched by simple class name against every
  `class`/`interface`/`object`/`enum class` declared anywhere in the target's `models/`, `api/`, `restapi/`,
  `gateway/`, `nullserializable/` trees (target consolidates many reference files into one `.kt`, so
  file-count comparison alone would be misleading).

---

## 1. Coverage

### 1.1 Totals

| Tree | Reference classes | Present in target | Missing | Coverage |
|---|---|---|---|---|
| `com/discord/api/**` | 286 | 158 | 128 | **55.2 %** |
| `com/discord/models/**` | 149 | 59 | 90 | **39.6 %** |
| `com/discord/restapi/**` | 10 | 6 | 4 | **60.0 %** |
| `com/discord/gateway/**` | 12 | 7 | 5 | **58.3 %** |
| **Total** | **457** | **230** | **227** | **50.3 %** |

`nullserializable/` — reference has 2 (`NullSerializable`, `NullSerializableTypeAdapterFactory`), target has
both: **100 %**.

### 1.2 Per-subpackage

```
api/activity/            13/14   api/message/attachment/   2/3    models/application/          0/1
api/application/          6/9    api/message/call/         1/1    models/authentication/       1/1
api/auth/                 3/5    api/message/embed/        9/11   models/botuikit/             7/11
api/auth/mfa/             0/9    api/message/reaction/     3/3    models/commands/             1/13
api/botuikit/            15/16   api/message/role_subscr.  1/1    models/deserialization/gson/ 0/1
api/botuikit/gson/        1/2    api/permission/           2/3    models/domain/              25/65
api/bugreport/            0/2    api/premium/              2/8    models/domain/auth/          2/2
api/channel/              3/5    api/presence/             3/3    models/domain/billing/       1/4
api/commands/             1/15   api/report/               7/15   models/domain/emoji/         1/6
api/connectedaccounts/    4/4    api/requiredaction/       0/1    models/domain/premium/       0/1
api/creatormonetization/  0/2    api/role/                 5/5    models/domain/spotify/       0/3
api/directory/            0/3    api/science/              0/3    models/embeddedactivities/   1/1
api/embeddedactivities/   2/3    api/stageinstance/        3/6    models/experiments/domain/   1/3
api/emoji/                1/2    api/sticker/              7/8    models/experiments/dto/      7/7
api/fingerprint/          1/1    api/thread/               3/10   models/friendsuggestions/    1/1
api/forum/                0/4    api/user/                11/13   models/gifpicker/domain/     0/1
api/friendsuggestions/    7/9    api/utcdatetime/          2/2    models/gifpicker/dto/        0/6
api/guild/               10/12   api/voice/server/         0/1    models/guild/                2/2
api/guild/preview/        0/1    api/voice/state/          2/3    models/hubs/                 0/2
api/guild/welcome/        2/3    gateway/                  3/4    models/invite/               0/2
api/guildhash/            2/2    gateway/io/               3/4    models/ktx/                  0/1
api/guildjoinrequest/     5/5    gateway/opcodes/          1/1    models/member/               1/1
api/guildmember/          3/4    gateway/rest/             0/3    models/message/              1/2
api/guildrolesubscription 0/18   restapi/                  5/8    models/phone/                1/1
api/guildscheduledevent/  7/14   restapi/utils/            1/2    models/presence/             1/1
api/handoff/              0/2                                     models/requiredaction/       1/1
api/hubs/                 0/4                                     models/sticker/dto/          0/3
api/interaction/          2/4                                     models/store/dto/            0/2
api/localizedstring/      0/2                                     models/thread/dto/           1/1
api/message/              3/5                                     models/user/                 3/3
api/message/activity/     2/3
```

### 1.3 Subpackages at **0 % coverage** (25)

`api/auth/mfa` (9), `api/bugreport` (2), `api/creatormonetization` (2), `api/directory` (3), `api/forum` (4),
`api/guild/preview` (1), `api/guildrolesubscription` (18), `api/handoff` (2), `api/hubs` (4),
`api/localizedstring` (2), `api/requiredaction` (1), `api/science` (3), `api/voice/server` (1),
`gateway/rest` (3), `models/application` (1), `models/deserialization/gson` (1), `models/domain/premium` (1),
`models/domain/spotify` (3), `models/gifpicker/domain` (1), `models/gifpicker/dto` (6), `models/hubs` (2),
`models/invite` (2), `models/ktx` (1), `models/sticker/dto` (3), `models/store/dto` (2).

Notes on near-misses:
- `models/deserialization/gson` — reference class is `InboundGatewayGsonParser`; the target's
  `TypeAdapterRegistrar.kt` (`app/src/main/java/com/discord/models/deserialization/gson/TypeAdapterRegistrar.kt`)
  is a partial functional stand-in but does not provide `InboundGatewayGsonParser`'s
  `fromJson(JsonReader, Class)` / `toJson` API nor its Gson field-naming configuration surface.
- `api/report` — the target implements these classes under package **`com.discord.api.reporting`**
  (`app/src/main/java/com/discord/api/reporting/InAppReportsMenu.kt:1`), not `com.discord.api.report`.
  **MAJOR** package-name divergence (breaks any reference-matching import).
- `api/voice/server/VoiceServer` is missing while `Opcode.VOICE_SERVER_PING` exists — see §4.

### 1.4 Missing reference classes (227)

api/: `ActivityTimestampsKt`, `ApplicationAsset`, `ApplicationKt`, `ApplicationTypeAdapter`, `OAuthScope`,
`OAuthScopeTypeAdapter`, `BackupCode`, `DisableMfaRequestBody`, `DisableMfaResponse`, `EnableMfaResponse`,
`GetBackupCodesRequestBody`, `GetBackupCodesResponse`, `GetBackupCodesSendVerificationKeyRequestBody`,
`GetBackupCodesSendVerificationKeyResponse`, `GetBackupCodesVerificationRequestBody`, `ComponentUtils`,
`ComponentRuntimeTypeAdapter`, `BugReportConfig`, `Feature`, `ChannelRecipient`, `ChannelUtils`,
`ApplicationCommand`, `ApplicationCommandAttachment`, `ApplicationCommandAutocompleteChoice`,
`ApplicationCommandAutocompleteResult`, `ApplicationCommandData`, `ApplicationCommandOption`,
`ApplicationCommandPermission`, `ApplicationCommandPermissionType`,
`ApplicationCommandPermissionTypeTypeAdapter`, `ApplicationCommandType`, `ApplicationCommandValue`,
`CommandChoice`, `CommandTypeAdapter`, `GuildApplicationCommands`,
`CreatorMonetizationEligibilityRequirements`, `CreatorMonetizationEnableRequest`, `DirectoryEntryEvent`,
`DirectoryEntryGuild`, `DirectoryEntryType`, `EmbeddedActivityInboundUpdate`, `GuildEmojisUpdate`,
`ForumPostFirstMessage`, `ForumPostFirstMessages`, `ForumUnread`, `ForumUnreads`, `AllowedInSuggestionsType`,
`AllowedInSuggestionsTypeAdapter`, `PruneCountResponse`, `VanityUrlResponse`, `GuildPreview`,
`GuildWelcomeChannelUtilsKt`, `PatchGuildMemberBody`, all 18 `api/guildrolesubscription/*`,
`ApiGuildScheduledEventUser`, `ApiGuildScheduledEventUserGuildMember`, `GuildRoleSubscriptionRoleBenefits`,
`GuildScheduledEventBroadcast`, `GuildScheduledEventBroadcastBody`, `GuildScheduledEventRsvpGuildMember`,
`GuildScheduledEventUserUpdate`, `CreateHandoffTokenRequest`, `HandoffToken`, `EmailVerification`,
`EmailVerificationCode`, `GuildInfo`, `WaitlistSignup`, `InteractionModalCreate`, `InteractionStateUpdate`,
`LocalizedString`, `LocalizedStringTypeAdapter`, `MessageFlags`, `MessageTypes`,
`MessageActivityTypeTypeAdapter`, `MessageAttachmentKt`, `EmbedTypeKt`, `EmbedTypeTypeAdapter`,
`PermissionKt`, `ClaimedOutboundPromotion`, `OutboundPromotion`, `PriceTierType`, `PriceTierTypeAdapter`,
`SubscriptionInterval`, `SubscriptionPlan`, `InAppReportsMenuKt`, `MenuAPIResponse`,
`ReportNodeBottomButtonTypeAdapter`, `ReportNodeChildTypeAdapter`, `ReportNodeElementDataTypeAdapter`,
`ReportReason`, `ReportSubmissionBody`, `ReportType`, `UserRequiredActionUpdate`, `AnalyticsSchema`,
`AnalyticsSchemaTypeAdapter`, `Science`, `RecommendedStageInstance`, `RecommendedStageInstanceGuild`,
`StageInstanceUtilsKt`, `GuildStickersUpdate`, `AugmentedThreadMember`, `ThreadListMember`, `ThreadListing`,
`ThreadMemberFlags`, `ThreadMemberListUpdate`, `ThreadMemberUpdate`, `ThreadMembersUpdate`, `PatchUserBody`,
`UserSettingsFriendDiscoveryFlags`, `VoiceServer`, `VoiceStateUtilsKt`.

gateway/: `GatewaySocketLogger`, `OutgoingPayload`, `RestApi`, `RestClient`, `RestConfig`.

restapi/: `BreadcrumbInterceptor`, `RestAPIAbortCodes`, `SpotifyTokenInterceptor`, `RetryWithDelay`.

models/: `Unread`, `ActionRowMessageComponentKt`, `ButtonMessageComponentKt`, `MessageComponentUtils`,
`SelectMessageComponentKt`, all 12 `models/commands/*` except `ApplicationCommandValue`,
`InboundGatewayGsonParser`, `Consent`, `Consents`, `Harvest`, `HasSubscriptionItems`, `Model`,
`ModelApplicationStreamPreview`, `ModelAppliedGuildBoost`, `ModelAuditLog`, `ModelAuditLogEntry`, `ModelBan`,
`ModelCall`, `ModelChannelFollowerStats`, `ModelChannelFollowerStatsDto`, `ModelConnectionAccessToken`,
`ModelConnectionState`, `ModelEmailChangeConfirm`, `ModelEntitlement`, `ModelGift`, `ModelGuildBoostSlot`,
`ModelGuildIntegration`, `ModelLibraryApplication`, `ModelMemberVerificationFormResponse`, `ModelOAuth2Token`,
`ModelRemoteAuthHandshake`, `ModelRtcLatencyRegion`, `ModelSearchResponse`, `ModelSku`, `ModelSkuKt`,
`ModelStoreListing`, `ModelUrl`, `ModelVoiceRegion`, `ModelWebhook`, `NonceGenerator`,
`NotificationSettingsModelUtils`, `PatchPaymentSourceRaw`, `PaymentSourceRaw`, `StreamCreateOrUpdate`,
`StreamDelete`, `StreamServerUpdate`, `StreamType`, `ModelInvoiceDiscount`, `ModelInvoiceItem`,
`ModelInvoicePreview`, `Emoji`, `EmojiCategory`, `EmojiSet`, `ModelEmojiCustom`, `ModelEmojiGuild`,
`SubscriptionPlanType`, `ModelSpotifyAlbum`, `ModelSpotifyArtist`, `ModelSpotifyTrack`, `Experiment`,
`ExperimentContext`, `ModelGifCategory`, all 6 `models/gifpicker/dto/*`, `DirectoryEntryCategory`,
`DirectoryEntryCategoryKt`, `InviteUtils`, `InviteUtilsKt`, `ModelUserExtensionsKt`, `MessageKt`,
`ModelStickerPack`, `ModelStickerPackStoreListing`, `ModelStickerStoreDirectory`, `ModelStoreAsset`,
`ModelStoreDirectoryLayout`.

**BLOCKER** subsets of the above (parsed by shipping code paths that the target's own gateway/store layer
already references or will need): `ChannelRecipient` (CHANNEL_RECIPIENT_ADD/REMOVE payload),
`ModelBan` (GUILD_BAN_ADD/REMOVE), `ModelCall` (CALL_CREATE/UPDATE/DELETE), `InteractionStateUpdate`
(INTERACTION_CREATE/SUCCESS/FAILURE), `EmbedTypeTypeAdapter` (every rich embed — see §5),
`ThreadMembersUpdate`/`ThreadMemberUpdate`/`ThreadListMember`/`AugmentedThreadMember` (all thread events),
`GuildEmojisUpdate`, `GuildStickersUpdate`, `ForumUnreads`, `Emoji`/`ModelEmojiCustom`/`ModelEmojiGuild`
(emoji picker + reactions), `MessageFlags`/`MessageTypes` (system-message rendering), `PermissionKt`
(permission bit helpers), `ChannelUtils`, `Model` (the reference streaming-parse contract).

### 1.5 Classes present in target but absent from the reference

`ChannelPinsUpdate`, `MessageReactionRemoveAll`, `MessageReactionRemoveEmoji`, `MessageDeleteEvent`,
`Hello`, `Identify`, `IdentifyClientState`, `IdentifyData`, `Resume`, `PresenceUpdate`, `GatewayEventRouter`,
`GatewayScheduler`, `RealGatewayScheduler`, `ComponentTypeAdapterFactory`, `GuildExperimentDtoTypeAdapter`,
`IntEnumTypeAdapter`, `ModelMuteConfigTypeAdapter`, `ModelNotificationSettingsTypeAdapterFactory`,
`ModelPayloadTypeAdapterFactory`, `MergedPresencesTypeAdapterFactory`, `NotificationSettingsV2`,
`PayloadJsonConverterFactory`, `RateLimitInterceptor`, `CookieJars`.

Most are legitimate re-materialisations of reference *nested* classes (`OutgoingPayload.Identify`,
`OutgoingPayload.Resume`, `OutgoingPayload.IdentifyClientState`, `ModelPayload.Hello`,
`ModelMessageDelete` → `MessageDeleteEvent`) or of Gson plumbing that JADX had inlined. Two are genuine
inventions with no reference basis:
- `api/channel/ChannelPinsUpdate.kt` — the string `"CHANNEL_PINS_UPDATE"` does not appear anywhere in the
  reference APK. **MINOR** (extra surface, not a parity gap).
- `MessageReactionRemoveAll` / `MessageReactionRemoveEmoji` — the reference parses
  `MESSAGE_REACTION_REMOVE_ALL` and `MESSAGE_REACTION_REMOVE_EMOJI` into
  `com.discord.api.message.reaction.MessageReactionUpdate` (see JADX warn comment
  `gateway/io/IncomingParser.java:174` and `:180`). Target splits into two new types.
  **MAJOR** — downstream store code keyed on `MessageReactionUpdate` will not receive these events.

---

## 2. Deep field-by-field comparison — core models

Legend: ✔ = identical name/type/nullability/order. Reference constructor order was recovered from the JADX
`toString()` builder chain (Kotlin data-class `toString` emits declaration order) plus the emitted
constructor signature; nullability from `m.checkNotNullParameter(...)` guards; defaults from the synthetic
`(..., int mask, DefaultConstructorMarker)` overload.

### 2.1 `api/channel/Channel` — **32/32 fields present, order identical**

Reference `app/src/main/java/com/discord/api/channel/Channel.java:110-142`
vs target `app/src/main/java/com/discord/api/channel/Channel.kt:9-42`.

| # | Reference | Target | Verdict |
|---|---|---|---|
| 1 | `String topic` | `topic: String?` | ✔ |
| 2 | `int type` | `type: Int` | ✔ |
| 3 | `long guildId` | `guildId: Long` | ✔ |
| 4 | `String name` | `name: String?` | ✔ |
| 5 | `long lastMessageId` | `lastMessageId: Long` | ✔ |
| 6 | `long id` | `id: Long` | ✔ |
| 7 | `long ownerId` | `ownerId: Long` | ✔ |
| 8 | `List<Long> recipientIds` | `List<Long>?` | ✔ |
| 9 | `List<User> recipients` | `List<User>?` | ✔ |
| 10 | `int position` | `Int` | ✔ |
| 11 | `List<PermissionOverwrite> permissionOverwrites` | `List<PermissionOverwrite>?` | ✔ |
| 12 | `int userLimit` | `Int` | ✔ |
| 13 | `int bitrate` | `Int` | ✔ |
| 14 | `String icon` | `String?` | ✔ |
| 15 | `long originChannelId` | `Long` | ✔ |
| 16 | `long applicationId` | `Long` | ✔ |
| 17 | `List<ChannelRecipientNick> nicks` | `List<ChannelRecipientNick>?` | ✔ |
| 18 | `boolean nsfw` | `Boolean` | ✔ |
| 19 | `long parentId` | `Long` | ✔ |
| 20 | `String memberListId` | `String?` | ✔ |
| 21 | `int rateLimitPerUser` | `Int` | ✔ |
| 22 | `Integer defaultAutoArchiveDuration` | `Int?` | ✔ |
| 23 | `String rtcRegion` | `String?` | ✔ |
| 24 | `Long flags` | `Long?` | ✔ |
| 25 | `transient GuildHashes guildHashes` (**non-final**, has `setGuildHashes`) | `@Transient val guildHashes: GuildHashes?` (**val**) | **MAJOR — mutability mismatch**: reference is `var`; `Channel.kt:34` must be `var`. The reference store rewrites this field in place after GUILD_CREATE hash reconciliation. |
| 26 | `List<ForumTag> availableTags` | `List<ForumTag>?` | ✔ |
| 27 | `List<Long> appliedTags` (`List<ForumTagId>`) | `List<Long>?` | ✔ (typealias lost — MINOR) |
| 28 | `ThreadMetadata threadMetadata` | `ThreadMetadata?` | ✔ |
| 29 | `Integer messageCount` (non-final) | `var messageCount: Int?` | ✔ |
| 30 | `transient Integer memberCount` | `@Transient val memberCount: Int?` | ✔ |
| 31 | `transient List<Long> memberIdsPreview` | `@Transient val memberIdsPreview: List<Long>?` | ✔ |
| 32 | `transient ThreadMember member` | `@Transient val member: ThreadMember?` | ✔ |

Companion constants: all 15 present and equal (`Channel.kt:44-58` vs `Channel.java:88-103`).

**Related types in the same package**
- `ChannelRecipientNick(id: Long, nick: String)` — ✔ (`Channel.kt:62`).
- `ForumTag` — reference (`ForumTag.java:11-16`) declares **five** backing fields:
  `customEmojiId`, `emojiId`, `emojiName`, `id`, `name`; `customEmojiId` is a *stored* body-level property
  (excluded from `equals`/`hashCode`/`toString`). Target (`Channel.kt:70`) makes it a computed
  `get()` with no backing field. **MINOR** — semantically equal on read, but not serialized/persisted.
- `ChannelRecipient(channelId: Long, nick: String, user: User)` — **MISSING** entirely.
  **BLOCKER**: `CHANNEL_RECIPIENT_ADD` / `CHANNEL_RECIPIENT_REMOVE` deserialize to this
  (`gateway/io/IncomingParser.java:100` JADX warn comment). Group-DM membership changes cannot be parsed.
- `ChannelUtils` — **MISSING** (display-name / sort-by-recency / sort-by-name-and-type helpers used by every
  channel list). **MAJOR**.

**"Domain channel model"**: in 126.21 there is no `models/…/Channel`; `com.discord.api.channel.Channel` *is*
the domain model (`models/domain/ModelChannel` was removed before this build). No gap.

### 2.2 `api/guild/Guild` — **46/46 fields present, order identical**

Reference `api/guild/Guild.java:27-77` vs target `api/guild/Guild.kt:16-63`. Every parameter of the
reference 46-arg constructor maps 1:1 in the same position with the same type and nullability:
`roles, emojis, stickers, name, description, defaultMessageNotifications, id, region, ownerId, icon,
verificationLevel, explicitContentFilter, presences, channels, members, voiceStates, unavailable, mfaLevel,
afkTimeout, afkChannelId, systemChannelId, features, memberCount, banner, splash, premiumTier,
premiumSubscriptionCount, systemChannelFlags, joinedAt, rulesChannelId, publicUpdatesChannelId,
preferredLocale, welcomeScreen, maxVideoChannelUsers, vanityUrlCode, approximateMemberCount,
approximatePresenceCount, permissions, guildHashes, channelUpdates, threads, nsfw, stageInstances,
guildScheduledEvents, embeddedActivities, hubType`. **No mismatches.**

`GuildFeature` enum: 29 reference constants vs 30 target — set difference is **empty** in both directions
(the extra target line is the closing `;`/trailing entry artefact of the count regex). ✔

### 2.3 `api/guildmember/GuildMember` — **13/13, order identical**

Reference `api/guildmember/GuildMember.java:30-45` vs target `api/guildmember/GuildMember.kt:7-21`.
`guildId: Long`, `user: User` (non-null, `checkNotNullParameter`), `roles: List<Long>` (non-null,
`checkNotNullParameter`), `nick: String?`, `premiumSince: String?`, `joinedAt: UtcDateTime?`,
`pending: Boolean`, `presence: Presence?`, `userId: Long?`, `avatar: String?`, `bio: String?`,
`banner: String?`, `communicationDisabledUntil: UtcDateTime?`. **No mismatches.**
`PatchGuildMemberBody` — **MISSING** (MAJOR; blocks member-edit REST call).

### 2.4 `api/role/GuildRole` — **11/11, order identical**

Reference `api/role/GuildRole.java:12-24` + `toString` chain vs target `api/role/GuildRole.kt:5-17`:
`position, name, id, color, hoist, permissions, mentionable, managed, icon, tags, unicodeEmoji`,
`: Serializable` on both. **No mismatches.**

`GuildRoleTags` — reference `api/role/GuildRoleTags.java:12-17`, order from `toString`:
`premiumSubscriber, botId, integrationId, skuId, subscriptionListingId, purchasableOrHasSubscribers`;
types `NullSerializable<Object>` for the two flag fields. Target `api/role/GuildRoleTags.kt:6-12` matches
exactly (`NullSerializable<Any>?`). ✔ — `Any` here is faithful to the reference `Object`, **not** a shortcut.

`GuildRoleCreate` / `GuildRoleUpdate` / `GuildRoleDelete` — present. ✔

### 2.5 `api/message/Message` — **33/33, order identical**

Reference `api/message/Message.java:33-69` vs target `api/message/Message.kt:19-53`:
`id, channelId, author, content, timestamp, editedTimestamp, tts, mentionEveryone, mentions, mentionRoles,
attachments, embeds, reactions, nonce, pinned, webhookId, type, activity, application, applicationId,
messageReference, flags, stickers, stickerItems, referencedMessage, interaction, thread, components, call,
guildId, member, hit, roleSubscriptionData`. All nullable-with-default in both. **No field mismatches.**

- **MINOR**: target overrides `toString()` to redact `content`/`nonce` (`Message.kt:54-55`); the reference
  emits the full data-class `toString` (`Message.java` toString chain lists every field). Behavioural
  divergence only.
- `MessageReference(guildId: Long?, channelId: Long?, messageId: Long?)` ✔ (`Message.kt:58`).
- **MISSING**: `api/message/MessageFlags`, `api/message/MessageTypes` — **BLOCKER** for the chat screen:
  every system-message variant and every flag check (ephemeral, crosspost, suppress-embeds, loading,
  has-thread) is driven by these two.

#### 2.5.1 Attachment
`api/message/attachment/MessageAttachment` — reference fields+order `url, size, id, proxyUrl, filename,
width, height` — target `attachment/MessageAttachment.kt:5-13` identical. ✔
`MessageAttachmentKt` (extension helpers) — **MISSING** (MINOR).

#### 2.5.2 Embeds — all field lists and orders identical
| Type | Reference order | Target | Verdict |
|---|---|---|---|
| `MessageEmbed` | title, type, description, url, timestamp, color, footer, image, thumbnail, video, provider, author, fields | `embed/MessageEmbed.kt:59-73` | ✔ |
| `EmbedImage` | url, proxyUrl, width, height | `MessageEmbed.kt:89` | ✔ |
| `EmbedThumbnail` | url, proxyUrl, height, width | `:90` | ✔ (note ref really is height-then-width here) |
| `EmbedVideo` | url, proxyUrl, height, width | `:91` | ✔ |
| `EmbedAuthor` | name, url, iconUrl, proxyIconUrl | `:92` | ✔ |
| `EmbedFooter` | text, iconUrl, proxyIconUrl | `:93` | ✔ |
| `EmbedProvider` | name, url | `:94` | ✔ |
| `EmbedField` | name, value, inline | `:95` | ✔ |
| `EmbedType` | ARTICLE, IMAGE, VIDEO, TWEET, LINK, HTML, FILE, GIFV, RICH, APPLICATION_NEWS, UNKNOWN + `apiValue` | `:75-87` | ✔ values |

- **BLOCKER**: `EmbedTypeTypeAdapter` (`api/message/embed/EmbedTypeTypeAdapter.java`) and `EmbedTypeKt`
  (the `EMBED_TYPES` array it scans) are **MISSING**, and `EmbedType` is **not registered** in
  `models/deserialization/gson/TypeAdapterRegistrar.kt:42-62`. Without it, Gson falls back to
  name-based enum matching, so wire values `"rich"`, `"image"`, `"link"`, `"gifv"`, `"video"`,
  `"article"` all resolve to **null** instead of the corresponding constant (reference behaviour:
  unknown → `EmbedType.UNKNOWN`, never null). Every embed in the chat list mis-renders.

#### 2.5.3 Reactions
`MessageReaction(count, emoji, me)` ✔ (`reaction/MessageReaction.kt:37-41`).
`MessageReactionEmoji(id: String?, name: String?, animated: Boolean)` ✔ (`:43-47`).
`MessageReactionUpdate` present. See §1.5 **MAJOR** about the two invented remove-all/remove-emoji types.

### 2.6 `api/user/User` — **22/22, order identical, defaults identical**

Reference `api/user/User.java:41-63` (default mask overload) vs target `api/user/User.kt:8-31`:
`id, username, avatar, banner, discriminator (= "0000"), publicFlags, flags, bot, system, token, email,
verified, locale, nsfwAllowed, mfaEnabled, phone, analyticsToken, premiumType, approximateGuildCount,
member, bio, bannerColor`. `NullSerializable<String>?` on `avatar`/`banner`/`bio`/`bannerColor` in both,
`: Serializable` on both, `"0000"` default confirmed at `User.java:44`. **No field mismatches.**
**MINOR**: target redacts `token`/`email`/`phone`/`analyticsToken` in `toString` (`User.kt:32-37`); reference
does not.

`models/user/CoreUser` — reference `models/user/CoreUser.java:12-23` order
`id, username, avatar, banner, isBot, isSystemUser, discriminator, premiumTier, flags, publicFlags, bio,
bannerColor` (types `long, String, String, String, boolean, boolean, int, PremiumTier, int, int, String,
String`). Target `models/user/CoreUser.kt:44-57` matches exactly. ✔
`models/user/MeUser` — reference order `id, username, avatar, banner, isBot, isSystemUser, discriminator,
premiumTier, email, mfaEnabled, isVerified, token, flags, publicFlags, phoneNumber, nsfwAllowance, bio,
bannerColor` + derived `hasBirthday`. Target `models/user/MeUser.kt:7-48` matches exactly (incl.
`hasBirthday` as a body-level `val`). ✔
`models/user/User` interface ✔. `api/user/PatchUserBody`, `api/user/UserSettingsFriendDiscoveryFlags` —
**MISSING** (MAJOR: user-settings screens).

### 2.7 `api/presence/Presence` + `api/activity/Activity`

`Presence` — reference `api/presence/Presence.java:20-27`: `status: ClientStatus` (non-null,
`checkNotNullParameter`), `activities: List<Activity>?`, `clientStatus: ClientStatuses?`, `user: User?`,
`userId: Long?`, `guildId: Long?`. Target `api/presence/Presence.kt:6-13` identical. ✔

`ClientStatuses(desktop, web, mobile)` — reference `ClientStatuses.java:10-14` declares
`desktop, mobile, web` alphabetically but the constructor is `(ClientStatus, ClientStatus, ClientStatus)`;
target `ClientStatuses.kt:3-7` uses `desktop, web, mobile` with private visibility and
`desktopStatus()/webStatus()/mobileStatus()` accessors. JADX did not emit a `toString` for this class, so the
**true reference constructor order cannot be confirmed from the decompile** — flagged as *unverified*, not as
a defect. If the reference order is `desktop, mobile, web`, target `ClientStatuses.kt:5-6` is transposed
(would be a **BLOCKER**, silently swapping web/mobile presence icons). Recommend confirming against the
smali/dex rather than the JADX Java.

`ClientStatus` — reference `api/presence/ClientStatus.java` has **no** `@SerializedName` on any constant and
no registered `TypeAdapter` anywhere in the APK (verified: `ClientStatus` appears in 21 files, none is an
adapter, and `b/a/b/a.java`'s registration list does not include it). Target adds
`@SerializedName("online"/"idle"/"dnd"/"invisible"/"offline")` (`api/presence/ClientStatus.kt:5-11`).
**MINOR — serialization-behaviour divergence** (the target's mapping is almost certainly the *correct* one;
the reference appears to rely on `name()` matching, which cannot match Discord's lowercase wire values).

`Activity` — reference `api/activity/Activity.java` order `name, type, url, createdAt, timestamps,
applicationId, details, state, emoji, party, assets, flags, syncId, sessionId, metadata, platform,
supportedPlatforms, buttons`; target `api/activity/Activity.kt:3-22` identical. ✔
`ActivityAssets(largeImage, largeText, smallImage, smallText)` ✔;
`ActivityParty(id, size: List<Long>?)` ✔; `ActivityTimestamps(start, end)` ✔;
`ActivityEmoji(id, name, animated)` ✔; `ActivitySecrets(match, join)` ✔;
`ActivityMetadata(contextUri, albumId, artistIds, buttonUrls)` ✔;
`ActivityType` values `PLAYING(0) STREAMING(1) LISTENING(2) WATCHING(3) CUSTOM_STATUS(4) COMPETING(5)
UNKNOWN(-1)` ✔. `ActivityTimestampsKt` — **MISSING** (MINOR).

`models/presence/Presence(status, clientStatuses, activities)` ✔ (`models/presence/Presence.kt:7-11`).

### 2.8 `api/permission/PermissionOverwrite` — 4/4 ✔, enum serialization differs

Reference `api/permission/PermissionOverwrite.java:24-29`: `(id: Long, type: Type, allow: Long, deny: Long)`,
`type` non-null. Target `api/permission/PermissionOverwrite.kt:5-10` identical order/types. ✔
Reference nested `enum Type { ROLE, MEMBER }` carries **no** `@SerializedName`
(`PermissionOverwrite.java:19-22`). Target adds `@SerializedName("0")` / `@SerializedName("1")`
(`PermissionOverwrite.kt:12-13`). **MINOR** serialization divergence (again the target is likely correcting a
reference defect, but it is a divergence under the stated contract).
`api/permission/PermissionKt` (the permission-bit helper functions) — **MISSING**. **BLOCKER**: every
"can I see / post in / manage this channel" check in the reference goes through it.

### 2.9 `api/voice/state/VoiceState` — **13/13, order identical**

Reference order (from `toString`): `userId, suppress, sessionId, selfMute, selfDeaf, selfVideo, selfStream,
mute, guildId, deaf, member, requestToSpeakTimestamp, channelId`. Target
`api/voice/state/VoiceState.kt:6-20` identical, same nullability (`sessionId: String?`, `member: GuildMember?`,
`requestToSpeakTimestamp: UtcDateTime?`, `channelId: Long?`). **No mismatches.**
`api/voice/state/VoiceStateUtilsKt` and `api/voice/server/VoiceServer` — **MISSING** (MAJOR; VOICE_SERVER_UPDATE
has no payload type).

### 2.10 READY payload — `models/domain/ModelPayload`

Reference `models/domain/ModelPayload.java:27-56` vs target `models/domain/ModelPayload.kt:23-73`.
All **25** fields present, same order, same types:
`v, me, userSettings, userGuildSettings, sessionId, relationships, readState, privateChannels, guilds,
guildJoinRequests, presences, analyticsToken, authToken, requiredAction, experiments (Map<Long,
UserExperimentDto>), guildExperiments, connectedAccounts, sessions, users, guildPresences
(List<List<Presence>>), guildMembers (List<List<GuildMember>>), countryCode, trace, userSurvey,
friendSuggestionCount`. `trace: Any?` mirrors the reference `Object trace` — **faithful, not a shortcut**.

Wire names: the target adapter (`ModelPayload.kt:202-236`) handles every reference key —
`v, user, user_settings, user_guild_settings, session_id, relationships, read_state, private_channels,
guilds, guild_join_requests, presences, merged_presences (friends/guilds), merged_members, analytics_token,
auth_token, required_action, experiments, guild_experiments, connected_accounts, sessions, users,
country_code, _trace, user_survey, friend_suggestion_count` — plus the nested `version`/`partial`/`entries`
of the versioned models. ✔

Nested types: `VersionedModel<T>` (`version`, `entries`, `isPartial`, `DEFAULT_VERSION = -1`) ✔,
`VersionedReadStates` ✔, `VersionedUserGuildSettings` ✔, `MergedPresences(friends, guilds)` ✔.

- **MAJOR — MISSING nested class `ModelPayload.Hello`** (`ModelPayload.java:58-118`, field
  `int heartbeatInterval`, wire key `"heartbeat_interval"`). The target relocated it to
  `gateway/io/GatewayPayloads.kt:17-20` as a top-level `Hello(heartbeatInterval, trace)`. Functionally
  covered, but the class no longer exists at `com.discord.models.domain.ModelPayload$Hello`, and the target
  version adds a `trace: List<String>?` field the reference `Hello` does not have.
- **MAJOR**: `models/domain/Model` (the reference streaming `Model`/`Model.JsonReader` contract that
  `ModelPayload` and ~40 other `models/domain` classes implement) is **MISSING**. The target replaced the
  whole hand-rolled streaming-parse layer with Gson tree parsing
  (`ModelPayload.kt:196-259` uses `JsonParser.parseReader` → `JsonObject`), which materialises the entire
  READY payload in memory. Behavioural divergence with real memory cost on large accounts.

---

## 3. REST contract

Reference interface: `app/src/main/java/com/discord/restapi/RestAPIInterface.java` (1127 lines).
Retrofit annotations are obfuscated in the decompile: `i0.f0.f`=GET, `i0.f0.o`=POST, `i0.f0.n`=PATCH,
`i0.f0.p`=PUT, `i0.f0.b`=DELETE, `i0.f0.h`=HTTP.

Target interface: `app/src/main/java/com/discord/restapi/RestAPIInterface.kt` (256 lines).

### 3.1 Counts

| | Reference | Target |
|---|---|---|
| Endpoint declarations | **282** | **47** |
| Unique (verb + path-with-normalised-placeholders) | **264** | **43** |
| GET | 97 | 14 |
| POST | 98 | 18 |
| PATCH | 40 | 4 |
| PUT | 15 | 4 |
| DELETE | 30 | 7 |
| HTTP (body-bearing DELETE) | 2 | 0 |

**Coverage: 42 / 264 unique reference endpoints = 15.9 %. 222 endpoints MISSING.**
Missing by verb: GET 83, POST 76, PATCH 28, DELETE 23, PUT 10, HTTP 2.

The single target endpoint with no `RestAPIInterface` counterpart is `GET "gateway"`
(`RestAPIInterface.kt:35`) — in the reference this lives on `com.discord.gateway.rest.RestApi`
(itself **MISSING** from the target). Not a defect, but a structural relocation.

### 3.2 Present (42)

```
DELETE channels/{}/messages/{}                          DELETE channels/{}/messages/{}/reactions
DELETE channels/{}/messages/{}/reactions/{}/@me         DELETE channels/{}/messages/{}/reactions/{}/{}
DELETE channels/{}/pins/{}                              DELETE friend-suggestions/{}
DELETE users/@me/relationships/{}                       GET    auth/location-metadata
GET    channels/{}                                      GET    channels/{}/messages
GET    channels/{}/messages/{}/reactions/{}             GET    channels/{}/pins
GET    friend-suggestions                               GET    users/@me/affinities/users
GET    users/@me/mentions                               GET    users/@me/notes/{}
GET    users/{}                                         GET    users/{}/profile
GET    users/{}/relationships                           PATCH  channels/{}/messages/{}
PATCH  users/@me/settings                               POST   auth/authorize-ip
POST   auth/fingerprint                                 POST   auth/forgot
POST   auth/login                                       POST   auth/logout
POST   auth/mfa/totp                                    POST   auth/register
POST   auth/verify/resend                               POST   channels/{}/messages
POST   channels/{}/messages/{}/ack                      POST   channels/{}/typing
POST   phone-verifications/resend                       POST   phone-verifications/verify
POST   users/@me/channels                               POST   users/@me/phone
POST   users/@me/relationships                          POST   users/@me/relationships/bulk
PUT    channels/{}/messages/{}/reactions/{}/@me         PUT    channels/{}/pins/{}
PUT    users/@me/notes/{}                               PUT    users/@me/relationships/{}
```

### 3.3 Missing (222) — grouped highlights

Whole feature areas with **zero** REST coverage in the target:

- **Guilds/CRUD**: `POST guilds`, `PATCH guilds/{}`, `DELETE guilds/{}`, `POST guilds/{}/channels`,
  `PATCH guilds/{}/channels`, `GET/POST/PATCH/DELETE guilds/{}/roles[/{}]`, `guilds/{}/members[/{}]`,
  `guilds/{}/bans[/{}]`, `guilds/{}/prune`, `guilds/{}/vanity-url`, `guilds/{}/emojis[/{}]`,
  `guilds/{}/stickers`, `guilds/{}/integrations`, `guilds/{}/webhooks`, `guilds/{}/templates`,
  `guilds/{}/welcome-screen`, `guilds/{}/preview`, `guilds/{}/regions`, `guilds/{}/audit-logs`,
  `guilds/{}/member-verification`, `guilds/{}/requests/@me`, `guilds/{}/ack`, `guilds/{}/premium/*`.
- **Channels**: `PATCH channels/{}`, `DELETE channels/{}`, `PUT channels/{}/permissions/{}`,
  `DELETE channels/{}/permissions/{}`, `channels/{}/recipients/{}`, `channels/{}/invites`,
  `channels/{}/followers`, `channels/{}/threads`, `channels/{}/thread-members/*`,
  `channels/{}/messages/search`, `channels/{}/call/*`, `channels/{}/webhooks`.
- **Threads/forums**: every endpoint (`threads/*`, `channels/{}/threads/*`, forum post APIs).
- **Application commands / interactions**: `interactions`, `applications/{}/commands`, autocomplete.
- **Stage instances**: `stage-instances[/{}]`, `stage-instances/recommended`.
- **Scheduled events**: `guilds/{}/scheduled-events[/{}]`, `/users`, `/rsvp`, `/broadcast`.
- **MFA**: `users/@me/mfa/totp/enable|disable`, `…/codes`, `…/codes-verification`.
- **Billing / premium / store / gifting**: `store/*`, `users/@me/billing/*`, `entitlements/*`,
  `outbound-promotions/*`, `subscriptions/*`, `users/@me/guilds/premium/*`.
- **Connections / OAuth**: `users/@me/connections/*`, `oauth2/*`, `auth/handoff`, `auth/sessions`,
  `remote-auth/*`.
- **Hubs / directories**: `hub-waitlist/*`, `directory-entries/*`, `channels/{}/directory-entry/*`.
- **Guild role subscriptions / creator monetization**: all of them.
- **Reporting / science / bug reports**: `reporting/*`, `science`, `bug-reports/*`.
- **Stickers, GIF picker, emoji directory**: `sticker-packs`, `stickers/*`, `gifs/*`.

The full machine-generated 222-line list is in
`…/scratchpad/missing_endpoints.txt` (same directory as this report).

### 3.4 `RestAPIParams`

Reference `restapi/RestAPIParams.java` declares **107** nested request-body classes; target
`restapi/RestAPIParams.kt` declares **27**, of which **17** match reference names.
**Coverage 17/107 = 15.9 %.** Missing (90): `ActivateMfaSMS, AddServerBody, ApplicationCommand,
AuthRegisterPhone, BanGuildMember, CaptchaCode, ChannelFollowerPost, ChannelPermissionOverwrites,
ChannelPosition, ChannelVoiceStateUpdate, ComponentInteraction, ConnectedAccount, ConnectedAccountContacts,
ConnectedAccountNameOnly, ConnectionState, Consents, ContactEntry, CreateGuild, CreateGuildChannel,
CreateGuildFromTemplate, CreateGuildRoleSubscriptionGroupListing, CreateGuildRoleSubscriptionTierListing,
CreateGuildScheduledEventBody, DeleteGuild, DeletePhone, DisableAccount, DisableGuildCommunication,
DowngradeSubscriptionBody, EnableIntegration, EnableMFA, GenerateGiftCode, GetForumPostData, GreetMessage,
GroupDM, GuildBoosting, GuildFeatureAck, GuildIntegration, GuildMFA, GuildMember, GuildMemberDisconnect,
HubWaitlist, Invite, InviteCode, InvoicePreviewBody, LeaveGuildBody, MemberVerificationForm,
ModalInteraction, ModalInteractionData, ModalInteractionDataComponent, Nick, OAuth2Authorize,
PatchGuildEmoji, Phone, PostGuildEmoji, PruneGuild, PurchaseMetadataBody, RemoteAuthCancel, RemoteAuthFinish,
RemoteAuthInitialize, Report, Ring, Role, StartStageInstanceBody, TextChannel, ThreadCreationSettings,
ThreadMemberSettings, ThreadSettings, Thumbnail, TopicalChannel, TransferGuildOwnership, UpdateGuild,
UpdateGuildRoleSubscriptionGroupListing, UpdateGuildRoleSubscriptionTierFreeTrial,
UpdateGuildRoleSubscriptionTierListing, UpdateGuildScheduledEventBody, UpdateStageInstanceBody,
UpdateSubscription, UploadContacts, UserAgreements, UserEmailConfirmCode, UserGuildSettings, UserInfo,
UserSettings, VanityUrl, VerificationCodeOnly, VerifyEmail, VerifyEmailCode, VerifyPurchaseResponse,
VerifyPurchaseTokenBody, VoiceChannel`. Severity **BLOCKER** for `Role`, `Nick`, `GuildMember`,
`ChannelPermissionOverwrites`, `ChannelPosition`, `UserGuildSettings`, `UserSettings`, `Invite`,
`ThreadCreationSettings`, `ComponentInteraction`/`ModalInteraction` — these back first-screen features.

Also missing from `restapi/`: `BreadcrumbInterceptor`, `SpotifyTokenInterceptor`, `RestAPIAbortCodes`
(the numeric error-code table used by every error toast), `utils/RetryWithDelay`. **MAJOR**.

---

## 4. Gateway

### 4.1 Opcodes — **21/21 present, values identical** ✔

`gateway/opcodes/Opcode.kt:3-24` reproduces the reference `Opcode.java` exactly:
`UNHANDLED(-1) DISPATCH(0) HEARTBEAT(1) IDENTIFY(2) PRESENCE_UPDATE(3) VOICE_STATE_UPDATE(4)
VOICE_SERVER_PING(5) RESUME(6) RECONNECT(7) REQUEST_GUILD_MEMBERS(8) INVALID_SESSION(9) HELLO(10)
HEARTBEAT_ACK(11) CALL_CONNECT(13) GUILD_SUBSCRIPTIONS(14) STREAM_CREATE(18) STREAM_DELETE(19)
STREAM_WATCH(20) OPCODE_STREAM_PING(21) REQUEST_GUILD_APPLICATION_COMMANDS(24) REQUEST_FORUM_UNREADS(28)`.

### 4.2 Opcodes the target never *sends* (declared but unused)

Reference `GatewaySocket.java` sends/handles: DISPATCH, GUILD_SUBSCRIPTIONS, HEARTBEAT, HEARTBEAT_ACK,
HELLO, IDENTIFY, INVALID_SESSION, RECONNECT, REQUEST_FORUM_UNREADS,
REQUEST_GUILD_APPLICATION_COMMANDS, RESUME, STREAM_CREATE — plus public methods
`callConnect`, `presenceUpdate`, `requestApplicationCommands`, `requestForumUnreads`, `requestGuildMembers`,
`streamCreate`, `streamDelete`, `streamPing`, `streamWatch`, `updateGuildSubscriptions`, `voiceServerPing`,
`voiceStateUpdate`, `expeditedHeartbeat`, `simulateReconnectForTesting`, `handleDeviceConnectivityChange`.

Target `gateway/GatewaySocket.kt` exposes only `connect`, `close`, `updateGuildSubscriptions` (`:123`),
`presenceUpdate` (`:127`), `isSessionEstablished` (`:140`). **MISSING senders** (each **MAJOR**, the voice
ones **BLOCKER** for any voice feature): `callConnect` (op 13), `voiceStateUpdate` (op 4), `voiceServerPing`
(op 5), `requestGuildMembers` (op 8), `streamCreate` (18), `streamDelete` (19), `streamWatch` (20),
`streamPing` (21), `requestApplicationCommands` (24), `requestForumUnreads` (28), plus
`expeditedHeartbeat`, `handleDeviceConnectivityChange`, `simulateReconnectForTesting`.

### 4.3 Outgoing payload types — 4/16

Reference `gateway/io/OutgoingPayload.java` is **MISSING** in the target; its 16 nested payload types are
partially re-expressed in `gateway/io/GatewayPayloads.kt`.

| Reference `OutgoingPayload.*` | Fields | Target |
|---|---|---|
| `Identify` | token, largeThreshold, compress, capabilities, properties `Map<String,Object>`, clientState | `GatewayPayloads.kt:29-38` ✔ (order + types match; `Map<String, Any>` faithful to reference `Map<String,Object>`) |
| `IdentifyClientState` | guildHashes `Map<Long,String[]>`, highestLastMessageId, readStateVersion, **`useruserGuildSettingsVersion`** | `GatewayPayloads.kt:22-27` — field renamed to `userGuildSettingsVersion` with `@SerializedName("user_guild_settings_version")`. **MAJOR wire-name mismatch**: the reference field is literally `useruserGuildSettingsVersion` (verified in the Kotlin `@Metadata` d2 array at `OutgoingPayload.java:442` and the backing field at `:448`), and the gateway Gson uses `FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`, so the real wire key is **`useruser_guild_settings_version`**. |
| `Resume` | token, sessionId, seq | `GatewayPayloads.kt:40-46` ✔ |
| `PresenceUpdate` | status, since, activities, afk | `GatewayPayloads.kt:48-53` ✔ |
| `ApplicationCommandRequest` | applications, commandIds, guildId, limit, nonce, offset, query | **MISSING** |
| `CallConnect` | channelId | **MISSING** |
| `CreateStream` | channelId, guildId, preferredRegion, type | **MISSING** |
| `DeleteStream` | streamKey | **MISSING** |
| `ForumUnreadsRequest` | channelId, guildId, threads | **MISSING** |
| `ForumUnreadsRequestThread` | ackMessageId, threadId | **MISSING** |
| `GuildMembersRequest` | guildId `List<Long>`, limit, presences, query, userIds | **MISSING** |
| `GuildSubscriptions` | activities, channels `Map<Long,List<List<Int>>>`, members, threadMemberLists, threads, typing | **MISSING** (target passes an opaque `GuildSubscriptionPayload` from the store layer at `GatewaySocket.kt:123`) |
| `GuildSubscriptionsUpdate` | + guildId | **MISSING** |
| `StreamPing` | streamKey | **MISSING** |
| `VoiceStateUpdate` | channelId, guildId, preferredRegion, selfDeaf, selfMute, selfVideo | **MISSING** |
| `VoiceStateUpdateNoPreferredRegion` | channelId, guildId, selfDeaf, selfMute, selfVideo | **MISSING** |

Reference `Outgoing` is non-generic `(int op, Object d)` (`gateway/io/Outgoing.java`); target uses
`Outgoing<T>(op: Int, d: T?)` (`GatewayPayloads.kt:15`). **MINOR** (shape-compatible on the wire).

### 4.4 Dispatch events — 39/83

> **JADX limitation:** `gateway/io/IncomingParser.java`'s `assignField` is **not decompilable**
> (`"Method dump skipped, instructions count: 1862 … Method not decompiled"`, `IncomingParser.java:255-262`).
> Only ~40 event names survive in the `JADX WARN: Code restructure failed` comments. The authoritative
> event enumeration was therefore taken from the reference dispatch table in
> `com/discord/stores/StoreGatewayConnection.java` (79 literal names + 4 referenced via
> `NotificationData.TYPE_*` constants = **83**).

Target dispatch table: `gateway/io/IncomingParser.kt:55-91` (39 names) — everything else falls through to
`else -> data.deepCopy()`, i.e. an untyped `JsonElement`.

**Reference events with NO target counterpart (44):**

| Event | Reference payload type | Severity |
|---|---|---|
| `CALL_CREATE` | `models.domain.ModelCall` | BLOCKER (calls) |
| `CALL_UPDATE` | `ModelCall` | BLOCKER |
| `CALL_DELETE` | `ModelCall` | BLOCKER |
| `CHANNEL_RECIPIENT_ADD` | `api.channel.ChannelRecipient` | BLOCKER (group DMs) |
| `CHANNEL_RECIPIENT_REMOVE` | `ChannelRecipient` | BLOCKER |
| `GUILD_BAN_ADD` | `models.domain.ModelBan` | MAJOR |
| `GUILD_BAN_REMOVE` | `ModelBan` | MAJOR |
| `INTERACTION_CREATE` | `api.interaction.InteractionStateUpdate` | BLOCKER (bot UI) |
| `INTERACTION_SUCCESS` | `InteractionStateUpdate` | BLOCKER |
| `INTERACTION_FAILURE` | `InteractionStateUpdate` | BLOCKER |
| `INTERACTION_MODAL_CREATE` | `api.interaction.InteractionModalCreate` | BLOCKER |
| `GUILD_JOIN_REQUEST_CREATE` | `api.guildjoinrequest.GuildJoinRequestCreateOrUpdate` | MAJOR |
| `GUILD_JOIN_REQUEST_UPDATE` | `GuildJoinRequestCreateOrUpdate` | MAJOR |
| `GUILD_JOIN_REQUEST_DELETE` | `GuildJoinRequestDelete` | MAJOR |
| `GUILD_EMOJIS_UPDATE` | `api.emoji.GuildEmojisUpdate` | BLOCKER (emoji picker) |
| `GUILD_STICKERS_UPDATE` | `api.sticker.GuildStickersUpdate` | MAJOR |
| `GUILD_INTEGRATIONS_UPDATE` | — | MINOR |
| `GUILD_APPLICATION_COMMANDS_UPDATE` | `api.commands.GuildApplicationCommands` | MAJOR |
| `APPLICATION_COMMAND_AUTOCOMPLETE_RESPONSE` | `ApplicationCommandAutocompleteResult` | MAJOR |
| `GUILD_SCHEDULED_EVENT_CREATE` | `api.guildscheduledevent.GuildScheduledEvent` | MAJOR |
| `GUILD_SCHEDULED_EVENT_UPDATE` | `GuildScheduledEvent` | MAJOR |
| `GUILD_SCHEDULED_EVENT_DELETE` | `GuildScheduledEvent` | MAJOR |
| `GUILD_SCHEDULED_EVENT_USER_ADD` | `GuildScheduledEventUserUpdate` | MAJOR |
| `GUILD_SCHEDULED_EVENT_USER_REMOVE` | `GuildScheduledEventUserUpdate` | MAJOR |
| `THREAD_MEMBER_UPDATE` | `api.thread.ThreadMemberUpdate` | BLOCKER (threads) |
| `THREAD_MEMBERS_UPDATE` | `api.thread.ThreadMembersUpdate` | BLOCKER |
| `THREAD_MEMBER_LIST_UPDATE` | `api.thread.ThreadMemberListUpdate` | BLOCKER |
| `VOICE_STATE_UPDATE` | `api.voice.state.VoiceState` | **BLOCKER — the type exists in the target but the event is not routed** |
| `VOICE_SERVER_UPDATE` | `api.voice.server.VoiceServer` | BLOCKER |
| `STREAM_CREATE` | `models.domain.StreamCreateOrUpdate` | MAJOR |
| `STREAM_UPDATE` | `StreamCreateOrUpdate` | MAJOR |
| `STREAM_DELETE` | `models.domain.StreamDelete` | MAJOR |
| `STREAM_SERVER_UPDATE` | `models.domain.StreamServerUpdate` | MAJOR |
| `EMBEDDED_ACTIVITY_UPDATE` | `api.embeddedactivities.EmbeddedActivityInboundUpdate` | MAJOR |
| `FORUM_UNREADS` | `api.forum.ForumUnreads` | MAJOR |
| `USER_CONNECTIONS_UPDATE` | (skipped → `Unit`) | MINOR |
| `USER_CONNECTIONS_INTEGRATION_JOINING` | (skipped → `Unit`) | MINOR |
| `USER_PAYMENT_SOURCES_UPDATE` | — | MINOR |
| `USER_SUBSCRIPTIONS_UPDATE` | — | MINOR |
| `USER_REQUIRED_ACTION_UPDATE` | `api.requiredaction.UserRequiredActionUpdate` | MAJOR (forced-action gate) |
| `GUILD_MEMBER_REMOVE` | present in target ✔ | — |
| `STAGE_INSTANCE_CREATE/UPDATE/DELETE` | present in target ✔ | — |

Target-only dispatch entry: `"CHANNEL_PINS_UPDATE"` (`IncomingParser.kt:85`) — no reference counterpart.

### 4.5 Other gateway gaps

- `gateway/GatewaySocketLogger` — **MISSING**; the target inlines `android.util.Log` calls
  (`GatewaySocket.kt:375-378`), so the reference's pluggable logger/`GatewaySocketLogger` breadcrumb
  contract is gone. **MINOR**.
- `gateway/rest/RestApi`, `RestClient`, `RestConfig` — **MISSING** (the gateway-discovery HTTP client).
  The target folds `GET gateway` into `RestAPIInterface`. **MAJOR** structural divergence.
- `GatewayEventHandler` — reference is a 4-method interface (`handleConnected`, `handleConnectionReady`,
  `handleDisconnect`, `handleDispatch`); target reproduces it at `gateway/GatewaySocket.kt:33-36`. ✔
- `GatewayDiscovery` — present ✔.

---

## 5. `Any` / raw-map / `JsonElement` / placeholder audit

Full grep of `models|api|restapi|gateway|nullserializable` for `: Any`, `Map<String, Any>`, `JsonElement`,
`JsonObject` in a value position. Results, classified:

**Faithful to the reference (NOT defects):**
| Site | Reference counterpart |
|---|---|
| `models/domain/ModelPayload.kt:68` `var trace: Any?` | `private Object trace;` (`ModelPayload.java:56`) |
| `gateway/io/GatewayPayloads.kt:34` `properties: Map<String, Any>` | `Map<String, Object> properties` (`OutgoingPayload.java`, `Identify`) |
| `gateway/io/GatewayPayloads.kt:12` `data: Any?` | `Object d` / `Object data` (`Outgoing.java`, `IncomingParser.java:13`) |
| `gateway/GatewaySocket.kt:36`, `GatewayEventRouter.kt:20` `handleDispatch(type, data: Any)` | `GatewayEventHandler.handleDispatch(String, Object)` |
| `api/role/GuildRoleTags.kt:7,12` `NullSerializable<Any>?` | `NullSerializable<Object>` (`GuildRoleTags.java:14-15`) |
| `models/domain/ModelMemberVerificationForm.kt:13` `var response: Any` | reference `ModelMemberVerificationForm$FormField.response` is also `Object` |
| `gateway/GatewaySocket.kt:70` `identifyProperties: Map<String, Any>` | same as `Identify.properties` |

**Actual untyped shortcuts (defects):**

| Severity | Site | Problem |
|---|---|---|
| **BLOCKER** | `gateway/io/IncomingParser.kt:91` `else -> data.deepCopy()` | 44 of 83 reference dispatch events fall into this branch and are delivered to the store layer as a raw `JsonElement` instead of the reference's typed payload (see §4.4). Every consumer downstream must either re-parse or ignore. The reference has **no** untyped fallback — `IncomingParser` assigns a concrete model for every known `t`. |
| **MAJOR** | `gateway/io/GatewayPayloads.kt:15` `Outgoing<T>(op: Int, d: T? = null)` with `d` unconstrained | Reference `Outgoing` takes `Object d`, but every call site constructs a typed `OutgoingPayload` subclass. Because the target has none of those 12 payload classes (§4.3), the type parameter is currently satisfied by ad-hoc objects/primitives (e.g. `send(Opcode.HEARTBEAT, sequence)` at `GatewaySocket.kt:274`). Not wrong on the wire, but the typed payload contract is absent. |
| **MAJOR** | `models/domain/ModelGuildMemberListUpdate.kt:108,116` `serializeOperation(...): Map<String, Any>` / `serializeItem(...): Map<String, Any>` | Reference `ModelGuildMemberListUpdate` models operations/items as typed nested classes throughout; the target flattens the *serialize* direction to raw string→Any maps. |
| **MINOR** | `models/domain/ModelPayload.kt:232-233` `_trace` decoded via `gson.fromJson(it, Any::class.java)` | Matches the reference `Object trace` but goes through a string round-trip the reference does not perform (`ModelPayload.java:68-72` reads it as a nested string list). |

No occurrence of `Map<String, Any>` or `JsonElement` was found substituting for a *known typed* reference
field on any DTO in §2 — the core models are clean in this respect.

---

## 6. Severity roll-up

### BLOCKER (shipping screen parses this)
1. `EmbedType` has no registered `TypeAdapter` and `EmbedTypeTypeAdapter`/`EmbedTypeKt` are missing —
   every rich embed deserialises `type` to `null` (`TypeAdapterRegistrar.kt:42-62`).
2. `TypeAdapterRegistrar.kt` registers **20** adapters vs the reference's **39 types + 3 factories**
   (`b/a/b/a.java`). Missing registrations: `EmbedType`, `ComponentType` (reference uses
   `ComponentTypeTypeAdapter` + `ComponentRuntimeTypeAdapter`), `ButtonStyle`, `TextStyle`,
   `ApplicationType`, `ApplicationStatus`, `ApplicationCommandType`, `ApplicationCommandPermissionType`,
   `AllowedInSuggestionsType`, `OAuthScope`, `MessageActivityType`, `LocalizedString`, `PriceTierType`,
   `GuildRoleSubscriptionBenefitType`, `PayoutStatus`, `PayoutGroupStatus`, `PayoutGroupType`,
   `ReportNodeChild`, `ReportNodeBottomButton`, `ReportNodeElementData`, `SchemaObject`.
3. 44 of 83 gateway dispatch events unrouted (§4.4) — voice, threads, calls, interactions, emoji/sticker
   updates, scheduled events, streams all dead.
4. `api/permission/PermissionKt` missing — no permission-bit evaluation.
5. `api/message/MessageFlags` + `MessageTypes` missing — system messages and flag-gated UI cannot render.
6. `api/channel/ChannelRecipient` + `ChannelUtils` missing.
7. REST coverage 15.9 % (222/264 endpoints missing) and `RestAPIParams` 15.9 % (90/107 bodies missing).
8. `models/domain/emoji/*` (5 of 6 classes) missing — emoji rendering/reactions.

### MAJOR
- `Channel.guildHashes` declared `val`, reference is mutable (`Channel.kt:34`).
- `IdentifyClientState` wire key changed from `useruser_guild_settings_version` to
  `user_guild_settings_version` (`GatewayPayloads.kt:26`).
- `MESSAGE_REACTION_REMOVE_ALL` / `_EMOJI` re-typed away from `MessageReactionUpdate`.
- `api/report/**` implemented under package `api/reporting/**`.
- 12 of 16 `OutgoingPayload` subtypes missing; 13 `GatewaySocket` send methods missing.
- `models/domain/Model` streaming-parse contract dropped in favour of full-tree Gson parsing.
- `ModelPayload.Hello` relocated and given an extra `trace` field.
- `gateway/rest/*` and `restapi/RestAPIAbortCodes`, `BreadcrumbInterceptor`, `RetryWithDelay` missing.
- `models/commands/**` 1/13, `api/commands/**` 1/15, `api/thread/**` 3/10, `api/guildrolesubscription/**` 0/18.

### MINOR
- `Message`, `User`, `MeUser`, `ActivitySecrets`, `Resume`, `Identify` override `toString()` with redaction;
  the reference emits the plain data-class `toString`.
- `ForumTag.customEmojiId` computed instead of stored.
- `PermissionOverwrite.Type` and `ClientStatus` gain `@SerializedName` the reference lacks.
- `Channel.appliedTags` loses the `ForumTagId` typealias; `IdentifyClientState.guildHashes` loses `GuildId`.
- `Outgoing` made generic.
- Invented `api/channel/ChannelPinsUpdate` + `CHANNEL_PINS_UPDATE` dispatch entry.
- `GatewaySocketLogger` replaced by inline `android.util.Log`.

---

## 7. Where the JADX output is unusable (do not treat as findings)

1. `gateway/io/IncomingParser.java:255-262` — `assignField` **not decompiled** (1862 instructions). The
   complete reference event→type mapping cannot be read from this file; §4.4 was reconstructed from
   `stores/StoreGatewayConnection.java` plus the surviving `JADX WARN` fragments. Roughly a dozen
   event→payload-type bindings in §4.4 are inferred from the store layer, not read directly.
2. `api/role/GuildRole.java`, `api/role/GuildRoleTags.java`, `api/voice/state/VoiceState.java`,
   `api/channel/ForumTag.java`, `api/presence/ClientStatuses.java` — JADX emitted **no constructor**.
   Declaration order for the first four was recovered from their `toString()` builder chains (reliable for
   Kotlin data classes). `ClientStatuses` has **no `toString`** either, so its constructor parameter order
   (`desktop, web, mobile` vs `desktop, mobile, web`) is **unverified**; see §2.7.
3. Retrofit annotations in `restapi/RestAPIInterface.java` are obfuscated (`i0.f0.f/o/n/p/b/h`). The
   verb mapping used in §3 was inferred from method names and paths (e.g. `@i0.f0.o("guilds")
   createGuild` ⇒ POST) and is consistent across all 282 declarations, but is an inference.
4. `@Metadata` blobs were stripped from all greps; a handful of Kotlin nullability facts (notably on
   `GuildRole`, `VoiceState`, `ForumTag`) come from those blobs and were only spot-checked.
