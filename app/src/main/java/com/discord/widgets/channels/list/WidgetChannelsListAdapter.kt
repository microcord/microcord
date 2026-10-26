package com.discord.widgets.channels.list

import android.text.format.DateUtils
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.ActivityType
import com.discord.databinding.WidgetChannelsListItemVoiceUserBinding
import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isDM
import com.discord.api.channel.isDMStatusVisible
import com.discord.api.channel.isGroup
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isHQDirectoryChannel
import com.discord.api.channel.isMultiUserDM
import com.discord.api.channel.isSystemDM
import com.discord.api.guild.GuildMaxVideoChannelUsers
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.api.permission.Permission
import com.discord.api.voice.state.VoiceState
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.feature.DmListCustomization
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.displaynamestyles.DisplayNameStyleRenderer
import com.discord.utilities.displaynamestyles.DisplayNameStyleUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.presence.PresenceUtils
import com.discord.utilities.view.extensions.setBackgroundAndKeepPadding
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.AvatarDecorationView
import com.discord.views.GuildTagView
import com.discord.views.NameplateView
import com.discord.views.StatusView
import com.discord.views.ThemedColor
import com.discord.views.VoiceUserLimitView
import com.discord.views.user.UserSummaryView
import com.discord.widgets.channels.list.items.ChannelListBottomNavSpaceItem
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemActiveEvent
import com.discord.widgets.channels.list.items.ChannelListItemAddServer
import com.discord.widgets.channels.list.items.ChannelListItemCategory
import com.discord.widgets.channels.list.items.ChannelListItemDirectory
import com.discord.widgets.channels.list.items.ChannelListItemGuildJoinRequest
import com.discord.widgets.servers.member_verification.MemberVerificationPendingViewModel
import com.discord.widgets.channels.list.items.ChannelListItemGuildScheduledEvents
import com.discord.widgets.channels.list.items.ChannelListItemEventsSeparator
import com.discord.widgets.channels.list.items.ChannelListItemHeader
import com.discord.widgets.channels.list.items.ChannelListItemInvite
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.list.items.ChannelListItemStageAudienceCount
import com.discord.widgets.channels.list.items.ChannelListItemStageVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemTextChannel
import com.discord.widgets.channels.list.items.ChannelListItemThread
import com.discord.widgets.channels.list.items.ChannelListItemVoiceChannel
import com.discord.widgets.channels.list.items.ChannelListItemVoiceUser
import com.discord.widgets.channels.list.items.ChannelListVocalItem
import com.discord.widgets.user.presence.ModelRichPresence
import com.discord.widgets.guildscheduledevent.GuildScheduledEventLocationInfo
import com.discord.views.VitoImageView
import com.discord.views.typing.TypingDots
import com.discord.stores.StoreVoiceChannelInfo
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.discord.utilities.user.UserUtils.getDisplayName
import rx.Subscription
import java.util.Locale

/**
 * The channels sidebar adapter: one view holder per [ChannelListItem] type.
 *
 * The adapter owns no state beyond [selectedGuildId] and [bottomNavHeight]; every interaction is
 * surfaced through a callback the hosting fragment assigns, exactly as the reference does. All
 * callbacks default to no-ops so a holder bound before the fragment finished wiring cannot crash.
 */
class WidgetChannelsListAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<ChannelListItem>(recycler) {

    /** Height of the bottom navigation bar, mirrored into the trailing spacer row. */
    private var bottomNavHeight: Int = 0

    var selectedGuildId: Long = 0L

    /** Voice channel statuses and session start times; only rows whose entry changed are rebound. */
    var voiceChannelInfo: Map<Long, StoreVoiceChannelInfo.Info> = emptyMap()
        set(value) {
            val previous = field
            field = value
            internalData.forEachIndexed { index, item ->
                if (item is ChannelListItemVoiceChannel && previous[item.channel.id] != value[item.channel.id]) {
                    notifyItemChanged(index)
                }
            }
        }

    /** Tap on the status line of the voice channel the user is connected to. */
    var onEditVoiceChannelStatus: (Channel, String?) -> Unit = { _, _ -> }

    /** Channels someone is typing in; only the rows whose state flipped are rebound. */
    var typingChannelIds: Set<Long> = emptySet()
        set(value) {
            val flipped = (field - value) + (value - field)
            field = value
            if (flipped.isEmpty()) return
            internalData.forEachIndexed { index, item ->
                val channelId = when (item) {
                    is ChannelListItemTextChannel -> item.channel.id
                    is ChannelListItemThread -> item.channel.id
                    is ChannelListItemPrivate -> item.channel.id
                    else -> return@forEachIndexed
                }
                if (channelId in flipped) notifyItemChanged(index)
            }
        }

    /** `null` clears the current selection (used by the hub "back to DMs" path). */
    var onSelectChannel: (Channel?) -> Unit = {}

    /** Long-press on a channel, category or thread row: opens the matching action sheet. */
    var onSelectChannelOptions: (Channel) -> Unit = {}

    /** Long-press on a voice participant. */
    var onSelectUserOptions: (User, Channel) -> Unit = { _, _ -> }

    /** Long-press on the invite row; the view is the anchor for the resulting toast. */
    var onSelectInvite: (View) -> Unit = {}

    /** Tap on a channel the user can only see because it is a role-subscription channel. */
    var onSelectGuildRoleSubscriptionLockedChannel: (Channel?) -> Unit = {}

    /** Tap on a vocal channel or a voice participant: joins the call. */
    var onCallChannel: (Channel) -> Unit = {}

    var onViewGuildScheduledEvents: (Guild) -> Unit = {}

    var onViewGuildRoleSubscriptions: () -> Unit = {}
    var onViewGuildOnboarding: () -> Unit = {}

    /** `(category, wasCollapsed)` — the holder reports the *previous* state, as the reference does. */
    var onCollapseCategory: (Channel, Boolean) -> Unit = { _, _ -> }

    var onAddServer: () -> Unit = {}

    /** Opens the create-channel flow for [guildId], optionally seeded with a type and category. */
    var onCreateChannel: (guildId: Long, channelType: Int, categoryId: Long?) -> Unit = { _, _, _ -> }

    /** Opens the guild invite-share sheet. */
    var onShareGuildInvite: (guildId: Long) -> Unit = {}

    /** Opens the membership-screening gate for the selected guild. */
    var onOpenMemberVerification: () -> Unit = {}

    /**
     * Opens the dialog for an application that has already been submitted or turned down, which is
     * a different destination from the form [onOpenMemberVerification] opens.
     */
    var onShowApplicationStatus: (MemberVerificationPendingViewModel.DialogState) -> Unit = {}

    /** Opens the detail sheet for a scheduled event that is not tied to a channel. */
    var onViewGuildScheduledEventDetails: (eventId: Long) -> Unit = {}

