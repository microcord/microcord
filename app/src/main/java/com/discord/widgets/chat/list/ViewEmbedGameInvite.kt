package com.discord.widgets.chat.list

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.activity.Activity
import com.discord.api.activity.ActivityAssets
import com.discord.api.activity.ActivityPlatform
import com.discord.api.application.Application
import com.discord.api.message.activity.MessageActivity
import com.discord.api.message.activity.MessageActivityType
import com.discord.models.user.MeUser
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.time.Clock
import com.discord.widgets.channels.list.WidgetCollapsedUsersListAdapter
import com.discord.widgets.channels.list.items.CollapsedUser
import com.discord.views.VitoImageView
import com.google.android.material.button.MaterialButton
import kotlin.math.min

class ViewEmbedGameInvite @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr, defStyleRes) {
    private val actionButton: MaterialButton
    private val applicationName: TextView
    private val avatar: VitoImageView
    private val avatarStatus: VitoImageView
    private val cover: VitoImageView
    private val header: TextView
    private val subtext: TextView
    private val userAdapter: WidgetCollapsedUsersListAdapter
    var onActionButtonClick: ((View, Model) -> Unit)? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_chat_embed_game_invite, this, true)
        orientation = VERTICAL
        actionButton = requireNotNull(findViewById(R.id.item_game_invite_action_btn))
        applicationName = requireNotNull(findViewById(R.id.item_game_invite_application_name_tv))
        avatar = requireNotNull(findViewById(R.id.item_game_invite_avatar_iv))
        avatarStatus = requireNotNull(findViewById(R.id.item_game_invite_avatar_status_iv))
        cover = requireNotNull(findViewById(R.id.item_game_invite_cover_iv))
        header = requireNotNull(findViewById(R.id.item_game_invite_header))
        subtext = requireNotNull(findViewById(R.id.item_game_invite_subtext))
        val recycler = requireNotNull<RecyclerView>(findViewById(R.id.item_game_invite_recycler))
        userAdapter = MGRecyclerAdapter.configure(WidgetCollapsedUsersListAdapter(recycler))
    }

    fun bind(model: Model, clock: Clock) {
        val dead = model.isDeadInvite(clock.currentTimeMillis())
        val openSlots = model.activity?.party?.size?.let { (it.lastOrNull() ?: 0) - (it.firstOrNull() ?: 0) } ?: 0
        val full = openSlots <= 0
        applicationName.text = model.application.name
        header.setText(
            if (dead || model.messageActivity.type == MessageActivityType.SPECTATE) {
                R.string.invite_embed_game_has_ended
            } else {
                R.string.invite_embed_invite_to_join_group
            },
        )
        subtext.text = if (full) model.activity?.details else model.activity?.state
        subtext.visibility = if (subtext.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        configureImages(model, dead)
        configureAction(model, dead, full)
        findViewById<RecyclerView>(R.id.item_game_invite_recycler).visibility = if (dead) View.GONE else View.VISIBLE
        if (!dead) userAdapter.setData(model.users)
    }

    private fun configureImages(model: Model, dead: Boolean) {
        avatar.setImageURI(model.application.icon?.let { applicationIcon(model.application.id, it) })
        val assets = model.activity?.assets
        avatarStatus.visibility = if (assets?.smallImage == null) View.GONE else View.VISIBLE
        avatarStatus.setImageURI(assets?.smallImage?.let { assetUrl(model.application.id, it, 64) })
        avatarStatus.contentDescription = assets?.smallText
        if (dead) {
            cover.visibility = View.GONE
            cover.setImageURI(null as String?)
            return
        }
        cover.visibility = View.VISIBLE
        val largeImage = assets?.largeImage
        if (largeImage != null) {
            cover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            cover.contentDescription = assets.largeText
            cover.setImageURI(assetUrl(model.application.id, largeImage, width))
        } else {
            cover.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            cover.contentDescription = null
            cover.setImageURI(model.application.coverImage?.let { applicationIcon(model.application.id, it) })
        }
    }

    private fun configureAction(model: Model, dead: Boolean, full: Boolean) {
        actionButton.visibility = View.VISIBLE
        if (dead || !model.canJoin) {
            actionButton.isEnabled = false
            actionButton.setText(R.string.join)
            actionButton.setOnClickListener(null)
            return
        }
        actionButton.isEnabled = !model.isInParty && !full && model.creatorId != model.meUser.id
        actionButton.setText(
            when {
                full -> R.string.invite_embed_full_group
                model.isInParty -> R.string.invite_embed_joined
                else -> R.string.join
            },
        )
        actionButton.setOnClickListener { onActionButtonClick?.invoke(it, model) }
    }

    data class Model(
        val meUser: MeUser,
        val creatorId: Long,
        val creationTime: Long,
        val application: Application,
        val messageActivity: MessageActivity,
        val activity: Activity?,
        val users: List<CollapsedUser>,
    ) {
        val isInParty = users.any { it.user.id == meUser.id }
        val canJoin = activity?.let {
            (it.flags ?: 0) and JOIN_FLAG == JOIN_FLAG &&
                (it.platform == ActivityPlatform.ANDROID || it.supportedPlatforms?.contains(ActivityPlatform.ANDROID) == true)
        } == true
        val gPlayPackageNames = application.googlePlayPackageNames
        val isPartyMatch = activity?.party?.id?.let { it == messageActivity.partyId } == true

        fun isExpiredInvite(now: Long) = now > creationTime + EMBED_LIFETIME_MILLIS
        fun isDeadInvite(now: Long) = !isPartyMatch || isExpiredInvite(now)

        companion object {
            private const val JOIN_FLAG = 2
        }
    }

    private fun applicationIcon(applicationId: Long, hash: String) =
        "https://cdn.discordapp.com/app-icons/$applicationId/$hash.webp?size=256"

    private fun assetUrl(applicationId: Long, asset: String, requestedSize: Int): String {
        val size = min(4096, maxOf(16, Integer.highestOneBit(maxOf(16, requestedSize - 1)) shl 1))
        if (':' in asset) {
            val (platform, id) = asset.split(':', limit = 2)
            return when (platform.lowercase()) {
                "spotify" -> "https://i.scdn.co/image/$id"
                "youtube" -> "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                else -> id
            }
        }
        return "https://cdn.discordapp.com/app-assets/$applicationId/$asset.jpg?size=$size"
    }

    companion object {
        const val EMBED_LIFETIME_MILLIS = 7_200_000L
    }
}
