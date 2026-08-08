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
import com.discord.restapi.ChannelReorderDiagnostics
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
import com.discord.dialogs.SimpleConfirmationDialog
import com.discord.dialogs.SimpleConfirmationDialogArgs
import com.discord.widgets.channels.settings.WidgetTextChannelSettings
import com.discord.widgets.settings.SettingsStrings
import com.discord.widgets.voice.settings.WidgetVoiceChannelSettings
import com.google.android.material.floatingactionbutton.FloatingActionButton
import rx.Observable
import rx.functions.Func7
import rx.subjects.BehaviorSubject
import java.util.LinkedHashMap

internal fun submitChannelReorder(
    guildId: Long,
    newPositions: Map<Long, SettingsChannelListAdapter.UpdatedPosition>,
    lockPermissionsFor: Long? = null,
): Observable<Void?> = RestAPI.apiSerializeNulls.reorderChannels(
    guildId,
    newPositions.map { (channelId, update) ->
        RestAPIParams.ChannelPosition(
            channelId,
            update.position,
            update.parentId,
            lockPermissions = true.takeIf { channelId == lockPermissionsFor },
        )
    },
).doOnNext { ChannelReorderDiagnostics.recordSuccess() }
    .doOnError(ChannelReorderDiagnostics::recordFailure)

class WidgetServerSettingsChannels : AppFragment(R.layout.widget_server_settings_channels) {
    private val channelSortTypeSubject = BehaviorSubject.create(-1)
    private val orderWrites = ChannelOrderWriteState()
    private var adapter: SettingsChannelListAdapter? = null
    private var latestServerModel: Model? = null
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
        latestServerModel = model
        val displayedModel = model.withChannels(orderWrites.apply(model.allChannels))
        setActionBarDisplayHomeAsUpEnabled(!displayedModel.isSorting)
        setActionBarTitle(if (displayedModel.isSorting) R.string.sorting else R.string.channels)
        setActionBarSubtitle(displayedModel.guild.name)
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
                menu.findItem(R.id.menu_sort_channel).isVisible = !displayedModel.isSorting
                menu.findItem(R.id.menu_sort_done).isVisible = displayedModel.isSorting
            },
        )
        createChannel.apply {
            if (displayedModel.isSorting) hide() else show()
            setOnClickListener {
                WidgetServerSettingsChannelsFabMenuFragment.show(
                    displayedModel.guild.id,
                    childFragmentManager,
                ) { if (!displayedModel.isSorting) show() }
                hide()
            }
        }
        adapter?.setData(displayedModel.items)
        adapter?.setPositionUpdateListener { reorderChannels(displayedModel, it) }
        adapter?.onClickListener = if (displayedModel.isSorting) {
            {}
        } else {
            { channelId ->
                if (PermissionUtils.can(Permission.MANAGE_CHANNELS, displayedModel.channelPermissions[channelId])) {
                    val channel = displayedModel.channels[channelId]
                    if (channel?.isVoiceChannel() == true) {
                        WidgetVoiceChannelSettings.launch(channelId, requireContext())
                    } else {
                        WidgetTextChannelSettings.launch(channelId, requireContext())
                    }
                }
            }
        }
    }

    /**
     * A channel dragged into a category is offered its overwrites; anything else commits straight
     * away. Only the moved channel gets `lock_permissions`, never the rest of the reshuffle.
     */
    private fun reorderChannels(
        model: Model,
        newPositions: Map<Long, SettingsChannelListAdapter.UpdatedPosition>,
    ) {
        val entered = newPositions.entries.firstOrNull { (channelId, update) ->
            val parentId = update.parentId?.takeIf { it != RestAPIParams.ChannelPosition.PARENT_ID_NONE }
            parentId != null && model.channels[channelId]?.parentId != parentId
        }
        if (entered == null) {
            submitReorder(model, newPositions, null)
            return
        }
        val category = model.allChannels[entered.value.parentId]
        SimpleConfirmationDialog.show(
            parentFragmentManager,
            SimpleConfirmationDialogArgs(
                title = getString(R.string.confirm_channel_drag_title),
                description = SettingsStrings.render(
                    requireContext(),
                    R.string.sync_permissions_explanation,
                    "channelName" to model.channels[entered.key]?.name.orEmpty(),
                    "categoryName" to category?.name.orEmpty(),
                ).toString(),
                positiveButtonText = getString(R.string.sync_permissions),
                negativeButtonText = getString(R.string.keep_permissions),
            ),
            negativeClickListener = { submitReorder(model, newPositions, null) },
            positiveClickListener = { submitReorder(model, newPositions, entered.key) },
        )
    }

    private fun submitReorder(
        model: Model,
        newPositions: Map<Long, SettingsChannelListAdapter.UpdatedPosition>,
        lockPermissionsFor: Long?,
    ) {
        if (newPositions.isEmpty()) return
        val updates = newPositions.mapValues { (_, update) ->
            GuildChannelsSort.PositionUpdate(update.position, update.parentId)
        }
        val serverChannels = latestServerModel?.allChannels ?: model.allChannels
        val revision = orderWrites.begin(orderWrites.apply(serverChannels), updates)
        submitChannelReorder(model.guild.id, newPositions, lockPermissionsFor)
            .restSubscribeOn()
            .withDimmer(dimmer, 0L)
            .ui(this)
            .appSubscribe(
                WidgetServerSettingsChannels::class.java,
                requireContext(),
                errorHandler = { error ->
                    if (orderWrites.failure(revision)) latestServerModel?.let(::configureUI)
                },
                onNext = {
                    orderWrites.success(revision)
                    AppToast.show(this, R.string.channel_order_updated)
                },
            )
    }

    internal data class Model(
        val guild: Guild,
        val items: List<CategoricalDragAndDropAdapter.Payload>,
        val isSorting: Boolean,
        val channels: Map<Long, Channel>,
        val channelPermissions: Map<Long, Long>,
        val allChannels: Map<Long, Channel>,
    ) {
        fun withChannels(updatedChannels: Map<Long, Channel>): Model {
            if (updatedChannels === allChannels) return this
            val updatedItems = items.map { item ->
                when (item) {
                    is SettingsChannelListAdapter.CategoryItem -> updatedChannels[item.id]
                        ?.let { item.copy(pos = it.position) }
                        ?: item
                    is SettingsChannelListAdapter.ChannelItem -> updatedChannels[item.channel.id]
                        ?.let { item.copy(channel = it, parentId = it.parentId) }
                        ?: item
                    else -> item
                }
            }
            return copy(
                items = GuildChannelsSort.sortedRows(updatedItems),
                channels = channels.mapValues { (id, channel) -> updatedChannels[id] ?: channel },
                allChannels = updatedChannels,
            )
        }

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
                return Model(
                    guild,
                    grouped.values.flatten(),
                    isSorting,
                    sortedChannels,
                    channelPermissions,
                    guildChannels + categories.associateBy(Channel::id),
                )
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
