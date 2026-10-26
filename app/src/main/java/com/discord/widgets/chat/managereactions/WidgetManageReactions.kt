package com.discord.widgets.chat.managereactions

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetManageReactionsSheetBinding
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.user.usersheet.WidgetUserSheet
import rx.subscriptions.CompositeSubscription

/**
 * Who reacted with what. The reference opens this as a full-screen page; it is a sheet here so a
 * reactor's profile opens on top of the chat rather than over a page stacked on it.
 */
class WidgetManageReactions : AppBottomSheet() {
    private lateinit var modelProvider: ManageReactionsModelProvider
    private var emojisAdapter: ManageReactionsEmojisAdapter? = null
    private var resultsAdapter: ManageReactionsResultsAdapter? = null

    override fun getContentViewResId(): Int = R.layout.widget_manage_reactions_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = WidgetManageReactionsSheetBinding.bind(view)
        if (!::modelProvider.isInitialized) {
            modelProvider = ManageReactionsModelProvider(
                argumentsOrDefault.getLong(EXTRA_CHANNEL_ID),
                argumentsOrDefault.getLong(EXTRA_MESSAGE_ID),
                argumentsOrDefault.getString(EXTRA_EMOJI_KEY),
            )
        }
        emojisAdapter = MGRecyclerAdapter.configure(
            ManageReactionsEmojisAdapter(binding.manageReactionsEmojisRecycler),
        ).also { it.onEmojiSelectedListener = modelProvider::onEmojiTargeted }
        resultsAdapter = MGRecyclerAdapter.configure(
            ManageReactionsResultsAdapter(binding.manageReactionsResultsRecycler),
        ).also { adapter ->
            adapter.onUserClicked = { item ->
                val guildId = StoreStream.getChannels().getChannel(item.channelId)?.guildId?.takeIf { it != 0L }
                WidgetUserSheet.show(parentFragmentManager, item.user.id, item.channelId, guildId)
            }
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        modelProvider.get()
            .ui(this)
            .appSubscribe(WidgetManageReactions::class.java, context = context, onNext = ::configureUI)
    }

    override fun onDestroyView() {
        emojisAdapter = null
        resultsAdapter = null
        super.onDestroyView()
    }

    private fun configureUI(data: ManageReactionsModel?) {
        if (data == null) {
            dismiss()
            return
        }
        emojisAdapter?.setData(data.reactionItems)
        resultsAdapter?.setData(data.userItems)
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_EMOJI_KEY = "com.discord.intent.extra.EXTRA_EMOJI_KEY"

        @JvmStatic
        @JvmOverloads
        fun show(
            fragmentManager: FragmentManager,
            channelId: Long,
            messageId: Long,
            targetedReaction: MessageReaction? = null,
        ) {
            WidgetManageReactions().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_CHANNEL_ID, channelId)
                    putLong(EXTRA_MESSAGE_ID, messageId)
                    putString(EXTRA_EMOJI_KEY, targetedReaction?.emoji?.reactionKey)
                }
            }.show(fragmentManager, WidgetManageReactions::class.java.name)
        }
    }
}
