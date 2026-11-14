package com.discord.widgets.channels.permissions

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.app.AppFragment
import com.discord.databinding.WidgetChannelSettingsAddMemberBinding
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import rx.subjects.BehaviorSubject

class WidgetChannelSettingsAddMemberFragment : AppFragment(R.layout.widget_channel_settings_add_member) {
    private val channelId get() = requireArguments().getLong(EXTRA_CHANNEL_ID)
    private val showRoles get() = requireArguments().getBoolean(EXTRA_SHOW_ROLES)
    private val permission get() = requireArguments().getLong(EXTRA_PERMISSION, Permission.MODERATE_STAGE_CHANNEL)
    private val viewModel by viewModels<WidgetChannelSettingsAddMemberFragmentViewModel> {
        object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                WidgetChannelSettingsAddMemberFragmentViewModel(channelId, showRoles, permission) as T
        }
    }
    private lateinit var adapter: AddMemberAdapter
    val selectedItemsSubject: BehaviorSubject<Map<Long, PermissionOverwrite.Type>> = BehaviorSubject.create(emptyMap())

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetChannelSettingsAddMemberBinding.bind(view)
        // configure() also installs the layout manager; a recycler without one draws no rows at all.
        adapter = MGRecyclerAdapter.configure(AddMemberAdapter(binding.recycler))
        adapter.setOnClickListener(viewModel::toggleItem)
        binding.searchInput.bindTextWatcher(this, viewModel::updateQuery)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe {
            adapter.setData(it.items)
            selectedItemsSubject.onNext(it.selected)
        }
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_SHOW_ROLES = "EXTRA_SHOW_ROLES_WITH_GUILD_PERMISSION"
        private const val EXTRA_PERMISSION = "EXTRA_PERMISSION"
        fun create(
            channelId: Long,
            showRolesWithGuildPermission: Boolean,
            permission: Long = Permission.MODERATE_STAGE_CHANNEL,
        ) = WidgetChannelSettingsAddMemberFragment().apply {
            arguments = Bundle().apply {
                putLong(EXTRA_CHANNEL_ID, channelId)
                putBoolean(EXTRA_SHOW_ROLES, showRolesWithGuildPermission)
                putLong(EXTRA_PERMISSION, permission)
            }
        }
    }
}
