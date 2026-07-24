package com.discord.models.domain

import com.discord.utilities.time.Clock
import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.JsonParser
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

@JsonAdapter(ModelNotificationSettingsTypeAdapterFactory::class)
class ModelNotificationSettings(
    @SerializedName("suppress_everyone") val isSuppressEveryone: Boolean = false,
    @SerializedName("suppress_roles") val isSuppressRoles: Boolean = false,
    private val muted: Boolean = false,
    val muteConfig: ModelMuteConfig? = null,
    private val derivedMute: Boolean = isMuted(muted, muteConfig, System.currentTimeMillis()),
    val isMobilePush: Boolean = true,
    val messageNotifications: Int = FREQUENCY_UNSET,
    val guildId: Long = 0,
    val channelOverrides: List<ChannelOverride> = emptyList(),
    val version: Int = 0,
    val notifyHighlights: Int = HIGHLIGHTS_NULL,
) {
    val isMuted: Boolean get() = derivedMute
    val muteEndTime: String? get() = muteConfig?.endTime

    fun getChannelOverride(channelId: Long) = channelOverrides.firstOrNull { it.channelId == channelId }

    fun getNextMuteEndTimeMs(clock: Clock): Long {
        val endTimes = mutableListOf<Long>()
        muteConfig?.endTimeMs?.let(endTimes::add)
        channelOverrides.forEach { it.muteConfig?.endTimeMs?.let(endTimes::add) }
        return endTimes.filter { it > clock.currentTimeMillis() }.minOrNull() ?: Long.MAX_VALUE
    }

    fun copyAndRecomputeTransientFields(clock: Clock) = ModelNotificationSettings(
        isSuppressEveryone,
        isSuppressRoles,
        muted,
        muteConfig,
        isMuted(muted, muteConfig, clock.currentTimeMillis()),
        isMobilePush,
        messageNotifications,
        guildId,
        channelOverrides.map { it.copyAndRecomputeTransientFields(clock) },
        version,
        notifyHighlights,
    )

    class ChannelOverride(
        private val muted: Boolean = false,
        val muteConfig: ModelMuteConfig? = null,
        private val derivedMute: Boolean = isMuted(muted, muteConfig, System.currentTimeMillis()),
        val messageNotifications: Int = FREQUENCY_UNSET,
        val flags: Int = NEW_FORUM_POSTS_UNSET,
        val channelId: Long = 0,
    ) {
        val isMuted: Boolean get() = derivedMute
        val muteEndTime: String? get() = muteConfig?.endTime

        fun getForumPostNotificationSetting(): Int = when {
            flags and FORUM_THREAD_CREATED_OFF == FORUM_THREAD_CREATED_OFF -> FORUM_THREAD_CREATED_OFF
            flags and FORUM_THREAD_CREATED_ON == FORUM_THREAD_CREATED_ON -> FORUM_THREAD_CREATED_ON
            else -> NEW_FORUM_POSTS_UNSET
        }

        fun copyAndRecomputeTransientFields(clock: Clock) = ChannelOverride(
            muted,
            muteConfig,
            isMuted(muted, muteConfig, clock.currentTimeMillis()),
            messageNotifications,
            flags,
            channelId,
        )

        override fun equals(other: Any?) = other is ChannelOverride &&
            muted == other.muted && muteConfig == other.muteConfig &&
            derivedMute == other.derivedMute && messageNotifications == other.messageNotifications &&
            flags == other.flags && channelId == other.channelId

        override fun hashCode() = arrayOf(
            muted,
            muteConfig,
            derivedMute,
            messageNotifications,
            flags,
            channelId,
        ).contentHashCode()

        override fun toString() = "ModelNotificationSettings.ChannelOverride(" +
            "muted=$isMuted, muteConfig=$muteConfig, derivedMute=$derivedMute, " +
            "messageNotifications=$messageNotifications, flags=$flags, channelId=$channelId)"
    }

    override fun equals(other: Any?) = other is ModelNotificationSettings &&
        isSuppressEveryone == other.isSuppressEveryone && isSuppressRoles == other.isSuppressRoles &&
        muted == other.muted && muteConfig == other.muteConfig && derivedMute == other.derivedMute &&
        isMobilePush == other.isMobilePush && messageNotifications == other.messageNotifications &&
        guildId == other.guildId && channelOverrides == other.channelOverrides &&
        version == other.version && notifyHighlights == other.notifyHighlights

    override fun hashCode() = arrayOf(
        isSuppressEveryone,
        isSuppressRoles,
        muted,
        muteConfig,
        derivedMute,
        isMobilePush,
        messageNotifications,
        guildId,
        channelOverrides,
        version,
        notifyHighlights,
    ).contentHashCode()

    override fun toString() = "ModelNotificationSettings(" +
        "suppressEveryone=$isSuppressEveryone, suppressRoles=$isSuppressRoles, muted=$isMuted, " +
        "muteConfig=$muteConfig, derivedMute=$derivedMute, mobilePush=$isMobilePush, " +
        "messageNotifications=$messageNotifications, guildId=$guildId, channelOverrides=$channelOverrides, " +
        "version=$version, notifyHighlights=$notifyHighlights)"

    companion object {
        const val FREQUENCY_ALL = 0
        const val FREQUENCY_MENTIONS = 1
        const val FREQUENCY_NOTHING = 2
        const val FREQUENCY_UNSET = 3
        const val HIGHLIGHTS_NULL = 0
        const val HIGHLIGHTS_DISABLED = 1
        const val HIGHLIGHTS_ENABLED = 2
        const val NEW_FORUM_POSTS_UNSET = 0
        const val FORUM_THREAD_CREATED_OFF = 8192
        const val FORUM_THREAD_CREATED_ON = 16384
        const val FORUM_THREAD_CREATED_MASK = FORUM_THREAD_CREATED_OFF or FORUM_THREAD_CREATED_ON

        private fun isMuted(muted: Boolean, config: ModelMuteConfig?, now: Long) =
            muted && config?.endTimeMs?.let { it <= now } != true
    }
}

