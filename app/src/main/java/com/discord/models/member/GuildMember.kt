package com.discord.models.member

import android.graphics.Color
import androidx.annotation.ColorInt
import com.discord.api.guildmember.GuildMember as ApiGuildMember
import com.discord.api.role.GuildRole
import com.discord.api.user.AvatarDecorationData
import com.discord.api.user.Collectibles
import com.discord.api.user.DisplayNameStyles
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.user.User
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.feature.RoleColorMode
import com.discord.utilities.feature.RoleColors

data class GuildMember(
    /** The highest coloured role's colour, kept even while role colours are hidden. */
    @ColorInt val roleColor: Int,
    val hoistRoleId: Long,
    private val roles: List<Long>?,
    val nick: String?,
    val premiumSince: String?,
    val pending: Boolean,
    val joinedAt: UtcDateTime?,
    val guildId: Long,
    val userId: Long,
    val avatarHash: String? = null,
    val bannerHash: String? = null,
    val bio: String? = null,
    val communicationDisabledUntil: UtcDateTime? = null,
    val collectibles: Collectibles? = null,
    val displayNameStyles: DisplayNameStyles? = null,
    val avatarDecoration: AvatarDecorationData? = null,
    val flags: Int = 0,
) {
    constructor() : this(0, 0, null, null, null, false, null, 0, 0)

    /** [roleColor] when names are drawn in role colours, otherwise "no colour"; see [RoleColors]. */
    @get:ColorInt
    val color: Int get() = if (RoleColors.mode() == RoleColorMode.NAMES) roleColor else Color.BLACK

    fun getRoles(): List<Long> = roles ?: emptyRoles

    fun hasAvatar() = !FeatureFlags.disableServerProfilesEnabled() &&
        !avatarHash.isNullOrEmpty() && guildId > 0 && userId > 0

    fun hasBanner() = !FeatureFlags.disableServerProfilesEnabled() &&
        !bannerHash.isNullOrEmpty() && guildId > 0 && userId > 0

    fun identityNick(): String? = nick.takeUnless { FeatureFlags.disableServerProfilesEnabled() }
    fun identityBannerHash(): String? = bannerHash.takeUnless { FeatureFlags.disableServerProfilesEnabled() }
    fun identityBio(): String? = bio.takeUnless { FeatureFlags.disableServerProfilesEnabled() }
    fun identityCollectibles(): Collectibles? = collectibles.takeUnless { FeatureFlags.disableServerProfilesEnabled() }
    fun identityAvatarDecoration(): AvatarDecorationData? =
        avatarDecoration.takeUnless { FeatureFlags.disableServerProfilesEnabled() }

    fun isCommunicationDisabled() =
        communicationDisabledUntil?.dateTimeMillis?.let { it > System.currentTimeMillis() } == true

    companion object {
        private val emptyRoles = emptyList<Long>()

        fun from(
            apiGuildMember: ApiGuildMember,
            guildId: Long,
            guildRoles: Map<Long, GuildRole>? = null,
        ): GuildMember {
            val roles = apiGuildMember.roles
            val resolvedRoles = roles.mapNotNull { guildRoles?.get(it) }
            val colorRole = resolvedRoles.filter { it.color != 0 }.maxWithOrNull(roleComparator)
            val hoistRole = resolvedRoles.filter(GuildRole::hoist).maxWithOrNull(roleComparator)
            return GuildMember(
                roleColor = colorRole?.color?.or(Color.BLACK) ?: Color.BLACK,
                hoistRoleId = hoistRole?.id ?: 0,
                roles = roles.takeIf(List<Long>::isNotEmpty),
                nick = apiGuildMember.nick,
                premiumSince = apiGuildMember.premiumSince,
                pending = apiGuildMember.pending,
                joinedAt = apiGuildMember.joinedAt,
                guildId = guildId,
                userId = apiGuildMember.user.id,
                avatarHash = apiGuildMember.avatar,
                bannerHash = apiGuildMember.banner,
                bio = apiGuildMember.bio,
                communicationDisabledUntil = apiGuildMember.communicationDisabledUntil,
                collectibles = apiGuildMember.collectibles,
                displayNameStyles = apiGuildMember.displayNameStyles,
                avatarDecoration = apiGuildMember.avatarDecorationData,
                flags = apiGuildMember.flags,
            )
        }

        fun getHighestRoleIconRole(roles: List<Long>, guildRoles: Map<Long, GuildRole>?): GuildRole? =
            roles.mapNotNull { guildRoles?.get(it) }
                .filter { it.icon != null || it.unicodeEmoji != null }
                .maxWithOrNull(roleComparator)

        @JvmStatic
        fun getColor(member: GuildMember?, @ColorInt defaultColor: Int) =
            getColor(member?.color ?: Color.BLACK, defaultColor)

        @JvmStatic
        fun getColor(@ColorInt color: Int, @ColorInt defaultColor: Int) =
            if (color == Color.BLACK) defaultColor else color

        @JvmStatic
        fun compareUserNames(
            user1: User,
            user2: User,
            member1: GuildMember?,
            member2: GuildMember?,
        ): Int {
            val name1 = user1.getDisplayName(member1?.identityNick())
            val name2 = user2.getDisplayName(member2?.identityNick())
            return name1.compareTo(name2, ignoreCase = true).takeUnless { it == 0 }
                ?: user1.id.compareTo(user2.id)
        }

        @JvmStatic
        fun getNickOrUsername(member: GuildMember?, user: User) = user.getDisplayName(member?.identityNick())

        private val roleComparator = compareBy<GuildRole>({ it.position }, { it.id })
    }
}
