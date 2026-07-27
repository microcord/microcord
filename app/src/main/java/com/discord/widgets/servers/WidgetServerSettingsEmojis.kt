package com.discord.widgets.servers

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.permission.Permission
import com.discord.app.AppFragment
import com.discord.app.AppViewFlipper
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.i18n.i18nPlural
import com.discord.models.domain.emoji.ModelEmojiCustom
import com.discord.models.domain.emoji.ModelEmojiGuild
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.user.CoreUser
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.premium.PremiumUtils
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.extensions.setGrayscale
import com.discord.utilities.view.extensions.setTextAndVisibilityBy
import com.discord.widgets.settings.SettingsNavigation
import com.discord.widgets.settings.SettingsStrings
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import rx.Observable

/**
 * The guild's custom emoji, grouped into static and animated sections.
 *
 * Reference: `com.discord.widgets.servers.WidgetServerSettingsEmojis`. The list comes from
 * [com.discord.stores.StoreEmojiGuild], which keeps emoji for **one guild at a time** — the one the
 * screen has `activate`d — because only `GET /guilds/{id}/emojis` returns the uploader that each row
 * displays. Missing the `activate`/`deactivate` pair leaves the list permanently stale, so the
 * screen drives both.
 *
 * Behaviours reproduced deliberately:
 * - **Each section is newest-first.** The reference reverses the static and animated lists
 *   independently, so a freshly uploaded emoji appears at the top of its own section.
 * - **A guild with no emoji at all still shows the header.** The empty state is a *row* in the list,
 *   not a separate flipper page, so the "Upload Emoji" button stays reachable.
 * - **Unavailable emoji are greyed out, not hidden.** When a guild loses boost tiers its extra emoji
 *   stop working but remain listed; the row renders them desaturated and dimmed.
 *
 * ### Deliberate degradations
 * - **Upload is unavailable.** It needs `MGImages.prepareImageUpload` / `requestDataUrl` and
 *   `com.discord.dialogs.ImageUploadDialog`, neither of which this project has reconstructed
 *   (`docs/parity-audit/followups.md`). The button is disabled rather than opening a picker that
 *   could never finish. Listing, renaming and deleting are complete.
 */
class WidgetServerSettingsEmojis : AppFragment(R.layout.widget_server_settings_emojis) {

    private val guildId: Long
        get() = arguments?.getLong(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)
            ?.takeIf { it != INVALID_GUILD_ID }
            ?: mostRecentIntent.getLongExtra(INTENT_EXTRA_GUILD_ID, INVALID_GUILD_ID)

    private val viewModel: ServerSettingsEmojisViewModel by appViewModels {
        ServerSettingsEmojisViewModel(guildId)
    }

