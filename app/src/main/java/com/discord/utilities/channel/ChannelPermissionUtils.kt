@file:JvmName("ChannelPermissionUtils")

package com.discord.utilities.channel

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.utilities.permissions.PermissionUtils

/**
 * The subtitle shown under a role in the private-channel member list.
 *
 * Administrators can see the channel regardless of its overwrites, so they are labelled
 * differently from roles that were explicitly granted access.
 */
@StringRes
fun getChannelPermissionOwnerRoleLabel(role: GuildRole): Int =
    if (PermissionUtils.can(Permission.ADMINISTRATOR, role.permissions)) {
        R.string.private_channel_add_members_modal_row_administrator
    } else {
        R.string.private_channel_add_members_modal_row_role
    }
