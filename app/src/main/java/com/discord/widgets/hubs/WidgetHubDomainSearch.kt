package com.discord.widgets.hubs

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.i18n.I18nSpans
import com.discord.i18n.withClickableHook
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setOnImeActionDone
import com.discord.utilities.view.text.LinkifiedTextView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale

/**
 * Filters the matched-hub list by name. Binds `R.layout.widget_hub_domain_search`.
 *
 * Filtering is local — the whole candidate list arrived with the arguments — so there is no loading
 * state to render. The empty state doubles as the initial state: before the first search it invites a
 * query, after a fruitless one it says nothing matched and offers the waitlist.
 *
 * Shares [WidgetHubDomainsViewModel] with [WidgetHubDomains] (activity-scoped), so selecting a hub
 * here advances the flow exactly as selecting one on the list behind it does.
 */
class WidgetHubDomainSearch : AppFragment(R.layout.widget_hub_domain_search) {

    private val args: HubDomainArgs by hubArgs()

    private val viewModel: WidgetHubDomainsViewModel by appActivityViewModels { WidgetHubDomainsViewModel() }

    private var adapter: HubDomainAdapter? = null
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var searchBar: TextInputLayout
    private lateinit var searchBarText: TextInputEditText

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        recycler = view.findViewById(R.id.recycler_view)
        emptyState = view.findViewById(R.id.empty_state)
        emptyTitle = view.findViewById(R.id.empty_title)
        searchBar = view.findViewById(R.id.search_bar)
        searchBarText = view.findViewById(R.id.search_bar_text)

        val listAdapter = MGRecyclerAdapter.configure(HubDomainAdapter(recycler, ::onServerClicked))
        adapter = listAdapter

        searchBar.setStartIconOnClickListener {
            activity?.onBackPressed()
            hideKeyboard(view)
        }
        searchBar.setOnImeActionDone { searchForGuild() }
        view.findViewById<FloatingActionButton>(R.id.search).setOnClickListener { searchForGuild() }

        val emptyDescription = view.findViewById<LinkifiedTextView>(R.id.empty_description)
        // "Can't find your school? [Join the waitlist.](onJoinWaitlist)"
        emptyDescription.text = I18nSpans
            .render(getString(R.string.hub_email_connection_guild_select_subheader))
            .withClickableHook(JOIN_WAITLIST_HOOK) {
                AppScreenRouter.replaceFragment(
                    fragmentManager = parentFragmentManager,
                    context = view.context,
                    screen = WidgetHubWaitlist::class.java,
                    addToBackStack = true,
                    arguments = HubWaitlistArgs(args.email).toArgsBundle(),
                )
            }

        // Nothing has been searched for yet: show the invitation, not an empty list.
        renderResults(emptyList(), searched = false)
        showKeyboard(searchBarText)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubDomainSearch::class.java) { updateView(it) }
    }

    override fun onDestroyView() {
        adapter?.unsubscribeFromUpdates()
        adapter = null
        super.onDestroyView()
    }

    private fun searchForGuild() {
        val query = searchBarText.text?.toString().orEmpty().lowercase(Locale.getDefault())
        val matches = args.guildInfos.filter { it.name.lowercase(Locale.getDefault()).contains(query) }
        renderResults(matches, searched = true)
    }

    private fun renderResults(matches: List<DomainGuildInfo>, searched: Boolean) {
        emptyTitle.setText(
            if (matches.isEmpty() && searched) R.string.directory_search_no_results_title
            else R.string.directory_search_initial_title,
        )
        emptyState.visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
        adapter?.setGuildInfos(matches)
    }

    private fun onServerClicked(guildInfo: DomainGuildInfo) {
        viewModel.onGuildClicked(guildInfo.id, args.email)
    }

    private fun updateView(state: DomainsState) {
        val context = context ?: return
        state.verifyEmailAsync.handleResponse(context) {
            AppScreenRouter.replaceFragment(
                fragmentManager = parentFragmentManager,
                context = context,
                screen = WidgetHubAuthentication::class.java,
                addToBackStack = true,
                arguments = HubAuthenticationArgs(args.email, state.selectedGuildId).toArgsBundle(),
            )
            viewModel.reset()
        }
    }

    private companion object {
        const val JOIN_WAITLIST_HOOK = "onJoinWaitlist"
    }
}
