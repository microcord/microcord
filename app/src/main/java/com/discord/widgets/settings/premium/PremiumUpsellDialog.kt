package com.discord.widgets.settings.premium

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.i18n.setI18nText
import com.discord.views.premium.ShinyButton
import com.google.android.material.button.MaterialButton

/**
 * The "Wanna upload your own banner?" modal a locked premium control opens.
 *
 * A locked control states what Nitro would unlock before sending anyone to the store, so each
 * [ValueProp] pairs the artwork with the copy for one feature.
 */
class PremiumUpsellDialog : AppDialog(R.layout.single_value_prop_premium_upsell_dialog) {

    enum class ValueProp(
        @DrawableRes val image: Int,
        @StringRes val header: Int,
        @StringRes val body: Int,
    ) {
        PROFILE_BANNER(
            R.drawable.img_profile_banner_value_prop,
            R.string.premium_profile_customization_upsell_header_mobile,
            R.string.premium_profile_customization_upsell_body_mobile,
        ),
        GUILD_IDENTITY(
            R.drawable.img_profile_banner_value_prop,
            R.string.guild_identity_upsell_native_modal_title,
            R.string.premium_profile_customization_upsell_body_mobile,
        ),
    }

    private val valueProp: ValueProp
        get() = ValueProp.values()[argumentsOrDefault.getInt(ARG_VALUE_PROP)]

    override fun onStart() {
        super.onStart()
        // The layout is a fixed-width card; without this the window shrinks to the artwork.
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val prop = valueProp
        view.findViewById<ImageView>(R.id.value_prop_image).setImageResource(prop.image)
        view.findViewById<TextView>(R.id.upsell_header_text).setText(prop.header)
        view.findViewById<TextView>(R.id.upsell_body_text).setI18nText(prop.body)
        view.findViewById<MaterialButton>(R.id.close_button).setOnClickListener { dismiss() }
        view.findViewById<ShinyButton>(R.id.get_premium_button).setOnClickListener {
            val context = requireContext()
            dismiss()
            WidgetSettingsPremium.launch(
                context,
                locationSection = argumentsOrDefault.getString(ARG_LOCATION),
            )
        }
    }

    companion object {
        private const val ARG_VALUE_PROP = "value_prop"
        private const val ARG_LOCATION = "location"
        private const val TAG = "PremiumUpsellDialog"

        fun show(fragmentManager: FragmentManager, valueProp: ValueProp, location: String) {
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            PremiumUpsellDialog().apply {
                arguments = bundleOf(ARG_VALUE_PROP to valueProp.ordinal, ARG_LOCATION to location)
            }.show(fragmentManager, TAG)
        }
    }
}
