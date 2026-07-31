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
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.channel.ChannelSelector
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
        viewModel.restoreState(
            savedInstanceState?.getLongArray(STATE_SELECTED_IDS),
            savedInstanceState?.getString(STATE_SEARCH),
        )
        binding.saveFab.setOnClickListener { viewModel.submit() }
        binding.retry.setOnClickListener { viewModel.retry() }
        setBottomSheetCollapsedStateDisabled()
        resizeSheetHeight()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray(STATE_SELECTED_IDS, viewModel.selectedUserIds())
        outState.putString(STATE_SEARCH, viewModel.currentSearchText())
        super.onSaveInstanceState(outState)
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
        when (event) {
            GroupInviteFriendsSheetViewModel.Event.ChannelFull ->
                Toast.makeText(requireContext(), R.string.group_dm_invite_full_sub, Toast.LENGTH_SHORT).show()
            is GroupInviteFriendsSheetViewModel.Event.Completed -> {
                ChannelSelector.getInstance().selectChannel(0L, event.channel.id)
                StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE)
                dismiss()
            }
        }
    }

    private fun updateView(viewState: GroupInviteFriendsSheetViewModel.ViewState) {
        val previousCount = adapter.itemCount
        adapter.setData(viewState.friendItems)
        if (previousCount < viewState.friendItems.size) binding.recyclerView.smoothScrollToPosition(0)
        binding.searchIcon.isVisible = viewState.showSearchIcon
        if (viewModel.currentSearchText().isNotEmpty() && binding.chipsView.text.isEmpty()) {
            binding.chipsView.text = viewModel.currentSearchText()
        }
        viewState.checkedUsers.forEach { user ->
            binding.chipsView.b(
                user.username,
                getString(R.string.remove_role_or_user).replace("!!{name}!!", user.username),
                user.id,
                UserDataContract(user),
            )
        }
        binding.chipsView.d(viewState.checkedUsers.mapTo(HashSet()) { it.id })
        binding.contentFlipper.displayedChild = when (viewState.status) {
            GroupInviteFriendsSheetViewModel.Status.READY -> VIEW_INDEX_FRIENDS
            GroupInviteFriendsSheetViewModel.Status.LOADING,
            GroupInviteFriendsSheetViewModel.Status.SUBMITTING -> VIEW_INDEX_LOADING
            GroupInviteFriendsSheetViewModel.Status.EMPTY -> VIEW_INDEX_EMPTY
            GroupInviteFriendsSheetViewModel.Status.FULL -> VIEW_INDEX_FULL
            GroupInviteFriendsSheetViewModel.Status.ERROR -> VIEW_INDEX_ERROR
        }
        val interactive = viewState.status == GroupInviteFriendsSheetViewModel.Status.READY ||
            viewState.status == GroupInviteFriendsSheetViewModel.Status.EMPTY ||
            viewState.status == GroupInviteFriendsSheetViewModel.Status.ERROR
        binding.chipsView.isEnabled = interactive
        binding.saveFab.isVisible = viewState.checkedUsers.isNotEmpty()
        binding.saveFab.isEnabled = viewState.canSubmit
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
        private const val STATE_SELECTED_IDS = "selected_user_ids"
        private const val STATE_SEARCH = "search"
        private const val VIEW_INDEX_FRIENDS = 0
        private const val VIEW_INDEX_LOADING = 1
        private const val VIEW_INDEX_EMPTY = 2
        private const val VIEW_INDEX_FULL = 3
        private const val VIEW_INDEX_ERROR = 4
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
