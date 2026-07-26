package com.discord.widgets.stickers

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.i18nFormat
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setTextAndVisibilityBy

/**
 * The little "{pack} Details" card the sticker picker raises when a pack header is tapped: three
 * optional bullets saying whether the pack is animated, Nitro-only, and how long it will stay on
 * sale.
 *
 * It sits above the bottom of the screen rather than centred, so it does not cover the picker row
 * that opened it; [EXTRA_Y_POSITION] is that offset, defaulting to half a keyboard's height.
 */
class WidgetStickerPackDetailsDialog : AppDialog(R.layout.sticker_pack_details_dialog) {

    private lateinit var packName: TextView
    private lateinit var animated: TextView
    private lateinit var premium: TextView
    private lateinit var limited: TextView

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).apply {
            window?.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            val requested = arguments?.getInt(EXTRA_Y_POSITION, 0) ?: 0
            val y = if (requested == 0) {
                (context.resources.getDimension(R.dimen.default_keyboard_height) / 2f).toInt()
            } else {
                requested
            }
            window?.attributes = window?.attributes?.apply { this.y = y }
        }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        packName = view.findViewById(R.id.sticker_pack_details_pack)
        animated = view.findViewById(R.id.sticker_pack_details_animated)
        premium = view.findViewById(R.id.sticker_pack_details_premium)
        limited = view.findViewById(R.id.sticker_pack_details_limited)
        view.findViewById<TextView>(R.id.sticker_pack_details_close).setOnClickListener { dismiss() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val stickerPackId = arguments?.getLong(EXTRA_STICKER_PACK_ID) ?: return
        StickerStores.stickers.observeStickerPack(stickerPackId)
            .ui(this)
            .subscribe(::configureUI)
    }

    @SuppressLint("SetTextI18n")
    private fun configureUI(stickerPackState: StickerStore.StickerPackState) {
        val pack = (stickerPackState as? StickerStore.StickerPackState.Loaded)?.stickerPack ?: return
        val context = requireContext()
        packName.text =
            context.i18nFormat(R.string.sticker_picker_pack_details, "stickerPackName" to pack.name)

        // The leading "・" is hard-coded in the reference too — it is a bullet, not copy.
        animated.setTextAndVisibilityBy(
            if (pack.isAnimatedPack()) {
                BULLET + getString(R.string.sticker_picker_pack_details_animated)
            } else {
                null
            },
        )
        premium.setTextAndVisibilityBy(
            if (pack.isPremiumPack()) {
                BULLET + getString(R.string.sticker_picker_pack_details_premium)
            } else {
                null
            },
        )
        limited.setTextAndVisibilityBy(
            if (pack.isLimitedPack()) {
                BULLET + context.i18nFormat(
                    R.string.sticker_picker_pack_details_limited_time_left,
                    "time" to StickerPackFormatting.getLimitedTimeLeftString(context, pack.storeListing),
                )
            } else {
                null
            },
        )
    }

    companion object {
        private const val EXTRA_STICKER_PACK_ID = "com.discord.intent.EXTRA_STICKER_PACK_ID"
        private const val EXTRA_Y_POSITION = "EXTRA_Y_POSITION"
        private const val BULLET = "・ "

        fun show(fragmentManager: FragmentManager, stickerPackId: Long, y: Int? = null) {
            WidgetStickerPackDetailsDialog().apply {
                arguments = Bundle().apply {
                    putLong(EXTRA_STICKER_PACK_ID, stickerPackId)
                    y?.let { putInt(EXTRA_Y_POSITION, it) }
                }
            }.show(fragmentManager, WidgetStickerPackDetailsDialog::class.java.simpleName)
        }
    }
}
