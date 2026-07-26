package com.discord.widgets.chat.input.autocomplete

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppComponent
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.autocomplete.adapter.ChatInputAutocompleteAdapter

/**
 * Binds one composer to one [AutocompleteViewModel]: it feeds edits in, renders the resulting
 * [AutocompleteViewState], and splices the picked candidate back into the text.
 *
 * This is a controller rather than a `View` because the pieces it drives live in three different
 * places in `widget_chat_input.xml` (the header, the recycler, the sticker row) and the composer
 * itself is inside the FlexInput child fragment. The reference makes the same choice.
 *
 * Reference: `com.discord.widgets.chat.input.autocomplete.InputAutocomplete`. The command browser
 * (application category rail, selected-command chips, attachment options) is not reconstructed —
 * see `AutocompleteModelUtils` for why.
 */
class InputAutocomplete(
    private val appComponent: AppComponent,
    private val editText: EditText,
    private val autocompleteHeader: TextView,
    private val autocompleteRecyclerView: RecyclerView,
    private val stickersContainer: View,
    private val viewModel: AutocompleteViewModel,
) {

    private val adapter = ChatInputAutocompleteAdapter()

    /**
     * Suppresses the [TextWatcher] while [applySelection] rewrites the text. Without it, splicing
     * a mention in would immediately re-open the popup on the text we just inserted.
     */
    private var applyingSelection = false

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (applyingSelection) return
            viewModel.onInputChanged(s ?: "", editText.selectionStart.coerceAtLeast(0).let { it..it })
        }
    }

    init {
        autocompleteRecyclerView.layoutManager = LinearLayoutManager(autocompleteRecyclerView.context)
        autocompleteRecyclerView.adapter = adapter
        adapter.onItemSelected = ::applySelection
        editText.addTextChangedListener(textWatcher)
    }

    /** Call from `onViewBoundOrOnResume`; the subscription ends with [appComponent]'s pause. */
    fun onViewBoundOrOnResume() {
        viewModel.observeViewState()
            .ui(appComponent)
            .subscribe({ configureUI(it) }, { /* a render failure must not kill the composer */ })
    }

    fun onDestroyView() {
        editText.removeTextChangedListener(textWatcher)
        autocompleteRecyclerView.adapter = null
    }

    /** True when the popup is open, so the host can consume a back press. */
    fun isShowing(): Boolean = autocompleteRecyclerView.visibility == View.VISIBLE

    fun dismiss() = viewModel.onAutocompleteDismissed()

    private fun configureUI(viewState: AutocompleteViewState) = when (viewState) {
        is AutocompleteViewState.Autocomplete -> showAutocomplete(viewState)
        is AutocompleteViewState.LoadingItems -> showLoading(viewState)
        AutocompleteViewState.Hidden -> hideAutocomplete()
    }

    private fun showAutocomplete(viewState: AutocompleteViewState.Autocomplete) {
        adapter.setData(viewState.autocompletables, hasStickers = viewState.stickers.isNotEmpty())
        autocompleteRecyclerView.visibility = View.VISIBLE
        stickersContainer.visibility = View.GONE
        autocompleteHeader.visibility =
            if (viewState.autocompletables.any { it is EmojiAutocompletable }) View.VISIBLE else View.GONE
        autocompleteHeader.setText(R.string.emoji_matching)
        adapter.scrollToPosition(0)
    }

    private fun showLoading(viewState: AutocompleteViewState.LoadingItems) {
        adapter.setData(
            List(viewState.numberLoadingItems) { ApplicationCommandLoadingPlaceholder() },
            sizeToMax = true,
        )
        autocompleteRecyclerView.visibility = View.VISIBLE
        autocompleteHeader.visibility = View.GONE
        stickersContainer.visibility = View.GONE
    }

    private fun hideAutocomplete() {
        autocompleteRecyclerView.visibility = View.GONE
        autocompleteHeader.visibility = View.GONE
        stickersContainer.visibility = View.GONE
    }

    /**
     * Replaces the token under the caret with the picked candidate's input form, plus a trailing
     * space so the next word starts a fresh token.
     *
     * The replacement range is recomputed from the *current* text rather than trusting the token
     * captured when the popup was populated: an in-flight keystroke can arrive between render and
     * tap, and replacing a stale range corrupts the composer.
     */
    private fun applySelection(autocompletable: Autocompletable) {
        val editable = editText.editableText ?: return
        val caret = editText.selectionStart.coerceAtLeast(0)
        val token = AutocompleteModelUtils.getMessageAutocompleteToken(editable, caret..caret)
            ?: return

        val replacement = autocompletable.getInputReplacement() + " "
        val start = token.startIndex
        val end = (token.startIndex + token.token.length).coerceAtMost(editable.length)
        if (start !in 0..end) return

        applyingSelection = true
        try {
            editable.replace(start, end, replacement)
            editText.setSelection((start + replacement.length).coerceAtMost(editable.length))
        } finally {
            applyingSelection = false
        }
        viewModel.onAutocompleteDismissed()
    }
}
