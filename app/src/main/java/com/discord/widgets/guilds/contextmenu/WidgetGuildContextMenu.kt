package com.discord.widgets.guilds.contextmenu

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guilds.profile.WidgetGuildProfileSheet
import com.discord.widgets.servers.WidgetServerNotifications

/** The guild long-press menu: mark as read, notifications, leave, and the guild profile sheet. */
class WidgetGuildContextMenu : AnchoredContextMenu(R.layout.widget_guild_context_menu) {

    private val guildId: Long
        get() = requireArguments().getLong(EXTRA_GUILD_ID)

    private val viewModel: GuildContextMenuViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GuildContextMenuViewModel(guildId) as T
        }
    }

    private lateinit var cardView: CardView
    private lateinit var header: TextView
    private lateinit var markAsRead: TextView
    private lateinit var notifications: TextView
    private lateinit var leaveGuild: TextView
    private lateinit var moreOptions: TextView

    override val card: CardView get() = cardView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        cardView = view.findViewById(R.id.guild_context_menu_card)
        header = view.findViewById(R.id.guild_context_menu_header)
        markAsRead = view.findViewById(R.id.guild_context_menu_mark_as_read)
        notifications = view.findViewById(R.id.guild_context_menu_notifications)
        leaveGuild = view.findViewById(R.id.guild_context_menu_leave_guild)
        moreOptions = view.findViewById(R.id.guild_context_menu_more_options)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildContextMenu::class.java) { configureUI(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildContextMenu::class.java) { dismiss() }
    }

    private fun configureUI(viewState: GuildContextMenuViewModel.ViewState) = when (viewState) {
        GuildContextMenuViewModel.ViewState.Invalid -> hide(requireActivity(), animate = false)
        is GuildContextMenuViewModel.ViewState.Valid -> configureValidUI(viewState)
    }

    private fun configureValidUI(viewState: GuildContextMenuViewModel.ViewState.Valid) {
        header.text = viewState.guild.name

        markAsRead.setOnClickListener {
            dismiss()
            viewModel.onMarkAsReadClicked()
        }
        notifications.setOnClickListener {
            dismiss()
            WidgetServerNotifications.launch(requireContext(), viewState.guild.id)
        }
        leaveGuild.setOnClickListener {
            dismiss()
            ContextMenuNavigation.showLeaveGuild(parentFragmentManager, viewState.guild.id)
        }
        moreOptions.setOnClickListener {
            dismiss()
            ContextMenuNavigation.showGuildProfile(
                parentFragmentManager,
                viewState.guild.id,
                viewState.isGuildSelected,
            )
        }

        leaveGuild.visibility = if (viewState.showLeaveGuild) View.VISIBLE else View.GONE
        markAsRead.visibility = if (viewState.showMarkAsRead) View.VISIBLE else View.GONE
        // The card measures itself with padding while it is hidden; once the rows are bound the
        // padding would show as a gap above the header.
        cardView.setContentPadding(0, 0, 0, 0)
        revealCard()
    }

    private fun dismiss() = dismissCard { hide(it, animate = false) }

    companion object : AnchoredContextMenu.Host(
        fragmentTag = "WidgetGuildContextMenu",
        viewContainerTag = "WidgetGuildContextMenuViewContainer",
        contentLayoutId = R.layout.widget_guild_context_menu,
    ) {
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        override fun newInstance(id: Long): Fragment = WidgetGuildContextMenu().apply {
            arguments = Bundle().apply { putLong(EXTRA_GUILD_ID, id) }
        }
    }
}

/**
 * Context-menu destinations that do not exist in this project yet.
 *
 * Destinations still under reconstruction stay injectable so their menu rows do not need another
 * rewrite when those screens land.
 */
object ContextMenuNavigation {

    /** `WidgetLeaveGuildDialog.show(fragmentManager, guildId)`. */
    @Volatile
    var showLeaveGuild: (FragmentManager, Long) -> Unit = { _, _ -> }

    /** `WidgetGuildProfileSheet.show(fragmentManager, isGuildSelected, guildId)`. */
    @Volatile
    var showGuildProfile: (FragmentManager, Long, Boolean) -> Unit = { manager, guildId, viewingGuild ->
        WidgetGuildProfileSheet.show(manager, viewingGuild, guildId)
    }

    /** `WidgetGuildFolderSettings.create(context, folderId)`. */
    @Volatile
    var showFolderSettings: (Context, Long) -> Unit = { _, _ -> }
}
