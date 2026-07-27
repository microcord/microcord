package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.string.format
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.guildscheduledevent.buttonconfiguration.ButtonConfiguration
import com.discord.widgets.guildscheduledevent.buttonconfiguration.DirectoryButtonConfiguration
import com.discord.widgets.guildscheduledevent.buttonconfiguration.GuildButtonConfiguration
import com.discord.widgets.hubs.events.HubGuildScheduledEventData
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The event card, used verbatim by every surface that shows an event.
 *
 * The surfaces differ in only three ways — which buttons the card offers, what background it
 * draws, and whether the guild identity row replaces the bare location line — so they all funnel
 * through [configureInternal] and then apply their own deltas. The card never reads a store:
 * everything it renders arrives as an argument.
 */
class GuildScheduledEventItemView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {
    private val root: ConstraintLayout
    private val buttons: GuildScheduledEventBottomButtonView
    private val locationContainer: ConstraintLayout
    private val creatorAvatar: SimpleDraweeView
    private val date: GuildScheduledEventDateView
    private val description: LinkifiedTextView
    private val channelName: TextView
    private val guildIcon: SimpleDraweeView
    private val guildPlaceholder: TextView
    private val guildName: TextView
    private val rsvpCount: TextView
    private val title: TextView
    private val standaloneIcon: ImageView
    private val standaloneName: LinkifiedTextView

    /** Both parsers cache their last render against their input, so they are per-card. */
    private val descriptionParser: GuildScheduledEventDescriptionParser by lazy {
        GuildScheduledEventDescriptionParser(description)
    }
    private val locationParser: GuildScheduledEventExternalLocationParser by lazy {
        GuildScheduledEventExternalLocationParser()
    }

