package com.discord.widgets.guildscheduledevent

import android.content.res.Resources
import com.discord.models.domain.ModelInvite
import com.discord.models.experiments.domain.Experiment
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuildScheduledEvents
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreInviteSettings
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreStageInstances
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.app.AppViewModel
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.widgets.guilds.invite.InviteGenerator
import com.discord.widgets.guilds.invite.InviteSettingsSource
import com.discord.widgets.guilds.invite.WidgetInviteModel
import rx.Observable

class WidgetGuildScheduledEventCreateSuccessViewModel(
    val channelId: Long?,
    val guildId: Long,
    val guildScheduledEventId: Long,
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val storeInviteSettings: StoreInviteSettings = InviteSettingsSource.store,
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storeStageInstances: StoreStageInstances = StoreStream.getStageInstances(),
    private val storeGuildScheduledEvents: StoreGuildScheduledEvents = StoreStream.getGuildScheduledEvents(),
    private val storeInstantInvites: StoreInstantInvites = StoreStream.getInstantInvites(),
    private val inviteGenerator: InviteGenerator = InviteGenerator(storeInviteSettings),
    val resources: Resources,
) : AppViewModel<WidgetGuildScheduledEventCreateSuccessViewModel.ViewState>(ViewState.Uninitialized) {

    sealed class ViewState {
        object Uninitialized : ViewState()
        data class Loaded(
            val widgetInviteModel: WidgetInviteModel,
            val showInviteSettings: Boolean = true,
        ) : ViewState()
    }

    init {
        observeInvite()
            .doOnNext(::generateInviteLinkFromViewState)
            .computationLatest()
            .ui(this)
            .appSubscribe(WidgetGuildScheduledEventCreateSuccessViewModel::class.java, onNext = ::updateViewState)
    }

    fun generateInviteLink(channelId: Long?) {
        storeChannels.observeDefaultChannel(guildId).first().appSubscribe(
            WidgetGuildScheduledEventCreateSuccessViewModel::class.java,
        ) { defaultChannel ->
            (channelId ?: defaultChannel?.id)?.let { inviteGenerator.generateForAppComponent(this, it) }
        }
    }

    fun getDefaultInviteExperiment(): Experiment? =
        storeInviteSettings.getInviteGuildExperiment(guildId, true)

    fun updateInviteSettings(settings: ModelInvite.Settings) =
        storeInviteSettings.setInviteSettings(settings)

    override fun onCleared() {
        inviteGenerator.close()
        super.onCleared()
    }

    private data class InviteInputs(
        val settings: ModelInvite.Settings,
        val channels: Map<Long, com.discord.api.channel.Channel>,
        val generation: InviteGenerator.InviteGenerationState,
        val me: com.discord.models.user.MeUser,
        val dms: List<com.discord.api.channel.Channel>,
        val guild: com.discord.models.guild.Guild?,
        val stages: Map<Long, com.discord.api.stageinstance.StageInstance>,
        val event: com.discord.api.guildscheduledevent.GuildScheduledEvent?,
    )

    private fun observeInvite(): Observable<ViewState.Loaded> {
        val inputs = Observable.combineLatest(
        storeInviteSettings.getInviteSettings(),
        storeInviteSettings.getInvitableChannels(guildId),
        inviteGenerator.generationState,
        storeUser.observeMe(),
        storeChannels.observeDMs(),
        storeGuilds.observeGuild(guildId),
        storeStageInstances.observeStageInstancesForGuild(guildId),
        storeGuildScheduledEvents.observeGuildScheduledEvent(guildScheduledEventId, guildId),
    ) { settings, channels, generation, me, dms, guild, stages, event ->
            InviteInputs(settings, channels, generation, me, dms, guild, stages, event)
        }
        return Observable.combineLatest(
            inputs,
            storeInstantInvites.observeInvite(null),
            storeChannels.observeDefaultChannel(guildId),
        ) { state, storedInvite, defaultChannel ->
            val invite = (storedInvite as? StoreInstantInvites.InviteState.Resolved)?.invite
            val model = WidgetInviteModel.create(
                state.settings,
                state.channels,
                state.generation,
                state.event?.channelId ?: defaultChannel?.id,
                state.me,
                state.dms,
                state.guild,
                state.stages,
                state.event,
                invite,
            )
            ViewState.Loaded(model, model.invite == null || (!model.invite.isStatic() && !model.isInviteFromStore))
        }.distinctUntilChanged()
    }

    private fun generateInviteLinkFromViewState(viewState: ViewState.Loaded) {
        val model = viewState.widgetInviteModel
        val target = model.targetChannel ?: return
        if (!model.isValidInvite && !model.isGeneratingInvite) {
            inviteGenerator.generateForAppComponent(this, target.id)
        }
    }
}
