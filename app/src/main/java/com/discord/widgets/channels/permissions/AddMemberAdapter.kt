package com.discord.widgets.channels.permissions

import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import com.discord.R
import com.discord.api.permission.PermissionOverwrite
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.permissions.ChannelPermissionOwnerView
import com.google.android.material.checkbox.MaterialCheckBox

class AddMemberAdapter(recycler: androidx.recyclerview.widget.RecyclerView) :
    MGRecyclerAdapterSimple<AddMemberAdapter.Item>(recycler, false) {
    private var onClick: ((Long, PermissionOverwrite.Type) -> Unit)? = null

    fun setOnClickListener(listener: ((Long, PermissionOverwrite.Type) -> Unit)?) {
        onClick = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = when (viewType) {
        Item.TYPE_CATEGORY -> CategoryHolder(this)
        Item.TYPE_OWNER -> OwnerHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    sealed class Item : MGRecyclerDataPayload {
        data class CategoryItem(val categoryType: CategoryType) : Item() {
            override val type = TYPE_CATEGORY
            override val key = categoryType.name
        }

        data class PermissionOwnerItem(
            val permissionOwner: PermissionOwner,
            val checked: Boolean,
            val addStatus: AddStatus,
        ) : Item() {
            override val type = TYPE_OWNER
            override val key = "${permissionOwner.javaClass.simpleName}:${permissionOwner.entityId}"
        }

        enum class CategoryType { ROLE, MEMBER }
        sealed class AddStatus {
            object CanAdd : AddStatus()
            data class CannotAdd(val reason: Reason) : AddStatus()
            enum class Reason { HAS_GUILD_PERMISSIONS }
        }

        companion object {
            const val TYPE_CATEGORY = 0
            const val TYPE_OWNER = 1
        }
    }

    private class CategoryHolder(adapter: AddMemberAdapter) : MGRecyclerViewHolder<AddMemberAdapter, Item>(
        R.layout.widget_channel_settings_add_member_category,
        adapter,
    ) {
        override fun onConfigure(position: Int, data: Item) {
            val category = data as Item.CategoryItem
            itemView.findViewById<TextView>(R.id.label).setText(
                if (category.categoryType == Item.CategoryType.ROLE) R.string.roles else R.string.members,
            )
        }
    }

    private class OwnerHolder(adapter: AddMemberAdapter) : MGRecyclerViewHolder<AddMemberAdapter, Item>(
        R.layout.widget_channel_settings_add_member_item,
        adapter,
    ) {
        override fun onConfigure(position: Int, data: Item) {
            val item = data as Item.PermissionOwnerItem
            itemView.findViewById<ChannelPermissionOwnerView>(R.id.channel_permission_owner_view)
                .configure(item.permissionOwner)
            itemView.findViewById<MaterialCheckBox>(R.id.checkbox).apply {
                isChecked = item.checked
                isClickable = false
            }
            val enabled = item.addStatus === Item.AddStatus.CanAdd
            itemView.findViewById<android.view.View>(R.id.container).apply {
                alpha = if (enabled) 1f else 0.3f
                setOnClickListener {
                    if (!enabled) {
                        Toast.makeText(context, R.string.channel_permissions_cannot_edit_moderators, Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val type = if (item.permissionOwner is PermissionOwner.Role) {
                        PermissionOverwrite.Type.ROLE
                    } else {
                        PermissionOverwrite.Type.MEMBER
                    }
                    adapter.onClick?.invoke(item.permissionOwner.entityId, type)
                }
            }
        }
    }
}
