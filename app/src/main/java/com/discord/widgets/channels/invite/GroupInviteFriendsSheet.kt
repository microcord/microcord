package com.discord.widgets.channels.invite

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.chips_view.ChipsView
import com.discord.databinding.GroupInviteFriendsBottomSheetBinding
import com.discord.models.user.User
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

class GroupInviteFriendsSheet : AppBottomSheet() {
    private var _binding: GroupInviteFriendsBottomSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private lateinit var adapter: GroupInviteFriendsSheetAdapter

    private val viewModel: GroupInviteFriendsSheetViewModel by viewModels {
        val channelId = requireArguments().getLong(EXTRA_CHANNEL_ID, NO_CHANNEL_ID)
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GroupInviteFriendsSheetViewModel(channelId) as T
        }
    }

    override fun getContentViewResId(): Int = R.layout.group_invite_friends_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = GroupInviteFriendsBottomSheetBinding.bind(view)
        adapter = MGRecyclerAdapter.configure(
            GroupInviteFriendsSheetAdapter(binding.recyclerView, viewModel::onChangeUserChecked),
        )
        @Suppress("UNCHECKED_CAST")
        (binding.chipsView as ChipsView<Long, UserDataContract>).apply {
            chipDeletedListener = { it?.modelUser?.let { user -> viewModel.onChangeUserChecked(user, false) } }
            textChangedListener = viewModel::onSearchTextChanged
        }
        setBottomSheetCollapsedStateDisabled()
        resizeSheetHeight()
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass, onNext = ::updateView)
        viewModel.observeEvents().ui(this).appSubscribe(javaClass, onNext = ::handleEvent)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun handleEvent(event: GroupInviteFriendsSheetViewModel.Event) {
        if (event === GroupInviteFriendsSheetViewModel.Event.ChannelFull) {
            Toast.makeText(requireContext(), R.string.group_dm_invite_full_sub, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateView(viewState: GroupInviteFriendsSheetViewModel.ViewState) {
        val previousCount = adapter.itemCount
        adapter.setData(viewState.friendItems)
        if (previousCount < viewState.friendItems.size) binding.recyclerView.smoothScrollToPosition(0)
        binding.searchIcon.isVisible = viewState.showSearchIcon
        viewState.checkedUsers.forEach { user ->
            binding.chipsView.b(
                user.username,
                getString(R.string.remove_role_or_user).replace("!!{name}!!", user.username),
                user.id,
                UserDataContract(user),
            )
        }
        binding.chipsView.d(viewState.checkedUsers.mapTo(HashSet()) { it.id })
    }

    private fun resizeSheetHeight() {
        binding.root.doWhenLaidOut { root ->
            root.layoutParams = root.layoutParams.apply {
                height = (resources.displayMetrics.heightPixels * BOTTOM_SHEET_HEIGHT_PERCENT).toInt()
            }
        }
        binding.recyclerView.requestLayout()
    }

    class UserDataContract(val modelUser: User) : ChipsView.a {
        override fun getDisplayString(): String = modelUser.username
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        const val NO_CHANNEL_ID = -1L
        const val BOTTOM_SHEET_HEIGHT_PERCENT = 0.9f

        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long = NO_CHANNEL_ID,
            source: String,
        ): GroupInviteFriendsSheet = GroupInviteFriendsSheet().also { sheet ->
            AnalyticsTracker.openModal(
                if (channelId == NO_CHANNEL_ID) "DM Group Create" else "DM Group Add",
                source,
            )
            sheet.arguments = Bundle().apply { putLong(EXTRA_CHANNEL_ID, channelId) }
            sheet.show(fragmentManager, GroupInviteFriendsSheet::class.java.simpleName)
        }
    }
}

private inline fun View.doWhenLaidOut(crossinline block: (View) -> Unit) {
    if (isLaidOut && !isLayoutRequested) {
        block(this)
    } else {
        addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                view: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                view.removeOnLayoutChangeListener(this)
                block(view)
            }
        })
    }
}
