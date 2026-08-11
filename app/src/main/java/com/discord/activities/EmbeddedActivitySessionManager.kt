package com.discord.activities

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.commands.ApplicationCommandData
import com.discord.api.embeddedactivities.EmbeddedActivityConfig
import com.discord.api.embeddedactivities.EmbeddedActivityDiscordConfig
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.api.embeddedactivities.EmbeddedActivityLaunchBody
import com.discord.api.embeddedactivities.EmbeddedActivityPlatform
import com.discord.api.embeddedactivities.EmbeddedActivityProxyTicketRequest
import com.discord.api.embeddedactivities.EmbeddedActivitySessionBody
import com.discord.api.embeddedactivities.EmbeddedActivityShelfApplication
import com.discord.api.permission.Permission
import com.discord.api.user.NsfwAllowance
import com.discord.stores.StoreEmbeddedActivities
import com.discord.stores.StoreStream
import com.discord.stores.StoreVoiceChannelSelected
import com.discord.restapi.EmbeddedActivitiesAPI
import com.discord.restapi.RestAPIParams
import com.discord.utilities.messagesend.NonceGenerator
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import rx.Observable

data class EmbeddedActivityLaunchRequest(
    val applicationId: Long,
    val channelId: Long? = null,
    val instanceId: String? = null,
    val customId: String? = null,
    val referrerId: String? = null,
)

data class EmbeddedActivitySession(
    val application: EmbeddedActivityShelfApplication,
    val config: EmbeddedActivityConfig,
    val instance: EmbeddedActivityInstance,
    val channel: Channel,
    val discordConfig: EmbeddedActivityDiscordConfig,
    val proxyTicket: String,
    val url: String,
) {
    override fun toString() =
        "EmbeddedActivitySession(application=${application.id}, instance=${instance.compositeInstanceId}, " +
            "channel=${channel.id}, proxyTicket=<redacted>, url=<redacted>)"
}

sealed class EmbeddedActivityLaunchException(message: String) : IllegalStateException(message) {
    class NoRestClient : EmbeddedActivityLaunchException("Activities are unavailable before login")
    class NoGatewaySession : EmbeddedActivityLaunchException("Gateway session is not ready")
    class InvalidChannel : EmbeddedActivityLaunchException("Select a text or voice channel first")
    class UnknownApplication : EmbeddedActivityLaunchException("Activity is not available in this channel")
    class UnsupportedPlatform : EmbeddedActivityLaunchException("Activity does not support Android")
    class AgeGated : EmbeddedActivityLaunchException("Activity is age restricted")
    class NitroRequired : EmbeddedActivityLaunchException("Activity requires Nitro")
    class MissingPermission : EmbeddedActivityLaunchException("Missing Use Activities permission")
    class VoiceJoinFailed : EmbeddedActivityLaunchException("Could not join the selected voice channel")
    class AfkChannel : EmbeddedActivityLaunchException("Activities cannot run in the AFK channel")
    class MissingConnect : EmbeddedActivityLaunchException("Missing Connect permission")
}

object EmbeddedActivitySessionManager {
    private const val CLIENT_VERSION = "340.12"
    private const val INSTANCE_TIMEOUT_SECONDS = 20L
    internal val rpcConfig = EmbeddedActivityDiscordConfig(
        apiEndpoint = "//discord.com/api",
        environment = "production",
        cdnHost = "cdn.discordapp.com",
    )
    private val nonceGenerator = NonceGenerator()

