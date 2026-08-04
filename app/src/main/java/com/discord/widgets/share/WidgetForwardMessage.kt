package com.discord.widgets.share

import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import androidx.appcompat.widget.Toolbar
import com.discord.api.channel.Channel
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppToast
import com.discord.app.appViewModels
import com.discord.i18n.i18nFormat
import com.discord.i18n.i18nPlural
import com.discord.models.message.Message
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.captcha.CaptchaRequestPolicy
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.user.search.WidgetGlobalSearchAdapter
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import com.discord.widgets.user.search.WidgetGlobalSearchViewModel
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import rx.Observable

/**
 * Reference `ForwardModal`.
 *
 * Forwarding is its own screen rather than a mode of [WidgetIncomingShare]: it picks up to
 * [MAX_DESTINATION_COUNT] destinations at once, keeps destinations it cannot send to on screen with
 * the reason attached, and previews the message it is about to forward.
 */
class WidgetForwardMessage : AppFragment(R.layout.widget_forward_message) {
    private val searchViewModel: WidgetGlobalSearchViewModel by appViewModels { WidgetGlobalSearchViewModel() }
    private lateinit var resultsAdapter: WidgetGlobalSearchAdapter

    private var source: Message? = null
    private var sourceChannel: Channel? = null
    private var selected = linkedMapOf<String, WidgetGlobalSearchModel.ItemDataPayload>()
    private var latestModel: WidgetGlobalSearchModel? = null
    private var isSending = false

    private lateinit var toolbar: Toolbar
    private lateinit var destinations: RecyclerView
    private lateinit var noResults: View
    private lateinit var comment: TextInputLayout
    private lateinit var sendButton: MaterialButton
    private lateinit var warning: TextView
    private lateinit var preview: View

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        toolbar = view.findViewById(R.id.action_bar_toolbar)
        destinations = view.findViewById(R.id.forward_destinations)
        noResults = view.findViewById(R.id.forward_no_results)
        comment = view.findViewById(R.id.forward_comment)
        sendButton = view.findViewById(R.id.forward_send)
        warning = view.findViewById(R.id.forward_warning)
        preview = view.findViewById(R.id.forward_preview)

        val channelId = arguments?.getLong(ARG_CHANNEL_ID) ?: 0L
        val messageId = arguments?.getLong(ARG_MESSAGE_ID) ?: 0L
        source = StoreStream.getMessages().getMessage(channelId, messageId)
        sourceChannel = StoreStream.getChannels().getChannel(channelId)

