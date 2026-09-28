package com.discord.widgets.chat.list.adapter

import android.content.Context
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppToast
import com.discord.databinding.WidgetChatListAdapterItemInviteBinding
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.isGroup
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.guild.GuildFeature
import com.discord.i18n.i18nFormat
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.user.User
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.SnowflakeUtils.toSnowflake
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.time.ClockFactory
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.views.ThemedColor
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.InviteEntry
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.guilds.invite.requiresApplication
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

    private val binding = WidgetChatListAdapterItemInviteBinding.bind(itemView)
    private val splashChangeDetector = MGImages.DistinctChangeDetector()

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
        val relationships = StoreStream.getUserRelationships()
        return ObservationDeckProvider.get()
            .connectRx(users, guilds, channels, accessibility, relationships)
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
                        isFriendOfInviter = invite.getInviter()?.let {
                            relationships.getRelationships()[it.id] == ModelUserRelationship.TYPE_FRIEND
                        } == true,
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
        binding.itemInviteHeader.setText(R.string.instant_invite_resolving)
        binding.itemInviteName.text = null
        binding.itemInviteName.setTextColor(ThemedColor.resolve(itemView.context, R.attr.primary_100))
        binding.itemInviteName.setBackgroundResource(R.drawable.drawable_empty_text_placeholder_dark)
        binding.itemInviteImage.setActualImageResource(R.drawable.drawable_empty_text_placeholder_dark)
        iconUrl = null
        binding.itemInviteImageText.visibility = View.GONE
        binding.itemInviteChannelName.visibility = View.GONE
        binding.itemInviteMemberContainer.visibility = View.GONE
        binding.itemInviteMentionButton.visibility = View.GONE
        binding.itemInviteJoinButton.visibility = View.GONE
        binding.itemInviteJoinedButton.visibility = View.GONE
        binding.itemInviteLoadingButton.visibility = View.VISIBLE
        binding.itemInviteHubLayout.visibility = View.GONE
    }

    private fun configureInvalidUI(model: Model.Invalid?) {
        resetResolvedViews()
        val context = itemView.context
        val isInviter = model?.authorUser?.id == model?.meId && model?.authorUser != null

        binding.itemInviteHeader.setText(
            if (isInviter) {
                R.string.invite_button_title_inviter_invalid
            } else {
                R.string.invite_button_title_invited_invalid
            },
        )
        binding.itemInviteName.setText(R.string.invite_button_invalid)
        binding.itemInviteName.setTextColor(ContextCompat.getColor(context, R.color.status_red_500))
        binding.itemInviteName.setBackgroundResource(0)
        binding.itemInviteImage.setActualImageResource(R.drawable.img_wump_trash_dark)
        iconUrl = null
        binding.itemInviteImageText.visibility = View.GONE

        val author = model?.authorUser
        binding.itemInviteMemberContainer.setInvalidText(
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
        binding.itemInviteChannelName.visibility = View.GONE
        binding.itemInviteMemberContainer.visibility = View.VISIBLE

        // The sender is offered a replacement invite; everyone else is offered a way to nudge them.
        binding.itemInviteMentionButton.setText(if (isInviter) R.string.create_instant_invite else R.string.mention)
        // The sender can only be offered a replacement invite when the dead one still names a
        // guild to create it in.
        val canCreateReplacement = model?.channel != null
        val showMentionButton = if (isInviter) canCreateReplacement else true
        binding.itemInviteMentionButton.visibility = if (showMentionButton) View.VISIBLE else View.GONE
        binding.itemInviteMentionButton.setOnClickListener { view ->
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

        binding.itemInviteJoinButton.visibility = View.GONE
        binding.itemInviteJoinedButton.visibility = View.GONE
        binding.itemInviteLoadingButton.visibility = View.GONE
        binding.itemInviteHubLayout.visibility = View.GONE
    }

    private fun configureResolvedUI(model: Model.Resolved) {
        val context = itemView.context
        val invite = model.invite
        if (invite.isFriendInvite()) return configureFriendInviteUI(model)
        val guild = invite.guild
        val isInviter = model.authorUser != null && model.authorUser.id == model.meId
        val isSmallGuild = (guild?.memberCount ?: 0) < SMALL_GUILD_MEMBER_COUNT
        val isGroupDM = invite.channel?.isGroup() == true
        val isHub = guild?.features?.contains(GuildFeature.HUB) == true

        val splashHash = guild?.splash
        IconUtils.setIcon(
            binding.itemInviteSplash,
            splashHash?.let { IconUtils.getGuildSplashUrl(guild.id, it, displayWidthPx()) },
            changeDetector = splashChangeDetector,
        )
        binding.itemInviteSplashContainer.visibility = if (splashHash != null) View.VISIBLE else View.GONE

        binding.itemInviteHeader.text = when {
            isGroupDM && isInviter -> context.getString(R.string.invite_button_title_inviter_group_dm)
            isInviter -> context.getString(R.string.invite_button_title_inviter)
            isGroupDM -> context.getString(R.string.invite_button_title_invited_group_dm)
            isHub -> context.getString(R.string.invite_button_title_invited_hub)
            isSmallGuild && invite.getInviter() != null -> context.i18nFormat(
                R.string.instant_invite_you_have_been_invited_to_join_by_user,
                "username" to invite.getInviter()?.username,
            )
            else -> context.getString(R.string.invite_button_title_invited)
        }

        binding.itemInviteName.text = if (isGroupDM) {
            (model.channel ?: invite.channel)?.getDisplayNameOrDefault(context)
        } else {
            guild?.name
        }
        binding.itemInviteName.setTextColor(ThemedColor.resolve(context, R.attr.primary_100))
        binding.itemInviteName.setBackgroundResource(0)

        when {
            isGroupDM -> {
                binding.itemInviteImage.visibility = View.VISIBLE
                updateIconUrlIfChanged(IconUtils.getForChannel(invite.channel))
                binding.itemInviteImageText.visibility = View.GONE
            }
            else -> {
                binding.itemInviteImage.visibility = View.VISIBLE
                updateIconUrlIfChanged(
                    IconUtils.getForGuild(
                        guild?.id,
                        guild?.icon,
                        DEFAULT_GUILD_ICON,
                        model.shouldAnimateGuildIcon,
                    ),
                )
                // The initials stand in for a guild that has no icon of its own.
                binding.itemInviteImageText.setTextAndVisibilityBy(
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
        binding.itemInviteLoadingButton.visibility = View.GONE
        binding.itemInviteJoinedButton.visibility = if (isJoined) View.VISIBLE else View.GONE
        binding.itemInviteJoinButton.visibility = if (isJoined) View.GONE else View.VISIBLE
        binding.itemInviteJoinButton.isEnabled = true
        binding.itemInviteJoinButton.setText(if (invite.requiresApplication()) R.string.invite_button_apply_to_join else R.string.join_guild)
        binding.itemInviteMentionButton.visibility = View.GONE
        val instanceId = inviteInstanceId(item.messageId, item.inviteCode)
        binding.itemInviteJoinButton.setOnClickListener { joinServerOrDM(javaClass, invite, inviteInstanceId = instanceId) }
        binding.itemInviteJoinedButton.setOnClickListener { joinServerOrDM(javaClass, invite, inviteInstanceId = instanceId) }

        // The reference gates the hub e-mail prompt on a growth experiment; the experiment client
        // is not reconstructed, so the prompt stays off, which is its default state.
        binding.itemInviteHubLayout.visibility = View.GONE
        binding.itemInviteHubLink.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Friend invite card, backported from the modern client's `FriendInvite` embed: the inviter's
     * avatar and name, with "Add Friend" (disabled on your own link) or "Send Message" once the
     * two of you are already friends.
     */
    private fun configureFriendInviteUI(model: Model.Resolved) {
        resetResolvedViews()
        val context = itemView.context
        val invite = model.invite
        val inviter = invite.getInviter()
        val isOwnInvite = inviter?.id == model.meId

        binding.itemInviteHeader.setText(
            if (isOwnInvite) R.string.friend_invite_button_title_inviter else R.string.friend_invite_button_title_invited,
        )
        binding.itemInviteName.text = inviter?.username
        binding.itemInviteName.setTextColor(ThemedColor.resolve(context, R.attr.primary_100))
        binding.itemInviteName.setBackgroundResource(0)
        updateIconUrlIfChanged(
            inviter?.let { IconUtils.getForUser(it.id, it.avatar?.value, it.discriminator.toIntOrNull()) },
        )
        binding.itemInviteImageText.visibility = View.GONE
        binding.itemInviteChannelName.visibility = View.GONE
        binding.itemInviteMemberContainer.visibility = View.GONE
        binding.itemInviteMentionButton.visibility = View.GONE
        binding.itemInviteJoinedButton.visibility = View.GONE
        binding.itemInviteLoadingButton.visibility = View.GONE
        binding.itemInviteHubLayout.visibility = View.GONE

        val openDM = model.isFriendOfInviter && !isOwnInvite
        binding.itemInviteJoinButton.visibility = View.VISIBLE
        binding.itemInviteJoinButton.isEnabled = !isOwnInvite
        binding.itemInviteJoinButton.setText(if (openDM) R.string.send_message else R.string.add_friend)
        binding.itemInviteJoinButton.setOnClickListener { view ->
            if (inviter == null) return@setOnClickListener
            if (openDM) {
                ChannelSelector.getInstance().findAndSetDirectMessage(view.context, inviter.id)
            } else {
                joinServerOrDM(
                    javaClass,
                    invite,
                    inviteInstanceId = inviteInstanceId(item.messageId, item.inviteCode),
                    onInvitePostSuccess = {
                        AppToast.show(
                            view.context,
                            view.context.i18nFormat(R.string.friend_invite_accept_confirmation, "username" to inviter.username),
                        )
                    },
                )
            }
        }
    }

    private fun configureForTextChannel(isGroupDM: Boolean, invite: ModelInvite) {
        binding.itemInviteMemberContainer.visibility = if (invite.channel != null) View.VISIBLE else View.GONE
        // A group DM has no presence roll-up, so only the total is shown.
        binding.itemInviteMemberContainer.setOnline(invite.getApproximatePresenceCount().takeUnless { isGroupDM })
        binding.itemInviteMemberContainer.setMembers(invite.getApproximateMemberCount())
        // Not part of the reference, which leaves the previous row's channel name behind here.
        binding.itemInviteChannelName.visibility = View.GONE
    }

    private fun configureForVocalChannel(channel: Channel, context: Context) {
        binding.itemInviteMemberContainer.visibility = View.GONE
        val iconRes = when (channel.type) {
            Channel.GUILD_VOICE -> context.getThemedDrawableRes(R.attr.ic_volume_up)
            Channel.GUILD_STAGE_VOICE -> R.drawable.ic_channel_stage_24dp
            else -> 0
        }
        binding.itemInviteChannelName.setCompoundDrawablesWithIntrinsicBounds(iconRes, 0, 0, 0)
        binding.itemInviteChannelName.setTextAndVisibilityBy(channel.getDisplayNameOrDefault(context, includePrefix = false))
    }

    /**
     * Sets the icon only when the url actually changed, so a re-render caused by an unrelated
     * store update does not restart the image request and flash the row.
     */
    private fun updateIconUrlIfChanged(url: String?) {
        if (iconUrl == url) return
        IconUtils.setIcon(binding.itemInviteImage, url, R.dimen.avatar_size_large)
        iconUrl = url
    }

    /**
     * Undoes the view changes only [configureResolvedUI] makes, which a recycled holder would
     * otherwise keep: another guild's banner, and a hidden icon.
     */
    private fun resetResolvedViews() {
        binding.itemInviteSplashContainer.visibility = View.GONE
        IconUtils.setIcon(binding.itemInviteSplash, null as String?)
        binding.itemInviteImage.visibility = View.VISIBLE
    }

    private fun displayWidthPx(): Int = itemView.resources.displayMetrics.widthPixels

    /** What the reference calls a guild's short name: the possessive `'s ` collapsed to a space. */
    private fun shortGuildName(guildName: String): String = guildName.replace(POSSESSIVE, " ")

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
            val isFriendOfInviter: Boolean,
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
