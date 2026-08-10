package com.discord.models.domain

import com.discord.api.channel.Channel
import com.discord.api.connectedaccounts.ConnectedAccount
import com.discord.api.guild.Guild
import com.discord.api.guildjoinrequest.GuildJoinRequest
import com.discord.api.guildmember.GuildMember
import com.discord.api.presence.Presence
import com.discord.api.user.User
import com.discord.api.user.UserSurvey
import com.discord.models.experiments.dto.GuildExperimentDto
import com.discord.models.experiments.dto.GuildExperimentDtoTypeAdapter
import com.discord.models.experiments.dto.UserExperimentDto
import com.discord.api.notification.AccountNotificationSettings
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.JsonAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

@JsonAdapter(ModelPayloadTypeAdapterFactory::class)
class ModelPayload() {
    var v: Int = 0
        internal set
    var me: User? = null
        internal set
    var userSettings: ModelUserSettings? = null
        internal set
    var userGuildSettings: VersionedUserGuildSettings? = null
        internal set
    var notificationSettings: AccountNotificationSettings? = null
        internal set
    var sessionId: String? = null
        internal set
    var relationships: List<ModelUserRelationship>? = null
        internal set
    var readState: VersionedReadStates? = null
        internal set
    var privateChannels: List<Channel>? = null
        internal set
    var guilds: List<Guild>? = null
        internal set
    internal var readyGuilds: List<ReadyGuild>? = null
    var guildJoinRequests: List<GuildJoinRequest>? = null
        internal set
    var presences: List<Presence>? = null
        internal set
    var analyticsToken: String? = null

    /**
     * The install's tracking token, carried inside `apex_experiments`.
     *
     * Only this field is read: the assignments beside it are the APEX experiment set, which is not
     * reconstructed, and the token is needed regardless of whether those ever are.
     */
    var installation: String? = null
        internal set
    var authToken: String? = null
        internal set
    /** Identifies which entry of `auth/sessions` is this connection. */
    var authSessionIdHash: String? = null
        internal set
    var requiredAction: String? = null
        internal set
    var experiments: Map<Long, UserExperimentDto>? = null
        internal set
    var guildExperiments: List<GuildExperimentDto>? = null
        internal set
    var connectedAccounts: List<ConnectedAccount>? = null
        internal set
    var sessions: List<ModelSession>? = null
        internal set
    var users: List<User>? = null
        internal set
    var guildPresences: List<List<Presence>>? = null
        internal set
    var guildMembers: List<List<GuildMember>>? = null
        internal set
    var countryCode: String? = null
        internal set
    var trace: Any? = null
        internal set
    var userSurvey: UserSurvey? = null
        internal set
    var friendSuggestionCount: Int = 0
        internal set
    var apiCodeVersion: Int? = null
        internal set

    constructor(other: ModelPayload) : this() {
        v = other.v
        me = other.me
        userSettings = other.userSettings
        userGuildSettings = other.userGuildSettings
        notificationSettings = other.notificationSettings
        sessionId = other.sessionId
        relationships = other.relationships
        readState = other.readState
        privateChannels = other.privateChannels
        guilds = other.guilds
        readyGuilds = other.readyGuilds
        guildJoinRequests = other.guildJoinRequests
        presences = other.presences
        analyticsToken = other.analyticsToken
        installation = other.installation
        authToken = other.authToken
        authSessionIdHash = other.authSessionIdHash
        requiredAction = other.requiredAction
        experiments = other.experiments
        guildExperiments = other.guildExperiments
        connectedAccounts = other.connectedAccounts
        sessions = other.sessions
        users = other.users
        guildPresences = other.guildPresences
        guildMembers = other.guildMembers
        countryCode = other.countryCode
        trace = other.trace
        userSurvey = other.userSurvey
        friendSuggestionCount = other.friendSuggestionCount
        apiCodeVersion = other.apiCodeVersion
    }

    fun withGuilds(guilds: List<Guild>) = ModelPayload(this).also { it.guilds = guilds }

