package com.discord.stores

import com.discord.api.presence.ClientStatus
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.media.Redaction
import com.discord.utilities.media.SensitiveContentSetting
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.StringValue
import com.google.protobuf.UInt32Value
import com.google.protobuf.UInt64Value
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.CustomStatus
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.CustomStatusPushNotificationType
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.DmSpamFilterV2
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.ExplicitContentRedaction
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.GuildFolder
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.Theme
import java.util.Locale

/**
 * The settings the app reads. An unset wrapper means the user has never chosen, so every setting
 * states its own default rather than letting proto3 decide.
 */
object UserSettings {

    private val store get() = StoreStream.getUserSettingsProto()
    private val writer get() = StoreStream.getUserSettingsProtoWriter()

    val gifAutoPlay = bool(true, { it.textAndImages.run { hasGifAutoPlay() to gifAutoPlay.value } }) {
        textAndImagesBuilder.gifAutoPlay = it
    }

    val animateEmoji = bool(true, { it.textAndImages.run { hasAnimateEmoji() to animateEmoji.value } }) {
        textAndImagesBuilder.animateEmoji = it
    }

    val renderEmbeds = bool(true, { it.textAndImages.run { hasRenderEmbeds() to renderEmbeds.value } }) {
        textAndImagesBuilder.renderEmbeds = it
    }

    val renderReactions = bool(true, { it.textAndImages.run { hasRenderReactions() to renderReactions.value } }) {
        textAndImagesBuilder.renderReactions = it
    }

    val inlineAttachmentMedia = bool(true, { it.textAndImages.run { hasInlineAttachmentMedia() to inlineAttachmentMedia.value } }) {
        textAndImagesBuilder.inlineAttachmentMedia = it
    }

    val inlineEmbedMedia = bool(true, { it.textAndImages.run { hasInlineEmbedMedia() to inlineEmbedMedia.value } }) {
        textAndImagesBuilder.inlineEmbedMedia = it
    }

    val messageDisplayCompact = bool(false, { it.textAndImages.run { hasMessageDisplayCompact() to messageDisplayCompact.value } }) {
        textAndImagesBuilder.messageDisplayCompact = it
    }

    val convertEmoticons = bool(true, { it.textAndImages.run { hasConvertEmoticons() to convertEmoticons.value } }) {
        textAndImagesBuilder.convertEmoticons = it
    }

    val viewImageDescriptions = bool(false, { it.textAndImages.run { hasViewImageDescriptions() to viewImageDescriptions.value } }) {
        textAndImagesBuilder.viewImageDescriptions = it
    }

    val includeStickersInAutocomplete =
        bool(false, { it.textAndImages.run { hasIncludeStickersInAutocomplete() to includeStickersInAutocomplete.value } }) {
            textAndImagesBuilder.includeStickersInAutocomplete = it
        }

    val viewNsfwGuilds = bool(false, { it.textAndImages.run { hasViewNsfwGuilds() to viewNsfwGuilds.value } }) {
        textAndImagesBuilder.viewNsfwGuilds = it
    }

    val viewNsfwCommands = bool(false, { it.textAndImages.run { hasViewNsfwCommands() to viewNsfwCommands.value } }) {
        textAndImagesBuilder.viewNsfwCommands = it
    }

    val animateStickers = uint32(
        ModelUserSettings.STICKER_ANIMATION_ALWAYS_ANIMATE,
        { it.textAndImages.run { hasAnimateStickers() to animateStickers.value } },
    ) { textAndImagesBuilder.animateStickers = it }

    val explicitContentFilter = uint32(
        ModelUserSettings.EXPLICIT_CONTENT_FILTER_NON_FRIENDS,
        { it.textAndImages.run { hasExplicitContentFilter() to explicitContentFilter.value } },
    ) { textAndImagesBuilder.explicitContentFilter = it }

