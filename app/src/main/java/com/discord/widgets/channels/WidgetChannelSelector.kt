package com.discord.widgets.channels

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildVocalChannel
import com.discord.api.channel.isStageVoiceChannel
import com.discord.app.AppBottomSheet
import com.discord.stores.StoreChannels
import com.discord.stores.StoreStream
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.drawable.setCompoundDrawablesCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import java.io.Serializable
import rx.Observable
import rx.subscriptions.CompositeSubscription

class WidgetChannelSelector : AppBottomSheet() {
    private lateinit var adapter: Adapter
    private val requestKey: String get() = argumentsOrDefault.getString(ARG_REQUEST_KEY).orEmpty()

    override fun getContentViewResId(): Int = R.layout.widget_channel_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = requireNotNull<RecyclerView>(view.findViewById(R.id.channel_selector_list))
        adapter = MGRecyclerAdapter.configure(
            Adapter(recycler, this, argumentsOrDefault.getInt(ARG_NO_CHANNEL_STRING_ID)),
        )
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val filter = argumentsOrDefault.getSerializable(ARG_FILTER_FUNCTION) as? FilterFunction
            ?: BaseFilterFunction()
        Model.get(
            argumentsOrDefault.getLong(ARG_GUILD_ID, -1L),
            argumentsOrDefault.getBoolean(ARG_INCLUDE_NO_CHANNEL),
            filter,
        ).ui(this).appSubscribe(WidgetChannelSelector::class.java) { adapter.setData(it) }
    }

    private fun onChannelSelected(channel: Channel?) {
        setFragmentResult(
            requestKey,
            bundleOf(
                RESULT_EXTRA_CHANNEL_ID to (channel?.id ?: -1L),
                RESULT_EXTRA_CHANNEL_NAME to channel?.getDisplayName(),
            ),
        )
        dismiss()
    }

    private class Adapter(
        recycler: RecyclerView,
        private val dialog: WidgetChannelSelector,
        private val noChannelStringId: Int,
    ) : MGRecyclerAdapterSimple<Model.Item>(recycler, deferredDiffs = false) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ItemChannel(R.layout.widget_channel_selector_item, this, noChannelStringId)

        class ItemChannel(
            layout: Int,
            adapter: Adapter,
            private val noChannelStringId: Int,
        ) : MGRecyclerViewHolder<Adapter, Model.Item>(layout, adapter) {
            private val name = itemView as TextView

            override fun onConfigure(position: Int, data: Model.Item) {
                super.onConfigure(position, data)
                name.text = data.channel?.getDisplayName() ?: itemView.context.getString(noChannelStringId)
                name.setCompoundDrawablesCompat(
                    start = itemView.getThemedDrawableRes(
                        when (data.channel?.type) {
                            Channel.GUILD_VOICE -> R.attr.ic_volume_up
                            Channel.GUILD_STAGE_VOICE -> R.attr.ic_channel_stage
                            Channel.GUILD_TEXT -> R.attr.ic_channel_text
                            else -> 0
                        },
                    ),
                )
                itemView.setOnClickListener { adapter.dialog.onChannelSelected(data.channel) }
            }
        }
    }

    interface FilterFunction : Serializable {
        fun includeChannel(channel: Channel): Boolean = true
    }

    class BaseFilterFunction : FilterFunction

    data class SetFilterFunction(private val channelIds: Set<Long>) : FilterFunction {
        override fun includeChannel(channel: Channel): Boolean = channel.id in channelIds
    }

    data class TypeFilterFunction(private val type: Int) : FilterFunction {
        override fun includeChannel(channel: Channel): Boolean = channel.type == type
    }

    object VocalChannelFilterFunction : FilterFunction {
        override fun includeChannel(channel: Channel): Boolean = channel.isGuildVocalChannel()
    }

    object InactiveStageChannelFilterFunction : FilterFunction {
        override fun includeChannel(channel: Channel): Boolean =
            channel.isStageVoiceChannel() &&
                StoreStream.getStageInstances().getStageInstanceForChannel(channel.id) == null
    }

    object Model {
        data class Item(
            val channel: Channel?,
            override val type: Int = channel?.type ?: -1,
            override val key: String = channel?.id?.toString().orEmpty(),
        ) : MGRecyclerDataPayload

        internal fun buildItems(
            channels: Collection<Channel>,
            includeNoChannel: Boolean,
            filterFunction: FilterFunction,
        ): List<Item> = buildList {
            if (includeNoChannel) add(Item(null))
            channels.asSequence()
                .filter(filterFunction::includeChannel)
                .sortedWith(Channel.getSortByNameAndType())
                .mapTo(this) { Item(it) }
        }

        fun get(
            guildId: Long,
            includeNoChannel: Boolean,
            filterFunction: FilterFunction,
            storeChannels: StoreChannels = StoreStream.getChannels(),
        ): Observable<List<Item>> = storeChannels.observeChannelsForGuild(guildId)
            .map { channels -> buildItems(channels.values, includeNoChannel, filterFunction) }
            .computationLatest().distinctUntilChanged()
    }

    companion object {
        private const val ARG_FILTER_FUNCTION = "INTENT_EXTRA_FILTER_FUNCTION"
        private const val ARG_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val ARG_INCLUDE_NO_CHANNEL = "INTENT_EXTRA_INCLUDE_NO_CHANNEL"
        private const val ARG_NO_CHANNEL_STRING_ID = "INTENT_EXTRA_NO_CHANNEL_STRING_ID"
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val RESULT_EXTRA_CHANNEL_ID = "INTENT_EXTRA_CHANNEL_ID"
        private const val RESULT_EXTRA_CHANNEL_NAME = "INTENT_EXTRA_CHANNEL_NAME"

        fun launch(
            fragment: Fragment,
            guildId: Long,
            requestKey: String,
            includeNoChannel: Boolean,
            noChannelStringId: Int,
            filterFunction: FilterFunction,
        ) {
            WidgetChannelSelector().apply {
                arguments = bundleOf(
                    ARG_REQUEST_KEY to requestKey,
                    ARG_GUILD_ID to guildId,
                    ARG_INCLUDE_NO_CHANNEL to includeNoChannel,
                    ARG_NO_CHANNEL_STRING_ID to noChannelStringId,
                    ARG_FILTER_FUNCTION to filterFunction,
                )
            }.show(fragment.parentFragmentManager, WidgetChannelSelector::class.java.name)
        }

        fun launchForInactiveStages(
            fragment: Fragment,
            guildId: Long,
            requestKey: String,
            includeNoChannel: Boolean = false,
            noChannelStringId: Int = R.string.none,
        ) = launch(
            fragment,
            guildId,
            requestKey,
            includeNoChannel,
            noChannelStringId,
            InactiveStageChannelFilterFunction,
        )

        fun launchForText(
            fragment: Fragment,
            guildId: Long,
            requestKey: String,
            includeNoChannel: Boolean = false,
            noChannelStringId: Int = R.string.none,
        ) = launch(fragment, guildId, requestKey, includeNoChannel, noChannelStringId, TypeFilterFunction(Channel.GUILD_TEXT))

        fun launchForVocal(
            fragment: Fragment,
            guildId: Long,
            requestKey: String,
            includeNoChannel: Boolean = false,
            noChannelStringId: Int = R.string.none,
        ) = launch(fragment, guildId, requestKey, includeNoChannel, noChannelStringId, VocalChannelFilterFunction)

        fun launchForVoice(
            fragment: Fragment,
            guildId: Long,
            requestKey: String,
            includeNoChannel: Boolean = false,
            noChannelStringId: Int = R.string.none,
        ) = launch(fragment, guildId, requestKey, includeNoChannel, noChannelStringId, TypeFilterFunction(Channel.GUILD_VOICE))

        fun registerForResult(
            fragment: Fragment,
            requestKey: String,
            allowNullChannel: Boolean = false,
            onChannelSelected: (Long, String) -> Unit,
        ) {
            fragment.setFragmentResultListener(requestKey) { resultRequestKey, bundle ->
                if (requestKey != resultRequestKey) return@setFragmentResultListener
                val channelId = bundle.getLong(RESULT_EXTRA_CHANNEL_ID, -1L)
                if (allowNullChannel || channelId > 0L) {
                    onChannelSelected(channelId, bundle.getString(RESULT_EXTRA_CHANNEL_NAME).orEmpty())
                }
            }
        }
    }
}
