package com.discord.utilities.channel

import android.content.Context
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.restapi.RestAPIParams
import com.discord.stores.Dispatcher
import com.discord.stores.SelectedChannelAnalyticsLocation
import com.discord.stores.StoreNavigation
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.IdleTracker
import com.discord.stores.updates.ObservationDeck
import com.discord.utilities.accessibility.AccessibilityUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.takeSingleUntilTimeout
import com.discord.utilities.rx.ui
import com.discord.widgets.voice.sheet.WidgetVoiceBottomSheet
import java.lang.ref.WeakReference

/**
 * The single owner of "open this channel".
 *
 * Every navigation into a channel goes through here — taps in the channel list, channel mentions
 * in chat, deep links, invites, notifications and the previous-channel fallback. Centralising it
 * is what makes the awkward cases consistent:
 *
 * * **A channel that has not been fetched yet.** [findAndSet] subscribes to the channel and
 *   permission stores and waits for the channel to appear *with* access before selecting it, so a
 *   deep link that arrives before `READY` still lands. If it never appears, the user gets a
 *   screen-reader announcement rather than a silent no-op.
 * * **Vocal channels are not text selections.** Selecting a voice (or stage) channel joins the
 *   voice channel and leaves the text selection alone; see [selectChannel].
 * * **Panel state.** Selecting anything closes the navigation drawer, which is what makes the
 *   channel list slide away on tap.
 *
 * Reference: `com.discord.utilities.channel.ChannelSelector`.
 *
 * **Deviation from the reference constructor.** The reference takes `(stream, dispatcher,
 * observationDeck)` and reaches into `stream`'s package-private store fields. This project's
 * `StoreStream` publishes its stores only through the companion accessors, so the `stream`
 * parameter would be dead weight and is omitted; store access below is via `StoreStream.getX()`,
 * which resolves to the same singletons.
 */