        toolbar.apply {
            setNavigationOnClickListener { close() }
            setNavigationContentDescription(R.string.close)
            setSubtitleTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextWarning))
            if (source != null) {
                inflateMenu(R.menu.menu_forward_message)
                setOnMenuItemClickListener { item ->
                    if (item.itemId == R.id.menu_forward_copy_link) {
                        copyMessageLink(channelId, messageId)
                        true
                    } else {
                        false
                    }
                }
            }
        }

        destinations.apply {
            resultsAdapter = MGRecyclerAdapter.configure(WidgetGlobalSearchAdapter(this))
            adapter = resultsAdapter
            setHasFixedSize(false)
        }
        resultsAdapter.destinationState = ::destinationState
        resultsAdapter.onSelectedListener = { _, isLongPress, item ->
            if (!isLongPress) toggleDestination(item)
        }

        view.findViewById<TextInputLayout>(R.id.forward_search)
            .editText?.addTextChangedListener(SimpleTextWatcher(searchViewModel::setFilter))
        comment.editText?.addTextChangedListener(SimpleTextWatcher { renderFooter() })
        sendButton.setOnClickListener { send() }

        renderPreview()
        renderFooter()
        if (source == null) {
            Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
            close()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        searchViewModel.observeViewState().ui(this).subscribe(::renderSearch)
    }

    private fun renderSearch(model: WidgetGlobalSearchModel) {
        latestModel = model
        val rows = destinationRows(model.data)
        resultsAdapter.setData(rows)
        noResults.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Guilds are not forwardable destinations, and a channel the user cannot post in at all never
     * makes the list — everything else stays and explains itself through [destinationState].
     */
    private fun destinationRows(
        rows: List<WidgetGlobalSearchModel.ItemDataPayload>,
    ): List<WidgetGlobalSearchModel.ItemDataPayload> {
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()
        val meId = StoreStream.getUsers().getMe().id
        val filtered = rows.filter { item ->
            when (item) {
                is WidgetGlobalSearchModel.ItemHeader -> true
                is WidgetGlobalSearchModel.ItemChannel ->
                    ForwardingPolicy.canSendTo(item.channel, permissions[item.channel.id])
                is WidgetGlobalSearchModel.ItemUser -> !item.user.isSystemUser && item.user.id != meId
                else -> false
            }
        }
        return filtered.filterIndexed { index, item ->
            val next = filtered.drop(index + 1).firstOrNull()
            item !is WidgetGlobalSearchModel.ItemHeader || next != null && next !is WidgetGlobalSearchModel.ItemHeader
        }
    }

    private fun destinationState(
        item: WidgetGlobalSearchModel.ItemDataPayload,
    ): WidgetGlobalSearchAdapter.DestinationState {
        val reason = unavailableReason(item)
        val isSelected = selected.containsKey(item.key)
        return WidgetGlobalSearchAdapter.DestinationState(
            selected = isSelected,
            enabled = reason == null && (isSelected || selected.size < MAX_DESTINATION_COUNT),
            unavailableReason = reason?.let(::getString),
        )
    }

    private fun unavailableReason(item: WidgetGlobalSearchModel.ItemDataPayload): Int? {
        val message = source ?: return null
        val origin = sourceChannel ?: return null
        val destination = item.channel
        return ForwardingPolicy.destinationUnavailable(
            message = message,
            sourceIsAgeRestricted = isAgeRestricted(origin),
            destination = destination,
            destinationIsAgeRestricted = destination != null && isAgeRestricted(destination),
            destinationPermissions = destination?.let {
                StoreStream.getPermissions().getPermissionsByChannel()[it.id]
            },
            guildStickers = StoreStream.getStickers().getStickers(),
        )
    }

    private fun isAgeRestricted(channel: Channel) =
        channel.nsfw || StoreStream.getGuilds().getGuild(channel.guildId)?.nsfw == true

    private fun toggleDestination(item: WidgetGlobalSearchModel.ItemDataPayload) {
        if (item is WidgetGlobalSearchModel.ItemHeader) return
        if (selected.remove(item.key) == null) {
            if (selected.size >= MAX_DESTINATION_COUNT) return
            selected[item.key] = item
        }
        latestModel?.let(::renderSearch)
        renderFooter()
    }

    private fun renderPreview() {
        val message = source ?: return
        val attachments = message.attachments.orEmpty()
        val content = message.content.orEmpty()
        if (content.isEmpty() && attachments.isEmpty() && message.embeds.isNullOrEmpty()) {
            preview.visibility = View.GONE
            requireView().findViewById<View>(R.id.forward_footer_divider).visibility = View.GONE
            return
        }

        val contentView = preview.findViewById<TextView>(R.id.forward_preview_content)
        contentView.text = content
        contentView.maxLines = if (attachments.isEmpty()) 2 else 1
        contentView.visibility = if (content.isEmpty()) View.GONE else View.VISIBLE

        val context = requireContext()
        val row = preview.findViewById<View>(R.id.forward_preview_attachment_row)
        row.visibility = if (attachments.isEmpty()) View.GONE else View.VISIBLE
        if (attachments.isNotEmpty()) {
            val videos = attachments.count { it.type == MessageAttachmentType.VIDEO }
            val images = attachments.count { it.type == MessageAttachmentType.IMAGE }
            val icon = preview.findViewById<ImageView>(R.id.forward_preview_attachment_icon)
            val label = preview.findViewById<TextView>(R.id.forward_preview_attachment_label)
            when {
                images > 0 && videos > 0 -> {
                    icon.setImageResource(R.drawable.ic_image_library_24dp)
                    label.text = context.i18nFormat(
                        R.string.num_images_videos,
                        "image_count" to context.i18nPlural(R.plurals.num_images_videos_image_count, images, "image_count" to images),
                        "video_count" to context.i18nPlural(R.plurals.num_images_videos_video_count, videos, "video_count" to videos),
                    )
                }
                videos > 0 -> {
                    icon.setImageResource(R.drawable.ic_videocam_white_24dp)
                    label.text = context.i18nPlural(R.plurals.num_videos_count, videos, "count" to videos)
                }
                images > 0 -> {
                    icon.setImageResource(R.drawable.ic_image_library_24dp)
                    label.text = context.i18nPlural(R.plurals.num_images_count, images, "count" to images)
                }
                else -> {
                    icon.setImageResource(R.drawable.ic_attachment_white_24dp)
                    label.text = context.i18nPlural(R.plurals.num_attachments_count, attachments.size, "count" to attachments.size)
                }
            }
        }

        val media = attachments.firstOrNull { it.type != MessageAttachmentType.FILE }
        val thumbnailUrl = media?.proxyUrl
            ?: message.embeds.orEmpty().firstNotNullOfOrNull { it.thumbnail?.proxyUrl }
        preview.findViewById<View>(R.id.forward_preview_thumbnail_wrap).visibility =
            if (thumbnailUrl == null) View.GONE else View.VISIBLE
        if (thumbnailUrl != null) {
            preview.findViewById<SimpleDraweeView>(R.id.forward_preview_thumbnail)
                .setImageURI(Uri.parse(thumbnailUrl))
            preview.findViewById<View>(R.id.forward_preview_thumbnail_play).visibility =
                if (media?.type == MessageAttachmentType.VIDEO) View.VISIBLE else View.GONE
            val overflow = preview.findViewById<TextView>(R.id.forward_preview_thumbnail_overflow)
            overflow.text = context.i18nFormat(R.string.forum_reactions_overflow, "count" to attachments.size - 1)
            overflow.visibility = if (attachments.size > 1) View.VISIBLE else View.GONE
        }
    }

    private fun renderFooter() {
        val count = selected.size
        sendButton.text = if (count <= 1) {
            getString(R.string.send)
        } else {
            requireContext().i18nFormat(R.string.messages_send_separately, "count" to count)
        }
        sendButton.isEnabled = count > 0 && !isSending

        toolbar.subtitle = if (count >= MAX_DESTINATION_COUNT) {
            requireContext().i18nFormat(
                R.string.messages_forward_max_destination_count,
                "count" to MAX_DESTINATION_COUNT,
            )
        } else {
            null
        }

        val slowmodeChannels = selected.values.mapNotNull { it.channel }.filter(::isRatelimited)
        val hasComment = comment.editText?.text?.isNotBlank() == true
        warning.visibility = if (slowmodeChannels.isEmpty() || !hasComment) View.GONE else View.VISIBLE
        if (warning.visibility == View.VISIBLE) {
            warning.text = requireContext().i18nFormat(
                R.string.message_forward_slowmode_warning,
                "count" to requireContext().i18nPlural(
                    R.plurals.message_forward_slowmode_warning_count,
                    slowmodeChannels.size,
                    "count" to slowmodeChannels.size,
                ),
                "channelNames" to slowmodeChannels.joinToString { it.name.orEmpty() },
            )
        }
    }

    private fun isRatelimited(channel: Channel): Boolean {
        if (channel.rateLimitPerUser <= 0) return false
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()[channel.id]
        return !PermissionUtils.can(Permission.BYPASS_SLOWMODE, permissions)
    }

    private fun close() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        } else {
            requireActivity().finish()
        }
    }

    private fun copyMessageLink(channelId: Long, messageId: Long) {
        val guildSegment = sourceChannel?.guildId?.takeUnless { it == 0L }?.toString() ?: "@me"
        AppToast.copyToClipboard(
            requireContext(),
            "https://discord.com/channels/$guildSegment/$channelId/$messageId",
            getString(R.string.link_copied),
        )
    }

    private fun send() {
        val message = source ?: return
        val origin = sourceChannel ?: return
        if (selected.isEmpty() || isSending) return
        setSending(true)

        resolveChannels(selected.values.toList()) { channels ->
            if (channels.isEmpty()) {
                setSending(false)
                Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
                return@resolveChannels
            }
            val note = comment.editText?.text?.toString().orEmpty()
            Observable.from(channels)
                .concatMap { channel ->
                    // Reference `sendForward`: the note is a second message, and slowmode drops it.
                    val withNote = note.isNotBlank() && !isRatelimited(channel)
                    ForwardMessageSender.send(channel.id, message, origin)
                        .flatMap { if (withNote) sendNote(channel, note) else Observable.just(it) }
                        .map { true }
                        .onErrorReturn { false }
                }
                .toList()
                .restSubscribeOn()
                .ui(this)
                .subscribe({ results ->
                    setSending(false)
                    if (results.all { it }) {
                        AppToast.show(requireContext(), R.string.message_forward_success)
                        // Reference lands you in the destination when there is only the one.
                        channels.singleOrNull()?.let { StoreStream.selectChannel(it.guildId, it.id) }
                        close()
                    } else {
                        Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
                    }
                }, {
                    setSending(false)
                    Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
                })
        }
    }

    private fun sendNote(channel: Channel, note: String) = RestAPI.api.sendMessage(
        channel.id,
        RestAPIParams.Message(content = note),
        CaptchaRequestPolicy(),
    )

    /**
     * A user destination has no channel until one is opened, so the DM is created on the way out.
     * Those callbacks land off the main thread, so the tally is folded back onto the view first.
     */
    private fun resolveChannels(
        items: List<WidgetGlobalSearchModel.ItemDataPayload>,
        completed: (List<Channel>) -> Unit,
    ) {
        val root = view ?: return
        val resolved = mutableListOf<Channel>()
        var remaining = items.size
        if (remaining == 0) return completed(emptyList())
        val onEach = { channel: Channel? ->
            root.post {
                if (isAdded) {
                    if (channel != null) resolved += channel
                    remaining -= 1
                    if (remaining == 0) completed(resolved.toList())
                }
            }
        }
        items.forEach { item ->
            val existing = item.channel
            when {
                existing != null -> onEach(existing)
                item is WidgetGlobalSearchModel.ItemUser ->
                    StoreStream.createPrivateChannel(listOf(item.user.id)) { onEach(it.getOrNull()) }
                else -> onEach(null)
            }
        }
    }

    private fun setSending(sending: Boolean) {
        isSending = sending
        renderFooter()
    }

    private class SimpleTextWatcher(private val changed: (String) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }

    companion object {
        /** Reference `MAX_DESTINATION_COUNT`. */
        const val MAX_DESTINATION_COUNT = 5

        private const val ARG_CHANNEL_ID = "forward_channel_id"
        private const val ARG_MESSAGE_ID = "forward_message_id"

        fun newInstance(message: Message) = WidgetForwardMessage().apply {
            arguments = bundleOf(
                ARG_CHANNEL_ID to message.channelId,
                ARG_MESSAGE_ID to message.id,
            )
        }
    }
}