class ModelNotificationSettingsTypeAdapterFactory : TypeAdapterFactory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? =
        if (type.rawType == ModelNotificationSettings::class.java) {
            ModelNotificationSettingsTypeAdapter(gson) as TypeAdapter<T>
        } else {
            null
        }
}

private class ModelNotificationSettingsTypeAdapter(private val gson: Gson) : TypeAdapter<ModelNotificationSettings>() {
    override fun read(reader: JsonReader): ModelNotificationSettings {
        val json = JsonParser.parseReader(reader).asJsonObject
        val muteConfig = json["mute_config"]?.takeUnless { it.isJsonNull }
            ?.let { gson.fromJson(it, ModelMuteConfig::class.java) }
        val overrides = json["channel_overrides"]?.asJsonArray?.map { element ->
            val value = element.asJsonObject
            val overrideMuteConfig = value["mute_config"]?.takeUnless { it.isJsonNull }
                ?.let { gson.fromJson(it, ModelMuteConfig::class.java) }
            val muted = value["muted"]?.asBoolean ?: false
            ModelNotificationSettings.ChannelOverride(
                muted,
                overrideMuteConfig,
                muted && overrideMuteConfig?.endTimeMs?.let { it <= System.currentTimeMillis() } != true,
                value["message_notifications"]?.asInt ?: ModelNotificationSettings.FREQUENCY_UNSET,
                value["flags"]?.asInt ?: ModelNotificationSettings.NEW_FORUM_POSTS_UNSET,
                value["channel_id"]?.asLong ?: 0,
            )
        }.orEmpty()
        val muted = json["muted"]?.asBoolean ?: false
        return ModelNotificationSettings(
            json["suppress_everyone"]?.asBoolean ?: false,
            json["suppress_roles"]?.asBoolean ?: false,
            muted,
            muteConfig,
            muted && muteConfig?.endTimeMs?.let { it <= System.currentTimeMillis() } != true,
            json["mobile_push"]?.asBoolean ?: true,
            json["message_notifications"]?.asInt ?: ModelNotificationSettings.FREQUENCY_UNSET,
            json["guild_id"]?.asLong ?: 0,
            overrides,
            json["version"]?.asInt ?: 0,
            json["notify_highlights"]?.asInt ?: ModelNotificationSettings.HIGHLIGHTS_NULL,
        )
    }

    override fun write(writer: JsonWriter, value: ModelNotificationSettings?) =
        throw UnsupportedOperationException()
}
