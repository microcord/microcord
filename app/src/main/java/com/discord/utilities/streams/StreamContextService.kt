package com.discord.utilities.streams

import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.voice.state.VoiceState
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.stores.StoreApplicationStreamPreviews
import com.discord.stores.StoreApplicationStreamPreviews.StreamPreview
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreApplicationStreaming.ActiveApplicationStream
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StorePermissions
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.stores.StoreVoiceStates
import com.discord.utilities.permissions.PermissionUtils
import rx.Observable

/**
 * Builds a [StreamContext] for a streaming user by joining the eight stores that describe a stream.
 *
 * This is the only place that decides whether a viewer may join a stream, so the go-live button,
 * the voice tile overlay and the guild-list badge cannot disagree.
 *
 * Reference: `com.discord.utilities.streams.StreamContextService`.
 *
 * **Missing dependency.** `StoreApplicationStreamPreviews` exists in this project but the store
 * graph does not construct it, so [applicationStreamPreviewStore] defaults to `null` and
 * [StreamContext.preview] is then always `null` — thumbnails stay blank while everything else on
 * the context is live. Pass the real store (or publish it from `StoreStream`) to close that.
 */
class StreamContextService(
    private val applicationStreamingStore: StoreApplicationStreaming = StoreStream.getApplicationStreaming(),
    private val guildStore: StoreGuilds = StoreStream.getGuilds(),
    private val permissionsStore: StorePermissions = StoreStream.getPermissions(),
    private val userStore: StoreUser = StoreStream.getUsers(),
    private val voiceChannelSelectedStore: StoreVoiceChannelSelected = StoreStream.getVoiceChannelSelected(),
    private val voiceStateStore: StoreVoiceStates = StoreStream.getVoiceStates(),
    private val channelStore: StoreChannels = StoreStream.getChannels(),
    private val applicationStreamPreviewStore: StoreApplicationStreamPreviews? = null,
) {

    /** The context for the stream the viewer is currently watching, or `null` when not watching. */
    fun getForActiveStream(): Observable<StreamContext?> =
        applicationStreamingStore.observeActiveStream().switchMap { activeStream ->
            if (activeStream == null) Observable.just(null)
            else getForUser(activeStream.stream.ownerId, false)
        }

    /**
     * A context per currently streaming user, keyed by user id.
     *
     * Previews are never included here — this feeds list badges, which would otherwise start a
     * thumbnail fetch for every stream in the guild.
     */
    fun getForAllStreamingUsers(): Observable<Map<Long, StreamContext>> =
        applicationStreamingStore.observeStreamsByUser()
            .map { it.values }
            .switchMap { allUserStreams ->
                if (allUserStreams.isEmpty()) {
                    Observable.just(emptyMap())
                } else {
                    Observable.combineLatest(
                        allUserStreams.map { getForUser(it.ownerId, false) },
                    ) { contexts ->
                        contexts.filterIsInstance<StreamContext>().associateBy { it.user.id }
                    }
                }
            }

    /**
     * The context for [userId]'s stream, or `null` when they are not streaming.
     *
     * Emits `null` rather than completing so a surface bound to one user keeps working when the
     * stream starts and stops.
     *
     * [includePreview] subscribes to the thumbnail stream as well; only the surfaces that actually
     * render a thumbnail should pass `true`.
     */
    fun getForUser(userId: Long, includePreview: Boolean): Observable<StreamContext?> =
        applicationStreamingStore.observeStreamsForUser(userId).switchMap { stream ->
            if (stream == null) return@switchMap Observable.just(null)

            val guildId = when (stream) {
                is ModelApplicationStream.GuildStream -> stream.guildId
                is ModelApplicationStream.CallStream -> 0L
            }

            val nickname = guildStore.observeComputed(guildId)
                .map { members -> members[userId]?.nick }
                .distinctUntilChanged()

            val head: Observable<Inputs> = Observable.combineLatest(
                guildStore.observeGuild(guildId),
                previewObservable(stream, includePreview),
                permissionsStore.observePermissionsForChannel(stream.channelId),
                userStore.observeUser(userId),
                userStore.observeMe(),
                nickname,
                voiceStateStore.observe(guildId, stream.channelId),
                channelStore.observeChannel(stream.channelId),
                voiceChannelSelectedStore.observeSelectedVoiceChannelId(),
            ) { guild, preview, channelPermissions, user, me, userNickname, voiceStates, channel, selectedVoiceChannelId ->
                Inputs(
                    guild, preview, channelPermissions, user, me,
                    userNickname, voiceStates, channel, selectedVoiceChannelId,
                )
            }

            // Split from the combine above because RxJava 1 tops out at nine sources; the
            // reference has a ten-argument overload and combines all of them at once.
            Observable.combineLatest(
                head,
                applicationStreamingStore.observeActiveStream(),
            ) { inputs, activeStream -> build(stream, inputs, activeStream) }
        }

    private fun previewObservable(
        stream: ModelApplicationStream,
        includePreview: Boolean,
    ): Observable<StreamPreview?> =
        if (includePreview && applicationStreamPreviewStore != null) {
            applicationStreamPreviewStore.observeStreamPreview(stream)
        } else {
            Observable.just(null)
        }

    private fun build(
        stream: ModelApplicationStream,
        inputs: Inputs,
        activeStream: ActiveApplicationStream?,
    ): StreamContext? {
        // A guild stream whose guild is not loaded yet, or a streamer we know nothing about, is
        // not renderable — the caller sees null and hides its surface.
        if (stream is ModelApplicationStream.GuildStream && inputs.guild == null) return null
        val user = inputs.user ?: return null

        val userLimit = inputs.channel?.userLimit ?: 0
        val isChannelFull = userLimit > 0 && inputs.voiceStates.size >= userLimit
        // Already in the channel: the limit does not apply, and neither does CONNECT.
        val isInChannel = inputs.channel != null && inputs.channel.id == inputs.selectedVoiceChannelId

        val canConnect = PermissionUtils.can(Permission.CONNECT, inputs.channelPermissions)
        // MANAGE_CHANNELS, not MOVE_MEMBERS: a channel manager can raise the limit, so a full
        // channel does not block them.
        val canManageChannel = PermissionUtils.can(Permission.MANAGE_CHANNELS, inputs.channelPermissions)

        val joinability = when {
            // A call stream is always reachable — being in the call is the permission.
            stream is ModelApplicationStream.GuildStream && !canConnect && !isInChannel ->
                StreamContext.Joinability.MISSING_PERMISSIONS
            isChannelFull && !canManageChannel -> StreamContext.Joinability.VOICE_CHANNEL_FULL
            else -> StreamContext.Joinability.CAN_CONNECT
        }

        return StreamContext(
            stream = stream,
            guild = inputs.guild,
            preview = inputs.preview,
            joinability = joinability,
            user = user,
            userNickname = inputs.userNickname,
            isCurrentUserParticipating = activeStream != null &&
                activeStream.state.isStreamActive() &&
                activeStream.stream == stream,
            isSelfStream = user.id == inputs.me.id,
        )
    }

    /** The nine sources that do not depend on the active stream, carried as one value. */
    private data class Inputs(
        val guild: Guild?,
        val preview: StreamPreview?,
        val channelPermissions: Long?,
        val user: User?,
        val me: MeUser,
        val userNickname: String?,
        val voiceStates: Map<Long, VoiceState>,
        val channel: Channel?,
        val selectedVoiceChannelId: Long,
    )
}
