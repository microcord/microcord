package com.discord.widgets.search

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.utilities.rx.ui
import com.discord.utilities.search.strings.ContextSearchStringProvider
import com.discord.utilities.textprocessing.AstRenderer
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout

/**
 * Message search for one guild or one private channel.
 *
 * The screen is a shell: it owns the input field and swaps between two child fragments — the
 * suggestion list while the query is being composed, the result list once it has been run. Both
 * children read the same [SearchStore], so this fragment never passes them anything.
 *
 * The input field is two-way. Text typed flows into the store, which parses it; the store pushes a
 * rendered AST *back* whenever a suggestion is tapped, which is how tapping "from:" inserts a bold
 * chip and leaves the caret after it.
 */
class WidgetSearch : AppFragment(R.layout.widget_search) {

    /** Whether [targetId] names a guild or a private channel; [DMS] has no id. */
    enum class TargetType { GUILD, CHANNEL, DMS }

    private var targetId: Long = 0L
    private var targetType: TargetType? = null

    private val searchStore: SearchStore get() = SearchStoreProvider.get()

    private val viewModel: WidgetSearchViewModel by appViewModels {
        WidgetSearchViewModel(targetType, targetId)
    }

    private val searchInput: TextInputLayout get() = requireView().findViewById(R.id.widget_search_input)
    private val searchInputEditText: EditText get() = requireNotNull(searchInput.editText)
    private val sendQueryFab: FloatingActionButton
        get() = requireView().findViewById(R.id.search_send_query_fab)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        targetId = mostRecentIntent.getLongExtra(INTENT_EXTRA_TARGET_ID, 0L)
        targetType = mostRecentIntent
            .getStringExtra(INTENT_EXTRA_TARGET_TYPE)
            ?.let { runCatching { TargetType.valueOf(it) }.getOrNull() }

        // Back from a result jump: the store still holds the query and its results, so put the
        // query back in the box and show them as they were.
        val reopened = mostRecentIntent.getBooleanExtra(INTENT_EXTRA_REOPEN, false)
        if (!isRecreated && reopened) {
            searchStore.storeSearchInput.currentInput.take(1).subscribe { query ->
                searchInputEditText.setText(query)
                searchInputEditText.setSelection(searchInputEditText.length())
            }
        } else if (!isRecreated) {
            showKeyboard(searchInputEditText)
        }

        val stringProvider = ContextSearchStringProvider(requireContext())
        when (targetType) {
            TargetType.GUILD -> searchStore.initForGuild(targetId, stringProvider)
            TargetType.CHANNEL -> searchStore.initForChannel(targetId, stringProvider)
            TargetType.DMS -> searchStore.initForDms(stringProvider)
            null -> Unit
        }