    fun handleBottomNavHeight(height: Int) {
        bottomNavHeight = height
        val index = internalData.indexOfLast { it is ChannelListBottomNavSpaceItem }
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, ChannelListItem> = when (viewType) {
        ChannelListItem.TYPE_CHANNEL ->
            ItemChannelText(R.layout.widget_channels_list_item_channel, this)
        ChannelListItem.TYPE_CHANNEL_VOICE ->
            ItemChannelVoice(R.layout.widget_channels_list_item_channel_voice, this)
        ChannelListItem.TYPE_CHANNEL_VOICE_USER ->
            ItemVoiceUser(R.layout.widget_channels_list_item_voice_user, this)
        ChannelListItem.TYPE_CHANNEL_PRIVATE ->
            ItemChannelPrivate(R.layout.widget_channels_list_item_channel_private, this)
        ChannelListItem.TYPE_HEADER ->
            ItemHeader(R.layout.widget_channels_list_item_header, this)
        ChannelListItem.TYPE_MFA_NOTICE ->
            ItemMFA(R.layout.widget_channels_list_item_mfa, this)
        ChannelListItem.TYPE_CATEGORY ->
            ItemChannelCategory(R.layout.widget_channels_list_item_category, this)
        ChannelListItem.TYPE_INVITE ->
            ItemInvite(R.layout.widget_channels_list_item_invite, this)
        ChannelListItem.TYPE_BOTTOM_NAV_SPACE ->
            ItemSpace(R.layout.recycler_item_bottom_nav_space, this)
        ChannelListItem.TYPE_THREAD ->
            ItemChannelThread(R.layout.widget_channels_list_item_thread, this)
        ChannelListItem.TYPE_CHANNEL_STAGE_VOICE ->
            ItemChannelStageVoice(R.layout.widget_channels_list_item_channel_stage_voice, this)
        ChannelListItem.TYPE_STAGE_EVENTS_SEPARATOR ->
            ItemSeparator(R.layout.widget_channels_list_item_stage_events_separator, this)
        ChannelListItem.TYPE_ACTIVE_EVENT ->
            ItemStageActiveEvent(R.layout.widget_channels_list_item_active_event, this)
        ChannelListItem.TYPE_STAGE_AUDIENCE_COUNT ->
            ItemStageChannelAudienceCount(R.layout.widget_channels_list_item_audience_count, this)
        ChannelListItem.TYPE_DIRECTORY ->
            ItemChannelDirectory(R.layout.widget_channels_list_item_directory, this)
        ChannelListItem.TYPE_ADD_SERVER ->
            ItemChannelAddServer(R.layout.widget_channels_list_item_directory, this)
        ChannelListItem.TYPE_GUILD_SCHEDULED_EVENTS ->
            ItemGuildScheduledEvents(R.layout.widget_channels_list_item_guild_scheduled_events, this)
        // The hub invite reuses the directory row rather than the standalone invite layout.
        ChannelListItem.TYPE_HUB_INVITE ->
            ItemInvite(R.layout.widget_channels_list_item_directory, this)
        ChannelListItem.TYPE_GUILD_ROLE_SUBSCRIPTIONS_OVERVIEW ->
            ItemGuildRoleSubscriptionsOverview(R.layout.widget_channels_list_item_guild_role_subs, this)
        ChannelListItem.TYPE_GUILD_JOIN_REQUEST ->
            ItemGuildJoinRequest(R.layout.widget_channels_list_item_guild_join_request, this)
        ChannelListItem.TYPE_GUILD_ONBOARDING ->
            ItemGuildOnboarding(R.layout.widget_channels_list_item_guild_onboarding, this)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * Base holder for rows that participate in the selected/unselected background swap.
     *
     * A vocal channel never takes the "selected" background: joining a voice channel does not
     * change which channel the sidebar considers open, so highlighting it would be misleading.
     */
    abstract class Item(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layoutResId, adapter) {

        protected fun TypingDots.bindTyping(channel: Channel) {
            val typing = channel.id in adapter.typingChannelIds
            visibility = if (typing) View.VISIBLE else View.GONE
            if (typing) start() else stop()
        }

        protected fun View.setChannelBackground(selected: Boolean, channel: Channel) {
            val attribute = if (selected && !channel.isGuildVocalChannel()) {
                R.attr.drawable_overlay_channels_selected
            } else {
                R.attr.drawable_overlay_channels
            }
            setBackgroundAndKeepPadding(
                ContextCompat.getDrawable(context, context.getThemedDrawableRes(attribute)),
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Guild channels
    // ------------------------------------------------------------------------------------------

    class ItemChannelCategory(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as LinearLayout
        private val arrow: ImageView = itemView.findViewById(R.id.channels_item_category_arrow)
        private val add: ImageView = itemView.findViewById(R.id.channels_item_category_add)
        private val name: TextView = itemView.findViewById(R.id.channels_item_category_name)

        /**
         * The collapse arrow is only animated when the state actually flips, so a rebind caused
         * by scrolling does not replay the rotation.
         */
        private var isCollapsed: Boolean = false
        private var boundItem: ChannelListItemCategory? = null

        init {
            root.setOnLongClickListenerConsumeClick { boundItem?.let { adapter.onSelectChannelOptions(it.channel) } }
            root.setOnClickListener {
                boundItem?.let { adapter.onCollapseCategory(it.channel, it.isCollapsed) }
            }
            add.setOnClickListener {
                boundItem?.let { adapter.onCreateChannel(it.channel.guildId, Channel.GUILD_TEXT, it.channel.id) }
            }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemCategory
            boundItem = item

            val categoryName = item.channel.getDisplayName()
            name.text = categoryName
            name.contentDescription = name.i18nFormat(
                R.string.category_a11y_label_with_expanded_state,
                "categoryName" to categoryName,
                "isExpanded" to name.context.getString(
                    if (item.isCollapsed) R.string.collapsed else R.string.expanded,
                ),
            )
            ViewCompat.setAccessibilityDelegate(
                name,
                object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(
                        host: View,
                        info: AccessibilityNodeInfoCompat,
                    ) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.isHeading = true
                    }
                },
            )

            val color = ThemedColor.resolve(
                name.context,
                if (item.isMuted) R.attr.colorInteractiveMuted else R.attr.colorChannelDefault,
            )
            name.setTextColor(color)

            arrow.setImageResource(
                if (item.isCollapsed) {
                    R.drawable.ic_chevron_right_grey_12dp
                } else {
                    R.drawable.ic_chevron_down_grey_12dp
                },
            )
            arrow.imageTintList = ColorStateList.valueOf(color)
            if (isCollapsed != item.isCollapsed) {
                isCollapsed = item.isCollapsed
                arrow.startAnimation(
                    if (item.isCollapsed) ARROW_ANIM_COLLAPSE else ARROW_ANIM_EXPAND,
                )
            }

            add.visibility = if (item.canManageChannels) View.VISIBLE else View.GONE
            add.imageTintList = ColorStateList.valueOf(color)
        }

        private companion object {
            private const val ANIMATION_DURATION = 300L

            /** Rotating *to* 0°, so the start angle encodes the direction of travel. */
            private fun animation(expand: Boolean): RotateAnimation = RotateAnimation(
                if (expand) -90f else 90f,
                0f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
                Animation.RELATIVE_TO_SELF,
                0.5f,
            ).apply {
                interpolator = AccelerateDecelerateInterpolator()
                duration = ANIMATION_DURATION
            }

            private val ARROW_ANIM_EXPAND = animation(expand = true)
            private val ARROW_ANIM_COLLAPSE = animation(expand = false)
        }
    }

    class ItemChannelText(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as RelativeLayout
        private val unread: ImageView = itemView.findViewById(R.id.channels_item_channel_unread)
        private val hash: ImageView = itemView.findViewById(R.id.channels_item_channel_hash)
        private val name: TextView = itemView.findViewById(R.id.channels_item_channel_name)
        private val mentions: TextView = itemView.findViewById(R.id.channels_item_channel_mentions)
        private val roleSubscriptionIcon: ImageView =
            itemView.findViewById(R.id.channels_item_channel_guild_role_subscription_icon)
        private val typing: TypingDots = itemView.findViewById(R.id.channels_item_channel_typing)
        private var boundItem: ChannelListItemTextChannel? = null

        init {
            root.setOnLongClickListenerConsumeClick { boundItem?.let { adapter.onSelectChannelOptions(it.channel) } }
            root.setOnClickListener {
                boundItem?.let { item ->
                    if (item.isGuildRoleSubscriptionLockedChannel) {
                        adapter.onSelectGuildRoleSubscriptionLockedChannel(item.channel)
                    } else {
                        adapter.onSelectChannel(item.channel)
                    }
                }
            }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemTextChannel
            boundItem = item
            val isAnnouncement = item.channel.type == Channel.GUILD_ANNOUNCEMENT

            root.setChannelBackground(item.selected, item.channel)
            root.contentDescription = contentDescription(item, isAnnouncement)

            name.text = item.channel.getDisplayName()
            name.setTextColor(textColor(item))
            name.typeface = root.context.themedFont(
                if (!(item.isUnread && item.isUnreadImportant) && !item.selected) {
                    R.attr.font_primary_normal
                } else {
                    R.attr.font_primary_semibold
                },
            )

            hash.setImageResource(
                when (item.channel.type) {
                    Channel.GUILD_ANNOUNCEMENT -> announcementsIcon(item)
                    Channel.GUILD_FORUM -> forumIcon(item)
                    Channel.GUILD_MEDIA -> R.drawable.ic_image_library_24dp
                    else -> hashIcon(item)
                },
            )
            hash.imageTintList = ColorStateList.valueOf(hashColor(item))
            typing.bindTyping(item.channel)

            mentions.visibility = if (item.mentionCount > 0) View.VISIBLE else View.GONE
            mentions.text = minOf(MAX_MENTION_COUNT, item.mentionCount).toString()
            mentions.backgroundTintList = if (item.isMentionLowImportance) {
                ColorStateList.valueOf(ThemedColor.resolve(root.context, R.attr.colorMentionBadgeMuted))
            } else {
                null
            }
            unread.visibility =
                if (item.isUnread && !item.selected && !item.muted) View.VISIBLE else View.GONE
            unread.imageTintList = ColorStateList.valueOf(
                ThemedColor.resolve(
                    root.context,
                    if (item.isUnreadImportant) R.attr.colorInteractiveActive else R.attr.colorMentionBadgeMuted,
                ),
            )

            roleSubscriptionIcon.visibility =
                if (item.isGuildRoleSubscriptionChannel) View.VISIBLE else View.GONE
            if (item.isGuildRoleSubscriptionLockedChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_locked)
            } else if (item.isGuildRoleSubscriptionChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_unlocked)
            }
        }

        private fun contentDescription(
            item: ChannelListItemTextChannel,
            isAnnouncement: Boolean,
        ): CharSequence {
            val channelName = item.channel.getDisplayName()
            return when {
                isAnnouncement && item.mentionCount > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_announcement_channel_a11y_label_with_mentions,
                    "mentionCount" to root.i18nPlural(
                        R.plurals.guild_sidebar_announcement_channel_a11y_label_with_mentions_mentionCount,
                        item.mentionCount,
                        "#" to item.mentionCount,
                    ),
                    "channelName" to channelName,
                )
                isAnnouncement && item.isUnread -> root.i18nFormat(
                    R.string.guild_sidebar_announcement_channel_a11y_label_with_unreads,
                    "channelName" to channelName,
                )
                isAnnouncement -> root.i18nFormat(
                    R.string.guild_sidebar_announcement_channel_a11y_label,
                    "channelName" to channelName,
                )
                item.mentionCount > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_default_channel_a11y_label_with_mentions,
                    "mentionCount" to root.i18nPlural(
                        R.plurals.guild_sidebar_default_channel_a11y_label_with_mentions_mentionCount,
                        item.mentionCount,
                        "#" to item.mentionCount,
                    ),
                    "channelName" to channelName,
                )
                item.isUnread -> root.i18nFormat(
                    R.string.guild_sidebar_default_channel_a11y_label_with_unreads,
                    "channelName" to channelName,
                )
                else -> root.i18nFormat(
                    R.string.guild_sidebar_default_channel_a11y_label,
                    "channelName" to channelName,
                )
            }
        }

