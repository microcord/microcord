package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission as DiscordPermission
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.dialogs.ImageUploadDialog
import com.discord.i18n.i18nPlural
import com.discord.media_picker.MediaPicker
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.MeUser
import com.discord.models.user.User
import com.discord.restapi.RestAPIParams
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.extensions.setGrayscale
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable

class WidgetServerSettingsEmojis : AppFragment(R.layout.widget_server_settings_emojis) {

    private var guildId = INVALID_GUILD_ID
    private lateinit var viewFlipper: AppViewFlipper
    private lateinit var adapter: Adapter
    private var uploadEmojiAction: ((String) -> Unit)? = null
    private val mediaPickerProvider by lazy {
        object : MediaPicker.Provider {
            override fun requireContext() = this@WidgetServerSettingsEmojis.requireContext()
            override fun getImageFile() = imageFile
            override fun startActivityForResult(intent: Intent, requestCode: Int) =
                this@WidgetServerSettingsEmojis.startActivityForResult(intent, requestCode)
        }
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        guildId = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
        viewFlipper = view.findViewById(R.id.widget_server_settings_emojis_view_flipper)
        adapter = MGRecyclerAdapter.configure(Adapter(view.findViewById(R.id.widget_server_settings_emojis_recycler)))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        StoreStream.getGuildEmojis().activate(guildId)
        Model.get(guildId)
            .ui(this)
            .appSubscribe(WidgetServerSettingsEmojis::class.java, context, onNext = ::configureUI)
    }

    override fun onPause() {
        super.onPause()
        StoreStream.getGuildEmojis().deactivate()
    }

