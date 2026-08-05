package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.graphics.Typeface
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.discord.R
import com.discord.i18n.Hook
import com.discord.i18n.I18nHooks
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.models.user.CoreUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.ThemedColor
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.facebook.drawee.view.SimpleDraweeView
import kotlin.math.roundToInt

/**
 * Every message Discord generates itself: joins and leaves, channel renames, pins, boosts,
 * discovery notices, thread creation, AutoMod blocks and role-subscription purchases.
 *
 * There is no message body to render — the text is built entirely from a translated template plus
 * the names and colours of the people involved, which is why this row does not go through the
 * markdown parser the way [WidgetChatListAdapterItemMessage] does.
 */
class WidgetChatListAdapterItemSystemMessage(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_system, adapter) {

    private val threadEmbedSpine: ImageView =
        itemView.findViewById(R.id.chat_list_adapter_item_thread_embed_spine)
    private val roleSubscriptionCard: ConstraintLayout =
        itemView.findViewById(R.id.role_subscription_purchase_welcome_card)
    private val roleSubscriptionAvatar: SimpleDraweeView =
        itemView.findViewById(R.id.role_subscription_purchaser_avatar)
    private val roleSubscriptionUsername: TextView =
        itemView.findViewById(R.id.role_subscription_purchaser_username)
    private val systemIcon: ImageView = itemView.findViewById(R.id.system_icon)
    private val systemIconAutomod: ImageView = itemView.findViewById(R.id.system_icon_automod)
    private val systemText: LinkifiedTextView = itemView.findViewById(R.id.system_text)
    private val systemTimestamp: TextView = itemView.findViewById(R.id.system_timestamp)
    private val welcomeCtaButton: LinearLayout =
        itemView.findViewById(R.id.system_welcome_cta_button)
    private val welcomeCtaSticker: StickerView =
        itemView.findViewById(R.id.system_welcome_cta_button_sticker)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as MessageEntry
        val message = entry.message
        val authorName = entry.authorDisplayName()
        val guild = adapter.data.guild

        val firstMentionedUserId = message.mentions.orEmpty().firstOrNull()?.id
        val firstMentionedUserName = firstMentionedUserId?.let(entry.nickOrUsernames::get)

        val defaultColor = ThemedColor.resolve(systemText.context, R.attr.colorHeaderPrimary)
        val authorRoleColor = GuildMember.getColor(entry.author, defaultColor)
        val otherRoleColor = GuildMember.getColor(entry.firstMentionedUser, defaultColor)

        systemText.text = systemMessage(
            message = message,
            context = systemText.context,
            authorName = authorName,
            authorRoleColor = authorRoleColor,
            firstMentionedUserId = firstMentionedUserId,
            firstMentionedUserName = firstMentionedUserName,
            otherRoleColor = otherRoleColor,
            guildName = guild?.name,
            isFlagged = isAutoModFlagged(message),
            roleSubscriptionListingId = message.roleSubscriptionData?.roleSubscriptionListingId,
        )

        itemView.setOnClickListener { onRowClicked(message) }
        itemView.setOnLongClickListenerConsumeClick {
            adapter.eventHandler.onMessageLongClicked(message, systemText.text, false)
        }

        systemTimestamp.text = TimeUtils.toReadableTimeString(
            itemView.context,
            message.timestamp?.dateTimeMillis ?: 0L,
        )

        systemIcon.setImageResource(iconFor(message))
        systemIconAutomod.visibility =
            if (message.type == TYPE_AUTO_MODERATION_ACTION) View.VISIBLE else View.GONE

        // The spine connects a message to the thread embed underneath it; a thread's own starter
        // message is already inside the thread, so it gets none.
        val showSpine = message.hasThread() && !entry.isThreadStarterMessage
        threadEmbedSpine.visibility = if (showSpine) View.VISIBLE else View.GONE