        @DrawableRes
        private fun announcementsIcon(item: ChannelListItemTextChannel): Int = when {
            item.channel.nsfw && item.hasActiveThreads ->
                R.drawable.ic_channel_announcements_thread_nsfw
            item.channel.nsfw -> R.drawable.ic_channel_announcements_nsfw
            item.isLocked && item.hasActiveThreads ->
                R.drawable.ic_channel_announcements_thread_locked
            item.isLocked -> R.drawable.ic_channel_announcements_locked
            item.hasActiveThreads -> R.drawable.ic_channel_announcements_thread
            else -> R.drawable.ic_channel_announcements
        }

        @DrawableRes
        private fun forumIcon(item: ChannelListItemTextChannel): Int = when {
            item.channel.nsfw -> R.drawable.ic_channel_forum_nsfw
            item.isLocked -> R.drawable.ic_channel_forum_locked
            else -> R.drawable.ic_discord_forum_filled
        }

        @DrawableRes
        private fun hashIcon(item: ChannelListItemTextChannel): Int = when {
            item.channel.nsfw && item.hasActiveThreads -> R.drawable.ic_thread_nsfw
            item.channel.nsfw -> R.drawable.ic_channel_text_nsfw
            item.isLocked && item.hasActiveThreads -> R.drawable.ic_thread_locked
            item.isLocked -> R.drawable.ic_channel_text_locked
            item.hasActiveThreads -> R.drawable.ic_thread
            else -> R.drawable.ic_channel_text
        }

        @ColorInt
        private fun hashColor(item: ChannelListItemTextChannel): Int = ThemedColor.resolve(
            root.context,
            when {
                item.muted -> R.attr.colorInteractiveMuted
                item.isUnread && item.isUnreadImportant -> R.attr.colorInteractiveActive
                else -> R.attr.colorChannelDefault
            },
        )

