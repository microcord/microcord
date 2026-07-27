package com.discord.utilities.user

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import androidx.annotation.ColorInt
import com.discord.api.premium.PremiumTier
import com.discord.api.presence.ClientStatus
import com.discord.api.user.User as ApiUser
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.presence.Presence
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.nullserializable.NullSerializable
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.time.Clock
import com.discord.utilities.time.ClockFactory
import java.security.MessageDigest
import java.util.Locale

/**
 * Badge predicates, name rendering, and account-age checks for users.
 *
 * Badges live in two overlapping bitfields — `flags` is what the client knows about the
 * current user, `public_flags` is what everybody else can see — so every predicate here
 * tests the union of the two. Never test a single field directly.
 */
object UserUtils {

    /** The account that authors official system messages. */
    const val SYSTEM_USER_ID = 643945264868098049L

    /** Clyde is synthetic and has no real snowflake. */
    const val CLYDE_BOT_USER_ID = -1L

    /** Guild verification levels can require an account to be at least this old. */
    private const val GUILD_VERIFICATION_ACCOUNT_AGE_THRESHOLD_MS = 300_000L

    /** Accounts younger than this are flagged as new in safety surfaces. */
    private const val NEW_USER_THRESHOLD_MS = 604_800_000L

    // User flag bits, in the order the reference declares the predicates.
    private const val FLAG_STAFF = 1
    private const val FLAG_PARTNER = 2
    private const val FLAG_HYPESQUAD = 4
    private const val FLAG_BUG_HUNTER_LEVEL_1 = 8
    private const val FLAG_MFA_SMS = 16
    private const val FLAG_HYPESQUAD_HOUSE_1 = 64
    private const val FLAG_HYPESQUAD_HOUSE_2 = 128
    private const val FLAG_HYPESQUAD_HOUSE_3 = 256
    private const val FLAG_PREMIUM_EARLY_SUPPORTER = 512
    private const val FLAG_HAS_UNREAD_URGENT_MESSAGES = 8192
    private const val FLAG_BUG_HUNTER_LEVEL_2 = 16384
    private const val FLAG_VERIFIED_BOT = 65536
    private const val FLAG_VERIFIED_DEVELOPER = 131072
    private const val FLAG_CERTIFIED_MODERATOR = 262144
    private const val FLAG_HTTP_INTERACTIONS_BOT = 524288

    /** A stand-in rendered while a real user is still loading. */
    @JvmStatic
    val EMPTY_USER: User = CoreUser(
        id = 0,
        username = "Empty User",
        discriminator = 1,
        premiumTier = PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY,
    )

    private fun hasFlag(flags: Int, targetFlag: Int): Boolean = (flags and targetFlag) == targetFlag

    private fun User.hasFlag(targetFlag: Int): Boolean = hasFlag(publicFlags or flags, targetFlag)

    // region badges

    val User.isStaff: Boolean get() = hasFlag(FLAG_STAFF)
    val User.isPartner: Boolean get() = hasFlag(FLAG_PARTNER)
    val User.isHypeSquad: Boolean get() = hasFlag(FLAG_HYPESQUAD)
    val User.isBugHunterLevel1: Boolean get() = hasFlag(FLAG_BUG_HUNTER_LEVEL_1)
    val User.isBugHunterLevel2: Boolean get() = hasFlag(FLAG_BUG_HUNTER_LEVEL_2)
    val User.isMfaSMSEnabled: Boolean get() = hasFlag(FLAG_MFA_SMS)
    val User.isHypesquadHouse1: Boolean get() = hasFlag(FLAG_HYPESQUAD_HOUSE_1)
    val User.isHypesquadHouse2: Boolean get() = hasFlag(FLAG_HYPESQUAD_HOUSE_2)
    val User.isHypesquadHouse3: Boolean get() = hasFlag(FLAG_HYPESQUAD_HOUSE_3)
    val User.isPremiumEarlySupporter: Boolean get() = hasFlag(FLAG_PREMIUM_EARLY_SUPPORTER)
    val User.isVerifiedDeveloper: Boolean get() = hasFlag(FLAG_VERIFIED_DEVELOPER)
    val User.isCertifiedModerator: Boolean get() = hasFlag(FLAG_CERTIFIED_MODERATOR)
    val User.isHttpInteractionsBot: Boolean get() = hasFlag(FLAG_HTTP_INTERACTIONS_BOT)
    val User.hasUnreadUrgentMessages: Boolean get() = hasFlag(FLAG_HAS_UNREAD_URGENT_MESSAGES)

    /** In exactly one of the three HypeSquad houses. */
    val User.isInHypesquadHouse: Boolean
        get() = isHypesquadHouse1 || isHypesquadHouse2 || isHypesquadHouse3

    /** Verified bots, the system account, and Clyde all render the verified tick. */
    val User.isVerifiedBot: Boolean
        get() = hasFlag(FLAG_VERIFIED_BOT) || isSystemUser || id == CLYDE_BOT_USER_ID

    // endregion

    // region premium

    /** Any Nitro tier, excluding the guild-boost-only pseudo tier. */
    val User.isPremium: Boolean
        get() = premiumTier != PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY &&
            premiumTier != PremiumTier.NONE

    val User.isPremiumTier2: Boolean get() = premiumTier == PremiumTier.TIER_2

    /** Any paid entitlement at all, including guild-boost-only. */
    val User.hasSubscription: Boolean get() = premiumTier != PremiumTier.NONE

