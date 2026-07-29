package com.discord.widgets.guilds.create

import android.content.Context
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.guild.Guild
import com.discord.app.AppViewModel
import com.discord.models.user.User
import com.discord.restapi.RestAPIInterface
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreGuildSelected
import com.discord.stores.StoreGuildTemplates
import com.discord.stores.StoreStream
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import retrofit2.HttpException
import rx.Observable
import rx.subjects.PublishSubject

class WidgetGuildCreateViewModel(
    @StringRes private val defaultGuildNameFormatRes: Int,
    private val stockGuildTemplate: StockGuildTemplate = StockGuildTemplate.CREATE,
    private val guildTemplateCode: String? = null,
    private val showChannelPrompt: Boolean,
    private val analyticsLocation: String,
    private val customTitle: String? = null,
    private val closeWithResult: Boolean = false,
    private val selectedGuildStore: StoreGuildSelected = StoreStream.getGuildSelected(),
    private val restApi: RestAPIInterface = requireNotNull(RestAPI.apiOrNull),
    storeObservable: Observable<StoreState> = defaultStoreObservable(guildTemplateCode),
) : AppViewModel<WidgetGuildCreateViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val meUser: User,
        val guildTemplate: StoreGuildTemplates.GuildTemplateState,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Initialized(
            val guildTemplate: StoreGuildTemplates.GuildTemplateState,
            @StringRes val defaultGuildNameFormatRes: Int,
            val username: String,
            val guildName: String? = null,
            val guildIconUri: String? = null,
            val isBusy: Boolean = false,
            val customTitle: String? = null,
        ) : ViewState() {
            fun getGuildName(context: Context): String = guildName
                ?: formatGuildName(context.getString(defaultGuildNameFormatRes), username)
        }
    }

    sealed class Event {
        data class ShowToast(@StringRes val stringResId: Int) : Event()
        data class LaunchChannelPrompt(val guildId: Long) : Event()
        data class LaunchInviteShareScreen(val guildId: Long) : Event()
        data class CloseWithResult(val guildId: Long) : Event()
    }

    private val events = PublishSubject.create<Event>()

    init {
        guildTemplateCode?.let(StoreStream.getGuildTemplates()::maybeInitTemplateState)
        storeObservable.ui(this).appSubscribe(WidgetGuildCreateViewModel::class.java) { state ->
            updateViewState(
                ViewState.Initialized(
                    guildTemplate = state.guildTemplate,
                    defaultGuildNameFormatRes = defaultGuildNameFormatRes,
                    username = state.meUser.username,
                    customTitle = customTitle,
                ),
            )
        }
    }

    fun observeEvents(): Observable<Event> = events

    fun updateGuildName(guildName: String) = updateInitialized { copy(guildName = guildName) }

    fun updateGuildIconUri(guildIconUri: String?) = updateInitialized { copy(guildIconUri = guildIconUri) }

    fun createGuild(context: Context) {
        val state = viewState as? ViewState.Initialized ?: return
        if (state.isBusy) return
        val name = state.getGuildName(context)
        if (name.isBlank()) {
            events.onNext(Event.ShowToast(R.string.server_name_required))
            return
        }

        val request: Observable<Guild> = when (val template = state.guildTemplate) {
            is StoreGuildTemplates.GuildTemplateState.Resolved -> restApi.createGuildFromTemplate(
                template.guildTemplate.code,
                RestAPIParams.CreateGuildFromTemplate(name, state.guildIconUri),
            )
            else -> restApi.createGuild(
                RestAPIParams.CreateGuild(
                    name,
                    state.guildIconUri,
                    stockGuildTemplate.getChannels(context.resources),
                    stockGuildTemplate.getSystemChannelId(),
                ),
            ).map { response -> response.body() ?: throw HttpException(response) }
        }

        updateViewState(state.copy(isBusy = true))
        request.restSubscribeOn(false).ui(this).appSubscribe(
            WidgetGuildCreateViewModel::class.java,
            context = context,
            errorHandler = { updateInitialized { copy(isBusy = false) } },
        ) { guild ->
            if (!closeWithResult) selectedGuildStore.set(guild.id)
            events.onNext(
                when {
                    showChannelPrompt -> Event.LaunchChannelPrompt(guild.id)
                    closeWithResult -> Event.CloseWithResult(guild.id)
                    else -> Event.LaunchInviteShareScreen(guild.id)
                },
            )
        }
    }

    private inline fun updateInitialized(transform: ViewState.Initialized.() -> ViewState.Initialized) {
        (viewState as? ViewState.Initialized)?.let { updateViewState(it.transform()) }
    }

    companion object {
        private fun defaultStoreObservable(code: String?): Observable<StoreState> {
            val template = code?.let(StoreStream.getGuildTemplates()::observeGuildTemplate)
                ?: Observable.just(StoreGuildTemplates.GuildTemplateState.None)
            return Observable.combineLatest(
                StoreStream.getUsers().observeMe().take(1),
                template,
                ::StoreState,
            )
        }

        internal fun formatGuildName(format: String, username: String) =
            format.replace("{username}", username)
    }
}
