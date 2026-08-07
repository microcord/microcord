package com.discord.models.domain

import com.discord.api.presence.ClientStatus
import com.discord.models.deserialization.gson.InboundGatewayGsonParser
import java.util.Locale

data class ModelGuildFolder(
    val id: Long?,
    val guildIds: List<Long>,
    val color: Int? = null,
    val name: String? = null,
)

data class ModelCustomStatusSetting(
    val text: String?,
    val emojiId: Long?,
    val emojiName: String?,
    val expiresAt: String?,
) {
    companion object {
        @JvmField val CLEAR = ModelCustomStatusSetting(null, null, null, null)
    }
}

class ModelUserSettings(
    allowAccessibilityDetection: Boolean? = null,
    animateEmoji: Boolean? = null,
    animateStickers: Int? = null,
    contactSyncUpsellShown: Boolean? = null,
    customStatus: ModelCustomStatusSetting? = null,
    defaultGuildsRestricted: Boolean? = null,
    developerMode: Boolean? = null,
    explicitContentFilter: Int? = null,
    friendDiscoveryFlags: Int? = null,
    friendSourceFlags: FriendSourceFlags? = null,
    guildFolders: List<ModelGuildFolder>? = null,
    inlineAttachmentMedia: Boolean? = null,
    inlineEmbedMedia: Boolean? = null,
    locale: String? = null,
    renderEmbeds: Boolean? = null,
    restrictedGuilds: List<Long>? = null,
    showCurrentGame: Boolean? = null,
    status: ClientStatus? = null,
    theme: String? = null,
) : Model {
    var allowAccessibilityDetection = allowAccessibilityDetection
        private set
    var animateEmoji = animateEmoji
        private set
    var animateStickers = animateStickers
        private set
    var contactSyncUpsellShown = contactSyncUpsellShown
        private set
    var customStatus = customStatus
        private set
    var defaultGuildsRestricted = defaultGuildsRestricted
        private set
    var developerMode = developerMode
        private set
    var explicitContentFilter = explicitContentFilter
        private set
    var friendDiscoveryFlags = friendDiscoveryFlags
        private set
    var friendSourceFlags = friendSourceFlags
        private set
    var guildFolders = guildFolders
        private set
    var inlineAttachmentMedia = inlineAttachmentMedia
        private set
    var inlineEmbedMedia = inlineEmbedMedia
        private set
    var locale = locale
        private set
    var renderEmbeds = renderEmbeds
        private set
    var restrictedGuilds = restrictedGuilds
        private set
    var showCurrentGame = showCurrentGame
        private set
    var status = status
        private set
    var theme = theme
        private set

    override fun assignField(reader: Model.JsonReader) {
        when (reader.nextName()) {
            "allow_accessibility_detection" -> allowAccessibilityDetection = reader.nextBooleanOrNull()
            "animate_emoji" -> animateEmoji = reader.nextBooleanOrNull()
            "animate_stickers" -> animateStickers = reader.nextIntOrNull()
            "contact_sync_enabled" -> contactSyncUpsellShown = reader.nextBooleanOrNull()
            "custom_status" -> customStatus = InboundGatewayGsonParser.fromJson(reader, ModelCustomStatusSetting::class.java)
            "default_guilds_restricted" -> defaultGuildsRestricted = reader.nextBooleanOrNull()
            "developer_mode" -> developerMode = reader.nextBoolean(false)
            "explicit_content_filter" -> explicitContentFilter = reader.nextIntOrNull()
            "friend_discovery_flags" -> friendDiscoveryFlags = reader.nextInt(0)
            "friend_source_flags" -> friendSourceFlags = InboundGatewayGsonParser.fromJson(reader, FriendSourceFlags::class.java)
            "guild_folders" -> reader.nextList {
                InboundGatewayGsonParser.fromJson(reader, ModelGuildFolder::class.java)
            }.takeIf { it.isNotEmpty() }?.let { guildFolders = it }
            "guild_positions" -> {
                val positions = reader.nextList { reader.nextLongOrNull() }
                if (guildFolders.isNullOrEmpty()) guildFolders = positions.map { ModelGuildFolder(null, listOf(it)) }
            }
            "inline_attachment_media" -> inlineAttachmentMedia = reader.nextBooleanOrNull()
            "inline_embed_media" -> inlineEmbedMedia = reader.nextBooleanOrNull()
            "locale" -> locale = reader.nextString(locale)
            "render_embeds" -> renderEmbeds = reader.nextBooleanOrNull()
            "restricted_guilds" -> restrictedGuilds = reader.nextList { reader.nextLongOrNull() }
            "show_current_game" -> showCurrentGame = reader.nextBoolean(false)
            "status" -> status = InboundGatewayGsonParser.fromJson(reader, ClientStatus::class.java)
            "theme" -> theme = reader.nextString(theme)
            else -> reader.skipValue()
        }
    }

    override fun equals(other: Any?): Boolean = other is ModelUserSettings &&
        renderEmbeds == other.renderEmbeds && inlineEmbedMedia == other.inlineEmbedMedia &&
        inlineAttachmentMedia == other.inlineAttachmentMedia && developerMode == other.developerMode &&
        defaultGuildsRestricted == other.defaultGuildsRestricted && explicitContentFilter == other.explicitContentFilter &&
        friendDiscoveryFlags == other.friendDiscoveryFlags && contactSyncUpsellShown == other.contactSyncUpsellShown &&
        showCurrentGame == other.showCurrentGame && animateEmoji == other.animateEmoji &&
        allowAccessibilityDetection == other.allowAccessibilityDetection && animateStickers == other.animateStickers &&
        theme == other.theme && guildFolders == other.guildFolders && locale == other.locale &&
        restrictedGuilds == other.restrictedGuilds && friendSourceFlags == other.friendSourceFlags &&
        status == other.status && customStatus == other.customStatus

    override fun hashCode(): Int {
        val values = listOf(
            renderEmbeds, inlineEmbedMedia, inlineAttachmentMedia, developerMode, defaultGuildsRestricted,
            explicitContentFilter, friendDiscoveryFlags, contactSyncUpsellShown, showCurrentGame, animateEmoji,
            allowAccessibilityDetection, animateStickers, theme, guildFolders, locale, restrictedGuilds,
            friendSourceFlags, status, customStatus,
        )
        return values.drop(1).fold((values.first()?.hashCode() ?: 43) + 59) { result, value ->
            result * 59 + (value?.hashCode() ?: 43)
        }
    }

    override fun toString() = "ModelUserSettings(theme=$theme, guildFolders=$guildFolders, " +
        "renderEmbeds=$renderEmbeds, inlineEmbedMedia=$inlineEmbedMedia, " +
        "inlineAttachmentMedia=$inlineAttachmentMedia, developerMode=$developerMode, locale=$locale, " +
        "restrictedGuilds=$restrictedGuilds, defaultGuildsRestricted=$defaultGuildsRestricted, " +
        "friendSourceFlags=$friendSourceFlags, explicitContentFilter=$explicitContentFilter, " +
        "friendDiscoveryFlags=$friendDiscoveryFlags, contactSyncUpsellShown=$contactSyncUpsellShown, " +
        "status=$status, customStatus=$customStatus, showCurrentGame=$showCurrentGame, " +
        "animateEmoji=$animateEmoji, allowAccessibilityDetection=$allowAccessibilityDetection, " +
        "animateStickers=$animateStickers)"

    class FriendSourceFlags(
        all: Boolean = false,
        mutualFriends: Boolean = false,
        mutualGuilds: Boolean = false,
    ) : Model {
        var all = all
            private set
        var mutualFriends = mutualFriends
            private set
        var mutualGuilds = mutualGuilds
            private set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "all" -> all = reader.nextBoolean(all)
                "mutual_friends" -> mutualFriends = reader.nextBoolean(mutualFriends)
                "mutual_guilds" -> mutualGuilds = reader.nextBoolean(mutualGuilds)
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?) = other is FriendSourceFlags && all == other.all &&
            mutualGuilds == other.mutualGuilds && mutualFriends == other.mutualFriends

        override fun hashCode() = (((if (all) 79 else 97) + 59) * 59 +
            (if (mutualGuilds) 79 else 97)) * 59 + (if (mutualFriends) 79 else 97)

        override fun toString() = "ModelUserSettings.FriendSourceFlags(" +
            "all=$all, mutualGuilds=$mutualGuilds, mutualFriends=$mutualFriends)"
    }

    companion object {
        const val EXPLICIT_CONTENT_FILTER_DISABLED = 0
        const val EXPLICIT_CONTENT_FILTER_NON_FRIENDS = 1
        const val EXPLICIT_CONTENT_FILTER_FRIENDS_AND_NON_FRIENDS = 2
        const val STICKER_ANIMATION_ALWAYS_ANIMATE = 0
        const val STICKER_ANIMATION_ANIMATE_ON_INTERACTION = 1
        const val STICKER_ANIMATION_NEVER_ANIMATE = 2
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_MATERIAL_YOU = "materialYou"
        const val THEME_PURE_EVIL = "pureEvil"

        @JvmStatic fun getDefaultLocale(): String = localeString(Locale.getDefault())

        @JvmStatic fun getLocaleObject(locale: String): Locale = locale.split('-').let {
            if (it.size > 1) Locale(it[0], it[1]) else Locale(locale)
        }

        private fun localeString(locale: Locale) = locale.language +
            locale.country.takeIf(String::isNotEmpty)?.let { "-${it.uppercase(Locale.getDefault())}" }.orEmpty()
    }
}
