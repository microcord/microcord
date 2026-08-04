package com.discord.widgets.chat.list.adapter

import android.view.LayoutInflater
import android.view.View
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.api.embeddedactivities.EmbeddedActivityInstance
import com.discord.databinding.WidgetChatListAdapterItemActivityBinding
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.list.entries.ActivityCardState
import com.discord.widgets.chat.list.entries.ActivityMessageEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import rx.Observable
import rx.Subscription

class WidgetChatListAdapterItemActivity private constructor(
    adapter: WidgetChatListAdapter,
    private val binding: WidgetChatListAdapterItemActivityBinding,
) : WidgetChatListItem(binding.root, adapter) {

    constructor(adapter: WidgetChatListAdapter) : this(
        adapter,
        WidgetChatListAdapterItemActivityBinding.inflate(
            LayoutInflater.from(adapter.recycler.context),
            adapter.recycler,
            false,
        ),
    )

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as ActivityMessageEntry
        modelSubscription?.unsubscribe()
        binding.activityAction.setIsLoading(false)
        configureCellHighlight(
            Message(id = entry.messageId, channelId = entry.channelId),
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            entry.isPendingReply,
        )

        observe(entry).ui().appSubscribe(
            errorClass = WidgetChatListAdapterItemActivity::class.java,
            subscriptionHandler = { modelSubscription = it },
        ) { model -> bind(entry, model) }
    }

    private fun observe(entry: ActivityMessageEntry): Observable<Model> = Observable.combineLatest(
        StoreStream.getUsers().observeMe(),
        StoreStream.getEmbeddedActivities().observeInstancesForChannel(entry.channelId),
        StoreStream.getPermissions().observePermissionsForChannel(entry.channelId),
    ) { me, instances, permissions ->
        val active = entry.application?.id?.let(instances::get)
        Model(
            me = me,
            activeInstance = active,
            permissions = permissions,
        )
    }.distinctUntilChanged()

    private fun bind(entry: ActivityMessageEntry, model: Model) {
        val context = itemView.context
        val application = entry.application
        val participants = model.activeInstance?.participants.orEmpty()
        val participantIds = participants.mapTo(LinkedHashSet()) { it.userId }
        val state = ActivityCardState.evaluate(
            entry,
            model.me,
            model.activeInstance?.compositeInstanceId ?: model.activeInstance?.instanceId?.toString(),
            participantIds,
            model.permissions,
        )

        binding.activityIcon.setImageURI(application?.icon?.let { IconUtils.getApplicationIcon(application.id, it, 128) })
        binding.activityArt.visibility = if (application?.coverImage == null) View.GONE else View.VISIBLE
        binding.activityArt.setImageURI(application?.coverImage?.let { IconUtils.getApplicationIcon(application.id, it, 1024) })

        val knownUsers = StoreStream.getUsers().getUsers()
        val participantAvatarUrls = participants.mapNotNull { participant ->
            participant.member?.user?.let { user ->
                IconUtils.getForUser(user.id, user.avatar?.value, user.discriminator.toIntOrNull(), size = 64)
            } ?: IconUtils.getForUser(knownUsers[participant.userId], size = 64)
        }.ifEmpty {
            listOfNotNull(entry.inviter?.let { user ->
                IconUtils.getForUser(user.id, user.avatar?.value, user.discriminator.toIntOrNull(), size = 64)
            })
        }
        val avatars = listOf(binding.activityAvatar1, binding.activityAvatar2, binding.activityAvatar3)
        avatars.forEachIndexed { index, avatar ->
            val avatarUrl = participantAvatarUrls.getOrNull(index)
            avatar.visibility = if (avatarUrl == null) View.GONE else View.VISIBLE
            if (avatarUrl != null) avatar.setImageURI(avatarUrl)
        }
        binding.activityAvatars.visibility = if (participantAvatarUrls.isEmpty()) View.GONE else View.VISIBLE

        binding.activityName.text = when {
            state.reason != null -> reasonText(state.reason)
            state.isEnded -> context.getString(R.string.activity_message_ended_restart)
            participantIds.isEmpty() -> context.getString(R.string.activity_message_no_participants)
            else -> context.getString(R.string.activity_message_participant_count, participantIds.size)
        }
        binding.activityStatus.visibility = View.GONE
        binding.activityAction.setText(
            context.getString(
                when (state.action) {
                    ActivityCardState.Action.START -> R.string.start
                    ActivityCardState.Action.JOIN -> R.string.embedded_activities_join
                    ActivityCardState.Action.OPEN -> R.string.open
                    ActivityCardState.Action.UNAVAILABLE -> R.string.game_action_button_unavailable
                },
            ),
        )
        binding.activityAction.isEnabled = state.action != ActivityCardState.Action.UNAVAILABLE
        binding.activityAction.setOnClickListener {
            val app = application ?: return@setOnClickListener
            val instanceId = when (state.action) {
                ActivityCardState.Action.START -> null
                else -> model.activeInstance?.compositeInstanceId ?: entry.instanceId
            }
            runCatching {
                EmbeddedActivityLauncher.launch(context, app.id, entry.channelId, instanceId)
            }.onFailure {
                binding.activityAction.isEnabled = false
                binding.activityName.setText(R.string.embedded_activities_launch_fail_generic)
            }
        }
        binding.root.contentDescription = listOfNotNull(
            application?.name,
            binding.activityStatus.text,
            binding.activityAction.takeIf { it.isEnabled }?.let { context.getString(
                when (state.action) {
                    ActivityCardState.Action.START -> R.string.start
                    ActivityCardState.Action.JOIN -> R.string.embedded_activities_join
                    ActivityCardState.Action.OPEN -> R.string.open
                    ActivityCardState.Action.UNAVAILABLE -> R.string.game_action_button_unavailable
                },
            ) },
        ).joinToString(", ")
    }

    private fun reasonText(reason: ActivityCardState.UnavailableReason): String = itemView.context.getString(
        when (reason) {
            ActivityCardState.UnavailableReason.ENDED -> R.string.embedded_activities_invite_activity_ended
            ActivityCardState.UnavailableReason.PLATFORM -> R.string.activity_message_platform_unsupported
            ActivityCardState.UnavailableReason.AGE -> R.string.activity_message_age_gated
            ActivityCardState.UnavailableReason.NITRO -> R.string.activity_message_nitro_required
            ActivityCardState.UnavailableReason.PERMISSION -> R.string.embedded_activities_invalid_permissions
            ActivityCardState.UnavailableReason.NO_APPLICATION -> R.string.activity_message_unavailable
        },
    )

    private data class Model(
        val me: MeUser,
        val activeInstance: EmbeddedActivityInstance?,
        val permissions: Long?,
    )
}
