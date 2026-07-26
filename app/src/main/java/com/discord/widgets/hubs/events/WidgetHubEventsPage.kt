package com.discord.widgets.hubs.events

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.stores.StoreStream
import com.discord.stores.utilities.Success
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.hubs.hubDialogArgs
import com.discord.widgets.hubs.toArgsBundle

/**
 * The scheduled events published into a hub directory channel. Binds
 * `R.layout.widget_hub_events_page`.
 *
 * The list is header + events + footer; the footer owns the loading, empty and retry states, so the
 * sheet has something to show before the first fetch returns.
 */
class WidgetHubEventsPage : AppBottomSheet() {

    private val args: HubEventsArgs by hubDialogArgs()

    private val viewModel: WidgetHubEventsViewModel by appViewModels {
        WidgetHubEventsViewModel(args.guildId, args.directoryChannelId)
    }

    private var adapter: WidgetHubEventsPageAdapter? = null
    private lateinit var title: TextView

    /** Row callbacks. Kept as one object so a row never reaches into the fragment directly. */
    val listener: HubEventsEventListener = object : HubEventsEventListener {
        override fun dismissHeader() = viewModel.dismissHeader()

        override fun fetchGuildScheduledEvents() = viewModel.fetchGuildScheduledEvents()

        override fun onCardClicked(eventData: HubGuildScheduledEventData) {
            // The reference opens WidgetGuildScheduledEventDetailsBottomSheet, which does not exist
            // in this project; selecting the event's guild is the closest reachable behaviour.
            StoreStream.selectGuild(eventData.event.guildId)
            dismiss()
        }

        override fun onPrimaryButtonClicked(eventData: HubGuildScheduledEventData) {
            StoreStream.selectChannel(eventData.event.guildId, eventData.event.channelId ?: return)
            dismiss()
        }

        override fun onSecondaryButtonClicked(eventData: HubGuildScheduledEventData) {
            viewModel.toggleRsvp(eventData.event)
        }

        override fun onShareClicked(eventData: HubGuildScheduledEventData) {
            // Sharing routes through WidgetGuildInviteShare, which is not reconstructed; reported as
            // an error rather than silently doing nothing under a visible button.
            context?.let { Toast.makeText(it, R.string.error_generic_title, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun getContentViewResId(): Int = R.layout.widget_hub_events_page

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        title = view.findViewById(R.id.title)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        adapter = MGRecyclerAdapter.configure(WidgetHubEventsPageAdapter(recycler, listener))
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetHubEventsPage::class.java) { configUI(it) }
    }

    override fun onDestroyView() {
        adapter?.unsubscribeFromUpdates()
        adapter = null
        super.onDestroyView()
    }

    private fun configUI(state: WidgetHubEventsState) {
        // Before the first response there is nothing to lay out but the footer's spinner, and the
        // reference deliberately leaves the sheet untouched until then.
        if (state.eventsData.isEmpty() && state.eventsAsync !is Success<*>) return

        val rows = ArrayList<HubEventsPage>(state.eventsData.size + 2)
        if (state.showHeader) rows.add(HubEventsPage.Header)
        state.eventsData.mapTo(rows) { HubEventsPage.Event(it) }
        rows.add(HubEventsPage.Footer(state.eventsAsync))
        adapter?.setItems(rows)

        title.text = context?.i18nPlural(
            R.plurals.guild_events_plural_number,
            state.eventsData.size,
            "#" to state.eventsData.size,
        )

        // A failure toasts through the default handler so a dead fetch is not silent; a success
        // scrolls the freshly loaded list back to the top and clears the state so the footer stops
        // reporting it.
        state.eventsAsync.handleResponse(requireContext()) {
            adapter?.recycler?.smoothScrollToPosition(0)
            viewModel.reset()
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager, guildId: Long, directoryChannelId: Long) {
            WidgetHubEventsPage().apply {
                arguments = HubEventsArgs(guildId, directoryChannelId).toArgsBundle()
                show(fragmentManager, WidgetHubEventsPage::class.java.name)
            }
        }
    }
}
