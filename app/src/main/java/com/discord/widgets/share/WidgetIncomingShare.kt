package com.discord.widgets.share

import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.permission.Permission
import com.discord.api.premium.PremiumTier
import com.discord.app.AppFragment
import com.discord.app.appViewModels
import com.discord.databinding.ViewImageBinding
import com.discord.databinding.WidgetIncomingShareBinding
import com.discord.dialogs.ImageUploadFailedDialog
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.utilities.ShareUtils
import com.discord.utilities.analytics.AnalyticsTracker
import com.discord.utilities.attachments.UploadAttachment
import com.discord.utilities.messagesend.MessageResult
import com.discord.utilities.messagesend.MessageSendCoordinator
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.isStaff
import com.discord.widgets.captcha.WidgetCaptchaBottomSheet
import com.discord.widgets.chat.input.WidgetChatInput
import com.discord.widgets.user.search.WidgetGlobalSearchAdapter
import com.discord.widgets.user.search.WidgetGlobalSearchModel
import com.discord.widgets.user.search.WidgetGlobalSearchViewModel
import com.facebook.drawee.view.SimpleDraweeView
import rx.Subscription

class WidgetIncomingShare : AppFragment(R.layout.widget_incoming_share) {
    private var binding: WidgetIncomingShareBinding? = null
    private val searchViewModel: WidgetGlobalSearchViewModel by appViewModels { WidgetGlobalSearchViewModel() }
    private lateinit var resultsAdapter: WidgetGlobalSearchAdapter
    private val previewAdapter = AttachmentAdapter(::removeAttachment)

    private var content = ShareUtils.SharedContent(null, null, emptyList(), null)
    private var selected: WidgetGlobalSearchModel.ItemDataPayload? = null
    private var latestSearchModel: WidgetGlobalSearchModel? = null
    private var isOnCooldown = false
    private var isSending = false
    private var cooldownSubscription: Subscription? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetIncomingShareBinding.bind(view).also { this.binding = it }
        content = ShareUtils.SharedContent(
            arguments?.getString(ARG_TEXT),
            arguments?.getString(ARG_SUBJECT),
            arguments?.getStringArrayList(ARG_URIS).orEmpty().map(Uri::parse),
            arguments?.getLong(ARG_DIRECT_SHARE_ID, 0L)?.takeIf { it > 0 },
        )

