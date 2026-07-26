package com.discord.widgets.botuikit.views.select

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.api.botuikit.SelectItem
import com.discord.app.AppBottomSheet
import com.discord.i18n.FormatUtils
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setEnabledAlpha
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import java.io.Serializable

/**
 * The sheet a bot's select menu opens.
 *
 * It exists as a sheet rather than a dropdown because a select component can carry 25 options with
 * descriptions and emoji, and because multi-select needs an explicit confirm step. The sheet is
 * fully self-contained: everything it needs arrives as arguments, so it survives process death
 * without reaching back into the message list.
 *
 * The confirmed selection is published with [FragmentManager.setFragmentResult] under
 * [RESULT_KEY]. The reference instead dispatches the interaction from its view model via
 * `StoreApplicationInteractions`; that store does not exist in this project, so the host — the chat
 * row that opened the sheet — performs the call. Listening is lifecycle-scoped, so a result that
 * arrives while the chat list is destroyed is delivered when it comes back rather than lost.
 */
class SelectComponentBottomSheet : AppBottomSheet() {

    private lateinit var viewModel: SelectComponentBottomSheetViewModel

    private val adapter = SelectComponentBottomSheetAdapter { item, selected ->
        viewModel.selectItem(item, selected)
    }

    override fun getContentViewResId(): Int = R.layout.widget_select_component_bottom_sheet

    @Suppress("UNCHECKED_CAST")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The option list is the content; collapsing it behind a peek would hide the very thing
        // the user opened the sheet to read.
        setBottomSheetCollapsedStateDisabled()

        val args = requireArguments()

        viewModel = ViewModelProvider(
            this,
            factory(
                componentContext = args.getSerializable(EXTRA_COMPONENT_CONTEXT) as ComponentContext,
                componentIndex = args.getInt(EXTRA_COMPONENT_INDEX),
                customId = args.getString(EXTRA_CUSTOM_ID).orEmpty(),
                placeholder = args.getString(EXTRA_PLACEHOLDER),
                items = args.getSerializable(EXTRA_OPTIONS) as ArrayList<SelectItem>,
                min = args.getInt(EXTRA_MIN),
                max = args.getInt(EXTRA_MAX),
                selectedOptions = args.getSerializable(EXTRA_SELECTED) as ArrayList<SelectItem>,
                emojiAnimationsEnabled = args.getBoolean(EXTRA_EMOJI_ANIMATIONS_ENABLED),
            ),
        ).get(SelectComponentBottomSheetViewModel::class.java)

        view.findViewById<MaxHeightRecyclerView>(R.id.widget_sheet_component_bottom_sheet_recycler).adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .ui(this)
            .subscribe({ configureUI(it) }, { /* a render failure must not trap the user */ })
        viewModel.observeEvents()
            .ui(this)
            .subscribe({ handleEvent(it) }, { })
    }

    private fun configureUI(viewState: SelectComponentBottomSheetViewModel.ViewState) {
        val root = requireView()
        root.findViewById<TextView>(R.id.widget_sheet_component_bottom_sheet_placeholder).text = viewState.title

        val subtitle = root.findViewById<TextView>(R.id.widget_sheet_component_bottom_sheet_subtitle)
        subtitle.visibility = if (viewState.isMultiSelect) View.VISIBLE else View.GONE
        if (viewState.isMultiSelect) {
            subtitle.text = FormatUtils.format(
                getString(R.string.message_select_component_select_requirement),
                "count" to viewState.minSelections,
            )
        }

        val select = root.findViewById<TextView>(R.id.widget_sheet_component_bottom_sheet_select)
        // INVISIBLE rather than GONE: the header is a constraint chain anchored to this button, and
        // removing it would re-flow the title on every single-select sheet.
        select.visibility = if (viewState.showSelectButton) View.VISIBLE else View.INVISIBLE
        select.setOnClickListener { viewModel.onClickSelect() }
        select.setEnabledAlpha(viewState.isValidSelection, DISABLED_ALPHA)
        select.isClickable = viewState.isValidSelection

        adapter.setItems(viewState.items, viewState.maxSelections, viewState.emojiAnimationsEnabled)
    }

    private fun handleEvent(event: SelectComponentBottomSheetViewModel.Event) = when (event) {
        SelectComponentBottomSheetViewModel.Event.CloseSheet -> dismiss()
    }

    private fun factory(
        componentContext: ComponentContext,
        componentIndex: Int,
        customId: String,
        placeholder: String?,
        items: List<SelectItem>,
        min: Int,
        max: Int,
        selectedOptions: List<SelectItem>,
        emojiAnimationsEnabled: Boolean,
    ) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SelectComponentBottomSheetViewModel(
                componentContext = componentContext,
                componentIndex = componentIndex,
                customId = customId,
                placeholder = placeholder,
                items = items,
                min = min,
                max = max,
                selectedOptions = selectedOptions,
                emojiAnimationsEnabled = emojiAnimationsEnabled,
                onSelectionSubmitted = ::publishSelection,
            ) as T
    }

    private fun publishSelection(
        componentContext: ComponentContext,
        componentIndex: Int,
        customId: String,
        values: List<String>,
    ) {
        parentFragmentManager.setFragmentResult(
            RESULT_KEY,
            bundleOf(
                RESULT_COMPONENT_CONTEXT to componentContext,
                RESULT_COMPONENT_INDEX to componentIndex,
                RESULT_CUSTOM_ID to customId,
                RESULT_VALUES to ArrayList(values),
            ),
        )
    }

    companion object {
        const val EXTRA_COMPONENT_CONTEXT = "extra_component_context"
        const val EXTRA_COMPONENT_INDEX = "extra_component_index"
        const val EXTRA_CUSTOM_ID = "extra_custom_id"
        const val EXTRA_EMOJI_ANIMATIONS_ENABLED = "extra_emoji_animations_enabled"
        const val EXTRA_MAX = "extra_max"
        const val EXTRA_MIN = "extra_min"
        const val EXTRA_OPTIONS = "extra_options"
        const val EXTRA_PLACEHOLDER = "extra_placeholder"
        const val EXTRA_SELECTED = "extra_selected"

        /** Fragment-result key the confirmed selection is published under. */
        const val RESULT_KEY = "select_component_bottom_sheet_result"
        const val RESULT_COMPONENT_CONTEXT = "component_context"
        const val RESULT_COMPONENT_INDEX = "component_index"
        const val RESULT_CUSTOM_ID = "custom_id"
        const val RESULT_VALUES = "values"

        private const val DISABLED_ALPHA = 0.3f

        fun show(
            fragmentManager: FragmentManager,
            componentContext: ComponentContext,
            componentIndex: Int,
            customId: String,
            placeholder: String?,
            min: Int,
            max: Int,
            options: List<SelectItem>,
            selectedItems: List<SelectItem>,
            emojiAnimationsEnabled: Boolean,
        ) {
            SelectComponentBottomSheet().apply {
                arguments = bundleOf(
                    EXTRA_COMPONENT_CONTEXT to componentContext,
                    EXTRA_COMPONENT_INDEX to componentIndex,
                    EXTRA_CUSTOM_ID to customId,
                    EXTRA_PLACEHOLDER to placeholder,
                    EXTRA_MIN to min,
                    EXTRA_MAX to max,
                    EXTRA_OPTIONS to ArrayList<Serializable>(options),
                    EXTRA_SELECTED to ArrayList<Serializable>(selectedItems),
                    EXTRA_EMOJI_ANIMATIONS_ENABLED to emojiAnimationsEnabled,
                )
            }.show(fragmentManager, SelectComponentBottomSheet::class.java.simpleName)
        }
    }
}
