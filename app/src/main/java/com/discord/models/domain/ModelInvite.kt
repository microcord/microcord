package com.discord.models.domain

import android.content.res.Resources
import com.discord.api.channel.Channel
import com.discord.api.guild.Guild
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.user.User
import java.io.Serializable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ModelInvite private constructor(
    var channel: Channel?,
    var code: String?,
    var guild: Guild?,
) : Serializable {
    private var approximateMemberCount: Int = 0
    private var approximatePresenceCount: Int = 0
    private var createdAt: String? = null
    private var guildScheduledEvent: GuildScheduledEvent? = null
    private var inviter: User? = null
    private val isStatic: Boolean = code != null
    private var maxAge: Int = 0
    private var maxUses: Int = 0
    private var memberVerificationForm: ModelMemberVerificationForm? = null
    private var newMember: Boolean = false
    private var revoked: Boolean = false
    private var targetType: Int = 0
    private var temporary: Boolean = false
    private var uses: Int = 0

    fun getApproximateMemberCount() = approximateMemberCount
    fun getApproximatePresenceCount() = approximatePresenceCount
    fun getCreatedAt() = createdAt?.let(::parseUtcDate) ?: 0L
    fun getExpirationTime() = getCreatedAt() + maxAge * 1000L
    fun getGuildScheduledEvent() = guildScheduledEvent
    fun getInviter() = inviter
    fun getMaxAge() = maxAge
    fun getMaxUses() = maxUses
    fun getMemberVerificationForm() = memberVerificationForm
    fun getTargetType() = targetType
    fun getTimeToExpirationMillis() = getExpirationTime() - System.currentTimeMillis()
    fun getUses() = uses
    fun isNewMember() = newMember
    fun isRevoked() = revoked
    fun isStatic() = isStatic
    fun isTemporary() = temporary

    fun canEqual(other: Any?) = other is ModelInvite

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ModelInvite || !other.canEqual(this)) return false
        return maxAge == other.maxAge &&
            revoked == other.revoked &&
            temporary == other.temporary &&
            targetType == other.targetType &&
            uses == other.uses &&
            maxUses == other.maxUses &&
            approximatePresenceCount == other.approximatePresenceCount &&
            approximateMemberCount == other.approximateMemberCount &&
            newMember == other.newMember &&
            isStatic == other.isStatic &&
            inviter == other.inviter &&
            guild == other.guild &&
            memberVerificationForm == other.memberVerificationForm &&
            guildScheduledEvent == other.guildScheduledEvent &&
            channel == other.channel &&
            getCreatedAt() == other.getCreatedAt() &&
            code == other.code
    }

    override fun hashCode(): Int {
        var result = maxAge
        result = 31 * result + revoked.hashCode()
        result = 31 * result + temporary.hashCode()
        result = 31 * result + targetType
        result = 31 * result + uses
        result = 31 * result + maxUses
        result = 31 * result + approximatePresenceCount
        result = 31 * result + approximateMemberCount
        result = 31 * result + newMember.hashCode()
        result = 31 * result + isStatic.hashCode()
        result = 31 * result + (inviter?.hashCode() ?: 0)
        result = 31 * result + (guild?.hashCode() ?: 0)
        result = 31 * result + (memberVerificationForm?.hashCode() ?: 0)
        result = 31 * result + (guildScheduledEvent?.hashCode() ?: 0)
        result = 31 * result + (channel?.hashCode() ?: 0)
        result = 31 * result + getCreatedAt().hashCode()
        return 31 * result + (code?.hashCode() ?: 0)
    }

    override fun toString() =
        "ModelInvite(inviter=$inviter, guild=$guild, memberVerificationForm=$memberVerificationForm, " +
            "guildScheduledEvent=$guildScheduledEvent, channel=$channel, createdAt=${getCreatedAt()}, code=$code, " +
            "maxAge=$maxAge, revoked=$revoked, temporary=$temporary, targetType=$targetType, uses=$uses, " +
            "maxUses=$maxUses, approximatePresenceCount=$approximatePresenceCount, " +
            "approximateMemberCount=$approximateMemberCount, newMember=$newMember, isStatic=$isStatic)"

    fun getInviteType(): String? = when {
        channel == null -> null
        targetType == InviteTargetTypes.STREAM.code -> InviteTargetTypes.STREAM.displayName
        targetType == InviteTargetTypes.EMBEDDED_APPLICATION.code -> InviteTargetTypes.EMBEDDED_APPLICATION.displayName
        channel?.type == Channel.GROUP_DM -> InviteTargetTypes.GDM.displayName
        channel?.type != Channel.DM && channel?.type != Channel.GROUP_DM -> InviteTargetTypes.SERVER.displayName
        else -> null
    }

    fun setGuildScheduledEvent(event: GuildScheduledEvent?) {
        guildScheduledEvent = event
    }

    @Suppress("UNUSED_PARAMETER")
    fun toLink(resources: Resources, baseUrl: String): String = guildScheduledEvent
        ?.takeIf { event -> channel?.let { event.isInChannel(it.id) } == true }
        ?.let { "$baseUrl/$code?event=${it.id}" }
        ?: "$baseUrl/$code"

    enum class InviteTargetTypes(val code: Int, val displayName: String) {
        STREAM(1, "Stream"),
        EMBEDDED_APPLICATION(2, "Application"),
        GDM(-1, "GDM Invite"),
        SERVER(-1, "Server Invite"),
    }

    data class Settings(val maxAge: Int, val maxUses: Int = 0, val temporary: Boolean = false) {
        fun mergeMaxAge(maxAge: Int) = copy(maxAge = maxAge)
        fun mergeMaxUses(maxUses: Int) = copy(maxUses = maxUses)
        fun mergeTemporary(temporary: Boolean) = copy(temporary = temporary)

        companion object {
            const val NEVER = 0
            const val HALF_HOUR = 1800
            const val ONE_HOUR = 3600
            const val SIX_HOURS = 21600
            const val TWELVE_HOURS = 43200
            const val ONE_DAY = 86400
            const val SEVEN_DAYS = 604800
            val EXPIRES_AFTER_ARRAY = intArrayOf(NEVER, SEVEN_DAYS, ONE_DAY, TWELVE_HOURS, SIX_HOURS, ONE_HOUR, HALF_HOUR)
            val MAX_USES_ARRAY = intArrayOf(0, 1, 10, 100)
        }
    }

    companion object {
        const val URL_FORMAT = "%1\$s/%2\$s"
        const val URL_FORMAT_WITH_EVENT = "%1\$s/%2\$s?event=%3\$s"

        fun createForStaticUrl(code: String?, guild: Guild?) = ModelInvite(null, code, guild)
        fun createForTesting(channel: Channel?) = ModelInvite(channel, null, null)
        fun getInviteStoreKey(code: String, eventId: Long?) = eventId?.let { "$code?event=$it" } ?: code

        private fun parseUtcDate(value: String): Long {
            val formats = arrayOf("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX")
            for (format in formats) {
                runCatching {
                    return SimpleDateFormat(format, Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }.parse(value)?.time ?: 0L
                }
            }
            return 0L
        }
    }
}
