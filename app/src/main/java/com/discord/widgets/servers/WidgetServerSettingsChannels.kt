package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.view.Menu
import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.getSortByNameAndType
import com.discord.api.channel.isGuildTextyChannel
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.dimmer.DimmerView
import com.discord.utilities.mg_recycler.CategoricalDragAndDropAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.rx.withDimmer
import com.discord.widgets.channels.settings.WidgetTextChannelSettings
import com.discord.widgets.voice.settings.WidgetVoiceChannelSettings
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable
import rx.functions.Func7
import rx.subjects.BehaviorSubject
import java.util.LinkedHashMap

class WidgetServerSettingsChannels : AppFragment(R.layout.widget_server_settings_channels) {
    private val channelSortTypeSubject = BehaviorSubject.create(-1)
    private var adapter: SettingsChannelListAdapter? = null
    private lateinit var dimmer: DimmerView
    private lateinit var createChannel: FloatingActionButton

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        dimmer = view.findViewById(R.id.dimmer_view)
        createChannel = view.findViewById(R.id.server_settings_channels_create_channel)
        createChannel.isVisible = false
        adapter = MGRecyclerAdapter.configure(
            SettingsChannelListAdapter(
                view.findViewById<RecyclerView>(R.id.server_settings_channels_text_recycler),
                isDraggable = true,
            ),
        ).also { channelAdapter ->
            channelAdapter.onClickListener = null
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val guildId = mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, -1L)
        Model.observe(guildId, channelSortTypeSubject)
            .ui(this)
            .appSubscribe(WidgetServerSettingsChannels::class.java) { configureUI(it) }
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            appActivity?.onBackPressedDispatcher?.onBackPressed()
            return
        }
        setActionBarDisplayHomeAsUpEnabled(!model.isSorting)
        setActionBarTitle(if (model.isSorting) R.string.sorting else R.string.channels)
        setActionBarSubtitle(model.guild.name)
        setActionBarOptionsMenu(
            R.menu.menu_channel_sort,
            onSelected = { item ->
                when (item.itemId) {
                    R.id.menu_sort_channel -> WidgetServerSettingsChannelsSortActions.show(childFragmentManager) {
                        channelSortTypeSubject.onNext(it)
                    }
                    R.id.menu_sort_done -> channelSortTypeSubject.onNext(-1)
                }
            },
            onConfigured = { menu: Menu ->
                menu.findItem(R.id.menu_sort_channel).isVisible = !model.isSorting
                menu.findItem(R.id.menu_sort_done).isVisible = model.isSorting
            },
        )
        createChannel.apply {
            if (model.isSorting) hide() else show()
            setOnClickListener {
                WidgetServerSettingsChannelsFabMenuFragment.show(
                    model.guild.id,
                    childFragmentManager,
                ) { if (!model.isSorting) show() }
                hide()
            }
        }
        adapter?.setData(model.items)
        adapter?.setPositionUpdateListener { reorderChannels(model, it) }
        adapter?.onClickListener = if (model.isSorting) {
            {}
        } else {
            { channelId ->
                if (PermissionUtils.can(Permission.MANAGE_CHANNELS, model.channelPermissions[channelId])) {
                    val channel = model.channels[channelId]
                    if (channel?.isVoiceChannel() == true) {
                        WidgetVoiceChannelSettings.launch(channelId, requireContext())
                    } else {
                        WidgetTextChannelSettings.launch(channelId, requireContext())
                    }
                }
            }
        }
    }

    private fun reorderChannels(
        model: Model,
        newPositions: Map<Long, SettingsChannelListAdapter.UpdatedPosition>,
    ) {
        if (newPositions.isEmpty()) return
        RestAPI.api.reorderChannels(
            model.guild.id,
            newPositions.map { (channelId, update) ->
                RestAPIParams.ChannelPosition(channelId, update.position, update.parentId)
            },
        ).restSubscribeOn()
            .withDimmer(dimmer, 0L)
            .ui(this)
            .appSubscribe(
                WidgetServerSettingsChannels::class.java,
                requireContext(),
                errorHandler = { configureUI(model) },
                onNext = {},
                onCompleted = { AppToast.show(this, R.string.saved_settings) },
            )
    }

    internal data class Model(
        val guild: Guild,
        val items: List<CategoricalDragAndDropAdapter.Payload>,
        val isSorting: Boolean,
        val channels: Map<Long, Channel>,
        val channelPermissions: Map<Long, Long>,
    ) {
        companion object {
            fun observe(guildId: Long, channelTypeObservable: Observable<Int>): Observable<Model?> {
                val stores = StoreStream
                return Observable.combineLatest(
                    stores.getUsers().observeMe(),
                    stores.getPermissions().observePermissionsForGuild(guildId),
                    stores.getGuilds().observeGuild(guildId),
                    stores.getChannels().observeChannelsForGuild(guildId),
                    stores.getChannels().observeChannelCategories(guildId),
                    stores.getPermissions().observeChannelPermissionsForGuild(guildId),
                    channelTypeObservable,
                    Func7<
                        MeUser,
                        Long?,
                        Guild?,
                        Map<Long, Channel>,
                        List<Channel>,
                        Map<Long, Long>,
                        Int,
                        Model?
                    > { me, permissions, guild, channels, categories, channelPermissions, channelType ->
                        build(me, permissions, guild, channels, categories, channelPermissions, channelType)
                    },
                ).computationLatest().distinctUntilChanged()
            }

            internal fun build(
                me: MeUser,
                guildPermissions: Long?,
                guild: Guild?,
                guildChannels: Map<Long, Channel>,
                categories: List<Channel>,
                channelPermissions: Map<Long, Long>,
                channelType: Int,
            ): Model? {
                guild ?: return null
                val isSorting = channelType != -1
                val canManageGuildChannels = PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_CHANNELS,
                    guildPermissions,
                    me.mfaEnabled,
                    guild.mfaLevel,
                )
                val sortedChannels = sortedGuildChannels(guildChannels, channelType)
                val grouped = LinkedHashMap<Long, MutableList<CategoricalDragAndDropAdapter.Payload>>()
                val manageableCategories = HashSet<Long>()

                if (!isSorting || canManageGuildChannels) {
                    manageableCategories += 0L
                    grouped[0L] = mutableListOf(
                        SettingsChannelListAdapter.CategoryItem(null, 0L, 0, false, false, true),
                    )
                }
                categories.sortedWith(Channel.getSortByNameAndType()).forEach { category ->
                    if (!PermissionUtils.hasAccess(category, channelPermissions) && category.id != 0L) {
                        return@forEach
                    }
                    val canManageCategory = PermissionUtils.can(
                        Permission.MANAGE_CHANNELS,
                        channelPermissions[category.id],
                    )
                    if (canManageCategory || canManageGuildChannels) manageableCategories += category.id
                    if (!isSorting || canManageCategory) {
                        grouped[category.id] = mutableListOf(
                            SettingsChannelListAdapter.CategoryItem(
                                category.getDisplayName(),
                                category.id,
                                category.position,
                                channelType == Channel.CATEGORY,
                                canManageGuildChannels,
                                canManageCategory,
                            ),
                        )
                    }
                }
                if (manageableCategories.isEmpty()) return null

                val textChannels = ArrayList<SettingsChannelListAdapter.ChannelItem>()
                val voiceChannels = ArrayList<SettingsChannelListAdapter.ChannelItem>()
                sortedChannels.values.forEach { channel ->
                    val canManageParent = channel.parentId in manageableCategories
                    if (isSorting && !canManageParent) return@forEach
                    if (!PermissionUtils.hasAccess(channel, channelPermissions)) return@forEach
                    val item = SettingsChannelListAdapter.ChannelItem(
                        channel,
                        isSorting,
                        channel.parentId,
                        canManageParent,
                    )
                    when {
                        channel.isGuildTextyChannel() -> textChannels += item
                        channel.isVoiceChannel() -> voiceChannels += item
                    }
                }
                (textChannels + voiceChannels).forEach { item -> grouped[item.parentId]?.add(item) }
                return Model(guild, grouped.values.flatten(), isSorting, sortedChannels, channelPermissions)
            }

            internal fun sortedGuildChannels(
                guildChannels: Map<Long, Channel>,
                channelType: Int,
            ): LinkedHashMap<Long, Channel> = guildChannels.values.asSequence()
                .filter { channel ->
                    when (channelType) {
                        -1 -> true
                        Channel.GUILD_TEXT -> channel.isGuildTextyChannel()
                        Channel.GUILD_VOICE -> channel.isVoiceChannel()
                        else -> channel.type == channelType
                    }
                }
                .sortedWith(Channel.getSortByNameAndType())
                .associateTo(LinkedHashMap()) { it.id to it }
        }
    }

    companion object {
        const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"

        fun show(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("CHANNELS", guildId)
            AppScreenRouter.launch(
                context,
                WidgetServerSettingsChannels::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }

        fun launch(context: Context, guildId: Long) = show(context, guildId)
    }
}
