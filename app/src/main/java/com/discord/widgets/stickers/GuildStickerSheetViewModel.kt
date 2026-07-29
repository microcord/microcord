package com.discord.widgets.stickers

import android.content.Context
import androidx.annotation.MainThread
import com.discord.api.guild.GuildFeature
import com.discord.api.sticker.Sticker
import com.discord.app.AppViewModel
import com.discord.models.guild.Guild
import com.discord.models.user.MeUser
import com.discord.restapi.RestAPIInterface
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.user.UserUtils
import com.discord.widgets.guilds.join.joinGuild
import retrofit2.HttpException
import rx.Observable
import com.discord.api.guild.Guild as ApiGuild

/**
 * Drives [WidgetGuildStickerSheet] — the popout for a *guild* sticker tapped in a message.
 *
 * The interesting work is resolving which guild the sticker came from and how much of that guild
 * the viewer is allowed to see, because the sticker may well be from a server they are not in:
 *
 *  - in the guild already → the guild is taken straight from [StoreGuilds]
 *  - in a *discoverable* guild, or not in the guild at all → `GET /stickers/{id}/guild` fills in the
 *    public profile and the online count
 *  - a 404 for a guild we are in means it stopped being discoverable, so the known guild is kept
 *    but marked private
 *  - anything else → [Companion.GuildStickerGuildInfo.Unknown], and the guild section hides itself
 */
class GuildStickerSheetViewModel(
    private val sticker: Sticker,
    private val api: RestAPIInterface = RestAPI.api,
    storeUser: StoreUser = StoreStream.getUsers(),
    storeChannelsSelected: StoreChannelsSelected = StoreStream.getChannelsSelected(),
    storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    storeStateObservable: Observable<StoreState> =
        observeStoreState(api, sticker, storeUser, storeChannelsSelected, storeGuilds),
) : AppViewModel<GuildStickerSheetViewModel.ViewState>(ViewState.Loading) {

    data class StoreState(
        val meUser: MeUser,
        val currentGuildId: Long?,
        val userGuilds: Map<Long, Guild>,
        val guildStickerGuildInfo: Companion.GuildStickerGuildInfo,
    )

    sealed class ViewState {
        object Loading : ViewState()

        data class Loaded(
            val sticker: Sticker,
            val isUserPremium: Boolean,
            val isCurrentGuild: Boolean,
            val guildStickerGuildInfo: Companion.GuildStickerGuildInfo,
        ) : ViewState()

        /** The user joined the guild from this sheet; the sheet has served its purpose. */
        object Dismiss : ViewState()
    }

    init {
        storeStateObservable
            .computationLatest()
            .ui(this)
            .subscribe(::handleStoreState)
    }

    @MainThread
    private fun handleStoreState(storeState: StoreState) {
        updateViewState(
            ViewState.Loaded(
                sticker = sticker,
                isUserPremium = with(UserUtils) { storeState.meUser.canUsePremiumStickers },
                isCurrentGuild = storeState.currentGuildId == sticker.guildId,
                guildStickerGuildInfo = storeState.guildStickerGuildInfo,
            ),
        )
    }

    fun joinGuild(context: Context, guild: Guild) = joinGuild(
        context = context,
        guildId = guild.id,
        isLurker = false,
        errorClass = GuildStickerSheetViewModel::class.java,
        onNext = { updateViewState(ViewState.Dismiss) },
    )

    companion object {

        /** What we managed to learn about the guild that owns the sticker. */
        sealed class GuildStickerGuildInfo {
            abstract val isPublic: Boolean
            abstract val isUserInGuild: Boolean

            data class Known(
                val guild: Guild,
                override val isPublic: Boolean,
                override val isUserInGuild: Boolean,
                val approximateOnline: Int?,
            ) : GuildStickerGuildInfo()

            object Unknown : GuildStickerGuildInfo() {
                override val isPublic: Boolean = false
                override val isUserInGuild: Boolean = false
            }
        }

        fun observeStoreState(
            api: RestAPIInterface,
            sticker: Sticker,
            storeUser: StoreUser,
            storeChannelsSelected: StoreChannelsSelected,
            storeGuilds: StoreGuilds,
        ): Observable<StoreState> =
            Observable.combineLatest(
                getGuildForGuildSticker(api, sticker, storeGuilds),
                storeUser.observeMe(),
                storeChannelsSelected.observeSelectedChannel(),
                storeGuilds.observeGuilds(),
            ) { guildInfo, meUser, channel, guilds ->
                StoreState(meUser, channel?.guildId, guilds, guildInfo)
            }

        private fun getGuildForGuildSticker(
            api: RestAPIInterface,
            sticker: Sticker,
            storeGuilds: StoreGuilds,
        ): Observable<GuildStickerGuildInfo> = Observable.defer {
            val joinedGuild = sticker.guildId?.let { storeGuilds.getGuilds()[it] }
            when {
                // Not a member: the public profile is all we can ever see.
                joinedGuild == null -> fetchStickerGuild(api, sticker)
                    .map<GuildStickerGuildInfo> { guild ->
                        GuildStickerGuildInfo.Known(
                            guild,
                            isPublic = true,
                            isUserInGuild = false,
                            approximateOnline = guild.approximatePresenceCount,
                        )
                    }
                    .onErrorReturn { GuildStickerGuildInfo.Unknown }

                // A member of a discoverable guild: the endpoint adds the online count.
                joinedGuild.hasFeature(GuildFeature.DISCOVERABLE) -> fetchStickerGuild(api, sticker)
                    .map<GuildStickerGuildInfo> { guild ->
                        GuildStickerGuildInfo.Known(
                            guild,
                            isPublic = true,
                            isUserInGuild = true,
                            approximateOnline = guild.approximatePresenceCount,
                        )
                    }
                    .onErrorReturn { error ->
                        // 404: the guild left discovery. We are still a member, so keep what we
                        // know and drop the public profile.
                        if (error is HttpException && error.code() == HTTP_NOT_FOUND) {
                            GuildStickerGuildInfo.Known(joinedGuild, false, true, null)
                        } else {
                            GuildStickerGuildInfo.Unknown
                        }
                    }

                // A member of a private guild: nothing public to fetch.
                else -> Observable.just<GuildStickerGuildInfo>(
                    GuildStickerGuildInfo.Known(joinedGuild, false, true, null),
                )
            }
        }

        private const val HTTP_NOT_FOUND = 404

        private fun fetchStickerGuild(api: RestAPIInterface, sticker: Sticker): Observable<Guild> =
            api.getStickerGuild(sticker.id).map { it.toModelGuild() }
    }
}

/**
 * The public guild profile returned by `GET /stickers/{id}/guild`, as the model type the sheet
 * renders.
 *
 * Only the fields the popout uses are carried over. The reference has a `Guild(ApiGuild)`
 * constructor for this; `StoreGuilds` owns the equivalent (`Guild.merge`) but keeps it private, so
 * **delete this and call that when it is exposed**.
 */
private fun ApiGuild.toModelGuild(): Guild = Guild(
    id = id,
    name = name,
    icon = icon,
    description = description,
    banner = banner,
    features = features.toSet(),
    approximatePresenceCount = approximatePresenceCount,
    memberCount = memberCount,
    premiumTier = premiumTier,
    ownerId = ownerId,
    vanityUrlCode = vanityUrlCode,
)
