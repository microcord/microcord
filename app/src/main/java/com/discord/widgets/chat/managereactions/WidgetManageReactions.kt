package com.discord.widgets.chat.managereactions

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.message.reaction.MessageReaction
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle

class WidgetManageReactions : AppFragment(R.layout.widget_manage_reactions) {
    private lateinit var emojisAdapter: ManageReactionsEmojisAdapter
    private lateinit var modelProvider: ManageReactionsModelProvider
    private lateinit var resultsAdapter: ManageReactionsResultsAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.reactions)
        if (!::modelProvider.isInitialized) {
            modelProvider = ManageReactionsModelProvider(
                mostRecentIntent.getLongExtra(EXTRA_CHANNEL_ID, 0L),
                mostRecentIntent.getLongExtra(EXTRA_MESSAGE_ID, 0L),
                mostRecentIntent.getStringExtra(EXTRA_EMOJI_KEY),
            )
        }
        emojisAdapter = MGRecyclerAdapter.configure(
            ManageReactionsEmojisAdapter(
                view.findViewById<RecyclerView>(R.id.manage_reactions_emojis_recycler),
            ),
        ).also { it.onEmojiSelectedListener = modelProvider::onEmojiTargeted }
        resultsAdapter = MGRecyclerAdapter.configure(
            ManageReactionsResultsAdapter(
                view.findViewById<RecyclerView>(R.id.manage_reactions_results_recycler),
            ),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        modelProvider.get()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetManageReactions::class.java, context = context, onNext = ::configureUI)
    }

    private fun configureUI(data: ManageReactionsModel?) {
        if (data == null) {
            appActivity?.finish()
            return
        }
        emojisAdapter.setData(data.reactionItems)
        resultsAdapter.setData(data.userItems)
    }

    companion object {
        private const val EXTRA_CHANNEL_ID = "com.discord.intent.extra.EXTRA_CHANNEL_ID"
        private const val EXTRA_MESSAGE_ID = "com.discord.intent.extra.EXTRA_MESSAGE_ID"
        private const val EXTRA_EMOJI_KEY = "com.discord.intent.extra.EXTRA_EMOJI_KEY"

        @JvmStatic
        fun create(
            channelId: Long,
            messageId: Long,
            context: Context,
            targetedReaction: MessageReaction? = null,
        ) {
            val intent = Intent()
                .putExtra(EXTRA_CHANNEL_ID, channelId)
                .putExtra(EXTRA_MESSAGE_ID, messageId)
                .putExtra(EXTRA_EMOJI_KEY, targetedReaction?.emoji?.reactionKey)
            AppScreenRouter.launch(context, WidgetManageReactions::class.java, intent)
        }
    }
}
