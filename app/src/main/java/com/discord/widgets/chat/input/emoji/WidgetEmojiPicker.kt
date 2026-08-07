package com.discord.widgets.chat.input.emoji

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.databinding.WidgetEmojiPickerBinding
import com.discord.models.domain.emoji.Emoji
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.stores.StoreStream
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.widgets.emoji.WidgetEmojiSheet
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.settings.premium.WidgetSettingsPremium

/**
 * The emoji grid, its category rail and its search box.
 *
 * All state lives in [EmojiPickerViewModel]; this fragment only renders it and reports two kinds
 * of user input back — what was typed into search, and where the grid was scrolled to. The
 * scroll report is what keeps the rail's highlight in sync, and it is deliberately one-directional:
 * a programmatic scroll caused by tapping the rail is ignored so the highlight cannot fight itself.
 */
class WidgetEmojiPicker : AppFragment(R.layout.widget_emoji_picker) {

    /** The surface hosting the picker. */
    interface Host : EmojiPickerListener {
        /** Backspace inside the picker deletes from the host's own input. */
        fun onEmojiBackspace()
    }

    private var _binding: WidgetEmojiPickerBinding? = null
    private val binding get() = requireNotNull(_binding)
    private lateinit var viewModel: EmojiPickerViewModel
    private lateinit var emojiAdapter: WidgetEmojiAdapter
    private lateinit var categoryAdapter: EmojiCategoryAdapter

    /** Set when the picker is embedded in a sheet that wants the selection itself. */
    internal var onPicked: ((Emoji) -> Unit)? = null

    /** Suppresses the scroll listener while the rail drives the grid. */
    private var scrollingToCategory = false

