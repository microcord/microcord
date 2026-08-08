package com.discord.widgets.hubs.events

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.restapi.RestAPIParams
import com.discord.stores.JoinGuildSources
import com.discord.stores.StoreStream
import com.discord.stores.utilities.Success
import com.discord.stores.utilities.handleResponse
import com.discord.utilities.guildscheduledevent.GuildScheduledEventUtilities
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rest.contextPropertiesOf
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.guildscheduledevent.GuildScheduledEventTiming
import com.discord.widgets.guildscheduledevent.ScheduledEventUi
import com.discord.widgets.guildscheduledevent.WidgetGuildScheduledEventDetailsBottomSheet
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
            WidgetGuildScheduledEventDetailsBottomSheet.showForDirectory(
                parentFragmentManager,
                eventData.event.id,
                args.guildId,
                args.directoryChannelId,
            )
        }

        override fun onPrimaryButtonClicked(eventData: HubGuildScheduledEventData) {
            joinAndGoToGuild(eventData)
        }

        override fun onSecondaryButtonClicked(eventData: HubGuildScheduledEventData) {
            viewModel.toggleRsvp(eventData.event)
        }

        override fun onShareClicked(eventData: HubGuildScheduledEventData) {
            GuildScheduledEventUtilities.launchInvite(
                eventData.canShare,
                this@WidgetHubEventsPage,
                eventData.event.guildId,
                eventData.channel,
                eventData.event.id,
            )
        }
    }

    private fun joinAndGoToGuild(eventData: HubGuildScheduledEventData) {
        val event = eventData.event
        if (eventData.isInGuild) {
            StoreStream.selectGuild(event.guildId)
            dismiss()
            return
        }
        RestAPI.apiOrNull?.joinGuild(
            guildId = event.guildId,
            isLurker = false,
            sessionId = null,
            directoryChannelId = args.directoryChannelId,
            body = RestAPIParams.InviteCode(),
            context = directoryJoinContext(),
        )
            ?.restSubscribeOn()
            ?.ui(this)
            ?.appSubscribe(WidgetHubEventsPage::class.java, context = requireContext()) {
                if (shouldToggleRsvpAfterHubJoin(
                        eventData.isRsvped,
                        ScheduledEventUi.timing(event),
                        event.status,
                    )
                ) {
                    viewModel.toggleRsvp(event)
                }
                StoreStream.selectGuild(event.guildId)
                parentFragmentManager.fragments.filterIsInstance<AppBottomSheet>().forEach(AppBottomSheet::dismiss)
            }
    }

    private fun directoryJoinContext(): String =
        contextPropertiesOf("source" to JoinGuildSources.DIRECTORY_EVENTS)

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

internal fun shouldToggleRsvpAfterHubJoin(
    isRsvped: Boolean,
    timing: GuildScheduledEventTiming,
    status: GuildScheduledEventStatus,
): Boolean = !isRsvped && timing != GuildScheduledEventTiming.LIVE && status != GuildScheduledEventStatus.COMPLETED
