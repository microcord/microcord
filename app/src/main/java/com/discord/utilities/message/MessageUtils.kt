package com.discord.utilities.message

import android.content.Context
import androidx.annotation.StringRes
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.ChannelRecipientNick
import com.discord.api.message.role_subscription.RoleSubscriptionData
import com.discord.api.sticker.StickerFormatType
import com.discord.api.sticker.StickerPartial
import com.discord.api.user.User
import com.discord.i18n.I18nHooks
import com.discord.i18n.i18nRender
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.utilities.locale.LocaleManager
import com.discord.utilities.time.TimeUtils
import java.util.Locale
import com.discord.utilities.user.NicknameUtils
import com.discord.utilities.user.UserUtils.getDisplayName

/**
 * Message ordering, per-channel display names, and the copy for the two system messages whose text
 * the client picks rather than the server.
 */
object MessageUtils {

    /**
     * Orders message ids oldest-first.
     *
     * Snowflakes are monotonic in creation time, so an id comparison *is* a chronological
     * comparison — but the ids are unsigned 64-bit values stored in a signed `Long`, and a plain
     * subtraction would overflow. The reference compares by subtraction and then narrows to a sign,
     * which is the same result for the id range Discord actually issues (the high bit is not set
     * until the year 2154); a direct `compareTo` is used here because it has no overflow window
     * at all.
     *
     * A null id sorts as `0`, i.e. oldest.
     */
    @JvmStatic
    fun compareMessages(messageId1: Long?, messageId2: Long?): Int =
        (messageId1 ?: 0L).compareTo(messageId2 ?: 0L)

    /** Whether [messageId1] is *older* than [messageId2] — the reference's name is inverted. */
    @JvmStatic
    fun isNewer(messageId1: Long?, messageId2: Long?): Boolean =
        compareMessages(messageId1, messageId2) < 0

    /** Oldest-first comparator over raw message ids. */
    @JvmField
    val SORT_BY_IDS_COMPARATOR: Comparator<Long> =
        Comparator { first, second -> compareMessages(first, second) }

    /**
     * The name to show for every user this message can name: its mentions, its author, and the
     * invoking user of a slash command.
     *
     * Resolution is per *channel*, not global — a group DM carries its own `nicks` list which wins
     * over the guild nickname, which in turn wins over the username. Building the whole map once
     * per message is what stops the renderer doing a store lookup per mention while it walks the
     * text.
     */
    @JvmStatic
    @JvmOverloads
    fun getNickOrUsernames(
        message: Message,
        channel: Channel?,
        guildMembers: Map<Long, GuildMember>,
        nicks: List<ChannelRecipientNick>? = null,
    ): Map<Long, String> = getNickOrUsernames(
        mentions = message.mentions,
        author = message.author,
        interactionUser = message.interaction?.user,
        channel = channel,
        guildMembers = guildMembers,
        nicks = nicks,
    )

    /**
     * Decomposed form, for callers that hold the participants but not a whole [Message] — the
     * renderer builds its name map before the model exists.
     */
    @JvmStatic
    @JvmOverloads
    fun getNickOrUsernames(
        mentions: List<User>?,
        author: User?,
        interactionUser: User?,
        channel: Channel?,
        guildMembers: Map<Long, GuildMember>,
        nicks: List<ChannelRecipientNick>? = null,
    ): Map<Long, String> {
        val result = HashMap<Long, String>((mentions?.size ?: 0) + 1)
        val effectiveNicks = nicks ?: channel?.nicks
        fun put(user: User) {
            result[user.id] = nickOrUsername(
                user,
                guildMembers[user.id],
                effectiveNicks,
                NicknameUtils.getNickname(channel?.guildId, channel?.id, user.id),
            )
        }
        mentions?.forEach(::put)
        author?.let(::put)
        interactionUser?.let(::put)
        return result
    }

    /**
     * Channel nick, then guild nick, then the account display name.
     *
     * The reference reaches this through `GuildMember.Companion.getNickOrUsername(user, member,
     * channel, nicks)`; the target's model only exposes the two-argument form, so the channel-nick
     * step lives here until that overload lands.
     */
    private fun nickOrUsername(
        user: User,
        guildMember: GuildMember?,
        nicks: List<ChannelRecipientNick>?,
        contextNickname: String?,
    ): String = user.getDisplayName(
        contextNickname ?: nicks?.firstOrNull { it.id == user.id }?.nick
            ?.takeUnless { com.discord.utilities.feature.FeatureFlags.disableServerProfilesEnabled() }
            ?: guildMember?.identityNick(),
    )

    /**
     * Which of the thirteen "user joined" greetings a join message shows.
     *
     * Chosen from the message's own creation time, so a given join message keeps the same greeting
     * forever and two people scrolling the same channel see the same text. The greetings are
     * English-only jokes, so every other language gets the first (neutral) one — that is a
     * deliberate reference behaviour, not a missing translation.
     */
    @StringRes
    fun getSystemMessageUserJoin(context: Context, messageId: Long): Int {
        val index = if (isEnglish(context)) {
            (TimeUtils.parseSnowflake(messageId) % WELCOME_MESSAGES.size).toInt()
        } else {
            0
        }
        return WELCOME_MESSAGES[index]
    }

