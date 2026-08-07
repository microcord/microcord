package com.discord.restapi

import com.discord.api.botuikit.ComponentType
import com.discord.api.guild.GuildExplicitContentFilter
import com.discord.api.guild.GuildFeature
import com.discord.api.guild.GuildVerificationLevel
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityMetadata
import com.discord.api.guildscheduledevent.GuildScheduledEventEntityType
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.api.message.activity.MessageActivityType
import com.discord.api.message.allowedmentions.MessageAllowedMentions
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.presence.ClientStatus
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelCustomStatusSetting
import com.discord.models.domain.ModelGuildFolder
import com.discord.models.domain.ModelMemberVerificationForm
import com.discord.models.domain.ModelMuteConfig
import com.discord.models.domain.ModelSubscription
import com.discord.nullserializable.NullSerializable
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Locale

/**
 * Every first-party REST request body accepted by [RestAPIInterface].
 *
 * Wire names come from the Gson instance built in [RestAPIBuilder], which applies
 * `FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`. The reference declares these bodies with plain
 * camelCase property names and no `@SerializedName`, relying entirely on that policy; this
 * reconstruction does the same so the emitted JSON keys stay identical.
 *
 * Bodies that carry credentials, tokens, verification codes or contact details deliberately override
 * `toString` so they cannot leak through logs or crash reports.
 */
object RestAPIParams {

    class ActivateMfaSMS(val password: String) {
        override fun toString(): String = "ActivateMfaSMS(<redacted>)"
    }

    data class AddServerBody(
        val description: String,
        val primaryCategoryId: Int,
    )

    /** `POST interactions` body for a slash/user/message command invocation. */
    data class ApplicationCommand(
        val type: Long,
        val channelId: String,
        val applicationId: String,
        val guildId: String?,
        val data: com.discord.api.commands.ApplicationCommandData,
        val sessionId: String?,
        val nonce: String,
        val analyticsLocation: String? = null,
        val sectionName: String? = null,
        val source: String? = null,
    )

    data class AuthLogin(
        val login: String,
        val password: String,
        val undelete: Boolean?,
        val loginSource: String?,
    ) {
        override fun toString(): String = "AuthLogin(<redacted>)"

        /** Where a login was started from, when that was not the plain login screen. */
        object LoginSource {
            const val GIFT = "gift"
            const val GUILD_TEMPLATE = "guild_template"
            const val GUILD_INVITE = "guild_invite"
            const val DM_INVITE = "dm_invite"
            const val FRIEND_INVITE = "friend_invite"
            const val ROLE_SUBSCRIPTION = "role_subscription"
            const val ROLE_SUBSCRIPTION_SETTING = "role_subscription_setting"
        }
    }

    /** [globalName] is the display name; registration has been pomelo-native since usernames split. */
    data class AuthRegister(
        val fingerprint: String,
        val username: String,
        val email: String?,
        val phoneToken: String?,
        val password: String,
        val invite: String?,
        val guildTemplateCode: String?,
        val consent: Boolean,
        val dateOfBirth: String? = null,
        val globalName: String? = null,
        val giftCodeSkuId: String? = null,
        val promotionalEmailOptIn: Boolean? = null,
    ) {
        override fun toString(): String = "AuthRegister(<redacted>)"
    }

    class AuthRegisterPhone(
        val phone: String,
    ) {
        override fun toString(): String = "AuthRegisterPhone(<redacted>)"
    }

    data class AuthorizeIP(val token: String) {
        override fun toString(): String = "AuthorizeIP(token=<redacted>)"
    }

    class BanGuildMember(val deleteMessageDays: Int? = null)

    /** Not a challenge retry: here the solved key is the request's actual payload. */
    class CaptchaCode(
        val captchaKey: String?,
        val captchaRqtoken: String?,
    ) {
        override fun toString(): String = "CaptchaCode(<redacted>)"
    }

    class ChannelFollowerPost(val webhookChannelId: Long)

    /**
     * `POST read-states/ack-bulk`. `read_state_type` is omitted for ordinary channels, which the
     * server then reads from the channel itself.
     */
    data class BulkAck(@SerializedName("read_states") val readStates: List<ReadState>) {
        data class ReadState(
            @SerializedName("channel_id") val channelId: Long,
            @SerializedName("message_id") val messageId: Long,
            @SerializedName("read_state_type") val readStateType: Int? = null,
        )
    }

    data class ChannelMessagesAck(
        val manual: Boolean?,
        val mentionCount: Int?,
        /** The rolling token from the previous ack; omitted on the first of a session. */
        val token: String? = null,
        @SerializedName("last_viewed") val lastViewed: Int? = null,
        /** Only sent when the classification bits changed, per the reference. */
        val flags: Int? = null,
    )

