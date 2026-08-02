package com.discord.api.user

import com.discord.api.application.ProfileApplication
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.guildmember.GuildMember
import com.google.gson.annotations.SerializedName

data class AvatarDecorationData(
    val asset: String,
    val skuId: Long,
    val expiresAt: Long? = null,
) {
    fun cdnUrl(size: Int = 256, passthrough: Boolean = true): String? = asset
        .takeIf(String::isNotBlank)
        ?.let { "https://cdn.discordapp.com/avatar-decoration-presets/$it.png?size=$size&passthrough=$passthrough" }
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
) {
    data class GuildReference(val id: Long, val nick: String?)

    data class ProfileBadge(
        val id: String,
        val description: String,
        val icon: String,
        val link: String? = null,
    )

    data class ProfileMetadata(
        val guildId: Long? = null,
        val pronouns: String = "",
        val bio: String? = null,
        val banner: String? = null,
        val accentColor: Int? = null,
        val themeColors: List<Int>? = null,
        val popoutAnimationParticleType: Long? = null,
        val emoji: ProfileEmoji? = null,
        val profileEffect: ProfileEffect? = null,
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
}
