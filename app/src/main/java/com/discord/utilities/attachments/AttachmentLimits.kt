package com.discord.utilities.attachments

import com.discord.api.guild.GuildFeature
import com.discord.api.premium.PremiumTier
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.utilities.user.UserUtils.isStaff

/**
 * Reference `Constants`, `PremiumConstants`, `utils/FileUtils` and `utils/UploadUtils`.
 *
 * Two independent ceilings apply to one send, and conflating them is what the old single check did:
 * every attachment must fit [maxFileSize] on its own, and their combined size must fit
 * [MAX_TOTAL_ATTACHMENT_SIZE]. Only the per-file one can be raised by Nitro or by the guild, which
 * is why exceeding the total must never offer the subscription upsell.
 *
 * Every value is binary — the reference names them `_MB` but computes them from `1024 * 1024`.
 */
object AttachmentLimits {

    const val MAX_ATTACHMENT_SIZE: Long = 10L * 1024 * 1024
    private const val MAX_STAFF_ATTACHMENT_SIZE: Long = 500L * 1024 * 1024

    /** Applies to the whole message, and no entitlement raises it. */
    const val MAX_TOTAL_ATTACHMENT_SIZE: Long = 500L * 1024 * 1024

    const val MAX_UPLOAD_COUNT: Int = 10

    /**
     * Refuse anything past this before transcoding on mobile.
     *
     * Compression can only shrink a file, so one already above every ceiling cannot be saved by it;
     * the real per-file limit is applied again afterwards.
     */
    const val PRE_COMPRESSION_MAX_ATTACHMENT_SIZE: Long = 200L * 1024 * 1024

    private const val NITRO_BASIC_AND_CLASSIC_SIZE: Long = 50L * 1024 * 1024
    private const val NITRO_SIZE: Long = 500L * 1024 * 1024

    /**
     * Staff is checked before the subscription because it outranks every tier. `TIER_0` (Nitro
     * Basic) sits below `TIER_1` in declaration order but grants the same size, so these are
     * matched by identity rather than by rank.
     */
    fun userMaxFileSize(user: MeUser?): Long = when {
        user == null -> MAX_ATTACHMENT_SIZE
        user.isStaff -> MAX_STAFF_ATTACHMENT_SIZE
        user.premiumTier == PremiumTier.TIER_2 -> NITRO_SIZE
        user.premiumTier == PremiumTier.TIER_1 ||
            user.premiumTier == PremiumTier.TIER_0 -> NITRO_BASIC_AND_CLASSIC_SIZE
        else -> MAX_ATTACHMENT_SIZE
    }

    /**
     * A guild raises the ceiling through powerup features rather than a boost level, and may hold
     * more than one at a time, so the largest wins rather than the list being treated as ordered.
     */
    fun guildMaxFileSize(guild: Guild?): Long = guildFileSizeFeatures
        .filter { (feature, _) -> guild?.hasFeature(feature) == true }
        .maxOfOrNull { (_, limit) -> limit }
        ?: MAX_ATTACHMENT_SIZE

    private val guildFileSizeFeatures: List<Pair<GuildFeature, Long>> = listOf(
        GuildFeature.MAX_FILE_SIZE_250_MB to 250L * 1024 * 1024,
        GuildFeature.MAX_FILE_SIZE_100_MB to 100L * 1024 * 1024,
        GuildFeature.MAX_FILE_SIZE_50_MB to 50L * 1024 * 1024,
    )

    /**
     * The ceiling for a single file here: whichever of the viewer's own allowance and the guild's
     * is larger. A DM has no guild to contribute one.
     */
    fun maxFileSize(user: MeUser?, guild: Guild?): Long =
        maxOf(userMaxFileSize(user), if (guild == null) 0L else guildMaxFileSize(guild))

    /** What went wrong, kept apart because the two report differently. */
    sealed class Violation {
        /** [size] is the offending file's, so the upsell can tell whether Nitro would fit it. */
        data class FileTooLarge(val size: Long, val limit: Long) : Violation()

        data class TotalTooLarge(val total: Long) : Violation()

        data class TooManyFiles(val count: Int) : Violation()
    }

    fun validate(sizes: List<Long>, maxFileSize: Long): Violation? {
        if (sizes.size > MAX_UPLOAD_COUNT) return Violation.TooManyFiles(sizes.size)
        sizes.maxOrNull()?.takeIf { it > maxFileSize }?.let {
            return Violation.FileTooLarge(it, maxFileSize)
        }
        val total = sizes.sumOf { it }
        if (total > MAX_TOTAL_ATTACHMENT_SIZE) return Violation.TotalTooLarge(total)
        return null
    }

    fun validate(sizes: List<Long>, user: MeUser?, guild: Guild?): Violation? =
        validate(sizes, maxFileSize(user, guild))

    /**
     * Whether a subscription would actually raise the ceiling past [size].
     *
     * The reference suppresses the upsell when it would not, so the viewer is not sold something
     * that still cannot send the file.
     */
    fun wouldNitroAllow(size: Long): Boolean = size <= NITRO_SIZE
}