    /**
     * `PUT channels/{channelId}/permissions/{targetId}` body. `id` is serialized as a string and
     * `type` as the numeric [PermissionOverwrite.Type] ordinal, matching the reference factories.
     */
    class ChannelPermissionOverwrites private constructor(
        val type: Int?,
        val id: String?,
        val allow: Long?,
        val deny: Long?,
    ) {
        companion object {
            private const val TYPE_ROLE = 0
            private const val TYPE_MEMBER = 1

            fun createForRole(roleId: Long, allow: Long?, deny: Long?) =
                ChannelPermissionOverwrites(TYPE_ROLE, roleId.toString(), allow, deny)

            fun createForMember(userId: Long, allow: Long?, deny: Long?) =
                ChannelPermissionOverwrites(TYPE_MEMBER, userId.toString(), allow, deny)

            fun fromPermissionOverwrite(overwrite: PermissionOverwrite) =
                ChannelPermissionOverwrites(
                    when (overwrite.type) {
                        PermissionOverwrite.Type.ROLE -> TYPE_ROLE
                        PermissionOverwrite.Type.MEMBER -> TYPE_MEMBER
                    },
                    overwrite.id.toString(),
                    overwrite.allow,
                    overwrite.deny,
                )
        }
    }

    /**
     * One entry of the `PATCH guilds/{guildId}/channels` reorder payload.
     *
     * [Serializer] is registered in [RestAPIBuilder] because [PARENT_ID_NONE] must be written as an
     * explicit JSON `null` (move to root) while an absent parent must be omitted entirely. Snowflakes
     * remain 64-bit JSON numbers; positions remain integers.
     */
    @JsonAdapter(ChannelPosition.Serializer::class)
    class ChannelPosition(
        val id: Long,
        val position: Int,
        val parentId: Long?,
    ) {
        companion object {
            const val PARENT_ID_NONE = -1L
        }

        class Serializer : TypeAdapter<ChannelPosition>() {
            override fun write(writer: JsonWriter, value: ChannelPosition?) {
                if (value == null) {
                    writer.nullValue()
                    return
                }
                writer.beginObject()
                writer.name("id").value(value.id)
                writer.name("position").value(value.position)
                value.parentId?.let { parentId ->
                    writer.name("parent_id")
                    if (parentId == PARENT_ID_NONE) {
                        val serializeNulls = writer.serializeNulls
                        writer.serializeNulls = true
                        writer.nullValue()
                        writer.serializeNulls = serializeNulls
                    } else {
                        writer.value(parentId)
                    }
                }
                writer.endObject()
            }

            override fun read(reader: JsonReader): ChannelPosition? {
                if (reader.peek() == JsonToken.NULL) return null.also { reader.nextNull() }
                var id = 0L
                var position = 0
                var parentId: Long? = null
                reader.beginObject()
                while (reader.hasNext()) when (reader.nextName()) {
                    "id" -> id = reader.nextString().toLong()
                    "position" -> position = reader.nextInt()
                    "parent_id" -> parentId = if (reader.peek() == JsonToken.NULL) {
                        PARENT_ID_NONE.also { reader.nextNull() }
                    } else {
                        reader.nextString().toLong()
                    }
                    else -> reader.skipValue()
                }
                reader.endObject()
                return ChannelPosition(id, position, parentId)
            }
        }
    }

    data class ChannelVoiceStateUpdate(
        val channelId: Long,
        val suppress: Boolean? = null,
        val requestToSpeakTimestamp: UtcDateTime? = null,
    )

    /** `POST interactions` body for a button press or select-menu submission. */
    data class ComponentInteraction(
        val type: Long,
        val channelId: Long,
        val applicationId: Long,
        val guildId: Long?,
        val messageId: Long,
        val messageFlags: Long?,
        val data: ComponentInteractionData,
        val sessionId: String?,
        val nonce: String,
    )

    sealed class ComponentInteractionData {
        data class ButtonComponentInteractionData(
            val componentType: ComponentType = ComponentType.BUTTON,
            val customId: String,
        ) : ComponentInteractionData()

        data class SelectComponentInteractionData(
            val componentType: ComponentType = ComponentType.SELECT,
            val customId: String,
            val values: List<String>,
        ) : ComponentInteractionData()
    }

    /**
     * `PATCH users/@me/connections/{connection}/{connectionId}` body. The reference declares these
     * properties in snake_case; the naming policy leaves such names unchanged, so the camelCase
     * spelling used here produces the same wire keys.
     */
    class ConnectedAccount(
        val friendSync: Boolean,
        val id: String,
        val name: String?,
        val revoked: Boolean,
        val showActivity: Boolean,
        val type: String,
        val verified: Boolean,
        val visibility: Int,
    ) {
        companion object {
            fun create(
                connectedAccount: com.discord.api.connectedaccounts.ConnectedAccount,
                friendSync: Boolean,
                showActivity: Boolean,
                visibility: Int,
            ) = ConnectedAccount(
                friendSync = friendSync,
                id = connectedAccount.id,
                name = connectedAccount.name,
                revoked = connectedAccount.revoked,
                showActivity = showActivity,
                type = connectedAccount.type,
                verified = connectedAccount.verified,
                visibility = visibility,
            )
        }
    }