    /** Never chosen falls back to the legacy filter, which is the answer the account already gave. */
    val dmSpamFilter = setting(
        { proto ->
            val stored = proto.textAndImages.dmSpamFilterV2
            if (stored != DmSpamFilterV2.DM_SPAM_FILTER_V2_DEFAULT_UNSET) {
                stored
            } else {
                when (explicitContentFilter.get(proto)) {
                    ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED -> DmSpamFilterV2.DM_SPAM_FILTER_V2_DISABLED
                    ModelUserSettings.EXPLICIT_CONTENT_FILTER_FRIENDS_AND_NON_FRIENDS ->
                        DmSpamFilterV2.DM_SPAM_FILTER_V2_FRIENDS_AND_NON_FRIENDS
                    else -> DmSpamFilterV2.DM_SPAM_FILTER_V2_NON_FRIENDS
                }
            }
        },
    ) { textAndImagesBuilder.dmSpamFilterV2 = it }

    val explicitContent = sensitiveContent(
        { it.textAndImages.explicitContentSettings.run { Triple(explicitContentGuilds, explicitContentFriendDm, explicitContentNonFriendDm) } },
        // Never chosen falls back to what the legacy filter said, so the account keeps its answer.
        { proto ->
            when (explicitContentFilter.get(proto)) {
                ModelUserSettings.EXPLICIT_CONTENT_FILTER_DISABLED -> SensitiveContentSetting(Redaction.SHOW, Redaction.SHOW, Redaction.SHOW)
                ModelUserSettings.EXPLICIT_CONTENT_FILTER_FRIENDS_AND_NON_FRIENDS ->
                    SensitiveContentSetting(Redaction.SHOW, Redaction.BLOCK, Redaction.BLOCK)
                else -> SensitiveContentSetting(Redaction.SHOW, Redaction.SHOW, Redaction.BLOCK)
            }
        },
    ) { value ->
        textAndImagesBuilder.explicitContentSettingsBuilder
            .setExplicitContentGuilds(value.guilds.toProto())
            .setExplicitContentFriendDm(value.friendDm.toProto())
            .setExplicitContentNonFriendDm(value.nonFriendDm.toProto())
    }

    val goreContent = sensitiveContent(
        { it.textAndImages.goreContentSettings.run { Triple(goreContentGuilds, goreContentFriendDm, goreContentNonFriendDm) } },
    ) { value ->
        textAndImagesBuilder.goreContentSettingsBuilder
            .setGoreContentGuilds(value.guilds.toProto())
            .setGoreContentFriendDm(value.friendDm.toProto())
            .setGoreContentNonFriendDm(value.nonFriendDm.toProto())
    }

    val selfHarmContent = sensitiveContent(
        { it.textAndImages.selfHarmContentSettings.run { Triple(selfHarmContentGuilds, selfHarmContentFriendDm, selfHarmContentNonFriendDm) } },
    ) { value ->
        textAndImagesBuilder.selfHarmContentSettingsBuilder
            .setSelfHarmContentGuilds(value.guilds.toProto())
            .setSelfHarmContentFriendDm(value.friendDm.toProto())
            .setSelfHarmContentNonFriendDm(value.nonFriendDm.toProto())
    }

    val showInAppNotifications =
        bool(true, { it.notifications.run { hasShowInAppNotifications() to showInAppNotifications.value } }) {
            notificationsBuilder.showInAppNotifications = it
        }

    val reactionNotifications = setting({ it.notifications.reactionNotifications }) {
        notificationsBuilder.reactionNotifications = it
    }

    /** The proto stores a tri-state whose unset value means enabled. */
    val statusNotifications = setting(
        { it.notifications.customStatusPushNotifications != CustomStatusPushNotificationType.CUSTOM_STATUS_PUSH_NOTIFICATION_TYPE_STATUS_PUSH_DISABLED },
    ) { enabled ->
        notificationsBuilder.customStatusPushNotifications = if (enabled) {
            CustomStatusPushNotificationType.CUSTOM_STATUS_PUSH_NOTIFICATION_TYPE_STATUS_PUSH_ENABLED
        } else {
            CustomStatusPushNotificationType.CUSTOM_STATUS_PUSH_NOTIFICATION_TYPE_STATUS_PUSH_DISABLED
        }
    }

