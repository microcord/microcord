package com.discord.widgets.guilds.list

import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.getDisplayName
import com.discord.api.guild.GuildFeature
import com.discord.api.guildjoinrequest.ApplicationStatus
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.guild.Guild
import com.discord.tooltips.SparkleView
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import com.discord.views.ServerFolderView
import com.facebook.drawee.view.SimpleDraweeView

/**
 * The eight holder shapes the guild sidebar can inflate.
 *
 * Guild and folder holders additionally implement [GuildsDragAndDropCallback.DraggableViewHolder]
 * so the drag callback can refuse to pick up rows that must not move (lurked guilds, pending
 * guilds, open folders), and [onDragStarted]/[onDragEnded] give the dragged row its own visual
 * state without the adapter having to rebind it.
 */
sealed class GuildListViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    protected fun configureMentionsCount(
        textView: TextView,
        count: Int,
        isLowImportance: Boolean = false,
    ) {
        if (count < 1) {
            textView.visibility = View.GONE
        } else {
            textView.visibility = View.VISIBLE
            textView.text = count.toString()
            textView.backgroundTintList = if (isLowImportance) {
                ColorStateList.valueOf(
                    ColorCompat.getThemedColor(textView.context, R.attr.colorMentionBadgeMuted),
                )
            } else {
                null
            }
        }
    }

    open fun onDragStarted() = Unit

    /** [wasMerge] is true when the drop created or joined a folder, so this row disappears. */
    open fun onDragEnded(wasMerge: Boolean) = Unit

    /** Divider, unavailable, create and help rows: no state beyond the click listener. */
    class SimpleViewHolder(view: View) : GuildListViewHolder(view)

    class SpaceViewHolder(private val view: View) : GuildListViewHolder(view) {
        fun configure(height: Int) {
            view.layoutParams = view.layoutParams.apply { this.height = height }
        }
    }

    class DiscordHubViewHolder(view: View) : GuildListViewHolder(view) {
        private val sparkle: SparkleView = view.findViewById(R.id.guild_item_sparkle)

        fun configure(item: GuildListItem.HubItem) {
            if (!item.showSparkle) sparkle.stop()
            sparkle.visibility = if (item.showSparkle) View.VISIBLE else View.GONE
        }
    }

    class FriendsViewHolder(
        view: View,
        private val onClicked: (GuildListItem.FriendsItem) -> Unit,
    ) : GuildListViewHolder(view) {
        private val avatar: ImageView = view.findViewById(R.id.guilds_item_profile_avatar)
        private val avatarWrap: FrameLayout = view.findViewById(R.id.guilds_item_profile_avatar_wrap)
        private val selected: ImageView = view.findViewById(R.id.guilds_item_profile_selected)

        fun configure(data: GuildListItem.FriendsItem) {
            selected.visibility = if (data.isSelected) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClicked(data) }
            if (data.isSelected) {
                avatarWrap.setBackgroundResource(R.drawable.drawable_squircle_brand_500)
                avatarWrap.backgroundTintList = null
                avatar.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(itemView.context, R.color.white))
            } else {
                avatarWrap.setBackgroundResource(R.drawable.drawable_circle_black)
                avatarWrap.backgroundTintList = ColorStateList.valueOf(
                    ColorCompat.getThemedColor(avatarWrap.context, R.attr.colorBackgroundSecondary),
                )
                avatar.imageTintList = ColorStateList.valueOf(
                    ColorCompat.getThemedColor(itemView.context, R.attr.primary_300),
                )
            }
        }
    }

    class PrivateChannelViewHolder(
        view: View,
        private val onClicked: (GuildListItem.PrivateChannelItem) -> Unit,
        private val onLongPressed: (GuildListItem.PrivateChannelItem) -> Unit,
    ) : GuildListViewHolder(view) {
        private val avatar: SimpleDraweeView = view.findViewById(R.id.guilds_item_dm_avatar)
        private val mentions: TextView = view.findViewById(R.id.guilds_item_dm_count)

        fun configure(data: GuildListItem.PrivateChannelItem) {
            val count = data.mentionCount
            configureMentionsCount(mentions, count)
            val mentionText = if (count > 0) {
                itemView.i18nPlural(R.plurals.dm_tooltip_a11y_label_mentions, count, "#" to count)
            } else {
                ""
            }
            itemView.contentDescription = itemView.i18nFormat(
                R.string.dm_tooltip_a11y_label,
                "name" to data.channel.getDisplayName(),
                "mentions" to mentionText,
            )
            IconUtils.setIcon(avatar, IconUtils.getForChannel(data.channel), R.dimen.avatar_size_large)
            itemView.setOnClickListener { onClicked(data) }
            itemView.setOnLongClickListenerConsumeClick { onLongPressed(data) }
        }
    }

    class FolderViewHolder(
        view: View,
        private val onClicked: (GuildListItem.FolderItem) -> Unit,
        private val onLongPressed: (GuildListItem.FolderItem) -> Unit,
    ) : GuildListViewHolder(view), GuildsDragAndDropCallback.DraggableViewHolder {

        private val folderView: ServerFolderView = view.findViewById(R.id.guilds_item_folder)
        private val folderContainer: FrameLayout = view.findViewById(R.id.guilds_item_folder_container)
        private val highlight: View = view.findViewById(R.id.guilds_item_highlight)
        private val mentions: TextView = view.findViewById(R.id.guilds_item_mentions)
        private val selected: View = view.findViewById(R.id.guilds_item_selected)
        private val unread: ImageView = view.findViewById(R.id.guilds_item_unread)
        private val voice: ImageView = view.findViewById(R.id.guilds_item_voice)

        private var data: GuildListItem.FolderItem? = null
        private var isDragging = false

        /** Opaque folder tint, or null when the folder uses the default brand colour. */
        @ColorInt
        var color: Int? = null
            private set

        /** How many guild rows follow this folder; [FolderItemDecoration] sizes the capsule by it. */
        var numChildren: Int = 0
            private set

        init {
            folderContainer.clipToOutline = true
        }

        override fun canDrag(): Boolean {
            val item = data ?: return false
            return !item.isOpen && !item.isPendingGuildsFolder
        }

        /** False while this folder is itself being dragged, so the capsule does not trail it. */
        fun shouldDrawDecoration(): Boolean = !isDragging

        fun configure(data: GuildListItem.FolderItem) {
            this.data = data
            color = data.color?.let { it or ALPHA_OPAQUE }
            numChildren = if (data.isOpen) data.guilds.size else 0

            itemView.setOnClickListener { onClicked(data) }
            configureFolderView(data)

            selected.visibility = if (!data.isOpen && data.isAnyGuildSelected) View.VISIBLE else View.GONE
            unread.visibility = if (!data.isOpen && data.isUnread) View.VISIBLE else View.GONE
            voice.isActivated = data.isAnyGuildConnectedToVoice
            voice.visibility =
                if (!data.isOpen && data.isAnyGuildConnectedToVoice) View.VISIBLE else View.GONE
            voice.setImageResource(
                if (data.isAnyGuildConnectedToStageChannel) R.drawable.ic_channel_stage_24dp_white
                else R.drawable.ic_volume_up_white_24dp,
            )
            highlight.visibility = if (data.isTargetedForFolderAddition) View.VISIBLE else View.GONE

            val mentionText = if (data.mentionCount > 0) {
                itemView.i18nPlural(
                    R.plurals.guild_folder_tooltip_a11y_label_mentions,
                    data.mentionCount,
                    "#" to data.mentionCount,
                )
            } else {
                ""
            }
            val expandedState =
                itemView.context.getString(if (data.isOpen) R.string.collapsed else R.string.expanded)
            itemView.contentDescription = itemView.i18nFormat(
                R.string.guild_folder_tooltip_a11y_label_with_expanded_state,
                "folderName" to data.name.orEmpty(),
                "mentions" to mentionText,
                "expandedState" to expandedState,
            )
            itemView.setOnLongClickListenerConsumeClick { onLongPressed(data) }
            // An expanded folder shows its children's badges instead of an aggregate one.
            configureMentionsCount(
                mentions,
                if (data.isOpen) 0 else data.mentionCount,
                data.isMentionLowImportance,
            )
        }

        /**
         * Mirrors `ServerFolderView.configure` from the reference. That method has not been
         * reconstructed on the view yet, so the state is driven through its public members here.
         */
        private fun configureFolderView(data: GuildListItem.FolderItem) {
            if (data.isPendingGuildsFolder) {
                folderView.folderId = data.folderId
                folderView.isOpen = data.isOpen
                folderView.folderImage.setImageDrawable(
                    ContextCompat.getDrawable(folderView.context, R.drawable.ic_guild_list_pending_folder),
                )
                folderView.showCurrentState()
                return
            }

            val previousId = folderView.folderId
            val shouldAnimate = previousId != null && previousId == data.folderId &&
                folderView.isOpen != data.isOpen
            folderView.folderId = data.folderId
            folderView.isOpen = data.isOpen

            if (data.isOpen) {
                if (shouldAnimate) folderView.animateState(R.anim.anim_folder_slide_in_down)
                else folderView.showCurrentState()
                val tint = data.color ?: ColorCompat.getThemedColor(folderView.context, R.attr.color_brand_500)
                folderView.folderImage.imageTintList =
                    ColorStateList.valueOf(tint or ALPHA_OPAQUE)
            } else {
                if (shouldAnimate) folderView.animateState(R.anim.anim_folder_slide_out_up)
                else folderView.showCurrentState()
                folderView.guildViews.forEachIndexed { index, guildView ->
                    val guild = data.guilds.getOrNull(index)
                    if (guild == null) {
                        guildView.visibility = View.GONE
                    } else {
                        guildView.visibility = View.VISIBLE
                        guildView.configure(
                            if (guild.hasIcon()) {
                                IconUtils.getForGuild(guild, DEFAULT_GUILD_ICON_ASSET)
                            } else {
                                null
                            },
                            guild.shortName,
                        )
                    }
                }
            }
        }

        override fun onDragStarted() {
            isDragging = true
            folderView.background =
                ContextCompat.getDrawable(folderView.context, R.drawable.drawable_squircle_primary_600)
        }

        override fun onDragEnded(wasMerge: Boolean) {
            isDragging = false
            folderView.background = null
        }
    }

    class GuildViewHolder(
        itemView: View,
        @ColorInt private val overlayColor: Int,
        @ColorInt private val overlayColorInFolder: Int,
        private val onClicked: (GuildListItem.GuildItem) -> Unit,
        private val onLongPressed: (GuildListItem.GuildItem) -> Unit,
    ) : GuildListViewHolder(itemView), GuildsDragAndDropCallback.DraggableViewHolder {

        private val selectedIndicator: ImageView = itemView.findViewById(R.id.guilds_item_selected)
        private val unreadIndicator: ImageView = itemView.findViewById(R.id.guilds_item_unread)
        private val applicationStatus: ImageView = itemView.findViewById(R.id.guilds_item_application_status)
        private val applicationStream: ImageView = itemView.findViewById(R.id.guilds_item_application_stream)
        private val avatar: SimpleDraweeView = itemView.findViewById(R.id.guilds_item_avatar)
        private val avatarText: TextView = itemView.findViewById(R.id.guilds_item_avatar_text)
        private val avatarWrap: FrameLayout = itemView.findViewById(R.id.guilds_item_avatar_wrap)
        private val eventStatus: ImageView = itemView.findViewById(R.id.guilds_item_event_status)
        private val mentions: TextView = itemView.findViewById(R.id.guilds_item_mentions)
        private val voice: ImageView = itemView.findViewById(R.id.guilds_item_voice)

        private val defaultAvatarSize =
            itemView.resources.getDimensionPixelSize(R.dimen.avatar_size_large)
        private val targetedAvatarSize =
            itemView.resources.getDimensionPixelSize(R.dimen.folder_guild_size)
        private val targetedAvatarMargin =
            itemView.resources.getDimensionPixelSize(R.dimen.folder_guild_outer_margin)
        private val imageRequestSize: Int

        private var data: GuildListItem.GuildItem? = null
        private var isDragging = false

        init {
            avatarWrap.clipToOutline = true
            imageRequestSize = IconUtils.getMediaProxySize(avatar.layoutParams.height)
        }

        val folderId: Long? get() = data?.folderId

        val isLastGuildInFolder: Boolean? get() = data?.isLastGuildInFolder

        fun isTargetedForFolderCreation(): Boolean = data?.isTargetedForFolderCreation == true

        override fun canDrag(): Boolean {
            val item = data ?: return false
            return !item.isLurkingGuild && !item.isPendingGuild
        }

        fun configure(data: GuildListItem.GuildItem) {
            val previous = this.data
            val selectionChanged = previous == null || previous.isSelected != data.isSelected
            val iconChanged = previous?.guild?.icon == null || previous.guild.icon != data.guild.icon
            // Re-laying out the avatar is only needed when the folder-creation preview toggles,
            // and only after a first bind established a baseline.
            val targetingChanged =
                previous != null && previous.isTargetedForFolderCreation != data.isTargetedForFolderCreation
            this.data = data

            itemView.visibility = View.VISIBLE
            itemView.isSelected = data.isSelected
            itemView.setOnClickListener { onClicked(data) }
            itemView.setOnLongClickListenerConsumeClick { onLongPressed(data) }

            val isInFolder = data.folderId != null || data.isTargetedForFolderCreation
            configureGuildIconBackground(data.isSelected, isInFolder, data.guild.hasIcon())
            if (targetingChanged) configureGuildIconPositioning(data.isTargetedForFolderCreation)
            if (iconChanged || selectionChanged) configureGuildIconImage(data.guild, data.isSelected)
            configureDraggingAlpha()

            avatarText.text = if (data.guild.hasIcon()) null else data.guild.shortName
            if (selectionChanged) {
                avatarText.setTextColor(
                    if (data.isSelected) ContextCompat.getColor(avatarText.context, R.color.white)
                    else ColorCompat.getThemedColor(avatarText.context, R.attr.colorHeaderPrimary),
                )
            }

            configureMentionsCount(mentions, data.mentionCount, data.isMentionLowImportance)
            val mentionText = if (data.mentionCount > 0) {
                itemView.i18nPlural(
                    R.plurals.guild_tooltip_a11y_label_mentions,
                    data.mentionCount,
                    "#" to data.mentionCount,
                )
            } else {
                ""
            }
            itemView.contentDescription = itemView.i18nFormat(
                R.string.guild_tooltip_a11y_label,
                "mentions" to mentionText,
                "guildName" to data.guild.name,
            )

            // Voice wins over an event; a stage without a connection still shows the stage glyph.
            voice.visibility = if (
                data.isConnectedToVoice || (data.hasActiveStageChannel && !data.hasActiveScheduledEvent)
            ) View.VISIBLE else View.GONE
            voice.isActivated = data.isConnectedToVoice
            voice.setImageResource(
                if (data.isConnectedToVoice && !data.isConnectedToStageChannel) {
                    R.drawable.ic_volume_up_white_24dp
                } else {
                    R.drawable.ic_channel_stage_24dp_white
                },
            )
            eventStatus.visibility =
                if (!data.isConnectedToVoice && data.hasActiveScheduledEvent) View.VISIBLE else View.GONE
            applicationStream.visibility =
                if (!data.isConnectedToVoice && data.hasOngoingApplicationStream) View.VISIBLE else View.GONE
            unreadIndicator.visibility = if (data.isUnread) View.VISIBLE else View.GONE
            selectedIndicator.visibility = if (data.isSelected) View.VISIBLE else View.GONE

            // The badge and the join-request pip share a corner, so mentions suppress the pip.
            configureApplicationStatus(data.applicationStatus, isInFolder, data.mentionCount > 0)
        }

        private fun configureApplicationStatus(
            status: ApplicationStatus?,
            isInFolder: Boolean,
            hasMentions: Boolean,
        ) {
            if (status == null || hasMentions) {
                applicationStatus.visibility = View.GONE
                return
            }
            applicationStatus.backgroundTintList = ColorStateList.valueOf(
                ColorCompat.getThemedColor(
                    applicationStatus.context,
                    if (isInFolder) R.attr.colorBackgroundSecondary else R.attr.colorBackgroundTertiary,
                ),
            )
            applicationStatus.visibility = View.VISIBLE
            when (status) {
                ApplicationStatus.STARTED ->
                    applicationStatus.setImageResource(R.drawable.ic_application_status_started)
                ApplicationStatus.PENDING ->
                    applicationStatus.setImageResource(R.drawable.ic_application_status_approved)
                ApplicationStatus.REJECTED ->
                    applicationStatus.setImageResource(R.drawable.ic_application_status_pending)
                ApplicationStatus.APPROVED ->
                    applicationStatus.setImageResource(R.drawable.ic_application_status_rejected)
                ApplicationStatus.UNKNOWN -> applicationStatus.visibility = View.GONE
            }
        }

        private fun configureDraggingAlpha() {
            avatarWrap.alpha = if (isDragging) DRAGGING_ALPHA else 1f
        }

        private fun configureGuildIconBackground(
            isSelected: Boolean,
            isInFolder: Boolean,
            guildHasIcon: Boolean,
        ) {
            // A selected guild squares off; an unselected one stays circular and the corners are
            // punched out with the sidebar background so neighbouring rows read as separate.
            MGImages.setRoundingParams(
                avatar,
                avatar.resources.getDimensionPixelSize(R.dimen.guild_icon_radius).toFloat(),
                !isSelected,
                if (isInFolder) overlayColorInFolder else overlayColor,
            )
            when {
                guildHasIcon -> {
                    avatarWrap.setBackgroundResource(
                        if (isSelected) R.drawable.drawable_squircle_transparent
                        else R.drawable.drawable_circle_transparent,
                    )
                    avatarWrap.backgroundTintList = null
                }
                isSelected -> {
                    avatarWrap.setBackgroundResource(R.drawable.drawable_squircle_brand_500)
                    avatarWrap.backgroundTintList = null
                }
                else -> {
                    avatarWrap.setBackgroundResource(R.drawable.drawable_circle_black)
                    avatarWrap.backgroundTintList = ColorStateList.valueOf(
                        ColorCompat.getThemedColor(
                            avatarWrap.context,
                            if (isInFolder) R.attr.colorBackgroundSecondary
                            else R.attr.colorBackgroundPrimary,
                        ),
                    )
                }
            }
        }

        private fun configureGuildIconImage(guild: Guild, isSelected: Boolean) {
            val animated = guild.hasFeature(GuildFeature.ANIMATED_ICON) &&
                guild.icon?.startsWith("a") == true
            val hierarchy = avatar.hierarchy
            if (animated) {
                // Crossfading an APNG restarts it, so animated icons swap in instantly and with no
                // placeholder underneath.
                hierarchy.setPlaceholderImage(null)
                hierarchy.fadeDuration = 0
            } else {
                hierarchy.setPlaceholderImage(R.drawable.asset_default_avatar_64dp)
                hierarchy.fadeDuration = STATIC_ICON_FADE_MS
            }

            if (guild.hasIcon()) {
                val url = IconUtils.getForGuild(
                    guild,
                    allowAnimation = isSelected,
                    size = imageRequestSize,
                )
                MGImages.setImage(avatar, url, imageRequestSize, imageRequestSize)
                avatar.visibility = View.VISIBLE
            } else {
                avatar.visibility = View.GONE
            }
        }

        /** Shrinks the avatar into the top-left cell of the folder grid preview and back. */
        private fun configureGuildIconPositioning(isTargetedForFolderCreation: Boolean) {
            val avatarParams = avatar.layoutParams as FrameLayout.LayoutParams
            val textParams = avatarText.layoutParams as FrameLayout.LayoutParams
            if (isTargetedForFolderCreation) {
                avatarParams.setMargins(targetedAvatarMargin, targetedAvatarMargin, 0, 0)
                avatarParams.width = targetedAvatarSize
                avatarParams.height = targetedAvatarSize
                avatarText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, TARGETED_AVATAR_TEXT_SIZE_DP)
                textParams.setMargins(targetedAvatarMargin, targetedAvatarMargin, 0, 0)
                textParams.width = targetedAvatarSize
                textParams.height = targetedAvatarSize
            } else {
                avatarParams.setMargins(0, 0, 0, 0)
                avatarParams.width = defaultAvatarSize
                avatarParams.height = defaultAvatarSize
                avatarText.setTextSize(TypedValue.COMPLEX_UNIT_SP, DEFAULT_AVATAR_TEXT_SIZE_SP)
                textParams.setMargins(0, 0, 0, 0)
                textParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                textParams.height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            avatar.layoutParams = avatarParams
            avatarText.layoutParams = textParams
        }

        override fun onDragStarted() {
            isDragging = true
            configureDraggingAlpha()
        }

        override fun onDragEnded(wasMerge: Boolean) {
            // The row is about to be folded into its target, so it leaves rather than snapping back.
            if (wasMerge) itemView.visibility = View.GONE
            isDragging = false
            configureDraggingAlpha()
        }

        private companion object {
            private const val DEFAULT_AVATAR_TEXT_SIZE_SP = 14f
            private const val TARGETED_AVATAR_TEXT_SIZE_DP = 8f
            private const val DRAGGING_ALPHA = 0.5f
            private const val STATIC_ICON_FADE_MS = 50
        }
    }

    private companion object {
        private const val ALPHA_OPAQUE = 0xFF000000.toInt()
        private const val DEFAULT_GUILD_ICON_ASSET = "asset://asset/images/default_icon.jpg"
    }
}