    fun withHydratedUserData(
        relationships: List<ModelUserRelationship>,
        privateChannels: List<Channel>,
        guildPresences: List<List<Presence>>,
        guildMembers: List<List<GuildMember>>,
        presences: List<Presence>,
    ) = ModelPayload(this).also {
        it.relationships = relationships
        it.privateChannels = privateChannels
        it.guildPresences = guildPresences
        it.guildMembers = guildMembers
        it.presences = presences
    }

    override fun equals(other: Any?) = other is ModelPayload && fields() == other.fields()
    override fun hashCode() = fields().hashCode()

    override fun toString() = "ModelPayload(" +
        "v=$v, me=$me, userSettings=$userSettings, userGuildSettings=$userGuildSettings, " +
        "notificationSettings=$notificationSettings, " +
        "sessionId=$sessionId, relationships=$relationships, readState=$readState, " +
        "privateChannels=$privateChannels, guilds=$guilds, readyGuilds=${readyGuilds?.size}, guildJoinRequests=$guildJoinRequests, " +
        "presences=$presences, analyticsToken=<redacted>, authToken=<redacted>, " +
        "authSessionIdHash=<redacted>, " +
        "requiredAction=$requiredAction, experiments=$experiments, guildExperiments=$guildExperiments, " +
        "connectedAccounts=$connectedAccounts, sessions=$sessions, users=$users, " +
        "guildPresences=$guildPresences, guildMembers=$guildMembers, countryCode=$countryCode, " +
        "trace=$trace, userSurvey=$userSurvey, friendSuggestionCount=$friendSuggestionCount, apiCodeVersion=$apiCodeVersion)"

    private fun fields() = listOf(
        v, me, userSettings, userGuildSettings, notificationSettings, sessionId, relationships,
        readState, privateChannels,
        guilds, readyGuilds, guildJoinRequests, presences, analyticsToken, authToken, authSessionIdHash,
        requiredAction, experiments,
        guildExperiments, connectedAccounts, sessions, users, guildPresences, guildMembers, countryCode,
        trace, userSurvey, friendSuggestionCount, apiCodeVersion,
    )

    abstract class VersionedModel<T> {
        var version: Int = DEFAULT_VERSION
            internal set
        var entries: List<T>? = null
            internal set
        var isPartial: Boolean = false
            internal set

        override fun equals(other: Any?) = other is VersionedModel<*> &&
            version == other.version && entries == other.entries && isPartial == other.isPartial

        override fun hashCode() = arrayOf(version, entries, isPartial).contentHashCode()
        override fun toString() =
            "ModelPayload.VersionedModel(version=$version, entries=$entries, partial=$isPartial)"

        companion object { const val DEFAULT_VERSION = -1 }
    }

    class VersionedReadStates : VersionedModel<ModelReadState>()
    class VersionedUserGuildSettings : VersionedModel<ModelNotificationSettings>()

    /**
     * Opcode 10 payload. Parsed straight off the socket stream, so it stays a mutable [Model]
     * rather than a Gson-mapped data class.
     */
    class Hello : Model {
        var heartbeatInterval: Int = 0
            internal set
        var trace: List<String>? = null
            internal set

        override fun assignField(reader: Model.JsonReader) {
            when (reader.nextName()) {
                "heartbeat_interval" -> heartbeatInterval = reader.nextInt(heartbeatInterval)
                "_trace" -> trace = reader.nextList { reader.nextString(null) }
                else -> reader.skipValue()
            }
        }

        override fun equals(other: Any?) = other is Hello &&
            heartbeatInterval == other.heartbeatInterval && trace == other.trace

        override fun hashCode() = 31 * heartbeatInterval + trace.hashCode()

        override fun toString() =
            "ModelPayload.Hello(heartbeatInterval=$heartbeatInterval, trace=$trace)"
    }

    @JsonAdapter(MergedPresencesTypeAdapterFactory::class)
    data class MergedPresences(
        val friends: List<Presence>? = null,
        val guilds: List<List<Presence>>? = null,
    )
}

