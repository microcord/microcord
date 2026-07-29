package com.discord.widgets.channels.permissions

import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppViewModel
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui

class ConfirmRemovePermissionDialogViewModel(
    private val permissionOwner: PermissionOwner,
    private val channelId: Long,
) : AppViewModel<ConfirmRemovePermissionDialogViewModel.ViewState>(ViewState.Default(false)) {
    internal fun updatedOverwrite(): PermissionOverwrite? {
        val type = if (permissionOwner is PermissionOwner.Role) PermissionOverwrite.Type.ROLE else PermissionOverwrite.Type.MEMBER
        val overwrite = StoreStream.getChannels().getChannel(channelId)?.permissionOverwrites.orEmpty()
            .firstOrNull { it.id == permissionOwner.entityId && it.type == type } ?: return null
        return removeModerator(overwrite)
    }

    fun submit() {
        val api = RestAPI.apiOrNull ?: return
        updateViewState(ViewState.Default(true))
        val overwrite = updatedOverwrite()
        val request = if (overwrite == null) api.deletePermissionOverwrites(channelId, permissionOwner.entityId)
        else api.updatePermissionOverwrites(channelId, overwrite.id, RestAPIParams.ChannelPermissionOverwrites.fromPermissionOverwrite(overwrite))
        request.restSubscribeOn().ui(this).subscribe(
            { },
            { updateViewState(ViewState.Default(false)) },
            { updateViewState(ViewState.Complete) },
        )
    }

    sealed class ViewState {
        object Complete : ViewState()
        data class Default(val isSubmitting: Boolean) : ViewState()
    }

    companion object {
        internal fun removeModerator(overwrite: PermissionOverwrite): PermissionOverwrite? = overwrite.copy(
            allow = overwrite.allow and Permission.MODERATE_STAGE_CHANNEL.inv(),
        ).takeUnless { it.allow == 0L && it.deny == 0L }
    }
}