    data class ConnectedAccountContacts(
        val name: String?,
        val friendSync: Boolean,
    )

    data class ConnectedAccountNameOnly(val name: String? = null)

    class ConnectionState(
        val code: String?,
        val state: String?,
        val fromContinuation: Boolean?,
        val insecure: Boolean?,
    ) {
        override fun toString(): String = "ConnectionState(<redacted>)"
    }

    class Consents(
        val grant: List<String> = emptyList(),
        val revoke: List<String> = emptyList(),
    ) {
        object Type {
            const val PERSONALIZATION = "personalization"
            const val USAGE_STATS = "usage_statistics"
        }
    }

    class ContactEntry(
        val mutualIdHash: String,
        val friendId: String,
        val metadata: Map<String, String>,
    ) {
        override fun toString(): String = "ContactEntry(<redacted>)"
    }

    class CreateGuild(
        val name: String,
        val icon: String?,
        val channels: List<CreateGuildChannel>? = null,
        val systemChannelId: Long? = null,
    )

    class CreateGuildChannel(
        val type: Int,
        val id: Long?,
        val name: String?,
        val parentId: Long?,
        val permissionOverwrites: List<ChannelPermissionOverwrites>?,
        val topic: String?,
    )

    class CreateGuildFromTemplate(
        val name: String,
        val icon: String?,
    )

    data class CreateGuildRoleSubscriptionGroupListing(
        val image: String?,
        val description: String?,
        val fullServerGate: Boolean?,
    )

    data class CreateGuildRoleSubscriptionTierListing(
        val name: String,
        val image: String?,
        val description: String?,
        val benefits: List<com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefit>,
        val priceTier: Int,
        val canAccessAllChannels: Boolean,
    )

    data class CreateGuildScheduledEventBody(
        val name: String,
        val description: String?,
        val privacyLevel: StageInstancePrivacyLevel,
        val scheduledStartTime: String,
        val scheduledEndTime: String?,
        val channelId: Long?,
        val entityType: GuildScheduledEventEntityType,
        val entityMetadata: GuildScheduledEventEntityMetadata?,
        val broadcastToDirectoryChannels: Boolean?,
    )

    class DeleteGuild(val code: String?) {
        override fun toString(): String = "DeleteGuild(code=<redacted>)"
    }

    class DeletePhone(val password: String) {
        override fun toString(): String = "DeletePhone(<redacted>)"
    }

    /** MFA-gated: [com.discord.restapi.MfaInterceptor] supplies the verification, not a body field. */
    class DisableAccount(val password: String) {
        override fun toString(): String = "DisableAccount(<redacted>)"
    }

    class DisableGuildCommunication(val communicationDisabledUntil: String? = null)

    data class DowngradeSubscriptionBody(
        val subscriptionId: String,
        val purchaseToken: String,
        val subscriptionSkuId: String,
    ) {
        override fun toString(): String =
            "DowngradeSubscriptionBody(subscriptionId=$subscriptionId, " +
                "subscriptionSkuId=$subscriptionSkuId, purchaseToken=<redacted>)"
    }

    class EmptyBody {
        override fun toString(): String = "EmptyBody"
    }

    class EnableIntegration(
        val type: String?,
        val id: String?,
    )

    class EnableMFA(
        val code: String,
        val secret: String,
        val password: String,
    ) {
        override fun toString(): String = "EnableMFA(<redacted>)"
    }

    data class ValidatePassword(val password: String) {
        override fun toString(): String = "ValidatePassword(password=<redacted>)"
    }

    data class UsernameAttempt(val username: String)

    data class OneTimeLogin(val ticket: String) {
        override fun toString(): String = "OneTimeLogin(ticket=<redacted>)"
    }

    /** Body of `POST auth/sessions/logout`; 1-64 session id hashes. */
    data class LogoutAuthSessions(val sessionIdHashes: List<String>)

    data class RenameAuthenticator(val name: String)

    data class ForgotPassword(
        val login: String,
    ) {
        override fun toString(): String = "ForgotPassword(<redacted>)"
    }

    class GenerateGiftCode(
        val skuId: Long,
        val subscriptionPlanId: Long?,
    )

    data class GetForumPostData(val threadIds: List<Long>)

    class GreetMessage(val stickerIds: List<Long>)

    class GroupDM(
        val name: String?,
        val icon: String?,
    )

    class GuildBoosting(val userPremiumGuildSubscriptionSlotIds: List<Long>)

    class GuildFeatureAck(
        val manual: Boolean?,
        val mentionCount: Int?,
    )

    class GuildIntegration(
        val expireBehavior: Int,
        val expireGracePeriod: Int,
        val enableEmoticons: Boolean,
    )