        binding.actionBarToolbar.apply {
            setNavigationOnClickListener { requireActivity().finish() }
            setNavigationContentDescription(R.string.close)
        }
        binding.externalShareComment.editText?.apply {
            setText(content.message)
            addTextChangedListener(SimpleTextWatcher { updateSendMenu() })
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND && selected != null) {
                    send()
                    true
                } else {
                    false
                }
            }
        }
        binding.externalShareList.apply {
            adapter = previewAdapter
            ViewCompat.setNestedScrollingEnabled(this, false)
            setHasFixedSize(true)
            val spacing = (8 * resources.displayMetrics.density).toInt()
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                    outRect.right = spacing
                    if (parent.getChildAdapterPosition(view) == 0) outRect.left = spacing
                }
            })
        }
        binding.externalShareSearchResults.apply {
            resultsAdapter = MGRecyclerAdapter.configure(WidgetGlobalSearchAdapter(this))
            adapter = resultsAdapter
            ViewCompat.setNestedScrollingEnabled(this, false)
            setHasFixedSize(false)
        }
        resultsAdapter.onSelectedListener = { _, isLongPress, item ->
            if (!isLongPress && item !is WidgetGlobalSearchModel.ItemHeader) select(item)
        }
        binding.externalShareSearch.editText?.addTextChangedListener(
            SimpleTextWatcher(searchViewModel::setFilter),
        )
        binding.externalShareSearch.editText?.setOnFocusChangeListener { _, focused ->
            if (focused) binding.scrollView.smoothScrollTo(0, binding.externalShareComment.bottom)
        }
        binding.widgetChannelSearchItemRemove.setOnClickListener {
            select(null)
            binding.externalShareSearch.postDelayed({
                binding.externalShareSearch.editText?.requestFocus()
                binding.externalShareSearch.editText?.let(::showKeyboard)
            }, 200L)
        }
        binding.searchFlipper.apply {
            inAnimation = AnimationUtils.loadAnimation(context, R.anim.abc_grow_fade_in_from_bottom)
            outAnimation = AnimationUtils.loadAnimation(context, R.anim.abc_shrink_fade_out_from_bottom)
        }
        binding.incomingShareResultsFlipper.displayedChild = 1
        content.uris.forEachIndexed { index, uri ->
            AnalyticsTracker.addAttachment(
                AnalyticsTracker.ATTACHMENT_SOURCE_SHARE,
                requireContext().contentResolver.getType(uri).orEmpty(),
                index,
            )
        }
        renderContent()
        select(null)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        searchViewModel.observeViewState().ui(this).subscribe(::renderSearch)
        observeCooldown()
    }

    override fun onDestroyView() {
        cooldownSubscription?.unsubscribe()
        cooldownSubscription = null
        binding = null
        super.onDestroyView()
    }

    private fun renderContent() {
        val binding = binding ?: return
        previewAdapter.items = content.uris
        binding.externalShareListWrap.visibility = if (content.uris.isEmpty()) View.GONE else View.VISIBLE
        binding.externalShareComment.hint = if (content.uris.isEmpty()) {
            getString(R.string.upload_area_leave_a_comment)
        } else {
            getString(R.string.add_a_comment_optional)
        }
        updateSendMenu()
    }

    private fun renderSearch(model: WidgetGlobalSearchModel) {
        val binding = binding ?: return
        latestSearchModel = model
        val rows = shareResults(model.data, selected?.key)
        resultsAdapter.setData(rows)
        binding.incomingShareResultsFlipper.displayedChild = if (rows.any { it !is WidgetGlobalSearchModel.ItemHeader }) 0 else 1
        binding.externalShareSearchResults.scrollToPosition(0)

        val directShareId = content.directShareId
        if (selected == null && directShareId != null) {
            rows.firstOrNull { it.channel?.id == directShareId }?.let(::select)
        }
    }

    private fun select(item: WidgetGlobalSearchModel.ItemDataPayload?) {
        selected = item
        val binding = binding ?: return
        binding.searchFlipper.displayedChild = if (item == null) 0 else 1
        binding.externalShareSearch.isFocusable = item == null
        binding.externalShareComment.editText?.imeOptions = if (item == null) {
            android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        } else {
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        when (item) {
            is WidgetGlobalSearchModel.ItemChannel -> binding.widgetChannelSearchItem.onConfigure(item)
            is WidgetGlobalSearchModel.ItemGuild -> binding.widgetChannelSearchItem.onConfigure(item)
            is WidgetGlobalSearchModel.ItemUser -> binding.widgetChannelSearchItem.onConfigure(item)
        }
        latestSearchModel?.let(::renderSearch)
        binding.scrollView.post { binding.scrollView.scrollTo(0, 0) }
        observeCooldown()
        updateSendMenu()
    }

    private fun observeCooldown() {
        cooldownSubscription?.unsubscribe()
        val channelId = selected?.channel?.id
        cooldownSubscription = StoreStream.getSlowMode().observeCooldownSecs(channelId, StoreSlowMode.Type.MessageSend)
            .ui(this)
            .subscribe { seconds -> isOnCooldown = seconds > 0 }
    }

    private fun updateSendMenu() {
        val binding = binding ?: return
        val comment = binding.externalShareComment.editText?.text?.toString().orEmpty()
        val enabled = canSend(selected != null, comment, content.uris.size) && !isSending
        binding.actionBarToolbar.menu.clear()
        binding.actionBarToolbar.inflateMenu(if (enabled) R.menu.menu_external_share else R.menu.menu_empty)
        binding.actionBarToolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_send) {
                send()
                true
            } else {
                false
            }
        }
    }

    private fun send() {
        val receiver = selected ?: return
        if (isOnCooldown) {
            Toast.makeText(requireContext(), R.string.channel_slowmode_desc_short, Toast.LENGTH_SHORT).show()
            return
        }
        setSending(true)
        resolveChannel(receiver) { result ->
            binding?.root?.post {
                if (!isAdded) return@post
                result.onSuccess(::sendToChannel).onFailure(::showError)
            }
        }
    }

    private fun resolveChannel(
        receiver: WidgetGlobalSearchModel.ItemDataPayload,
        completed: (Result<Channel>) -> Unit,
    ) {
        when (receiver) {
            is WidgetGlobalSearchModel.ItemChannel -> completed(Result.success(receiver.channel))
            is WidgetGlobalSearchModel.ItemUser -> receiver.channel?.let { completed(Result.success(it)) }
                ?: StoreStream.createPrivateChannel(listOf(receiver.user.id), completed)
            is WidgetGlobalSearchModel.ItemGuild -> shareChannel(receiver.guild)?.let {
                StoreStream.selectChannel(it.guildId, it.id)
                completed(Result.success(it))
            } ?: completed(Result.failure(IllegalStateException("No sendable channel")))
            else -> completed(Result.failure(IllegalArgumentException("Unsupported recipient")))
        }
    }

    private fun sendToChannel(channel: Channel) {
        val context = requireContext()
        val attachments = runCatching {
            content.uris.mapIndexed { index, uri ->
                UploadAttachment.from(context.contentResolver, index.toLong(), uri, uri.lastPathSegment ?: "attachment")
            }
        }.getOrElse {
            showError(it)
            return
        }
        val me = StoreStream.getUsers().getMe()
        val guild = StoreStream.getGuilds().getGuild(channel.guildId)
        val limitMb = uploadLimitMB(me.premiumTier, guild?.premiumTier ?: 0, me.isStaff)
        if (attachments.sumOf(UploadAttachment::size) > limitMb * BYTES_PER_MEGABYTE) {
            setSending(false)
            ImageUploadFailedDialog.show(
                parentFragmentManager,
                isPremium(me.premiumTier),
                limitMb,
                onResendCompressed = null,
                isVideo = attachments.any { it.mimeType.startsWith("video/") },
            )
            return
        }

        val coordinator = WidgetChatInput.coordinatorProvider() ?: run {
            showError(IllegalStateException("Message sender unavailable"))
            return
        }
        val submission = coordinator.send(
            MessageSendCoordinator.Send(
                channelId = channel.id,
                guildId = channel.guildId.takeUnless { it == 0L },
                content = binding?.externalShareComment?.editText?.text?.toString().orEmpty(),
                canSend = true,
                systemDm = channel.type == Channel.DM && channel.recipients.orEmpty().any { it.system == true },
                attachments = attachments.takeIf { it.isNotEmpty() },
            ),
        )
        when (submission) {
            is MessageSendCoordinator.Submission.Accepted -> {
                coordinator.observeUpdates().ui(this).subscribe { update ->
                    if (update.requestId != submission.requestId) return@subscribe
                    when (update) {
                        is MessageSendCoordinator.Update.Reconciled -> {
                            setSending(false)
                            requireActivity().finish()
                        }
                        is MessageSendCoordinator.Update.Failed -> {
                            if (update.result is MessageResult.CaptchaRequired) {
                                showCaptcha(coordinator, submission.requestId, update.result)
                            } else {
                                showSendFailure(update.result)
                            }
                        }
                        is MessageSendCoordinator.Update.Cancelled -> setSending(false)
                        is MessageSendCoordinator.Update.Optimistic -> Unit
                    }
                }
            }
            is MessageSendCoordinator.Submission.Rejected -> showSendFailure(submission.result)
        }
    }

    private fun setSending(sending: Boolean) {
        isSending = sending
        binding?.dimmerView?.setDimmed(sending)
        updateSendMenu()
    }

    private fun showCaptcha(
        coordinator: MessageSendCoordinator,
        requestId: String,
        result: MessageResult.CaptchaRequired,
    ) {
        setSending(false)
        val challenge = result.challenge ?: return
        WidgetCaptchaBottomSheet.show(parentFragmentManager, requestId, challenge) { solution ->
            if (solution == null) return@show
            setSending(true)
            if (!coordinator.retry(requestId, solution)) showError(IllegalStateException("Message retry unavailable"))
        }
    }

    private fun showSendFailure(result: MessageResult) {
        setSending(false)
        val message = when (result) {
            is MessageResult.Slowmode -> R.string.channel_slowmode_desc_short
            MessageResult.NoValidContent -> R.string.message_too_long_header
            else -> R.string.error
        }
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(@Suppress("UNUSED_PARAMETER") error: Throwable) {
        setSending(false)
        Toast.makeText(requireContext(), R.string.error, Toast.LENGTH_SHORT).show()
    }

    private fun removeAttachment(uri: Uri) {
        content = content.copy(uris = content.uris.filterNot { it == uri })
        renderContent()
    }

    private fun shareChannel(guild: Guild): Channel? {
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()
        return StoreStream.getChannels().getChannelsForGuild(guild.id).values
            .filter(::isSendableChannel)
            .filter { canSendInChannel(it, permissions[it.id]) }
            .sortedBy { it.position }
            .firstOrNull()
    }

    private fun shareResults(
        rows: List<WidgetGlobalSearchModel.ItemDataPayload>,
        selectedKey: String?,
    ): List<WidgetGlobalSearchModel.ItemDataPayload> {
        val permissions = StoreStream.getPermissions().getPermissionsByChannel()
        val meId = StoreStream.getUsers().getMe().id
        val filtered = rows.filter { item ->
            item.key != selectedKey && when (item) {
                is WidgetGlobalSearchModel.ItemHeader -> true
                is WidgetGlobalSearchModel.ItemChannel -> isSendableChannel(item.channel) &&
                    item.channel.recipients.orEmpty().none { it.system == true } &&
                    canSendInChannel(item.channel, permissions[item.channel.id])
                is WidgetGlobalSearchModel.ItemUser -> !item.user.isSystemUser && item.user.id != meId
                is WidgetGlobalSearchModel.ItemGuild -> shareChannel(item.guild) != null
                else -> false
            }
        }
        return filtered.filterIndexed { index, item ->
            val next = filtered.drop(index + 1).firstOrNull()
            item !is WidgetGlobalSearchModel.ItemHeader || next != null && next !is WidgetGlobalSearchModel.ItemHeader
        }
    }

    private class AttachmentAdapter(
        private val onRemove: (Uri) -> Unit,
    ) : RecyclerView.Adapter<AttachmentAdapter.Holder>() {
        var items = emptyList<Uri>()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = ViewImageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(binding, onRemove)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        class Holder(
            private val binding: ViewImageBinding,
            private val onRemove: (Uri) -> Unit,
        ) : RecyclerView.ViewHolder(binding.root) {
            fun bind(uri: Uri) {
                (binding.root as SimpleDraweeView).setImageURI(uri)
                binding.root.contentDescription = binding.root.context.getString(R.string.attachment_utilities_remove)
                binding.root.setOnClickListener { onRemove(uri) }
            }
        }
    }

    private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = changed(s?.toString().orEmpty())
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    companion object {
        fun newInstance(content: ShareUtils.SharedContent) = WidgetIncomingShare().apply {
            arguments = bundleOf(
                ARG_TEXT to content.text,
                ARG_SUBJECT to content.subject,
                ARG_URIS to ArrayList(content.uris.map(Uri::toString)),
                ARG_DIRECT_SHARE_ID to (content.directShareId ?: 0L),
            )
        }

        internal fun canSend(selected: Boolean, comment: String, attachmentCount: Int): Boolean =
            selected && (comment.isNotBlank() || attachmentCount > 0)

        internal fun uploadLimitMB(premiumTier: PremiumTier, guildPremiumTier: Int, isStaff: Boolean): Int = maxOf(
            when {
                isStaff -> 200
                premiumTier == PremiumTier.TIER_1 -> 50
                premiumTier == PremiumTier.TIER_2 -> 100
                premiumTier == PremiumTier.TIER_0 -> 20
                else -> 8
            },
            when (guildPremiumTier) {
                0, 1 -> 8
                2 -> 50
                else -> 100
            },
        )

        private fun isPremium(tier: PremiumTier) = tier in setOf(
            PremiumTier.TIER_0,
            PremiumTier.TIER_1,
            PremiumTier.TIER_2,
        )

        private fun isSendableChannel(channel: Channel) = channel.type in SENDABLE_TYPES

        private fun canSendInChannel(channel: Channel, permissions: Long?): Boolean {
            if (channel.guildId == 0L) return true
            val required = if (channel.type in THREAD_TYPES) Permission.SEND_MESSAGES_IN_THREADS else Permission.SEND_MESSAGES
            return permissions?.and(required) != 0L
        }

        private val SENDABLE_TYPES = setOf(
            Channel.GUILD_TEXT,
            Channel.DM,
            Channel.GROUP_DM,
            Channel.GUILD_ANNOUNCEMENT,
            Channel.ANNOUNCEMENT_THREAD,
            Channel.PUBLIC_THREAD,
            Channel.PRIVATE_THREAD,
        )
        private val THREAD_TYPES = setOf(Channel.ANNOUNCEMENT_THREAD, Channel.PUBLIC_THREAD, Channel.PRIVATE_THREAD)

        private const val BYTES_PER_MEGABYTE = 1024L * 1024L
        private const val ARG_TEXT = "text"
        private const val ARG_SUBJECT = "subject"
        private const val ARG_URIS = "uris"
        private const val ARG_DIRECT_SHARE_ID = "direct_share_id"
    }
}
