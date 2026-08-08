package com.discord.widgets.chat.list.reactions

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui

class WidgetManageReactions : AppFragment(R.layout.widget_manage_reactions) {
    private lateinit var modelProvider: ManageReactionsModelProvider
    private lateinit var emojisAdapter: ManageReactionsEmojisAdapter
    private lateinit var resultsAdapter: ManageReactionsResultsAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.reactions)
        modelProvider = ManageReactionsModelProvider(
            mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L),
            mostRecentIntent.getLongExtra(EXTRA_MESSAGE_ID, 0L),
            mostRecentIntent.getStringExtra(EXTRA_EMOJI_KEY),
        )
        emojisAdapter = ManageReactionsEmojisAdapter(
            view.findViewById<RecyclerView>(R.id.manage_reactions_emojis_recycler),
        ).also { adapter ->
            adapter.onEmojiSelected = modelProvider::onEmojiTargeted
            adapter.recycler.adapter = adapter
        }
        resultsAdapter = ManageReactionsResultsAdapter(
            view.findViewById<RecyclerView>(R.id.manage_reactions_results_recycler),
            StoreStream.getMessageReactions(),
        ).also { it.recycler.adapter = it }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        modelProvider.get()
            .ui(this)
            .appSubscribe(WidgetManageReactions::class.java, context = context, onNext = ::configureUI)
    }

    private fun configureUI(model: ManageReactionsModel?) {
        if (model == null) {
            appActivity?.finish()
            return
        }
        emojisAdapter.setData(model.reactionItems)
        resultsAdapter.setData(model.userItems)
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_EMOJI_KEY = "com.discord.intent.extra.EXTRA_EMOJI_KEY"

        @JvmOverloads
        fun create(
            channelId: Long,
            messageId: Long,
            context: Context,
            targetedReaction: MessageReaction? = null,
        ) {
            AppScreenRouter.launch(
                context,
                WidgetManageReactions::class.java,
                Intent()
                    .putExtra(EXTRA_CHANNEL_ID, channelId)
                    .putExtra(EXTRA_MESSAGE_ID, messageId)
                    .putExtra(EXTRA_EMOJI_KEY, targetedReaction?.emoji?.reactionKey),
            )
        }
    }
}
