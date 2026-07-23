package com.discord.widgets.guildscheduledevent

import android.content.Context
import android.content.res.ColorStateList
import android.text.util.Linkify
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.text.util.LinkifyCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.guildscheduledevent.GuildScheduledEvent
import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.models.guild.Guild
import com.discord.models.guild.UserGuildMember
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.guildscheduledevent.buttonconfiguration.ButtonConfiguration
import com.discord.widgets.guildscheduledevent.buttonconfiguration.GuildButtonConfiguration
import com.facebook.drawee.view.SimpleDraweeView
import java.text.NumberFormat

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

    fun configure(
        event: GuildScheduledEvent,
        channel: Channel? = null,
        guild: Guild? = null,
        creator: UserGuildMember? = null,
        buttonConfiguration: ButtonConfiguration,
        onCardClicked: View.OnClickListener? = null,
    ) {
        buttons.visible(buttonConfiguration.isAnyButtonVisible)
        buttons.configure(buttonConfiguration)
        rsvpCount.text = event.userCount?.let(NumberFormat.getIntegerInstance()::format)
        rsvpCount.visible(event.userCount != null)
        configureCreator(creator)
        date.configure(event)
        title.text = event.name
        description.text = event.description
        description.visible(!event.description.isNullOrEmpty())
        LinkifyCompat.addLinks(description, Linkify.WEB_URLS)
        configureLocation(GuildScheduledEventLocationInfo.buildLocationInfo(event, channel), guild)
        root.setOnClickListener(onCardClicked)
        root.isClickable = onCardClicked != null
    }

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
        val configuration = GuildButtonConfiguration(
            guildScheduledEvent, true, false, isConnected, canConnect, isInGuild, isRsvped,
            onRsvpButtonClicked = rsvpButtonClickListener,
            onJoinButtonClicked = joinServerButtonClickListener,
            onShareButtonClicked = shareButtonClickListener,
        )
        configure(guildScheduledEvent, channel, guild, creator, configuration, onCardClicked)
        root.background = ContextCompat.getDrawable(
            context,
            if (canConnect && guildScheduledEvent.status == GuildScheduledEventStatus.ACTIVE) {
                R.drawable.bg_guild_scheduled_event_list_item_connected_primary
            } else {
                R.drawable.bg_guild_scheduled_event_list_item_primary
            },
        )
        rsvpCount.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white_alpha_10))
        (title.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = (8 * resources.displayMetrics.density).toInt()
            title.layoutParams = it
        }
        val showInterested = isInGuild
        requireNotNull(buttons.findViewById<View>(R.id.primary_button)).visible(!showInterested)
        requireNotNull(buttons.findViewById<View>(R.id.primary_button_text)).visible(!showInterested)
        requireNotNull(buttons.findViewById<View>(R.id.secondary_button)).visible(showInterested)
        requireNotNull(buttons.findViewById<View>(R.id.secondary_button_text)).visible(showInterested)
    }

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
        val configuration = GuildButtonConfiguration(
            guildScheduledEvent, false, canStartEvent, isConnected, canConnect, true, false,
            onStartEventButtonClicked = onStartEventButtonClicked,
        )
        configure(guildScheduledEvent, creator = creator, buttonConfiguration = configuration, onCardClicked = onCardClicked)
        rsvpCount.visibility = View.GONE
        description.visible(!isCollapsed && !guildScheduledEvent.description.isNullOrEmpty())
        buttons.visible(canStartEvent && !isCollapsed)
        title.maxLines = if (isCollapsed) 1 else Int.MAX_VALUE
        title.ellipsize = if (isCollapsed) android.text.TextUtils.TruncateAt.END else null
        root.background = ContextCompat.getDrawable(
            context,
            if (isConnected) R.drawable.bg_guild_scheduled_event_list_item_connected_primary
            else R.drawable.bg_guild_scheduled_event_list_item_primary,
        )
    }

    private fun configureCreator(creator: UserGuildMember?) {
        creatorAvatar.visible(creator != null)
        if (creator == null) {
            creatorAvatar.setImageURI(null as String?)
            return
        }
        val user = creator.user
        val uri = user.avatar?.let {
            val extension = if (it.startsWith("a_")) "gif" else "webp"
            "https://cdn.discordapp.com/avatars/${user.id}/$it.$extension?size=32"
        } ?: "asset://asset/images/default_avatar_${user.discriminator % 5}.png"
        creatorAvatar.setImageURI(uri)
    }

    private fun configureLocation(location: GuildScheduledEventLocationInfo?, guild: Guild?) {
        locationContainer.visible(location != null || guild != null)
        if (location == null && guild == null) return
        val standalone = location != null && guild == null
        standaloneIcon.visible(standalone)
        standaloneName.visible(standalone)
        guildIcon.visible(!standalone)
        guildName.visible(!standalone)
        guildPlaceholder.visible(!standalone && guild?.hasIcon() == false)
        channelName.visible(!standalone && location != null)
        if (standalone) {
            standaloneIcon.setImageResource(location!!.locationIcon)
            standaloneName.text = location.locationName
            LinkifyCompat.addLinks(standaloneName, Linkify.WEB_URLS)
            return
        }
        guildPlaceholder.text = guild?.shortName
        guildName.text = guild?.name
        if (guild?.hasIcon() == true) {
            guildIcon.setImageURI("https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.webp?size=64")
        } else {
            guildIcon.setImageURI(null as String?)
        }
        channelName.text = location?.locationName
        channelName.setCompoundDrawablesWithIntrinsicBounds(location?.locationIconSmall ?: 0, 0, 0, 0)
    }

    private fun View.visible(show: Boolean) {
        visibility = if (show) View.VISIBLE else View.GONE
    }
}