    class GuildMFA(
        val level: Int,
        val code: String?,
    ) {
        override fun toString(): String = "GuildMFA(level=$level, code=<redacted>)"
    }

    class GuildMember(
        val nick: String? = null,
        val roles: List<Long>? = null,
        val mute: Boolean? = null,
        val deaf: Boolean? = null,
        val channelId: Long? = null,
    ) {
        companion object {
            fun createWithNick(nick: String?) = GuildMember(nick = nick)
            fun createWithRoles(roles: List<Long>?) = GuildMember(roles = roles)
            fun createWithMute(mute: Boolean) = GuildMember(mute = mute)
            fun createWithDeaf(deaf: Boolean) = GuildMember(deaf = deaf)
            fun createWithChannelId(channelId: Long) = GuildMember(channelId = channelId)
        }
    }

    class GuildMemberDisconnect(val channelId: Long? = null)

    data class HubWaitlist(
        val email: String,
        val school: String,
    ) {
        override fun toString(): String = "HubWaitlist(school=$school, email=<redacted>)"
    }

    class Invite(
        val maxAge: Int,
        val maxUses: Int,
        val temporary: Boolean,
        val regenerate: String?,
    )

    /** Empty, but still required: the endpoints want a JSON object and Retrofit wants a type. */
    class InviteCode

    data class InvoicePreviewBody(
        val subscriptionId: String,
        val renewal: Boolean,
        val applyEntitlements: Boolean,
    )

    data class LeaveGuildBody(val lurking: Boolean = false)

    /**
     * Body of `POST auth/mfa/{type}`.
     *
     * [loginInstanceId] ties the verification to the login attempt that issued the ticket; the
     * server rejects a mismatch even when the code is right.
     */
    data class MFALogin(
        val ticket: String,
        val code: String,
        val loginInstanceId: String? = null,
        val loginSource: String? = null,
        val giftCodeSkuId: String? = null,
    ) {
        override fun toString(): String = "MFALogin(<redacted>)"
    }

    /** Body of `POST auth/mfa/sms/send`. */
    data class MFASendSms(val ticket: String) {
        override fun toString(): String = "MFASendSms(ticket=<redacted>)"
    }

    class MemberVerificationForm(
        val formFields: List<ModelMemberVerificationForm.FormField>? = null,
        val version: String? = null,
    )

    data class Message(
        val content: String? = null,
        val nonce: String? = null,
        val applicationId: Long? = null,
        val activity: Activity? = null,
        val stickerIds: List<Long>? = null,
        val messageReference: MessageReference? = null,
        val allowedMentions: AllowedMentions? = null,
        val attachments: List<Attachment>? = null,
        val poll: Poll? = null,
        // ponytail: remove with StoreStream's legacy upload call site; 126.21 never sends this field.
        @Transient
        val enforceNonce: Boolean? = null,
    ) {
        override fun toString(): String =
            "Message(nonce=$nonce, applicationId=$applicationId, stickerIds=$stickerIds, " +
                "content=<redacted>)"

        /**
         * [uploadedFilename] names a file already sitting in storage, which is how a cloud upload
         * is claimed. It is absent only for the legacy multipart send, where the bytes travel with
         * the request instead.
         */
        data class Attachment(
            val id: Int,
            val filename: String,
            @SerializedName("is_spoiler") val isSpoiler: Boolean,
            @SerializedName("uploaded_filename") val uploadedFilename: String? = null,
        )

        data class Poll(
            val question: PollMedia,
            val answers: List<PollAnswer>,
            val duration: Int,
            @SerializedName("allow_multiselect") val allowMultiselect: Boolean,
            @SerializedName("layout_type") val layoutType: Int = 1,
        )

        data class PollAnswer(@SerializedName("poll_media") val pollMedia: PollMedia)

        data class PollMedia(
            val text: String,
            val emoji: PollEmoji? = null,
        )

        data class PollEmoji(
            val id: Long? = null,
            val name: String,
            val animated: Boolean? = null,
        )

        class Activity(
            type: MessageActivityType,
            val partyId: String,
            val sessionId: String,
        ) {
            val type: Int = type.apiInt
        }

        data class MessageReference(
            val guildId: Long?,
            val channelId: Long,
            val messageId: Long?,
            val type: Int? = null,
            val failIfNotExists: Boolean? = null,
            val forwardOnly: ForwardOnly? = null,
        )

        data class ForwardOnly(
            val attachmentIds: List<Long>? = null,
            val embedIndices: List<Int>? = null,
        )

        data class AllowedMentions(
            val parse: List<String>? = null,
            val users: List<Long>? = null,
            val roles: List<Long>? = null,
            val repliedUser: Boolean? = null,
        ) {
            companion object {
                fun create(model: MessageAllowedMentions) = AllowedMentions(
                    parse = model.parse?.map { it.serialize() },
                    users = model.users,
                    roles = model.roles,
                    repliedUser = model.repliedUser,
                )
            }
        }
    }

