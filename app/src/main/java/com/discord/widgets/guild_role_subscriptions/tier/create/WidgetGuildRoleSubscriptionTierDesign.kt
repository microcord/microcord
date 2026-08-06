package com.discord.widgets.guild_role_subscriptions.tier.create

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.widgets.guild_role_subscriptions.GuildSubscriptionRoleImageUploadView
import com.discord.widgets.guild_role_subscriptions.showRoleSubscriptionImageUploadDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import java.util.Locale

class WidgetGuildRoleSubscriptionTierDesign :
    AppFragment(R.layout.widget_guild_role_subscription_tier_design) {

    private val viewModel: GuildRoleSubscriptionTierViewModel by appActivityViewModels {
        GuildRoleSubscriptionTierViewModel()
    }

    private val hideHeader: Boolean
        get() = arguments?.getBoolean(INTENT_EXTRA_HIDE_HEADER, false) ?: false

    private val defaultColor: Int
        get() = ContextCompat.getColor(requireContext(), R.color.primary_300)

    private lateinit var colorSwatch: View
    private lateinit var colorLayout: View
    private lateinit var colorText: TextView
    private lateinit var memberIcon: GuildSubscriptionRoleImageUploadView
    private lateinit var lightPreview: GuildRoleSubscriptionMemberPreview
    private lateinit var darkPreview: GuildRoleSubscriptionMemberPreview

    private val memberBadgeImageSelectedResult: (String) -> Unit = { viewModel.updateMemberIcon(it) }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        colorSwatch = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_appearance_color))
        colorLayout = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_appearance_color_layout))
        colorText = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_appearance_color_text))
        memberIcon = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_member_icon))
        lightPreview = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_member_preview_light))
        darkPreview = requireNotNull(view.findViewById(R.id.guild_role_subscription_tier_member_preview_dark))

        memberIcon.configureUI(
            onImageRemoved = { viewModel.updateMemberIcon() },
            onImageChooserClicked = ::openMediaChooser,
        )
        viewModel.setDefaultMemberColor(defaultColor)

        val title = requireNotNull<TextView>(
            view.findViewById(R.id.guild_role_subscription_tier_appearance_title),
        )
        val subtitle = requireNotNull<TextView>(
            view.findViewById(R.id.guild_role_subscription_tier_appearance_subtitle),
        )
        title.visibility = if (hideHeader) View.GONE else View.VISIBLE
        subtitle.setPadding(0, if (hideHeader) dpToPx(16) else 0, 0, 0)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierDesign::class.java, onNext = ::configureUI)
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        showRoleSubscriptionImageUploadDialog(uri, mimeType, memberBadgeImageSelectedResult)
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, memberBadgeImageSelectedResult)
    }

    private fun configureUI(viewState: GuildRoleSubscriptionTierViewModel.ViewState) {
        val tier = viewState.guildRoleSubscriptionTier
        val color = tier.memberColor ?: defaultColor
        setupMemberColor(color)
        memberIcon.updateImage(tier.memberIcon, tier.guildRole)
        lightPreview.setMemberDesign(color, tier.memberIcon, tier.guildRole)
        darkPreview.setMemberDesign(color, tier.memberIcon, tier.guildRole)
    }

    private fun setupMemberColor(color: Int) {
        colorSwatch.background = requireNotNull(
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_rectangle_4dp_radius),
        ).mutate().apply {
            colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
        }
        colorLayout.setOnClickListener { launchColorPicker(color) }
        colorText.text = String.format(Locale.ROOT, "#%06X", color and 0xFFFFFF)
    }

    private fun launchColorPicker(currentColor: Int) {
        ColorPickerDialog.newBuilder()
            .setDialogTitle(R.string.guild_role_subscription_tier_design_member_color_label)
            .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
            .setColor(currentColor)
            .setShowAlphaSlider(false)
            .create()
            .apply {
                setColorPickerDialogListener(object : ColorPickerDialogListener {
                    override fun onColorSelected(dialogId: Int, color: Int) {
                        viewModel.updateMemberColor(color)
                    }

                    override fun onDialogDismissed(dialogId: Int) = Unit
                })
            }
            .show(parentFragmentManager, DIALOG_TAG_COLOR_PICKER)
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val DIALOG_TAG_COLOR_PICKER = "DIALOG_TAG_COLOR_PICKER"
        private const val INTENT_EXTRA_HIDE_HEADER = "INTENT_EXTRA_HIDE_HEADER"

        @JvmOverloads
        fun newInstance(hideHeader: Boolean = false) = WidgetGuildRoleSubscriptionTierDesign().apply {
            arguments = bundleOf(INTENT_EXTRA_HIDE_HEADER to hideHeader)
        }
    }
}
