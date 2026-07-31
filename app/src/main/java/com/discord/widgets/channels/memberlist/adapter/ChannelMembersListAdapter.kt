package com.discord.widgets.channels.memberlist.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.discord.databinding.WidgetChannelMembersListItemAddOrLeaveBinding
import com.discord.databinding.WidgetChannelMembersListItemHeaderBinding
import com.discord.databinding.WidgetChannelMembersListItemLoadingBinding
import com.discord.databinding.WidgetChannelMembersListItemPlaceholderHeaderBinding
import com.discord.databinding.WidgetChannelMembersListItemUserBinding
import com.discord.models.presence.Presence
import com.discord.utilities.views.StickyHeaderItemDecoration
import com.discord.widgets.channels.memberlist.WidgetChannelMembersListViewModel

class ChannelMembersListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(),
    StickyHeaderItemDecoration.StickyHeaderAdapter {
    private var memberList: WidgetChannelMembersListViewModel.MemberList = EmptyMemberList
    private var onAddMemberClicked: (() -> Unit)? = null
    private var onJoinLeaveThreadClicked: (() -> Unit)? = null
    private var onUserClicked: ((Long) -> Unit)? = null
    private var onUserLongClicked: ((Long) -> Unit)? = null
    private var stickyHeader: ChannelMembersListViewHolderHeader? = null
    private var stickyRoleHeader: ChannelMembersListViewHolderRoleHeader? = null
    private var stickyPlaceholder: View? = null
    private var recyclerView: RecyclerView? = null

    init {
        setHasStableIds(true)
    }

    fun dispose() = Unit

    override fun getItemCount() = memberList.size
    override fun getItemViewType(position: Int) = memberList[position].type.ordinal
    override fun getHeaderPositionForItem(itemPosition: Int) = memberList.getHeaderPositionForItem(itemPosition)
    override fun isHeader(position: Int) = memberList[position].let {
        it is Item.Header || it is Item.RoleHeader || it is Item.PlaceholderHeader
    }

    override fun getItemId(position: Int): Long = when (val item = memberList[position]) {
        is Item.Member -> item.userId
        is Item.RoleHeader -> item.roleId
        is Item.Header -> when (item.headerType) {
            Item.Header.Type.ONLINE -> -2L
            Item.Header.Type.OFFLINE -> -3L
            Item.Header.Type.GROUP_DM -> -4L
        }
        is Item.PlaceholderHeader -> -5L
        is Item.PlaceholderMember -> -(position + 6L)
        is Item.AddMember, is Item.JoinLeaveThread -> -1L
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (ViewType.fromOrdinal(viewType)) {
            ViewType.ROLE_HEADER -> ChannelMembersListViewHolderRoleHeader(
                WidgetChannelMembersListItemHeaderBinding.inflate(inflater, parent, false),
            )
            ViewType.PLACEHOLDER_HEADER -> ChannelMembersListViewHolderPlaceholderHeader(
                WidgetChannelMembersListItemPlaceholderHeaderBinding.inflate(inflater, parent, false),
            )
            ViewType.HEADER -> ChannelMembersListViewHolderHeader(
                WidgetChannelMembersListItemHeaderBinding.inflate(inflater, parent, false),
            )
            ViewType.MEMBER -> ChannelMembersListViewHolderMember(
                WidgetChannelMembersListItemUserBinding.inflate(inflater, parent, false),
            )
            ViewType.ADD_MEMBER -> ChannelMembersListViewHolderAdd(
                WidgetChannelMembersListItemAddOrLeaveBinding.inflate(inflater, parent, false),
            )
            ViewType.LOADING -> ChannelMembersListViewHolderLoading(
                WidgetChannelMembersListItemLoadingBinding.inflate(inflater, parent, false),
            )
            ViewType.JOIN_LEAVE_THREAD -> ChannelMembersListViewHolderJoinLeaveThread(
                WidgetChannelMembersListItemAddOrLeaveBinding.inflate(inflater, parent, false),
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = memberList[position]) {
            is Item.Header -> (holder as ChannelMembersListViewHolderHeader).bind(item)
            is Item.RoleHeader -> (holder as ChannelMembersListViewHolderRoleHeader).bind(item)
            is Item.Member -> (holder as ChannelMembersListViewHolderMember).bind(
                item,
                { onUserClicked?.invoke(item.userId) },
                onUserLongClicked?.let { callback -> { callback(item.userId) } },
            )
            is Item.AddMember -> (holder as ChannelMembersListViewHolderAdd).bind({ onAddMemberClicked?.invoke() }, item.title)
            is Item.JoinLeaveThread -> (holder as ChannelMembersListViewHolderJoinLeaveThread).bind(
                { onJoinLeaveThreadClicked?.invoke() },
                item.isThreadJoined,
            )
            is Item.PlaceholderMember -> (holder as ChannelMembersListViewHolderLoading).bind(position)
            is Item.PlaceholderHeader -> Unit
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
        val inflater = LayoutInflater.from(recyclerView.context)
        val headerBinding = WidgetChannelMembersListItemHeaderBinding.inflate(inflater, recyclerView, false)
        stickyHeader = ChannelMembersListViewHolderHeader(headerBinding)
        stickyRoleHeader = ChannelMembersListViewHolderRoleHeader(headerBinding)
        stickyPlaceholder = WidgetChannelMembersListItemPlaceholderHeaderBinding.inflate(inflater, recyclerView, false).root
    }

    override fun getAndBindHeaderView(position: Int): View? = when (val item = memberList[position]) {
        is Item.Header -> stickyHeader?.also { it.bind(item) }?.itemView
        is Item.RoleHeader -> stickyRoleHeader?.also { it.bind(item) }?.itemView
        is Item.PlaceholderHeader -> stickyPlaceholder
        else -> null
    }?.also { view -> recyclerView?.let { StickyHeaderItemDecoration.LayoutManager.layoutHeaderView(it, view) } }

    fun setData(listId: String, rows: WidgetChannelMembersListViewModel.MemberList, forceOverwrite: Boolean) {
        val previous = memberList
        memberList = rows
        if (forceOverwrite || previous.listId != listId || maxOf(previous.size, rows.size) > MAX_COMPUTE_DIFF_THRESHOLD) {
            notifyDataSetChanged()
        } else {
            DiffUtil.calculateDiff(MemberListDiff(previous, rows)).dispatchUpdatesTo(this)
        }
    }

    fun setOnAddMemberClicked(addMember: () -> Unit) { onAddMemberClicked = addMember }
    fun setOnJoinLeaveThreadClicked(joinLeaveThread: () -> Unit) { onJoinLeaveThreadClicked = joinLeaveThread }
    fun setOnUserClicked(onUserClicked: ((Long) -> Unit)?) { this.onUserClicked = onUserClicked }
    fun setOnUserLongClicked(onUserLongClicked: ((Long) -> Unit)?) { this.onUserLongClicked = onUserLongClicked }

    sealed class Item(val type: ViewType, open val rowId: Any) {
        data class AddMember(override val rowId: String, val title: Int) : Item(ViewType.ADD_MEMBER, rowId)
        data class Header(override val rowId: String, val headerType: Type, val memberCount: Int) : Item(ViewType.HEADER, rowId) {
            enum class Type { ONLINE, OFFLINE, GROUP_DM }
        }
        data class JoinLeaveThread(override val rowId: String, val isThreadJoined: Boolean) :
            Item(ViewType.JOIN_LEAVE_THREAD, rowId)
        data class Member(
            val userId: Long,
            val guildId: Long?,
            val name: String,
            val isBot: Boolean,
            val tagText: Int?,
            val tagVerified: Boolean,
            val presence: Presence?,
            val color: Int?,
            val avatarUrl: String?,
            val showOwnerIndicator: Boolean,
            val premiumSince: String?,
            val isApplicationStreaming: Boolean,
            val canDisplayStatusEmoji: Boolean,
            val userFlags: Int,
        ) : Item(ViewType.MEMBER, userId)
        data class PlaceholderHeader(val listId: String) : Item(ViewType.PLACEHOLDER_HEADER, "placeholder:$listId")
        data class PlaceholderMember(private val placeholderSize: Float) : Item(ViewType.LOADING, "")
        data class RoleHeader(val roleId: Long, val roleName: String, val memberCount: Int, val guildId: Long?) :
            Item(ViewType.ROLE_HEADER, roleId)
    }

    enum class ViewType {
        ROLE_HEADER,
        PLACEHOLDER_HEADER,
        HEADER,
        MEMBER,
        ADD_MEMBER,
        LOADING,
        JOIN_LEAVE_THREAD;

        companion object {
            private val cachedValues = values()
            fun fromOrdinal(ordinal: Int) = cachedValues[ordinal]
        }
    }

    private class MemberListDiff(
        private val old: WidgetChannelMembersListViewModel.MemberList,
        private val new: WidgetChannelMembersListViewModel.MemberList,
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition].rowId == new[newItemPosition].rowId
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) =
            old[oldItemPosition] == new[newItemPosition]
    }

    private object EmptyMemberList : WidgetChannelMembersListViewModel.MemberList {
        override val listId = ""
        override val size = 0
        override fun get(index: Int): Item = throw IndexOutOfBoundsException("Index: $index")
        override fun getHeaderPositionForItem(itemPosition: Int): Int? = null
    }
}