    /**
     * Copy for a role-subscription purchase system message.
     *
     * Four variants, chosen by whether this is a renewal and whether the member has accrued whole
     * months worth mentioning. Also English-only: a non-English client — or a message that arrived
     * without its `role_subscription_data` — falls back to the generic "message could not be
     * loaded" string rather than rendering a half-empty sentence.
     *
     * [render] is the seam for callers that need clickable runs inside the sentence (the tier name
     * links to the subscription sheet). It defaults to plain `{token}` substitution; a caller with
     * a hook-aware renderer passes its own.
     */
    @JvmOverloads
    fun getSystemMessageRoleSubscriptionPurchase(
        context: Context,
        guildName: String?,
        authorName: String?,
        roleSubscriptionData: RoleSubscriptionData?,
        render: (Context, Int, Map<String, Any?>) -> CharSequence = ::defaultRender,
    ): CharSequence {
        if (roleSubscriptionData == null || !isEnglish(context)) {
            return context.getString(R.string.reply_quote_message_not_loaded)
        }

        val args = mutableMapOf<String, Any?>(
            "username" to authorName,
            "tierName" to roleSubscriptionData.tierName,
            "guildName" to guildName,
        )

        if (roleSubscriptionData.totalMonthsSubscribed > 0) {
            // The recovered plurals carry Discord's `{#}` / `{count}` token, not `%d`, so the
            // positional `getQuantityString(res, quantity, quantity)` overload would leave the
            // token visible. `I18nHooks.plural` substitutes both spellings.
            args["months"] = I18nHooks.plural(
                context,
                R.plurals.system_message_role_subscription_join_with_duration_mobile_months,
                roleSubscriptionData.totalMonthsSubscribed,
            )
            val stringRes = if (roleSubscriptionData.isRenewal) {
                R.string.system_message_role_subscription_renew_with_duration_mobile
            } else {
                R.string.system_message_role_subscription_join_with_duration_mobile
            }
            return render(context, stringRes, args)
        }

        val stringRes = if (roleSubscriptionData.isRenewal) {
            R.string.system_message_role_subscription_renew_mobile
        } else {
            R.string.system_message_role_subscription_join_mobile
        }
        return render(context, stringRes, args)
    }

    /**
     * The sticker attached to a "user joined" system message.
     *
     * Keyed on the *sum* of the message's and the viewer's creation times, so two people looking at
     * the same join message see different stickers while each one's own view stays stable.
     */
    fun getWelcomeSticker(meUserId: Long, messageId: Long): StickerPartial = WELCOME_STICKERS[
        ((TimeUtils.parseSnowflake(messageId) + TimeUtils.parseSnowflake(meUserId)) %
            WELCOME_STICKERS.size).toInt()
    ]

    /** Same viewer-varying selection as [getWelcomeSticker], for role-subscription purchases. */
    fun getRoleSubscriptionPurchaseSticker(meUserId: Long, messageId: Long): StickerPartial =
        ROLE_SUBSCRIPTION_PURCHASE_STICKERS[
            ((TimeUtils.parseSnowflake(messageId) + TimeUtils.parseSnowflake(meUserId)) %
                ROLE_SUBSCRIPTION_PURCHASE_STICKERS.size).toInt()
        ]

    private fun defaultRender(
        context: Context,
        @StringRes stringRes: Int,
        args: Map<String, Any?>,
    ): CharSequence = context.i18nRender(stringRes, *args.toList().toTypedArray())

    private fun isEnglish(context: Context): Boolean =
        LocaleManager().getPrimaryLocale(context).language == Locale("en").language

    private val WELCOME_MESSAGES = intArrayOf(
        R.string.system_message_guild_member_join_001,
        R.string.system_message_guild_member_join_002,
        R.string.system_message_guild_member_join_003,
        R.string.system_message_guild_member_join_004,
        R.string.system_message_guild_member_join_005,
        R.string.system_message_guild_member_join_006,
        R.string.system_message_guild_member_join_007,
        R.string.system_message_guild_member_join_008,
        R.string.system_message_guild_member_join_009,
        R.string.system_message_guild_member_join_010,
        R.string.system_message_guild_member_join_011,
        R.string.system_message_guild_member_join_012,
        R.string.system_message_guild_member_join_013,
    )

    /**
     * Hardcoded sticker ids, with their alt text as the name.
     *
     * These are official stickers the client references directly rather than fetching, so the ids
     * are literals in the reference too.
     */
    private val WELCOME_STICKERS = arrayOf(
        StickerPartial(749054660769218631L, StickerFormatType.LOTTIE, "Wumpus waves hello"),
        StickerPartial(
            751606379340365864L,
            StickerFormatType.LOTTIE,
            "Nelly peeks around a wall and waves hello",
        ),
        StickerPartial(754108890559283200L, StickerFormatType.LOTTIE, "Clyde cheerfully waving"),
        StickerPartial(781291131828699156L, StickerFormatType.APNG, "Choco waves in greeting"),
        StickerPartial(816087792291282944L, StickerFormatType.LOTTIE, "Doggo spins and greets you"),
        StickerPartial(
            819128604311027752L,
            StickerFormatType.LOTTIE,
            "Peach raising her arms in distress",
        ),
    )

    private val ROLE_SUBSCRIPTION_PURCHASE_STICKERS = arrayOf(
        StickerPartial(
            781323471249604648L,
            StickerFormatType.APNG,
            "Cheerful Choco jumps out of gift box",
        ),
        StickerPartial(
            781324642736144424L,
            StickerFormatType.APNG,
            "Cheerful Choco preens against window",
        ),
        StickerPartial(781323769960202280L, StickerFormatType.APNG, "Cheerful Choco sparkles"),
        StickerPartial(781324722394103808L, StickerFormatType.APNG, "Cheerful Choco cheers"),
        StickerPartial(813951723822645278L, StickerFormatType.APNG, "Cheerful Choco gives thumbs up"),
    )
}
