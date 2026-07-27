package com.discord.widgets.user

import com.discord.api.permission.Permission
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.Subscription
import rx.subjects.PublishSubject

/**
 * State for the "prune inactive members" dialog.
 *
 * Two things are being tracked at once, and they are what make this a view model rather than form
 * logic on the dialog:
 *
 * 1. **A live estimate.** Every change of the day window re-asks the server how many members would
 *    be removed, and only the newest answer may win — [pruneCountRequest] cancels the previous
 *    request so a slow reply for "7 days" cannot overwrite a fresh one for "30 days".
 * 2. **A live permission check.** Kicking is an elevated action: losing the permission, or the guild
 *    turning on required MFA while the dialog is open, must close it rather than let the user press
 *    a button that will fail.
 */
class WidgetPruneUsersViewModel(
    private val guildId: Long,
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeUsers: StoreUser = StoreStream.getUsers(),
    private val guildName: String = storeGuilds.getGuilds()[guildId]?.name.orEmpty(),
    private val restAPI: RestAPIInterface = RestAPI.api,
    storeDataObservable: Observable<StoreData> = observeStoreState(
        guildId,
        ObservationDeckProvider.get(),
        StoreStream.getPermissions(),
        StoreStream.getGuilds(),
        storeUsers,
    ),
) : AppViewModel<WidgetPruneUsersViewModel.ViewState>(
    WidgetPruneUsersViewModel.ViewState.Loading(DEFAULT_DAYS, guildName),
) {

    private val eventSubject: PublishSubject<Event> = PublishSubject.create()

    /** In-flight estimate request, kept only so the next one can cancel it. */
    private var pruneCountRequest: Subscription? = null

    private var whichPruneDays: PruneDays =
        (requireViewState() as ViewState.Loading).whichPruneDays

    init {
        storeDataObservable
            .map { it.canPrune }
            .distinctUntilChanged()
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetPruneUsersViewModel::class.java) { canKick ->
                if (canKick) {
                    getUpdatedPruneCount()
                } else {
                    // Not an error the user can act on — the dialog simply should not be open.
                    updateViewState(ViewState.LoadFailed(dismiss = true))
                }
            }
    }

    fun observeEvents(): Observable<Event> = eventSubject

    fun pruneDaysSelected(days: PruneDays) {
        whichPruneDays = days
        getUpdatedPruneCount()
    }

    fun pruneClicked() {
        pruneInProgress(true)
        restAPI
            .pruneMembers(guildId, RestAPIParams.PruneGuild(whichPruneDays.count, false))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(
                errorClass = WidgetPruneUsersViewModel::class.java,
                errorHandler = { error -> eventSubject.onNext(Event.RestClientFailed(error.cause)) },
                // Re-enables the button whether the prune succeeded or failed; on success the
                // dismiss below wins the race anyway.
                onTerminated = { pruneInProgress(false) },
            ) { eventSubject.onNext(Event.Dismiss) }
    }

    /**
     * Re-asks the server for the estimate, showing the loading state while it is outstanding.
     *
     * A failure here is reported twice on purpose: once as an [Event.RestClientFailed] so the dialog
     * can show the platform error, and once as a non-dismissing [ViewState.LoadFailed] so the button
     * stays disabled rather than offering to prune an unknown number of people.
     */
    private fun getUpdatedPruneCount() {
        updateViewState(loadingState())
        restAPI
            .getPruneCount(guildId, whichPruneDays.count)
            .restSubscribeOn()
            .map { it.pruned }
            .map { count -> ViewState.Loaded(whichPruneDays, count) as ViewState }
            .doOnError { throwable -> eventSubject.onNext(Event.RestClientFailed(throwable)) }
            .onErrorReturn { ViewState.LoadFailed(dismiss = false) }
            .ui(this)
            .appSubscribe(
                errorClass = WidgetPruneUsersViewModel::class.java,
                subscriptionHandler = { request ->
                    pruneCountRequest?.unsubscribe()
                    pruneCountRequest = request
                },
            ) { viewState -> updateViewState(viewState) }
    }

    private fun loadingState() = ViewState.Loading(whichPruneDays, guildName)

    /** Keeps the rendered count while the prune runs, but disables the button. */
    private fun pruneInProgress(inProgress: Boolean) {
        val loaded = viewState as? ViewState.Loaded ?: return
        updateViewState(loaded.withPruneInProgress(inProgress))
    }

    /** The two windows the dialog offers; [count] is the `days` query the API takes. */
    enum class PruneDays(val count: Int) {
        Seven(7),
        Thirty(30),
    }

    /** The store facts the permission check needs, sampled together. */
    data class StoreData(
        val permission: Long?,
        val guild: Guild?,
        val user: MeUser?,
    ) {
        /**
         * Kick is an elevated permission, so a guild with required MFA also demands that the
         * acting user has MFA enabled — the server enforces this and would reject the prune.
         */
        internal val canPrune: Boolean
            get() = permission != null && guild != null && user != null &&
                PermissionUtils.canAndIsElevated(
                    Permission.KICK_MEMBERS,
                    permission,
                    user.mfaEnabled,
                    guild.mfaLevel,
                )
    }

    sealed class ViewState {

        /** Shown until the first estimate arrives, and again on every window change. */
        data class Loading(
            val whichPruneDays: PruneDays,
            val guildName: String,
        ) : ViewState()

        data class Loaded(
            val pruneDays: PruneDays,
            val pruneCount: Int,
            private val pruneInProgress: Boolean = false,
        ) : ViewState() {
            /** Nothing to prune, or a prune already running, both mean "do not offer the button". */
            val pruneButtonEnabled: Boolean
                get() = pruneCount > 0 && !pruneInProgress

            /**
             * The reference flips this field with `copy`; here it is a method because
             * [pruneInProgress] stays private, matching the reference's own visibility (it is
             * `component3` and never exposed to the dialog).
             */
            internal fun withPruneInProgress(inProgress: Boolean): Loaded =
                copy(pruneInProgress = inProgress)
        }

        /**
         * @param dismiss true when the dialog should close outright (the user may not do this at
         *   all), false when it should stay open with the action disabled (the estimate failed).
         */
        data class LoadFailed(val dismiss: Boolean) : ViewState()
    }

    sealed class Event {
        object Dismiss : Event()
        data class RestClientFailed(val throwable: Throwable) : Event()
    }

    companion object {
        private val DEFAULT_DAYS = PruneDays.Thirty

        @JvmStatic
        fun observeStoreState(
            guildId: Long,
            observationDeck: ObservationDeck,
            storePermissions: StorePermissions,
            storeGuilds: StoreGuilds,
            storeUsers: StoreUser,
        ): Observable<StoreData> = observationDeck.connectRx(
            storePermissions,
            storeGuilds,
            storeUsers,
        ) {
            StoreData(
                storePermissions.getGuildPermissions()[guildId],
                storeGuilds.getGuilds()[guildId],
                storeUsers.getMe(),
            )
        }
    }
}
