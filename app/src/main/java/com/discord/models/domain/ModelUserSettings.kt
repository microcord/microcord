package com.discord.models.domain

import com.discord.api.presence.ClientStatus
import java.io.Serializable
import java.util.Locale

data class ModelGuildFolder(
    val id: Long?,
    val guildIds: List<Long>,
    val color: Int? = null,
    val name: String? = null,
) : Serializable

data class ModelCustomStatusSetting(
    val text: String?,
    val emojiId: Long?,
    val emojiName: String?,
    val expiresAt: String?,
) : Serializable {
    companion object {
        @JvmField val CLEAR = ModelCustomStatusSetting(null, null, null, null)
    }
}

data class ModelUserSettings(
    val allowAccessibilityDetection: Boolean? = null,
    val animateEmoji: Boolean? = null,
    val animateStickers: Int? = null,
    val contactSyncUpsellShown: Boolean? = null,
    val customStatus: ModelCustomStatusSetting? = null,
    val defaultGuildsRestricted: Boolean? = null,
    val developerMode: Boolean? = null,
    val explicitContentFilter: Int? = null,
    val friendDiscoveryFlags: Int? = null,
    val friendSourceFlags: FriendSourceFlags? = null,
    val guildFolders: List<ModelGuildFolder>? = null,
    val inlineAttachmentMedia: Boolean? = null,
    val inlineEmbedMedia: Boolean? = null,
    val locale: String? = null,
    val renderEmbeds: Boolean? = null,
    val restrictedGuilds: List<Long>? = null,
    val showCurrentGame: Boolean? = null,
    val status: ClientStatus? = null,
    val theme: String? = null,
) : Serializable {
    data class FriendSourceFlags(
        val all: Boolean = false,
        val mutualFriends: Boolean = false,
        val mutualGuilds: Boolean = false,
    ) : Serializable

    companion object {
        const val EXPLICIT_CONTENT_FILTER_DISABLED = 0
        const val EXPLICIT_CONTENT_FILTER_NON_FRIENDS = 1
        const val EXPLICIT_CONTENT_FILTER_FRIENDS_AND_NON_FRIENDS = 2
        const val STICKER_ANIMATION_ALWAYS_ANIMATE = 0
        const val STICKER_ANIMATION_ANIMATE_ON_INTERACTION = 1
        const val STICKER_ANIMATION_NEVER_ANIMATE = 2
        const val THEME_DARK = "dark"
        const val THEME_LIGHT = "light"
        const val THEME_PURE_EVIL = "pureEvil"

        @JvmStatic fun getDefaultLocale(): String = Locale.getDefault().toLanguageTag()
        @JvmStatic fun getLocaleObject(locale: String): Locale = Locale.forLanguageTag(locale)
    }
}
