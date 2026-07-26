package com.discord.utilities.guilds

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.models.member.GuildMember
import java.util.regex.Pattern

/**
 * Role ranking, role colours, and role-tag predicates.
 *
 * Rank is what gates moderation: a member may only act on someone whose highest role is
 * strictly below their own. Colour is what tints member names in the chat and member
 * list, and comes from the member's highest *hoisted* role.
 */
object RoleUtils {

    private val ROLE_MENTION_RE: Pattern = Pattern.compile("<@&(\\d+)>")

    private const val OPAQUE_ALPHA = 0xFF000000.toInt()

    /**
     * Orders roles from highest rank to lowest, so `compare(a, b) < 0` means "a outranks
     * b". A `null` role ranks below every real role.
     *
     * Discord orders by descending position, and breaks ties with the older role first.
     * Role ids are snowflakes, so comparing ids is the same ordering as comparing the
     * creation timestamps the reference parses out of them.
     */
    @JvmStatic
    val ROLE_COMPARATOR: Comparator<GuildRole?> = Comparator { first, second ->
        when {
            first != null && second != null ->
                if (second.position != first.position) {
                    second.position - first.position
                } else {
                    first.id.compareTo(second.id)
                }
            first == null && second == null -> 0
            first == null -> 1
            else -> -1
        }
    }

    /** Whether [role1] outranks [role2]. A real role always outranks `null`. */
    @JvmStatic
    fun rankIsHigher(role1: GuildRole?, role2: GuildRole?): Boolean =
        ROLE_COMPARATOR.compare(role1, role2) < 0

    /** Whether the two roles sit at the same rank. */
    @JvmStatic
    fun rankEquals(role1: GuildRole?, role2: GuildRole?): Boolean =
        ROLE_COMPARATOR.compare(role1, role2) == 0

    /** The highest-ranked role the member holds, or `null` if they hold none. */
    @JvmStatic
    fun getHighestRole(guildRoles: Map<Long, GuildRole>, member: GuildMember?): GuildRole? =
        if (member == null) null else getHighestRole(guildRoles, member.getRoles())

    /** The highest-ranked role among [roleIds]. */
    @JvmStatic
    fun getHighestRole(guildRoles: Map<Long, GuildRole>, roleIds: Collection<Long>?): GuildRole? {
        if (roleIds == null) return null
        var highest: GuildRole? = null
        roleIds.forEach { roleId ->
            val role = guildRoles[roleId]
            if (role != null && rankIsHigher(role, highest)) highest = role
        }
        return highest
    }

    /**
     * The highest-ranked *hoisted* role the member holds. This is the role that colours
     * their name and that groups them in the member list.
     */
    @JvmStatic
    fun getHighestHoistedRole(guildRoles: Map<Long, GuildRole>, member: GuildMember?): GuildRole? {
        if (member == null) return null
        val hoistedRoleIds = member.getRoles().filter { guildRoles[it]?.hoist == true }
        return getHighestRole(guildRoles, hoistedRoleIds)
    }

    /** Whether the role uses the "no colour" sentinel and should inherit the default. */
    @JvmStatic
    fun isDefaultColor(role: GuildRole): Boolean = role.color == 0

    /**
     * The role's colour forced to full opacity, falling back to [defaultColor] when the
     * role has none.
     */
    @JvmStatic
    @ColorInt
    @JvmOverloads
    fun getOpaqueColor(role: GuildRole?, @ColorInt defaultColor: Int = 0): Int {
        val color = if (role != null && role.color != 0) role.color else defaultColor
        return OPAQUE_ALPHA + color
    }

    /**
     * The colour to render text in for this role: its own colour, or [defaultColor] when
     * it has none, or the standard muted name colour when no default is supplied.
     */
    @JvmStatic
    @ColorInt
    @JvmOverloads
    fun getRoleColor(role: GuildRole, context: Context, @ColorInt defaultColor: Int? = null): Int =
        if (isDefaultColor(role)) {
            defaultColor ?: ContextCompat.getColor(context, R.color.primary_300)
        } else {
            getOpaqueColor(role)
        }

    /** Whether [input] contains at least one `<@&roleId>` mention. */
    @JvmStatic
    fun containsRoleMentions(input: String?): Boolean =
        input != null && ROLE_MENTION_RE.matcher(input).find()

    /** Whether the role is backed by a role-subscription listing. */
    @JvmStatic
    fun hasSubscriptionListingId(role: GuildRole): Boolean =
        !role.tags?.subscriptionListingId.isNullOrBlank()

    /**
     * Whether the role is a role-subscription tier that is currently on sale or already
     * has subscribers, i.e. one that should be shown in the subscription surfaces.
     */
    @JvmStatic
    fun isSubscriptionRolePurchasableOrHasSubscribers(role: GuildRole): Boolean =
        hasSubscriptionListingId(role) && role.tags?.isPurchasableOrHasSubscribers == true
}
