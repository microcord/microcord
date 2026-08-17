package com.discord.widgets.guilds

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.setFragmentResult
import androidx.fragment.app.setFragmentResultListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.models.guild.Guild
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.views.VitoImageView
import java.io.Serializable
import rx.Observable
import rx.subscriptions.CompositeSubscription

class WidgetGuildSelector : AppBottomSheet() {

    fun interface FilterFunction : Serializable {
        fun includeGuild(guild: Guild): Boolean
    }

    class BaseFilterFunction : FilterFunction {
        override fun includeGuild(guild: Guild) = true
    }

    data class GuildFilterFunction(val guildIds: Set<Long>) : FilterFunction {
        override fun includeGuild(guild: Guild) = guild.id in guildIds
    }

    private lateinit var adapter: Adapter
    private val requestKey get() = argumentsOrDefault.getString(ARG_REQUEST_KEY, REQUEST_KEY_DEFAULT).orEmpty()

    override fun getContentViewResId() = R.layout.widget_guild_selector

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = Adapter(argumentsOrDefault.getInt(ARG_NO_GUILD_STRING_ID), ::onGuildSelected)
        view.findViewById<RecyclerView>(R.id.guild_selector_list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetGuildSelector.adapter
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val filter = argumentsOrDefault.getSerializable(ARG_FILTER_FUNCTION) as? FilterFunction
            ?: BaseFilterFunction()
        compositeSubscription.add(
            get(argumentsOrDefault.getBoolean(ARG_INCLUDE_NO_GUILD), filter)
                .ui(this)
                .subscribe(adapter::setData),
        )
    }

    private fun get(includeNoGuild: Boolean, filter: FilterFunction): Observable<List<Guild?>> =
        StoreStream.getGuildsSorted().observeOrderedGuilds()
            .map { guilds ->
                buildList {
                    if (includeNoGuild) add(null)
                    addAll(guilds.values.filter(filter::includeGuild))
                }
            }
            .computationLatest()
            .distinctUntilChanged()

    private fun onGuildSelected(guild: Guild?) {
        setFragmentResult(requestKey, Bundle().apply {
            putLong(RESULT_EXTRA_GUILD_ID, guild?.id ?: -1L)
            putString(RESULT_EXTRA_GUILD_NAME, guild?.name)
        })
        dismiss()
    }

    private class Adapter(
        private val noGuildStringId: Int,
        private val onSelected: (Guild?) -> Unit,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        private var guilds: List<Guild?> = emptyList()

        fun setData(data: List<Guild?>) {
            guilds = data
            notifyDataSetChanged()
        }

        override fun getItemCount() = guilds.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.widget_guild_selector_item, parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(guilds[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val icon: VitoImageView = view.findViewById(R.id.item_icon)
            private val acronym: TextView = view.findViewById(R.id.item_text)
            private val name: TextView = view.findViewById(R.id.item_name)

            fun bind(guild: Guild?) {
                itemView.setOnClickListener { onSelected(guild) }
                name.text = guild?.name ?: itemView.context.getString(noGuildStringId)
                if (guild == null) {
                    icon.setImageURI(null as String?)
                    acronym.text = null
                } else {
                    IconUtils.setIcon(icon, guild)
                    acronym.text = guild.shortName.takeIf { guild.icon.isNullOrEmpty() }
                }
            }
        }
    }

    companion object {
        private const val ARG_FILTER_FUNCTION = "INTENT_EXTRA_FILTER_FUNCTION"
        private const val ARG_INCLUDE_NO_GUILD = "INTENT_EXTRA_INCLUDE_NO_GUILD"
        private const val ARG_NO_GUILD_STRING_ID = "INTENT_EXTRA_NO_GUILD_STRING_ID"
        private const val ARG_REQUEST_KEY = "INTENT_EXTRA_REQUEST_CODE"
        private const val REQUEST_KEY_DEFAULT = "GUILD_SELECTOR_DEFAULT_REQUEST_KEY"
        private const val RESULT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val RESULT_EXTRA_GUILD_NAME = "INTENT_EXTRA_GUILD_NAME"

        fun launch(
            fragment: Fragment,
            requestKey: String = REQUEST_KEY_DEFAULT,
            includeNoGuild: Boolean = false,
            noGuildStringId: Int = R.string.none,
            filterFunction: FilterFunction? = null,
        ) = WidgetGuildSelector().apply {
            arguments = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(ARG_INCLUDE_NO_GUILD, includeNoGuild)
                putInt(ARG_NO_GUILD_STRING_ID, noGuildStringId)
                putSerializable(ARG_FILTER_FUNCTION, filterFunction)
            }
        }.show(fragment.parentFragmentManager, WidgetGuildSelector::class.java.name)

        fun registerForResult(
            fragment: Fragment,
            requestKey: String = REQUEST_KEY_DEFAULT,
            allowNullGuild: Boolean = false,
            onGuildSelected: (Long, String) -> Unit,
        ) = fragment.setFragmentResultListener(requestKey) { key, bundle ->
            if (key != requestKey) return@setFragmentResultListener
            val guildId = bundle.getLong(RESULT_EXTRA_GUILD_ID, -1L)
            if (guildId > 0L || allowNullGuild) {
                onGuildSelected(guildId, bundle.getString(RESULT_EXTRA_GUILD_NAME).orEmpty())
            }
        }
    }
}
