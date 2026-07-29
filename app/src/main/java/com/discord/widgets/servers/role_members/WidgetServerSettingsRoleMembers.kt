package com.discord.widgets.servers.role_members

import android.os.Bundle
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.views.SearchInputView
import com.discord.widgets.settings.SettingsStrings

class WidgetServerSettingsRoleMembers : AppFragment(R.layout.widget_server_settings_role_members) {

    private val guildId: Long get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_ID) ?: INVALID_ID
    private val guildRoleId: Long get() =
        arguments?.getLong(INTENT_EXTRA_GUILD_ROLE_ID, INVALID_ID) ?: INVALID_ID

    private val viewModel: ServerSettingsRoleMembersViewModel by appViewModels {
        ServerSettingsRoleMembersViewModel(guildId, guildRoleId)
    }

    private lateinit var flipper: AppViewFlipper
    private var adapter: ServerSettingsRoleMembersAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        flipper = view.findViewById(R.id.role_members_app_view_flipper)
        val recycler = view.findViewById<RecyclerView>(R.id.server_settings_role_members_list)
        adapter = MGRecyclerAdapter.configure(ServerSettingsRoleMembersAdapter(recycler))
        view.findViewById<SearchInputView>(R.id.server_settings_role_members_search_input)
            .bindTextWatcher(this, viewModel::updateSearchQuery)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        viewModel.observeEvents().ui(this).subscribe {
            if (it is ServerSettingsRoleMembersViewModel.Event.RemoveMemberFailure) {
                AppToast.show(this, R.string.error_occurred_try_again)
            }
        }
    }

    private fun configureUI(viewState: ServerSettingsRoleMembersViewModel.ViewState) {
        when (viewState) {
            ServerSettingsRoleMembersViewModel.ViewState.Loading -> flipper.displayedChild = VIEW_LOADING
            is ServerSettingsRoleMembersViewModel.ViewState.Loaded -> {
                flipper.displayedChild = VIEW_RESULTS
                adapter?.removeMemberClickListener = { item ->
                    val name = item.guildMember.nick ?: item.permissionOwner.user.username
                    SimpleConfirmationDialog.show(
                        childFragmentManager,
                        SimpleConfirmationDialogArgs(
                            title = getString(R.string.role_remove_member_confirm_title),
                            description = SettingsStrings.render(
                                requireContext(),
                                R.string.role_remove_member_confirm_body,
                                "username" to name,
                                "roleName" to viewState.role.name,
                            ).toString(),
                            positiveButtonText = getString(R.string.remove),
                            negativeButtonText = getString(R.string.cancel),
                        ),
                    ) { viewModel.removeRoleFromMember(item.guildMember) }
                }
                adapter?.setItems(viewState.memberList)
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INTENT_EXTRA_GUILD_ROLE_ID = "INTENT_EXTRA_GUILD_ROLE_ID"
        private const val INVALID_ID = -1L
        private const val VIEW_LOADING = 0
        private const val VIEW_RESULTS = 1

        fun newInstance(guildRoleId: Long, guildId: Long) = WidgetServerSettingsRoleMembers().apply {
            arguments = Bundle().apply {
                putLong(INTENT_EXTRA_GUILD_ROLE_ID, guildRoleId)
                putLong(INTENT_EXTRA_GUILD_ID, guildId)
            }
        }
    }
}
