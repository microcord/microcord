package com.discord.widgets.chat.list.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.view.View
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetViewRawBinding
import com.discord.models.deserialization.gson.registerDiscordApiTypeAdapters
import com.discord.stores.StoreStream
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageRenderContext
import com.google.gson.FieldNamingPolicy
import com.google.gson.GsonBuilder

/**
 * A message's markdown source and its JSON in the API's own field names, laid out like Aliucord's
 * ViewRaw. Opened from the message menu when the "View Raw" developer option is on.
 */
class WidgetViewRaw : AppFragment(R.layout.widget_view_raw) {
    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetViewRawBinding.bind(view)
        setActionBarDisplayHomeAsUpEnabled()

        val intent = mostRecentIntent
        val message = StoreStream.getMessages()
            .getMessage(intent.getLongExtra(ARG_CHANNEL_ID, 0L), intent.getLongExtra(ARG_MESSAGE_ID, 0L))
        if (message == null) {
            requireActivity().finish()
            return
        }
        setActionBarTitle(getString(R.string.view_raw_title, message.author?.username.orEmpty()))
        setActionBarSubtitle(R.string.developer_view_raw)

        val content = message.content.orEmpty()
        val json = GSON.toJson(message.synthesizeApiMessage())
        val hasContent = content.isNotEmpty()

        binding.viewRawCopyContent.isEnabled = hasContent
        binding.viewRawCopyContent.setOnClickListener {
            copy(content)
            AppToast.show(this, R.string.view_raw_copied_content)
        }
        binding.viewRawCopyJson.setOnClickListener {
            copy(json)
            AppToast.show(this, R.string.view_raw_copied_data)
        }
        binding.viewRawContent.visibility = if (hasContent) View.VISIBLE else View.GONE
        binding.viewRawDivider.visibility = binding.viewRawContent.visibility
        if (hasContent) binding.viewRawContent.setDraweeSpanStringBuilder(codeBlock(content, null))
        binding.viewRawJson.setDraweeSpanStringBuilder(codeBlock(json, "js"))
    }

    /** Renders [text] as a chat code block, so it looks exactly like one in a message. */
    private fun codeBlock(text: String, language: String?) = AstRenderer.render(
        // A fence inside the text would end the block early; a zero-width space keeps it literal.
        DiscordParser.parse("```${language.orEmpty()}\n${text.replace("```", "``​`")}\n```"),
        MessageRenderContext(requireContext(), myId = 0L, isAnimationEnabled = false),
    )

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.developer_view_raw), text))
    }

    companion object {
        private const val ARG_CHANNEL_ID = "channel_id"
        private const val ARG_MESSAGE_ID = "message_id"

        private val GSON = GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .registerDiscordApiTypeAdapters()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create()

        fun launch(context: Context, channelId: Long, messageId: Long) = AppScreenRouter.launch(
            context,
            WidgetViewRaw::class.java,
            Intent().putExtra(ARG_CHANNEL_ID, channelId).putExtra(ARG_MESSAGE_ID, messageId),
        )
    }
}
