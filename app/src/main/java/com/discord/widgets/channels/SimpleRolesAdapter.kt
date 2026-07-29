package com.discord.widgets.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.utilities.guilds.RoleUtils

internal class SimpleRolesAdapter(private val recycler: RecyclerView) :
    RecyclerView.Adapter<SimpleRolesAdapter.RoleAdapterItem>() {
    private var data: List<RoleItem> = emptyList()
    private var onRoleClickedListener: ((GuildRole) -> Unit)? = null

    override fun getItemCount(): Int = data.size

    override fun getItemViewType(position: Int): Int = data[position].type

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoleAdapterItem {
        require(viewType == RoleItem.TYPE_ROLE) { "Invalid view type: $viewType" }
        return RoleAdapterItem(this)
    }

    override fun onBindViewHolder(holder: RoleAdapterItem, position: Int) {
        holder.configure(data[position])
    }

    fun setData(data: List<RoleItem>, onRoleClickedListener: (GuildRole) -> Unit) {
        val oldData = this.data
        this.data = data
        this.onRoleClickedListener = onRoleClickedListener
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldData.size
            override fun getNewListSize(): Int = data.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldData[oldItemPosition].key == data[newItemPosition].key

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldData[oldItemPosition] == data[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    class RoleAdapterItem(private val adapter: SimpleRolesAdapter) : RecyclerView.ViewHolder(
        LayoutInflater.from(adapter.recycler.context)
            .inflate(R.layout.simple_role_list_item, adapter.recycler, false),
    ) {
        private val roleName = itemView as TextView

        fun configure(data: RoleItem) {
            roleName.text = data.role.name
            roleName.setTextColor(RoleUtils.getRoleColor(data.role, roleName.context))
            itemView.setOnClickListener { adapter.onRoleClickedListener?.invoke(data.role) }
        }
    }

    data class RoleItem(val role: GuildRole) : Comparable<RoleItem> {
        val key: String = role.id.toString()
        val type: Int = TYPE_ROLE

        override fun compareTo(other: RoleItem): Int = RoleUtils.ROLE_COMPARATOR.compare(role, other.role)

        companion object {
            const val TYPE_ROLE = 0
        }
    }
}
