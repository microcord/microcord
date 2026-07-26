package com.discord.widgets.user.usersheet

import com.discord.api.channel.Channel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.api.role.GuildRole
import com.discord.api.user.UserProfile
import com.discord.api.voice.state.StageRequestToSpeakState
import com.discord.api.voice.state.VoiceState
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreMediaSettings
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserNotes
import com.discord.stores.StoreUserPresence
import com.discord.stores.StoreUserProfile
import com.discord.stores.StoreUserRelationships
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceStates
import com.discord.utilities.rx.leadingEdgeThrottle
import com.discord.utilities.streams.StreamContext
import com.discord.widgets.user.presence.ModelRichPresence
import java.util.concurrent.TimeUnit
import rx.Observable

/**
 * Assembles the user sheet's [WidgetUserSheetViewModel.StoreState].
 *
 * Split out from the view model because the combination is the hard part: which guild, channel and
 * voice channel apply is not known until the user and the opening context have resolved, so the
 * graph is two-stage. [BootstrapData] is the first stage; everything else fans out from it.
 *
 * A missing user is not an error here — after a grace period the stream emits `null` and the view
 * model turns that into "user not found". Waiting matters because the sheet is routinely opened for
 * somebody the gateway has not sent yet.
 */
object WidgetUserSheetViewModelStoreState {

    /** How long to wait for an unknown user to arrive before declaring them missing. */
    private const val USER_RESOLUTION_TIMEOUT_SECONDS = 5L

    /** Coalesces the fan-out; a busy guild would otherwise re-render the sheet continuously. */
    private const val THROTTLE_MILLIS = 250L

    /** Playback volume for a user we hold no explicit setting for. */
    private const val DEFAULT_OUTPUT_VOLUME = 100f

