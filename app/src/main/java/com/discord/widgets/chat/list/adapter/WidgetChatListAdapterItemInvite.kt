package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isGroup
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.guild.GuildFeature
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelInvite
import com.discord.models.user.User
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.SnowflakeUtils.toSnowflake
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.utilities.view.rounded.RoundedRelativeLayout
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.views.ThemedColor
import com.discord.views.guilds.ServerMemberCount
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.InviteEntry
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.guilds.invite.requiresApplication
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable
import rx.Subscription

/**
 * The card rendered for a `discord.gg` link in a message body.
 *
 * The row starts with nothing but a code: it asks [StoreInstantInvites] to resolve it, shows a
 * skeleton while that is in flight, and then renders one of three faces — the resolved invite, or
 * the "invalid invite" state, which is a different apology depending on whether the viewer is the
 * person who sent the dead link.
 */
class WidgetChatListAdapterItemInvite(
    adapter: WidgetChatListAdapter,
) : WidgetChatListAdapterItemInviteBase(R.layout.widget_chat_list_adapter_item_invite, adapter) {

    private val splash: SimpleDraweeView = itemView.findViewById(R.id.item_invite_splash)
    private val splashContainer: RoundedRelativeLayout =
        itemView.findViewById(R.id.item_invite_splash_container)
    private val header: TextView = itemView.findViewById(R.id.item_invite_header)
    private val image: SimpleDraweeView = itemView.findViewById(R.id.item_invite_image)
    private val imageText: TextView = itemView.findViewById(R.id.item_invite_image_text)
    private val name: TextView = itemView.findViewById(R.id.item_invite_name)
    private val channelName: TextView = itemView.findViewById(R.id.item_invite_channel_name)
    private val memberCount: ServerMemberCount = itemView.findViewById(R.id.item_invite_member_container)
    private val joinButton: MaterialButton = itemView.findViewById(R.id.item_invite_join_button)
    private val joinedButton: MaterialButton = itemView.findViewById(R.id.item_invite_joined_button)
    private val mentionButton: MaterialButton = itemView.findViewById(R.id.item_invite_mention_button)
    private val loadingButton: View = itemView.findViewById(R.id.item_invite_loading_button)
    private val hubLayout: FrameLayout = itemView.findViewById(R.id.item_invite_hub_layout)
    private val hubLink: LinkifiedTextView = itemView.findViewById(R.id.item_invite_hub_link)

    private lateinit var item: InviteEntry
    private var iconUrl: String? = null

    override var subscription: Subscription? = null

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as InviteEntry
        item = entry

        val eventId = entry.eventId?.toSnowflake()
        StoreStream.getInstantInvites().fetchInviteIfNotLoaded(entry.inviteCode, eventId)
        observeModel(entry, eventId).ui().appSubscribe(
            WidgetChatListAdapterItemInvite::class.java,
            subscriptionHandler = { subscription = it },
            errorHandler = { configureInvalidUI(null) },
            onNext = ::configureUI,
        )
    }

    // region model

    private fun observeModel(entry: InviteEntry, eventId: Long?): Observable<Model> =
        StoreStream.getInstantInvites()
            .observeInvite(ModelInvite.getInviteStoreKey(entry.inviteCode, eventId))
            .switchMap { state ->
                when (state.inviteCardState()) {
                    InviteCardState.LOADING -> Observable.just<Model>(Model.Loading)
                    InviteCardState.RESOLVED ->
                        observeResolved(entry, (state as StoreInstantInvites.InviteState.Resolved).invite)
                    InviteCardState.INVALID -> observeInvalid(entry)
                }
            }

    private fun observeInvalid(entry: InviteEntry): Observable<Model> {
        val users = StoreStream.getUsers()
        return Observable.combineLatest(
            users.observeMeId(),
            users.observeUser(entry.userId),
        ) { meId, author -> Model.Invalid(author, meId, null) }
    }

    private fun observeResolved(entry: InviteEntry, invite: ModelInvite): Observable<Model> {
        val users = StoreStream.getUsers()
        val guilds = StoreStream.getGuilds()
        val channels = StoreStream.getChannels()
        val accessibility = StoreStream.getAccessibility()
        return ObservationDeckProvider.get()
            .connectRx(users, guilds, channels, accessibility)
            .map { _ ->
                val meId = users.getMe().id
                val author = users.getUsers()[entry.userId]
                val isMemberOfGuild = invite.guild?.let { guilds.getGuild(it.id) } != null
                val localChannel = invite.channel?.let { channels.getChannel(it.id) }

                val isResolvable = invite.isResolvableAt(ClockFactory.get().currentTimeMillis())
                if (isResolvable) {
                    Model.Resolved(
                        invite = invite,
                        meId = meId,
                        authorUser = author,
                        isMemberOfGuild = isMemberOfGuild,
                        channel = localChannel,
                        shouldAnimateGuildIcon = !accessibility.isReducedMotionEnabled(),
                    )
                } else {
                    Model.Invalid(author, meId, localChannel)
                }
            }
    }

    // endregion

    private fun configureUI(model: Model) = when (model) {
        is Model.Loading -> configureLoadingUI()
        is Model.Resolved -> configureResolvedUI(model)
        is Model.Invalid -> configureInvalidUI(model)
    }

    private fun configureLoadingUI() {
        resetResolvedViews()
        header.setText(R.string.instant_invite_resolving)
        name.text = null
        name.setTextColor(ThemedColor.resolve(itemView.context, R.attr.primary_100))
        name.setBackgroundResource(R.drawable.drawable_empty_text_placeholder_dark)
        image.setActualImageResource(R.drawable.drawable_empty_text_placeholder_dark)
        iconUrl = null
        imageText.visibility = View.GONE
        channelName.visibility = View.GONE
        memberCount.visibility = View.GONE
        mentionButton.visibility = View.GONE
        joinButton.visibility = View.GONE
        joinedButton.visibility = View.GONE
        loadingButton.visibility = View.VISIBLE
        hubLayout.visibility = View.GONE
    }

    private fun configureInvalidUI(model: Model.Invalid?) {
        resetResolvedViews()
        val context = itemView.context
        val isInviter = model?.authorUser?.id == model?.meId && model?.authorUser != null

        header.setText(
            if (isInviter) {
                R.string.invite_button_title_inviter_invalid
            } else {
                R.string.invite_button_title_invited_invalid
            },
        )
        name.setText(R.string.invite_button_invalid)
        name.setTextColor(ContextCompat.getColor(context, R.color.status_red_500))
        name.setBackgroundResource(0)
        image.setActualImageResource(R.drawable.img_wump_trash_dark)
        iconUrl = null
        imageText.visibility = View.GONE

        val author = model?.authorUser
        memberCount.setInvalidText(
            when {
                isInviter -> context.getString(R.string.invite_button_invalid_owner)
                author != null ->
                    context.i18nFormat(
                        R.string.instant_invite_ask_user_for_new_invite,
                        "username" to author.username,
                    )
                else -> context.getString(R.string.instant_invite_ask_for_new_invite)
            },
        )
        channelName.visibility = View.GONE
        memberCount.visibility = View.VISIBLE

        // The sender is offered a replacement invite; everyone else is offered a way to nudge them.
        mentionButton.setText(if (isInviter) R.string.create_instant_invite else R.string.mention)
        // The sender can only be offered a replacement invite when the dead one still names a
        // guild to create it in.
        val canCreateReplacement = model?.channel != null
        val showMentionButton = if (isInviter) canCreateReplacement else true
        mentionButton.visibility = if (showMentionButton) View.VISIBLE else View.GONE
        mentionButton.setOnClickListener { view ->
            if (isInviter) {
                val channel = model?.channel ?: return@setOnClickListener
                WidgetGuildInviteShare.launch(
                    view.context,
                    adapter.fragmentManager,
                    channel.guildId,
                    channel.id,
                    source = INVITE_SOURCE,
                )
            } else {
                StoreStream.getChat().appendMention(model?.authorUser, item.guildId)
            }
        }

        joinButton.visibility = View.GONE
        joinedButton.visibility = View.GONE
        loadingButton.visibility = View.GONE
        hubLayout.visibility = View.GONE
    }

    private fun configureResolvedUI(model: Model.Resolved) {
        val context = itemView.context
        val invite = model.invite
        val guild = invite.guild
        val isInviter = model.authorUser != null && model.authorUser.id == model.meId
        val isSmallGuild = (guild?.memberCount ?: 0) < SMALL_GUILD_MEMBER_COUNT
        val isGroupDM = invite.channel?.isGroup() == true
        val isHub = guild?.features?.contains(GuildFeature.HUB) == true
        // No channel and no guild, but somebody sent it: a friend invite to a plain DM.
        val isFriendInvite = invite.channel == null && guild == null && invite.getInviter() != null

        val splashHash = guild?.splash
        IconUtils.setIcon(
            splash,
            splashHash?.let { IconUtils.getGuildSplashUrl(guild.id, it, displayWidthPx()) },
        )
        splashContainer.visibility = if (splashHash != null) View.VISIBLE else View.GONE

        header.text = when {
            isGroupDM && isInviter -> context.getString(R.string.invite_button_title_inviter_group_dm)
            isInviter -> context.getString(R.string.invite_button_title_inviter)
            isGroupDM -> context.getString(R.string.invite_button_title_invited_group_dm)
            isHub -> context.getString(R.string.invite_button_title_invited_hub)
            isFriendInvite -> context.i18nFormat(
                R.string.instant_invite_you_have_been_invited_to_chat,
                "username" to invite.getInviter()?.username,
            )
            isSmallGuild && invite.getInviter() != null -> context.i18nFormat(
                R.string.instant_invite_you_have_been_invited_to_join_by_user,
                "username" to invite.getInviter()?.username,
            )
            else -> context.getString(R.string.invite_button_title_invited)
        }

        name.text = if (isGroupDM) {
            (model.channel ?: invite.channel)?.getDisplayNameOrDefault(context)
        } else {
            guild?.name
        }
        name.setTextColor(ThemedColor.resolve(context, R.attr.primary_100))
        name.setBackgroundResource(0)

        when {
            isFriendInvite -> image.visibility = View.GONE
            isGroupDM -> {
                image.visibility = View.VISIBLE
                updateIconUrlIfChanged(IconUtils.getForChannel(invite.channel))
                imageText.visibility = View.GONE
            }
            else -> {
                image.visibility = View.VISIBLE
                updateIconUrlIfChanged(
                    IconUtils.getForGuild(
                        guild?.id,
                        guild?.icon,
                        DEFAULT_GUILD_ICON,
                        model.shouldAnimateGuildIcon,
                    ),
                )
                // The initials stand in for a guild that has no icon of its own.
                imageText.setTextAndVisibilityBy(
                    guild?.name?.takeIf { guild.icon.isNullOrEmpty() }?.let(::shortGuildName),
                )
            }
        }

        val vocalChannel = model.channel?.takeIf { it.isGuildVocalChannel() }
        if (vocalChannel != null) {
            configureForVocalChannel(vocalChannel, context)
        } else {
            configureForTextChannel(isGroupDM, invite)
        }

        // For a group DM there is no guild to be a member of; having the channel locally is the
        // equivalent signal that the viewer is already in it.
        val isJoined = if (isGroupDM) model.channel != null else model.isMemberOfGuild
        loadingButton.visibility = View.GONE
        joinedButton.visibility = if (isJoined) View.VISIBLE else View.GONE
        joinButton.visibility = if (isJoined) View.GONE else View.VISIBLE
        joinButton.setText(if (invite.requiresApplication()) R.string.invite_button_apply_to_join else R.string.join_guild)
        mentionButton.visibility = View.GONE
        val instanceId = inviteInstanceId(item.messageId, item.inviteCode)
        joinButton.setOnClickListener { joinServerOrDM(javaClass, invite, inviteInstanceId = instanceId) }
        joinedButton.setOnClickListener { joinServerOrDM(javaClass, invite, inviteInstanceId = instanceId) }

        // The reference gates the hub e-mail prompt on a growth experiment; the experiment client
        // is not reconstructed, so the prompt stays off, which is its default state.
        hubLayout.visibility = View.GONE
        hubLink.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun configureForTextChannel(isGroupDM: Boolean, invite: ModelInvite) {
        memberCount.visibility = if (invite.channel != null) View.VISIBLE else View.GONE
        // A group DM has no presence roll-up, so only the total is shown.
        memberCount.setOnline(invite.getApproximatePresenceCount().takeUnless { isGroupDM })
        memberCount.setMembers(invite.getApproximateMemberCount())
        // Not part of the reference, which leaves the previous row's channel name behind here.
        channelName.visibility = View.GONE
    }

    private fun configureForVocalChannel(channel: Channel, context: Context) {
        memberCount.visibility = View.GONE
        val iconRes = when (channel.type) {
            Channel.GUILD_VOICE -> context.getThemedDrawableRes(R.attr.ic_volume_up)
            Channel.GUILD_STAGE_VOICE -> R.drawable.ic_channel_stage_24dp
            else -> 0
        }
        channelName.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        channelName.setTextAndVisibilityBy(channel.getDisplayNameOrDefault(context, includePrefix = false))
    }

    /**
     * Sets the icon only when the url actually changed, so a re-render caused by an unrelated
     * store update does not restart the image request and flash the row.
     */
    private fun updateIconUrlIfChanged(url: String?) {
        if (iconUrl == url) return
        IconUtils.setIcon(image, url, R.dimen.avatar_size_large)
        iconUrl = url
    }

    /**
     * Undoes the view changes only [configureResolvedUI] makes, which a recycled holder would
     * otherwise keep: another guild's banner, and the icon hidden for a friend invite.
     */
    private fun resetResolvedViews() {
        splashContainer.visibility = View.GONE
        IconUtils.setIcon(splash, null as String?)
        image.visibility = View.VISIBLE
    }

    private fun displayWidthPx(): Int = itemView.resources.displayMetrics.widthPixels

    /** What the reference calls a guild's short name: the possessive `'s ` collapsed to a space. */
    private fun shortGuildName(name: String): String = name.replace(POSSESSIVE, " ")

    /** The three faces of the card. */
    private sealed class Model {
        object Loading : Model()

        data class Resolved(
            val invite: ModelInvite,
            val meId: Long,
            val authorUser: User?,
            val isMemberOfGuild: Boolean,
            val channel: Channel?,
            val shouldAnimateGuildIcon: Boolean,
        ) : Model()

        data class Invalid(
            val authorUser: User?,
            val meId: Long,
            val channel: Channel?,
        ) : Model()
    }

    private companion object {
        /** Analytics source recorded against every join started from a chat invite card. */
        private const val INVITE_SOURCE = "Invite Button Embed"

        /** Below this the card names whoever sent the invite instead of the guild alone. */
        private const val SMALL_GUILD_MEMBER_COUNT = 200

        private const val DEFAULT_GUILD_ICON = "asset://asset/images/default_icon_selected.jpg"

        private val POSSESSIVE = Regex("'s ")
    }
}

internal enum class InviteCardState { LOADING, RESOLVED, INVALID }

internal fun StoreInstantInvites.InviteState.inviteCardState(): InviteCardState = when (this) {
    StoreInstantInvites.InviteState.Loading,
    StoreInstantInvites.InviteState.LoadFailed -> InviteCardState.LOADING
    is StoreInstantInvites.InviteState.Resolved -> InviteCardState.RESOLVED
    StoreInstantInvites.InviteState.Invalid -> InviteCardState.INVALID
}

internal fun ModelInvite.isResolvableAt(now: Long) = !isExpired(now) && !isRevoked()