    private val emojiSelectedListener = object : OnEmojiSelectedListener {
        override fun onEmojiSelected(emoji: Emoji) {
            if (!emoji.isUsable()) {
                showNitroUpsell()
                return
            }
            StoreStream.getEmojis().onEmojiUsed(emoji)
            val handler = onPicked
            if (handler != null) handler(emoji) else host()?.onEmojiPicked(emoji)
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }

        override fun onEmojiLongPressed(emoji: Emoji) {
            val identity = when (emoji) {
                is ModelEmojiCustom -> EmojiNode.EmojiIdAndType.Custom(emoji.id, emoji.isAnimated, emoji.name)
                is ModelEmojiUnicode -> EmojiNode.generateEmojiIdAndType(emoji)
                else -> return
            }
            WidgetEmojiSheet.show(parentFragmentManager, identity)
            view?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun showNitroUpsell() {
        WidgetNoticeDialog.show(
            fragmentManager = parentFragmentManager,
            headerText = getString(R.string.premium_upsell_emoji_active_mobile),
            bodyText = getString(R.string.premium_upsell_emoji_description_mobile),
            okText = getString(R.string.emoji_picker_premium_upsell_cta),
            cancelText = getString(R.string.cancel),
            listenerMap = mapOf(
                R.id.notice_ok to {
                    WidgetSettingsPremium.launch(requireContext(), locationSection = NITRO_UPSELL_LOCATION)
                },
            ),
            tag = NITRO_UPSELL_LOCATION,
        )
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetEmojiPickerBinding.bind(view)
        val contextType = arguments?.getSerializable(ARG_CONTEXT_TYPE) as? EmojiPickerContextType
            ?: EmojiPickerContextType.Chat

        viewModel = ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    EmojiPickerViewModel(requireContext().applicationContext, contextType) as T
            },
        ).get(EmojiPickerViewModel::class.java)

        emojiAdapter = WidgetEmojiAdapter(emojiSelectedListener)
        categoryAdapter = EmojiCategoryAdapter(::onCategorySelected)

        setUpEmojiRecycler()
        setUpCategoryRecycler()
        initializeSearchBar()

        binding.emojiPickerBackspaceIcon.apply {
            setOnClickListener { host()?.onEmojiBackspace() }
            val repeat = object : Runnable {
                override fun run() {
                    if (!isPressed || !isAttachedToWindow) return
                    performClick()
                    postDelayed(this, BACKSPACE_REPEAT_MS)
                }
            }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.isPressed = true
                        view.performClick()
                        view.postDelayed(repeat, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.removeCallbacks(repeat)
                        view.isPressed = false
                    }
                }
                true
            }
        }
    }

    override fun onDestroyView() {
        _binding?.chatInputEmojiPickerRecycler?.adapter = null
        _binding?.emojiPickerCategoryRecycler?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe({ configureUI(it) }, { /* leave the last rendered grid in place */ })
    }

    private fun setUpEmojiRecycler() {
        val recycler = binding.chatInputEmojiPickerRecycler
        val layoutManager = GridLayoutManager(recycler.context, WidgetEmojiAdapter.DEFAULT_NUM_COLUMNS)
        layoutManager.spanSizeLookup = emojiAdapter.spanSizeLookup(WidgetEmojiAdapter.DEFAULT_NUM_COLUMNS)
        recycler.layoutManager = layoutManager
        recycler.adapter = emojiAdapter
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) scrollingToCategory = false
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (scrollingToCategory) return
                val first = layoutManager.findFirstVisibleItemPosition()
                if (first != RecyclerView.NO_POSITION) viewModel.onGridScrolledTo(first)
            }
        })
    }

    private fun setUpCategoryRecycler() {
        binding.emojiPickerCategoryRecycler.apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = categoryAdapter
        }
    }

    private fun initializeSearchBar() {
        val mode = arguments?.getSerializable(ARG_MODE) as? EmojiPickerMode ?: EmojiPickerMode.INLINE
        binding.emojiAppBar.visibility =
            if (mode.showsSearchBar) View.VISIBLE else View.GONE
        binding.emojiInlineSearchButton.visibility =
            if (mode == EmojiPickerMode.INLINE) View.VISIBLE else View.GONE
        binding.emojiPickerBackspaceIcon.visibility = if (mode == EmojiPickerMode.INLINE) View.VISIBLE else View.GONE
        val input = binding.emojiSearchInput
        input.visibility = if (mode.showsSearchBar) View.VISIBLE else View.GONE
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchText(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        binding.emojiSearchClear.setOnClickListener { input.text?.clear() }
    }

    private fun onCategorySelected(item: EmojiCategoryItem) {
        viewModel.selectCategory(item)
        val recycler = _binding?.chatInputEmojiPickerRecycler ?: return
        scrollingToCategory = true
        (recycler.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(item.categoryRange.first, 0)
    }

    private fun configureUI(viewState: EmojiPickerViewModel.ViewState) {
        when (viewState) {
            EmojiPickerViewModel.ViewState.Loading -> Unit
            is EmojiPickerViewModel.ViewState.Results -> {
                emojiAdapter.setItems(viewState.items)
                categoryAdapter.setItems(viewState.categories)
                _binding?.chatInputEmojiPickerViewFlipper?.displayedChild =
                    if (viewState.isEmpty) FLIPPER_EMPTY else FLIPPER_GRID

                val shortcut = _binding?.emojiPickerUnicodeEmojiShortcutButton
                val target = viewState.firstUnicodeCategoryItem
                shortcut?.visibility = if (target != null && !viewState.isSearching) View.VISIBLE else View.GONE
                shortcut?.setOnClickListener { target?.let(::onCategorySelected) }
            }
        }
    }

    private fun host(): Host? =
        parentFragment as? Host
            ?: parentFragment?.parentFragment as? Host
            ?: activity as? Host

    companion object {
        private const val ARG_CONTEXT_TYPE = "emoji_picker_context_type"
        private const val ARG_MODE = "emoji_picker_mode"

        private const val FLIPPER_GRID = 0
        private const val FLIPPER_EMPTY = 1
        private const val BACKSPACE_REPEAT_MS = 50L
        private const val NITRO_UPSELL_LOCATION = "Emoji Picker Locked Item"

        /** Restricts the picker to [contextType]; the default is the whole of the user's emoji. */
        fun create(
            contextType: EmojiPickerContextType,
            mode: EmojiPickerMode = EmojiPickerMode.INLINE,
        ): WidgetEmojiPicker =
            WidgetEmojiPicker().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CONTEXT_TYPE, contextType)
                    putSerializable(ARG_MODE, mode)
                }
            }
    }
}
