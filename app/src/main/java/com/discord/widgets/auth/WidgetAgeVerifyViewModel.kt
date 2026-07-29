package com.discord.widgets.auth

import androidx.annotation.MainThread
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.user.NsfwAllowance
import com.discord.app.AppViewModel
import com.discord.stores.StoreAuthentication
import com.discord.stores.StoreChannels
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import java.util.Calendar
import rx.Observable
import rx.subjects.PublishSubject

class WidgetAgeVerifyViewModel(
    storeObservable: Observable<StoreState>,
    private val submitAge: (Long, (AuthResult) -> Unit) -> Unit,
    private val selectSafeChannel: (Channel?) -> Unit = { channel ->
        if (channel == null) ChannelSelector.getInstance().selectChannel(0L, 0L)
        else ChannelSelector.getInstance().selectChannel(channel)
    },
) : AppViewModel<WidgetAgeVerifyViewModel.ViewState>(ViewState()) {
    private val eventsSubject = PublishSubject.create<Event>()

    init {
        storeObservable.ui(this).appSubscribe(WidgetAgeVerifyViewModel::class.java) { handleStoreState(it) }
    }

    fun observeEvents(): Observable<Event> = eventsSubject

    @MainThread
    fun backToSafety(): Boolean {
        val safeChannel = requireViewState().safeChannel
        selectSafeChannel(safeChannel)
        return safeChannel == null
    }

    @MainThread
    fun onConfirmBackClicked() {
        updateViewState(requireViewState().copy(displayedChild = Views.VIEW_AGE_VERIFY))
    }

    @MainThread
    fun setDateOfBirth(dateOfBirth: Long) {
        updateViewState(requireViewState().copy(dateOfBirth = dateOfBirth, errorStringId = null))
    }

    @MainThread
    fun submit(isNsfwChannel: Boolean) {
        val state = requireViewState()
        val dateOfBirth = state.dateOfBirth ?: return
        if (isNsfwChannel && age(dateOfBirth) < 18 && state.displayedChild == Views.VIEW_AGE_VERIFY) {
            updateViewState(state.copy(displayedChild = Views.VIEW_AGE_CONFIRM))
            return
        }

        updateViewState(state.copy(isSubmitting = true, errorStringId = null))
        submitAge(dateOfBirth) { result ->
            when (result) {
                is AuthResult.Success -> {
                    updateViewState(requireViewState().copy(isSubmitting = false))
                    eventsSubject.onNext(Event.Verified)
                }
                is AuthResult.Challenge -> updateViewState(
                    requireViewState().copy(
                        isSubmitting = false,
                        displayedChild = Views.VIEW_AGE_GATED,
                        underageMessage = result.state.underageMessage,
                    ),
                )
                is AuthResult.Failure -> updateViewState(
                    requireViewState().copy(
                        isSubmitting = false,
                        errorStringId = R.string.age_gate_failed_to_update_birthday,
                    ),
                )
                is AuthResult.CaptchaRequired -> updateViewState(
                    requireViewState().copy(
                        isSubmitting = false,
                        errorStringId = R.string.age_gate_failed_to_update_birthday,
                    ),
                )
            }
        }
    }

    internal fun handleStoreState(storeState: StoreState) {
        updateViewState(
            requireViewState().copy(
                safeChannel = storeState.safeChannel,
                shouldClose = !(storeState.authed && storeState.nsfwAllowed == NsfwAllowance.UNKNOWN),
            ),
        )
    }

    data class StoreState(
        val safeChannel: Channel?,
        val nsfwAllowed: NsfwAllowance,
        val authed: Boolean,
    )

    data class ViewState(
        val isSubmitting: Boolean = false,
        val errorStringId: Int? = null,
        val displayedChild: Int = Views.VIEW_AGE_VERIFY,
        val dateOfBirth: Long? = null,
        val underageMessage: String? = null,
        val safeChannel: Channel? = null,
        val shouldClose: Boolean = false,
    )

    sealed class Event {
        object Verified : Event()
    }

    object Views {
        const val VIEW_AGE_VERIFY = 0
        const val VIEW_AGE_CONFIRM = 1
        const val VIEW_AGE_GATED = 2
    }

    companion object {
        internal fun observeStores(
            users: StoreUser = StoreStream.getUsers(),
            authentication: StoreAuthentication = StoreStream.getAuthentication(),
            channelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
            channels: StoreChannels = StoreStream.getChannels(),
        ): Observable<StoreState> {
            val safeChannel = channelsSelected.observeSelectedChannel().switchMap { selected ->
                val guildId = selected?.guildId?.takeIf { it > 0L }
                if (guildId == null) Observable.just(null)
                else channels.observeDefaultChannel(guildId).map { it?.takeUnless(Channel::nsfw) }
            }
            return Observable.combineLatest(
                users.observeMe(),
                authentication.observeIsAuthed(),
                safeChannel,
            ) { me, authed, channel -> StoreState(channel, me.nsfwAllowance, authed) }
        }

        internal fun age(dateOfBirth: Long, now: Long = System.currentTimeMillis()): Int {
            val birth = Calendar.getInstance().apply { timeInMillis = dateOfBirth }
            val today = Calendar.getInstance().apply { timeInMillis = now }
            var years = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
            if (today.get(Calendar.MONTH) < birth.get(Calendar.MONTH) ||
                today.get(Calendar.MONTH) == birth.get(Calendar.MONTH) &&
                today.get(Calendar.DAY_OF_MONTH) < birth.get(Calendar.DAY_OF_MONTH)
            ) years--
            return years
        }
    }
}
