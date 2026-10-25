package com.discord.api.user

import com.discord.utilities.icon.IconUtils

import com.discord.api.application.ProfileApplication
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.guildmember.GuildMember
import com.google.gson.annotations.SerializedName
import java.io.Serializable

data class AvatarDecorationData(
    val asset: String,
    val skuId: Long,
    val expiresAt: Long? = null,
) : Serializable {
    /** Collectibles-shop asset first, legacy preset second; Fresco takes the first that resolves. */
    fun imageUrls(animate: Boolean): List<String> =
        if (isActive()) listOfNotNull(collectiblesUrl(animate), presetUrl(animate)) else emptyList()

    fun isActive(nowEpochSeconds: Long = System.currentTimeMillis() / 1000) =
        expiresAt == null || expiresAt > nowEpochSeconds

    private fun collectiblesUrl(animate: Boolean) = skuId.takeIf { it > 0 }
        ?.let { "$CDN/media/v1/collectibles-shop/$it/${if (animate) "animated" else "static"}" }

    private fun presetUrl(animate: Boolean) = asset.takeIf(String::isNotBlank)
        ?.let { "$CDN/avatar-decoration-presets/$it.png?size=${presetSize()}&passthrough=$animate" }

    private companion object {
        const val CDN = "https://cdn.discordapp.com"
        /** Small and drawn in bulk, so the rung below is accepted when it is close enough. */
        fun presetSize() = IconUtils.proxySizePx(IconUtils.AVATAR_DECORATION_SIZE_DP, allowUpscaling = true)
    }
}

data class UserProfile(
    val connectedAccounts: List<ConnectedAccount> = emptyList(),
    val mutualGuilds: List<GuildReference> = emptyList(),
    val user: User = User(0, ""),
    val premiumSince: String? = null,
    val premiumGuildSince: String? = null,
    val guildMember: GuildMember? = null,
    val application: ProfileApplication? = null,
    val userProfile: ProfileMetadata? = null,
    val guildMemberProfile: ProfileMetadata? = null,
    val badges: List<ProfileBadge>? = null,
    val guildBadges: List<ProfileBadge>? = null,
    val widgets: List<ProfileWidget>? = null,
    /**
     * The owner limited who can see their full profile, and the viewer is not on that list.
     *
     * Nothing is hidden client-side by this: the server has already blanked `bio`, `pronouns`,
     * `badges`, `guildBadges`, `connectedAccounts`, `premiumSince` and `premiumGuildSince` by the
     * time the response arrives. The flag exists so the viewer is told *why* the profile is bare
     * instead of being left to assume it is broken.
     */
    @SerializedName("private")
    val isPrivate: Boolean = false,
    /** Modern profile fields, sent only with `with_mutual_friends` / `with_mutual_friends_count`. */
    val mutualFriends: List<User>? = null,
    val mutualFriendsCount: Int? = null,
) {
    /**
     * The frame this profile is wearing, if any.
     *
     * A per-guild identity replaces the global one outright rather than merging into it, which is
     * why a guild profile with no frame hides the global one.
     */
    fun equippedProfileFrameSkuId(): Long? =
        (guildMemberProfile ?: userProfile)?.collectibles
            ?.firstOrNull { it.type == PROFILE_FRAME_TYPE }
            ?.skuId

    data class GuildReference(val id: Long, val nick: String?)

    data class ProfileBadge(
        val id: String,
        val description: String,
        val icon: String,
        val link: String? = null,
    )

    data class ProfileWidget(
        val id: Long,
        val updatedAt: String? = null,
        val data: ProfileWidgetData,
    )

    data class ProfileWidgetData(
        val type: String,
        val games: List<ProfileWidgetGame>? = null,
        val applicationId: Long? = null,
    )

    data class ProfileWidgetGame(
        val gameId: Long,
        val comment: String? = null,
        val tags: List<String>? = null,
    )

    data class ProfileMetadata(
        val guildId: Long? = null,
        val pronouns: String = "",
        val bio: String? = null,
        val banner: String? = null,
        val accentColor: Int? = null,
        val themeColors: List<Int>? = null,
        /** Snowflake-shaped, but sent as a raw JSON number that can exceed [Long]; kept as text. */
        val popoutAnimationParticleType: String? = null,
        val emoji: ProfileEmoji? = null,
        val profileEffect: ProfileEffect? = null,
        /** Every equipped collectible, including types this client does not render. */
        val collectibles: List<ProfileCollectible> = emptyList(),
    )

    data class ProfileCollectible(
        @SerializedName("sku_id")
        val skuId: Long,
        val type: Int,
        val expiresAt: String? = null,
    )

    data class ProfileEmoji(
        val id: Long? = null,
        val name: String? = null,
        val roles: List<Long> = emptyList(),
        val user: User? = null,
        val requireColons: Boolean = false,
        val managed: Boolean = false,
        val animated: Boolean = false,
        val available: Boolean? = null,
    )

    data class ProfileEffect(
        @SerializedName(value = "sku_id", alternate = ["id"])
        val skuId: Long,
        val expiresAt: Long? = null,
        @Transient val product: ProfileEffectProduct? = null,
    )

    data class ProfileEffectProduct(
        val items: List<ProfileEffectItem> = emptyList(),
    )

    data class ProfileEffectItem(
        @SerializedName("sku_id")
        val skuId: Long,
        val type: Int,
        @SerializedName(value = "accessibilityLabel", alternate = ["accessibility_label"])
        val accessibilityLabel: String? = null,
        @SerializedName(value = "animationType", alternate = ["animation_type"])
        val animationType: Int,
        @SerializedName(value = "staticFrameSrc", alternate = ["static_frame_src"])
        val staticFrameSrc: String? = null,
        @SerializedName(value = "thumbnailPreviewSrc", alternate = ["thumbnail_preview_src"])
        val thumbnailPreviewSrc: String? = null,
        @SerializedName(value = "reducedMotionSrc", alternate = ["reduced_motion_src"])
        val reducedMotionSrc: String? = null,
        val effects: List<ProfileEffectLayer> = emptyList(),
    ) {
        companion object {
            const val TYPE = 1
        }
    }

    data class ProfileEffectLayer(
        val src: String,
        val loop: Boolean,
        val width: Int? = null,
        val height: Int? = null,
        val duration: Long = 0,
        val start: Long = 0,
        @SerializedName(value = "loopDelay", alternate = ["loop_delay"])
        val loopDelay: Long = 0,
        val position: ProfileEffectPosition? = null,
        @SerializedName(value = "zIndex", alternate = ["z_index"])
        val zIndex: Int = 0,
        @SerializedName(value = "randomizedSources", alternate = ["randomized_sources"])
        val randomizedSources: List<ProfileEffectSource>? = null,
    )

    data class ProfileEffectPosition(
        val x: Int = 0,
        val y: Int = 0,
    )

    data class ProfileEffectSource(val src: String)

    companion object {
        private const val PROFILE_FRAME_TYPE = 3
        const val WIDGET_FAVORITE_GAMES = "favorite_games"
        const val WIDGET_CURRENT_GAMES = "current_games"
        const val WIDGET_WANT_TO_PLAY_GAMES = "want_to_play_games"
        const val WIDGET_PLAYED_GAMES = "played_games"
        const val WIDGET_APPLICATION = "application"
    }
}