    /**
     * @param channelId the channel the sheet was opened from, or `null`.
     * @param guildId the guild to scope nickname, roles and moderation to. When `null` it is taken
     *   from [channelId]'s channel, so a sheet opened from a guild channel is still guild-scoped.
     * @param storeVoiceChannelSelected my current voice channel. `StoreStream` publishes no
     *   accessor, so the default is `null` and the sheet reports me as not connected — which hides
     *   the per-user voice settings.
     * @param storeVoiceStates voice states per channel. Same situation; the default reports none,
     *   which hides the server mute/deafen/move actions.
     * @param storeMediaSettings my local audio settings. Same situation; the default reports the
     *   user unmuted at full volume.
     * @param streamContextService resolves the user's Go Live stream. The reference passes
     *   `StreamContextService`, which is not reconstructed; the default reports no stream.
     * @param observeCanDisableCommunication the timeout feature gate. Reference:
     *   `GuildCommunicationDisabledGuildsFeatureFlag`; not reconstructed, so timeouts are off.
     */
    @JvmStatic
    @JvmOverloads
    fun observeStoreState(
        userId: Long,
        channelId: Long?,
        guildId: Long?,
        storeUser: StoreUser = StoreStream.getUsers(),
        storeChannels: StoreChannels = StoreStream.getChannels(),
        storeVoiceChannelSelected: StoreVoiceChannelSelected? = null,
        storeUserProfile: StoreUserProfile = StoreStream.getUserProfiles(),
        storeUserRelationships: StoreUserRelationships = StoreStream.getUserRelationships(),
        storeVoiceStates: StoreVoiceStates? = null,
        storeGuilds: StoreGuilds = StoreStream.getGuilds(),
        storeMediaSettings: StoreMediaSettings? = null,
        storeUserPresence: StoreUserPresence = StoreStream.getPresences(),
        storePermissions: StorePermissions = StoreStream.getPermissions(),
        storeUserNotes: StoreUserNotes = StoreStream.getUserNotes(),
        streamContextService: (Long) -> Observable<StreamContext?> = { Observable.just(null) },
        observeCanDisableCommunication: (Long) -> Observable<Boolean> = { Observable.just(false) },
    ): Observable<WidgetUserSheetViewModel.StoreState?> {
        val channelObservable: Observable<Channel?> =
            channelId?.let { storeChannels.observeChannel(it) } ?: Observable.just(null)
        val selectedVoiceChannelObservable: Observable<Channel?> =
            storeVoiceChannelSelected?.observeSelectedChannel() ?: Observable.just(null)

        return Observable.combineLatest(
            storeUser.observeUser(userId),
            storeUser.observeMe(),
            channelObservable,
            selectedVoiceChannelObservable,
            ::BootstrapData,
        )
            .switchMap { bootstrap ->
                val user = bootstrap.user
                    ?: return@switchMap Observable
                        .timer(USER_RESOLUTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .map<WidgetUserSheetViewModel.StoreState?> { null }

                // A sheet opened from a guild channel is guild-scoped even when the caller did not
                // say which guild.
                val scopedGuildId = guildId ?: bootstrap.channel?.guildId?.takeIf { it != 0L }

                val computedMembers: Observable<Map<Long, GuildMember>> = scopedGuildId
                    ?.let { storeGuilds.observeComputed(it, listOf(user.id, bootstrap.me.id)) }
                    ?: Observable.just(emptyMap())
                val guildRoles: Observable<Map<Long, GuildRole>> = scopedGuildId
                    ?.let { storeGuilds.observeRoles(it) }
                    ?: Observable.just(emptyMap())
                val guild: Observable<Guild?> = scopedGuildId
                    ?.let { storeGuilds.observeGuild(it) }
                    ?: Observable.just(null)
                val mySelectedVoiceChannelVoiceStates = voiceStatesFor(
                    storeVoiceStates,
                    bootstrap.selectedVoiceChannel,
                )
                val currentChannelVoiceStates = voiceStatesFor(storeVoiceStates, bootstrap.channel)
                val permissions: Observable<Long?> = bootstrap.channel
                    ?.let { storePermissions.observePermissionsForChannel(it.id) }
                    ?: Observable.just(null)
                // `map` widens the store's non-null Observable to the nullable one this state uses;
                // RxJava 1's Observable is invariant, so a plain assignment does not compile.
                val voiceConfig: Observable<StoreMediaSettings.VoiceConfiguration?> =
                    storeMediaSettings?.getVoiceConfig()
                        ?.map<StoreMediaSettings.VoiceConfiguration?> { it }
                        ?: Observable.just(null)
                val richPresence = ModelRichPresence.get(user.id, storeUserPresence)
                val canDisableCommunication: Observable<Boolean> = scopedGuildId
                    ?.let(observeCanDisableCommunication)
                    ?: Observable.just(false)

                // A stage the *caller* is in; the stage actions section is about my membership of
                // it, not the target's.
                val stageChannel = bootstrap.selectedVoiceChannel
                    ?.takeIf { it.isStageVoiceChannel() }

                combineInputs(
                    computedMembers,
                    guildRoles,
                    mySelectedVoiceChannelVoiceStates,
                    currentChannelVoiceStates,
                    voiceConfig,
                    richPresence,
                    guild,
                    permissions,
                    streamContextService(user.id),
                    storeUserProfile.observeUserProfile(user.id),
                    storeUserRelationships.observe(user.id),
                    storeUserNotes.observeUserNote(user.id),
                    canDisableCommunication,
                ) { inputs ->
                    inputs.toStoreState(user, bootstrap.me, bootstrap.channel, stageChannel)
                }
            }
            .leadingEdgeThrottle(THROTTLE_MILLIS, TimeUnit.MILLISECONDS)
            .distinctUntilChanged()
    }

    private fun voiceStatesFor(
        storeVoiceStates: StoreVoiceStates?,
        channel: Channel?,
    ): Observable<Map<Long, VoiceState>> =
        if (storeVoiceStates == null || channel == null) {
            Observable.just(emptyMap())
        } else {
            storeVoiceStates.observe(channel.guildId, channel.id)
        }

    /**
     * The four values every other observation depends on.
     *
     * Named rather than a tuple because the second stage reads all four in different places, and a
     * `Pair<Pair<…>>` at this arity is unreadable.
     */
    private data class BootstrapData(
        val user: User?,
        val me: MeUser,
        val channel: Channel?,
        val selectedVoiceChannel: Channel?,
    )

    /**
     * The thirteen second-stage sources, carried as one value.
     *
     * RxJava 1 stops providing typed `combineLatest` overloads well below this arity, and the
     * reference reaches for its own `ObservableCombineLatestOverloadsKt` for the same reason. This
     * keeps the types instead of degrading to `Array<Any?>`.
     */
    private class Inputs(
        val computedMembers: Map<Long, GuildMember>,
        val guildRoles: Map<Long, GuildRole>,
        val mySelectedVoiceChannelVoiceStates: Map<Long, VoiceState>,
        val currentChannelVoiceStates: Map<Long, VoiceState>,
        val voiceConfig: StoreMediaSettings.VoiceConfiguration?,
        val richPresence: ModelRichPresence?,
        val guild: Guild?,
        val permissions: Long?,
        val streamContext: StreamContext?,
        val userProfile: UserProfile,
        val userRelationshipType: Int?,
        val userNote: StoreUserNotes.UserNoteState,
        val canDisableCommunication: Boolean,
    )

    private fun Inputs.toStoreState(
        user: User,
        me: MeUser,
        channel: Channel?,
        stageChannel: Channel?,
    ) = WidgetUserSheetViewModel.StoreState(
        user = user,
        me = me,
        channel = channel,
        computedMembers = computedMembers,
        guildRoles = guildRoles,
        mySelectedVoiceChannelVoiceStates = mySelectedVoiceChannelVoiceStates,
        currentChannelVoiceStates = currentChannelVoiceStates,
        muted = voiceConfig?.mutedUsers?.get(user.id) == true,
        selfMuted = voiceConfig?.isSelfMuted() == true,
        selfDeafened = voiceConfig?.isSelfDeafened == true,
        outputVolume = voiceConfig?.userOutputVolumes?.get(user.id) ?: DEFAULT_OUTPUT_VOLUME,
        richPresence = richPresence,
        guild = guild,
        permissions = permissions,
        streamContext = streamContext,
        userProfile = userProfile,
        userRelationshipType = userRelationshipType,
        userNoteFetchState = userNote,
        stageChannel = stageChannel,
        // Reference: `StoreStageChannels.observeUserRequestToSpeakState`. That store does not
        // exist here, so nobody ever appears to be requesting to speak.
        userRequestToSpeakState = StageRequestToSpeakState.NONE,
        canDisableCommunication = canDisableCommunication,
    )

    /** Thirteen-source `combineLatest`, built from the four-source overload RxJava 1 does provide. */
    @Suppress("UNCHECKED_CAST", "LongParameterList")
    private fun <R> combineInputs(
        computedMembers: Observable<Map<Long, GuildMember>>,
        guildRoles: Observable<Map<Long, GuildRole>>,
        mySelectedVoiceChannelVoiceStates: Observable<Map<Long, VoiceState>>,
        currentChannelVoiceStates: Observable<Map<Long, VoiceState>>,
        voiceConfig: Observable<StoreMediaSettings.VoiceConfiguration?>,
        richPresence: Observable<ModelRichPresence?>,
        guild: Observable<Guild?>,
        permissions: Observable<Long?>,
        streamContext: Observable<StreamContext?>,
        userProfile: Observable<UserProfile>,
        userRelationshipType: Observable<Int?>,
        userNote: Observable<StoreUserNotes.UserNoteState>,
        canDisableCommunication: Observable<Boolean>,
        combine: (Inputs) -> R,
    ): Observable<R> = Observable.combineLatest(
        listOf<Observable<*>>(
            computedMembers,
            guildRoles,
            mySelectedVoiceChannelVoiceStates,
            currentChannelVoiceStates,
            voiceConfig,
            richPresence,
            guild,
            permissions,
            streamContext,
            userProfile,
            userRelationshipType,
            userNote,
            canDisableCommunication,
        ),
    ) { values ->
        combine(
            Inputs(
                computedMembers = values[0] as Map<Long, GuildMember>,
                guildRoles = values[1] as Map<Long, GuildRole>,
                mySelectedVoiceChannelVoiceStates = values[2] as Map<Long, VoiceState>,
                currentChannelVoiceStates = values[3] as Map<Long, VoiceState>,
                voiceConfig = values[4] as StoreMediaSettings.VoiceConfiguration?,
                richPresence = values[5] as ModelRichPresence?,
                guild = values[6] as Guild?,
                permissions = values[7] as Long?,
                streamContext = values[8] as StreamContext?,
                userProfile = values[9] as UserProfile,
                userRelationshipType = values[10] as Int?,
                userNote = values[11] as StoreUserNotes.UserNoteState,
                canDisableCommunication = values[12] as Boolean,
            ),
        )
    }
}
