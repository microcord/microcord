package com.discord.widgets.channels

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Intent
import android.text.util.Linkify
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.isVisible
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.utilities.channel.GuildChannelIconType
import com.discord.utilities.channel.mapGuildChannelTypeToIcon
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.channels.WidgetChannelTopicViewModel.Event
import com.discord.widgets.channels.WidgetChannelTopicViewModel.ViewState
import com.discord.widgets.channels.settings.WidgetChannelGroupDMSettings
import com.discord.widgets.chat.pins.WidgetChannelPinnedMessages
import com.discord.widgets.notice.WidgetNoticeDialog
import com.discord.widgets.user.WidgetUserMutualGuilds
import com.facebook.drawee.span.DraweeSpanStringBuilder
import com.discord.views.VitoImageView
import com.discord.i18n.i18nRender

/**
 * The header strip at the top of the right-hand channel panel: channel icon, channel name, and —
 * for a guild channel — its topic rendered with the full markdown pipeline.
 *
 * Three behaviours make this more than a label:
 *
 * - **Expand/collapse.** The topic is clamped to [RenderedTopic.MIN_LINES]. Tapping either the topic
 *   or the ellipsis chevron animates `maxLines` to [RenderedTopic.MAX_LINES] and back. The chevron
 *   only appears when the collapsed text actually overflows, which can only be measured after a
 *   layout pass — hence [configureEllipsis] runs again at the end of the animation.
 * - **Autolink is conditional.** `Linkify` rewrites the whole `CharSequence`, which destroys the
 *   spoiler and code spans the AST renderer just installed. So the mask is `ALL` only for short
 *   topics with nothing conflicting in them, and `0` otherwise; links inside those topics remain
 *   tappable through the AST's own url nodes.
 * - **Private channels get an overflow menu** (pins, copy id, customise group, close/leave) that
 *   guild channels do not.
 *
 * @see WidgetChannelTopicViewModel for the state that drives all of it.
 */
class WidgetChannelTopic : AppFragment(R.layout.widget_channel_topic) {

    private val viewModel: WidgetChannelTopicViewModel by appViewModels { WidgetChannelTopicViewModel() }

    private lateinit var channelAka: UserAkaView
    private lateinit var channelIcon: VitoImageView
    private lateinit var ellipsis: ImageView
    private lateinit var moreIcon: ImageView
    private lateinit var channelName: TextView
    private lateinit var channelTopicTitle: LinkifiedTextView

    /**
     * Whether the last rendered state was a one-to-one DM. A DM's "topic" slot holds the recipient
     * name, which is already shown as the channel name, so it is never displayed and never expands.
     */
    private var isDm: Boolean = false

    /**
     * What the strip actually paints, independent of which [ViewState] produced it.
     *
     * @param autoLinkMask the [Linkify] mask for [topic]; `0` disables autolinking entirely.
     */
    data class RenderedTopic(
        val channelName: CharSequence? = "",
        val topic: CharSequence? = null,
        val autoLinkMask: Int = 0,
    ) {
        companion object {
            const val MAX_LINES = 40
            const val MIN_LINES = 2
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        channelAka = view.findViewById(R.id.channel_aka)
        channelIcon = view.findViewById(R.id.channel_topic_channel_icon)
        ellipsis = view.findViewById(R.id.channel_topic_ellipsis)
        moreIcon = view.findViewById(R.id.channel_topic_more_icon)
        channelName = view.findViewById(R.id.channel_topic_name)
        channelTopicTitle = view.findViewById(R.id.channel_topic_title)

        channelTopicTitle.setOnClickListener { onToggleTopicExpansionState() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.listenForEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelTopic::class.java) { handleEvent(it) }
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetChannelTopic::class.java) { configureUI(it) }
        channelTopicTitle.maxLines = RenderedTopic.MIN_LINES
    }

    private fun handleEvent(event: Event) = when (event) {
        Event.FocusFirstElement ->
            channelName.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
    }