    data class MessageRequestConsentStatus(val consentStatus: Int)

    data class PollVote(@SerializedName("answer_ids") val answerIds: List<Int>)

    /** `POST interactions` body for a modal submission. */
    data class ModalInteraction(
        val type: Long,
        val applicationId: Long,
        val messageId: Long?,
        val channelId: Long,
        val guildId: Long?,
        val sessionId: String?,
        val nonce: Long,
        val data: ModalInteractionData,
    )

    data class ModalInteractionData(
        val id: Long,
        val customId: String,
        val components: List<ModalInteractionDataComponent>,
        val attachments: List<ModalAttachment>? = null,
    )

    data class ModalAttachment(val id: Long, val filename: String)

    sealed interface ModalInteractionDataComponent {
        val type: ComponentType
        val id: Int?
    }

    data class ModalInteractionDataLayout(
        override val type: ComponentType,
        override val id: Int?,
        val components: List<ModalInteractionDataComponent>? = null,
        val component: ModalInteractionDataComponent? = null,
    ) : ModalInteractionDataComponent

    data class ModalInteractionDataText(
        override val type: ComponentType,
        override val id: Int?,
        val customId: String,
        val value: String,
    ) : ModalInteractionDataComponent

    data class ModalInteractionDataBoolean(
        override val type: ComponentType,
        override val id: Int?,
        val customId: String,
        val value: Boolean,
    ) : ModalInteractionDataComponent

    data class ModalInteractionDataValues(
        override val type: ComponentType,
        override val id: Int?,
        val customId: String,
        val values: List<String>,
    ) : ModalInteractionDataComponent

    data class ModalInteractionDataContent(
        override val type: ComponentType,
        override val id: Int?,
    ) : ModalInteractionDataComponent

    class Nick(val nick: String?)

    object OAuth2Authorize {
        class ResponseGet(
            val user: com.discord.api.user.User,
            val application: com.discord.api.application.Application,
            val connections: List<com.discord.api.connectedaccounts.ConnectedAccount>,
            val guilds: List<com.discord.api.guild.Guild>,
        )

        class ResponsePost(val location: String)
    }

    class PatchGuildEmoji(val name: String)

    class Phone(
        val phone: String?,
        val source: String?,
    ) {
        override fun toString(): String = "Phone(source=$source, <redacted>)"
    }

    class PostGuildEmoji(
        val name: String,
        val image: String,
    )

    class PruneGuild(
        val days: Int? = null,
        val computePruneCount: Boolean? = false,
    )

    data class PurchaseMetadataBody(
        val skuId: Long,
        val paymentGatewaySkuId: String,
    )

    class RemoteAuthCancel(val handshakeToken: String) {
        override fun toString(): String = "RemoteAuthCancel(handshakeToken=<redacted>)"
    }

    class RemoteAuthFinish(
        val temporary: Boolean,
        val handshakeToken: String,
    ) {
        override fun toString(): String =
            "RemoteAuthFinish(temporary=$temporary, handshakeToken=<redacted>)"
    }

    class RemoteAuthInitialize(val fingerprint: String)

    class Report(
        val reason: Int,
        val channelId: Long? = null,
        val messageId: Long? = null,
    )

    class Ring(val recipients: List<Long>?)

    /** `PATCH guilds/{guildId}/roles[/{roleId}]` body. */
    class Role(
        var hoist: Boolean? = null,
        var name: String? = null,
        var mentionable: Boolean? = null,
        var color: Int? = null,
        var position: Int? = null,
        var permissions: Long? = null,
        var id: Long = 0L,
        var icon: String? = null,
    ) {
        companion object {
            fun createForPosition(id: Long, position: Int) = Role(position = position, id = id)

            fun createWithRole(role: GuildRole) = Role(
                hoist = role.hoist,
                name = role.name,
                mentionable = role.mentionable,
                color = role.color,
                position = role.position,
                permissions = role.permissions,
                id = role.id,
            )
        }
    }

    data class StartStageInstanceBody(
        val channelId: Long,
        val topic: String,
        val privacyLevel: StageInstancePrivacyLevel,
        val sendStartNotification: Boolean,
        val guildScheduledEventId: String? = null,
    )

    class TextChannel(
        val name: String?,
        val type: Int?,
        val topic: String?,
        val nsfw: Boolean?,
        val rateLimitPerUser: Int?,
        val defaultAutoArchiveDuration: Int?,
        /** Private threads only. */
        val invitable: Boolean? = null,
        val flags: Long? = null,
        /** Thread-only channels: what members see before overriding it for themselves. */
        val defaultForumLayout: Int? = null,
        val defaultSortOrder: Int? = null,
        val defaultTagSetting: String? = null,
        val defaultThreadRateLimitPerUser: Int? = null,
    )

