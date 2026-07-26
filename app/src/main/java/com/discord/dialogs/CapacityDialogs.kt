package com.discord.dialogs

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.app.AppToast
import com.discord.i18n.setI18nText
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.extensions.setCompoundDrawableWithIntrinsicBounds
import com.google.android.material.button.MaterialButton

/**
 * "This channel is at video capacity" — shown when the user tries to turn their camera on in a
 * voice channel that has already hit its per-channel video limit.
 *
 * Informational only: the single button dismisses, and a tap outside does too, because there is no
 * action the user can take from here.
 */
class CameraCapacityDialog : AppDialog(R.layout.camera_capacity_dialog) {

    override fun onResume() {
        super.onResume()
        requireDialog().setCanceledOnTouchOutside(true)

        val view = requireView()
        view.findViewById<MaterialButton>(R.id.camera_capacity_confirm).setOnClickListener { dismiss() }
        view.findViewById<TextView>(R.id.camera_capacity_body).setI18nText(
            R.string.video_capacity_modal_body,
            "videoUserLimit" to argumentsOrDefault.getInt(ARG_MAX_VIDEO_CHANNEL_USERS).toString(),
        )
    }

    companion object {
        private const val ARG_MAX_VIDEO_CHANNEL_USERS = "ARG_MAX_VIDEO_CHANNEL_USERS"

        fun show(fragmentManager: FragmentManager, maxVideoChannelUsers: Int) {
            CameraCapacityDialog().apply {
                arguments = Bundle().apply { putInt(ARG_MAX_VIDEO_CHANNEL_USERS, maxVideoChannelUsers) }
            }.show(fragmentManager, CameraCapacityDialog::class.java.simpleName)
        }
    }
}

/**
 * "Nobody else can watch right now" — shown when a guild has run out of concurrent stream/video
 * slots, as opposed to a single channel being full ([CameraCapacityDialog]).
 *
 * Carries no arguments: the limit is a server-side property the user cannot act on.
 */
class GuildVideoAtCapacityDialog : AppDialog(R.layout.guild_video_at_capacity) {

    override fun onResume() {
        super.onResume()
        requireDialog().setCanceledOnTouchOutside(true)
        requireView()
            .findViewById<MaterialButton>(R.id.guild_video_at_capacity_confirm)
            .setOnClickListener { dismiss() }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) {
            GuildVideoAtCapacityDialog().show(
                fragmentManager,
                GuildVideoAtCapacityDialog::class.java.simpleName,
            )
        }
    }
}

/**
 * Long-press actions for a connected third-party account on a user profile.
 *
 * The header doubles as the account's name plus its platform icon; "Open in browser" is hidden when
 * the platform exposes no public profile URL, which is why the dialog can end up with a single row.
 */
class ConnectedAccountActionsDialog : AppDialog(R.layout.connected_account_actions_dialog) {

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val profileUrl = argumentsOrDefault.getString(ARG_PROFILE_URL, "")
        val username = argumentsOrDefault.getString(ARG_USERNAME, "")
        val platformImageAttrRes = argumentsOrDefault.getInt(ARG_THEMED_PLATFORM_IMAGE_RES, 0)

        val header = view.findViewById<TextView>(R.id.connected_account_actions_dialog_header)
        if (platformImageAttrRes != 0) {
            header.setCompoundDrawableWithIntrinsicBounds(
                start = view.getThemedDrawableRes(platformImageAttrRes),
            )
        }
        header.text = username

        val openInBrowser = view.findViewById<TextView>(R.id.connected_account_actions_dialog_open_in_browser)
        openInBrowser.visibility = if (profileUrl.isNullOrEmpty()) View.GONE else View.VISIBLE
        openInBrowser.setOnClickListener {
            UriHandler.handle(requireContext(), profileUrl)
            dismiss()
        }

        view.findViewById<TextView>(R.id.connected_account_actions_dialog_copy_username)
            .setOnClickListener {
                AppToast.copyToClipboard(requireContext(), username.orEmpty())
                dismiss()
            }
    }

    companion object {
        private const val ARG_PROFILE_URL = "ARG_PROFILE_URL"
        private const val ARG_USERNAME = "ARG_USERNAME"
        private const val ARG_THEMED_PLATFORM_IMAGE_RES = "ARG_THEMED_PLATFORM_IMAGE_RES"

        /**
         * @param themedPlatformImageAttrRes a theme *attribute*, not a drawable id — the platform
         *   badges have light and dark variants.
         */
        fun show(
            fragmentManager: FragmentManager,
            username: String,
            profileUrl: String?,
            themedPlatformImageAttrRes: Int,
        ) {
            ConnectedAccountActionsDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_USERNAME, username)
                    putString(ARG_PROFILE_URL, profileUrl)
                    putInt(ARG_THEMED_PLATFORM_IMAGE_RES, themedPlatformImageAttrRes)
                }
            }.show(fragmentManager, ConnectedAccountActionsDialog::class.java.simpleName)
        }
    }
}
