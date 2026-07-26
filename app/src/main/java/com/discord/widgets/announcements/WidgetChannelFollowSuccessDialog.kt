package com.discord.widgets.announcements

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton

/**
 * "You're following this channel" — the confirmation shown once the follow request succeeds.
 *
 * Both the headline and the illustration are picked at random from a set, independently of each
 * other; that is the reference behaviour, not a bug.
 */
class WidgetChannelFollowSuccessDialog : AppDialog(R.layout.widget_channel_follow_success_dialog) {

    private val successTextOptions = intArrayOf(
        R.string.follow_success_modal_header_01,
        R.string.follow_success_modal_header_02,
        R.string.follow_success_modal_header_03,
        R.string.follow_success_modal_header_04,
        R.string.follow_success_modal_header_05,
        R.string.follow_success_modal_header_06,
        R.string.follow_success_modal_header_07,
        R.string.follow_success_modal_header_08,
        R.string.follow_success_modal_header_09,
        R.string.follow_success_modal_header_10,
    )

    private val successImageOptions = intArrayOf(
        R.attr.img_channel_following_success_1,
        R.attr.img_channel_following_success_2,
        R.attr.img_channel_following_success_3,
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val successText: TextView = view.findViewById(R.id.channel_follow_success_text)
        val successImage: SimpleDraweeView = view.findViewById(R.id.channel_follow_success_image)
        val confirm: MaterialButton = view.findViewById(R.id.channel_follow_success_confirm)

        successText.setText(successTextOptions.random())
        // The illustrations are theme attributes, so light and dark get different art.
        MGImages.setImage(successImage, requireContext().getThemedDrawableRes(successImageOptions.random()))
        confirm.setOnClickListener { dismiss() }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            WidgetChannelFollowSuccessDialog()
                .show(fragmentManager, WidgetChannelFollowSuccessDialog::class.java.simpleName)
        }
    }
}