    val friendOnlineNotifications =
        bool(true, { it.notifications.run { hasEnableFriendOnlineNotifications() to enableFriendOnlineNotifications.value } }) {
            notificationsBuilder.enableFriendOnlineNotifications = it
        }

    val voiceActivityNotifications =
        bool(true, { it.notifications.run { hasEnableVoiceActivityNotifications() to enableVoiceActivityNotifications.value } }) {
            notificationsBuilder.enableVoiceActivityNotifications = it
        }

    val friendAnniversaryNotifications =
        bool(true, { it.notifications.run { hasEnableFriendAnniversaryNotifications() to enableFriendAnniversaryNotifications.value } }) {
            notificationsBuilder.enableFriendAnniversaryNotifications = it
        }

    val profileUpdateNotifications =
        bool(true, { it.notifications.run { hasEnableProfileUpdatesNotifications() to enableProfileUpdatesNotifications.value } }) {
            notificationsBuilder.enableProfileUpdatesNotifications = it
        }

    val friendGamingActivityNotifications =
        bool(true, { it.notifications.run { hasEnableFriendGamingActivityNotifications() to enableFriendGamingActivityNotifications.value } }) {
            notificationsBuilder.enableFriendGamingActivityNotifications = it
        }

    val upcomingEventNotifications =
        bool(true, { it.notifications.run { hasEnableUpcomingServerEventNotifications() to enableUpcomingServerEventNotifications.value } }) {
            notificationsBuilder.enableUpcomingServerEventNotifications = it
        }

    val notifyFriendsOnComeOnline =
        bool(true, { it.notifications.run { hasNotifyFriendsOnComeOnline() to notifyFriendsOnComeOnline.value } }) {
            notificationsBuilder.notifyFriendsOnComeOnline = it
        }

    val notifyFriendsOnProfileUpdate =
        bool(true, { it.notifications.run { hasNotifyFriendsOnProfileUpdate() to notifyFriendsOnProfileUpdate.value } }) {
            notificationsBuilder.notifyFriendsOnProfileUpdate = it
        }

    val streamNotifications =
        bool(true, { it.voiceAndVideo.run { hasStreamNotificationsEnabled() to streamNotificationsEnabled.value } }) {
            voiceAndVideoBuilder.streamNotificationsEnabled = it
        }

    val developerMode = setting({ it.appearance.developerMode }) { appearanceBuilder.developerMode = it }

    /** Null until picked, leaving the device on the system theme. `materialYou` stays device-local. */
    val theme = setting({ THEMES[it.appearance.theme] }) { name ->
        appearanceBuilder.theme = THEMES.entries.firstOrNull { it.value == name }?.key ?: Theme.THEME_DARK
    }

    val locale = setting({ it.localization.locale.value.takeIf(String::isNotEmpty) }) { value ->
        localizationBuilder.locale = StringValue.of(value.orEmpty())
    }

    val showCurrentGame = bool(true, { it.status.run { hasShowCurrentGame() to showCurrentGame.value } }) {
        statusBuilder.showCurrentGame = it
    }

    val status = setting({ statusOf(it.status.status.value) }) { value ->
        statusBuilder.status = StringValue.of(value?.name?.lowercase(Locale.ROOT).orEmpty())
    }

    val customStatus = setting(
        { proto -> if (proto.status.hasCustomStatus()) proto.status.customStatus.toModel() else ModelCustomStatusSetting.CLEAR },
    ) { status ->
        if (status == ModelCustomStatusSetting.CLEAR) {
            statusBuilder.clearCustomStatus()
        } else {
            statusBuilder.customStatus = status.toProto(ClockFactory.get().currentTimeMillis())
        }
    }

    val allowAccessibilityDetection =
        setting({ it.privacy.allowAccessibilityDetection }) { privacyBuilder.allowAccessibilityDetection = it }

    val contactSyncEnabled = bool(false, { it.privacy.run { hasContactSyncEnabled() to contactSyncEnabled.value } }) {
        privacyBuilder.contactSyncEnabled = it
    }

