package com.discord.widgets.user.search

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.appActivityViewModels
import com.discord.app.appViewModels
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.widgets.user.usersheet.WidgetUserSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * The quick switcher: type anything, jump anywhere.
 *
 * Results are ranked, not listed — see [WidgetGlobalSearchViewModel]. The rail across the top is a
 * shortcut to recent DMs and servers; the list underneath is the ranked match set, grouped by kind.
 *
 * The screen is hosted three ways (its own tab, [WidgetGlobalSearchDialog], and the quick-mentions
 * pager), so navigating publishes a dismiss event rather than closing anything itself.
 */
class WidgetGlobalSearch : AppFragment(R.layout.widget_global_search) {

    private lateinit var adapter: WidgetGlobalSearchAdapter
    private lateinit var guildsAdapter: WidgetGlobalSearchGuildsAdapter

    private val viewModel: WidgetGlobalSearchViewModel by appViewModels {
        WidgetGlobalSearchViewModel()
    }

    /** Shared with the host so a selection can close it. */
    private val dismissViewModel: WidgetGlobalSearchDismissModel by appActivityViewModels {
        WidgetGlobalSearchDismissModel()
    }

    private val searchBarText: TextInputEditText
        get() = requireView().findViewById(R.id.global_search_bar_text)

    override fun onViewBound(view: View) {
        super.onViewBound(view)

        val recycler = view.findViewById<RecyclerView>(R.id.global_search_recycler)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        adapter = MGRecyclerAdapter.configure(WidgetGlobalSearchAdapter(recycler))
        adapter.onSelectedListener = ::handleOnSelected

        val guildRecycler = view.findViewById<RecyclerView>(R.id.global_search_guild_list)
        guildsAdapter = MGRecyclerAdapter.configure(WidgetGlobalSearchGuildsAdapter(guildRecycler))
        guildsAdapter.onClickListener = ::handleGuildSelected
        guildsAdapter.onLongClickListener = { item ->
            // Long-pressing a server scopes the query to it instead of navigating.
            item.guild?.let { setSearchPrefix(WidgetGlobalSearchViewModel.GUILD_PREFIX, it.name) }
            item.guild != null
        }

        view.findViewById<View>(R.id.global_search_bar).setOnLongClickListener {
            showFilterPickerDialog()
            true
        }

        searchBarText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                viewModel.setFilter(s?.toString().orEmpty())
            }
        })

        arguments?.getString(EXTRA_SEARCH_TEXT)?.let { searchBarText.setText(it) }

        // Nothing produces friend suggestions or joinable servers from here yet, so the two
        // no-results shortcuts stay hidden rather than leading nowhere.
        view.findViewById<View>(R.id.global_search_add_a_friend_btn).visibility = View.GONE
        view.findViewById<View>(R.id.global_search_join_guild_btn).visibility = View.GONE
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    /** Puts the caret in the search box; the hosts call this when they become visible. */
    fun focusSearchBar() {
        showKeyboard(searchBarText)
    }

    /** The host dialog calls this on dismiss so the query does not survive into the next open. */
    fun onDismiss() {
        searchBarText.setText("")
    }

    private fun configureUI(model: WidgetGlobalSearchModel) {
        adapter.setData(model.data)
        guildsAdapter.setData(model.guildsList.orEmpty())

        requireView().findViewById<View>(R.id.global_search_guild_list).visibility =
            if (model.guildsList.isNullOrEmpty()) View.GONE else View.VISIBLE

        requireView().findViewById<AppViewFlipper>(R.id.global_search_view_flipper)
            .displayedChild = when {
            model.data.isNotEmpty() -> INDEX_RESULTS
            model.filter.isEmpty() -> INDEX_TUTORIAL
            else -> INDEX_NO_RESULTS
        }
    }

    /**
     * A tap navigates and dismisses; a long press on a person opens their profile instead, which is
     * the only way to reach a user sheet from here.
     */
    private fun handleOnSelected(
        view: View,
        isLongPress: Boolean,
        data: WidgetGlobalSearchModel.ItemDataPayload,
    ) {
        hideKeyboard(view)
        if (isLongPress && data is WidgetGlobalSearchModel.ItemUser) {
            WidgetUserSheet.show(data.user.id, parentFragmentManager)
            return
        }
        when (data) {
            is WidgetGlobalSearchModel.ItemChannel ->
                StoreStream.selectChannel(data.channel.guildId, data.channel.id)
            is WidgetGlobalSearchModel.ItemGuild -> StoreStream.selectGuild(data.guild.id)
            is WidgetGlobalSearchModel.ItemUser -> StoreStream.openDm(data.user.id)
            else -> return
        }
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        dismissViewModel.dismiss()
    }

    private fun handleGuildSelected(item: WidgetGlobalSearchGuildsModel.Item) {
        when (item.type) {
            WidgetGlobalSearchGuildsModel.Item.TYPE_GUILD ->
                item.guild?.let { StoreStream.selectGuild(it.id) }
            WidgetGlobalSearchGuildsModel.Item.TYPE_DM ->
                item.channel?.let { StoreStream.selectChannel(it.guildId, it.id) }
            else -> return
        }
        StoreStream.getTabsNavigation().selectHomeTab(StoreNavigation.PanelAction.CLOSE, true)
        dismissViewModel.dismiss()
    }

    /** The four prefixes, offered as a menu for anyone who does not know the shorthand. */
    private fun showFilterPickerDialog() {
        val options = FILTER_OPTIONS
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.filter_options)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, index ->
                setSearchPrefix(options[index].second)
            }
            .show()
    }

    private fun setSearchPrefix(prefix: Char, text: String = "") {
        searchBarText.setText("$prefix$text")
        searchBarText.setSelection(searchBarText.length())
        showKeyboard(searchBarText)
    }

    companion object {
        const val EXTRA_SEARCH_TEXT = "EXTRA_SEARCH_TEXT"

        private const val INDEX_RESULTS = 0
        private const val INDEX_TUTORIAL = 1
        private const val INDEX_NO_RESULTS = 2

        private val FILTER_OPTIONS = listOf(
            R.string.users to WidgetGlobalSearchViewModel.MENTION_PREFIX,
            R.string.text_channels to WidgetGlobalSearchViewModel.CHANNEL_PREFIX,
            R.string.voice_channels to WidgetGlobalSearchViewModel.VOICE_PREFIX,
            R.string.servers to WidgetGlobalSearchViewModel.GUILD_PREFIX,
        )

        fun create(searchText: String?): WidgetGlobalSearch = WidgetGlobalSearch().apply {
            arguments = Bundle().apply { putString(EXTRA_SEARCH_TEXT, searchText) }
        }
    }
}