    fun prepare(request: EmbeddedActivityLaunchRequest): Observable<EmbeddedActivitySession> {
        val api = RestAPI.embeddedActivitiesApiOrNull
            ?: return Observable.error(EmbeddedActivityLaunchException.NoRestClient())
        val channel = resolveChannel(request.channelId)
            ?: return Observable.error(EmbeddedActivityLaunchException.InvalidChannel())
        val sessionId = StoreStream.getInteractions().getSessionId()
            ?: return Observable.error(EmbeddedActivityLaunchException.NoGatewaySession())
        val store = StoreStream.getEmbeddedActivities()

        return joinVoiceIfNeeded(channel).flatMap {
            store.fetchShelf(api, channel.guildId.takeIf { it > 0L })
        }.flatMap { shelf ->
            val application = shelf.applications.firstOrNull { it.id == request.applicationId }
                ?: return@flatMap Observable.error(EmbeddedActivityLaunchException.UnknownApplication())
            val config = shelf.activities.firstOrNull { it.applicationId == request.applicationId }
                ?: application.embeddedActivityConfig
                ?: return@flatMap Observable.error(EmbeddedActivityLaunchException.UnknownApplication())
            validate(channel, config)
            api.proxyTicket(request.applicationId, EmbeddedActivityProxyTicketRequest(channel.id)).flatMap { ticket ->
                start(api, request.applicationId, channel, sessionId).flatMap {
                    store.observeInstancesForChannel(channel.id)
                        .map { it[request.applicationId] }
                        .filter { it != null }
                        .map { checkNotNull(it) }
                        .take(1)
                        .timeout(INSTANCE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.map { activityInstance ->
                    EmbeddedActivitySession(
                        application,
                        config,
                        activityInstance,
                        channel,
                        rpcConfig,
                        ticket.ticket,
                        buildUrl(activityInstance, channel, ticket.ticket, request),
                    )
                }
            }
        }
    }

    fun leave(session: EmbeddedActivitySession): Observable<*> {
        val api = RestAPI.embeddedActivitiesApiOrNull ?: return Observable.empty<Any>()
        val sessionId = StoreStream.getInteractions().getSessionId() ?: return Observable.empty<Any>()
        return api.leave(
            session.application.id,
            session.instance.location.id,
            session.instance.instanceId,
            EmbeddedActivitySessionBody(sessionId),
        )
    }

    private fun resolveChannel(channelId: Long?): Channel? {
        if (channelId != null) return StoreStream.getChannels().getChannel(channelId)
        return StoreStream.getChannelsSelected().getSelectedChannel()
            ?: StoreStream.getVoiceChannelSelected().getSelectedVoiceChannel()
    }

    private fun joinVoiceIfNeeded(channel: Channel): Observable<Unit> {
        if (!channel.isGuildVocalChannel() ||
            StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId() == channel.id
        ) return Observable.just(Unit)
        return StoreStream.getVoiceChannelSelected().selectVoiceChannel(channel.id).flatMap { result ->
            if (result == StoreVoiceChannelSelected.JoinVoiceChannelResult.SUCCESS ||
                result == StoreVoiceChannelSelected.JoinVoiceChannelResult.ALREADY_CONNECTED
            ) Observable.just(Unit) else Observable.error(EmbeddedActivityLaunchException.VoiceJoinFailed())
        }
    }

    internal fun start(
        api: EmbeddedActivitiesAPI,
        applicationId: Long,
        channel: Channel,
        sessionId: String,
    ): Observable<*> {
        val direct = api.launch(
            channel.id,
            applicationId,
            EmbeddedActivityLaunchBody(sessionId, channel.guildId.takeIf { it > 0L }),
        )
        if (channel.isGuildVocalChannel()) return direct
        return api.commandIndex(applicationId).flatMap { index ->
            val command = index.applicationCommands.firstOrNull {
                it.type == EmbeddedActivityLauncher.PRIMARY_ENTRY_POINT_COMMAND_TYPE ||
                    it.handler == EmbeddedActivityLauncher.DISCORD_LAUNCH_ACTIVITY_HANDLER
            }
                ?: index.applicationCommands.firstOrNull { it.name == "launch" }
                ?: return@flatMap direct
            api.interaction(
                RestAPIParams.ApplicationCommand(
                    type = INTERACTION_APPLICATION_COMMAND,
                    channelId = channel.id.toString(),
                    applicationId = applicationId.toString(),
                    guildId = channel.guildId.takeIf { it > 0L }?.toString(),
                    data = ApplicationCommandData(
                        version = command.version,
                        guildId = command.guildId,
                        id = command.id.toString(),
                        name = command.name,
                        options = null,
                        attachments = null,
                        type = EmbeddedActivityLauncher.PRIMARY_ENTRY_POINT_COMMAND_TYPE,
                        applicationCommand = command,
                    ),
                    sessionId = sessionId,
                    nonce = nonceGenerator.nonce().toString(),
                    analyticsLocation = "activities_mini_shelf",
                    sectionName = "activities",
                    source = "TEXT",
                ),
            )
        }.onErrorResumeNext(direct)
    }

    private fun validate(channel: Channel, config: EmbeddedActivityConfig) {
        if (EmbeddedActivityPlatform.ANDROID !in config.supportedPlatforms) {
            throw EmbeddedActivityLaunchException.UnsupportedPlatform()
        }
        val me = StoreStream.getUsers().getMe()
        if (config.requiresAgeGate && me.nsfwAllowance != NsfwAllowance.ALLOWED) {
            throw EmbeddedActivityLaunchException.AgeGated()
        }
        if ((config.premiumTierRequirement ?: 0) > me.premiumTier.ordinal) {
            throw EmbeddedActivityLaunchException.NitroRequired()
        }
        if (channel.guildId <= 0L) return
        if (StoreStream.getGuilds().getGuild(channel.guildId)?.afkChannelId == channel.id) {
            throw EmbeddedActivityLaunchException.AfkChannel()
        }
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id]
        if (!PermissionUtils.can(Permission.USE_EMBEDDED_ACTIVITIES, permissions)) {
            throw EmbeddedActivityLaunchException.MissingPermission()
        }
        // Launching in a voice channel we are not already in means joining it first.
        val alreadyConnected =
            StoreStream.getVoiceChannelSelected().getSelectedVoiceChannelId() == channel.id
        if (channel.isGuildVocalChannel() && !alreadyConnected &&
            !PermissionUtils.can(Permission.CONNECT, permissions)
        ) {
            throw EmbeddedActivityLaunchException.MissingConnect()
        }
    }

    internal fun buildUrl(
        instance: EmbeddedActivityInstance,
        channel: Channel,
        proxyTicket: String,
        request: EmbeddedActivityLaunchRequest,
        frameId: String = UUID.randomUUID().toString(),
    ): String = HttpUrl.Builder()
        .scheme("https")
        .host("${instance.applicationId}.discordsays.com")
        .addPathSegment("")
        .addQueryParameter("instance_id", instance.compositeInstanceId)
        .addQueryParameter("location_id", instance.location.id)
        .addQueryParameter("launch_id", instance.launchId.toString())
        .addQueryParameter("discord_proxy_ticket", proxyTicket)
        .addQueryParameter("channel_id", channel.id.toString())
        .apply { if (channel.guildId > 0L) addQueryParameter("guild_id", channel.guildId.toString()) }
        .apply { request.customId?.let { addQueryParameter("custom_id", it) } }
        .apply { request.referrerId?.let { addQueryParameter("referrer_id", it) } }
        .addQueryParameter("frame_id", frameId)
        .addQueryParameter("platform", EmbeddedActivityPlatform.MOBILE)
        .addQueryParameter("mobile_app_version", CLIENT_VERSION)
        .build()
        .toString()

    private const val INTERACTION_APPLICATION_COMMAND = 2L
}