    private lateinit var viewFlipper: AppViewFlipper
    private var adapter: Adapter? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.emoji)
        viewFlipper = view.findViewById(R.id.widget_server_settings_emojis_view_flipper)
        val recycler = view.findViewById<RecyclerView>(R.id.widget_server_settings_emojis_recycler)
        adapter = MGRecyclerAdapter.configure<Item, Adapter>(Adapter(recycler))
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Declares this guild as the one the store should keep fetched, then asks for it once.
        StoreStream.getGuildEmojis().activate(guildId)
        StoreStream.getGuildEmojis().fetchGuildEmoji(guildId)
        viewModel.observeViewState().ui(this).subscribe(::configureUI)
    }

    override fun onPause() {
        super.onPause()
        StoreStream.getGuildEmojis().deactivate()
    }

    private fun configureUI(viewState: ServerSettingsEmojisViewModel.ViewState) {
        when (viewState) {
            is ServerSettingsEmojisViewModel.ViewState.Uninitialized ->
                viewFlipper.displayedChild = VIEW_INDEX_LOADING

            // The reference has no read-only mode: without Manage Emojis the screen closes.
            is ServerSettingsEmojisViewModel.ViewState.Unauthorized -> appActivity?.onBackPressed()

            is ServerSettingsEmojisViewModel.ViewState.Loading ->
                viewFlipper.displayedChild = VIEW_INDEX_LOADING

            is ServerSettingsEmojisViewModel.ViewState.Loaded -> {
                setActionBarSubtitle(viewState.guildName)
                viewFlipper.displayedChild = VIEW_INDEX_CONTENT
                adapter?.onEmojiItemClicked = { view, emoji ->
                    WidgetServerSettingsEmojisEdit.launch(
                        view.context,
                        viewState.guildId,
                        emoji.id,
                        emoji.name,
                    )
                }
                adapter?.setData(viewState.items)
            }
        }
    }

    /** One row of the list. The list mixes four kinds, so the type is a sealed hierarchy. */
    sealed class Item : MGRecyclerDataPayload {

        /** The upload button and the "you may add N emoji" explanation. */
        data class EmojiHeader(val emojiMax: Int) : Item() {
            override val type: Int get() = Item.TYPE_HEADER
            override val key: String get() = "EMOJI_HEADER"
        }

        /**
         * A section title plus its remaining-slot count.
         *
         * [emojiLength] is the number of emoji *in this section*, while [emojiMax] is the guild's
         * whole allowance — the difference is what the header reports as available.
         */
        data class EmojiSection(
            val emojiMax: Int,
            val emojiLength: Int,
            val titleId: Int,
        ) : Item() {
            override val type: Int get() = Item.TYPE_SECTION
            override val key: String get() = "EMOJI_SECTION_$titleId"
        }

        data class EmojiItem(
            val emoji: ModelEmojiGuild,
            val user: User,
            val guildMember: GuildMember?,
        ) : Item() {
            override val type: Int get() = Item.TYPE_EMOJI
            override val key: String get() = emoji.id.toString()

            companion object {
                fun from(emoji: ModelEmojiGuild, members: Map<Long, GuildMember>): EmojiItem {
                    val user = CoreUser(emoji.user)
                    return EmojiItem(emoji, user, members[user.id])
                }
            }
        }

        /** Shown in place of both sections when the guild has no emoji at all. */
        object EmojiEmpty : Item() {
            override val type: Int get() = Item.TYPE_EMPTY
            override val key: String get() = "EMOJI_EMPTY"
        }

        companion object {
            const val TYPE_EMOJI = 0
            const val TYPE_SECTION = 1
            const val TYPE_HEADER = 2
            const val TYPE_EMPTY = 3
        }
    }

    class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<Item>(recycler) {

        var onEmojiItemClicked: ((View, ModelEmojiGuild) -> Unit)? = null

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MGRecyclerViewHolder<Adapter, Item> = when (viewType) {
            Item.TYPE_EMOJI -> EmojiItemViewHolder(this)
            Item.TYPE_SECTION -> EmojiSectionViewHolder(this)
            Item.TYPE_HEADER -> EmojiHeaderViewHolder(this)
            Item.TYPE_EMPTY -> EmojiEmptyViewHolder(this)
            else -> throw invalidViewTypeException(viewType)
        }

        /** Static content only; it exists so the empty state can be a list row. */
        private class EmojiEmptyViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(R.layout.widget_server_settings_emojis_empty, adapter)

        private class EmojiHeaderViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(
                R.layout.widget_server_settings_emojis_header,
                adapter,
            ) {

            private val upload: MaterialButton =
                itemView.findViewById(R.id.widget_server_settings_emojis_upload)
            private val description: TextView =
                itemView.findViewById(R.id.widget_server_settings_emojis_upload_description)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val header = data as Item.EmojiHeader
                description.text = SettingsStrings.render(
                    itemView.context,
                    R.string.guild_settings_emoji_upload_to_server_message,
                    "count" to header.emojiMax,
                    "maxSize" to EMOJI_MAX_FILESIZE_KB,
                )
                // No upload path exists yet — see the screen's KDoc. Disabled rather than opening a
                // picker whose result could not be turned into a data URL.
                upload.isEnabled = false
                upload.setOnClickListener(null)
            }
        }

        private class EmojiSectionViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(
                R.layout.widget_server_settings_emojis_section,
                adapter,
            ) {

            private val section: TextView =
                itemView.findViewById(R.id.widget_server_settings_emojis_section)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val model = data as Item.EmojiSection
                val context = itemView.context
                val remaining = model.emojiMax - model.emojiLength
                val available = SettingsStrings.render(
                    context,
                    R.string.emoji_slots_available,
                    "count" to context.i18nPlural(
                        R.plurals.emoji_slots_available_count,
                        remaining,
                        "count" to remaining,
                    ),
                )
                // `emoji_section` is one of the few positional format strings in the catalogue
                // (`%1$s - %2$s`), so it goes through getString rather than the {name} renderer.
                section.text =
                    context.getString(R.string.emoji_section, context.getString(model.titleId), available)
            }
        }

        private class EmojiItemViewHolder(adapter: Adapter) :
            MGRecyclerViewHolder<Adapter, Item>(
                R.layout.widget_server_settings_emojis_item,
                adapter,
            ) {

            private val container: View = itemView.findViewById(R.id.server_settings_emojis_container)
            private val emojiImage: SimpleDraweeView =
                itemView.findViewById(R.id.server_settings_emojis_avatar)
            private val name: TextView = itemView.findViewById(R.id.server_settings_emojis_name)
            private val nickname: TextView =
                itemView.findViewById(R.id.server_settings_emojis_nickname)
            private val username: TextView =
                itemView.findViewById(R.id.server_settings_emojis_username)
            private val uploaderAvatar: SimpleDraweeView =
                itemView.findViewById(R.id.server_settings_emojis_username_avatar)
            private val overflow: ImageView =
                itemView.findViewById(R.id.server_settings_emojis_overflow)

            override fun onConfigure(position: Int, data: Item) {
                super.onConfigure(position, data)
                val item = data as Item.EmojiItem
                name.text = item.emoji.name
                username.text = item.user.getUserNameWithDiscriminator()
                // Only shown when the uploader has a nickname in this guild.
                nickname.setTextAndVisibilityBy(item.guildMember?.nick)
                IconUtils.setIcon(
                    uploaderAvatar,
                    item.user,
                    R.dimen.avatar_size_standard,
                    guildMember = item.guildMember,
                )

                val emojiSizePx = itemView.resources.getDimensionPixelSize(R.dimen.emoji_size)
                MGImages.setImage(
                    emojiImage,
                    ModelEmojiCustom.getImageUri(item.emoji.id, item.emoji.animated, EMOJI_IMAGE_SIZE),
                    // Divergence: the reference passes `R.dimen.emoji_size` — the *resource id* —
                    // as the pixel width and height, so Fresco never downscales. Resolved to real
                    // pixels here; visually identical, far less bitmap memory.
                    emojiSizePx,
                    emojiSizePx,
                    useSmallCache = true,
                )
                // An emoji the guild has lost the boost tier for stays listed but cannot be used.
                emojiImage.setGrayscale(!item.emoji.available)
                emojiImage.imageAlpha =
                    if (item.emoji.available) ALPHA_AVAILABLE else ALPHA_UNAVAILABLE

                container.setOnClickListener { view ->
                    adapter.onEmojiItemClicked?.invoke(view, item.emoji)
                }
                // The overflow glyph is decoration: the whole row is the touch target, matching the
                // reference, which never binds a listener to it.
                overflow.isClickable = false
            }
        }
    }

    companion object {
        private const val INTENT_EXTRA_GUILD_ID = "INTENT_EXTRA_GUILD_ID"
        private const val INVALID_GUILD_ID = -1L

        /** Discord's per-emoji upload limit, quoted in the header copy. */
        private const val EMOJI_MAX_FILESIZE_KB = 256

        /** CDN render size for a list row's emoji. */
        private const val EMOJI_IMAGE_SIZE = 64

        private const val ALPHA_AVAILABLE = 255
        private const val ALPHA_UNAVAILABLE = 100

        private const val VIEW_INDEX_LOADING = 0
        private const val VIEW_INDEX_CONTENT = 1

        fun newInstance(guildId: Long) = WidgetServerSettingsEmojis().apply {
            arguments = Bundle().apply { putLong(INTENT_EXTRA_GUILD_ID, guildId) }
        }

        fun launch(context: Context, guildId: Long) {
            SettingsNavigation.launch(
                context,
                WidgetServerSettingsEmojis::class.java,
                Intent().putExtra(INTENT_EXTRA_GUILD_ID, guildId),
            )
        }
    }
}

