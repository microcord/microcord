package com.discord.widgets.emoji

import android.content.Context
import com.discord.api.channel.Channel
import com.discord.api.guild.GuildFeature
import com.discord.app.AppViewModel
import com.discord.models.domain.emoji.ModelEmojiUnicode
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreAnalytics
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreEmoji
import com.discord.stores.StoreEmojiCustom
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.textprocessing.node.EmojiNode
import com.discord.utilities.user.UserUtils.isPremium
import com.discord.widgets.guilds.join.joinGuild
import retrofit2.HttpException
import rx.Observable
import com.discord.api.guild.Guild as ApiGuild

/**
 * Drives [WidgetEmojiSheet] — the popout for an emoji tapped in a message.
 *
 * A unicode emoji resolves straight out of [StoreEmoji]'s catalog. A *custom* emoji is the
 * interesting case, because it may come from a server the viewer is not in:
 *
 *  - in one of my guilds, not discoverable → that guild, marked private
 *  - in one of my guilds and discoverable, or not in any of mine → `GET /emojis/{id}/guild` fills
 *    in the public profile and the online count
 *  - a 404 for a guild I *am* in means it stopped being discoverable, so the known guild is kept
 *    and marked private
 *  - anything else → [Companion.CustomEmojiGuildInfo.Unknown] and the guild card hides itself
 */
