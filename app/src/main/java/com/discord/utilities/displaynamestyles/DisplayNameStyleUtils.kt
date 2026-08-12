package com.discord.utilities.displaynamestyles

import com.discord.api.user.DisplayNameStyles
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.api.user.User as ApiUser

/** Which style a name should be drawn with, given where it is being drawn. */
object DisplayNameStyleUtils {

    /**
     * [guildMember]'s style if it has one, otherwise [user]'s.
     *
     * A per-server style replaces the account-wide one outright rather than filling in the fields
     * it leaves unset — the same rule the rest of the guild-member profile follows.
     */
    @JvmStatic
    fun resolve(user: User?, guildMember: GuildMember? = null): DisplayNameStyles? =
        guildMember?.displayNameStyles ?: user?.displayNameStyles

    /**
     * The same rule for a caller holding the wire user rather than the model one.
     *
     * A message keeps its author in wire form, and converting one per bound row to read a single
     * field would allocate down the whole list.
     */
    @JvmStatic
    fun resolve(user: ApiUser?, guildMember: GuildMember? = null): DisplayNameStyles? =
        guildMember?.displayNameStyles ?: user?.displayNameStyles?.value
}