/**
 * State for [WidgetServerSettingsEmojis].
 *
 * `StoreEmojiGuild.observeGuildEmoji` emits `null` until the fetch lands — that is the loading
 * state, and it stays distinct from an empty list, which means "this guild has no emoji yet".
 *
 * The permission test is [PermissionUtils.canAndIsElevated] with **no owner bypass**, matching the
 * reference: managing emoji is an elevated action, so an owner who has not enabled 2FA on an
 * MFA-required guild is refused like anyone else.
 */
class ServerSettingsEmojisViewModel(
    guildId: Long,
    storeStateObservable: Observable<StoreState> = observeStoreState(guildId),
) : AppViewModel<ServerSettingsEmojisViewModel.ViewState>(ViewState.Uninitialized) {

    data class StoreState(
        val guild: Guild?,
        val canManageEmojis: Boolean,
        val emojis: List<ModelEmojiGuild>?,
        val members: Map<Long, GuildMember>,
    )

    sealed class ViewState {
        object Uninitialized : ViewState()

        /** No Manage Emojis and Stickers at the required elevation. The screen closes itself. */
        object Unauthorized : ViewState()

        /** Permitted, but the emoji list has not been fetched yet. */
        object Loading : ViewState()

        data class Loaded(
            val guildId: Long,
            val guildName: String,
            val items: List<WidgetServerSettingsEmojis.Item>,
        ) : ViewState()
    }

    init {
        storeStateObservable.computationLatest().distinctUntilChanged().ui(this).subscribe {
            updateViewState(toViewState(it))
        }
    }

    companion object {
        internal fun toViewState(storeState: StoreState): ViewState {
            val guild = storeState.guild
            if (guild == null || !storeState.canManageEmojis) return ViewState.Unauthorized
            val emojis = storeState.emojis ?: return ViewState.Loading
            return ViewState.Loaded(
                guildId = guild.id,
                guildName = guild.name,
                items = buildItems(guild, emojis, storeState.members),
            )
        }

        /**
         * Lays the list out as the reference does: header, then each non-empty section preceded by
         * its title, then the empty row only when both sections are empty.
         */
        internal fun buildItems(
            guild: Guild,
            emojis: List<ModelEmojiGuild>,
            members: Map<Long, GuildMember>,
        ): List<WidgetServerSettingsEmojis.Item> {
            val emojiMax = PremiumUtils.getGuildEmojiMaxCount(guild)
            // Reversed so the most recently uploaded emoji leads its section.
            val static = emojis.filterNot { it.animated }
                .map { WidgetServerSettingsEmojis.Item.EmojiItem.from(it, members) }
                .reversed()
            val animated = emojis.filter { it.animated }
                .map { WidgetServerSettingsEmojis.Item.EmojiItem.from(it, members) }
                .reversed()

            val items = mutableListOf<WidgetServerSettingsEmojis.Item>(
                WidgetServerSettingsEmojis.Item.EmojiHeader(emojiMax),
            )
            if (static.isNotEmpty()) {
                items += WidgetServerSettingsEmojis.Item.EmojiSection(
                    emojiMax,
                    static.size,
                    R.string.emoji,
                )
                items += static
            }
            if (animated.isNotEmpty()) {
                items += WidgetServerSettingsEmojis.Item.EmojiSection(
                    emojiMax,
                    animated.size,
                    R.string.animated_emoji,
                )
                items += animated
            }
            if (static.isEmpty() && animated.isEmpty()) {
                items += WidgetServerSettingsEmojis.Item.EmojiEmpty
            }
            return items
        }

        fun observeStoreState(guildId: Long): Observable<StoreState> = Observable.combineLatest(
            StoreStream.getGuilds().observeGuild(guildId),
            StoreStream.getUsers().observeMe(),
            StoreStream.getPermissions().observePermissionsForGuild(guildId),
            StoreStream.getGuildEmojis().observeGuildEmoji(guildId),
            StoreStream.getGuilds().observeGuildMembers(guildId),
        ) { guild, meUser, guildPermissions, emojis, members ->
            StoreState(
                guild = guild,
                canManageEmojis = guild != null && PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_EMOJIS_AND_STICKERS,
                    guildPermissions,
                    meUser.mfaEnabled,
                    guild.mfaLevel,
                ),
                emojis = emojis,
                members = members,
            )
        }
    }
}