class EmojiSheetViewModel(
    private val emojiIdAndType: EmojiNode.EmojiIdAndType,
    private val restApi: RestAPIInterface = RestAPI.api,
    private val storeEmoji: StoreEmoji = StoreStream.getEmojis(),
    storeEmojiCustom: StoreEmojiCustom = StoreStream.getCustomEmojis(),
    /**
     * `StoreAnalytics` exists but `StoreStream` publishes no accessor for it yet, so the popout
     * impression is skipped rather than raised from the wrong place. **Reported to the
     * `StoreStream` wiring agent** — one `getAnalytics()` accessor closes this.
     */
    private val storeAnalytics: StoreAnalytics? = null,
    storeUsers: StoreUser = StoreStream.getUsers(),
    storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    storeStateObservable: Observable<StoreState> = observeStoreState(
        restApi,
        emojiIdAndType,
        storeUsers,
        storeGuilds,
        storeEmojiCustom,
        storeChannelsSelected,
    ),
) : AppViewModel<EmojiSheetViewModel.ViewState>(ViewState.Loading) {

    data class StoreState(
        val customEmojiGuildInfo: Companion.CustomEmojiGuildInfo?,
        val emoji: EmojiNode.EmojiIdAndType,
        val meUserIsPremium: Boolean,
        val joinedGuildIds: Set<Long>,
        val currentChannel: Channel?,
        val favoriteKeys: Set<String>,
    )

    sealed class ViewState {
        object Loading : ViewState()

        data class EmojiCustom(
            val emojiCustom: EmojiNode.EmojiIdAndType.Custom,
            val emojiGuildInfo: Companion.CustomEmojiGuildInfo,
            val isUserPremium: Boolean,
            val isCurrentGuild: Boolean,
            val canFavorite: Boolean,
            val isFavorite: Boolean,
        ) : ViewState()

        data class EmojiUnicode(
            val emojiUnicode: ModelEmojiUnicode,
            val canFavorite: Boolean,
            val isFavorite: Boolean,
        ) : ViewState()

        /** The emoji could not be resolved at all; the sheet closes. */
        object Invalid : ViewState()

        /** The viewer joined the source guild from this sheet; it has served its purpose. */
        object Dismiss : ViewState()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .distinctUntilChanged()
            .subscribe(::handleStoreState)
    }

    private fun handleStoreState(storeState: StoreState) {
        when (storeState.emoji) {
            is EmojiNode.EmojiIdAndType.Unicode -> {
                standardPopoutAnalytics(storeState)
                handleStoreStateUnicode(storeState, storeState.emoji)
            }

            is EmojiNode.EmojiIdAndType.Custom -> {
                customPopoutAnalytics(storeState, storeState.emoji)
                handleStoreStateCustom(storeState, storeState.emoji)
            }
        }
    }

    /** Popout impression for a unicode emoji. No channel means no impression, as in the reference. */
    private fun standardPopoutAnalytics(storeState: StoreState) {
        val channel = storeState.currentChannel ?: return
        storeAnalytics?.openUnicodeEmojiPopout(channel)
    }

    /**
     * Popout impression for a custom emoji.
     *
     * The last dimension is `sourceGuildPrivate`, which the reference computes as `!isPublic`
     * (`javap -c`: `isPublic ... iconst_1 ixor`) — not as a stored field. Reproduced so the
     * analytics dimension does not silently invert.
     */
    private fun customPopoutAnalytics(storeState: StoreState, custom: EmojiNode.EmojiIdAndType.Custom) {
        val channel = storeState.currentChannel ?: return
        val guildInfo = storeState.customEmojiGuildInfo ?: return
        storeAnalytics?.openCustomEmojiPopout(
            channel = channel,
            emojiId = custom.id,
            isPremium = storeState.meUserIsPremium,
            joinedSourceGuild = guildInfo.isUserInGuild,
            sourceGuildPrivate = !guildInfo.isPublic,
        )
    }

    private fun handleStoreStateCustom(
        storeState: StoreState,
        custom: EmojiNode.EmojiIdAndType.Custom,
    ) {
        val guildInfo = storeState.customEmojiGuildInfo ?: run {
            updateViewState(ViewState.Invalid)
            return
        }
        val sourceGuildId = (guildInfo as? Companion.CustomEmojiGuildInfo.Known)?.guild?.id
        updateViewState(
            ViewState.EmojiCustom(
                emojiCustom = custom,
                emojiGuildInfo = guildInfo,
                isUserPremium = storeState.meUserIsPremium,
                isCurrentGuild = sourceGuildId != null &&
                    sourceGuildId == storeState.currentChannel?.guildId,
                canFavorite = guildInfo.isUserInGuild,
                isFavorite = favoriteKey(custom) in storeState.favoriteKeys,
            ),
        )
    }

    private fun handleStoreStateUnicode(
        storeState: StoreState,
        unicode: EmojiNode.EmojiIdAndType.Unicode,
    ) {
        val modelEmoji = storeEmoji.unicodeEmojisNamesMap[unicode.name]
        updateViewState(
            if (modelEmoji == null) {
                ViewState.Invalid
            } else {
                ViewState.EmojiUnicode(
                    emojiUnicode = modelEmoji,
                    canFavorite = true,
                    isFavorite = favoriteKey(modelEmoji) in storeState.favoriteKeys,
                )
            },
        )
    }

    fun joinGuild(context: Context, guild: Guild) = joinGuild(
        context = context,
        guildId = guild.id,
        isLurker = false,
        errorClass = EmojiSheetViewModel::class.java,
        onNext = { updateViewState(ViewState.Dismiss) },
    )

    fun setFavorite(favorite: Boolean) {
        val key = when (val state = viewState) {
            is ViewState.EmojiCustom -> favoriteKey(state.emojiCustom)
            is ViewState.EmojiUnicode -> favoriteKey(state.emojiUnicode)
            else -> return
        }
        setFavoriteEmoji(key, favorite)
    }

    companion object {

        /** What we managed to learn about the guild that owns a custom emoji. */
        sealed class CustomEmojiGuildInfo {
            abstract val isPublic: Boolean
            abstract val isUserInGuild: Boolean

            data class Known(
                val guild: Guild,
                override val isPublic: Boolean,
                override val isUserInGuild: Boolean,
                val approximateOnline: Int?,
            ) : CustomEmojiGuildInfo()

            object Unknown : CustomEmojiGuildInfo() {
                override val isPublic: Boolean = false
                override val isUserInGuild: Boolean = false
            }
        }

        /**
         * `StoreMediaFavorites` has not been reconstructed. It keys favourites by
         * `Emoji.getUniqueId()`, which is the custom emoji id as a string, or the unicode emoji's
         * surrogate — reproduced by [favoriteKey] so this seam can be swapped for the real store
         * without changing the view model. **Reported to the `StoreStream` wiring agent**; the two
         * calls needed are `observeFavorites(Favorite.EmojiTypes)` and `add`/`removeFavorite`.
         */
        @JvmStatic
        var observeFavoriteEmojiKeys: () -> Observable<Set<String>> = { Observable.just(emptySet()) }

        /** @see observeFavoriteEmojiKeys */
        @JvmStatic
        var setFavoriteEmoji: (key: String, favorite: Boolean) -> Unit = { _, _ -> }

        private fun favoriteKey(custom: EmojiNode.EmojiIdAndType.Custom): String = custom.id.toString()

        private fun favoriteKey(unicode: ModelEmojiUnicode): String = unicode.getUniqueId()

        fun observeStoreState(
            restApi: RestAPIInterface,
            emojiIdAndType: EmojiNode.EmojiIdAndType,
            storeUsers: StoreUser,
            storeGuilds: StoreGuilds,
            storeEmojiCustom: StoreEmojiCustom,
            storeChannelsSelected: StoreChannelsSelected,
        ): Observable<StoreState> = Observable.combineLatest(
            getGuildForCustomEmoji(restApi, emojiIdAndType, storeGuilds, storeEmojiCustom),
            storeUsers.observeMe(),
            storeGuilds.observeGuilds(),
            storeChannelsSelected.observeSelectedChannel(),
            observeFavoriteEmojiKeys(),
        ) { guildInfo, meUser: MeUser, guilds, channel, favorites ->
            StoreState(
                customEmojiGuildInfo = guildInfo,
                emoji = emojiIdAndType,
                meUserIsPremium = meUser.isPremium,
                joinedGuildIds = guilds.keys,
                currentChannel = channel,
                favoriteKeys = favorites,
            )
        }

        private fun getGuildForCustomEmoji(
            restApi: RestAPIInterface,
            emojiIdAndType: EmojiNode.EmojiIdAndType,
            storeGuilds: StoreGuilds,
            storeEmojiCustom: StoreEmojiCustom,
        ): Observable<CustomEmojiGuildInfo?> {
            val custom = emojiIdAndType as? EmojiNode.EmojiIdAndType.Custom
                ?: return Observable.just<CustomEmojiGuildInfo?>(null)
            return Observable
                .fromCallable {
                    // Which of my guilds, if any, owns this emoji id.
                    storeEmojiCustom.getAllGuildEmoji()
                        .entries
                        .firstOrNull { custom.id in it.value.keys }
                        ?.let { storeGuilds.getGuilds()[it.key] }
                }
                .flatMap { joinedGuild ->
                    when {
                        joinedGuild == null -> fetchEmojiGuild(restApi, custom.id)
                            .map<CustomEmojiGuildInfo> {
                                CustomEmojiGuildInfo.Known(it, true, false, it.approximatePresenceCount)
                            }
                            .onErrorReturn { CustomEmojiGuildInfo.Unknown }

                        joinedGuild.hasFeature(GuildFeature.DISCOVERABLE) ->
                            fetchEmojiGuild(restApi, custom.id)
                                .map<CustomEmojiGuildInfo> {
                                    CustomEmojiGuildInfo.Known(it, true, true, it.approximatePresenceCount)
                                }
                                .onErrorReturn { error ->
                                    // 404: it left discovery. We are still a member, so keep what we
                                    // know and drop the public profile.
                                    if (error is HttpException && error.code() == 404) {
                                        CustomEmojiGuildInfo.Known(joinedGuild, false, true, null)
                                    } else {
                                        CustomEmojiGuildInfo.Unknown
                                    }
                                }

                        else -> Observable.just(
                            CustomEmojiGuildInfo.Known(joinedGuild, false, true, null),
                        )
                    }
                }
        }

        private fun fetchEmojiGuild(restApi: RestAPIInterface, emojiId: Long): Observable<Guild> =
            restApi.getEmojiGuild(emojiId).map(::toModelGuild)
    }
}

/**
 * The public guild profile returned by `GET /emojis/{id}/guild`, as the model type the sheet
 * renders. The reference has a `Guild(ApiGuild)` constructor; `StoreGuilds` owns the equivalent
 * (`Guild.merge`) but keeps it private, so **delete this and call that when it is exposed** —
 * `widgets/stickers/GuildStickerSheetViewModel` carries the identical copy.
 */
private fun toModelGuild(apiGuild: ApiGuild): Guild = Guild(
    id = apiGuild.id,
    name = apiGuild.name,
    icon = apiGuild.icon,
    description = apiGuild.description,
    banner = apiGuild.banner,
    features = apiGuild.features.toSet(),
    approximatePresenceCount = apiGuild.approximatePresenceCount,
    memberCount = apiGuild.memberCount,
    premiumTier = apiGuild.premiumTier,
    ownerId = apiGuild.ownerId,
    vanityUrlCode = apiGuild.vanityUrlCode,
)