    private fun configureUI(viewState: ViewState) {
        val renderedTopic = when (viewState) {
            is ViewState.NoChannel -> {
                view?.isVisible = false
                moreIcon.visibility = View.GONE
                setChannelIcon(mapGuildChannelTypeToIcon(GuildChannelIconType.Text))
                RenderedTopic()
            }

            is ViewState.Guild -> {
                view?.isVisible = true
                moreIcon.visibility = View.GONE
                setChannelIcon(mapGuildChannelTypeToIcon(viewState.channelIconType))
                when (viewState) {
                    is ViewState.Guild.DefaultTopic -> getRenderedTopicForDefaultTopic(viewState)
                    is ViewState.Guild.Topic -> getRenderedTopicForTopic(viewState)
                }
            }

            is ViewState.DM -> {
                view?.isVisible = true
                moreIcon.visibility = View.VISIBLE
                setChannelIcon(R.drawable.ic_direct_message_header)
                channelAka.configure(viewState.guildMembers)
                RenderedTopic(channelName = viewState.recipientName)
            }

            is ViewState.GDM -> {
                view?.isVisible = true
                moreIcon.visibility = View.VISIBLE
                setChannelIconForGDM(viewState.channel)
                RenderedTopic(channelName = viewState.channel.getDisplayNameOrDefault(requireContext(), false))
            }
        }

        configureChannelTopicTitle(renderedTopic)

        val isDmState = viewState is ViewState.DM
        if (viewState is ViewState.DM) {
            channelAka.setOnClickListener {
                viewState.recipientUserId?.let(::showMutualGuilds)
            }
            // The row is worth showing when there is either a nickname to print or an avatar to
            // pile up; a recipient with neither would render an empty "aka" strip.
            channelAka.isVisible = viewState.recipientNicknames.isNotEmpty() ||
                viewState.guildMembers.any { it.hasAvatar() }
        } else {
            channelAka.visibility = View.GONE
        }
        isDm = isDmState

        channelTopicTitle.isVisible = !renderedTopic.topic.isNullOrBlank() && !isDmState
        configureEllipsis()
        channelName.text = renderedTopic.channelName
        moreIcon.setOnClickListener { onClickMore(viewState) }
    }

    private fun configureChannelTopicTitle(renderedTopic: RenderedTopic) {
        channelTopicTitle.isVisible = !renderedTopic.topic.isNullOrEmpty()
        channelTopicTitle.autoLinkMask = renderedTopic.autoLinkMask
        val topic = renderedTopic.topic
        if (topic is DraweeSpanStringBuilder) {
            channelTopicTitle.setDraweeSpanStringBuilder(topic)
        } else {
            channelTopicTitle.text = topic
        }
    }

