package com.discord.widgets.guilds.invite

import android.content.res.Resources
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.app.AppViewModel
import com.discord.models.domain.ModelInvite
import com.discord.models.experiments.domain.Experiment
import com.discord.models.invite.InviteUtils
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreMessages
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * Drives every full-screen invite-share surface: [WidgetGuildInviteShare],
 * [WidgetGuildInviteShareCompact] and [WidgetGuildInviteShareEmptySuggestions].
 *
 * Three things are worth knowing before changing it.
 *
 * **The link generates itself.** Every emission runs through [generateInviteLinkFromViewState]: if
 * the model reports no valid invite, nothing in flight, and a target channel, a new link is
 * requested. Changing the target channel or the invite settings therefore needs no explicit call —
 * invalidating the model is enough.
 *
 * **"Sent" is per link, not per recipient.** [sentInvites] is keyed by the *invite link* so that
 * regenerating the link (new expiry, new channel) resets every row's Sent badge, which is correct:
 * the recipients were sent the old link.
 *
 * **[refreshUi] exists for the settings sheet.** Collapsing the sheet has to re-render even though
 * no store changed, so a plain `Unit` subject is folded into the combine.
 *
 * @param eventId scopes the shared link to a scheduled event (`?event=`).
 * @param inviteStoreKey when set, the invite is taken from [StoreInstantInvites] instead of being
 *   generated — the share sheet opened from an existing invite card.
 * @param subscribeOnInit `false` in tests so the state can be driven by hand.
 */
