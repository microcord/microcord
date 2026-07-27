package com.discord.widgets.stage.start

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.calls.CallEventsButtonView
import com.discord.widgets.stage.sheet.WidgetStageStartEventBottomSheet

/**
 * The screen a moderator lands on when they enter a stage channel that is not live yet: start now,
 * start a scheduled event, schedule one for later, or leave.
 *
 * The screen dismisses itself the moment an event goes active in this channel — whether the
 * moderator started it here or somebody else started it elsewhere.
 */
class WidgetModeratorStartStage : AppFragment(R.layout.widget_moderator_start_stage) {

    private val channelId: Long by lazy {
        mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, -1L)
    }
    private val guildId: Long by lazy {
        mostRecentIntent.getLongExtra(EXTRA_GUILD_ID, -1L)
    }

    private val viewModel: ModeratorStartStageViewModel by appViewModels {
        ModeratorStartStageViewModel(channelId)
    }

    private lateinit var adapter: ModeratorStartStageAdapter
    private lateinit var eventsButton: CallEventsButtonView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        eventsButton = view.findViewById(R.id.toolbar_events_button)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler_view)
        adapter = ModeratorStartStageAdapter(recycler, ::handleListItemClick)
        recycler.layoutManager = LinearLayoutManager(recycler.context)
        recycler.adapter = adapter

        view.findViewById<Button>(R.id.moderator_start_stage_exit_stage_button).setOnClickListener {
            viewModel.disconnectFromStage()
            activity?.finish()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetModeratorStartStage::class.java) { configureUi(it) }
    }

    private fun configureUi(viewState: ModeratorStartStageViewModel.ViewState) {
        if (viewState.channelEventActive) {
            launchStageCall(channelId)
            return
        }
        eventsButton.configure(parentFragmentManager, guildId, channelId, viewState.channelEvents)
        adapter.setData(viewState.items)
    }

    private fun handleListItemClick(item: ModeratorStartStageItem) {
        when (item) {
            is ModeratorStartStageItem.ListItem.StaticOption.StartStage ->
                WidgetStageStartEventBottomSheet.show(parentFragmentManager, channelId) {
                    launchStageCall(channelId)
                }
            is ModeratorStartStageItem.ListItem.StaticOption.ScheduleEvent ->
                // The reference opens `WidgetGuildScheduledEventLocationSelect` here. That screen
                // is not reconstructed yet, so the row is left inert rather than sent somewhere
                // wrong. See the handoff report.
                Unit
            is ModeratorStartStageItem.ListItem.Event ->
                // The reference opens `WidgetPreviewGuildScheduledEvent` with `Action.START_EVENT`
                // and an activity-result launcher. That screen is not reconstructed yet.
                Unit
            is ModeratorStartStageItem.Continue -> launchStageCall(channelId)
            is ModeratorStartStageItem.Header,
            is ModeratorStartStageItem.Waiting,
            -> Unit
        }
    }

    /**
     * Hands over to the fullscreen stage call and closes this screen, so Back from the call does
     * not come back to a start menu for a stage that is now live.
     */
    private fun launchStageCall(channelId: Long) {
        launchFullscreenCall?.invoke(requireContext(), channelId)
        activity?.finish()
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        /**
         * Opens the fullscreen stage call. The reference calls
         * `WidgetCallFullscreen.launch(context, channelId, isStageCall = true, …)` directly;
         * `WidgetCallFullscreen` is not reconstructed yet, so the voice slice installs it here the
         * same way it does on `StageChannelJoinHelper`. Until then starting a stage leaves the user
         * on the channel with no fullscreen surface.
         */
        @JvmStatic
        var launchFullscreenCall: ((Context, Long) -> Unit)? = null

        @JvmStatic
        fun launch(context: Context, channelId: Long, guildId: Long) {
            AppScreenRouter.launch(
                context,
                WidgetModeratorStartStage::class.java,
                Intent().apply {
                    putExtra(EXTRA_CHANNEL_ID, channelId)
                    putExtra(EXTRA_GUILD_ID, guildId)
                },
            )
        }
    }
}
