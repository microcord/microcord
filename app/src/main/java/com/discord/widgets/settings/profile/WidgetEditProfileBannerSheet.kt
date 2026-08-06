package com.discord.widgets.settings.profile

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.widgets.settings.premium.PremiumUpsellDialog
import com.discord.app.AppBottomSheet
import com.discord.app.appViewModels
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.google.android.material.button.MaterialButton

class WidgetEditProfileBannerSheet : AppBottomSheet() {
    private val defaultBannerColorHex get() = argumentsOrDefault.getString(ARG_DEFAULT_BANNER_COLOR_HEX, "#000000")
    private val viewModel: EditProfileBannerSheetViewModel by appViewModels {
        EditProfileBannerSheetViewModel(
            defaultBannerColorHex,
            argumentsOrDefault.getString(ARG_INITIAL_COLOR_PREVIEW_HEX),
        )
    }
    private var colorPickerDialog: ProfileColorPickerDialog? = null

    private lateinit var colorContainer: View
    private lateinit var colorPreview: ImageView
    private lateinit var changeImageContainer: View
    private lateinit var changeImageTitle: TextView
    private lateinit var removeImage: TextView
    private lateinit var premiumUpsell: MaterialButton
    private lateinit var profileEffectItem: View
    private lateinit var profileEffectDescription: TextView

    override fun getContentViewResId() = R.layout.widget_edit_profile_banner_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        colorContainer = view.findViewById(R.id.banner_color_item_container)
        colorPreview = view.findViewById(R.id.banner_color_preview)
        changeImageContainer = view.findViewById(R.id.banner_change_image_item_container)
        changeImageTitle = view.findViewById(R.id.banner_change_image_item_title)
        removeImage = view.findViewById(R.id.banner_remove_image_item)
        premiumUpsell = view.findViewById(R.id.premium_upsell_button)
        profileEffectItem = view.findViewById(R.id.banner_profile_effect_item)
        profileEffectDescription = view.findViewById(R.id.banner_profile_effect_item_description)

        val hasBanner = argumentsOrDefault.getBoolean(ARG_HAS_BANNER_IMAGE)
        changeImageTitle.setText(
            if (hasBanner) R.string.user_settings_change_profile_banner else R.string.user_settings_upload_profile_banner,
        )
        removeImage.visibility = visible(hasBanner)
        removeImage.setOnClickListener {
            sendResult(ACTION_REMOVE_IMAGE)
            dismiss()
        }
        premiumUpsell.setOnClickListener { launchPremium() }
        profileEffectDescription.text = argumentsOrDefault.getString(ARG_PROFILE_EFFECT_NAME)
            ?: getString(R.string.avatar_decoration_none)
        profileEffectItem.setOnClickListener {
            sendResult(ACTION_PROFILE_EFFECT)
            dismiss()
        }
        parentFragmentManager.setFragmentResultListener(
            ProfileColorPickerDialog.REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, result ->
            if (result.getBoolean(ProfileColorPickerDialog.RESULT_HAS_COLOR)) {
                val color = result.getInt(ProfileColorPickerDialog.RESULT_COLOR)
                viewModel.updateColorPreview(colorHex(color))
                sendResult(ACTION_COLOR, color)
            } else {
                viewModel.updateColorPreview(null)
                sendResult(ACTION_COLOR, hasColor = false)
            }
        }
        if (isGuildContext) {
            changeImageTitle.setText(R.string.change_identity_profile_banner)
            removeImage.setText(R.string.change_identity_reset_banner)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.observeViewState().bindToComponentLifecycle(this)
            .appSubscribe(WidgetEditProfileBannerSheet::class.java, onNext = ::configureUi)
    }

    override fun onPause() {
        super.onPause()
        dismiss()
        colorPickerDialog?.dismiss()
        colorPickerDialog = null
    }