        // Only on a fresh launch: on a rotation the field already holds whatever was typed since.
        // A query handed over from another screen is run straight away — the caller already asked
        // for these results, so stopping at a filled-in box would look like nothing happened.
        if (!isRecreated) {
            mostRecentIntent.getStringExtra(INTENT_EXTRA_INITIAL_QUERY)
                ?.takeIf(String::isNotBlank)
                ?.let { query ->
                    searchInputEditText.setText(query)
                    searchInputEditText.setSelection(searchInputEditText.length())
                    searchInputEditText.post { if (view != null) sendQuery() }
                }
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        setOnBackPressed(handler = {
            val activity = appActivity ?: return@setOnBackPressed false
            activity.finish()
            true
        })
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
        configureSearchInput()

        sendQueryFab.setOnClickListener { sendQuery() }
        searchInput.setStartIconOnClickListener {
            hideKeyboard(it)
            requireAppActivity().onBackPressedDispatcher.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (returnTo == null) searchStore.clear()
    }

    /** Closes search for a result jump; Back from [channelId] then reopens these results. */
    internal fun closeForJump(channelId: Long, scrollPosition: Int, scrollOffset: Int) {
        returnTo = targetType?.let { ReturnTo(targetId, it, channelId, scrollPosition, scrollOffset) }
        appActivity?.finish()
    }

    private fun configureSearchInput() {
        searchInputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId in SUBMIT_ACTIONS) {
                hideKeyboard()
                sendQuery()
                true
            } else {
                false
            }
        }
        searchInputEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchStore.storeSearchInput.updateInput(s?.toString().orEmpty())
            }
        })
        // Suggestion taps rewrite the query; the rendered AST is pushed back into the field and the
        // caret parked at the end so typing continues where the user left off.
        searchStore.storeSearchInput.forcedInput
            .map { AstRenderer.render(it, requireContext()).toString() }
            .ui(this)
            .subscribe { text ->
                searchInputEditText.setText(text)
                searchInputEditText.setSelection(searchInputEditText.length())
            }
    }

    private fun sendQuery() {
        hideKeyboard()
        searchStore.loadInitial(
            searchInputEditText.text.toString(),
            ContextSearchStringProvider(requireContext()),
        )
    }

    private fun configureUI(viewState: WidgetSearchViewModel.ViewState) {
        if (viewState !is WidgetSearchViewModel.ViewState.Loaded) {
            appActivity?.finish()
            return
        }

        searchInput.hint = when (targetType) {
            TargetType.GUILD -> requireContext()
                .i18nFormat(R.string.search_in, "guildName" to viewState.guild?.name.orEmpty())
            TargetType.CHANNEL -> channelHint(viewState.channel)
            TargetType.DMS -> requireContext()
                .i18nFormat(R.string.search_in, "guildName" to getString(R.string.direct_messages))
            null -> getString(R.string.search)
        }

        val suggestions = requireView().findViewById<View>(R.id.widget_search_suggestions)
        val results = requireView().findViewById<View>(R.id.widget_search_results)
        when (viewState.displayState) {
            SearchStore.DisplayState.SUGGESTIONS -> {
                suggestions.visibility = View.VISIBLE
                // INVISIBLE, not GONE: the results fragment keeps its scroll position underneath.
                results.visibility = View.INVISIBLE
                sendQueryFab.visibility = if (viewState.isQueryValid) View.VISIBLE else View.GONE
            }
            SearchStore.DisplayState.RESULTS -> {
                suggestions.visibility = View.INVISIBLE
                results.visibility = View.VISIBLE
                sendQueryFab.visibility = View.GONE
            }
        }
    }

    private fun channelHint(channel: Channel?): CharSequence {
        val name = channel?.getDisplayName().orEmpty()
        return when (channel?.type) {
            Channel.DM -> requireContext().i18nFormat(R.string.search_dm_with, "userName" to name)
            Channel.GROUP_DM -> requireContext().i18nFormat(R.string.search_in, "guildName" to name)
            else -> getString(R.string.search)
        }
    }

    companion object {
        const val INTENT_EXTRA_TARGET_ID = "INTENT_EXTRA_TARGET_ID"
        const val INTENT_EXTRA_TARGET_TYPE = "INTENT_EXTRA_SEARCH_TYPE"
        const val INTENT_EXTRA_INITIAL_QUERY = "INTENT_EXTRA_INITIAL_QUERY"
        private const val INTENT_EXTRA_REOPEN = "INTENT_EXTRA_REOPEN"
        const val INTENT_EXTRA_SCROLL_POSITION = "INTENT_EXTRA_SCROLL_POSITION"
        const val INTENT_EXTRA_SCROLL_OFFSET = "INTENT_EXTRA_SCROLL_OFFSET"

        private data class ReturnTo(
            val targetId: Long,
            val targetType: TargetType,
            val channelId: Long,
            val scrollPosition: Int,
            val scrollOffset: Int,
        )

        /** The search a result jump left, while its results are still held by the store. */
        private var returnTo: ReturnTo? = null

        /**
         * Back from the chat: reopens the search whose result jump led to [channelId]. One-shot, so
         * any other Back from the chat forgets it.
         */
        fun reopenAfterJump(context: Context, channelId: Long?): Boolean {
            val target = returnTo ?: return false
            if (target.channelId != channelId) {
                discardReturn()
                return false
            }
            returnTo = null
            launch(target.targetId, target.targetType, context, reopen = target)
            return true
        }

        fun canReopenFrom(channelId: Long): Boolean = returnTo?.channelId == channelId

        private fun discardReturn() {
            if (returnTo == null) return
            returnTo = null
            SearchStoreProvider.get().clear()
        }

        fun launchForGuild(guildId: Long, context: Context) =
            launch(guildId, TargetType.GUILD, context)

        fun launchForChannel(channelId: Long, context: Context) =
            launch(channelId, TargetType.CHANNEL, context)

        /** Cross-DM search: every private channel at once, so there is no id to scope it to. */
        fun launchForDms(context: Context, initialQuery: String? = null) =
            launch(0L, TargetType.DMS, context, initialQuery)

        private fun launch(
            targetId: Long,
            targetType: TargetType,
            context: Context,
            initialQuery: String? = null,
            reopen: ReturnTo? = null,
        ) {
            if (reopen == null) discardReturn()
            AppScreenRouter.launch(
                context,
                WidgetSearch::class.java,
                Intent()
                    .putExtra(INTENT_EXTRA_TARGET_ID, targetId)
                    .putExtra(INTENT_EXTRA_TARGET_TYPE, targetType.name)
                    .putExtra(INTENT_EXTRA_INITIAL_QUERY, initialQuery)
                    .putExtra(INTENT_EXTRA_REOPEN, reopen != null)
                    .putExtra(INTENT_EXTRA_SCROLL_POSITION, reopen?.scrollPosition ?: 0)
                    .putExtra(INTENT_EXTRA_SCROLL_OFFSET, reopen?.scrollOffset ?: 0),
            )
        }

        /** IME actions that submit the query: Go, Search and Done. */
        private val SUBMIT_ACTIONS = setOf(
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_DONE,
        )
    }
}