    /** A forum tag being created or edited; [emojiName] and [emojiId] are mutually exclusive. */
    data class ForumTag(
        val name: String,
        val emojiId: Long? = null,
        val emojiName: String? = null,
        val moderated: Boolean = false,
    )

    data class ThreadCreationSettings(
        val name: String,
        val type: Int,
        val autoArchiveDuration: Int? = null,
    )

    data class ForumThreadCreation(
        val name: String,
        val autoArchiveDuration: Int,
        val appliedTags: List<Long> = emptyList(),
        val message: Message,
    )

    data class ThreadMemberSettings(
        val flags: Int? = null,
        val muted: Boolean? = null,
        val muteConfig: ModelMuteConfig? = null,
    )

    data class ThreadSettings(
        val archived: Boolean? = null,
        val locked: Boolean? = null,
        val autoArchiveDuration: Int? = null,
        val invitable: Boolean? = null,
        val appliedTags: List<Long>? = null,
        val flags: Long? = null,
    )

    class Thumbnail(val thumbnail: String)

    class TopicalChannel(
        val name: String?,
        val type: Int?,
        val topic: String?,
    )

    class TransferGuildOwnership(
        val ownerId: Long,
        val code: String?,
    ) {
        override fun toString(): String =
            "TransferGuildOwnership(ownerId=$ownerId, code=<redacted>)"
    }

    class UpdateGuild(
        val afkChannelId: Long? = null,
        val afkTimeout: Int? = null,
        val systemChannelId: Long? = null,
        val defaultMessageNotifications: Int? = null,
        val icon: String? = null,
        val name: String? = null,
        val verificationLevel: GuildVerificationLevel? = null,
        val explicitContentFilter: GuildExplicitContentFilter? = null,
        val splash: String? = null,
        val banner: String? = null,
        val systemChannelFlags: Int? = null,
        val features: List<GuildFeature>? = null,
        val rulesChannelId: Long? = null,
        val publicUpdatesChannelId: Long? = null,
        val preferredLocale: String? = null,
    ) {
        companion object {
            fun createForVerificationLevel(verificationLevel: GuildVerificationLevel) =
                UpdateGuild(verificationLevel = verificationLevel)

            fun createForExplicitContentFilter(
                explicitContentFilter: GuildExplicitContentFilter,
            ) = UpdateGuild(explicitContentFilter = explicitContentFilter)
        }
    }

    data class UpdateGuildRoleSubscriptionGroupListing(
        val image: String?,
        val description: String?,
        val fullServerGate: Boolean?,
    )

    data class UpdateGuildRoleSubscriptionTierFreeTrial(
        val trial: NullSerializable<com.discord.api.guildrolesubscription.SubscriptionTrialInterval>?,
        val maxNumActiveTrialUsers: NullSerializable<Int>?,
    )

    data class UpdateGuildRoleSubscriptionTierListing(
        val name: String?,
        val image: String?,
        val description: String?,
        val priceTier: Int?,
        val benefits: List<com.discord.api.guildrolesubscription.GuildRoleSubscriptionBenefit>?,
        val published: Boolean?,
        val canAccessAllChannels: Boolean?,
    )

    data class UpdateGuildScheduledEventBody(
        val name: String? = null,
        val description: String? = null,
        val privacyLevel: StageInstancePrivacyLevel? = null,
        val scheduledStartTime: String? = null,
        val scheduledEndTime: String? = null,
        val channelId: NullSerializable<Long>? = null,
        val entityType: GuildScheduledEventEntityType? = null,
        val entityMetadata: NullSerializable<GuildScheduledEventEntityMetadata>? = null,
        val status: GuildScheduledEventStatus? = null,
        val broadcastToDirectoryChannels: Boolean? = null,
    )

    data class UpdateStageInstanceBody(
        val topic: String? = null,
        val privacyLevel: StageInstancePrivacyLevel? = null,
    )

    class UpdateSubscription(
        val status: Int? = null,
        val paymentSourceId: String? = null,
        val planId: Long? = null,
        val additionalPlans: List<ModelSubscription.SubscriptionAdditionalPlan>? = null,
    )

    class UploadContacts(
        val friendListEntries: List<ContactEntry>,
        val background: Boolean,
        val setUseForSuggestions: com.discord.api.friendsuggestions.AllowedInSuggestionsType? =
            com.discord.api.friendsuggestions.AllowedInSuggestionsType.DISABLED,
    ) {
        override fun toString(): String =
            "UploadContacts(entries=${friendListEntries.size}, background=$background, " +
                "setUseForSuggestions=$setUseForSuggestions)"
    }

    class UserAgreements(
        val terms: Boolean? = null,
        val privacy: Boolean? = null,
        val acknowledgment: Boolean? = null,
    ) {
        companion object {
            fun agree() = UserAgreements(terms = true, privacy = true)
            fun acknowledge() = UserAgreements(acknowledgment = true)
        }
    }

