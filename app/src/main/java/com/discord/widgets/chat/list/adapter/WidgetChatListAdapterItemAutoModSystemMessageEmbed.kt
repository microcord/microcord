package com.discord.widgets.chat.list.adapter

import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.style.BackgroundColorSpan
import android.view.View
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.databinding.WidgetChatListAdapterItemAutoModSystemMessageEmbedBinding
import com.discord.i18n.FormatUtils
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MarkupEligibility
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.time.TimeUtils
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.AutoModSystemMessageEmbedEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.settings.profile.WidgetEditUserOrGuildMemberProfile

class WidgetChatListAdapterItemAutoModSystemMessageEmbed(
    adapter: WidgetChatListAdapter,
) : WidgetChatListItem(R.layout.widget_chat_list_adapter_item_auto_mod_system_message_embed, adapter) {
    private val binding = WidgetChatListAdapterItemAutoModSystemMessageEmbedBinding.bind(itemView)

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as AutoModSystemMessageEmbedEntry
        val context = entry.embed.extractAutoModContext()

        configureAuthor(entry)
        binding.headerText.text = headerText(context)
        binding.timestamp.text = TimeUtils.toReadableTimeString(
            binding.root.context,
            entry.message.timestamp?.dateTimeMillis ?: 0L,
            adapter.clock,
        )
        processMessageText(entry, context)
        configureDetails(context)
        configureNotification(context.notification)
        configureFooter(entry, context)
        configureActions(entry, context)
    }

    private fun configureAuthor(entry: AutoModSystemMessageEmbedEntry) {
        val author = entry.message.author
        val guildId = entry.message.guildId ?: entry.channel.guildId
        val isMember = author != null && StoreStream.getGuilds().getMember(guildId, author.id) != null
        binding.memberStatusText.visibility = if (author != null && !isMember) View.VISIBLE else View.GONE
        binding.chatListAdapterItemTextAvatar.setImageURI(null as String?)
        binding.username.text = null
        if (author != null) {
            val coreUser = CoreUser(author)
            IconUtils.setIcon(
                binding.chatListAdapterItemTextAvatar,
                coreUser,
                R.dimen.avatar_size_chat,
                guildMember = entry.author,
            )
            binding.username.text = GuildMember.getNickOrUsername(entry.author, coreUser)
        }
        val memberRoles = entry.author?.getRoles().orEmpty().takeIf { isMember }.orEmpty()
        val coloredRole = entry.roles.orEmpty().values
            .filter { it.id in memberRoles && it.color != 0 }
            .maxWithOrNull(compareBy<com.discord.api.role.GuildRole>({ it.position }, { it.id }))
        val roleColor = coloredRole?.color
            ?: ThemedColor.resolve(binding.root.context, R.attr.colorHeaderPrimary)
        binding.username.setTextColor(roleColor)
        binding.roleDot.apply {
            visibility = if (coloredRole == null) View.GONE else View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(roleColor)
            }
        }
        val iconRole = GuildMember.getHighestRoleIconRole(memberRoles, entry.roles)
        binding.roleIcon.visibility = if (iconRole == null) View.GONE else View.VISIBLE
        binding.roleIcon.setImageDrawable(null)
        iconRole?.let { IconUtils.setIcon(binding.roleIcon, it) }

        val openAuthor = View.OnClickListener { openAuthorActions(entry) }
        binding.username.setOnClickListener(openAuthor)
        binding.chatListAdapterItemTextAvatar.setOnClickListener(openAuthor)
    }

    private fun headerText(context: AutoModMessageContext): CharSequence = when {
        context.notification != null -> binding.root.context.getString(notificationHeader(context.notification.type))
        context.applicationName != null -> FormatUtils.format(
            binding.root.context.getString(R.string.guild_automod_header_interaction),
            "applicationName" to context.applicationName,
        )
        context.quarantineType != null -> binding.root.context.getString(R.string.guild_automod_header_quarantine)
        context.decisionOutcome == "blocked" || context.voiceChannelStatusOutcome == "blocked" ->
            binding.root.context.getString(R.string.guild_automod_header_blocked)
        else -> binding.root.context.getString(R.string.guild_automod_header_flagged)
    }

    private fun processMessageText(entry: AutoModSystemMessageEmbedEntry, autoMod: AutoModMessageContext) {
        val rendered = DiscordParser.parseChannelMessage(
            context = binding.root.context,
            messageText = autoMod.content,
            messageRenderContext = messageRenderContext(entry),
            preprocessor = messagePreprocessor(adapter.data.userId, entry),
            parserOptions = MarkupEligibility.parserOptionsFor(entry.message),
            isEdited = (entry.message.editedTimestamp?.dateTimeMillis ?: 0L) > 0L,
        )
        val matched = autoMod.keywordMatchedContent ?: autoMod.keyword
        if (!matched.isNullOrEmpty()) {
            var start = rendered.toString().indexOf(matched, ignoreCase = true)
            while (start >= 0) {
                rendered.setSpan(
                    BackgroundColorSpan(ThemedColor.resolve(binding.root.context, R.attr.colorBackgroundMessageAutomod)),
                    start,
                    start + matched.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                start = rendered.toString().indexOf(matched, start + matched.length, ignoreCase = true)
            }
        }
        binding.messageContent.visibility = if (rendered.isEmpty()) View.GONE else View.VISIBLE
        binding.messageContent.setDraweeSpanStringBuilder(rendered)
    }

    private fun configureDetails(context: AutoModMessageContext) {
        val keyword = listOfNotNull(
            context.keyword?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_keyword_match), "keyword" to it)
            },
            context.keywordMatchedContent?.takeUnless { it == context.keyword }?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_matched_content), "content" to it)
            },
        ).joinToString("\n")
        binding.keywordText.setTextAndVisibilityBy(keyword)

        val reasons = listOfNotNull(
            context.decisionReason?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_decision_reason), "reason" to it)
            },
            context.timeoutDuration?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_timeout_duration), "duration" to it)
            },
            (context.quarantineType ?: context.quarantineEvent)?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_quarantine_reason), "reason" to it)
            },
            context.alertActionsExecution,
        ).joinToString("\n")
        binding.reasonText.setTextAndVisibilityBy(reasons)
    }

    private fun configureNotification(notification: AutoModNotificationContext?) {
        binding.notificationContainer.visibility = if (notification == null) View.GONE else View.VISIBLE
        if (notification == null) return
        binding.notificationHeader.setText(notificationHeader(notification.type))
        binding.notificationBody.setText(
            when (notification.type) {
                "activity_alerts_enabled" -> R.string.guild_automod_notification_activity_enabled_body
                "interaction_blocked" -> R.string.guild_automod_notification_interaction_body
                else -> R.string.guild_automod_notification_raid_body
            },
        )
        val meta = listOfNotNull(
            notification.joinAttempts?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_notification_join_attempts), "count" to it)
            },
            notification.dmsSent?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_notification_dms_sent), "count" to it)
            },
            notification.raidDatetime,
            notification.suspiciousMentionActivityUntil,
            notification.resolvedReason?.let {
                FormatUtils.format(binding.root.context.getString(R.string.guild_automod_notification_resolved), "reason" to it)
            },
        ).joinToString(" • ")
        binding.notificationMeta.setTextAndVisibilityBy(meta)
    }

    private fun notificationHeader(type: String): Int = when (type) {
        "mention_raid" -> R.string.guild_automod_notification_mention_raid_header
        "activity_alerts_enabled" -> R.string.guild_automod_notification_activity_enabled_header
        "interaction_blocked" -> R.string.guild_automod_notification_interaction_header
        else -> R.string.guild_automod_notification_raid_header
    }

    private fun configureFooter(entry: AutoModSystemMessageEmbedEntry, context: AutoModMessageContext) {
        val channel = context.channelId?.let(StoreStream.getChannels()::getChannel)
        val canView = channel != null && PermissionUtils.can(
            Permission.VIEW_CHANNEL,
            StoreStream.getPermissions().getPermissionsByChannel()[channel.id],
        )
        binding.channelChip.apply {
            visibility = if (canView) View.VISIBLE else View.GONE
            text = channel?.name?.let { "#$it" }
            setOnClickListener { channel?.let { StoreStream.selectChannel(it.guildId, it.id) } }
        }
        binding.ruleText.setTextAndVisibilityBy(context.ruleName?.let {
            FormatUtils.format(binding.root.context.getString(R.string.guild_automod_rule_display), "ruleName" to it)
        })
    }

    private fun configureActions(entry: AutoModSystemMessageEmbedEntry, context: AutoModMessageContext) {
        binding.actionsButton.setOnClickListener {
            if (context.notification != null) {
                adapter.eventHandler.onAutoModIncidentActions(entry.message.guildId ?: entry.channel.guildId, entry.message.id)
            } else if (context.quarantineType != null && entry.message.author?.id == StoreStream.getUsers().getMe().id) {
                WidgetEditUserOrGuildMemberProfile.launch(binding.root.context, entry.message.guildId ?: entry.channel.guildId)
            } else {
                openAuthorActions(entry)
            }
        }
        binding.reportButton.apply {
            visibility = if (context.decisionId.isNullOrEmpty()) View.GONE else View.VISIBLE
            setOnClickListener { adapter.eventHandler.onReportIssueWithAutoMod(binding.root.context, entry.message) }
        }
        binding.dotSeparator.visibility = if (binding.reportButton.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        binding.viewMessageButton.apply {
            visibility = if (context.flaggedMessageId == null) View.GONE else View.VISIBLE
            setOnClickListener {
                context.flaggedMessageId?.let { messageId ->
                    StoreStream.getMessagesLoader().jumpToMessage(context.channelId ?: entry.message.channelId, messageId)
                }
            }
        }
    }

    private fun openAuthorActions(entry: AutoModSystemMessageEmbedEntry) {
        entry.message.author ?: return
        val guildId = entry.message.guildId ?: entry.channel.guildId
        adapter.eventHandler.onAutoModUserActions(entry.message, guildId)
    }

    private fun messagePreprocessor(userId: Long, entry: AutoModSystemMessageEmbedEntry): MessagePreprocessor {
        val settings = StoreStream.getUserSettings()
        val embeds = entry.message.embeds
            .takeIf { settings.getIsEmbedMediaInlined() && settings.getIsRenderEmbedsEnabled() }
        return MessagePreprocessor(userId, entry.messageState, embeds, true, null)
    }

    private fun messageRenderContext(entry: AutoModSystemMessageEmbedEntry) = MessageRenderContext(
        context = binding.root.context,
        myId = adapter.data.userId,
        isAnimationEnabled = entry.animateEmojis,
        userNames = entry.nickOrUsernames,
        channelNames = adapter.data.channelNames,
        roles = entry.roles,
        onLongPressUrl = adapter.eventHandler::onUrlLongClicked,
        spoilerOnClick = { },
        userMentionOnClick = { userId ->
            adapter.eventHandler.onUserMentionClicked(userId, adapter.data.channelId, adapter.data.guildId)
        },
        channelMentionOnClick = { channelId -> StoreStream.selectChannel(adapter.data.guildId, channelId) },
    )
}
