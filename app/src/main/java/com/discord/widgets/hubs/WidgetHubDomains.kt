package com.discord.widgets.hubs

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appActivityViewModels
import com.discord.i18n.I18nSpans
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.google.android.material.button.MaterialButton

/**
 * "Which school is yours?" — shown when one school email matches several Student Hubs.
 *
 * Binds `R.layout.widget_hub_domains`. The list comes straight from the arguments (the lookup already
 * returned it); picking one re-runs the lookup pinned to that hub and, on success, pushes
 * [WidgetHubAuthentication].
 *
 * The view model is activity-scoped because [WidgetHubDomainSearch] — pushed from the toolbar search
 * action — drives the same selection.
 */
class WidgetHubDomains : AppFragment(R.layout.widget_hub_domains) {

    private val args: HubDomainArgs by hubArgs()

    private val viewModel: WidgetHubDomainsViewModel by appActivityViewModels { WidgetHubDomainsViewModel() }

    private var adapter: HubDomainAdapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        val listAdapter = MGRecyclerAdapter.configure(HubDomainAdapter(recycler, ::onServerClicked))
        adapter = listAdapter
        listAdapter.setGuildInfos(args.guildInfos)

        setActionBarOptionsMenu(R.menu.menu_search, { item ->
            if (item.itemId == R.id.search) {
                // No arguments: the search screen falls back to the launching intent, which already
                // carries this screen's HubDomainArgs. Matches the reference.
                AppScreenRouter.replaceFragment(
                    fragmentManager = parentFragmentManager,
                    context = view.context,
                    screen = WidgetHubDomainSearch::class.java,
                    addToBackStack = true,
                )
            }
        })

        val waitlistButton = view.findViewById<MaterialButton>(R.id.waitlist_button)
        // "Can't find your school? [Join the waitlist.](onJoinWaitlist)" — the whole sentence is the
        // button label in this layout, so the hook is not wired to a span here.
        waitlistButton.text = I18nSpans.render(getString(R.string.hub_email_connection_guild_select_subheader))
        waitlistButton.setOnClickListener {
            AppScreenRouter.replaceFragment(
                fragmentManager = parentFragmentManager,
                context = it.context,
                screen = WidgetHubWaitlist::class.java,
                addToBackStack = true,
                arguments = HubWaitlistArgs(args.email).toArgsBundle(),
            )
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubDomains::class.java) { updateView(it) }
    }

    override fun onDestroyView() {
        adapter?.unsubscribeFromUpdates()
        adapter = null
        super.onDestroyView()
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
            // Clears the success so navigating back here does not immediately push again.
            viewModel.reset()
        }
    }
}