    private fun configureUi(state: EditProfileBannerSheetViewModel.ViewState) {
        val color = runCatching { Color.parseColor(state.bannerColorHex) }.getOrNull()
        colorPreview.visibility = if (color == null) View.INVISIBLE else View.VISIBLE
        color?.let { ImageViewCompat.setImageTintList(colorPreview, ColorStateList.valueOf(it)) }
        colorContainer.setOnClickListener {
            launchColorPicker(color ?: runCatching { Color.parseColor(defaultBannerColorHex) }.getOrDefault(Color.BLACK))
        }
        colorContainer.visibility = visible(!isGuildContext)
        premiumUpsell.visibility = visible(state.showPremiumUpsell)
        changeImageContainer.setOnClickListener {
            if (state.showPremiumUpsell) {
                launchPremium()
            } else {
                sendResult(ACTION_CHANGE_IMAGE)
                dismiss()
            }
        }
    }

    private fun launchPremium() {
        val manager = parentFragmentManager
        dismiss()
        PremiumUpsellDialog.show(
            manager,
            PremiumUpsellDialog.ValueProp.PROFILE_BANNER,
            PREMIUM_UPSELL_LOCATION,
        )
    }

    private fun launchColorPicker(initialColor: Int) {
        hideKeyboard()
        colorPickerDialog = ProfileColorPickerDialog.show(parentFragmentManager, initialColor)
    }

    private fun sendResult(action: String, color: Int = 0, hasColor: Boolean = true) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_ACTION to action, RESULT_COLOR to color, RESULT_HAS_COLOR to hasColor),
        )
    }

    private val isGuildContext get() = argumentsOrDefault.containsKey(EXTRA_GUILD_ID)

    companion object {
        private const val PREMIUM_UPSELL_LOCATION = "Profile Banner Sheet"
        private const val ARG_DEFAULT_BANNER_COLOR_HEX = "ARG_DEFAULT_BANNER_COLOR_HEX"
        private const val ARG_INITIAL_COLOR_PREVIEW_HEX = "ARG_INITIAL_COLOR_PREVIEW_HEX"
        private const val ARG_HAS_BANNER_IMAGE = "ARG_HAS_BANNER_IMAGE"
        private const val ARG_PROFILE_EFFECT_NAME = "ARG_PROFILE_EFFECT_NAME"
        private const val EXTRA_GUILD_ID = "com.discord.intent.extra.EXTRA_GUILD_ID"
        internal const val REQUEST_KEY = "EDIT_PROFILE_BANNER"
        internal const val RESULT_ACTION = "action"
        internal const val RESULT_COLOR = "color"
        internal const val RESULT_HAS_COLOR = "has_color"
        internal const val ACTION_CHANGE_IMAGE = "change_image"
        internal const val ACTION_REMOVE_IMAGE = "remove_image"
        internal const val ACTION_COLOR = "color"
        internal const val ACTION_PROFILE_EFFECT = "profile_effect"

        fun show(
            guildId: Long? = null,
            defaultBannerColorHex: String,
            initialColorPreviewHex: String?,
            hasBannerImage: Boolean,
            profileEffectName: String?,
            fragmentManager: FragmentManager,
        ) {
            WidgetEditProfileBannerSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_DEFAULT_BANNER_COLOR_HEX, defaultBannerColorHex)
                    putString(ARG_INITIAL_COLOR_PREVIEW_HEX, initialColorPreviewHex)
                    putBoolean(ARG_HAS_BANNER_IMAGE, hasBannerImage)
                    putString(ARG_PROFILE_EFFECT_NAME, profileEffectName)
                    guildId?.let { putLong(EXTRA_GUILD_ID, it) }
                }
            }.show(fragmentManager, WidgetEditProfileBannerSheet::class.java.name)
        }

        private fun visible(value: Boolean) = if (value) View.VISIBLE else View.GONE
        private fun colorHex(color: Int) = String.format("#%06X", color and 0xFFFFFF)
    }
}
