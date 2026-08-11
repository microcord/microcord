package com.discord.stores

import com.discord.api.presence.ClientStatus
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelUserSettings
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.time.TimeUtils
import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.StringValue
import com.google.protobuf.UInt32Value
import com.google.protobuf.UInt64Value
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.CustomStatus
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.GuildFolder
import discord_protos.discord_users.v1.PreloadedUserSettingsOuterClass.PreloadedUserSettings.Theme
import java.util.Locale

/**
 * The settings the app reads, one declaration each.
 *
 * A wrapper type that is unset means the user has never chosen, which is not the same as choosing
 * the zero value — so every setting here states its own default rather than letting proto3 decide.
 */
object UserSettings {

    private val store get() = StoreStream.getUserSettingsProto()
    private val writer get() = StoreStream.getUserSettingsProtoWriter()

    // -- Text and images ---------------------------------------------------------------------

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

    val viewNsfwGuilds = bool(false, { it.textAndImages.run { hasViewNsfwGuilds() to viewNsfwGuilds.value } }) {
        textAndImagesBuilder.viewNsfwGuilds = it
    }

    /** Whether age-restricted commands are offered in DMs and group DMs. */
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

    // -- Appearance --------------------------------------------------------------------------

    val developerMode = setting({ it.appearance.developerMode }) { appearanceBuilder.developerMode = it }

    /**
     * The account theme, under the names the rest of the app uses; null while it has never been
     * picked, which leaves the device following the system.
     *
     * `materialYou` has no counterpart in the enum, so it stays device-local; see
     * `StoreUserSettingsSystem`.
     */
    val theme = setting({ THEMES[it.appearance.theme] }) { name ->
        appearanceBuilder.theme = THEMES.entries.firstOrNull { it.value == name }?.key ?: Theme.THEME_DARK
    }

    // -- Localization ------------------------------------------------------------------------

    /** Null while the account has never picked, which the app renders as the device locale. */
    val locale = setting({ it.localization.locale.value.takeIf(String::isNotEmpty) }) { value ->
        localizationBuilder.locale = StringValue.of(value.orEmpty())
    }

    // -- Status ------------------------------------------------------------------------------

    val showCurrentGame = bool(true, { it.status.run { hasShowCurrentGame() to showCurrentGame.value } }) {
        statusBuilder.showCurrentGame = it
    }

    /** The presence the account shows on every client; null until one has been chosen. */
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

    // -- Privacy -----------------------------------------------------------------------------

    val allowAccessibilityDetection =
        setting({ it.privacy.allowAccessibilityDetection }) { privacyBuilder.allowAccessibilityDetection = it }

    val contactSyncEnabled = bool(false, { it.privacy.run { hasContactSyncEnabled() to contactSyncEnabled.value } }) {
        privacyBuilder.contactSyncEnabled = it
    }

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
        // NO_RELATION is only honoured alongside the other two, so "everyone" is all three bits.
        if (flags.all) bits = bits or FRIEND_SOURCE_NO_RELATION
        privacyBuilder.friendSourceFlags = UInt32Value.of(bits)
    }

    val friendDiscoveryFlags = uint32(0, { it.privacy.run { hasFriendDiscoveryFlags() to friendDiscoveryFlags.value } }) {
        privacyBuilder.friendDiscoveryFlags = it
    }

    /** The v1 field is only read, and only until the account has answered in the v2 one. */
    val defaultGuildsRestricted = setting(
        { it.privacy.run { if (hasDefaultGuildsRestrictedV2()) defaultGuildsRestrictedV2.value else defaultGuildsRestricted } },
    ) { privacyBuilder.defaultGuildsRestrictedV2 = BoolValue.of(it) }

    val restrictedGuildIds = setting({ it.privacy.restrictedGuildIdsList.toList() }) { ids ->
        privacyBuilder.clearRestrictedGuildIds().addAllRestrictedGuildIds(ids)
    }

    // -- Guild folders -----------------------------------------------------------------------

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