class MergedPresencesTypeAdapterFactory : TypeAdapterFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? =
        if (type.rawType == ModelPayload.MergedPresences::class.java) {
            object : TypeAdapter<ModelPayload.MergedPresences>() {
                override fun read(reader: JsonReader): ModelPayload.MergedPresences {
                    var friends: List<Presence>? = null
                    var guilds: List<List<Presence>>? = null
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        if (reader.skipNull()) continue
                        when (name) {
                            "friends" -> friends = gson.fromJson(reader, object : TypeToken<List<Presence>>() {}.type)
                            "guilds" -> guilds = gson.fromJson(reader, object : TypeToken<List<List<Presence>>>() {}.type)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    return ModelPayload.MergedPresences(friends, guilds)
                }

                override fun write(writer: JsonWriter, value: ModelPayload.MergedPresences?) =
                    throw UnsupportedOperationException()
            } as TypeAdapter<T>
        } else {
            null
        }
}

class ModelPayloadTypeAdapterFactory : TypeAdapterFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? =
        if (type.rawType == ModelPayload::class.java) ModelPayloadAdapter(gson) as TypeAdapter<T> else null
}

private class ModelPayloadAdapter(private val gson: Gson) : TypeAdapter<ModelPayload>() {
    override fun read(reader: JsonReader): ModelPayload {
        val payload = ModelPayload()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (reader.skipNull()) continue
            when (name) {
                    "v" -> payload.v = reader.nextInt()
                    "user" -> payload.me = gson.fromJson(reader, User::class.java)
                    "user_settings" -> payload.userSettings = gson.fromJson(reader, ModelUserSettings::class.java)
                    "user_guild_settings" -> payload.userGuildSettings = versionedSettings(reader)
                    "notification_settings" ->
                        payload.notificationSettings = gson.fromJson(reader, AccountNotificationSettings::class.java)
                    "session_id" -> payload.sessionId = reader.nextString()
                    "relationships" -> payload.relationships = gson.fromJson(reader, type<List<ModelUserRelationship>>())
                    "read_state" -> payload.readState = versionedReadStates(reader)
                    "private_channels" -> payload.privateChannels = gson.fromJson(reader, type<List<Channel>>())
                    "guilds" -> readGuilds(reader, payload)
                    "guild_join_requests" -> payload.guildJoinRequests = gson.fromJson(reader, type<List<GuildJoinRequest>>())
                    "presences" -> payload.presences = gson.fromJson(reader, type<List<Presence>>())
                    "merged_presences" -> gson.fromJson<ModelPayload.MergedPresences>(
                        reader,
                        ModelPayload.MergedPresences::class.java,
                    ).also {
                        payload.presences = it.friends
                        payload.guildPresences = it.guilds
                    }
                    "merged_members" -> payload.guildMembers = gson.fromJson(reader, type<List<List<GuildMember>>>())
                    "analytics_token" -> payload.analyticsToken = reader.nextString()
                    "apex_experiments" -> payload.installation = readInstallation(reader)
                    "auth_token" -> payload.authToken = reader.nextString()
                    "auth_session_id_hash" -> payload.authSessionIdHash = reader.nextString()
                    "required_action" -> payload.requiredAction = reader.nextString()
                    "experiments" -> payload.experiments = readExperiments(reader)
                    "guild_experiments" -> payload.guildExperiments = readGuildExperiments(reader)
                    "connected_accounts" -> payload.connectedAccounts = gson.fromJson(reader, type<List<ConnectedAccount>>())
                    "sessions" -> payload.sessions = gson.fromJson(reader, type<List<ModelSession>>())
                    "users" -> payload.users = gson.fromJson(reader, type<List<User>>())
                    "country_code" -> payload.countryCode = reader.nextString()
                    "_trace" -> payload.trace = readTrace(reader)
                    "user_survey" -> payload.userSurvey = gson.fromJson(reader, UserSurvey::class.java)
                    "friend_suggestion_count" -> payload.friendSuggestionCount = reader.nextInt()
                    "api_code_version" -> payload.apiCodeVersion = reader.nextInt()
                    else -> reader.skipValue()
            }
        }
        reader.endObject()
        return payload
    }

    override fun write(writer: JsonWriter, value: ModelPayload?) =
        throw UnsupportedOperationException()

    private fun versionedReadStates(reader: JsonReader) =
        ModelPayload.VersionedReadStates().also {
            readVersioned(reader, { value -> it.version = value }, { value -> it.isPartial = value }) {
                it.entries = gson.fromJson(reader, type<List<ModelReadState>>())
            }
        }

    private fun versionedSettings(reader: JsonReader) =
        ModelPayload.VersionedUserGuildSettings().also {
            readVersioned(reader, { value -> it.version = value }, { value -> it.isPartial = value }) {
                it.entries = gson.fromJson(reader, type<List<ModelNotificationSettings>>())
            }
        }

    private inline fun readVersioned(
        reader: JsonReader,
        setVersion: (Int) -> Unit,
        setPartial: (Boolean) -> Unit,
        readEntries: () -> Unit,
    ) {
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (reader.skipNull()) continue
            when (name) {
                "version" -> setVersion(reader.nextInt())
                "partial" -> setPartial(reader.nextBoolean())
                "entries" -> readEntries()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
    }

    private fun readInstallation(reader: JsonReader): String? {
        var installation: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (reader.skipNull()) continue
            if (name == "installation") installation = reader.nextString() else reader.skipValue()
        }
        reader.endObject()
        return installation
    }

    private fun readExperiments(reader: JsonReader): Map<Long, UserExperimentDto>? {
        val value = com.google.gson.JsonParser.parseReader(reader)
        if (!value.isJsonArray) return null
        return value.asJsonArray.mapNotNull { encoded ->
            runCatching { UserExperimentDto.Adapter().fromJsonTree(encoded) }.getOrNull()
        }.associateBy(UserExperimentDto::nameHash)
    }

    private fun readGuildExperiments(reader: JsonReader): List<GuildExperimentDto>? {
        val value = com.google.gson.JsonParser.parseReader(reader)
        if (!value.isJsonArray) return null
        return value.asJsonArray.mapNotNull {
            runCatching { GuildExperimentDtoTypeAdapter().fromJsonTree(it) }.getOrNull()
        }
    }

    /**
     * Reads READY's `guilds`, which is versioned or legacy depending on what was negotiated.
     *
     * Only a guild carrying data the client asked to have diffed has `data_mode`; an unavailable
     * one is a bare `{id, unavailable}` stub in either shape. So the mode is decided by the first
     * guild that is not a stub, and the stubs seen before that are held until it is known.
     */
    private fun readGuilds(reader: JsonReader, payload: ModelPayload) {
        reader.beginArray()
        val stubs = ArrayList<com.google.gson.JsonObject>()
        val readyGuilds = ArrayList<ReadyGuild>()
        val guilds = ArrayList<Guild>()
        var isV2: Boolean? = null
        while (reader.hasNext()) {
            if (isV2 == null) {
                val element = com.google.gson.JsonParser.parseReader(reader).asJsonObject
                if (!element.has("data_mode") && element.get("unavailable")?.asBoolean == true) {
                    stubs += element
                    continue
                }
                isV2 = element.has("data_mode")
                if (isV2) readyGuilds += gson.fromJson(element, ReadyGuild::class.java)
                else guilds += gson.fromJson(element, Guild::class.java)
                stubs.forEach { stub ->
                    if (isV2 == true) readyGuilds += gson.fromJson(stub, ReadyGuild::class.java)
                    else guilds += gson.fromJson(stub, Guild::class.java)
                }
                stubs.clear()
                continue
            }
            if (isV2 == true) readyGuilds += gson.fromJson<ReadyGuild>(reader, ReadyGuild::class.java)
            else guilds += gson.fromJson<Guild>(reader, Guild::class.java)
        }
        reader.endArray()
        // An all-stub array tells us nothing, so it is read as legacy; either shape renders the same.
        if (isV2 == true) {
            payload.readyGuilds = readyGuilds
        } else {
            payload.guilds = guilds + stubs.map { gson.fromJson(it, Guild::class.java) }
        }
    }

    private fun readTrace(reader: JsonReader): Any? {
        reader.beginArray()
        val trace = if (!reader.hasNext() || reader.skipNull()) null else gson.fromJson(reader.nextString(), Any::class.java)
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return trace
    }

    private inline fun <reified T> type() = object : TypeToken<T>() {}.type
}

private fun JsonReader.skipNull(): Boolean {
    if (peek() != JsonToken.NULL) return false
    nextNull()
    return true
}