        @ColorInt
        private fun textColor(item: ChannelListItemTextChannel): Int = ThemedColor.resolve(
            root.context,
            when {
                item.selected -> R.attr.colorInteractiveActive
                item.muted -> R.attr.colorInteractiveMuted
                item.isUnread && item.isUnreadImportant -> R.attr.colorInteractiveActive
                else -> R.attr.colorChannelDefault
            },
        )
    }

    class ItemChannelThread(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as ConstraintLayout
        private val content: ConstraintLayout =
            itemView.findViewById(R.id.channels_item_thread_content)
        private val name: TextView = itemView.findViewById(R.id.channels_item_thread_name)
        private val mentions: TextView = itemView.findViewById(R.id.channels_item_thread_mentions)
        private val typing: TypingDots = itemView.findViewById(R.id.channels_item_thread_typing)
        private val spine: ImageView = itemView.findViewById(R.id.channels_item_thread_spine)
        private val unread: ImageView = itemView.findViewById(R.id.channels_item_thread_unread)
        private var boundItem: ChannelListItemThread? = null

        init {
            root.setOnLongClickListenerConsumeClick { boundItem?.let { adapter.onSelectChannelOptions(it.channel) } }
            root.setOnClickListener { boundItem?.let { adapter.onSelectChannel(it.channel) } }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemThread
            boundItem = item

            // The background sits on the inner content view so the spine gutter stays untinted.
            content.setChannelBackground(item.selected, item.channel)
            content.contentDescription = contentDescription(item)

            name.text = item.channel.name
            typing.bindTyping(item.channel)
            name.setTextColor(textColor(item))
            name.typeface = root.context.themedFont(
                if (!(item.isUnread && item.isUnreadImportant) && !item.selected) {
                    R.attr.font_primary_normal
                } else {
                    R.attr.font_primary_semibold
                },
            )

            mentions.visibility = if (item.mentionCount > 0) View.VISIBLE else View.GONE
            mentions.text = minOf(MAX_MENTION_COUNT, item.mentionCount).toString()
            mentions.backgroundTintList = if (item.isMentionLowImportance) {
                ColorStateList.valueOf(ThemedColor.resolve(root.context, R.attr.colorMentionBadgeMuted))
            } else {
                null
            }
            unread.visibility =
                if (item.isUnread && !item.selected && !item.muted) View.VISIBLE else View.GONE
            unread.imageTintList = ColorStateList.valueOf(
                ThemedColor.resolve(
                    root.context,
                    if (item.isUnreadImportant) R.attr.colorInteractiveActive else R.attr.colorMentionBadgeMuted,
                ),
            )

            spine.setImageResource(spineResourceId(item.spineType))
        }

        private fun contentDescription(item: ChannelListItemThread): CharSequence = when {
            item.mentionCount > 0 -> root.i18nFormat(
                R.string.guild_sidebar_default_channel_a11y_label_with_mentions,
                "mentionCount" to root.i18nPlural(
                    R.plurals.guild_sidebar_default_channel_a11y_label_with_mentions_mentionCount,
                    item.mentionCount,
                    "#" to item.mentionCount,
                ),
                "channelName" to item.channel.name,
            )
            item.isUnread -> root.i18nFormat(
                R.string.guild_sidebar_default_channel_a11y_label_with_unreads,
                "channelName" to item.channel.name,
            )
            else -> root.i18nFormat(
                R.string.guild_sidebar_default_channel_a11y_label,
                "channelName" to item.channel.name,
            )
        }

        @DrawableRes
        private fun spineResourceId(
            spineType: WidgetChannelListModel.ThreadSpineType,
        ): Int = when (spineType) {
            WidgetChannelListModel.ThreadSpineType.Single -> R.drawable.ic_spine_short_cap
            WidgetChannelListModel.ThreadSpineType.Start -> R.drawable.ic_spine_long_cap
            WidgetChannelListModel.ThreadSpineType.End -> R.drawable.ic_spine_short_no_cap
            WidgetChannelListModel.ThreadSpineType.Middle -> R.drawable.ic_spine_long_no_cap
        }

        @ColorInt
        private fun textColor(item: ChannelListItemThread): Int = ThemedColor.resolve(
            root.context,
            when {
                item.selected -> R.attr.colorInteractiveActive
                item.muted -> R.attr.colorInteractiveMuted
                item.isUnread && item.isUnreadImportant -> R.attr.colorInteractiveActive
                else -> R.attr.colorChannelDefault
            },
        )
    }

    class ItemChannelVoice(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as ConstraintLayout
        private val mentions: TextView = itemView.findViewById(R.id.channels_item_channel_mentions)
        private val unread: ImageView = itemView.findViewById(R.id.channels_item_channel_unread)
        private val eventTopic: TextView =
            itemView.findViewById(R.id.channels_item_voice_channel_event_topic)
        private val roleSubscriptionIcon: ImageView =
            itemView.findViewById(R.id.channels_item_voice_channel_guild_role_subscription_icon)
        private val name: TextView = itemView.findViewById(R.id.channels_item_voice_channel_name)
        private val speaker: ImageView =
            itemView.findViewById(R.id.channels_item_voice_channel_speaker)
        private val userLimit: VoiceUserLimitView =
            itemView.findViewById(R.id.channels_item_voice_channel_user_limit)
        private val status: SimpleDraweeSpanTextView = itemView.findViewById(R.id.channels_item_voice_channel_status)
        private var renderedStatus: String? = null
        private val duration: TextView = itemView.findViewById(R.id.channels_item_voice_channel_duration)
        private var boundItem: ChannelListItemVoiceChannel? = null
        private var startTimeMs: Long? = null
        private val tick = object : Runnable {
            override fun run() {
                val start = startTimeMs ?: return
                duration.text = formatElapsed(System.currentTimeMillis() - start)
                itemView.postDelayed(this, 1000L)
            }
        }

        init {
            root.setOnClickListener {
                boundItem?.let { item ->
                    if (item.isGuildRoleSubscriptionLockedChannel) {
                        adapter.onSelectGuildRoleSubscriptionLockedChannel(item.channel)
                    } else {
                        adapter.onCallChannel(item.channel)
                    }
                }
            }
            // Not gated on MANAGE_CHANNELS: the sheet is mostly things any member can do — mute,
            // mark as read, notification overrides, invite — and it hides the manage-only entries
            // by itself. Requiring the permission left everyone else with a long press that ate
            // the gesture and showed nothing.
            root.setOnLongClickListenerConsumeClick {
                boundItem?.let { adapter.onSelectChannelOptions(it.channel) }
            }
            status.setOnClickListener {
                boundItem?.let { adapter.onEditVoiceChannelStatus(it.channel, adapter.voiceChannelInfo[it.channel.id]?.status) }
            }
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = restartTick()
                override fun onViewDetachedFromWindow(view: View) {
                    itemView.removeCallbacks(tick)
                }
            })
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemVoiceChannel
            boundItem = item
            val canConnect = PermissionUtils.can(Permission.CONNECT, item.permission)

            val selected = item.voiceSelected || item.textSelected
            root.isSelected = selected
            root.setChannelBackground(selected, item.channel)

            name.text = item.channel.getDisplayName()
            var iconColor = voiceChannelColor(selected)
            name.setTextColor(iconColor)

            eventTopic.setTextAndVisibilityBy(item.guildScheduledEvent?.name)

            mentions.visibility = if (item.mentionCount > 0) View.VISIBLE else View.GONE
            mentions.text = minOf(MAX_MENTION_COUNT, item.mentionCount).toString()
            unread.visibility =
                if (item.isUnread && !item.textSelected) View.VISIBLE else View.GONE

            roleSubscriptionIcon.visibility =
                if (item.isGuildRoleSubscriptionChannel) View.VISIBLE else View.GONE
            if (item.isGuildRoleSubscriptionLockedChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_locked)
            } else if (item.isGuildRoleSubscriptionChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_unlocked)
            }

            val speakerIcon = when {
                !canConnect -> R.drawable.ic_channel_lock_16dp
                item.isNsfw -> R.drawable.ic_voice_nsfw
                item.isLocked -> R.drawable.ic_channel_voice_locked
                else -> R.drawable.ic_channel_voice
            }
            // A live event recolours the whole row's icon, overriding the selection colour.
            if (item.guildScheduledEvent != null) {
                iconColor = ContextCompat.getColor(root.context, R.color.status_green_600)
            }
            speaker.setImageResource(speakerIcon)
            speaker.imageTintList = ColorStateList.valueOf(iconColor)

            configureUserLimit(item, canConnect)
            configureChannelInfo(item, adapter.voiceChannelInfo[item.channel.id])
        }

        /**
         * The status line, and the time since the channel's current session began, which takes the
         * user-limit slot when the channel has no limit. Connected members who may set the status
         * get a prompt when there is none.
         */
        private fun configureChannelInfo(item: ChannelListItemVoiceChannel, info: StoreVoiceChannelInfo.Info?) {
            val canSetStatus = item.voiceSelected &&
                PermissionUtils.can(Permission.SET_VOICE_CHANNEL_STATUS, item.permission)
            val statusText = info?.status?.takeIf(String::isNotBlank)
            if (statusText == null) {
                renderedStatus = null
                status.text = if (canSetStatus) root.context.getString(R.string.voice_channel_set_status) else null
            } else if (statusText != renderedStatus) {
                // Emoji and light markdown, as the desktop client renders it; no links or mentions.
                renderedStatus = statusText
                status.setDraweeSpanStringBuilder(
                    AstRenderer.render(
                        STATUS_PARSER.parse(statusText, MessageParseState.initialState),
                        MessageRenderContext(
                            context = root.context,
                            myId = 0L,
                            isAnimationEnabled = StoreStream.getUserSettings().getIsAnimatedEmojisEnabled(),
                        ),
                    ),
                )
            }
            status.visibility = if (statusText != null || canSetStatus) View.VISIBLE else View.GONE
            status.isClickable = canSetStatus
            val pencil = if (canSetStatus) {
                ContextCompat.getDrawable(root.context, R.drawable.ic_edit_24dp)?.mutate()?.apply {
                    val size = (STATUS_EDIT_ICON_DP * root.resources.displayMetrics.density).toInt()
                    setBounds(0, 0, size, size)
                    setTint(status.currentTextColor)
                }
            } else {
                null
            }
            status.setCompoundDrawablesRelative(null, null, pencil, null)

            startTimeMs = info?.startTimeMs?.takeIf { userLimit.visibility != View.VISIBLE }
            duration.visibility = if (startTimeMs != null) View.VISIBLE else View.GONE
            restartTick()
        }

        private fun restartTick() {
            itemView.removeCallbacks(tick)
            if (startTimeMs != null) tick.run()
        }

        /**
         * The channel's own user limit and the guild's video limit are separate caps. When video
         * is in use and the video cap is the tighter of the two, the row shows the video cap and
         * switches to the video style; otherwise it shows the channel limit.
         */
        private fun configureUserLimit(item: ChannelListItemVoiceChannel, canConnect: Boolean) {
            val videoLimit =
                (item.guildMaxVideoChannelUsers as? GuildMaxVideoChannelUsers.Limited)?.limit ?: 0
            val effectiveLimit = minOf(item.channel.userLimit, videoLimit)
            if (effectiveLimit <= 0) {
                userLimit.visibility = View.GONE
                root.contentDescription = contentDescription(item, effectiveLimit)
                return
            }
            val isVideo = item.isAnyoneUsingVideo && effectiveLimit == videoLimit
            val shownLimit = if (isVideo) videoLimit else item.channel.userLimit
            userLimit.visibility =
                if (shownLimit > 0 && canConnect) View.VISIBLE else View.GONE
            userLimit.configure(item.numUsersConnected, shownLimit, isVideo)
            root.contentDescription = contentDescription(item, shownLimit)
        }

        private fun contentDescription(
            item: ChannelListItemVoiceChannel,
            userLimit: Int,
        ): CharSequence {
            val channelName = item.channel.getDisplayName()
            val mentionText = {
                root.i18nPlural(
                    R.plurals.guild_sidebar_default_channel_a11y_label_with_mentions_mentionCount,
                    item.mentionCount,
                    "#" to item.mentionCount,
                )
            }
            val userText = {
                root.i18nPlural(
                    R.plurals.guild_sidebar_voice_channel_a11y_label_with_users_userCount,
                    item.numUsersConnected,
                    "#" to item.numUsersConnected,
                )
            }
            return when {
                userLimit > 0 && item.mentionCount > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label_with_limit_with_mentions,
                    "mentionCount" to mentionText(),
                    "channelName" to channelName,
                    "userCount" to item.numUsersConnected,
                    "limit" to userLimit,
                )
                userLimit > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label_with_limit,
                    "channelName" to channelName,
                    "userCount" to item.numUsersConnected,
                    "limit" to userLimit,
                )
                item.numUsersConnected > 0 && item.mentionCount > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label_with_users_with_mentions,
                    "mentionCount" to mentionText(),
                    "channelName" to channelName,
                    "userCount" to userText(),
                )
                item.numUsersConnected > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label_with_users,
                    "channelName" to channelName,
                    "userCount" to userText(),
                )
                item.mentionCount > 0 -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label_with_mentions,
                    "mentionCount" to mentionText(),
                    "channelName" to channelName,
                )
                else -> root.i18nFormat(
                    R.string.guild_sidebar_voice_channel_a11y_label,
                    "channelName" to channelName,
                )
            }
        }

        @ColorInt
        private fun voiceChannelColor(isSelected: Boolean): Int = ThemedColor.resolve(
            root.context,
            if (isSelected) R.attr.colorInteractiveActive else R.attr.colorChannelDefault,
        )
    }

    class ItemChannelStageVoice(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as RelativeLayout
        private val roleSubscriptionIcon: ImageView =
            itemView.findViewById(R.id.channels_item_stage_voice_channel_guild_role_subscription_icon)
        private val stageIcon: ImageView =
            itemView.findViewById(R.id.stage_channel_item_stage_channel_icon)
        private val name: TextView =
            itemView.findViewById(R.id.stage_channel_item_voice_channel_name)
        private val topic: TextView =
            itemView.findViewById(R.id.stage_channel_item_voice_channel_topic)
        private var boundItem: ChannelListItemStageVoiceChannel? = null

        init {
            root.setOnClickListener {
                boundItem?.let { item ->
                    if (item.isGuildRoleSubscriptionLockedChannel) {
                        adapter.onSelectGuildRoleSubscriptionLockedChannel(item.channel)
                    } else {
                        adapter.onCallChannel(item.channel)
                    }
                }
            }
            root.setOnLongClickListenerConsumeClick { boundItem?.let { adapter.onSelectChannelOptions(it.channel) } }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemStageVoiceChannel
            boundItem = item
            val canConnect = PermissionUtils.can(Permission.CONNECT, item.permission)

            root.isSelected = item.selected
            root.setChannelBackground(item.selected, item.channel)

            val color = ThemedColor.resolve(
                root.context,
                if (item.selected) R.attr.colorInteractiveActive else R.attr.colorChannelDefault,
            )
            name.text = item.channel.getDisplayName()
            name.setTextColor(color)
            topic.setTextAndVisibilityBy(item.stageInstance?.topic)

            roleSubscriptionIcon.visibility =
                if (item.isGuildRoleSubscriptionChannel) View.VISIBLE else View.GONE
            if (item.isGuildRoleSubscriptionLockedChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_locked)
            } else if (item.isGuildRoleSubscriptionChannel) {
                roleSubscriptionIcon.setImageResource(R.drawable.ic_premium_channel_unlocked)
            }

            stageIcon.setImageResource(
                when {
                    !canConnect -> R.drawable.ic_channel_lock_16dp
                    item.isLocked -> R.drawable.ic_channel_stage_locked
                    else -> R.drawable.ic_channel_stage_24dp
                },
            )
            // A live stage the user can actually join is the only case that goes green.
            stageIcon.imageTintList = ColorStateList.valueOf(
                if (item.hasActiveEvent && canConnect) {
                    ContextCompat.getColor(root.context, R.color.status_green_600)
                } else {
                    color
                },
            )

            root.contentDescription = contentDescription(item, item.channel.userLimit)
        }

        private fun contentDescription(
            item: ChannelListVocalItem,
            userLimit: Int,
        ): CharSequence = when {
            userLimit > 0 -> root.i18nFormat(
                R.string.guild_sidebar_voice_channel_a11y_label_with_limit,
                "channelName" to item.channel.getDisplayName(),
                "userCount" to item.numUsersConnected,
                "limit" to userLimit,
            )
            item.numUsersConnected > 0 -> root.i18nFormat(
                R.string.guild_sidebar_voice_channel_a11y_label_with_users,
                "channelName" to item.channel.getDisplayName(),
                "userCount" to root.i18nPlural(
                    R.plurals.guild_sidebar_voice_channel_a11y_label_with_users_userCount,
                    item.numUsersConnected,
                    "#" to item.numUsersConnected,
                ),
            )
            else -> root.i18nFormat(
                R.string.guild_sidebar_voice_channel_a11y_label,
                "channelName" to item.channel.getDisplayName(),
            )
        }
    }

    class ItemVoiceUser(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val binding = WidgetChannelsListItemVoiceUserBinding.bind(itemView)
        private val avatarChangeDetector = MGImages.DistinctChangeDetector()
        private val activityChangeDetector = MGImages.DistinctChangeDetector()
        private var boundItem: ChannelListItemVoiceUser? = null
        private var presenceSubscription: Subscription? = null
        override val subscription: Subscription? get() = presenceSubscription

        init {
            binding.root.setOnLongClickListenerConsumeClick {
                boundItem?.let { adapter.onSelectUserOptions(it.user, it.channel) }
            }
            binding.root.setOnClickListener { boundItem?.let { adapter.onCallChannel(it.channel) } }
            itemView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = observeActivity()
                override fun onViewDetachedFromWindow(view: View) {
                    presenceSubscription?.let { adapter.cellSubscriptions.remove(it) }
                    presenceSubscription = null
                }
            })
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemVoiceUser
            boundItem = item

            IconUtils.setIcon(
                imageView = binding.channelsItemVoiceUserAvatar,
                user = item.user,
                sizeDimenRes = R.dimen.avatar_size_small,
                guildMember = item.computed,
                changeDetector = avatarChangeDetector,
            )

            binding.channelsItemVoiceUserName.text = GuildMember.getNickOrUsername(item.computed, item.user)
            DisplayNameStyleRenderer.apply(
                binding.channelsItemVoiceUserName,
                DisplayNameStyleUtils.resolve(item.user, item.computed),
                DisplayNameStyleRenderer.Mode.FONT,
            )
            binding.channelsItemVoiceUserCamera.visibility = if (item.voiceState.selfVideo) View.VISIBLE else View.GONE
            binding.channelsItemVoiceUserMicrophone.visibility =
                if (item.voiceState.isMutedByAnySource()) View.VISIBLE else View.GONE
            binding.channelsItemVoiceUserHeadphones.visibility =
                if (item.voiceState.selfDeaf || item.voiceState.deaf) View.VISIBLE else View.GONE
            // "LIVE" needs both a stream and the permission to watch it.
            binding.channelsItemVoiceUserLive.visibility =
                if (item.isApplicationStreaming && item.hasChannelConnectPermission) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

            // Our own entry from another client: shown, but faded so it does not read as this device's call.
            binding.root.alpha = if (item.isOtherSession) OTHER_SESSION_ALPHA else 1f

            bindActivityIcon(null)
            if (itemView.isAttachedToWindow) observeActivity()
        }

        private fun observeActivity() {
            presenceSubscription?.let { adapter.cellSubscriptions.remove(it) }
            val userId = boundItem?.user?.id ?: return
            ModelRichPresence.get(userId, activityType = ActivityType.PLAYING)
                .map { model -> model?.playingIconUrl?.let { it to model.primaryActivity?.name } }
                .distinctUntilChanged()
                .ui()
                .appSubscribe(
                    errorClass = ItemVoiceUser::class.java,
                    subscriptionHandler = {
                        presenceSubscription = it
                        adapter.cellSubscriptions.add(it)
                    },
                ) { icon -> bindActivityIcon(icon?.first, icon?.second) }
        }

        private fun bindActivityIcon(url: String?, gameName: String? = null) {
            MGImages.setImage(
                binding.channelsItemVoiceUserActivity,
                url,
                useSmallCache = true,
                changeDetector = activityChangeDetector,
            )
            binding.channelsItemVoiceUserActivity.contentDescription = gameName
            binding.channelsItemVoiceUserActivity.visibility = if (url.isNullOrBlank()) View.GONE else View.VISIBLE
        }
    }

    // ------------------------------------------------------------------------------------------
    // Private channels
    // ------------------------------------------------------------------------------------------

    class ItemChannelPrivate(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as RelativeLayout
        private val status: StatusView = itemView.findViewById(R.id.channel_list_item_private_status)
        private val avatar: VitoImageView =
            itemView.findViewById(R.id.channels_list_item_private_avatar)
        private val desc: SimpleDraweeSpanTextView =
            itemView.findViewById(R.id.channels_list_item_private_desc)
        private val mentions: TextView =
            itemView.findViewById(R.id.channels_list_item_private_mentions)
        private val name: TextView = itemView.findViewById(R.id.channels_list_item_private_name)
        private val tag: TextView = itemView.findViewById(R.id.channels_list_item_private_tag)
        private val timestamp: TextView =
            itemView.findViewById(R.id.channels_list_item_private_timestamp)
        private val guildTag: GuildTagView = itemView.findViewById(R.id.channels_list_item_private_guild_tag)
        private val nameplate: NameplateView =
            itemView.findViewById(R.id.channels_list_item_private_nameplate)
        private val avatarChangeDetector = MGImages.DistinctChangeDetector()
        private val avatarDecoration: AvatarDecorationView =
            itemView.findViewById(R.id.channels_list_item_private_avatar_decoration)
        private val content: View = itemView.findViewById(R.id.channels_list_item_private_content)
        private val pin: ImageView = itemView.findViewById(R.id.channels_list_item_private_pin)
        private val typing: TypingDots = itemView.findViewById(R.id.channels_list_item_private_typing)
        private var boundChannel: Channel? = null

        init {
            root.setOnLongClickListenerConsumeClick { boundChannel?.let { adapter.onSelectChannelOptions(it) } }
            root.setOnClickListener { boundChannel?.let { adapter.onSelectChannel(it) } }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemPrivate
            val channel = item.channel
            boundChannel = channel
            // A one-to-one DM already carries the recipient's name; anything else may need the
            // "Unnamed"/"Direct Message" fallback that getDisplayNameOrDefault supplies.
            val channelName = if (channel.isDM()) {
                item.displayName ?: channel.getDisplayName()
            } else {
                item.displayName ?: channel.getDisplayNameOrDefault(name.context)
            }

            root.setOnHoverListener { _, event ->
                nameplate.setNameplateHovered(
                    event.action == MotionEvent.ACTION_HOVER_ENTER || event.action == MotionEvent.ACTION_HOVER_MOVE,
                )
                false
            }
            root.contentDescription = contentDescription(item, channelName)
            val hasNameplate =
                (item.nameplate ?: channel.getDMRecipient()?.collectibles?.nameplate) != null
            nameplate.bind(
                item.nameplate ?: channel.getDMRecipient()?.collectibles?.nameplate,
                NameplateView.Placement.CHANNEL,
                item.selected,
            )

            name.text = channelName
            name.setTextColor(textColor(item))
            DisplayNameStyleRenderer.apply(
                name,
                DisplayNameStyleUtils.resolve(channel.getDMRecipient()),
                if (item.selected) DisplayNameStyleRenderer.Mode.FULL else DisplayNameStyleRenderer.Mode.FONT,
                textColor(item),
            )
            pin.setColorFilter(textColor(item))
            typing.bindTyping(channel)
            guildTag.bind(channel.getDMRecipient()?.primaryGuild)
            if (!DmListCustomization.visible(DmListCustomization.Component.GUILD_TAG)) {
                guildTag.visibility = View.GONE
            }
            tag.visibility = if (channel.isSystemDM()) View.VISIBLE else View.GONE

            IconUtils.setIcon(avatar, channel, R.dimen.avatar_size_standard, changeDetector = avatarChangeDetector)
            avatarDecoration.bind(item.avatarDecoration ?: channel.getDMRecipient()?.avatarDecoration)

            status.visibility = if (
                DmListCustomization.visible(DmListCustomization.Component.STATUS_DOT) &&
                channel.isDMStatusVisible(item.presence)
            ) View.VISIBLE else View.GONE
            status.setPresence(item.presence)
            status.setBackgroundColor(presenceBackground(item.selected))

            // The preloaded preview from StoreMessagePreviews, falling back to the synced message
            // cache for channels the client has actually opened.
            val preview = (item.previewMessage
                ?: StoreStream.getMessages().getMessages(channel.id)
                    .lastOrNull { !it.content.isNullOrBlank() })
                ?.content?.takeIf {
                    DmListCustomization.previewReplacesStatus(
                        DmListCustomization.visible(DmListCustomization.Component.MESSAGE),
                        channel.isDM(),
                        it,
                    )
                }
            when {
                preview != null -> desc.text = preview
                !DmListCustomization.visible(DmListCustomization.Component.STATUS) ->
                    desc.setTextAndVisibilityBy(null as CharSequence?)
                channel.isSystemDM() ->
                    desc.setTextAndVisibilityBy(desc.context.getString(R.string.system_dm_activity_text))
                channel.isMultiUserDM() ->
                    desc.setTextAndVisibilityBy(memberCount(channel, desc.context))
                else -> PresenceUtils.setStatusLineWithLeadingIcon(
                    item.presence,
                    item.isApplicationStreaming,
                    desc,
                )
            }

            val showTimestamp = DmListCustomization.visible(DmListCustomization.Component.TIMESTAMP) &&
                channel.lastMessageId > 0L
            timestamp.text = if (showTimestamp) {
                DateUtils.getRelativeTimeSpanString(SnowflakeUtils.toTimestamp(channel.lastMessageId))
            } else {
                ""
            }
            timestamp.visibility = if (showTimestamp) View.VISIBLE else View.GONE

            mentions.text = minOf(MAX_MENTION_COUNT, item.mentionCount).toString()
            mentions.visibility = if (item.mentionCount > 0) View.VISIBLE else View.GONE
            // The themed pill strokes itself in the row's background colour to fake a gap. Over a
            // nameplate the row is artwork, not that colour, so the ring reads as a grey blob.
            mentions.setBackgroundResource(
                if (hasNameplate) {
                    R.drawable.drawable_overlay_mentions_borderless
                } else {
                    itemView.getThemedDrawableRes(R.attr.drawable_overlay_mentions)
                },
            )
            pin.visibility = if (item.pinned) View.VISIBLE else View.GONE

            root.isSelected = item.selected
            root.setChannelBackground(item.selected, channel)
        }

        /** The reference appends its hints to the label, pinned ahead of anything else. */
        private fun contentDescription(
            item: ChannelListItemPrivate,
            channelName: CharSequence?,
        ): CharSequence {
            val label = channelLabel(item, channelName)
            if (!item.pinned) return label
            return "$label, ${itemView.context.getString(R.string.channel_pinned_a11y_hint)}"
        }

        private fun channelLabel(
            item: ChannelListItemPrivate,
            channelName: CharSequence?,
        ): CharSequence = when {
            item.channel.isGroup() && item.mentionCount > 0 -> itemView.i18nFormat(
                R.string.group_message_a11y_label_with_unreads,
                "channelName" to channelName,
            )
            item.channel.isGroup() -> itemView.i18nFormat(
                R.string.group_message_a11y_label,
                "channelName" to channelName,
            )
            item.mentionCount > 0 -> itemView.i18nFormat(
                R.string.direct_message_a11y_label_with_unreads,
                "channelName" to channelName,
            )
            else -> itemView.i18nFormat(
                R.string.direct_message_a11y_label,
                "channelName" to channelName,
            )
        }

        /** The recipient list excludes the current user, hence the `+ 1`. */
        private fun memberCount(channel: Channel, context: Context): String =
            "${channel.recipients.orEmpty().size + 1} ${context.getString(R.string.members)}"

        @ColorInt
        private fun presenceBackground(selected: Boolean): Int = ThemedColor.resolve(
            itemView.context,
            if (selected) {
                R.attr.color_bg_private_channel_presence_selected
            } else {
                R.attr.colorBackgroundSecondary
            },
        )

        @ColorInt
        private fun textColor(item: ChannelListItemPrivate): Int = ThemedColor.resolve(
            itemView.context,
            when {
                item.selected -> R.attr.colorInteractiveActive
                item.muted -> R.attr.colorInteractiveMuted
                else -> R.attr.colorChannelDefault
            },
        )
    }

    // ------------------------------------------------------------------------------------------
    // Hub / directory rows
    // ------------------------------------------------------------------------------------------

    class ItemChannelDirectory(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as LinearLayout
        private val icon: ImageView = itemView.findViewById(R.id.directory_channel_icon)
        private val name: TextView = itemView.findViewById(R.id.directory_channel_name)
        private val unreadCount: TextView =
            itemView.findViewById(R.id.directory_channel_unread_count)
        private var boundChannel: Channel? = null

        init {
            root.setOnClickListener { boundChannel?.let { adapter.onSelectChannel(it) } }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemDirectory
            boundChannel = item.channel
            // Discord's own seeded directory keeps its real name and the hub glyph; every other
            // directory channel is presented as the generic "Join servers" entry point.
            val isHqDirectory = item.channel.isHQDirectoryChannel()

            if (isHqDirectory) {
                name.text = item.channel.name.orEmpty()
                icon.setImageResource(R.drawable.ic_hub_24dp)
            } else {
                name.setText(R.string.hub_sidebar_join_servers)
                icon.setImageResource(R.drawable.ic_compass)
            }

            unreadCount.setTextAndVisibilityBy(
                if (item.unreadCount > 0) item.unreadCount.toString() else null,
            )
            root.isSelected = item.selected
        }
    }

    class ItemChannelAddServer(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as LinearLayout
        private val icon: ImageView = itemView.findViewById(R.id.directory_channel_icon)
        private val name: TextView = itemView.findViewById(R.id.directory_channel_name)

        init {
            root.setOnClickListener { adapter.onAddServer() }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            if (data !is ChannelListItemAddServer) return
            name.setText(R.string.hub_sidebar_add_servers)
            icon.setImageResource(R.drawable.ic_add_24dp)
        }
    }

    /**
     * The invite row.
     *
     * Guild lists inflate `widget_channels_list_item_invite`, which carries its own copy; hubs
     * reuse the directory row layout and therefore have to set the label and icon here.
     */
    class ItemInvite(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private var boundGuildId: Long? = null

        init {
            itemView.setOnClickListener { boundGuildId?.let { adapter.onShareGuildInvite(it) } }
            itemView.setOnLongClickListenerConsumeClick { adapter.onSelectInvite(it) }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            if (data !is ChannelListItemInvite) return
            boundGuildId = data.guildId
            if (data.isHub) {
                itemView.findViewById<TextView>(R.id.directory_channel_name)
                    .setText(R.string.invite_a_friend)
                itemView.findViewById<ImageView>(R.id.directory_channel_icon)
                    .setImageResource(R.drawable.ic_guild_invite_24dp)
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Notices, headers and spacers
    // ------------------------------------------------------------------------------------------

    class ItemSeparator(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layoutResId, adapter) {

        private val gap = itemView.resources.getDimensionPixelSize(R.dimen.channel_list_separator_gap)

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val params = itemView.layoutParams as ViewGroup.MarginLayoutParams
            params.bottomMargin = if ((data as ChannelListItemEventsSeparator).tightBelow) 0 else gap
            itemView.layoutParams = params
        }
    }

    class ItemHeader(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layoutResId, adapter) {

        private val header: TextView = itemView.findViewById(R.id.channels_list_item_header)
        private val add: ImageView = itemView.findViewById(R.id.channels_list_new)
        private var boundSelectedGuildId: Long = 0L
        private var boundCreateType: Int? = null

        init {
            add.setOnClickListener {
                boundCreateType?.let { createType -> adapter.onCreateChannel(boundSelectedGuildId, createType, null) }
            }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemHeader
            boundSelectedGuildId = item.selectedGuildId

            // The add button is kept INVISIBLE rather than GONE where it does not apply, so the
            // header text keeps the same width in every section.
            val createType = when {
                !item.ableToManageChannel -> null
                item.textResId == R.string.text_channels -> Channel.GUILD_TEXT
                item.textResId == R.string.voice_channels -> Channel.GUILD_VOICE
                else -> null
            }
            boundCreateType = createType
            add.visibility = if (createType == null) View.INVISIBLE else View.VISIBLE
            header.setText(item.textResId)
        }
    }

    class ItemMFA(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layoutResId, adapter) {

        private val text: LinkifiedTextView =
            itemView.findViewById(R.id.channels_list_item_mfa_text)

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            // The warning string carries a `[Resolve](onClick)` hook. Until `com.discord.i18n`
            // exposes hook binding, the copy renders without the tappable "Resolve" run; the
            // 2FA help article is reachable from Settings.
            text.setText(R.string.two_fa_guild_mfa_warning)
        }
    }

    /** A transparent spacer whose height tracks the bottom navigation bar. */
    class ItemSpace(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : MGRecyclerViewHolder<WidgetChannelsListAdapter, ChannelListItem>(layoutResId, adapter) {

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            itemView.layoutParams = itemView.layoutParams.apply {
                height = adapter.bottomNavHeight
            }
        }
    }

    class ItemGuildRoleSubscriptionsOverview(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as LinearLayout
        private val name: TextView = itemView.findViewById(R.id.directory_channel_name)

        init {
            root.setOnClickListener { adapter.onViewGuildRoleSubscriptions() }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            root.setBackgroundAndKeepPadding(
                ContextCompat.getDrawable(
                    root.context,
                    root.context.getThemedDrawableRes(R.attr.drawable_overlay_channels),
                ),
            )
            name.setText(R.string.guild_role_subscriptions_channel_label)
        }
    }

    class ItemGuildOnboarding(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {
        init {
            itemView.setOnClickListener { adapter.onViewGuildOnboarding() }
        }
    }

    class ItemGuildScheduledEvents(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as ConstraintLayout
        private val count: TextView =
            itemView.findViewById(R.id.channels_list_item_guild_scheduled_event_count)
        private var boundItem: ChannelListItemGuildScheduledEvents? = null

        init {
            root.setOnClickListener { boundItem?.let { adapter.onViewGuildScheduledEvents(it.guild) } }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemGuildScheduledEvents
            boundItem = item
            count.visibility = if (item.data.isNotEmpty()) View.VISIBLE else View.GONE
            count.text = item.data.size.toString()
            root.setBackgroundAndKeepPadding(
                ContextCompat.getDrawable(
                    root.context,
                    root.context.getThemedDrawableRes(R.attr.drawable_overlay_channels),
                ),
            )
        }
    }

    class ItemGuildJoinRequest(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val card: ConstraintLayout =
            itemView.findViewById(R.id.channels_list_item_guild_join_request)
        private val button: MaterialButton =
            itemView.findViewById(R.id.channels_list_item_guild_join_request_button)
        private val label: TextView =
            itemView.findViewById(R.id.channels_list_item_guild_join_request_label)

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            if (data !is ChannelListItemGuildJoinRequest) return
            val context = itemView.context

            // A member who is no longer pending has nothing left to review.
            if (!data.member.pending) {
                card.visibility = View.GONE
            }

            when (data.guildJoinRequest.applicationStatus) {
                ApplicationStatus.STARTED -> {
                    label.setText(R.string.member_verification_notice_text)
                    button.setText(R.string.member_verification_notice_cta)
                    button.setBackgroundColor(ThemedColor.resolve(context, R.attr.color_brand))
                    button.setOnClickListener { adapter.onOpenMemberVerification() }
                    card.visibility = View.VISIBLE
                }
                ApplicationStatus.PENDING -> {
                    label.setText(R.string.member_verification_pending_application_notice_title)
                    button.setText(R.string.member_verification_pending_application_modal_cancel)
                    button.setBackgroundColor(
                        ThemedColor.resolve(context, R.attr.colorBackgroundAccent),
                    )
                    // The form is already submitted; re-opening it would let the user overwrite an
                    // application under review, so this asks about withdrawing instead.
                    button.setOnClickListener {
                        adapter.onShowApplicationStatus(MemberVerificationPendingViewModel.DialogState.CANCEL)
                    }
                    card.visibility = View.VISIBLE
                }
                ApplicationStatus.REJECTED -> {
                    label.setText(R.string.member_verification_application_rejected_notice_title)
                    button.setText(R.string.member_verification_learn_more)
                    button.setBackgroundColor(
                        ThemedColor.resolve(context, R.attr.colorBackgroundAccent),
                    )
                    button.setOnClickListener {
                        adapter.onShowApplicationStatus(MemberVerificationPendingViewModel.DialogState.REJECTED)
                    }
                    card.visibility = View.VISIBLE
                }
                else -> card.visibility = View.GONE
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Stage / event rows
    // ------------------------------------------------------------------------------------------

    class ItemStageActiveEvent(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val root = itemView as ConstraintLayout
        private val channelSeparator: ImageView = itemView.findViewById(R.id.channel_separator)
        private val eventChannel: TextView = itemView.findViewById(R.id.event_channel)
        private val connectButton: MaterialButton = itemView.findViewById(R.id.event_connect_button)
        private val eventTopic: TextView = itemView.findViewById(R.id.event_topic)
        private val liveNowLabel: TextView = itemView.findViewById(R.id.live_now_label)
        private val listeners: TextView = itemView.findViewById(R.id.stage_event_listeners)
        private val userSummary: UserSummaryView = itemView.findViewById(R.id.user_summary)
        private val userSummaryLabel: MaterialTextView =
            itemView.findViewById(R.id.user_summary_label)

        init {
            // Bound once in the constructor, not per-bind: the button reads the current row out
            // of the adapter, so it stays correct across recycling without being reattached.
            connectButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val item = adapter.getItem(position) as? ChannelListItemActiveEvent
                    ?: return@setOnClickListener
                val location = item.data.locationInfo
                val vocalChannel = (location as? GuildScheduledEventLocationInfo.ChannelLocation)
                    ?.channel
                    ?.takeIf { it.isGuildVocalChannel() }
                if (vocalChannel != null) {
                    adapter.onCallChannel(vocalChannel)
                    return@setOnClickListener
                }
                val eventId = item.data.eventId
                if (location is GuildScheduledEventLocationInfo.ExternalLocation && eventId != null) {
                    adapter.onViewGuildScheduledEventDetails(eventId)
                }
            }
        }

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val event = (data as ChannelListItemActiveEvent).data
            val channel =
                (event.locationInfo as? GuildScheduledEventLocationInfo.ChannelLocation)?.channel
            val isStage = channel?.type == Channel.GUILD_STAGE_VOICE
            val isExternal = event.locationInfo is GuildScheduledEventLocationInfo.ExternalLocation

            val locationIcon = ContextCompat.getDrawable(
                root.context,
                when {
                    isStage -> R.drawable.ic_channel_stage_16dp
                    channel?.type == Channel.GUILD_VOICE -> R.drawable.ic_channel_voice_16dp
                    else -> R.drawable.ic_location_16dp
                },
            )

            liveNowLabel.setText(
                if (isExternal) R.string.live_event else R.string.stage_channel_live_now,
            )
            eventTopic.text = event.topic
            eventChannel.text = channel?.getDisplayNameOrDefault(root.context)
                ?: event.locationInfo.locationName
            eventChannel.setCompoundDrawablesWithIntrinsicBounds(locationIcon, null, null, null)

            // Only a stage has an audience to count, so both the count and the dot separating it
            // from the channel name are stage-only.
            listeners.visibility = if (isStage) View.VISIBLE else View.GONE
            listeners.text = listeners.i18nFormat(
                R.string.listening_count,
                "count" to event.audienceSize,
            )
            channelSeparator.visibility = if (isStage) View.VISIBLE else View.GONE

            val hasSpeakers = event.speakers.isNotEmpty()
            userSummary.visibility = if (hasSpeakers) View.VISIBLE else View.GONE
            userSummaryLabel.visibility = if (hasSpeakers) View.VISIBLE else View.GONE
            if (hasSpeakers) {
                userSummary.configure(event.speakers)
                userSummaryLabel.text = speakerSummary(event.speakers)
            }

            connectButton.setText(
                when {
                    isExternal -> R.string.see_detail
                    !isStage -> R.string.join
                    else -> R.string.stage_channel_join_button
                },
            )
        }

        private fun speakerSummary(speakers: List<UserGuildMember>): CharSequence {
            val first = speakers.first().let { it.user.getDisplayName(it.guildMember?.identityNick()) }
            if (speakers.size == 1) return first
            return userSummaryLabel.i18nFormat(
                R.string.user_summary_with_others,
                "name" to first,
                "count" to userSummaryLabel.i18nPlural(
                    R.plurals.user_summary_with_others_count,
                    speakers.size - 1,
                    "count" to speakers.size - 1,
                ),
            )
        }
    }

    class ItemStageChannelAudienceCount(
        @LayoutRes layoutResId: Int,
        adapter: WidgetChannelsListAdapter,
    ) : Item(layoutResId, adapter) {

        private val count: TextView = itemView.findViewById(R.id.stage_channels_audience_count)

        override fun onConfigure(position: Int, data: ChannelListItem) {
            super.onConfigure(position, data)
            val item = data as ChannelListItemStageAudienceCount
            if (item.audienceSize != 0) {
                count.text = count.i18nFormat(
                    R.string.stage_channel_audience_count,
                    "userCount" to item.audienceSize,
                )
            } else {
                count.setText(R.string.stage_channel_no_audience)
            }
        }
    }

    companion object {
        /** Badges saturate at 99; the reference prints the raw clamp, not "99+". */
        private const val MAX_MENTION_COUNT = 99
        private const val STATUS_EDIT_ICON_DP = 14
        private const val OTHER_SESSION_ALPHA = 0.5f

        private val STATUS_PARSER by lazy {
            DiscordParser.createParser(
                allowMaskedLinks = false,
                supportEntityMentions = false,
                renderBlockQuotes = false,
                renderCodeBlocks = false,
                renderSubtext = false,
                renderLinks = false,
            )
        }

        /** `M:SS`, or `H:MM:SS` from the first hour on, as the desktop client shows it. */
        internal fun formatElapsed(elapsedMs: Long): String {
            val seconds = maxOf(0L, elapsedMs / 1000L)
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) {
                String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
            } else {
                String.format(Locale.ROOT, "%d:%02d", m, s)
            }
        }
    }
}

/**
 * Resolves a themed font attribute.
 *
 * Belongs in `com.discord.utilities.font.FontUtils`, which does not exist yet.
 */
private fun Context.themedFont(@AttrRes attribute: Int): Typeface? {
    val value = TypedValue()
    if (!theme.resolveAttribute(attribute, value, true) || value.resourceId == 0) return null
    return runCatching { ResourcesCompat.getFont(this, value.resourceId) }.getOrNull()
}

/**
 * Whether the participant's microphone is off for any reason at all — their own mute, a server
 * mute, or stage suppression.
 *
 * Belongs in `com.discord.api.voice.state.VoiceStateUtils`; verified against the reference at
 * bytecode level (`selfMute || mute || suppress`).
 */
private fun VoiceState.isMutedByAnySource(): Boolean = selfMute || mute || suppress