    private fun configureUI(model: Model?) {
        if (model == null) {
            appActivity?.onBackPressed()
            return
        }
        setActionBarTitle(R.string.emoji)
        setActionBarSubtitle(model.guild.name)
        val items = model.items
        val defaultName = model.defaultName
        if (items.isNullOrEmpty() || defaultName.isNullOrBlank()) return
        viewFlipper.displayedChild = VIEW_INDEX_CONTENT
        uploadEmojiAction = { dataUrl -> uploadEmoji(defaultName, dataUrl) }
        adapter.setData(items)
        adapter.onUploadEmoji = ::openMediaChooser
        adapter.onEmojiItemClicked = { view, emoji ->
            WidgetServerSettingsEmojisEdit.launch(view.context, guildId, emoji.id, emoji.name)
        }
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        val upload = uploadEmojiAction ?: return
        ImageUploadDialog.show(
            parentFragmentManager,
            uri,
            mediaPickerProvider,
            mimeType,
            upload,
            ImageUploadDialog.PreviewType.EMOJI,
        )
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, uploadEmojiAction)
    }

    private fun uploadEmoji(name: String, imageBase64: String) {
        val api = RestAPI.apiOrNull ?: return
        api.postGuildEmoji(guildId, RestAPIParams.PostGuildEmoji(name, imageBase64))
            .restSubscribeOn()
            .ui(this)
            .appSubscribe(WidgetServerSettingsEmojis::class.java, context) {
                StoreStream.getGuildEmojis().fetchGuildEmoji(guildId)
            }
    }

    sealed class Item : MGRecyclerDataPayload {
        data class EmojiHeader(val emojiMax: Int) : Item() {
            override val type = TYPE_HEADER
            override val key = "EMOJI_HEADER"
        }

        data class EmojiSection(
            val emojiMax: Int,
            val emojiLength: Int,
            val titleId: Int,
        ) : Item() {
            override val type = TYPE_SECTION
            override val key = titleId.toString()
        }

        data class EmojiItem(
            val emoji: ModelEmojiGuild,
            val user: User,
            val guildMember: GuildMember?,
        ) : Item() {
            override val type = TYPE_EMOJI
            override val key = emoji.id.toString()

            companion object {
                fun from(emoji: ModelEmojiGuild, members: Map<Long, GuildMember>): EmojiItem {
                    val user = CoreUser(emoji.user)
                    return EmojiItem(emoji, user, members[user.id])
                }
            }
        }

        object EmojiEmpty : Item() {
            override val type = TYPE_EMPTY
            override val key = "EMOJI_EMPTY"
        }

        companion object {
            const val TYPE_EMOJI = 0
            const val TYPE_SECTION = 1
            const val TYPE_HEADER = 2
            const val TYPE_EMPTY = 3
        }
    }

    class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<Item>(recycler) {
        var onEmojiItemClicked: (View, ModelEmojiGuild) -> Unit = { _, _ -> }
        var onUploadEmoji: () -> Unit = {}

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<Adapter, Item> =
            when (viewType) {
                Item.TYPE_EMOJI -> EmojiItemViewHolder(this)
                Item.TYPE_SECTION -> EmojiSectionViewHolder(this)
                Item.TYPE_HEADER -> EmojiHeaderViewHolder(this)
                Item.TYPE_EMPTY -> EmojiEmptyViewHolder(this)
                else -> throw invalidViewTypeException(viewType)
            }

        private class EmojiEmptyViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_emojis_empty, adapter)

        private class EmojiHeaderViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_emojis_header, adapter) {
            private val upload: MaterialButton = itemView.findViewById(R.id.widget_server_settings_emojis_upload)
            private val description: TextView =
                itemView.findViewById(R.id.widget_server_settings_emojis_upload_description)

            override fun onConfigure(position: Int, data: Item) {
                val header = data as Item.EmojiHeader
                description.text = SettingsStrings.render(
                    itemView.context,
                    R.string.guild_settings_emoji_upload_to_server_message,
                    "count" to header.emojiMax,
                    "maxSize" to EMOJI_MAX_FILESIZE_KB,
                )
                upload.setOnClickListener { upload.post { adapter.onUploadEmoji() } }
            }
        }

        private class EmojiSectionViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_emojis_section, adapter) {
            private val section: TextView = itemView.findViewById(R.id.widget_server_settings_emojis_section)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val item = data as Item.EmojiSection
                val remaining = item.emojiMax - item.emojiLength
                val available = SettingsStrings.render(
                    itemView.context,
                    R.string.emoji_slots_available,
                    "count" to itemView.context.i18nPlural(
                        R.plurals.emoji_slots_available_count,
                        remaining,
                        "count" to remaining,
                    ),
                )
                section.text = itemView.context.getString(
                    R.string.emoji_section,
                    itemView.context.getString(item.titleId),
                    available,
                )
            }
        }

        private class EmojiItemViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_emojis_item, adapter) {
            private val container: View = itemView.findViewById(R.id.server_settings_emojis_container)
            private val emojiImage: SimpleDraweeView = itemView.findViewById(R.id.server_settings_emojis_avatar)
            private val name: TextView = itemView.findViewById(R.id.server_settings_emojis_name)
            private val nickname: TextView = itemView.findViewById(R.id.server_settings_emojis_nickname)
            private val username: TextView = itemView.findViewById(R.id.server_settings_emojis_username)
            private val uploaderAvatar: SimpleDraweeView =
                itemView.findViewById(R.id.server_settings_emojis_username_avatar)
            private val overflow: ImageView = itemView.findViewById(R.id.server_settings_emojis_overflow)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val item = data as Item.EmojiItem
                name.text = item.emoji.name
                username.text = item.user.getUserNameWithDiscriminator()
                nickname.setTextAndVisibilityBy(item.guildMember?.nick)
                IconUtils.setIcon(
                    uploaderAvatar,
                    item.user,
                    R.dimen.avatar_size_standard,
                    guildMember = item.guildMember,
                )
                MGImages.setImage(
                    emojiImage,
                    ModelEmojiCustom.getImageUri(item.emoji.id, item.emoji.animated, EMOJI_IMAGE_SIZE),
                    R.dimen.emoji_size,
                    R.dimen.emoji_size,
                    useSmallCache = true,
                )
                emojiImage.setGrayscale(!item.emoji.available)
                emojiImage.imageAlpha = if (item.emoji.available) 255 else 100
                container.setOnClickListener { adapter.onEmojiItemClicked(it, item.emoji) }
                overflow.isClickable = false
            }
        }
    }

    private data class Model(
        val guild: Guild,
        val items: List<Item>?,
        val defaultName: String?,
    ) {
        data class Permission(val canManage: Boolean, val guild: Guild)

        companion object {
            fun get(guildId: Long): Observable<Model?> = canManageEmojisAndStickers(guildId)
                .flatMap { permission ->
                    if (permission?.canManage == true) getGuildEmojis(permission.guild)
                    else Observable.just(null as Model?)
                }

            private fun canManageEmojisAndStickers(guildId: Long): Observable<Permission?> =
                Observable.combineLatest(
                    StoreStream.getPermissions().observePermissionsForGuild(guildId),
                    StoreStream.getGuilds().observeGuild(guildId),
                    StoreStream.getUsers().observeMe(),
                ) { permissions: Long?, guild: Guild?, meUser: MeUser ->
                    if (guild == null || permissions == null) null else Permission(
                        PermissionUtils.canAndIsElevated(
                            DiscordPermission.MANAGE_EMOJIS_AND_STICKERS,
                            permissions,
                            meUser.mfaEnabled,
                            guild.mfaLevel,
                        ),
                        guild,
                    )
                }.computationLatest()

            private fun getGuildEmojis(guild: Guild): Observable<Model?> = Observable.combineLatest(
                StoreStream.getGuildEmojis().observeGuildEmoji(guild.id),
                StoreStream.getGuilds().observeGuildMembers(guild.id),
            ) { emojis: List<ModelEmojiGuild>?, members: Map<Long, GuildMember> ->
                create(guild, emojis, members)
            }.doOnSubscribe { StoreStream.getGuildEmojis().fetchGuildEmoji(guild.id) }
                .computationLatest()

            private fun create(
                guild: Guild,
                emojis: List<ModelEmojiGuild>?,
                members: Map<Long, GuildMember>,
            ): Model {
                if (emojis == null) return Model(guild, null, null)
                val emojiMax = PremiumUtils.getGuildEmojiMaxCount(guild)
                val static = emojis.filterNot(ModelEmojiGuild::animated)
                    .map { Item.EmojiItem.from(it, members) }
                    .reversed()
                val animated = emojis.filter(ModelEmojiGuild::animated)
                    .map { Item.EmojiItem.from(it, members) }
                    .reversed()
                val items = mutableListOf<Item>(Item.EmojiHeader(emojiMax))
                if (static.isNotEmpty()) {
                    items += Item.EmojiSection(emojiMax, static.size, R.string.emoji)
                    items += static
                }
                if (animated.isNotEmpty()) {
                    items += Item.EmojiSection(emojiMax, animated.size, R.string.animated_emoji)
                    items += animated
                }
                if (static.isEmpty() && animated.isEmpty()) items += Item.EmojiEmpty
                return Model(guild, items, "emoji_${emojis.size + 1}")
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L
        private const val EMOJI_MAX_FILESIZE_KB = 256
        private const val EMOJI_IMAGE_SIZE = 64
        private const val VIEW_INDEX_CONTENT = 1

        fun newInstance(guildId: Long) = WidgetServerSettingsEmojis().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun create(context: Context, guildId: Long) = launch(context, guildId)

        fun launch(context: Context, guildId: Long) {
            StoreStream.getAnalytics().onGuildSettingsPaneViewed("EMOJIS", guildId)
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEmojis::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}