    data class UserBulkRelationship(
        val userIds: List<Long>,
        val token: String,
    ) {
        override fun toString(): String = "UserBulkRelationship(userIds=$userIds, token=<redacted>)"
    }

    data class UserDevices(val token: String?) {
        val provider: String = "gcm"
        override fun toString(): String = "UserDevices(provider=$provider, token=<redacted>)"
    }

    data class UserEmailConfirmCode(val code: String) {
        override fun toString(): String = "UserEmailConfirmCode(code=<redacted>)"
    }

    class UserGuildSettings(
        val suppressEveryone: Boolean? = null,
        val suppressRoles: Boolean? = null,
        val muted: Boolean? = null,
        val muteConfig: ModelMuteConfig? = null,
        val mobilePush: Boolean? = null,
        val messageNotifications: Int? = null,
        val channelOverrides: Map<Long, ChannelOverride>? = null,
        val notifyHighlights: Int? = null,
        val flags: Int? = null,
        val muteScheduledEvents: Boolean? = null,
    ) {
        constructor(channelId: Long, channelOverride: ChannelOverride) :
            this(channelOverrides = mapOf(channelId to channelOverride))

        class ChannelOverride(
            val muted: Boolean?,
            val muteConfig: ModelMuteConfig?,
            val messageNotifications: Int? = null,
            val flags: Int? = null,
        ) {
            constructor(messageNotifications: Int?, flags: Int?) :
                this(null, null, messageNotifications, flags)
        }
    }

    /**
     * `PATCH users/@me` body. Populated through [Companion] helpers so a partial patch never
     * accidentally clears an unrelated account field.
     */
    class UserInfo(
        val avatar: String? = null,
        val email: String? = null,
        val emailToken: String? = null,
        val password: String? = null,
        val newPassword: String? = null,
        val username: String? = null,
        val pushToken: String? = null,
        val discriminator: String? = null,
        val flags: Int? = null,
        val dateOfBirth: String? = null,
    ) {
        val pushProvider: String = "gcm"

        override fun toString(): String = "UserInfo(<redacted>)"

        companion object {
            fun createForEmail(email: CharSequence, password: CharSequence) =
                UserInfo(email = email.toString(), password = password.toString())
        }
    }

    /**
     * `PATCH users/@me/relationships/{id}` body.
     *
     * [NullSerializable] because clearing has to put an explicit `null` on the wire; Gson omits a
     * plain null field, which the server reads as "leave it alone".
     */
    data class FriendNickname(val nickname: NullSerializable<String>)

    data class UserNoteUpdate(val note: String?)

    data class UserRelationship(
        val type: Int? = null,
        val friendToken: String? = null,
    ) {
        override fun toString() = "UserRelationship(type=$type, credentials=<redacted>)"

        /** [discriminator] is null for a unique username; the field is sent as an explicit null. */
        data class Add(
            val username: String,
            val discriminator: Int?,
        )
    }