class WidgetGuildInviteShareViewModel(
    private val guildId: Long,
    private val eventId: Long?,
    private val inviteStoreKey: String?,
    private val resources: Resources,
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val inviteSettings: InviteSettingsSource = InviteSettingsSource.instance,
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeStageInstances: StoreStageInstances = StoreStream.getStageInstances(),
    private val storeGuildScheduledEvents: StoreGuildScheduledEvents =
        StoreStream.getGuildScheduledEvents(),
    private val storeInstantInvites: StoreInstantInvites = StoreStream.getInstantInvites(),
    private val storeMessages: StoreMessages = StoreStream.getMessages(),
    private val inviteGenerator: InviteGenerator = InviteGenerator(),
    private val inviteSuggestionsManager: InviteSuggestionsManager = InviteSuggestionsManager(),
    subscribeOnInit: Boolean = true,
) : AppViewModel<WidgetGuildInviteShareViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class ViewState {
        object Uninitialized : ViewState()

        data class Loaded(
            val widgetInviteModel: WidgetInviteModel,
            val inviteSuggestionItems: List<InviteSuggestionItem>,
            val searchQuery: String = "",
            val sentInvites: Map<String, Set<Long>>,
            /**
             * Hidden for an invite that came from the store and for the guild's static link:
             * neither can be regenerated, so an expiry picker would do nothing.
             */
            val showInviteSettings: Boolean = true,
        ) : ViewState()
    }

    private val sentInvitesSubject: BehaviorSubject<Map<String, Set<Long>>> =
        BehaviorSubject.create(emptyMap())
    private val filterPublisher: BehaviorSubject<String> = BehaviorSubject.create("")
    private val selectedChannelSubject: BehaviorSubject<Long?> =
        BehaviorSubject.create(null as Long?)
    private val refreshUiSubject: BehaviorSubject<Unit> = BehaviorSubject.create(Unit)

    init {
        if (subscribeOnInit) {
            observeViewStateFromStores(guildId, eventId, inviteStoreKey)
                .doOnNext(::generateInviteLinkFromViewState)
                .computationBuffered()
                .ui(this)
                .appSubscribe(WidgetGuildInviteShareViewModel::class.java) { updateViewState(it) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        inviteGenerator.close()
    }

    // region public API

    fun selectChannel(channelId: Long) = selectedChannelSubject.onNext(channelId)

    fun generateInviteLink(channelId: Long) = inviteGenerator.generateForAppComponent(this, channelId)

    fun updateInviteSettings(settings: ModelInvite.Settings) =
        inviteSettings.setInviteSettings(settings)

    fun updateSearchQuery(searchQuery: String) = filterPublisher.onNext(searchQuery)

    fun refreshUi() = refreshUiSubject.onNext(Unit)

    fun getDefaultInviteExperiment(): Experiment? =
        inviteSettings.getInviteGuildExperiment(guildId, trackExposure = true)

    /**
     * Sends the current link into [channelId] and marks the row Sent on success only — a failed
     * send leaves the "Invite" button in place so it can be retried.
     */
    fun sendInviteToChannel(
        channelId: Long,
        viewState: ViewState.Loaded,
        @Suppress("UNUSED_PARAMETER") invite: ModelInvite?,
    ) {
        val inviteLink = viewState.widgetInviteModel.invite.toInviteLink()
        storeMessages
            .sendMessage(channelId, viewState.widgetInviteModel.me, inviteLink)
            .ui(this)
            .appSubscribe(WidgetGuildInviteShareViewModel::class.java) { result ->
                // Reference: StoreAnalytics.inviteSent(invite, message, "Guild Create Invite
                // Suggestion"). No StoreAnalytics is published on StoreStream in this project.
                if (result !is MessageResult.Success) return@appSubscribe
                val alreadySent = viewState.sentInvites[inviteLink].orEmpty()
                updateSentInvites(
                    viewState.sentInvites +
                        (inviteLink to alreadySent + result.message.channelId),
                )
            }
    }

    /** Resolves (or opens) the DM with [userId] first, then hands off to [sendInviteToChannel]. */
    fun sendInviteToUser(userId: Long, viewState: ViewState.Loaded, invite: ModelInvite?) {
        RestAPI.api
            .userCreateChannel(RestAPIParams.CreateChannel(userId))
            .restSubscribeOn(false)
            .ui(this)
            .appSubscribe(WidgetGuildInviteShareViewModel::class.java) { channel ->
                sendInviteToChannel(channel.id, viewState, invite)
            }
    }

    // endregion

    // region state composition

    internal fun observeViewStateFromStores(
        guildId: Long,
        eventId: Long?,
        inviteStoreKey: String?,
    ): Observable<ViewState.Loaded> = Observable.combineLatest(
        observeWidgetInviteModel(guildId, eventId, inviteStoreKey),
        inviteSuggestionsManager.observeInviteSuggestions(),
        filterPublisher,
        sentInvitesSubject,
        refreshUiSubject,
    ) { widgetInviteModel, inviteSuggestions, filter, sentInvites, _ ->
        val inviteLink = widgetInviteModel.invite.toInviteLink()
        val alreadySent = sentInvites[inviteLink].orEmpty()
        val filtered = inviteSuggestions.filterBy(filter)

        // A DM's recipient is what the Sent badge is keyed on for a user row, so the row needs the
        // recipient -> channel mapping the suggestion itself does not carry.
        val dmChannelIdsByRecipient = widgetInviteModel.dms
            .associateBy({ it.getDMRecipient()?.id }, { it.id })

        val items = when {
            filtered.isEmpty() && filter.isNotBlank() ->
                listOf(InviteSuggestionItem.SearchNoResultsItem)
            else -> filtered.map { suggestion ->
                when (suggestion) {
                    is InviteSuggestion.ChannelItem -> InviteSuggestionItem.ChannelItem(
                        suggestion.channel,
                        suggestion.channel.id in alreadySent,
                    )
                    is InviteSuggestion.UserSuggestion -> InviteSuggestionItem.UserItem(
                        suggestion.user,
                        dmChannelIdsByRecipient[suggestion.user.id] in alreadySent,
                    )
                }
            }
        }

        val invite = widgetInviteModel.invite
        val showInviteSettings =
            invite == null || (!invite.isStatic() && !widgetInviteModel.isInviteFromStore)

        ViewState.Loaded(
            widgetInviteModel = widgetInviteModel,
            inviteSuggestionItems = items,
            searchQuery = filter,
            sentInvites = sentInvites,
            showInviteSettings = showInviteSettings,
        )
    }

    private fun observeWidgetInviteModel(
        guildId: Long,
        eventId: Long?,
        inviteStoreKey: String?,
    ): Observable<WidgetInviteModel> = Observable.combineLatest(
        inviteSettings.observeInviteSettings(),
        inviteSettings.getInvitableChannels(guildId),
        inviteGenerator.generationState,
        selectedChannelSubject,
        storeUser.observeMe(),
        storeChannels.observeDMs(),
        storeGuilds.observeGuild(guildId),
        storeStageInstances.observeStageInstancesForGuild(guildId),
        observeExistingInvite(eventId, guildId, inviteStoreKey),
    ) { settings, invitableChannels, generationState, selectedChannelId, me, dms, guild, stageInstances, existing ->
        WidgetInviteModel.create(
            settings = settings,
            invitableChannels = invitableChannels,
            inviteGenerationState = generationState,
            selectedChannelId = selectedChannelId,
            me = me,
            dms = dms,
            guild = guild,
            guildStageInstances = stageInstances,
            guildScheduledEvent = existing.scheduledEvent,
            existingInviteFromStore = existing.invite,
        )
    }.computationLatest().distinctUntilChanged()

    private data class ExistingInvite(
        val scheduledEvent: GuildScheduledEvent?,
        val invite: ModelInvite?,
    )

    /**
     * The scheduled event to hang off the link, and the already-resolved invite when the screen was
     * opened from an invite card. Only [StoreInstantInvites.InviteState.Resolved] contributes an
     * invite; every other state falls back to generating one, so a failed resolve degrades to the
     * normal flow rather than to an empty screen.
     */
    private fun observeExistingInvite(
        eventId: Long?,
        guildId: Long,
        inviteStoreKey: String?,
    ): Observable<ExistingInvite> = Observable.combineLatest(
        storeGuildScheduledEvents.observeGuildScheduledEvent(eventId, guildId),
        storeInstantInvites.observeInvite(inviteStoreKey),
    ) { event, inviteState ->
        ExistingInvite(
            scheduledEvent = event,
            invite = (inviteState as? StoreInstantInvites.InviteState.Resolved)
                ?.invite
                ?.takeIf { inviteStoreKey != null },
        )
    }

    private fun List<InviteSuggestion>.filterBy(filter: String): List<InviteSuggestion> {
        if (filter.isBlank()) return this
        return filter { suggestion ->
            when (suggestion) {
                is InviteSuggestion.ChannelItem ->
                    suggestion.channel.getDisplayName().contains(filter, ignoreCase = true)
                is InviteSuggestion.UserSuggestion ->
                    suggestion.user.username.contains(filter, ignoreCase = true)
            }
        }
    }

    private fun generateInviteLinkFromViewState(viewState: ViewState.Loaded) {
        val model = viewState.widgetInviteModel
        val targetChannel: Channel = model.targetChannel ?: return
        if (model.isValidInvite || model.isGeneratingInvite) return
        inviteGenerator.generateForAppComponent(this, targetChannel.id)
    }

    private fun updateSentInvites(sentInvites: Map<String, Set<Long>>) =
        sentInvitesSubject.onNext(sentInvites)

    private fun ModelInvite?.toInviteLink(): String =
        this?.toLink(resources, InviteUtils.HOST_INVITE) ?: InviteUtils.HOST_INVITE

    // endregion
}