    /** Who may join a party an application publishes; both default on, as the reference has them. */
    val allowActivityPartyPrivacyFriends = bool(
        true,
        { it.privacy.run { hasAllowActivityPartyPrivacyFriends() to allowActivityPartyPrivacyFriends.value } },
    ) { privacyBuilder.allowActivityPartyPrivacyFriends = it }

    val allowActivityPartyPrivacyVoiceChannel = bool(
        true,
        { it.privacy.run { hasAllowActivityPartyPrivacyVoiceChannel() to allowActivityPartyPrivacyVoiceChannel.value } },
    ) { privacyBuilder.allowActivityPartyPrivacyVoiceChannel = it }

    val friendSourceFlags = setting(
        { proto ->
            val bits = proto.privacy.run { if (hasFriendSourceFlags()) friendSourceFlags.value else FRIEND_SOURCE_ALL }
            ModelUserSettings.FriendSourceFlags(
                all = bits and FRIEND_SOURCE_ALL == FRIEND_SOURCE_ALL,
                mutualFriends = bits and FRIEND_SOURCE_MUTUAL_FRIENDS != 0,
                mutualGuilds = bits and FRIEND_SOURCE_MUTUAL_GUILDS != 0,
            )
        },
    ) { flags ->
        var bits = 0
        if (flags.all || flags.mutualFriends) bits = bits or FRIEND_SOURCE_MUTUAL_FRIENDS
        if (flags.all || flags.mutualGuilds) bits = bits or FRIEND_SOURCE_MUTUAL_GUILDS
        if (flags.all) bits = bits or FRIEND_SOURCE_NO_RELATION
        privacyBuilder.friendSourceFlags = UInt32Value.of(bits)
    }

    val friendDiscoveryFlags = uint32(0, { it.privacy.run { hasFriendDiscoveryFlags() to friendDiscoveryFlags.value } }) {
        privacyBuilder.friendDiscoveryFlags = it
    }

    /** v1 is read only until the account has answered in v2. */
    val defaultGuildsRestricted = setting(
        { it.privacy.run { if (hasDefaultGuildsRestrictedV2()) defaultGuildsRestrictedV2.value else defaultGuildsRestricted } },
    ) { privacyBuilder.defaultGuildsRestrictedV2 = BoolValue.of(it) }

    val restrictedGuildIds = setting({ it.privacy.restrictedGuildIdsList.toList() }) { ids ->
        privacyBuilder.clearRestrictedGuildIds().addAllRestrictedGuildIds(ids)
    }

    val messageRequestRestrictedGuildIds =
        setting({ it.privacy.messageRequestRestrictedGuildIdsList.toList() }) { ids ->
            privacyBuilder.clearMessageRequestRestrictedGuildIds().addAllMessageRequestRestrictedGuildIds(ids)
        }

    val guildFolders = setting(
        fromProto = { proto ->
            proto.guildFolders.foldersList.map { it.toModel() }
                .ifEmpty { proto.guildFolders.guildPositionsList.map { ModelGuildFolder(null, listOf(it)) } }
        },
        delay = UserSettingsDelay.FREQUENT_USER_ACTION,
    ) { folders ->
        guildFoldersBuilder.clearFolders().clearGuildPositions().addAllFolders(folders.map { it.toProto() })
    }

    private fun <T> setting(
        fromProto: (PreloadedUserSettings) -> T,
        delay: UserSettingsDelay = UserSettingsDelay.INFREQUENT_USER_ACTION,
        toProto: PreloadedUserSettings.Builder.(T) -> Unit,
    ) = UserSettingsProtoSetting(store, writer, fromProto, delay, toProto = toProto)

    private fun bool(
        default: Boolean,
        read: (PreloadedUserSettings) -> Pair<Boolean, Boolean>,
        write: PreloadedUserSettings.Builder.(BoolValue) -> Unit,
    ) = setting({ proto -> read(proto).let { (isSet, value) -> if (isSet) value else default } }) { write(BoolValue.of(it)) }

