package com.discord.widgets.chat.overlay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetManageReactionsResultUserBinding
import com.discord.databinding.WidgetTypingUsersSheetBinding
import com.discord.models.guild.UserGuildMember
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.user.usersheet.WidgetUserSheet
import rx.subscriptions.CompositeSubscription

/** Everyone typing in the open channel, for when the typing bar is too crowded to name them. */
class WidgetTypingUsersSheet : AppBottomSheet() {
    private val adapter = Adapter(::openProfile)

    override fun getContentViewResId(): Int = R.layout.widget_typing_users_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        WidgetTypingUsersSheetBinding.bind(view).typingUsersList.adapter = adapter
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        ChatTypingModel.get()
            .map { (it as? ChatTypingModel.Typing)?.typingUsers.orEmpty() }
            .ui(this)
            .appSubscribe(WidgetTypingUsersSheet::class.java) { users ->
                if (users.isEmpty()) dismiss() else adapter.submitList(users)
            }
    }

    private fun openProfile(userId: Long) {
        val channel = StoreStream.getChannelsSelected().getSelectedChannel()
        WidgetUserSheet.show(parentFragmentManager, userId, channel?.id, channel?.guildId?.takeIf { it != 0L })
    }

    private class Adapter(private val onClick: (Long) -> Unit) :
        ListAdapter<UserGuildMember, Adapter.Holder>(Diff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetManageReactionsResultUserBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val member = getItem(position)
            with(holder.binding) {
                manageReactionsResultUserName.text = member.getNickOrUserName()
                IconUtils.setIcon(
                    manageReactionsResultUserAvatar,
                    member.user,
                    R.dimen.avatar_size_standard,
                    guildMember = member.guildMember,
                )
                manageReactionsResultRemoveReaction.visibility = View.GONE
                root.setOnClickListener { onClick(member.user.id) }
            }
        }

        class Holder(val binding: WidgetManageReactionsResultUserBinding) : RecyclerView.ViewHolder(binding.root)

        private object Diff : DiffUtil.ItemCallback<UserGuildMember>() {
            override fun areItemsTheSame(old: UserGuildMember, new: UserGuildMember) = old.user.id == new.user.id
            override fun areContentsTheSame(old: UserGuildMember, new: UserGuildMember) = old == new
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) =
            WidgetTypingUsersSheet().show(fragmentManager, WidgetTypingUsersSheet::class.java.name)
    }
}
