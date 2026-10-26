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
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.analytics.Traits
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.uri.UriHandler
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.ThemedColor
import com.discord.views.sticker.StickerView
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.servers.guildboost.WidgetGuildBoost
import com.discord.views.VitoImageView
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
    private val roleSubscriptionAvatar: VitoImageView =
        itemView.findViewById(R.id.role_subscription_purchaser_avatar)
    private val roleSubscriptionAvatarChangeDetector = MGImages.DistinctChangeDetector()
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
            changeDetector = roleSubscriptionAvatarChangeDetector,
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
                val guildId = adapter.data.guildId
                AnalyticsTracker.guildBoostPromotionOpened(
                    guildId,
                    Traits.Location(
                        section = Traits.Location.Section.CHANNEL_TEXT_AREA,
                        _object = Traits.Location.Obj.BOOST_GEM_ICON,
                    ),
                )
                WidgetGuildBoost.create(systemText.context, guildId)
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
            onLongClick = { adapter.eventHandler.onMessageAuthorLongClicked(message, adapter.data.guildId) },
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

            TYPE_STAGE_START -> I18nHooks.format(
                context,
                R.string.system_message_stage_start_mobile,
                mapOf("username" to authorName, "topic" to message.content),
                nameHooks,
            )

            TYPE_STAGE_END -> I18nHooks.format(
                context,
                R.string.system_message_stage_end_mobile,
                mapOf("username" to authorName, "topic" to message.content),
                nameHooks,
            )

            TYPE_STAGE_TOPIC -> I18nHooks.format(
                context,
                R.string.system_message_stage_topic_mobile,
                mapOf("username" to authorName, "topic" to message.content),
                nameHooks,
            )

            TYPE_STAGE_SPEAKER -> I18nHooks.format(
                context,
                R.string.system_message_stage_speaker_mobile,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_STAGE_RAISE_HAND -> I18nHooks.format(
                context,
                R.string.system_message_stage_raise_hand_mobile,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_PRIVATE_CHANNEL_INTEGRATION_ADDED,
            TYPE_PRIVATE_CHANNEL_INTEGRATION_REMOVED,
            -> privateChannelIntegrationMessage(context, message, authorName, nameHooks)

            // The alert-mode notice is authored by AutoMod, whose end time travels in the body.
            TYPE_GUILD_INCIDENT_ALERT_MODE_ENABLED -> I18nHooks.format(
                context,
                R.string.guild_server_lockdown_enabled_system_message_mobile_short,
                mapOf("username" to authorName, "time" to alertModeEndTime(message)),
                nameHooks,
            )

            TYPE_GUILD_INCIDENT_ALERT_MODE_DISABLED -> I18nHooks.format(
                context,
                R.string.guild_server_lockdown_disabled_system_message_mobile,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_GUILD_INCIDENT_REPORT_RAID -> I18nHooks.format(
                context,
                R.string.guild_raid_report_system_message_mobile,
                mapOf("username" to authorName, "guildName" to guildName),
                nameHooks,
            )

            TYPE_GUILD_INCIDENT_REPORT_FALSE_ALARM -> I18nHooks.format(
                context,
                R.string.guild_report_false_alarm_system_message_mobile_short,
                mapOf("username" to authorName),
                nameHooks,
            )

            TYPE_GUILD_JOIN_REQUEST_ACCEPT,
            TYPE_GUILD_JOIN_REQUEST_REJECT,
            TYPE_GUILD_JOIN_REQUEST_WITHDRAWN,
            -> joinRequestMessage(context, message)

            TYPE_GUILD_APPLICATION_PREMIUM_SUBSCRIPTION -> I18nHooks.format(
                context,
                if (message.application != null) {
                    R.string.system_message_application_subscription_purchase_mobile
                } else {
                    R.string.system_message_application_subscription_purchase_from_deleted_application_mobile
                },
                mapOf("username" to authorName, "applicationName" to message.application?.name),
                nameHooks,
            )

            TYPE_CHANNEL_LINKED_TO_LOBBY -> I18nHooks.format(
                context,
                R.string.system_message_channel_linked_to_lobby_new_mobile,
                mapOf("username" to authorName, "applicationName" to messageApplicationName(message)),
                nameHooks +
                    ("applicationNameOnClick" to Hook(spans = listOf(StyleSpan(Typeface.BOLD)))) +
                    ("urlOnClick" to linkHook(context, LINKED_LOBBIES_HELP_URL)),
            )

            TYPE_IN_GAME_MESSAGE_NUX -> I18nHooks.format(
                context,
                R.string.system_message_in_game_message_nux_mobile,
                mapOf("username" to authorName, "gameName" to messageApplicationName(message)),
                nameHooks + ("urlOnClick" to linkHook(context, SOCIAL_LAYER_HELP_URL)),
            )

            // Only the guild-product flavour has a notice; the reference renders nothing else.
            TYPE_PURCHASE_NOTIFICATION -> I18nHooks.format(
                context,
                R.string.system_message_guild_product_purchase_mobile,
                mapOf(
                    "username" to authorName,
                    "productName" to message.purchaseNotification?.guildProductPurchase?.productName,
                ),
                nameHooks,
            )

            TYPE_VOICE_SESSION -> voiceSessionMessage(context, message, authorName, authorHook, otherRoleColor)

            TYPE_FRIEND_REQUEST_ACCEPTED -> I18nHooks.format(
                context,
                if (message.author?.id == StoreStream.getUsers().getMe().id) {
                    R.string.system_message_friend_request_accepted_requester
                } else {
                    R.string.system_message_friend_request_accepted_accepter
                },
                mapOf("username" to authorName),
                nameHooks,
            )

            else -> context.getString(R.string.reply_quote_message_not_loaded)
        }
    }

    /** The message carries the application inline, or names one the store already holds. */
    private fun messageApplicationName(message: Message): String? =
        message.application?.name
            ?: message.applicationId?.let { StoreStream.getApplication().getApplication(it)?.name }

    private fun linkHook(context: Context, url: String) = Hook(
        spans = listOf(ForegroundColorSpan(ThemedColor.resolve(context, R.attr.colorTextLink))),
        onClick = { UriHandler.handle(context, url) },
    )

    /**
     * A session with no end timestamp is still running, so it reads as an invitation rather than
     * a summary. Once it ends the participants are named, most-affine first, up to three before
     * the rest collapse into a count.
     */
    private fun voiceSessionMessage(
        context: Context,
        message: Message,
        authorName: String?,
        authorHook: Hook,
        otherRoleColor: Int,
    ): CharSequence {
        if (message.call?.endedTimestamp == null) {
            return I18nHooks.format(
                context,
                R.string.voice_session_started_system_message_mobile,
                mapOf("username" to authorName),
                mapOf("usernameOnClick" to authorHook),
            )
        }
        val participants = voiceSessionParticipants(message)
        val duration = TimeUtils.toFriendlyStringSimple(message.getCallDuration())
        val args = mutableMapOf<String, Any?>("username" to authorName, "duration" to duration)
        val hooks = mutableMapOf("usernameOnClick" to authorHook)
        participants.take(2).forEachIndexed { index, user ->
            args["username${index + 2}"] = user.username
            hooks["username${index + 2}OnClick"] = Hook(
                spans = listOf(StyleSpan(Typeface.BOLD), ForegroundColorSpan(otherRoleColor)),
                onClick = {
                    adapter.eventHandler.onUserMentionClicked(
                        user.id,
                        adapter.data.channelId,
                        adapter.data.guildId,
                    )
                },
            )
        }
        // The author counts as a participant, which is what the reference numbers these by.
        val res = when (participants.size + 1) {
            1 -> R.string.voice_session_ended_system_message_mobile_one
            2 -> R.string.voice_session_ended_system_message_mobile_two
            3 -> R.string.voice_session_ended_system_message_mobile_three
            else -> {
                args["otherCount"] = participants.size - 1
                R.string.voice_session_ended_system_message_mobile_many
            }
        }
        return I18nHooks.format(context, res, args, hooks)
    }

    /** Everyone but the author, ordered the way the reference orders them: by affinity. */
    private fun voiceSessionParticipants(message: Message): List<User> {
        val authorId = message.author?.id
        val affinities = StoreStream.getUserAffinities().getUserAffinities()
            .userAffinities.associate { it.userId to it.affinity }
        val users = StoreStream.getUsers().getUsers()
        return message.call?.participants.orEmpty()
            .filterNot { it == authorId }
            .mapNotNull(users::get)
            .sortedByDescending { affinities[it.id] ?: 0f }
    }

    /**
     * The applicant and the server are resolved from the interview channel the notice was posted
     * in, which shares its snowflake with the application. A moderator reviewing someone else's
     * application holds no record of it, and the reference has anonymous copy for exactly that.
     */
    private fun joinRequestMessage(context: Context, message: Message): CharSequence {
        val request = StoreStream.getGuildJoinRequests().getRequestById(message.channelId)
        val guildName = request?.guildId?.let { guildId ->
            StoreStream.getGuilds().getGuild(guildId)?.name
                ?: StoreStream.getGuildJoinRequests().getPendingGuild(guildId)?.name
        }
        val username = request?.userId?.let { StoreStream.getUsers().getUsers()[it]?.username }
            ?: request?.user?.username
        if (username == null || guildName == null) {
            return context.getString(
                when (message.type) {
                    TYPE_GUILD_JOIN_REQUEST_ACCEPT ->
                        R.string.guild_join_request_approved_system_message_anonymous
                    TYPE_GUILD_JOIN_REQUEST_REJECT ->
                        R.string.guild_join_request_rejected_system_message_anonymous
                    else -> R.string.guild_join_request_withdrawn_system_message_anonymous
                },
            )
        }
        return I18nHooks.format(
            context,
            when (message.type) {
                TYPE_GUILD_JOIN_REQUEST_ACCEPT -> R.string.guild_join_request_approved_system_message_mobile
                TYPE_GUILD_JOIN_REQUEST_REJECT -> R.string.guild_join_request_rejected_system_message_mobile
                else -> R.string.guild_join_request_withdrawn_system_message_mobile
            },
            mapOf("username" to username, "guildName" to guildName),
        )
    }

    /** A deleted application has no name left to show, which the reference has its own copy for. */
    private fun privateChannelIntegrationMessage(
        context: Context,
        message: Message,
        authorName: String?,
        nameHooks: Map<String, Hook>,
    ): CharSequence {
        val applicationName = message.application?.name
        val added = message.type == TYPE_PRIVATE_CHANNEL_INTEGRATION_ADDED
        val res = when {
            applicationName == null && added -> R.string.private_channel_integration_added_deleted_application_mobile
            applicationName == null -> R.string.private_channel_integration_removed_deleted_application_mobile
            added -> R.string.private_channel_integration_added_mobile
            else -> R.string.private_channel_integration_removed_mobile
        }
        return I18nHooks.format(
            context,
            res,
            mapOf("username" to authorName, "otherUsername" to applicationName),
            nameHooks + ("helpCenterLink" to linkHook(context, PRIVATE_CHANNEL_INTEGRATIONS_HELP_URL)),
        )
    }

    /** `content` carries the ISO instant the alert mode lapses, or is empty when it does not. */
    private fun alertModeEndTime(message: Message): String {
        val content = message.content?.takeUnless(String::isBlank) ?: return ""
        val millis = TimeUtils.parseUTCDate(content).takeIf { it > 0L } ?: return ""
        return DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(millis))
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
        TYPE_STAGE_START, TYPE_STAGE_END, TYPE_STAGE_TOPIC -> R.drawable.ic_stage_audience
        TYPE_STAGE_SPEAKER -> R.drawable.ic_stage_move_to_speaker
        TYPE_STAGE_RAISE_HAND -> R.drawable.ic_stage_raised_hand
        TYPE_PRIVATE_CHANNEL_INTEGRATION_ADDED -> R.drawable.ic_group_join
        TYPE_PRIVATE_CHANNEL_INTEGRATION_REMOVED -> R.drawable.ic_group_leave
        TYPE_GUILD_INCIDENT_ALERT_MODE_ENABLED, TYPE_GUILD_INCIDENT_REPORT_RAID ->
            R.drawable.ic_shieldstar_24dp
        TYPE_GUILD_INCIDENT_ALERT_MODE_DISABLED, TYPE_GUILD_INCIDENT_REPORT_FALSE_ALARM ->
            R.drawable.ic_check_green_24dp
        TYPE_GUILD_JOIN_REQUEST_ACCEPT -> R.drawable.ic_application_status_approved
        TYPE_GUILD_JOIN_REQUEST_REJECT -> R.drawable.ic_application_status_rejected
        TYPE_GUILD_JOIN_REQUEST_WITHDRAWN -> R.drawable.ic_application_status_pending
        TYPE_FRIEND_REQUEST_ACCEPTED -> R.drawable.ic_group_join
        TYPE_VOICE_SESSION -> R.drawable.ic_call_24dp
        TYPE_GUILD_APPLICATION_PREMIUM_SUBSCRIPTION,
        TYPE_PURCHASE_NOTIFICATION,
        -> R.drawable.ic_user_premium_guild_subscription
        TYPE_CHANNEL_LINKED_TO_LOBBY -> R.drawable.ic_link_white_24dp
        TYPE_IN_GAME_MESSAGE_NUX -> R.drawable.ic_group_join
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
        private const val TYPE_STAGE_START = 27
        private const val TYPE_STAGE_END = 28
        private const val TYPE_STAGE_SPEAKER = 29
        private const val TYPE_STAGE_RAISE_HAND = 30
        private const val TYPE_STAGE_TOPIC = 31
        private const val TYPE_PRIVATE_CHANNEL_INTEGRATION_ADDED = 33
        private const val TYPE_PRIVATE_CHANNEL_INTEGRATION_REMOVED = 34
        private const val TYPE_GUILD_INCIDENT_ALERT_MODE_ENABLED = 36
        private const val TYPE_GUILD_INCIDENT_ALERT_MODE_DISABLED = 37
        private const val TYPE_GUILD_INCIDENT_REPORT_RAID = 38
        private const val TYPE_GUILD_INCIDENT_REPORT_FALSE_ALARM = 39
        private const val TYPE_GUILD_JOIN_REQUEST_ACCEPT = 52
        private const val TYPE_GUILD_JOIN_REQUEST_REJECT = 53
        private const val TYPE_GUILD_JOIN_REQUEST_WITHDRAWN = 54
        private const val TYPE_GUILD_APPLICATION_PREMIUM_SUBSCRIPTION = 32
        private const val TYPE_PURCHASE_NOTIFICATION = 44
        private const val TYPE_CHANNEL_LINKED_TO_LOBBY = 49
        private const val TYPE_IN_GAME_MESSAGE_NUX = 51
        private const val TYPE_VOICE_SESSION = 65
        private const val TYPE_FRIEND_REQUEST_ACCEPTED = 67

        private const val PRIVATE_CHANNEL_INTEGRATIONS_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/15104189280151"
        private const val LINKED_LOBBIES_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/29216887858967"
        private const val SOCIAL_LAYER_HELP_URL =
            "https://support.discord.com/hc/en-us/articles/27893392334359"

        /** Local placeholder produced when a thread's starter message cannot be fetched. */
        private const val TYPE_THREAD_STARTER_NOT_LOADED = -7

        /**
         * Reference: `WidgetChatListAdapterItemAutoModSystemMessageEmbedKt`. Inlined here so this
         * row does not depend on a file another wave owns.
         */
    }
}
