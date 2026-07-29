package com.discord.utilities.permissions

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionBit

class PermissionsManager {
    fun can(permission: PermissionBit, computedPermissions: Long?): Boolean =
        PermissionUtils.can(permission, computedPermissions)
}

class VideoPermissionsManager(
    private val permissionsManager: PermissionsManager = PermissionsManager(),
) {
    fun hasVideoPermission(
        channel: Channel,
        channelIdAFK: Long?,
        computedPermissions: Long?,
    ): Boolean =
        channel.id != channelIdAFK &&
            (channel.isPrivate() || permissionsManager.can(Permission.STREAM, computedPermissions))
}
