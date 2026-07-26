package com.discord.widgets.chat.input.emoji

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.models.domain.emoji.Emoji
import com.discord.utilities.rx.ui
import com.google.android.material.textfield.TextInputEditText

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

    private lateinit var viewModel: EmojiPickerViewModel
    private lateinit var emojiAdapter: WidgetEmojiAdapter
    private lateinit var categoryAdapter: EmojiCategoryAdapter

    /** Set when the picker is embedded in a sheet that wants the selection itself. */
    internal var onPicked: ((Emoji) -> Unit)? = null

    /** Suppresses the scroll listener while the rail drives the grid. */
    private var scrollingToCategory = false

    private val emojiSelectedListener = object : OnEmojiSelectedListener {
        override fun onEmojiSelected(emoji: Emoji) {
            val handler = onPicked
            if (handler != null) handler(emoji) else host()?.onEmojiPicked(emoji)
            view?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
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

        setUpEmojiRecycler(view)
        setUpCategoryRecycler(view)
        initializeSearchBar(view)

        view.findViewById<View>(R.id.emoji_picker_backspace_icon).setOnClickListener {
            host()?.onEmojiBackspace()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe({ configureUI(it) }, { /* leave the last rendered grid in place */ })
    }

    private fun setUpEmojiRecycler(view: View) {
        val recycler = view.findViewById<RecyclerView>(R.id.chat_input_emoji_picker_recycler)
        val layoutManager = GridLayoutManager(view.context, WidgetEmojiAdapter.DEFAULT_NUM_COLUMNS)
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

    private fun setUpCategoryRecycler(view: View) {
        view.findViewById<RecyclerView>(R.id.emoji_picker_category_recycler).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = categoryAdapter
        }
    }

    private fun initializeSearchBar(view: View) {
        view.findViewById<View>(R.id.emoji_inline_search_button).visibility = View.GONE
        val input = view.findViewById<TextInputEditText>(R.id.emoji_search_input)
        input.visibility = View.VISIBLE
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchText(s?.toString().orEmpty())
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.findViewById<View>(R.id.emoji_search_clear).setOnClickListener { input.text?.clear() }
    }

    private fun onCategorySelected(item: EmojiCategoryItem) {
        viewModel.selectCategory(item)
        val recycler = view?.findViewById<RecyclerView>(R.id.chat_input_emoji_picker_recycler) ?: return
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
                view?.findViewById<AppViewFlipper>(R.id.chat_input_emoji_picker_view_flipper)
                    ?.displayedChild = if (viewState.isEmpty) FLIPPER_EMPTY else FLIPPER_GRID

                val shortcut = view?.findViewById<View>(R.id.emoji_picker_unicode_emoji_shortcut_button)
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

        private const val FLIPPER_GRID = 0
        private const val FLIPPER_EMPTY = 1

        /** Restricts the picker to [contextType]; the default is the whole of the user's emoji. */
        fun create(contextType: EmojiPickerContextType): WidgetEmojiPicker =
            WidgetEmojiPicker().apply {
                arguments = Bundle().apply { putSerializable(ARG_CONTEXT_TYPE, contextType) }
            }
    }
}
