package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.stageinstance.StageInstance
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelInvite
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreInviteSettings
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject

class GuildInviteShareSheetViewModel(
    private val channelId: Long?,
    val guildId: Long,
    private val storeInviteSettings: StoreInviteSettings = InviteSettingsSource.store,
    private val storeMessages: StoreMessages = StoreStream.getMessages(),
    private val targetChannelSelector: TargetChannelSelector = TargetChannelSelector(),
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<GuildInviteShareSheetViewModel.ViewState>() {

    sealed class StoreState {
        object Invalid : StoreState()
        data class Valid(
            val inviteSettings: ModelInvite.Settings,
            val invitableChannels: Map<Long, Channel>,
            val me: MeUser,
            val dms: List<Channel>,
            val guild: Guild,
            val inviteSuggestions: List<InviteSuggestion>,
            val guildStageInstances: Map<Long, StageInstance>,
        ) : StoreState()
    }

    data class ViewState(
        val invite: GuildInvite?,
        val inviteSettings: ModelInvite.Settings?,
        val inviteSuggestionItems: List<InviteSuggestionItemV2>,
        val channel: Channel?,
        val searchQuery: String = "",
        val sentInvites: Map<String, Set<Long>> = emptyMap(),
        val showInviteSettings: Boolean,
        val guildId: Long,
    ) {
        val hasResults = inviteSuggestionItems.isNotEmpty()
        val hasSearchResults = hasResults && inviteSuggestionItems.first() !is InviteSuggestionItemV2.SearchNoResultsItem
    }

    private val searchQuerySubject = BehaviorSubject.create("")
    private var currentStoreState: StoreState.Valid? = null
    private var invite: GuildInvite? = null
    private var isGeneratingInvite = false
    private var sentInvites: Map<String, Set<Long>> = emptyMap()
    private var targetChannel: Channel? = null

    init {
        storeStateObservable.computationLatest().ui(this).appSubscribe(GuildInviteShareSheetViewModel::class.java) {
            val valid = it as? StoreState.Valid ?: return@appSubscribe
            currentStoreState = valid
            targetChannel = targetChannelSelector.getTargetChannel(valid.invitableChannels, channelId)
            if (invite == null) {
                val stageCode = channelId?.let(valid.guildStageInstances::get)?.inviteCode
                val staticCode = stageCode ?: valid.guild.vanityUrlCode?.takeIf { channelId == null }
                if (staticCode != null) {
                    invite = GuildInvite.createFromModelInvite(ModelInvite.createForStaticUrl(staticCode, valid.guild.toApiGuild()))
                } else {
                    targetChannel?.let(::generateInviteForChannel)
                }
            }
            publish(valid, searchQuerySubject.value.orEmpty())
        }
        searchQuerySubject.distinctUntilChanged().ui(this).appSubscribe(GuildInviteShareSheetViewModel::class.java) { query ->
            currentStoreState?.let { publish(it, query) }
        }
    }

    fun onSearchTextChanged(searchQuery: String) = searchQuerySubject.onNext(searchQuery)

    fun updateInvite(invite: GuildInvite) {
        this.invite = invite
        currentStoreState?.let { publish(it, searchQuerySubject.value.orEmpty()) }
    }

    fun sendInvite(item: InviteSuggestionItemV2) {
        when (item) {
            is InviteSuggestionItemV2.ChannelItem -> sendInviteToChannel(item.channel.id)
            is InviteSuggestionItemV2.UserItem -> RestAPI.api
                .userCreateChannel(RestAPIParams.CreateChannel(item.user.id))
                .restSubscribeOn(false)
                .ui(this)
                .appSubscribe(GuildInviteShareSheetViewModel::class.java) { sendInviteToChannel(it.id) }
            InviteSuggestionItemV2.SearchNoResultsItem -> Unit
        }
    }

    private fun generateInviteForChannel(channel: Channel) {
        if (isGeneratingInvite) return
        isGeneratingInvite = true
        storeInviteSettings.generateInvite(channel.id)
            .ui(this)
            .appSubscribe(
                GuildInviteShareSheetViewModel::class.java,
                errorHandler = {
                    isGeneratingInvite = false
                    val state = currentStoreState ?: return@appSubscribe
                    state.guild.vanityUrlCode?.let { code ->
                        updateInvite(GuildInvite.createFromModelInvite(ModelInvite.createForStaticUrl(code, state.guild.toApiGuild())))
                    }
                },
            ) {
                isGeneratingInvite = false
                updateInvite(GuildInvite.createFromModelInvite(it))
            }
    }

    private fun sendInviteToChannel(channelId: Long) {
        val state = currentStoreState ?: return
        val link = invite?.toLink() ?: return
        storeMessages.sendMessage(channelId, state.me, link)
            .ui(this)
            .appSubscribe(GuildInviteShareSheetViewModel::class.java) { result ->
                if (result !is MessageResult.Success) return@appSubscribe
                sentInvites = sentInvites + (link to (sentInvites[link].orEmpty() + result.message.channelId))
                publish(state, searchQuerySubject.value.orEmpty())
            }
    }

    private fun publish(state: StoreState.Valid, query: String) {
        val link = invite?.toLink().orEmpty()
        val sent = sentInvites[link].orEmpty()
        val dmChannels = state.dms.mapNotNull { channel ->
            channel.getDMRecipient()?.id?.let { it to channel.id }
        }.toMap()
        val suggestions = state.inviteSuggestions.filter {
            query.isBlank() || when (it) {
                is InviteSuggestion.ChannelItem -> it.channel.getDisplayName().contains(query, true)
                is InviteSuggestion.UserSuggestion -> it.user.username.contains(query, true)
            }
        }
        val items = if (suggestions.isEmpty() && query.isNotBlank()) {
            listOf(InviteSuggestionItemV2.SearchNoResultsItem)
        } else {
            suggestions.map {
                when (it) {
                    is InviteSuggestion.ChannelItem -> InviteSuggestionItemV2.ChannelItem(it.channel, it.channel.id in sent, query)
                    is InviteSuggestion.UserSuggestion -> InviteSuggestionItemV2.UserItem(it.user, dmChannels[it.user.id] in sent, query)
                }
            }
        }
        updateViewState(
            ViewState(
                invite,
                state.inviteSettings,
                items,
                invite?.channelId?.let(state.invitableChannels::get),
                query,
                sentInvites,
                invite?.isStaticInvite != true,
                guildId,
            ),
        )
    }

    companion object {
        fun observeStoreState(
            guildId: Long,
            inviteSuggestionsManager: InviteSuggestionsManager = InviteSuggestionsManager(),
            storeInviteSettings: StoreInviteSettings = InviteSettingsSource.store,
            storeUser: StoreUser = StoreStream.getUsers(),
            storeChannels: StoreChannels = StoreStream.getChannels(),
            storeGuilds: StoreGuilds = StoreStream.getGuilds(),
            storeStageInstances: StoreStageInstances = StoreStream.getStageInstances(),
        ): Observable<StoreState> = Observable.combineLatest(
            storeInviteSettings.getInviteSettings(),
            storeInviteSettings.getInvitableChannels(guildId),
            storeUser.observeMe(),
            storeChannels.observeDMs(),
            storeGuilds.observeGuild(guildId),
            inviteSuggestionsManager.observeInviteSuggestions(),
            storeStageInstances.observeStageInstancesForGuild(guildId),
        ) { settings, channels, me, dms, guild, suggestions, stages ->
            guild?.let { StoreState.Valid(settings, channels, me, dms, it, suggestions, stages) }
                ?: StoreState.Invalid
        }
    }
}