class ChannelSelector(
    private val dispatcher: Dispatcher,
    private val observationDeck: ObservationDeck,
) {

    /**
     * Commit a selection on the store thread.
     *
     * The guild is selected first so that `StoreChannelsSelected` validates the channel against
     * the right guild; both run in the same dispatch in the reference, and `Dispatcher` is FIFO on
     * a single store thread here, so scheduling them back to back is equivalent.
     */
    private fun gotoChannel(
        guildId: Long,
        channelId: Long,
        peekParent: Long?,
        analyticsLocation: SelectedChannelAnalyticsLocation?,
    ) {
        StoreStream.selectGuild(guildId)
        dispatcher.schedule {
            StoreStream.getChannelsSelected()
                .trySelectChannel(guildId, channelId, peekParent, analyticsLocation)
        }
    }

    /** Close the thread composer without creating a thread. */
    fun dismissCreateThread() {
        dispatcher.schedule {
            StoreStream.getChannelsSelected().dismissCreateThread()
        }
    }

    /**
     * Select [channelId] once it exists and the viewer can see it.
     *
     * This is the deep-link path: the channel may not be in the store yet, and the permission
     * computation for it may not have run. The observation is re-evaluated on every channel or
     * permission change and gives up after the shared `takeSingleUntilTimeout` window, at which
     * point the announcement below is the only feedback.
     *
     * A non-positive [channelId] is ignored, matching the reference.
     */
    fun findAndSet(context: Context?, channelId: Long) {
        if (channelId <= 0L) return

        observationDeck.connectRx(
            StoreStream.getChannels(),
            StoreStream.getPermissions(),
        ) {
            val channel = StoreStream.getChannels().findChannelById(channelId)
            val accessible = channel != null && PermissionUtils.hasAccess(
                channel,
                StoreStream.getPermissions().getPermissionsByChannel()[channelId],
            )
            if (accessible) {
                channel
            } else {
                context?.let {
                    AccessibilityUtils.sendAnnouncement(
                        it,
                        it.getString(R.string.inaccessible_channel_link_title),
                    )
                }
                null
            }
        }
            .filterNull()
            .takeSingleUntilTimeout()
            .computationLatest()
            .appSubscribe(ChannelSelector::class.java) { channel ->
                selectChannel(channel)
            }
    }

    /** Open the existing DM with [userId], or create and ingest it before selecting it. */
    @JvmOverloads
    fun findAndSetDirectMessage(context: Context, userId: Long, onSelected: (channelId: Long) -> Unit = {}) {
        routeDirectMessage(
            userId = userId,
            findExistingChannelId = { recipientId ->
                StoreStream.getChannels().getChannelsForGuild(0L).values
                    .firstOrNull { it.getDMRecipient()?.id == recipientId }
                    ?.id
            },
            createChannel = { recipientId, completed ->
                RestAPI.api.userCreateChannel(RestAPIParams.CreateChannel(recipientId))
                    .computationBuffered()
                    .ui()
                    .appSubscribe(ChannelSelector::class.java, context) { channel ->
                        StoreStream.getGatewayConnection().channelCreateOrUpdate.onNext(channel)
                        completed(channel.id)
                    }
            },
            selectChannel = { guildId, channelId -> selectChannel(guildId, channelId) },
            onSelected = onSelected,
        )
    }

    /**
     * Fetch [channelId] as a thread, ingest it, then select it.
     *
     * Threads are not delivered by `READY`, so a link to one has to be fetched before it can be
     * selected; [findAndSet] alone would wait forever.
     */
    fun findAndSetThread(context: Context?, channelId: Long) {
        RestAPI.api.getChannel(channelId)
            .computationBuffered()
            .ui()
            .appSubscribe(ChannelSelector::class.java, context) { channel ->
                StoreStream.handleThreadCreateOrUpdate(channel)
                findAndSet(context, channelId)
            }
    }

    /** Open the thread composer under [channelId], optionally seeded from [parentMessageId]. */
    @JvmOverloads
    fun openCreateThread(
        guildId: Long,
        channelId: Long,
        parentMessageId: Long? = null,
        startThreadLocation: String?,
    ) {
        dispatcher.schedule {
            StoreStream.getThreadDraft().clearDraftState()
            StoreStream.getChannelsSelected()
                .openCreateThread(guildId, channelId, parentMessageId, startThreadLocation)
            AnalyticsTracker.threadCreationStarted(channelId, guildId, startThreadLocation)
        }
    }

    /**
     * Show the voice-channel sheet for [channelId] without joining it.
     *
     * The fragment manager is held weakly because the observation can outlive the screen that
     * started it — the sheet is simply not shown if the screen went away first.
     */
    fun previewVoiceChannel(fragmentManager: FragmentManager, channelId: Long) {
        val fragmentManagerRef = WeakReference(fragmentManager)
        observationDeck.connectRx(
            StoreStream.getChannels(),
            StoreStream.getPermissions(),
        ) {
            StoreStream.getChannels().findChannelById(channelId)?.takeIf { channel ->
                PermissionUtils.hasAccess(
                    channel,
                    StoreStream.getPermissions().getPermissionsByChannel()[channelId],
                )
            }
        }
            .filterNull()
            .takeSingleUntilTimeout()
            .ui()
            .appSubscribe(ChannelSelector::class.java) { channel ->
                val manager = fragmentManagerRef.get() ?: return@appSubscribe
                StoreStream.getGuildSelected().set(channel.guildId)
                WidgetVoiceBottomSheet.show(
                    manager,
                    channelId,
                    true,
                    WidgetVoiceBottomSheet.FeatureContext.HOME,
                )
            }
    }

    /**
     * Select [channelId] in [guildId].
     *
     * The selection is recorded immediately, because `StoreChannelsSelected` already holds a
     * pending selection until the channel appears from cache restore or READY — waiting here
     * instead made a notification tap race that restore and silently drop on timeout.
     *
     * A **stage** channel is also a vocal selection: its text selection is invalid by
     * [com.discord.stores.StoreChannelsSelected.isValidResolution], so it resolves to the guild's
     * first available channel while the stage itself is joined as voice once the channel is known.
     *
     * A zero guild or channel id skips the channel entirely and goes straight through, which is how
     * "close the current channel" (`0, 0`) and DM selection (`guildId == 0`) are expressed.
     */
    @JvmOverloads
    fun selectChannel(
        guildId: Long,
        channelId: Long,
        peekParent: Long? = null,
        analyticsLocation: SelectedChannelAnalyticsLocation? = null,
    ) {
        IdleTracker.markUsed()
        StoreStream.getNavigation().setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)

        gotoChannel(guildId, channelId, peekParent, analyticsLocation)

        if (guildId == 0L || channelId == 0L) return
        StoreStream.getChannels().observeChannel(channelId)
            .filterNull()
            .takeSingleUntilTimeout()
            .appSubscribe(ChannelSelector::class.java) { channel ->
                if (channel.isStageVoiceChannel()) {
                    StoreStream.getVoiceChannelSelected().selectVoiceChannel(channelId)
                }
            }
    }

    /**
     * Opens a vocal channel's chat without joining the call.
     *
     * [selectChannel] cannot do this for a stage: it treats selecting one as "join the stage" and
     * deliberately leaves the text area on the guild's default channel, which is what a stage meant
     * before it had a chat of its own. Reading the chat is now a separate intent from being on
     * stage, so this selects the channel itself and connects nothing.
     */
    fun openVocalChannelChat(
        channel: Channel,
        analyticsLocation: SelectedChannelAnalyticsLocation? = null,
    ) {
        StoreStream.getNavigation().setNavigationPanelAction(StoreNavigation.PanelAction.CLOSE)
        gotoChannel(channel.guildId, channel.id, null, analyticsLocation)
    }

    /** [selectChannel] for a channel that is already in hand. A `null` channel is a no-op. */
    @JvmOverloads
    fun selectChannel(
        channel: Channel?,
        peekParent: Long? = null,
        analyticsLocation: SelectedChannelAnalyticsLocation? = null,
    ) {
        channel ?: return
        selectChannel(channel.guildId, channel.id, peekParent, analyticsLocation)
    }

    /**
     * Return to the channel that was open before the current one.
     *
     * A vocal previous channel is skipped (selecting it would re-join voice), and so is a
     * previous channel that no longer exists — both fall back to `0`, which selects the guild's
     * default channel.
     */
    fun selectPreviousChannel(guildId: Long) {
        StoreStream.getChannelsSelected().observePreviousId()
            .switchMap { previousChannelId ->
                StoreStream.getChannels().observeChannel(previousChannelId)
            }
            .takeSingleUntilTimeout()
            .appSubscribe(ChannelSelector::class.java) { previousChannel ->
                val channelId = previousChannel
                    ?.takeUnless { it.isVoiceChannel() }
                    ?.id
                    ?.takeIf { it >= 0L }
                    ?: 0L
                selectChannel(guildId, channelId)
            }
    }

    companion object {
        @Volatile
        private var instance: ChannelSelector? = null

        @JvmStatic
        fun init(dispatcher: Dispatcher, observationDeck: ObservationDeck) {
            instance = ChannelSelector(dispatcher, observationDeck)
        }

        @JvmStatic
        fun getInstance(): ChannelSelector = checkNotNull(instance) {
            "ChannelSelector.init() must be called from StoreStream before any navigation."
        }
    }
}

internal fun routeDirectMessage(
    userId: Long,
    findExistingChannelId: (Long) -> Long?,
    createChannel: (Long, (Long) -> Unit) -> Unit,
    selectChannel: (Long, Long) -> Unit,
    onSelected: (channelId: Long) -> Unit,
) {
    if (userId <= 0L) return
    val select: (Long) -> Unit = { channelId ->
        selectChannel(0L, channelId)
        onSelected(channelId)
    }
    findExistingChannelId(userId)?.let(select) ?: createChannel(userId, select)
}
