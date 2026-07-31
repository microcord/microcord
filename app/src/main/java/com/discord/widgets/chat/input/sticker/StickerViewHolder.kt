package com.discord.widgets.chat.input.sticker

import android.view.HapticFeedbackConstants
import com.discord.R
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.ui
import com.discord.utilities.stickers.StickerUtils
import com.discord.views.sticker.StickerView
import com.discord.widgets.stickers.StickerSendability
import rx.Subscription
import rx.subjects.BehaviorSubject

class StickerViewHolder(
    adapter: WidgetStickerAdapter,
    private val recyclerScrollingWithinThresholdSubject: BehaviorSubject<Boolean>?,
    private val onStickerItemSelected: (StickerItem) -> Unit,
) : MGRecyclerViewHolder<WidgetStickerAdapter, MGRecyclerDataPayload>(
    R.layout.sticker_picker_sticker_item,
    adapter,
) {
    private val stickerView: StickerView = itemView.findViewById(R.id.sticker_picker_sticker)
    private var scrollingSpeedSubscription: Subscription? = null

    override val subscription: Subscription? get() = stickerView.getSubscription()

    override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
        super.onConfigure(position, data)
        val item = data as? StickerItem ?: return
        if (recyclerScrollingWithinThresholdSubject == null) return configureSticker(item)
        scrollingSpeedSubscription?.unsubscribe()
        scrollingSpeedSubscription = recyclerScrollingWithinThresholdSubject
            .distinctUntilChanged()
            .ui(adapter.appComponent)
            .subscribe { withinThreshold ->
                if (withinThreshold) {
                    configureSticker(item)
                    scrollingSpeedSubscription?.unsubscribe()
                    scrollingSpeedSubscription = null
                }
            }
    }

    private fun configureSticker(item: StickerItem) {
        stickerView.setSticker(item.sticker, PICKER_ANIMATION_SETTING)
        stickerView.alpha = if (item.sendability == StickerSendability.SENDABLE) 1f else 0.25f
        stickerView.setOnClickListener {
            onStickerItemSelected(item)
            stickerView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun cancelLoading() {
        scrollingSpeedSubscription?.unsubscribe()
        scrollingSpeedSubscription = null
    }

    companion object {
        const val PICKER_ANIMATION_SETTING = StickerUtils.ALWAYS_ANIMATE
    }
}
