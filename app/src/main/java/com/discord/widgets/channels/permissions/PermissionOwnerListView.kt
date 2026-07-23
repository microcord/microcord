package com.discord.widgets.channels.permissions

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.views.permissions.ChannelPermissionOwnerView

class PermissionOwnerListView : RecyclerView {
    private val permissionOwnerAdapter: Adapter

    constructor(context: Context) : super(context) {
        permissionOwnerAdapter = Adapter()
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        permissionOwnerAdapter = Adapter()
        initialize()
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
        super(context, attrs, defStyleAttr) {
        permissionOwnerAdapter = Adapter()
        initialize()
    }

    private fun initialize() {
        layoutManager = LinearLayoutManager(context, VERTICAL, false)
        adapter = permissionOwnerAdapter
    }

    fun setData(
        permissionOwners: List<Item>,
        onRemoveClicked: (PermissionOwner) -> Unit,
    ) {
        permissionOwnerAdapter.onRemoveClicked = onRemoveClicked
        permissionOwnerAdapter.setData(permissionOwners)
    }

    data class Item(
        val permissionOwner: PermissionOwner,
        val removeStatus: RemoveStatus,
    )

    sealed class RemoveStatus {
        object CanRemove : RemoveStatus()
        object IsRemoving : RemoveStatus()
        data class CannotRemove(val reason: Reason) : RemoveStatus() {
            enum class Reason {
                IS_GUILD_OWNER,
                IS_ADMINISTRATOR,
                IS_NOT_OVERRIDE,
                HAS_NO_PERMISSION,
            }
        }
    }

    private class Adapter : RecyclerView.Adapter<PermissionOwnerViewHolder>() {
        var onRemoveClicked: (PermissionOwner) -> Unit = {}
        private var data: List<Item> = emptyList()

        fun setData(data: List<Item>) {
            this.data = data
            notifyDataSetChanged()
        }

        override fun getItemCount() = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermissionOwnerViewHolder {
            val row = LayoutInflater.from(parent.context)
                .inflate(R.layout.removable_permission_owner_view, parent, false)
            return PermissionOwnerViewHolder(row as ViewGroup)
        }

        override fun onBindViewHolder(holder: PermissionOwnerViewHolder, position: Int) {
            val item = data[position]
            holder.configure(item) { onRemoveClicked(item.permissionOwner) }
        }
    }

    private class PermissionOwnerViewHolder(root: ViewGroup) : RecyclerView.ViewHolder(root) {
        private val owner: ChannelPermissionOwnerView =
            requireNotNull(root.findViewById(R.id.permission_owner_view))
        private val remove: ImageView = requireNotNull(root.findViewById(R.id.remove))

        fun configure(item: Item, onRemoveClicked: () -> Unit) {
            owner.configure(item.permissionOwner)
            remove.alpha = if (item.removeStatus is RemoveStatus.CanRemove) 1f else 0.3f
            remove.setOnClickListener {
                when (val status = item.removeStatus) {
                    RemoveStatus.CanRemove -> onRemoveClicked()
                    RemoveStatus.IsRemoving -> Unit
                    is RemoveStatus.CannotRemove -> when (status.reason) {
                        RemoveStatus.CannotRemove.Reason.IS_NOT_OVERRIDE ->
                            showToast(R.string.channel_permissions_remove_not_overwrite)
                        RemoveStatus.CannotRemove.Reason.HAS_NO_PERMISSION ->
                            showToast(R.string.channel_permissions_cannot_edit_moderators)
                        RemoveStatus.CannotRemove.Reason.IS_GUILD_OWNER,
                        RemoveStatus.CannotRemove.Reason.IS_ADMINISTRATOR,
                        -> Unit
                    }
                }
            }
        }

        private fun showToast(message: Int) {
            Toast.makeText(itemView.context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
