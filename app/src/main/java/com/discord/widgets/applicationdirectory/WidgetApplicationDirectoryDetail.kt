package com.discord.widgets.applicationdirectory

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import com.discord.R
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.app.AppViewModel
import com.discord.app.appViewModels
import com.discord.databinding.WidgetApplicationDirectoryDetailBinding
import com.discord.stores.StoreStream
import com.discord.stores.StoreApplicationDirectory
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.intent.ExternalUriPolicy
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.utilities.uri.UriHandler
import com.discord.widgets.auth.WidgetOauth2Authorize
import com.discord.widgets.notice.WidgetNoticeDialog
import java.text.NumberFormat

class WidgetApplicationDirectoryDetail : AppFragment(R.layout.widget_application_directory_detail) {
    private val oauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (WidgetOauth2Authorize.completionResult(result.data?.getStringExtra(WidgetOauth2Authorize.RESULT_COMPLETION))) {
            WidgetOauth2Authorize.CompletionResult.SUCCESS,
            WidgetOauth2Authorize.CompletionResult.EXTERNAL -> showInstallResult(R.string.application_directory_install_success)
            WidgetOauth2Authorize.CompletionResult.ERROR -> showInstallResult(R.string.application_directory_install_error)
            WidgetOauth2Authorize.CompletionResult.CANCELLED -> Unit
        }
    }
    private val applicationId: Long by lazy {
        arguments?.getLong(ARG_APPLICATION_ID)?.takeIf { it > 0L }
            ?: mostRecentIntent.getLongExtra(ARG_APPLICATION_ID, 0L)
    }
    private val viewModel: ApplicationDirectoryDetailViewModel by appViewModels {
        ApplicationDirectoryDetailViewModel(applicationId)
    }
    private var binding: WidgetApplicationDirectoryDetailBinding? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val binding = WidgetApplicationDirectoryDetailBinding.bind(view).also { this.binding = it }
        binding.toolbar.setNavigationOnClickListener { activity?.onBackPressed() }
        binding.retry.setOnClickListener { viewModel.retry() }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState().ui(this).appSubscribe(javaClass) { render(it) }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun render(state: StoreApplicationDirectory.DetailState) {
        val binding = binding ?: return
        val loading = state is StoreApplicationDirectory.DetailState.Loading
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        if (loading) binding.progress.start() else binding.progress.stop()
        binding.retry.visibility = if (state is StoreApplicationDirectory.DetailState.Error) View.VISIBLE else View.GONE
        binding.retry.setIsLoading(false)
        binding.content.visibility = if (state is StoreApplicationDirectory.DetailState.Loaded) View.VISIBLE else View.GONE
        if (state !is StoreApplicationDirectory.DetailState.Loaded) return
        val application = state.application
        binding.toolbar.title = application.name
        binding.name.text = application.name
        binding.description.text = application.directoryEntry?.detailedDescription
            ?.takeIf(String::isNotBlank)
            ?: application.directoryEntry?.shortDescription?.takeIf(String::isNotBlank)
            ?: application.description
        val installs = application.approximateUserInstallCount ?: application.approximateGuildCount
        binding.metadata.text = buildList {
            if (application.isVerified) add(getString(R.string.application_directory_verified))
            installs?.let {
                add(getString(R.string.application_directory_installs, NumberFormat.getIntegerInstance().format(it)))
            }
        }.joinToString(" / ")
        MGImages.setImage(
            binding.icon,
            application.icon?.let { IconUtils.getApplicationIcon(application.id, it) },
        )
        binding.addApp.isEnabled = application.customInstallUrl != null || application.supportedIntegrationTypes.isNotEmpty()
        binding.addApp.setIsLoading(false)
        binding.addApp.setOnClickListener {
            ApplicationInstallCoordinator.install(requireContext(), parentFragmentManager, application, oauthLauncher)
        }
        bindExternal(binding.privacy, application.privacyPolicyUrl)
        bindExternal(binding.terms, application.termsOfServiceUrl)
    }

    private fun bindExternal(button: View, url: String?) {
        (button as? com.discord.views.LoadingButton)?.setIsLoading(false)
        button.visibility = if (url != null && ExternalUriPolicy.isAllowed(url)) View.VISIBLE else View.GONE
        button.setOnClickListener { if (url != null) UriHandler.handle(requireContext(), url) }
    }

    private fun showInstallResult(message: Int) {
        WidgetNoticeDialog.Builder(requireContext())
            .setMessage(message)
            .setPositiveButton(android.R.string.ok)
            .setCancelable(true)
            .show(parentFragmentManager)
    }

    companion object {
        private const val ARG_APPLICATION_ID = "application_id"

        fun launch(context: Context, applicationId: Long) = AppScreenRouter.launch(
            context,
            WidgetApplicationDirectoryDetail::class.java,
            Intent().putExtra(ARG_APPLICATION_ID, applicationId),
        )
    }
}

class ApplicationDirectoryDetailViewModel(
    private val applicationId: Long,
    private val store: StoreApplicationDirectory = StoreStream.getApplicationDirectory(),
) : AppViewModel<StoreApplicationDirectory.DetailState>(StoreApplicationDirectory.DetailState.Loading) {
    init {
        store.observeState().map { it.details[applicationId] ?: StoreApplicationDirectory.DetailState.Loading }
            .distinctUntilChanged()
            .ui(this)
            .subscribe(::updateViewState)
        store.loadApplication(applicationId)
    }

    fun retry() = store.loadApplication(applicationId, force = true)
}
