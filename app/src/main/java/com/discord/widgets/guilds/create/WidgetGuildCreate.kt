package com.discord.widgets.guilds.create

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.view.View
import android.widget.Toast
import androidx.core.os.BundleCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetGuildCreateBinding
import com.discord.i18n.setI18nText
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.view.extensions.setTextIfDifferent
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.widgets.guilds.invite.WidgetGuildInviteShare
import com.discord.widgets.nux.WidgetNuxChannelPrompt

open class WidgetGuildCreate(
    layoutResId: Int = R.layout.widget_guild_create,
) : AppFragment(layoutResId) {

    data class Options(
        val analyticsLocation: String,
        val template: StockGuildTemplate = StockGuildTemplate.CREATE,
        val showChannelPrompt: Boolean = false,
        val customTitle: String? = null,
        val closeWithResult: Boolean = false,
    ) : Parcelable {
        constructor(parcel: Parcel) : this(
            analyticsLocation = parcel.readString().orEmpty(),
            template = StockGuildTemplate.valueOf(parcel.readString().orEmpty()),
            showChannelPrompt = parcel.readInt() != 0,
            customTitle = parcel.readString(),
            closeWithResult = parcel.readInt() != 0,
        )

        override fun writeToParcel(parcel: Parcel, flags: Int) {
            parcel.writeString(analyticsLocation)
            parcel.writeString(template.name)
            parcel.writeInt(if (showChannelPrompt) 1 else 0)
            parcel.writeString(customTitle)
            parcel.writeInt(if (closeWithResult) 1 else 0)
        }

        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<Options> {
            override fun createFromParcel(parcel: Parcel) = Options(parcel)
            override fun newArray(size: Int): Array<Options?> = arrayOfNulls(size)
        }
    }

    data class Result(val guildId: Long) : Parcelable {
        constructor(parcel: Parcel) : this(parcel.readLong())
        override fun writeToParcel(parcel: Parcel, flags: Int) = parcel.writeLong(guildId)
        override fun describeContents() = 0

        companion object CREATOR : Parcelable.Creator<Result> {
            override fun createFromParcel(parcel: Parcel) = Result(parcel)
            override fun newArray(size: Int): Array<Result?> = arrayOfNulls(size)
        }
    }

    protected val options: Options by lazy {
        arguments?.let { BundleCompat.getParcelable(it, EXTRA_OPTIONS, Options::class.java) }
            ?: mostRecentIntent.extras?.let { BundleCompat.getParcelable(it, EXTRA_OPTIONS, Options::class.java) }
            ?: error("Missing guild creation options")
    }

    protected val viewModel: WidgetGuildCreateViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = createViewModelFactory() as T
        }
    }

    private var currentImageUri: String? = null

    protected open val views: GuildCreateCloneViews
        get() = GuildCreateCloneViews.from(WidgetGuildCreateBinding.bind(requireView()))

    protected open fun createViewModelFactory() = WidgetGuildCreateViewModel(
        defaultGuildNameFormatRes = R.string.create_server_default_server_name_format,
        stockGuildTemplate = options.template,
        guildTemplateCode = null,
        showChannelPrompt = options.showChannelPrompt,
        analyticsLocation = options.analyticsLocation,
        customTitle = options.customTitle,
        closeWithResult = options.closeWithResult,
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled()
        val views = views

        views.guildCreateIconUploader.setOnClickListener {
            hideKeyboard(views.guildCreateName)
            openMediaChooser()
        }
        views.guildCreateName.editText?.doAfterTextChanged { viewModel.updateGuildName(it?.toString().orEmpty()) }
        views.guildCreateButton.setOnClickListener {
            viewModel.createGuild(requireContext())
            hideKeyboard(view)
        }
        (views.guidelinesTextView as LinkifiedTextView)
            .setI18nText(R.string.create_server_guidelines, "guidelinesURL" to GUIDELINES_URL)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildCreate::class.java, context, onNext = ::configureUi)
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetGuildCreate::class.java, context, onNext = ::handleEvent)
    }

    override fun onImageChosen(uri: Uri, mimeType: String) {
        super.onImageChosen(uri, mimeType)
        MGImages.requestDataUrl(requireContext(), uri, mimeType, viewModel::updateGuildIconUri)
    }

    override fun onImageCropped(uri: Uri, mimeType: String) {
        super.onImageCropped(uri, mimeType)
        MGImages.requestDataUrl(requireContext(), uri, mimeType, viewModel::updateGuildIconUri)
    }

    protected open fun configureUi(state: WidgetGuildCreateViewModel.ViewState) {
        val initialized = state as? WidgetGuildCreateViewModel.ViewState.Initialized ?: return
        val views = views
        views.guildCreateName.setTextIfDifferent(initialized.getGuildName(requireContext()))
        if (currentImageUri != initialized.guildIconUri) {
            currentImageUri = initialized.guildIconUri
            views.iconUploaderPlaceholderGroup.visibility = if (currentImageUri == null) View.VISIBLE else View.GONE
            IconUtils.setIcon(views.iconUploaderImage, currentImageUri, R.dimen.avatar_size_xxlarge)
        }
        initialized.customTitle?.let { views.screenTitleView?.setTitle(it) }
        views.guildCreateButton.setIsLoading(initialized.isBusy)
    }

    private fun handleEvent(event: WidgetGuildCreateViewModel.Event) {
        when (event) {
            is WidgetGuildCreateViewModel.Event.ShowToast ->
                Toast.makeText(requireContext(), event.stringResId, Toast.LENGTH_SHORT).show()
            is WidgetGuildCreateViewModel.Event.LaunchChannelPrompt -> {
                WidgetNuxChannelPrompt.launch(requireContext(), event.guildId)
                requireActivity().finish()
            }
            is WidgetGuildCreateViewModel.Event.LaunchInviteShareScreen -> {
                WidgetGuildInviteShare.launch(
                    requireContext(),
                    parentFragmentManager,
                    event.guildId,
                    source = ANALYTICS_SOURCE,
                )
                requireActivity().finish()
            }
            is WidgetGuildCreateViewModel.Event.CloseWithResult -> {
                requireActivity().setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(INTENT_ARGS_KEY, Result(event.guildId)),
                )
                requireActivity().finish()
            }
        }
    }

    companion object {
        const val ANALYTICS_SOURCE = "Guild Create"
        const val EXTRA_OPTIONS = "com.discord.intent.extra.EXTRA_OPTIONS"
        private const val INTENT_ARGS_KEY = "intent_args_key"
        private const val GUIDELINES_URL = "https://discord.com/guidelines"

        fun show(context: Context, options: Options) {
            AppScreenRouter.launch(
                context,
                WidgetGuildCreate::class.java,
                Intent().putExtra(EXTRA_OPTIONS, options),
            )
        }

        fun showFragment(fragment: AppFragment, options: Options) {
            AppScreenRouter.replaceFragment(
                fragment.parentFragmentManager,
                fragment.requireContext(),
                WidgetGuildCreate::class.java,
                R.id.app_root,
                addToBackStack = true,
                arguments = Bundle().apply { putParcelable(EXTRA_OPTIONS, options) },
            )
        }

    }
}