    /**
     * Shows the expand chevron only when the collapsed topic is actually clipped.
     *
     * `lineCount` is meaningless before the view has been measured, so this is called both after
     * binding and at the end of the expand/collapse animation.
     */
    private fun configureEllipsis() {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.INITIALIZED)) return
        ellipsis.setOnClickListener { onToggleTopicExpansionState() }
        ellipsis.isVisible = !isDm &&
            channelTopicTitle.lineCount > RenderedTopic.MIN_LINES &&
            channelTopicTitle.maxLines != RenderedTopic.MAX_LINES
    }

    private fun getRenderedTopicForDefaultTopic(viewState: ViewState.Guild.DefaultTopic) = RenderedTopic(
        channelName = viewState.channel.getDisplayNameOrDefault(requireContext(), false),
        topic = "",
        autoLinkMask = 0,
    )

    private fun getRenderedTopicForTopic(viewState: ViewState.Guild.Topic): RenderedTopic {
        val rendered = AstRenderer.render(
            viewState.ast,
            MessageRenderContext(
                context = channelTopicTitle.context,
                myId = 0L,
                isAnimationEnabled = viewState.allowAnimatedEmojis,
                userNames = viewState.userNames,
                channelNames = viewState.channelNames,
                roles = viewState.roles,
                spoilerOnClick = viewModel::handleOnIndexClicked,
            ),
        )
        // Linkify would rewrite the whole CharSequence and drop the spans just installed, so it is
        // only allowed over short topics that contain nothing it could conflict with.
        val autoLinkMask =
            if (rendered.length <= MAX_AUTOLINK_LENGTH && !viewState.isLinkifyConflicting) Linkify.ALL else 0
        return RenderedTopic(
            channelName = viewState.channel.getDisplayNameOrDefault(requireContext(), false),
            topic = rendered,
            autoLinkMask = autoLinkMask,
        )
    }

    private fun onToggleTopicExpansionState() {
        val target =
            if (channelTopicTitle.maxLines != RenderedTopic.MAX_LINES) RenderedTopic.MAX_LINES
            else RenderedTopic.MIN_LINES
        ObjectAnimator.ofInt(channelTopicTitle, "maxLines", target).apply {
            duration = EXPAND_ANIMATION_DURATION_MS
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animator: Animator) = Unit
                override fun onAnimationEnd(animator: Animator) = configureEllipsis()
                override fun onAnimationCancel(animator: Animator) = Unit
                override fun onAnimationRepeat(animator: Animator) = Unit
            })
        }.start()
    }

    private fun setChannelIcon(channelIconResource: Int) {
        channelIcon.setImageResource(channelIconResource)
        setChannelIconEndMargin(dpToPixels(4))
    }

    private fun setChannelIconForGDM(channel: Channel) {
        IconUtils.setIcon(channelIcon, IconUtils.getForChannel(channel), R.dimen.avatar_size_large)
        setChannelIconEndMargin(dpToPixels(8))
    }

    /** A drawable icon sits tighter against the name than a group-DM avatar does. */
    private fun setChannelIconEndMargin(endMargin: Int) {
        val layoutParams = channelIcon.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        layoutParams.setMargins(
            layoutParams.leftMargin,
            layoutParams.topMargin,
            endMargin,
            layoutParams.bottomMargin,
        )
        channelIcon.layoutParams = layoutParams
    }

    private fun onClickMore(viewState: ViewState) = when (viewState) {
        is ViewState.GDM -> showContextMenu(
            isGroup = true,
            channelId = viewState.channelId,
            channelTitle = viewState.channel.getDisplayNameOrDefault(requireContext(), false),
            developerModeEnabled = viewState.developerModeEnabled,
        )

        is ViewState.DM -> showContextMenu(
            isGroup = false,
            channelId = viewState.channelId,
            developerModeEnabled = viewState.developerModeEnabled,
        )

        else -> Unit
    }

    private fun showContextMenu(
        isGroup: Boolean,
        channelId: Long,
        channelTitle: CharSequence? = null,
        developerModeEnabled: Boolean = false,
    ) {
        val menu = PopupMenu(moreIcon.context, moreIcon, android.view.Gravity.BOTTOM or android.view.Gravity.START)
        menu.inflate(R.menu.menu_private_channel_sidebar)

        menu.menu.findItem(R.id.menu_private_channel_sidebar_pinned_messages)
            .setOnMenuItemClickListener {
                WidgetChannelPinnedMessages.show(requireContext(), channelId)
                true
            }

        menu.menu.findItem(R.id.menu_private_channel_sidebar_copy_id).apply {
            isVisible = developerModeEnabled
            setOnMenuItemClickListener {
                AppToast.copyToClipboard(requireContext(), channelId.toString())
                true
            }
        }

        menu.menu.findItem(R.id.menu_private_channel_sidebar_customize_gorup).apply {
            isVisible = isGroup
            setOnMenuItemClickListener {
                WidgetChannelGroupDMSettings.create(channelId, requireContext())
                true
            }
        }

        menu.menu.findItem(R.id.menu_private_channel_sidebar_close).apply {
            if (isGroup) {
                setTitle(R.string.leave_group_dm)
                setOnMenuItemClickListener {
                    // Leaving a group cannot be undone without a re-invite, so it is confirmed;
                    // closing a one-to-one DM only hides it and is not.
                    GroupDmLeave.confirm(requireContext(), parentFragmentManager, channelTitle ?: "") { silent ->
                        viewModel.handleClosePrivateChannel(requireContext(), silent)
                    }
                    true
                }
            } else {
                setTitle(R.string.close_dm)
                setOnMenuItemClickListener {
                    viewModel.handleClosePrivateChannel(requireContext())
                    true
                }
            }
        }

        menu.show()
    }

    /**
     * Opens the mutual-guilds screen for [userId].
     *
     * The argument bundle is taken from [WidgetUserMutualGuilds.newInstance] rather than rebuilt
     * here so the extra key stays private to that screen; [com.discord.app.AppActivity] copies the
     * intent extras onto the fragment.
     */
    private fun showMutualGuilds(userId: Long) {
        val arguments = WidgetUserMutualGuilds.newInstance(userId).arguments ?: return
        AppScreenRouter.launch(
            requireContext(),
            WidgetUserMutualGuilds::class.java,
            Intent().putExtras(arguments),
        )
    }

    private companion object {
        /** Above this many characters a topic is assumed to be prose, and autolinking is skipped. */
        const val MAX_AUTOLINK_LENGTH = 200
        const val EXPAND_ANIMATION_DURATION_MS = 150L
    }
}
