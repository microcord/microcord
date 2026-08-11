package com.discord.stores

import discord_protos.discord_users.v1.FrecencyUserSettingsOuterClass.FrecencyUserSettings

/** The account's frecency and favourites. Unlike [UserSettings], the first read fetches it. */
object FrecencySettings {

    private val store get() = StoreStream.getFrecencyProto()
    private val writer get() = StoreStream.getFrecencyProtoWriter()

    val favoriteGifs = setting({ it.favoriteGifs.gifsMap }) { gifs ->
        favoriteGifsBuilder.clearGifs().putAllGifs(gifs)
    }

    val favoriteEmojis = setting({ it.favoriteEmojis.emojisList.toList() }) { emojis ->
        favoriteEmojisBuilder.clearEmojis().addAllEmojis(emojis)
    }

    val emojiFrecency = frecency(
        { it.emojiFrecency.emojisMap },
        { emojiFrecencyBuilder.clearEmojis().putAllEmojis(it) },
    )

    val stickerFrecency = frecency(
        { it.stickerFrecency.stickersMap.mapKeys(Map.Entry<Long, *>::key) },
        { items -> stickerFrecencyBuilder.clearStickers().putAllStickers(items.mapKeys { it.key.toLong() }) },
    )

    val guildAndChannelFrecency = frecency(
        { it.guildAndChannelFrecency.guildAndChannelsMap.mapKeys(Map.Entry<Long, *>::key) },
        { items ->
            guildAndChannelFrecencyBuilder.clearGuildAndChannels()
                .putAllGuildAndChannels(items.mapKeys { it.key.toLong() })
        },
    )

    private fun <T> setting(
        fromProto: (FrecencyUserSettings) -> T,
        delay: UserSettingsDelay = UserSettingsDelay.INFREQUENT_USER_ACTION,
        toProto: FrecencyUserSettings.Builder.(T) -> Unit,
    ) = UserSettingsProtoSetting(store, writer, fromProto, delay, toProto = toProto)

    /** Written by the periodic flush, never on the gesture that changed them. */
    private fun <K> frecency(
        fromProto: (FrecencyUserSettings) -> Map<K, FrecencyUserSettings.FrecencyItem>,
        toProto: FrecencyUserSettings.Builder.(Map<K, FrecencyUserSettings.FrecencyItem>) -> Unit,
    ) = setting(fromProto, UserSettingsDelay.AUTOMATED, toProto)
}