    private fun sensitiveContent(
        read: (PreloadedUserSettings) -> Triple<ExplicitContentRedaction, ExplicitContentRedaction, ExplicitContentRedaction>,
        unset: (PreloadedUserSettings) -> SensitiveContentSetting = { SensitiveContentSetting() },
        write: PreloadedUserSettings.Builder.(SensitiveContentSetting) -> Unit,
    ) = setting({ proto ->
        val (guilds, friendDm, nonFriendDm) = read(proto)
        val fallback = unset(proto)
        SensitiveContentSetting(
            guilds = guilds.toRedaction(fallback.guilds),
            friendDm = friendDm.toRedaction(fallback.friendDm),
            nonFriendDm = nonFriendDm.toRedaction(fallback.nonFriendDm),
        )
    }, toProto = write)

    private fun ExplicitContentRedaction.toRedaction(unset: Redaction) = when (this) {
        ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_SHOW -> Redaction.SHOW
        ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_BLUR -> Redaction.BLUR
        ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_BLOCK -> Redaction.BLOCK
        else -> unset
    }

    private fun Redaction.toProto() = when (this) {
        Redaction.SHOW -> ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_SHOW
        Redaction.BLUR -> ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_BLUR
        Redaction.BLOCK -> ExplicitContentRedaction.EXPLICIT_CONTENT_REDACTION_BLOCK
    }

    private fun uint32(
        default: Int,
        read: (PreloadedUserSettings) -> Pair<Boolean, Int>,
        write: PreloadedUserSettings.Builder.(UInt32Value) -> Unit,
    ) = setting({ proto -> read(proto).let { (isSet, value) -> if (isSet) value else default } }) { write(UInt32Value.of(it)) }

    private fun CustomStatus.toModel() = ModelCustomStatusSetting(
        text = text.takeIf(String::isNotEmpty),
        emojiId = emojiId.takeIf { it != 0L },
        emojiName = emojiName.takeIf(String::isNotEmpty),
        expiresAt = expiresAtMs.takeIf { it != 0L }?.let { TimeUtils.toUTCDateTime(it) },
    )

    private fun ModelCustomStatusSetting.toProto(createdAtMs: Long): CustomStatus = CustomStatus.newBuilder()
        .setText(text.orEmpty())
        .setEmojiId(emojiId ?: 0L)
        .setEmojiName(emojiName.orEmpty())
        .setExpiresAtMs(expiresAt?.let { TimeUtils.parseUTCDate(it) } ?: 0L)
        .setCreatedAtMs(createdAtMs)
        .build()

    private fun GuildFolder.toModel() = ModelGuildFolder(
        id = if (hasId()) id.value else null,
        guildIds = guildIdsList.toList(),
        color = if (hasColor()) color.value.toInt() else null,
        name = if (hasName()) name.value else null,
    )

    private fun ModelGuildFolder.toProto(): GuildFolder = GuildFolder.newBuilder()
        .addAllGuildIds(guildIds)
        .also { builder ->
            id?.let { builder.id = Int64Value.of(it) }
            color?.let { builder.color = UInt64Value.of(it.toLong()) }
            name?.takeIf(String::isNotEmpty)?.let { builder.name = StringValue.of(it) }
        }
        .build()

    private const val FRIEND_SOURCE_MUTUAL_FRIENDS = 1 shl 1
    private const val FRIEND_SOURCE_MUTUAL_GUILDS = 1 shl 2
    private const val FRIEND_SOURCE_NO_RELATION = 1 shl 3
    private const val FRIEND_SOURCE_ALL =
        FRIEND_SOURCE_MUTUAL_FRIENDS or FRIEND_SOURCE_MUTUAL_GUILDS or FRIEND_SOURCE_NO_RELATION

    private fun statusOf(name: String) =
        ClientStatus.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private val THEMES = mapOf(
        Theme.THEME_DARK to ModelUserSettings.THEME_DARK,
        Theme.THEME_DARKER to ModelUserSettings.THEME_DARKER,
        Theme.THEME_LIGHT to ModelUserSettings.THEME_LIGHT,
        Theme.THEME_MIDNIGHT to ModelUserSettings.THEME_MIDNIGHT,
    )
}
