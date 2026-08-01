package com.discord.widgets.channels

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.databinding.WidgetChannelPickerSheetBinding
import com.discord.utilities.channel.guildChannelIcon
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import rx.subscriptions.CompositeSubscription

class WidgetChannelPickerBottomSheet : AppBottomSheet() {
    private val guildId: Long get() = argumentsOrDefault.getLong(ARG_CHANNEL_PICKER_GUILD_ID)
    private val selectedChannelId: Long get() = argumentsOrDefault.getLong(ARG_SELECTED_CHANNEL_ID, -1L)
    private val hideAnnouncementChannels: Boolean
        get() = argumentsOrDefault.getBoolean(ARG_HIDE_ANNOUNCEMENT_CHANNELS)

    private val viewModel: WidgetChannelPickerBottomSheetViewModel by appViewModels {
        WidgetChannelPickerBottomSheetViewModel(guildId, selectedChannelId, hideAnnouncementChannels)
    }
    private var _binding: WidgetChannelPickerSheetBinding? = null
    private lateinit var adapter: WidgetchannelPickerAdapter
    private var onCancel: (() -> Unit)? = null

    private val itemClickListener = object : WidgetchannelPickerAdapter.OnItemClickListener {
        override fun onChannelItemClick(channelItem: ChannelPickerAdapterItem.ChannelItem) {
            val channel = channelItem.channel
            setFragmentResult(
                argumentsOrDefault.getString(ARG_REQUEST_KEY).orEmpty(),
                bundleOf(
                    RESULT_EXTRA_CHANNEL_ID to channel.id,
                    RESULT_EXTRA_CHANNEL_NAME to channel.getDisplayName(),
                    RESULT_EXTRA_CHANNEL_ICON_RES_ID to guildChannelIcon(channel),
                    RESULT_EXTRA_SELECTION_TYPE to SelectionType.CHANNEL.ordinal,
                ),
            )
            dismiss()
        }

        override fun onCreateChannelClick() {
            setFragmentResult(
                argumentsOrDefault.getString(ARG_REQUEST_KEY).orEmpty(),
                bundleOf(RESULT_EXTRA_SELECTION_TYPE to SelectionType.CREATE_CHANNEL.ordinal),
            )
            dismiss()
        }
    }

    override fun getContentViewResId(): Int = R.layout.widget_channel_picker_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetChannelPickerSheetBinding.bind(view).also { _binding = it }
        adapter = WidgetchannelPickerAdapter(itemClickListener, this)
        binding.channelPickerRecycler.adapter = adapter
        binding.channelPickerSearchInput.bindTextWatcher(this, viewModel::updateSearchQuery)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelPickerBottomSheet::class.java, onNext = ::configureUI)
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        hideKeyboard()
        onCancel?.invoke()
    }

    fun setOnCancel(onCancel: (() -> Unit)? = null) {
        this.onCancel = onCancel
    }

    private fun configureUI(viewState: WidgetChannelPickerBottomSheetViewModel.ViewState) {
        val binding = _binding ?: return
        when (viewState) {
            WidgetChannelPickerBottomSheetViewModel.ViewState.Loading -> {
                binding.channelPickerAppFlipper.displayedChild = FLIPPER_LOADING
                binding.channelPickerSearchInput.visibility = View.GONE
            }
            is WidgetChannelPickerBottomSheetViewModel.ViewState.Loaded -> {
                binding.channelPickerSearchInput.visibility = View.VISIBLE
                binding.channelPickerAppFlipper.displayedChild = FLIPPER_RESULT
                adapter.setItems(viewState.adapterItems)
            }
        }
    }

    enum class SelectionType {
        UNKNOWN,
        CREATE_CHANNEL,
        CHANNEL;

        companion object {
            fun fromInt(value: Int): SelectionType = values().getOrNull(value) ?: UNKNOWN
        }
    }

    companion object {
        private const val ARG_CHANNEL_PICKER_GUILD_ID = "ARG_CHANNEL_PICKER_GUILD_ID"
        private const val ARG_HIDE_ANNOUNCEMENT_CHANNELS = "ARG_HIDE_ANNOUNCEMENT_CHANNELS"
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val ARG_SELECTED_CHANNEL_ID = "ARG_SELECTED_CHANNEL_ID"
        private const val RESULT_EXTRA_CHANNEL_ICON_RES_ID = "RESULT_EXTRA_CHANNEL_ICON_RES_ID"
        private const val RESULT_EXTRA_CHANNEL_ID = "RESULT_EXTRA_CHANNEL_ID"
        private const val RESULT_EXTRA_CHANNEL_NAME = "RESULT_EXTRA_CHANNEL_NAME"
        private const val RESULT_EXTRA_SELECTION_TYPE = "RESULT_EXTRA_SELECTION_TYPE"
        private const val FLIPPER_LOADING = 0
        private const val FLIPPER_RESULT = 1

        fun launch(
            fragment: Fragment,
            requestKey: String,
            guildId: Long,
            selectedChannelId: Long? = null,
            onCancel: (() -> Unit)? = null,
            hideAnnouncementChannels: Boolean = false,
        ) {
            WidgetChannelPickerBottomSheet().apply {
                arguments = bundleOf(
                    ARG_REQUEST_KEY to requestKey,
                    ARG_CHANNEL_PICKER_GUILD_ID to guildId,
                    ARG_HIDE_ANNOUNCEMENT_CHANNELS to hideAnnouncementChannels,
                ).apply {
                    selectedChannelId?.let { putLong(ARG_SELECTED_CHANNEL_ID, it) }
                }
                setOnCancel(onCancel)
            }.show(fragment.parentFragmentManager, WidgetChannelPickerBottomSheet::class.java.name)
        }

        fun registerForResult(
            fragment: Fragment,
            requestKey: String,
            onChannelSelected: (Long, String, Int) -> Unit,
            onCreateChannelSelected: () -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { resultRequestKey, bundle ->
                if (requestKey != resultRequestKey) return@setFragmentResultListener
                when (SelectionType.fromInt(bundle.getInt(RESULT_EXTRA_SELECTION_TYPE, SelectionType.UNKNOWN.ordinal))) {
                    SelectionType.CREATE_CHANNEL -> onCreateChannelSelected()
                    SelectionType.CHANNEL -> {
                        val channelId = bundle.getLong(RESULT_EXTRA_CHANNEL_ID, -1L)
                        if (channelId > 0L) {
                            onChannelSelected(
                                channelId,
                                bundle.getString(RESULT_EXTRA_CHANNEL_NAME).orEmpty(),
                                bundle.getInt(RESULT_EXTRA_CHANNEL_ICON_RES_ID, -1),
                            )
                        }
                    }
                    SelectionType.UNKNOWN -> Unit
                }
            }
        }
    }
}