    init {
        LayoutInflater.from(context).inflate(R.layout.guild_scheduled_event_item_view, this)
        root = requireNotNull(getChildAt(0) as? ConstraintLayout)
        buttons = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_buttom_view))
        locationContainer = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_channel_guild_container))
        creatorAvatar = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_creator_avatar))
        date = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_date_view))
        description = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_desc_text))
        channelName = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_guild_channel_name))
        guildIcon = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_guild_icon))
        guildPlaceholder = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_guild_icon_placeholder_text))
        guildName = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_guild_name))
        rsvpCount = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_rsvp_text))
        title = requireNotNull(findViewById(R.id.guild_scheduled_event_list_item_title_text))
        standaloneIcon = requireNotNull(findViewById(R.id.standalone_channel_icon))
        standaloneName = requireNotNull(findViewById(R.id.standalone_channel_name))
    }

    // region entry points

    @JvmOverloads
    fun configure(
        event: GuildScheduledEvent,
        channel: Channel? = null,
        guild: Guild? = null,
        creator: UserGuildMember? = null,
        buttonConfiguration: ButtonConfiguration,
        onCardClicked: View.OnClickListener? = null,
    ) = configureInternal(
        guildScheduledEvent = event,
        locationInfo = GuildScheduledEventLocationInfo.buildLocationInfo(event, channel),
        buttonConfiguration = buttonConfiguration,
        guild = guild,
        creator = creator,
        onCardClicked = onCardClicked,
    )

    /**
     * The card inside a scheduled-event invite embed in chat.
     *
     * The only surface where the viewer may not be a member of the guild, so the button pair is
     * swapped after the fact: an outsider gets "Join Server" in the primary slot, a member gets
     * the interested toggle in the secondary one.
     */
    fun configureInChatList(
        guildScheduledEvent: GuildScheduledEvent,
        channel: Channel?,
        guild: Guild?,
        creator: UserGuildMember?,
        isInGuild: Boolean,
        isRsvped: Boolean,
        canConnect: Boolean,
        isConnected: Boolean,
        onCardClicked: View.OnClickListener?,
        rsvpButtonClickListener: View.OnClickListener,
        joinServerButtonClickListener: View.OnClickListener,
        shareButtonClickListener: View.OnClickListener,
    ) {
        configureInternal(
            guildScheduledEvent = guildScheduledEvent,
            locationInfo = GuildScheduledEventLocationInfo.buildLocationInfo(guildScheduledEvent, channel),
            buttonConfiguration = GuildButtonConfiguration(
                guildScheduledEvent = guildScheduledEvent,
                canRsvp = true,
                canStartEvent = false,
                isConnected = isConnected,
                canConnect = canConnect,
                isInGuild = true,
                isRsvped = isRsvped,
                isDetailView = false,
                onRsvpButtonClicked = rsvpButtonClickListener,
                onJoinButtonClicked = joinServerButtonClickListener,
                onStartEventButtonClicked = joinServerButtonClickListener,
                onShareButtonClicked = shareButtonClickListener,
            ),
            guild = guild,
            creator = creator,
            onCardClicked = onCardClicked,
        )
        root.background = ContextCompat.getDrawable(
            context,
            if (isConnected && guildScheduledEvent.status == GuildScheduledEventStatus.ACTIVE) {
                R.drawable.bg_guild_scheduled_event_list_item_connected_secondary
            } else {
                R.drawable.bg_guild_scheduled_event_list_item_secondary
            },
        )
        rsvpCount.backgroundTintList = ColorCompat.createDefaultColorStateList(
            ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary),
        )
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dpToPixels(8)
            title.layoutParams = it
        }

        val primaryText = requireNotNull(buttons.findViewById<TextView>(R.id.primary_button_text))
        primaryText.setText(R.string.guild_event_invite_embed_join_server_button_label)
        primaryText.visible(!isInGuild)
        requireNotNull(buttons.findViewById<View>(R.id.primary_button)).visible(!isInGuild)
        requireNotNull(buttons.findViewById<View>(R.id.secondary_button)).visible(isInGuild)
        requireNotNull(buttons.findViewById<View>(R.id.secondary_button_text)).visible(isInGuild)
    }

    /** A row of the guild's event list sheet. */
    fun configureInEventList(
        item: GuildScheduledEventListItem.Event,
        cardClickListener: View.OnClickListener,
        rsvpButtonClickListener: View.OnClickListener,
        eventStartButtonClickListener: View.OnClickListener,
        shareButtonClickListener: View.OnClickListener,
        joinButtonClickListener: View.OnClickListener,
    ) {
        configureInternal(
            guildScheduledEvent = item.event,
            locationInfo = GuildScheduledEventLocationInfo.buildLocationInfo(item.event, item.channel),
            buttonConfiguration = GuildButtonConfiguration(
                guildScheduledEvent = item.event,
                canRsvp = true,
                canStartEvent = item.canStartEvent,
                isConnected = item.isConnected,
                canConnect = item.canConnect,
                isInGuild = true,
                isRsvped = item.isRsvped,
                isDetailView = false,
                onRsvpButtonClicked = rsvpButtonClickListener,
                onJoinButtonClicked = joinButtonClickListener,
                onStartEventButtonClicked = eventStartButtonClickListener,
                onShareButtonClicked = shareButtonClickListener,
            ),
            guild = null,
            creator = item.creator,
            onCardClicked = cardClickListener,
        )
        root.background = ContextCompat.getDrawable(
            context,
            if (item.isConnected && item.event.status == GuildScheduledEventStatus.ACTIVE) {
                R.drawable.bg_guild_scheduled_event_list_item_connected_primary
            } else {
                R.drawable.bg_guild_scheduled_event_list_item_primary
            },
        )
    }

    /**
     * A row of a hub's directory event list.
     *
     * The location line is deliberately suppressed: a hub lists events from guilds the viewer may
     * not have joined, whose channels they cannot resolve. The guild identity row takes its place.
     */
    fun configureInDirectoryEventList(
        eventData: HubGuildScheduledEventData,
        cardClickListener: View.OnClickListener,
        secondaryButtonOnClickListener: View.OnClickListener,
        primaryButtonOnClickListener: View.OnClickListener,
        shareButtonOnClickListener: View.OnClickListener,
    ) = configureInternal(
        guildScheduledEvent = eventData.event,
        locationInfo = null,
        buttonConfiguration = DirectoryButtonConfiguration(
            guildScheduledEvent = eventData.event,
            isInGuild = eventData.isInGuild,
            isRsvped = eventData.isRsvped,
            isDetailView = false,
            primaryButtonOnClickListener = primaryButtonOnClickListener,
            secondaryButtonOnClickListener = secondaryButtonOnClickListener,
            shareButtonOnClickListener = shareButtonOnClickListener,
            extrasButtonOnClickListener = null,
        ),
        // `models.guild.Guild` has no api-Guild constructor in this project (`StoreGuilds.merge`
        // is private). The card reads only id, name and icon — `shortName` is derived from name —
        // so those three are supplied. Replace with the real conversion when it is exposed.
        guild = eventData.event.guild?.let { Guild(id = it.id, name = it.name, icon = it.icon) },
        creator = null,
        onCardClicked = cardClickListener,
    )

    /**
     * The card as the create/edit preview screen shows it: no buttons, and the schedule read from
     * the unsaved form rather than from a server-side event.
     */
    fun configureAsPreview(
        eventModel: GuildScheduledEventModel,
        channel: Channel?,
        creator: UserGuildMember?,
    ) {
        val startMillis = GuildScheduledEventPickerDateTime.toMillis(eventModel.startDate, eventModel.startTime)
        val endMillis = if (eventModel.endDate != null && eventModel.endTime != null) {
            GuildScheduledEventPickerDateTime.toMillis(eventModel.endDate, eventModel.endTime)
        } else {
            null
        }
        root.background =
            ContextCompat.getDrawable(context, R.drawable.bg_guild_scheduled_event_list_item_floating)
        rsvpCount.backgroundTintList = ColorCompat.createDefaultColorStateList(
            ColorCompat.getThemedColor(context, R.attr.colorBackgroundTertiary),
        )
        configureCreator(creator)
        rsvpCount.setTextAndVisibilityBy(eventModel.userCount?.toString())
        buttons.visibility = View.GONE
        title.text = eventModel.name
        descriptionParser.configureDescription(eventModel.description, eventModel.guildId)
        date.configure(startMillis, endMillis, eventModel.entityType, GuildScheduledEventStatus.SCHEDULED)
        configureLocation(GuildScheduledEventLocationInfo.buildLocationInfo(eventModel, channel), null)
        root.setOnClickListener(null)
        root.isClickable = false
    }

    /**
     * The card pinned above a voice or stage call.
     *
     * [isCollapsed] is the call sheet's peek state: it hides the description and the buttons and
     * clamps the title to a single line.
     */
    fun configureInVoiceChannel(
        guildScheduledEvent: GuildScheduledEvent,
        creator: UserGuildMember?,
        canStartEvent: Boolean,
        isCollapsed: Boolean,
        canConnect: Boolean,
        isConnected: Boolean,
        onCardClicked: View.OnClickListener,
        onStartEventButtonClicked: View.OnClickListener,
    ) {
        configureInternal(
            guildScheduledEvent = guildScheduledEvent,
            locationInfo = null,
            buttonConfiguration = GuildButtonConfiguration(
                guildScheduledEvent = guildScheduledEvent,
                canRsvp = false,
                canStartEvent = canStartEvent,
                isConnected = isConnected,
                canConnect = canConnect,
                isInGuild = true,
                isRsvped = false,
                isDetailView = false,
                onStartEventButtonClicked = onStartEventButtonClicked,
            ),
            guild = null,
            creator = creator,
            onCardClicked = onCardClicked,
        )
        root.background = ContextCompat.getDrawable(
            context,
            if (isConnected && guildScheduledEvent.status == GuildScheduledEventStatus.ACTIVE) {
                R.drawable.bg_guild_scheduled_event_list_item_connected_secondary
            } else {
                R.drawable.bg_guild_scheduled_event_list_item_secondary
            },
        )
        rsvpCount.visibility = View.GONE
        if (isCollapsed) description.visibility = View.GONE
        buttons.visible(canStartEvent && !isCollapsed)
        title.maxLines = if (isCollapsed) 1 else Int.MAX_VALUE
        title.ellipsize = if (isCollapsed) TextUtils.TruncateAt.END else null
    }

    // endregion

    private fun configureInternal(
        guildScheduledEvent: GuildScheduledEvent,
        locationInfo: GuildScheduledEventLocationInfo?,
        buttonConfiguration: ButtonConfiguration,
        guild: Guild?,
        creator: UserGuildMember?,
        onCardClicked: View.OnClickListener?,
    ) {
        buttons.visible(buttonConfiguration.isAnyButtonVisible)
        buttons.configure(buttonConfiguration)
        rsvpCount.setTextAndVisibilityBy(guildScheduledEvent.userCount?.format(context))
        configureCreator(creator)
        date.configure(guildScheduledEvent)
        title.text = guildScheduledEvent.name
        descriptionParser.configureDescription(guildScheduledEvent.description, guildScheduledEvent.guildId)
        configureLocation(locationInfo, guild)
        root.setOnClickListener(onCardClicked)
        root.isClickable = onCardClicked != null
    }

    private fun configureCreator(creator: UserGuildMember?) {
        creatorAvatar.visible(creator != null)
        IconUtils.setIcon(
            creatorAvatar,
            creator?.let { IconUtils.getForGuildMemberOrUser(it.user, it.guildMember) },
            R.dimen.avatar_size_standard,
        )
    }

    /**
     * Three shapes: nothing at all, a bare location line, or the guild identity row with the
     * location beside it. The bare form is what a call or a details sheet shows, where the guild is
     * already unambiguous.
     */
    private fun configureLocation(location: GuildScheduledEventLocationInfo?, guild: Guild?) {
        if (location == null && guild == null) {
            locationContainer.visibility = View.GONE
            return
        }
        locationContainer.visibility = View.VISIBLE

        val standalone = guild == null
        standaloneIcon.visible(standalone)
        standaloneName.visible(standalone)
        guildIcon.visible(!standalone)
        guildName.visible(!standalone)
        guildPlaceholder.visible(!standalone && guild?.hasIcon() == false)
        channelName.visible(!standalone && location != null)

        if (standalone) {
            standaloneIcon.setImageResource(location!!.locationIcon)
            standaloneName.text = locationParser.getTextFromLocation(context, location)
            return
        }
        guildPlaceholder.text = guild?.shortName
        guildName.text = guild?.name
        IconUtils.setIcon(guildIcon, IconUtils.getForGuild(guild), R.dimen.avatar_size_standard)
        channelName.setTextAndVisibilityBy(
            location?.let { locationParser.getTextFromLocation(context, it) },
        )
        channelName.setCompoundDrawablesWithIntrinsicBounds(location?.locationIconSmall ?: 0, 0, 0, 0)
    }

    private fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }
}
