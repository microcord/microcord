package com.discord.widgets.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.views.user.SettingsMemberView

class SimpleMembersAdapter(var recycler: RecyclerView) :
    RecyclerView.Adapter<SimpleMembersAdapter.MemberAdapterItem>() {
    private var data: List<MemberItem> = emptyList()
    private var onUserClickedListener: ((User) -> Unit)? = null

    override fun getItemCount() = data.size

    override fun getItemViewType(position: Int) = data[position].type

    fun getItem(position: Int) = data[position]

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberAdapterItem {
        require(viewType == MemberItem.TYPE_MEMBER) { "Invalid view type: $viewType" }
        return MemberAdapterItem(this)
    }

    override fun onBindViewHolder(holder: MemberAdapterItem, position: Int) {
        holder.configure(position, data[position])
    }

    fun setData(data: List<MemberItem>, onUserClickedListener: (User) -> Unit) {
        val oldData = this.data
        this.data = data
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldData.size
            override fun getNewListSize() = data.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldData[oldItemPosition].key == data[newItemPosition].key

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldData[oldItemPosition] == data[newItemPosition]
        }).dispatchUpdatesTo(this)
        this.onUserClickedListener = onUserClickedListener
    }

    class MemberAdapterItem(private val adapter: SimpleMembersAdapter) : RecyclerView.ViewHolder(
        LayoutInflater.from(adapter.recycler.context)
            .inflate(R.layout.simple_member_list_item, adapter.recycler, false),
    ) {
        private val memberView = requireNotNull<SettingsMemberView>(itemView.findViewById(R.id.member_view))

        internal fun configure(position: Int, data: MemberItem) {
            memberView.configure(data.user, data.guildMember)
            itemView.setOnClickListener { adapter.onUserClickedListener?.invoke(data.user) }
        }
    }

    data class MemberItem(
        val user: User,
        val guildMember: GuildMember?,
    ) {
        val key = user.id.toString()
        val type = TYPE_MEMBER

        companion object {
            const val TYPE_MEMBER = 0
        }
    }
}