        configureWelcomeCta(entry, message)
        configureRoleSubscriptionCard(entry, message, authorName, guild?.id)
    }

    private fun configureWelcomeCta(entry: MessageEntry, message: Message) {
        val ctaData = entry.ctaData
        welcomeCtaButton.visibility = if (ctaData != null) View.VISIBLE else View.GONE
        if (ctaData == null) return
        welcomeCtaSticker.setSticker(ctaData.sticker)
        welcomeCtaSticker.clearBackgrounds()
        welcomeCtaButton.setOnClickListener {
            adapter.eventHandler.onSystemMessageCtaClicked(message, ctaData.channel, ctaData.sticker)
        }
    }

    /**
     * The lanyard card is a welcome, not a receipt: it is only shown for a member's *first* month,
     * so a renewal notice renders as plain text.
     */
    private fun configureRoleSubscriptionCard(
        entry: MessageEntry,
        message: Message,
        authorName: String?,
        guildId: Long?,
    ) {
        val subscription = message.roleSubscriptionData
        val isFirstMonth = subscription != null && subscription.totalMonthsSubscribed <= 1
        roleSubscriptionCard.visibility = if (isFirstMonth) View.VISIBLE else View.GONE
        if (!isFirstMonth) return

        roleSubscriptionUsername.text = authorName
        val author = message.author ?: return
        IconUtils.setIcon(
            roleSubscriptionAvatar,
            CoreUser(author),
            R.dimen.avatar_size_large,
            guildMember = entry.author,
        )
        roleSubscriptionAvatar.setOnClickListener {
            if (guildId != null) {
                adapter.eventHandler.onMessageAuthorAvatarClicked(message, guildId)
            }
        }
    }

    /** Where tapping the row takes you depends on what the notice is about. */
    private fun onRowClicked(message: Message) {
        when (message.type) {
            TYPE_GUILD_MEMBER_JOIN, TYPE_CHANNEL_FOLLOW_ADD ->
                adapter.eventHandler.onMessageAuthorNameClicked(message, adapter.data.guildId)
            TYPE_CHANNEL_PINNED_MESSAGE -> adapter.eventHandler.onOpenPinsClicked(message)
            TYPE_THREAD_CREATED -> {
                val threadChannelId = message.messageReference?.channelId
                if (threadChannelId != null) {
                    StoreStream.selectChannel(adapter.data.guildId, threadChannelId)
                }
            }
            TYPE_POLL_RESULT -> {
                val reference = message.messageReference
                if (reference?.channelId != null && reference.messageId != null) {
                    StoreStream.getMessagesLoader().jumpToMessage(reference.channelId, reference.messageId)
                }
            }
            TYPE_GUILD_MEMBER_SUBSCRIBED,
            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_1,
            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_2,
            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_3,
            -> {
                // TODO(parity): the reference tracks `guildBoostPromotionOpened` and opens
                //  WidgetGuildBoost here; neither AnalyticsTracker nor that screen exists yet.
            }
        }
    }

    /**
     * AutoMod notices come in two flavours, "blocked" and "flagged", told apart by whether the
     * attached embed carries a `flagged_message_id` field.
     */
    private fun isAutoModFlagged(message: Message): Boolean {
        val embed = message.embeds.orEmpty().firstOrNull() ?: return false
        return embed.fields.orEmpty().any { it.name == AUTOMOD_EMBED_FLAGGED_MESSAGE_ID }
    }

    private fun systemMessage(
        message: Message,
        context: Context,
        authorName: String?,
        authorRoleColor: Int,
        firstMentionedUserId: Long?,
        firstMentionedUserName: String?,
        otherRoleColor: Int,
        guildName: String?,
        isFlagged: Boolean,
        roleSubscriptionListingId: Long?,
    ): CharSequence {
        // Hooks take their display text from the template itself (`[!!{username}!!](usernameOnClick)`),
        // so only the styling and the click target have to be supplied here.
        val authorHook = Hook(
            spans = listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(authorRoleColor)),
            onClick = { adapter.eventHandler.onMessageAuthorNameClicked(message, adapter.data.guildId) },
        )
        val otherHook = Hook(
            spans = listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(otherRoleColor)),
            onClick = firstMentionedUserId?.let { userId ->
                {
                    adapter.eventHandler.onUserMentionClicked(
                        userId,
                        adapter.data.channelId,
                        adapter.data.guildId,
                    )
                }
            },
        )
        val nameHooks = mapOf("usernameOnClick" to authorHook, "otherUsernameOnClick" to otherHook)

        return when (message.type) {
            TYPE_RECIPIENT_ADD -> I18nHooks.format(
                context,
                R.string.system_message_recipient_add,
                mapOf("username" to authorName, "otherUsername" to firstMentionedUserName),
                nameHooks,
            )

            TYPE_RECIPIENT_REMOVE ->
                if (firstMentionedUserId == message.author?.id) {
                    I18nHooks.format(
                        context,
                        R.string.system_message_recipient_remove_self,
                        mapOf("username" to authorName),
                        nameHooks,
                    )
                } else {
                    I18nHooks.format(
                        context,
                        R.string.system_message_recipient_remove,
                        mapOf("username" to authorName, "otherUsername" to firstMentionedUserName),
                        nameHooks,
                    )
                }

            TYPE_CHANNEL_NAME_CHANGE -> I18nHooks.format(
                context,
                R.string.system_message_channel_name_change,
                mapOf("username" to authorName, "channelName" to message.content),
                nameHooks,
            )

            TYPE_CHANNEL_ICON_CHANGE -> I18nHooks.format(
                context,
                R.string.system_message_channel_icon_change,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_CHANNEL_PINNED_MESSAGE -> I18nHooks.format(
                context,
                R.string.system_message_pinned_message_no_cta,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_GUILD_MEMBER_JOIN -> I18nHooks.format(
                context,
                MessageUtils.getSystemMessageUserJoin(context, message.id),
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_GUILD_MEMBER_SUBSCRIBED -> {
                // The boost count travels in the message body as a bare integer.
                val boostCount = message.content?.toIntOrNull() ?: 1
                if (boostCount > 1) {
                    I18nHooks.format(
                        context,
                        R.string.system_message_guild_member_subscribed_many,
                        mapOf("username" to authorName, "numSubscriptions" to boostCount),
                        nameHooks,
                    )
                } else {
                    I18nHooks.format(
                        context,
                        R.string.system_message_guild_member_subscribed,
                        mapOf("username" to authorName),
                        nameHooks,
                    )
                }
            }

            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_1,
            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_2,
            TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_3,
            -> I18nHooks.format(
                context,
                R.string.system_message_guild_member_subscribed_achieved_tier,
                mapOf(
                    "username" to authorName,
                    "guildName" to guildName,
                    "newTierName" to context.getString(tierNameFor(message.type)),
                ),
                nameHooks,
            )

            TYPE_CHANNEL_FOLLOW_ADD -> I18nHooks.format(
                context,
                R.string.system_message_channel_follow_add,
                mapOf("username" to authorName, "webhookName" to message.content),
                nameHooks + ("webhookNameHook" to Hook(
                    spans = listOf(StyleSpan(Typeface.BOLD)),
                )),
            )

            TYPE_GUILD_DISCOVERY_DISQUALIFIED ->
                context.getString(R.string.system_message_guild_discovery_disqualified_mobile)

            TYPE_GUILD_DISCOVERY_REQUALIFIED ->
                context.getString(R.string.system_message_guild_discovery_requalified)

            TYPE_GUILD_DISCOVERY_GRACE_PERIOD_INITIAL_WARNING ->
                context.getString(R.string.system_message_guild_discovery_grace_period_initial_warning)

            TYPE_GUILD_DISCOVERY_GRACE_PERIOD_FINAL_WARNING ->
                context.getString(R.string.system_message_guild_discovery_grace_period_final_warning)

            TYPE_THREAD_CREATED -> I18nHooks.format(
                context,
                R.string.system_message_thread_created_mobile,
                mapOf("actorName" to authorName, "threadName" to message.content),
                mapOf(
                    "actorHook" to authorHook,
                    "threadOnClick" to Hook(
                        spans = listOf(StyleSpan(Typeface.BOLD)),
                        onClick = {
                            message.messageReference?.channelId?.let { channelId ->
                                StoreStream.selectChannel(adapter.data.guildId, channelId)
                            }
                        },
                    ),
                ),
            )

            TYPE_THREAD_STARTER_NOT_LOADED ->
                context.getString(R.string.thread_starter_message_not_loaded)

            TYPE_AUTO_MODERATION_ACTION -> I18nHooks.format(
                context,
                if (isFlagged) {
                    R.string.guild_automod_system_message_text_flagged
                } else {
                    R.string.guild_automod_system_message_text
                },
                hooks = mapOf(
                    "autoModHook" to Hook(spans = listOf(StyleSpan(Typeface.BOLD))),
                    "systemHook" to Hook(spans = listOf(StyleSpan(Typeface.BOLD))),
                ),
            )

            TYPE_ROLE_SUBSCRIPTION_PURCHASE -> roleSubscriptionPurchaseMessage(
                context,
                message,
                authorName,
                guildName,
                roleSubscriptionListingId,
                authorHook,
            )

            TYPE_POLL_RESULT -> pollResultMessage(context, message, authorName)

            else -> context.getString(R.string.reply_quote_message_not_loaded)
        }
    }

    /** The purchase notice links the tier name back to the server's subscription listing. */
    private fun roleSubscriptionPurchaseMessage(
        context: Context,
        message: Message,
        authorName: String?,
        guildName: String?,
        roleSubscriptionListingId: Long?,
        authorHook: Hook,
    ): CharSequence {
        val hooks = mapOf(
            "usernameOnClick" to authorHook,
            "roleSubscriptionOnClick" to Hook(
                spans = listOf(ForegroundColorSpan(ThemedColor.resolve(context, R.attr.colorTextLink))),
                onClick = roleSubscriptionListingId?.let { listingId ->
                    {
                        adapter.onRoleSubscriptionPurchaseSystemMessageClicked(listingId, message.id)
                    }
                },
            ),
        )
        // `render` is the seam `MessageUtils` provides for exactly this: it keeps the clickable tier
        // name and author name, which the default plain-substitution renderer cannot express.
        return MessageUtils.getSystemMessageRoleSubscriptionPurchase(
            context,
            guildName,
            authorName,
            message.roleSubscriptionData,
            render = { ctx, res, args -> I18nHooks.format(ctx, res, args, hooks) },
        )
    }

    private fun tierNameFor(messageType: Int?): Int = when (messageType) {
        TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_2 -> R.string.premium_guild_tier_2
        TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_3 -> R.string.premium_guild_tier_3
        else -> R.string.premium_guild_tier_1
    }

    private fun pollResultMessage(context: Context, message: Message, authorName: String?): String {
        val fields = message.embeds.orEmpty().firstOrNull()?.fields.orEmpty()
            .associate { it.name.orEmpty() to it.value.orEmpty() }
        val question = fields["poll_question_text"].orEmpty()
        val winner = fields["victor_answer_text"]
        val winnerVotes = fields["victor_answer_votes"]?.toIntOrNull() ?: 0
        val totalVotes = (fields["total_votes"]?.toIntOrNull() ?: 0).coerceAtLeast(1)
        val percent = (winnerVotes * 100.0 / totalVotes).roundToInt()
        return when {
            winnerVotes == 0 -> context.getString(R.string.poll_result_no_votes, authorName.orEmpty(), question)
            winner == null -> context.getString(R.string.poll_result_draw, authorName.orEmpty(), question, percent)
            else -> context.getString(
                R.string.poll_result_winner,
                authorName.orEmpty(),
                question,
                winner,
                percent,
            )
        }
    }

    private fun iconFor(message: Message): Int = when (message.type) {
        TYPE_RECIPIENT_ADD,
        TYPE_GUILD_MEMBER_JOIN,
        TYPE_CHANNEL_FOLLOW_ADD,
        TYPE_ROLE_SUBSCRIPTION_PURCHASE,
        -> R.drawable.ic_group_join
        TYPE_RECIPIENT_REMOVE -> R.drawable.ic_group_leave
        TYPE_CHANNEL_NAME_CHANGE, TYPE_CHANNEL_ICON_CHANGE -> R.drawable.ic_group_edit
        TYPE_CHANNEL_PINNED_MESSAGE -> R.drawable.ic_channel_pinned_message
        TYPE_GUILD_MEMBER_SUBSCRIBED,
        TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_1,
        TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_2,
        TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_3,
        -> R.drawable.ic_user_premium_guild_subscription
        TYPE_GUILD_DISCOVERY_DISQUALIFIED -> R.drawable.ic_x_red_24dp
        TYPE_GUILD_DISCOVERY_REQUALIFIED -> R.drawable.ic_check_green_24dp
        TYPE_GUILD_DISCOVERY_GRACE_PERIOD_INITIAL_WARNING,
        TYPE_GUILD_DISCOVERY_GRACE_PERIOD_FINAL_WARNING,
        -> R.drawable.ic_warning_circle_24dp
        TYPE_THREAD_CREATED -> R.drawable.ic_thread
        TYPE_POLL_RESULT -> R.drawable.ic_sort_white_24dp
        else -> R.drawable.ic_x_red_24dp
    }

    private companion object {
        private const val TYPE_RECIPIENT_ADD = 1
        private const val TYPE_RECIPIENT_REMOVE = 2
        private const val TYPE_CHANNEL_NAME_CHANGE = 4
        private const val TYPE_CHANNEL_ICON_CHANGE = 5
        private const val TYPE_CHANNEL_PINNED_MESSAGE = 6
        private const val TYPE_GUILD_MEMBER_JOIN = 7
        private const val TYPE_GUILD_MEMBER_SUBSCRIBED = 8
        private const val TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_1 = 9
        private const val TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_2 = 10
        private const val TYPE_GUILD_MEMBER_SUBSCRIBED_TIER_3 = 11
        private const val TYPE_CHANNEL_FOLLOW_ADD = 12
        private const val TYPE_GUILD_DISCOVERY_DISQUALIFIED = 14
        private const val TYPE_GUILD_DISCOVERY_REQUALIFIED = 15
        private const val TYPE_GUILD_DISCOVERY_GRACE_PERIOD_INITIAL_WARNING = 16
        private const val TYPE_GUILD_DISCOVERY_GRACE_PERIOD_FINAL_WARNING = 17
        private const val TYPE_THREAD_CREATED = 18
        private const val TYPE_AUTO_MODERATION_ACTION = 24
        private const val TYPE_ROLE_SUBSCRIPTION_PURCHASE = 25
        private const val TYPE_POLL_RESULT = 46

        /** Local placeholder produced when a thread's starter message cannot be fetched. */
        private const val TYPE_THREAD_STARTER_NOT_LOADED = -7

        /**
         * Reference: `WidgetChatListAdapterItemAutoModSystemMessageEmbedKt`. Inlined here so this
         * row does not depend on a file another wave owns.
         */
    }
}
