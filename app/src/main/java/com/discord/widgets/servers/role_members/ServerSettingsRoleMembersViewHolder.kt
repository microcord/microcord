package com.discord.widgets.servers.role_members

import android.widget.ImageView
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.permissions.ChannelPermissionOwnerView

class ServerSettingsRoleMembersViewHolder(
    adapter: ServerSettingsRoleMembersAdapter,
) : MGRecyclerViewHolder<ServerSettingsRoleMembersAdapter, ServerSettingsRoleMemberAdapterItem>(
    R.layout.removable_permission_owner_view,
    adapter,
) {
    private val owner: ChannelPermissionOwnerView = itemView.findViewById(R.id.permission_owner_view)
    private val remove: ImageView = itemView.findViewById(R.id.remove)

    override fun onConfigure(position: Int, data: ServerSettingsRoleMemberAdapterItem) {
        super.onConfigure(position, data)
        owner.configure(data.permissionOwner)
        remove.setOnClickListener { adapter.removeMemberClickListener?.invoke(data) }
    }
}
