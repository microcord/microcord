package com.discord.widgets.announcements

import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.utilities.error.RestApiError
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject

/**
 * Drives [WidgetChannelFollowSheet]: "publish this announcement channel into one of *my* servers".
 *
 * The destination list is not "every guild I am in" — it is every guild in which I hold
 * `MANAGE_WEBHOOKS` on at least one text or announcement channel, computed by crossing
 * [StorePermissions.observePermissionsForAllChannels] with the channel table. Guild and channel
 * selection are two [BehaviorSubject]s folded into the same combine, so picking a guild
 * re-resolves the channel list without a second round trip.
 *
 * When the cross yields nothing the sheet flips to its "you cannot follow anywhere" panel rather
 * than showing two empty pickers.
 */
class WidgetChannelFollowSheetViewModel(
    private val sourceGuildId: Long,
    private val sourceChannelId: Long,
    val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    val storeChannels: StoreChannels = StoreStream.getChannels(),
    val storePermissions: StorePermissions = StoreStream.getPermissions(),
    private val api: RestAPIInterface = RestAPI.api,
) : AppViewModel<WidgetChannelFollowSheetViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class Event {
        object FollowSuccess : Event()
    }

    sealed class ViewState {
        /** Before the first store emission; the sheet shows its inflated placeholder. */
        object Uninitialized : ViewState()

        data class Loaded(
            val sourceChannel: Channel?,
            val sourceGuild: Guild?,
            val selectedGuild: Guild?,
            val selectedChannel: Channel?,
            val availableGuilds: Set<Long>,
            val availableChannels: Set<Long>,
            @StringRes val errorTextRes: Int? = null,
        ) : ViewState()
    }

    private val selectedGuildSubject: BehaviorSubject<Long?> = BehaviorSubject.create<Long?>(null)
    private val selectedChannelSubject: BehaviorSubject<Long?> = BehaviorSubject.create<Long?>(null)
    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    init {
        observeViewStateFromStores()
            .computationLatest()
            .ui(this)
            .subscribe { updateViewState(it) }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun selectGuild(guildId: Long) {
        selectedGuildSubject.onNext(guildId)
    }

    fun selectChannel(channelId: Long) {
        selectedChannelSubject.onNext(channelId)
    }

    fun followChannel(webhookChannelId: Long) {
        api.createChannelFollower(sourceChannelId, RestAPIParams.ChannelFollowerPost(webhookChannelId))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetChannelFollowSheetViewModel::class.java,
                errorHandler = ::handleChannelFollowError,
            ) { eventSubject.onNext(Event.FollowSuccess) }
    }

    /**
     * Failures stay inside the sheet as an inline message. `30007` is "maximum number of webhooks
     * reached", which is actionable by picking a different channel, so it gets its own copy.
     */
    private fun handleChannelFollowError(error: RestApiError) {
        val errorTextRes = if (error.code == MAX_WEBHOOKS_ERROR_CODE) {
            R.string.follow_modal_too_many_webhooks
        } else {
            R.string.follow_modal_fail
        }
        val current = viewState as? ViewState.Loaded ?: return
        updateViewState(current.copy(errorTextRes = errorTextRes))
    }

    private fun observeViewStateFromStores(): Observable<ViewState.Loaded> =
        Observable.combineLatest(
            storeGuilds.observeGuild(sourceGuildId),
            storeChannels.observeChannel(sourceChannelId),
            calculateChannelsWithPermissions(),
            selectedGuildSubject.switchMap { id ->
                if (id != null) storeGuilds.observeGuild(id) else Observable.just<Guild?>(null)
            },
            selectedChannelSubject.switchMap { id ->
                if (id != null) storeChannels.observeChannel(id) else Observable.just<Channel?>(null)
            },
        ) { sourceGuild, sourceChannel, channelsByGuild, selectedGuild, selectedChannel ->
            ViewState.Loaded(
                sourceChannel = sourceChannel,
                sourceGuild = sourceGuild,
                selectedGuild = selectedGuild,
                selectedChannel = selectedChannel,
                availableGuilds = channelsByGuild.keys,
                availableChannels = channelsByGuild.values.flatten().map(Channel::id).toSet(),
            )
        }

    /** Text and announcement channels the viewer can manage webhooks in, grouped by guild. */
    private fun calculateChannelsWithPermissions(): Observable<Map<Long, List<Channel>>> =
        storePermissions.observePermissionsForAllChannels()
            .distinctUntilChanged()
            .switchMap { permissions ->
                storeChannels.observeGuildAndPrivateChannels()
                    .distinctUntilChanged()
                    .map { channels ->
                        channels.values
                            .filter { channel ->
                                (channel.type == Channel.GUILD_ANNOUNCEMENT || channel.type == Channel.GUILD_TEXT) &&
                                    PermissionUtils.can(
                                        Permission.MANAGE_WEBHOOKS,
                                        permissions[channel.id] ?: 0L,
                                    )
                            }
                            .groupBy(Channel::guildId)
                    }
            }

    companion object {
        /** JSON error code for "maximum number of webhooks reached". */
        private const val MAX_WEBHOOKS_ERROR_CODE = 30007
    }
}
