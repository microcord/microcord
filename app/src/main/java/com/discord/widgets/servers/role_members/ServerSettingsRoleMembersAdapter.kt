package com.discord.widgets.servers.role_members

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple

class ServerSettingsRoleMembersAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<ServerSettingsRoleMemberAdapterItem>(recycler) {

    var removeMemberClickListener: ((ServerSettingsRoleMemberAdapterItem) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerSettingsRoleMembersViewHolder =
        if (viewType == ServerSettingsRoleMemberAdapterItem.TYPE_MEMBER) {
            ServerSettingsRoleMembersViewHolder(this)
        } else {
            throw invalidViewTypeException(viewType)
        }

    fun setItems(items: List<ServerSettingsRoleMemberAdapterItem>) = setData(items)
}
