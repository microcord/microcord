package com.discord.widgets.forums

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetForumGuidelinesBottomSheetBinding
import com.discord.models.message.Message
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageTextRenderer
import com.discord.stores.StoreStream
import com.discord.utilities.rx.filterNull
import com.discord.utilities.rx.ui
import rx.subscriptions.CompositeSubscription

class WidgetForumGuidelinesBottomSheet : AppBottomSheet() {
    private val channelId get() = argumentsOrDefault.getLong(EXTRA_CHANNEL_ID)
    private val guildId get() = argumentsOrDefault.getLong(EXTRA_GUILD_ID)
    private val requestKey get() = argumentsOrDefault.getString(EXTRA_REQUEST_KEY).orEmpty()
    private var _binding: WidgetForumGuidelinesBottomSheetBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun getContentViewResId() = R.layout.widget_forum_guidelines_bottom_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetForumGuidelinesBottomSheetBinding.bind(view)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        compositeSubscription.add(
            StoreStream.getChannels().observeChannel(channelId).filterNull().ui(this).subscribe { channel ->
                // The reference parses guidelines as markdown, headings and lists included.
                val rendered = MessageTextRenderer.render(
                    Message(id = channel.id, channelId = channel.id, content = channel.topic),
                    binding.channelTopic,
                    parserOptions = DiscordParser.ParserOptions.FORUM_POST_FIRST_MESSAGE,
                )
                binding.channelTopic.setDraweeSpanStringBuilder(rendered.text)
                binding.channelTopic.contentDescription = rendered.plainText
                binding.gotIt.setOnClickListener {
                    setFragmentResult(requestKey, bundleOf(EXTRA_GUILD_ID to guildId, EXTRA_CHANNEL_ID to channelId))
                    dismiss()
                }
            },
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val EXTRA_REQUEST_KEY = "INTENT_EXTRA_REQUEST_KEY"
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"

        fun registerForResult(fragment: Fragment, requestKey: String, onActionTaken: (Long, Long) -> Unit) {
            fragment.setFragmentResultListener(requestKey) { resultKey, bundle ->
                if (resultKey == requestKey) {
                    onActionTaken(bundle.getLong(EXTRA_GUILD_ID), bundle.getLong(EXTRA_CHANNEL_ID))
                }
            }
        }

        fun show(fragmentManager: FragmentManager, guildId: Long, parentChannelId: Long, requestKey: String) {
            ForumGuidelinesManager.markGuidelinesSeen(parentChannelId)
            WidgetForumGuidelinesBottomSheet().apply {
                arguments = bundleOf(
                    EXTRA_GUILD_ID to guildId,
                    EXTRA_CHANNEL_ID to parentChannelId,
                    EXTRA_REQUEST_KEY to requestKey,
                )
            }.show(fragmentManager, WidgetForumGuidelinesBottomSheet::class.java.name)
        }
    }
}
