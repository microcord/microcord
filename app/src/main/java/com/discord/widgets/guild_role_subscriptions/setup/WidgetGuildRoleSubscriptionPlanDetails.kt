package com.discord.widgets.guild_role_subscriptions.setup

import android.net.Uri
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.Group
import androidx.core.os.bundleOf
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.appActivityViewModels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.setTextIfDifferent
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.widgets.guild_role_subscriptions.showRoleSubscriptionImageUploadDialog
import com.discord.views.VitoImageView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Step 2 of setup: the storefront's cover image and blurb.
 *
 * The cover has two sources — a freshly picked `data:` URL and an already-uploaded asset — and the
 * former always wins, so re-picking an image on an existing storefront shows the new one
 * immediately instead of the stale CDN copy.
 */
class WidgetGuildRoleSubscriptionPlanDetails : AppFragment(R.layout.widget_guild_role_subscription_plan_details) {

    private val viewModel: GuildRoleSubscriptionPlanDetailsViewModel by appActivityViewModels {
        GuildRoleSubscriptionPlanDetailsViewModel()
    }

    private lateinit var coverImage: VitoImageView
    private lateinit var editCoverImage: ImageView
    private lateinit var descriptionLayout: TextInputLayout

    private val coverImageSelectedResult: (String) -> Unit = { dataUrl ->
        viewModel.updateCoverImage(dataUrl)
    }

    private val hideHeader: Boolean
        get() = arguments?.getBoolean(INTENT_EXTRA_HIDE_HEADER, false) ?: false

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        coverImage = requireNotNull(view.findViewById(R.id.guild_role_subscription_plan_cover_image))
        editCoverImage = requireNotNull(view.findViewById(R.id.guild_subscription_plan_details_edit_cover_image))
        descriptionLayout = requireNotNull(view.findViewById(R.id.guild_role_subscription_plan_description_layout))

        requireNotNull<TextInputEditText>(view.findViewById(R.id.guild_role_subscription_plan_description))
            .addBindedTextWatcher(this) { viewModel.updateDescription(it.toString()) }
        coverImage.setOnClickListener { openMediaChooser() }
        requireNotNull<Group>(view.findViewById(R.id.guild_role_subscription_plan_details_header_group))
            .visibility = if (hideHeader) View.GONE else View.VISIBLE
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildRoleSubscriptionPlanDetails::class.java) { configureUI(it) }
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        showRoleSubscriptionImageUploadDialog(uri, mimeType, coverImageSelectedResult)
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(context, uri, mimeType, coverImageSelectedResult)
    }

    private fun configureUI(viewState: GuildRoleSubscriptionPlanDetailsViewModel.ViewState) {
        descriptionLayout.setTextIfDifferent(viewState.description)
        val imageUrl = when {
            !viewState.coverImage.isNullOrBlank() -> viewState.coverImage
            viewState.coverImageAssetId != null -> IconUtils.getStoreAssetImage(
                viewState.applicationId,
                viewState.coverImageAssetId.toString(),
                IconUtils.getMediaProxySize(coverImage.measuredWidth),
            )
            else -> null
        }
        MGImages.setImage(coverImage, imageUrl)
        // The pencil overlay only makes sense once there is an image under it.
        editCoverImage.visibility = if (imageUrl.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    companion object {
        const val INTENT_EXTRA_HIDE_HEADER = "INTENT_EXTRA_BENEFITS_HIDE_HEADER"

        @JvmOverloads
        fun newInstance(hideHeader: Boolean = false) = WidgetGuildRoleSubscriptionPlanDetails().apply {
            arguments = bundleOf(INTENT_EXTRA_HIDE_HEADER to hideHeader)
        }
    }
}
