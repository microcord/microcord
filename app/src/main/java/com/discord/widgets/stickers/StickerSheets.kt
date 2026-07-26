package com.discord.widgets.stickers

import androidx.fragment.app.FragmentManager
import com.discord.api.sticker.BaseSticker
import com.discord.api.sticker.Sticker
import com.discord.api.sticker.StickerPartial
import com.discord.api.sticker.StickerType
import com.discord.app.AppComponent
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Observable

/**
 * Opens the right sticker popout for a sticker tapped in a message.
 *
 * A message only carries a [StickerPartial] (id, name, format), which is not enough to know whether
 * the sticker is a standard pack sticker or a guild one, so the full [Sticker] has to be resolved
 * first — from cache if it is there, otherwise over REST. The three outcomes match the reference's
 * `WidgetChatListAdapterEventsHandler.onStickerClicked`:
 *
 *  - [StickerType.STANDARD] → [WidgetStickerSheet]
 *  - [StickerType.GUILD] → [WidgetGuildStickerSheet]
 *  - resolution failed → [WidgetUnknownStickerSheet], which shows the partial and nothing else
 *
 * A [StickerType.UNKNOWN] sticker opens nothing at all, exactly as in the reference.
 */
object StickerSheets {

    fun show(
        fragmentManager: FragmentManager,
        appComponent: AppComponent,
        sticker: BaseSticker,
        channelId: Long,
    ) {
        if (sticker is Sticker) {
            showResolved(fragmentManager, sticker, channelId)
            return
        }
        getGuildOrStandardSticker(sticker.getStickerId(), fetchIfMissing = true)
            .ui(appComponent)
            .subscribe(
                { resolved ->
                    if (resolved != null) {
                        showResolved(fragmentManager, resolved, channelId)
                    } else {
                        showUnknown(fragmentManager, sticker)
                    }
                },
                { showUnknown(fragmentManager, sticker) },
            )
    }

    private fun showResolved(fragmentManager: FragmentManager, sticker: Sticker, channelId: Long) {
        when (sticker.type) {
            StickerType.STANDARD -> WidgetStickerSheet.show(fragmentManager, sticker, channelId)
            StickerType.GUILD -> WidgetGuildStickerSheet.show(fragmentManager, sticker)
            StickerType.UNKNOWN -> Unit
        }
    }

    private fun showUnknown(fragmentManager: FragmentManager, sticker: BaseSticker) {
        WidgetUnknownStickerSheet.show(
            fragmentManager,
            sticker as? StickerPartial ?: sticker.toStickerPartial(),
        )
    }

    /**
     * Port of the reference's `StickerUtils.getGuildOrStandardSticker`, which the parity audit
     * flagged as absent — it is why message rows render the partial rather than the hydrated
     * sticker.
     *
     * It belongs in `utilities/stickers/StickerUtils`; it is here only because that file is
     * concurrently owned by the sticker-animation work. Move it when that lands — the chat sticker
     * row wants it too, so that both the row art and this popout use the full sticker.
     *
     * Emits `null` rather than erroring when the sticker is not cached and [fetchIfMissing] is
     * false, so a caller can render a fallback without an error path.
     */
    fun getGuildOrStandardSticker(
        stickerId: Long,
        fetchIfMissing: Boolean = false,
    ): Observable<Sticker?> {
        StickerStores.stickers.getStickers()[stickerId]?.let { return Observable.just(it) }
        StickerStores.guildStickers.getGuildSticker(stickerId)?.let { return Observable.just(it) }
        if (!fetchIfMissing) return Observable.just(null)
        return RestAPI.api.getSticker(stickerId)
            .restSubscribeOn()
            .doOnNext { fetched ->
                StickerStores.stickers.handleFetchedSticker(fetched)
                StickerStores.guildStickers.handleFetchedSticker(fetched)
            }
            .map { it }
    }
}
