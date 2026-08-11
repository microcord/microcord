package com.discord.api.guildjoinrequest

import com.discord.api.user.User
import com.google.gson.TypeAdapter
import com.google.gson.annotations.JsonAdapter
import com.google.gson.annotations.SerializedName
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.util.Locale

@JsonAdapter(ApplicationStatusTypeAdapter::class)
enum class ApplicationStatus { STARTED, PENDING, REJECTED, APPROVED, UNKNOWN }

class ApplicationStatusTypeAdapter : TypeAdapter<ApplicationStatus>() {
    override fun read(reader: JsonReader): ApplicationStatus {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return ApplicationStatus.UNKNOWN
        }
        return when (reader.nextString()) {
            "STARTED" -> ApplicationStatus.STARTED
            "PENDING", "SUBMITTED" -> ApplicationStatus.PENDING
            "REJECTED" -> ApplicationStatus.REJECTED
            "APPROVED" -> ApplicationStatus.APPROVED
            else -> ApplicationStatus.UNKNOWN
        }
    }

    override fun write(writer: JsonWriter, value: ApplicationStatus?) {
        if (value == null) writer.nullValue() else writer.value(value.name.uppercase(Locale.ROOT))
    }
}

data class GuildJoinRequest(
    @SerializedName("user_id") val userId: Long,
    val user: User?,
    @SerializedName("guild_id") val guildId: Long,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("application_status") val applicationStatus: ApplicationStatus,
    @SerializedName("rejection_reason") val rejectionReason: String?,
    @SerializedName("invite_code") val inviteCode: String?,
    @SerializedName("last_seen") val lastSeen: String?,
    @SerializedName("actioned_at") val actionedAt: String?,
    /** Identifies the application itself; the ack endpoint is addressed by it, not by guild. */
    @SerializedName("join_request_id") val joinRequestId: Long? = null,
    /**
     * The group DM moderators opened to interview the applicant, once one exists. Shares its
     * snowflake with [joinRequestId].
     */
    @SerializedName("interview_channel_id") val interviewChannelId: Long? = null,
) {
    /**
     * An approval the user has already been shown. The reference drops these rather than keeping
     * them, so the row for the guild leaves the pending folder once the "you're in" dialog is
     * confirmed instead of lingering as a permanently approved application.
     */
    val isApprovedAndAcked: Boolean
        get() = applicationStatus == ApplicationStatus.APPROVED && lastSeen != null
}

data class GuildJoinRequestCreateOrUpdate(
    @SerializedName("guild_id") val guildId: Long,
    val request: GuildJoinRequest,
)

data class GuildJoinRequestDelete(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("guild_id") val guildId: Long,
)