    /**
     * `PATCH users/@me/settings` body. The reference exposes only the `createWith*` factories so a
     * settings write always carries exactly one changed value.
     */
    class UserSettings private constructor(
        val theme: String? = null,
        val developerMode: Boolean? = null,
        val renderEmbeds: Boolean? = null,
        val inlineEmbedMedia: Boolean? = null,
        val inlineAttachmentMedia: Boolean? = null,
        val blockedMessageBar: Boolean? = null,
        val locale: String? = null,
        val restrictedGuilds: Collection<Long>? = null,
        val status: String? = null,
        val showCurrentGame: Boolean? = null,
        val guildFolders: Collection<ModelGuildFolder>? = null,
        val defaultGuildsRestricted: Boolean? = null,
        val friendSourceFlags: FriendSourceFlags? = null,
        val explicitContentFilter: Int? = null,
        val animateEmoji: Boolean? = null,
        val allowAccessibilityDetection: Boolean? = null,
        val animateStickers: Int? = null,
        val contactSyncEnabled: Boolean? = null,
        val friendDiscoveryFlags: Int? = null,
    ) {
        class FriendSourceFlags(
            val all: Boolean?,
            val mutualGuilds: Boolean?,
            val mutualFriends: Boolean?,
        )

        companion object {
            fun createWithTheme(theme: String) = UserSettings(theme = theme)

            fun createWithDeveloperMode(developerMode: Boolean) =
                UserSettings(developerMode = developerMode)

            fun createWithRenderEmbeds(renderEmbeds: Boolean) =
                UserSettings(renderEmbeds = renderEmbeds)

            fun createWithInlineEmbedMedia(inlineEmbedMedia: Boolean) =
                UserSettings(inlineEmbedMedia = inlineEmbedMedia)

            fun createWithInlineAttachmentMedia(inlineAttachmentMedia: Boolean) =
                UserSettings(inlineAttachmentMedia = inlineAttachmentMedia)

            fun createWithBlockedMessageBar(blockedMessageBar: Boolean) =
                UserSettings(blockedMessageBar = blockedMessageBar)

            fun createWithLocale(locale: String) = UserSettings(locale = locale)

            fun createWithRestrictedGuilds(
                defaultGuildsRestricted: Boolean?,
                restrictedGuilds: Collection<Long>?,
            ) = UserSettings(
                restrictedGuilds = restrictedGuilds,
                defaultGuildsRestricted = defaultGuildsRestricted,
            )

            fun createWithStatus(status: ClientStatus) =
                UserSettings(status = status.name.lowercase(Locale.ROOT))

            fun createWithShowCurrentGame(showCurrentGame: Boolean) =
                UserSettings(showCurrentGame = showCurrentGame)

            fun createWithGuildFolders(guildFolders: List<ModelGuildFolder>) =
                UserSettings(guildFolders = guildFolders)

            fun createWithFriendSourceFlags(
                all: Boolean?,
                mutualGuilds: Boolean?,
                mutualFriends: Boolean?,
            ) = UserSettings(friendSourceFlags = FriendSourceFlags(all, mutualGuilds, mutualFriends))

            fun createWithExplicitContentFilter(explicitContentFilter: Int) =
                UserSettings(explicitContentFilter = explicitContentFilter)

            fun createWithAllowAnimatedEmojis(animateEmoji: Boolean?) =
                UserSettings(animateEmoji = animateEmoji)

            fun createWithAllowAccessibilityDetection(allowAccessibilityDetection: Boolean?) =
                UserSettings(allowAccessibilityDetection = allowAccessibilityDetection)

            fun createWithStickerAnimationSettings(animateStickers: Int?) =
                UserSettings(animateStickers = animateStickers)

            fun createWithContactSyncUpsellShown() = UserSettings(contactSyncEnabled = true)

            fun createWithFriendDiscoveryFlags(friendDiscoveryFlags: Int?) =
                UserSettings(friendDiscoveryFlags = friendDiscoveryFlags)
        }
    }

    class VanityUrl(val code: String? = null)

    data class VerificationCode(
        val phone: String,
        val code: String,
    ) {
        override fun toString(): String = "VerificationCode(<redacted>)"
    }

    class VerificationCodeOnly(val code: String) {
        override fun toString(): String = "VerificationCodeOnly(code=<redacted>)"
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

    data class VerifyEmail(
        val email: String,
        val guildId: Long? = null,
        val allowMultipleGuilds: Boolean = false,
        val useVerificationCode: Boolean = true,
    ) {
        override fun toString(): String =
            "VerifyEmail(guildId=$guildId, allowMultipleGuilds=$allowMultipleGuilds, " +
                "useVerificationCode=$useVerificationCode, email=<redacted>)"
    }

    data class VerifyEmailCode(
        val email: String,
        val code: String,
        val guildId: Long?,
    ) {
        override fun toString(): String = "VerifyEmailCode(guildId=$guildId, <redacted>)"
    }

    data class VerifyPurchaseResponse(
        val verifiedSkuId: Long,
        val subscriptionPlanId: Long?,
        val giftCode: String?,
    ) {
        override fun toString(): String =
            "VerifyPurchaseResponse(verifiedSkuId=$verifiedSkuId, " +
                "subscriptionPlanId=$subscriptionPlanId, giftCode=<redacted>)"
    }

    data class VerifyPurchaseTokenBody(
        val purchaseToken: String,
        val userId: Long,
        val packageName: String,
        val subscriptionSkuId: String?,
        val oneTimePurchaseSkuId: String?,
    ) {
        override fun toString(): String =
            "VerifyPurchaseTokenBody(userId=$userId, packageName=$packageName, " +
                "subscriptionSkuId=$subscriptionSkuId, " +
                "oneTimePurchaseSkuId=$oneTimePurchaseSkuId, purchaseToken=<redacted>)"
    }

    class VoiceChannel(
        val name: String?,
        val topic: String?,
        val type: Int?,
        val nsfw: Boolean?,
        val userLimit: Int?,
        val bitrate: Int?,
        val rtcRegion: String?,
    )

    data class CreateChannel(val recipients: List<Long>) {
        constructor(recipientId: Long) : this(listOf(recipientId))
    }

    // ---------------------------------------------------------------------------------------------
    // Reconstruction-only bodies.
    //
    // 126.21 sends every `PATCH users/@me/settings` write through `UserSettings`. These narrower
    // bodies predate the full `UserSettings` reconstruction and remain because store code already
    // depends on them; they serialize to the same keys as the corresponding `UserSettings` field.
    // ---------------------------------------------------------------------------------------------

    data class GuildFoldersUpdate(@SerializedName("guild_folders") val guildFolders: List<ModelGuildFolder>)

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
}
