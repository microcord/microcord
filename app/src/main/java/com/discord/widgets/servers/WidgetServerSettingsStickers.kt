package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.text.format.Formatter
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.api.sticker.Sticker
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.views.sticker.StickerView
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.google.android.material.button.MaterialButton
import rx.Observable

/**
 * "Stickers" server settings: the guild's uploaded stickers, how many slots remain, and the entry
 * points to upload, rename and delete them.
 *
 * The list comes from [com.discord.stores.StoreGuildStickers], which the gateway keeps current, so
 * the screen never refetches after a mutation — `GUILD_STICKERS_UPDATE` does that.
 */
class WidgetServerSettingsStickers : AppFragment(R.layout.widget_server_settings_stickers) {

    private var guildId = INVALID_GUILD_ID
    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var adapter: Adapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.guild_settings_stickers)
        guildId = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
        viewFlipper = view.findViewById(R.id.widget_server_settings_stickers_view_flipper)
        adapter = MGRecyclerAdapter.configure(
            Adapter(view.findViewById(R.id.widget_server_settings_stickers_recycler)),
        )
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        observeModel(guildId)
            .ui(this)
            .appSubscribe(WidgetServerSettingsStickers::class.java, context, onNext = ::configureUI)
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            appActivity?.onBackPressed()
            return
        }
        setActionBarSubtitle(model.guild.name)
        viewFlipper.displayedChild = VIEW_INDEX_CONTENT
        adapter.onUploadSticker = {
            WidgetServerSettingsStickerEdit.launch(requireContext(), guildId, null)
        }
        adapter.onStickerClicked = { sticker ->
            WidgetServerSettingsStickerEdit.launch(requireContext(), guildId, sticker.id)
        }
        adapter.setData(model.items)
    }

    sealed class Item : MGRecyclerDataPayload {
        data class Header(val used: Int, val max: Int) : Item() {
            override val type = TYPE_HEADER
            override val key = "STICKER_HEADER"
        }

        data class StickerItem(val sticker: Sticker) : Item() {
            override val type = TYPE_STICKER
            override val key = sticker.id.toString()
        }

        object Empty : Item() {
            override val type = TYPE_EMPTY
            override val key = "STICKER_EMPTY"
        }

        companion object {
            const val TYPE_STICKER = 0
            const val TYPE_HEADER = 1
            const val TYPE_EMPTY = 2
        }
    }

    class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<Item>(recycler) {
        var onStickerClicked: (Sticker) -> Unit = {}
        var onUploadSticker: () -> Unit = {}

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<Adapter, Item> =
            when (viewType) {
                Item.TYPE_STICKER -> StickerItemViewHolder(this)
                Item.TYPE_HEADER -> HeaderViewHolder(this)
                Item.TYPE_EMPTY -> EmptyViewHolder(this)
                else -> throw invalidViewTypeException(viewType)
            }

        private class EmptyViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_stickers_empty, adapter)

        private class HeaderViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_stickers_header, adapter) {
            private val requirements: TextView =
                itemView.findViewById(R.id.widget_server_settings_stickers_requirements)
            private val upload: MaterialButton =
                itemView.findViewById(R.id.widget_server_settings_stickers_upload)
            private val slots: TextView = itemView.findViewById(R.id.widget_server_settings_stickers_slots)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val header = data as Item.Header
                val remaining = (header.max - header.used).coerceAtLeast(0)
                requirements.text = SettingsStrings.render(
                    itemView.context,
                    R.string.guild_sticker_upload_description_no_lottie,
                    "fileSize" to Formatter.formatFileSize(itemView.context, MAX_STICKER_FILE_SIZE),
                )
                slots.text = SettingsStrings.render(
                    itemView.context,
                    R.string.guild_sticker_settings_remaining_slots_available,
                    "numAvailable" to remaining,
                    "numTotal" to header.max,
                )
                upload.isEnabled = remaining > 0
                upload.setText(
                    if (remaining > 0) R.string.guild_settings_action_filter_sticker_create
                    else R.string.guild_sticker_no_slots_available,
                )
                upload.setOnClickListener { adapter.onUploadSticker() }
            }
        }

        private class StickerItemViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_stickers_item, adapter) {
            private val container: View = itemView.findViewById(R.id.server_settings_stickers_container)
            private val preview: StickerView = itemView.findViewById(R.id.server_settings_stickers_preview)
            private val name: TextView = itemView.findViewById(R.id.server_settings_stickers_name)
            private val description: TextView =
                itemView.findViewById(R.id.server_settings_stickers_description)
            private val overflow: ImageView = itemView.findViewById(R.id.server_settings_stickers_overflow)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val sticker = (data as Item.StickerItem).sticker
                name.text = sticker.name
                description.text = sticker.description
                preview.d(sticker)
                container.setOnClickListener { adapter.onStickerClicked(sticker) }
                overflow.isClickable = false
            }
        }
    }

    data class Model(val guild: Guild, val items: List<Item>)

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val VIEW_INDEX_CONTENT = 1

        /** The API rejects anything larger, whatever the on-screen copy says about formats. */
        internal const val MAX_STICKER_FILE_SIZE = 512L * 1024

        private fun observeModel(guildId: Long): Observable<Model?> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getGuildStickers().observeGuildStickers(),
        ) { guild: Guild?, permissions: Long?, me: MeUser, stickers: Map<Long, Map<Long, Sticker>> ->
            if (guild == null || !canManage(guild, permissions, me)) return@combineLatest null
            val owned = stickers[guildId]?.values.orEmpty().sortedBy(Sticker::name)
            val max = PremiumUtils.getGuildStickerMaxCount(guild)
            val items = mutableListOf<Item>(Item.Header(owned.size, max))
            if (owned.isEmpty()) items += Item.Empty else items += owned.map(Item::StickerItem)
            Model(guild, items)
        }.computationLatest()

        private fun canManage(guild: Guild, permissions: Long?, me: MeUser) =
            listOf(Permission.MANAGE_GUILD_EXPRESSIONS, Permission.CREATE_GUILD_EXPRESSIONS).any {
                PermissionUtils.canAndIsElevated(it, permissions, me.mfaEnabled, guild.mfaLevel)
            }

        fun newInstance(guildId: Long) = WidgetServerSettingsStickers().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("STICKERS", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsStickers::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
