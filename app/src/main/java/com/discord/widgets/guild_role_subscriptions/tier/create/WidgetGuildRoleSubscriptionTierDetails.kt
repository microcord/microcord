package com.discord.widgets.guild_role_subscriptions.tier.create

import android.net.Uri
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setCompoundDrawableWithIntrinsicBounds
import com.discord.utilities.view.extensions.setTextIfDifferent
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.guild_role_subscriptions.GuildSubscriptionRoleImageUploadView
import com.discord.widgets.guild_role_subscriptions.WidgetPriceTierPickerBottomSheet
import com.discord.widgets.guild_role_subscriptions.showRoleSubscriptionImageUploadDialog
import com.discord.widgets.stickers.StickerPackFormatting
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class WidgetGuildRoleSubscriptionTierDetails :
    AppFragment(R.layout.widget_guild_role_subscription_details) {

    private val viewModel: GuildRoleSubscriptionTierViewModel by appActivityViewModels {
        GuildRoleSubscriptionTierViewModel()
    }

    private val isEditMode: Boolean
        get() = arguments?.getBoolean(INTENT_IS_EDIT_MODE, false) ?: false

    private lateinit var imageUpload: GuildSubscriptionRoleImageUploadView
    private lateinit var nameLayout: TextInputLayout
    private lateinit var descriptionLayout: TextInputLayout
    private lateinit var price: TextView

    private val tierImageSelectedResult: (String) -> Unit = { viewModel.updateImage(it) }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        imageUpload = requireNotNull(view.findViewById(R.id.create_subscription_tier_image_upload_view))
        nameLayout = requireNotNull(view.findViewById(R.id.create_subscription_tier_name_layout))
        descriptionLayout = requireNotNull(view.findViewById(R.id.create_subscription_tier_description_layout))
        price = requireNotNull(view.findViewById(R.id.create_subscription_tier_price))

        WidgetPriceTierPickerBottomSheet.registerForResult(this, REQUEST_KEY_PRICE_TIER_PICKER) {
            viewModel.updatePriceTier(it)
        }
        imageUpload.configureUI(
            onImageRemoved = { viewModel.updateImage() },
            onImageChooserClicked = ::openMediaChooser,
        )
        requireNotNull<TextInputEditText>(view.findViewById(R.id.create_subscription_tier_name))
            .addBindedTextWatcher(this) { viewModel.updateTierName(it.toString()) }
        requireNotNull<TextInputEditText>(view.findViewById(R.id.create_subscription_tier_description))
            .addBindedTextWatcher(this) { viewModel.updateDescription(it.toString()) }

        val headerVisibility = if (isEditMode) View.GONE else View.VISIBLE
        requireNotNull<View>(view.findViewById(R.id.create_subscription_tier_title)).visibility = headerVisibility
        requireNotNull<View>(view.findViewById(R.id.create_subscription_tier_subtitle)).visibility = headerVisibility
        requireNotNull<View>(view.findViewById(R.id.guild_role_subscription_tier_detail_divider)).visibility =
            headerVisibility
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .distinctUntilChanged()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionTierDetails::class.java, onNext = ::configureUI)
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        showRoleSubscriptionImageUploadDialog(uri, mimeType, tierImageSelectedResult)
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, tierImageSelectedResult)
    }

    private fun configureUI(viewState: GuildRoleSubscriptionTierViewModel.ViewState) {
        val tier = viewState.guildRoleSubscriptionTier
        nameLayout.setTextIfDifferent(tier.name)
        descriptionLayout.setTextIfDifferent(tier.description)
        imageUpload.updateImage(tier.image, null)
        price.text = tier.priceTier?.let {
            StickerPackFormatting.formattedPriceUsd(it, requireContext())
        } ?: getString(R.string.guild_role_subscription_tier_detail_price_placeholder)

        if (tier.isPublished == true) {
            price.setOnClickListener(null)
            price.setCompoundDrawableWithIntrinsicBounds(end = R.drawable.ic_lock_white_a60_16dp)
        } else {
            price.setCompoundDrawableWithIntrinsicBounds(end = R.drawable.ic_chevron_down_grey_12dp)
            price.setOnClickListener {
                WidgetPriceTierPickerBottomSheet.launch(this, REQUEST_KEY_PRICE_TIER_PICKER)
            }
        }
        price.isSelected = tier.priceTier != null && tier.isPublished != true
    }

    companion object {
        private const val INTENT_IS_EDIT_MODE = "INTENT_IS_EDIT_MODE"
        private const val REQUEST_KEY_PRICE_TIER_PICKER = "REQUEST_KEY_PRICE_TIER_PICKER"

        @JvmOverloads
        fun newInstance(isEditMode: Boolean = false) = WidgetGuildRoleSubscriptionTierDetails().apply {
            arguments = bundleOf(INTENT_IS_EDIT_MODE to isEditMode)
        }
    }
}