    /** Tiers that unlock the premium sticker packs. */
    val User.canUsePremiumStickers: Boolean
        get() = premiumTier == PremiumTier.TIER_2 || premiumTier == PremiumTier.TIER_0

    val MeUser.hasPhone: Boolean get() = phoneNumber != null

    // endregion

    // region names

    /** The four-digit discriminator with its leading `#`, zero padded. */
    val User.discriminatorWithPadding: String get() = padDiscriminator(discriminator)

    /** Formats [discriminator] as `#0001`. */
    fun padDiscriminator(discriminator: Int): String =
        String.format(Locale.US, "#%04d", discriminator)

    /**
     * `username#0001`, optionally styling the discriminator.
     *
     * Returns a plain [String] when neither styling argument is supplied, so callers that
     * only need text do not pay for a [SpannableString].
     */
    @JvmOverloads
    fun User.getUserNameWithDiscriminator(
        @ColorInt discriminatorColor: Int? = null,
        discriminatorRelativeSize: Float? = null,
    ): CharSequence {
        val text = username + discriminatorWithPadding
        if (discriminatorColor == null && discriminatorRelativeSize == null) return text
        return SpannableString(text).apply {
            val flags = Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            discriminatorColor?.let {
                setSpan(ForegroundColorSpan(it), username.length, length, flags)
            }
            discriminatorRelativeSize?.let {
                setSpan(RelativeSizeSpan(it), username.length, length, flags)
            }
        }
    }

    /** Case-insensitive name ordering, falling back to user id so it is a total order. */
    @JvmOverloads
    fun compareUserNames(
        user1: User?,
        user2: User?,
        user1Nickname: String? = null,
        user2Nickname: String? = null,
    ): Int = compareUserNames(
        user1?.username,
        user2?.username,
        user1Nickname,
        user2Nickname,
        user1?.id ?: 0L,
        user2?.id ?: 0L,
    )

    /** Nicknames win over usernames when present; blank nicknames are ignored. */
    fun compareUserNames(
        user1Username: String?,
        user2Username: String?,
        user1Nickname: String?,
        user2Nickname: String?,
        user1Id: Long,
        user2Id: Long,
    ): Int {
        if (user1Username == null) return if (user2Username == null) 0 else -1
        if (user2Username == null) return 1
        val name1 = user1Nickname?.takeUnless(String::isBlank) ?: user1Username
        val name2 = user2Nickname?.takeUnless(String::isBlank) ?: user2Username
        val byName = name1.compareTo(name2, ignoreCase = true)
        return if (byName != 0) byName else user1Id.compareTo(user2Id)
    }

    // endregion

    // region account age

    /** How long ago the account was created, derived from its snowflake. */
    @JvmOverloads
    fun User.getAgeMs(clock: Clock = ClockFactory.get()): Long =
        clock.currentTimeMillis() - createdAtMs

    /** Whether the account is old enough for guild verification levels that check age. */
    @JvmOverloads
    fun User.isAccountOldEnough(clock: Clock = ClockFactory.get()): Boolean =
        getAgeMs(clock) > GUILD_VERIFICATION_ACCOUNT_AGE_THRESHOLD_MS

    /** Whether the account was created within the last week. */
    @JvmOverloads
    fun User.isNewUser(clock: Clock = ClockFactory.get()): Boolean =
        createdAtMs >= clock.currentTimeMillis() - NEW_USER_THRESHOLD_MS

    /** Whether the account already existed at [date]. */
    @JvmOverloads
    fun User.since(date: UtcDateTime, clock: Clock = ClockFactory.get()): Boolean =
        UtcDateTime(createdAtMs) <= date

    private val User.createdAtMs: Long
        get() = SnowflakeUtils.toTimestamp(id)

    // endregion

    /**
     * Whether the presence dot should be rendered for this user.
     *
     * HTTP-interaction bots have no gateway connection, so they only show a dot when a
     * presence actually exists and is not offline. Everyone else shows one whenever a
     * presence is known, or always when [isMissingPresenceVisible].
     */
    fun isStatusVisible(user: User, presence: Presence?, isMissingPresenceVisible: Boolean): Boolean =
        isStatusVisible(user.publicFlags or user.flags, presence, isMissingPresenceVisible)

    /** [isStatusVisible] for a raw flag union, used where only flags are available. */
    fun isStatusVisible(flags: Int, presence: Presence?, isMissingPresenceVisible: Boolean): Boolean =
        if (hasFlag(flags, FLAG_HTTP_INTERACTIONS_BOT)) {
            presence != null && presence.status != ClientStatus.OFFLINE
        } else {
            presence != null || isMissingPresenceVisible
        }

    /**
     * Rebuilds the API user that a cached model user came from.
     *
     * Needed where an API-shaped payload is required but only the model is in hand, for
     * example when synthesizing a local message author.
     */
    fun User.synthesizeApiUser(): ApiUser = ApiUser(
        id = id,
        username = username,
        avatar = avatar?.let { NullSerializable.Value(it) } ?: NullSerializable.Null(),
        discriminator = discriminator.toString(),
        publicFlags = publicFlags,
        flags = flags,
        bot = isBot,
        system = isSystemUser,
    )

    /**
     * A stable, irreversible identifier for a user, for diagnostics that must not carry
     * the real id.
     */
    fun getObfuscatedUserId(id: Long?): String? {
        if (id == null) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toString().toByteArray())
        return digest.joinToString("") { String.format(Locale.US, "%02x", it) }
    }
}
