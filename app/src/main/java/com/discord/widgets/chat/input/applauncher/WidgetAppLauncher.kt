package com.discord.widgets.chat.input.applauncher

import android.content.DialogInterface
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetAppLauncherBinding
import com.discord.models.commands.ApplicationCommand
import com.discord.utilities.rx.ui
import com.discord.widgets.applicationdirectory.ApplicationInstallCoordinator
import com.discord.widgets.auth.WidgetOauth2Authorize
import com.discord.widgets.notice.WidgetNoticeDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import rx.subscriptions.CompositeSubscription

class WidgetAppLauncher : AppBottomSheet() {
    private val oauthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        when (WidgetOauth2Authorize.completionResult(result.data?.getStringExtra(WidgetOauth2Authorize.RESULT_COMPLETION))) {
            WidgetOauth2Authorize.CompletionResult.SUCCESS,
            WidgetOauth2Authorize.CompletionResult.EXTERNAL -> dismiss()
            WidgetOauth2Authorize.CompletionResult.ERROR -> WidgetNoticeDialog.Builder(requireContext())
                .setMessage(R.string.application_directory_install_error)
                .setPositiveButton(android.R.string.ok)
                .setCancelable(true)
                .show(parentFragmentManager)
            WidgetOauth2Authorize.CompletionResult.CANCELLED -> Unit
        }
    }
    fun interface ActivityLauncher {
        fun launch(host: Fragment, channelId: Long, application: ApplicationDirectoryApplication): Boolean
    }

    private lateinit var binding: WidgetAppLauncherBinding
    private lateinit var adapter: AppLauncherAdapter
    private lateinit var viewModel: AppLauncherViewModel
    private var closeReason = AppLauncherCloseReason.DISMISSED
    private var closeResultSent = false
    private val entrypoint: AppLauncherEntrypoint
        get() = AppLauncherEntrypoint.entries.getOrElse(argumentsOrDefault.getInt(ARG_ENTRYPOINT)) {
            AppLauncherEntrypoint.TEXT
        }
    private val channelId get() = argumentsOrDefault.getLong(ARG_CHANNEL_ID)

    override fun getContentViewResId() = R.layout.widget_app_launcher

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = WidgetAppLauncherBinding.bind(view)
        viewModel = ViewModelProvider(
            this,
            AppLauncherViewModel.Factory(channelId, entrypoint),
        )[AppLauncherViewModel::class.java]
        adapter = AppLauncherAdapter(
            viewModel::selectApplication,
            ::onActivitySelected,
            viewModel::selectCommand,
            viewModel::showViewAll,
        )
        binding.appLauncherList.adapter = adapter
        binding.appLauncherList.itemAnimator = null
        binding.appLauncherList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                viewModel.setSearchPinnedDivider(recyclerView.computeVerticalScrollOffset() > PINNED_DIVIDER_OFFSET_PX)
            }
        })
        binding.appLauncherSearch.hint = getString(
            if (entrypoint == AppLauncherEntrypoint.VOICE) R.string.app_launcher_search_activities
            else R.string.app_launcher_search_apps,
        )
        binding.appLauncherSearch.doAfterTextChanged { viewModel.setQuery(it?.toString().orEmpty()) }
        binding.appLauncherBack.setOnClickListener { if (!viewModel.navigateBack()) dismiss() }
        binding.appLauncherClose.setOnClickListener { dismiss() }
        binding.appLauncherAction.setOnClickListener { onPrimaryAction(viewModel.currentState()) }

        val restoredQuery = savedInstanceState?.getString(STATE_QUERY).orEmpty()
        if (restoredQuery.isNotEmpty()) binding.appLauncherSearch.setText(restoredQuery)
        viewModel.restore(
            savedInstanceState?.getString(STATE_ROUTE),
            savedInstanceState?.getLong(STATE_APPLICATION_ID)?.takeUnless { it == 0L },
            savedInstanceState?.getString(STATE_COMMAND_ID),
        )
        if (entrypoint == AppLauncherEntrypoint.VOICE) {
            setBottomSheetState(BottomSheetBehavior.STATE_EXPANDED)
        }
    }

    override fun onResume() {
        super.onResume()
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (!viewModel.navigateBack()) dismiss()
                true
            } else {
                false
            }
        }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        compositeSubscription.add(viewModel.observeState().ui(this).subscribe(::render))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = viewModel.currentState()
        outState.putString(STATE_ROUTE, state.route.routeName)
        outState.putString(STATE_QUERY, state.query)
        outState.putLong(STATE_APPLICATION_ID, state.selectedApplication?.id ?: 0L)
        outState.putString(STATE_COMMAND_ID, state.selectedCommand?.id)
        super.onSaveInstanceState(outState)
    }

    override fun onDismiss(dialog: DialogInterface) {
        sendCloseResult()
        super.onDismiss(dialog)
    }

    private fun render(state: AppLauncherViewState) {
        adapter.setData(state.rows)
        binding.appLauncherTitle.text = state.titleRes?.let(::getString)
            ?: state.title.ifBlank { getString(R.string.apps) }
        binding.appLauncherBack.visibility = if (state.route == AppLauncherRoute.HOME) View.INVISIBLE else View.VISIBLE
        binding.appLauncherSearchWrap.isVisible = state.route == AppLauncherRoute.HOME
        binding.appLauncherSearchDivider.isVisible = state.route == AppLauncherRoute.HOME && state.searchPinnedDivider
        binding.appLauncherAction.visibility = when (state.route) {
            AppLauncherRoute.COMMAND_VIEW -> View.VISIBLE
            AppLauncherRoute.APPLICATION_VIEW -> if (state.selectedApplication?.directoryApplication != null) View.VISIBLE else View.GONE
            else -> View.GONE
        }
        binding.appLauncherAction.setText(
            getString(
                when {
                    state.route == AppLauncherRoute.COMMAND_VIEW -> R.string.app_launcher_use_command
                    state.selectedApplication?.searchResultType == AppLauncherSearchResultType.ACTIVITY_APPLICATION ->
                        R.string.app_launcher_launch_activity
                    else -> R.string.app_launcher_add_app
                },
            ),
        )
        binding.appLauncherAction.setIsLoading(false)
    }

    private fun onPrimaryAction(state: AppLauncherViewState) {
        when (state.route) {
            AppLauncherRoute.COMMAND_VIEW -> state.selectedCommand?.let(::selectCommand)
            AppLauncherRoute.APPLICATION_VIEW -> state.selectedApplication?.let { selected ->
                if (selected.searchResultType == AppLauncherSearchResultType.ACTIVITY_APPLICATION) {
                    onActivitySelected(selected)
                } else {
                    selected.directoryApplication?.let {
                        ApplicationInstallCoordinator.install(requireContext(), parentFragmentManager, it, oauthLauncher)
                    }
                }
            }
            else -> Unit
        }
    }

    private fun selectCommand(command: ApplicationCommand) {
        closeReason = AppLauncherCloseReason.COMMAND
        setFragmentResult(
            RESULT_REQUEST_KEY,
            bundleOf(
                RESULT_COMMAND_ID to command.id,
                RESULT_APPLICATION_ID to command.applicationId,
            ),
        )
        dismiss()
    }

    private fun onActivitySelected(application: AppLauncherApplication) {
        val directoryApplication = application.directoryApplication
        if (directoryApplication != null && activityLauncher?.launch(this, channelId, directoryApplication) == true) {
            closeReason = AppLauncherCloseReason.ACTIVITY
            dismiss()
        } else {
            viewModel.selectApplication(application)
        }
    }

    private fun sendCloseResult() {
        if (closeResultSent) return
        closeResultSent = true
        parentFragmentManager.setFragmentResult(
            CLOSE_RESULT_REQUEST_KEY,
            bundleOf(CLOSE_RESULT_REASON to closeReason.ordinal),
        )
    }

    companion object {
        private const val ARG_CHANNEL_ID = "app_launcher_channel_id"
        private const val ARG_ENTRYPOINT = "app_launcher_entrypoint"
        private const val STATE_ROUTE = "app_launcher_route"
        private const val STATE_QUERY = "app_launcher_query"
        private const val STATE_APPLICATION_ID = "app_launcher_application_id"
        private const val STATE_COMMAND_ID = "app_launcher_command_id"
        private const val PINNED_DIVIDER_OFFSET_PX = 5

        const val RESULT_REQUEST_KEY = "app_launcher_command_selected"
        const val RESULT_COMMAND_ID = "app_launcher_command_id"
        const val RESULT_APPLICATION_ID = "app_launcher_application_id"
        const val CLOSE_RESULT_REQUEST_KEY = "app_launcher_closed"
        const val CLOSE_RESULT_REASON = "app_launcher_close_reason"

        @JvmField
        var activityLauncher: ActivityLauncher? = ActivityLauncher { host, channelId, application ->
            EmbeddedActivityLauncher.launch(host.requireContext(), application.id, channelId)
            true
        }

        fun show(
            manager: FragmentManager,
            channelId: Long,
            entrypoint: AppLauncherEntrypoint = AppLauncherEntrypoint.TEXT,
        ) = WidgetAppLauncher().apply {
            arguments = bundleOf(
                ARG_CHANNEL_ID to channelId,
                ARG_ENTRYPOINT to entrypoint.ordinal,
            )
        }.show(manager, WidgetAppLauncher::class.java.name)
    }
}
