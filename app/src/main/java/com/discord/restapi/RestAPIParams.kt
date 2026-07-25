package com.discord.restapi

import com.discord.api.message.activity.MessageActivityType
import com.discord.api.presence.ClientStatus
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelCustomStatusSetting
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

object RestAPIParams {
    data class GuildFoldersUpdate(@SerializedName("guild_folders") val guildFolders: List<ModelGuildFolder>)

    data class Message(
        @SerializedName("content") val content: String? = null,
        @SerializedName("nonce") val nonce: String? = null,
        @SerializedName("application_id") val applicationId: Long? = null,
        @SerializedName("activity") val activity: Activity? = null,
        @SerializedName("sticker_ids") val stickerIds: List<Long>? = null,
        @SerializedName("message_reference") val messageReference: MessageReference? = null,
        @SerializedName("allowed_mentions") val allowedMentions: AllowedMentions? = null,
        @SerializedName("captcha_key") val captchaKey: String? = null,
        @SerializedName("captcha_rqtoken") val captchaRqtoken: String? = null,
        @SerializedName("enforce_nonce") val enforceNonce: Boolean? = null,
    ) {
        class Activity(
            type: MessageActivityType,
            @SerializedName("party_id") val partyId: String,
            @SerializedName("session_id") val sessionId: String,
        ) {
            @SerializedName("type")
            val type: Int = type.apiInt
        }

        data class MessageReference(
            @SerializedName("guild_id") val guildId: Long?,
            @SerializedName("channel_id") val channelId: Long,
            @SerializedName("message_id") val messageId: Long?,
        )

        data class AllowedMentions(
            @SerializedName("parse") val parse: List<String>? = null,
            @SerializedName("users") val users: List<Long>? = null,
            @SerializedName("roles") val roles: List<Long>? = null,
            @SerializedName("replied_user") val repliedUser: Boolean? = null,
        )
    }

    data class ChannelMessagesAck(
        @SerializedName("manual") val manual: Boolean?,
        @SerializedName("mention_count") val mentionCount: Int?,
    )

    data class UserRelationship(
        val type: Int? = null,
        val friendToken: String? = null,
        val captchaKey: String? = null,
        val captchaRqtoken: String? = null,
    ) {
        override fun toString() = "UserRelationship(type=$type, credentials=<redacted>)"

        data class Add(
            val username: String,
            val discriminator: Int,
            val captchaKey: String? = null,
            val captchaRqtoken: String? = null,
        ) {
            override fun toString() = "Add(username=$username, discriminator=$discriminator, captcha=<redacted>)"
        }
    }

    data class UserBulkRelationship(
        @SerializedName("user_ids") val userIds: List<Long>,
        val token: String,
    ) {
        override fun toString() = "UserBulkRelationship(userIds=$userIds, token=<redacted>)"
    }

    data class UserNoteUpdate(val note: String?)

    data class CreateChannel(val recipients: List<Long>) {
        constructor(recipientId: Long) : this(listOf(recipientId))
    }

    data class UserSettingsStatus(val status: ClientStatus)

    data class UserSettingsCustomStatus(val customStatus: CustomStatusValue) {
        companion object {
            fun set(value: ModelCustomStatusSetting) = UserSettingsCustomStatus(CustomStatusValue(value))
            fun clear() = UserSettingsCustomStatus(CustomStatusValue(null))
        }
    }

    @JsonAdapter(CustomStatusValue.Adapter::class)
    data class CustomStatusValue(val value: ModelCustomStatusSetting?) {
        class Adapter : TypeAdapter<CustomStatusValue>() {
            override fun write(writer: JsonWriter, wrapped: CustomStatusValue?) {
                val value = wrapped?.value
                if (value == null) {
                    val serializeNulls = writer.serializeNulls
                    writer.serializeNulls = true
                    writer.nullValue()
                    writer.serializeNulls = serializeNulls
                    return
                }
                writer.beginObject()
                value.text?.let { writer.name("text").value(it) }
                value.emojiId?.let { writer.name("emoji_id").value(it) }
                value.emojiName?.let { writer.name("emoji_name").value(it) }
                value.expiresAt?.let { writer.name("expires_at").value(it) }
                writer.endObject()
            }

            override fun read(reader: JsonReader): CustomStatusValue? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return CustomStatusValue(null)
                }
                var text: String? = null
                var emojiId: Long? = null
                var emojiName: String? = null
                var expiresAt: String? = null
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "text" -> text = reader.nextStringOrNull()
                    "emoji_id" -> emojiId = reader.nextLongOrNull()
                    "emoji_name" -> emojiName = reader.nextStringOrNull()
                    "expires_at" -> expiresAt = reader.nextStringOrNull()
                    else -> reader.skipValue()
                }
                reader.endObject()
                return CustomStatusValue(ModelCustomStatusSetting(text, emojiId, emojiName, expiresAt))
            }

            private fun JsonReader.nextStringOrNull(): String? =
                if (peek() == JsonToken.NULL) null.also { nextNull() } else nextString()

            private fun JsonReader.nextLongOrNull(): Long? =
                if (peek() == JsonToken.NULL) null.also { nextNull() } else nextLong()
        }
    }

    class EmptyBody {
        override fun toString(): String = "EmptyBody"
    }

    data class AuthLogin(
        val login: String,
        val password: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
        val undelete: Boolean?,
        val loginSource: String?,
    ) {
        override fun toString(): String = "AuthLogin(<redacted>)"

        object LoginSource {
            const val LOGIN_SOURCE_KEYCHAIN_AUTO = "keychain_auto"
        }
    }

    data class AuthRegister(
        val fingerprint: String,
        val username: String,
        val email: String?,
        val phoneToken: String?,
        val password: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
        val invite: String?,
        val guildTemplateCode: String?,
        val consent: Boolean,
        val dateOfBirth: String?,
    ) {
        override fun toString(): String = "AuthRegister(<redacted>)"
    }

    data class MFALogin(val ticket: String, val code: String) {
        override fun toString(): String = "MFALogin(<redacted>)"
    }

    data class AuthorizeIP(val token: String) {
        override fun toString(): String = "AuthorizeIP(token=<redacted>)"
    }

    data class ForgotPassword(
        val login: String,
        val captchaKey: String?,
        val captchaRqtoken: String?,
    ) {
        override fun toString(): String = "ForgotPassword(<redacted>)"
    }

    data class UserDevices(val token: String?) {
        val provider: String = "gcm"
        override fun toString(): String = "UserDevices(provider=$provider, token=<redacted>)"
    }

    data class VerificationCode(val phone: String, val code: String) {
        override fun toString(): String = "VerificationCode(<redacted>)"
    }

    data class VerificationCodeResend(val phone: String) {
        override fun toString(): String = "VerificationCodeResend(phone=<redacted>)"
    }

    data class VerificationPhoneCode(
        val phoneToken: String,
        val password: String,
        val source: String,
    ) {
        override fun toString(): String = "VerificationPhoneCode(source=$source, credentials=<redacted>)"
    }
}
