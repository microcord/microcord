package com.discord.widgets.mobile_reports

import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.api.report.NodeElementResult
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.utilities.view.extensions.fadeIn
import com.discord.utilities.view.extensions.fadeOut
import java.lang.ref.WeakReference

class WidgetMobileReports : AppFragment(R.layout.widget_mobile_reports) {

    private val reportArgs: MobileReportArgs by lazy {
        requireNotNull(readArgs()) { "WidgetMobileReports requires report arguments" }
    }

    private val viewModel: MobileReportsViewModel by lazy {
        ViewModelProvider(
            this,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MobileReportsViewModel(WeakReference(requireContext()), reportArgs) as T
            },
        )[MobileReportsViewModel::class.java]
    }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarDisplayHomeAsUpEnabled(false)
        setOnBackPressed(viewModel::handleBack)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetMobileReports::class.java, context = context) { state ->
                when (state) {
                    is MobileReportsViewModel.ViewState.Menu -> configureUI(state)
                    is MobileReportsViewModel.ViewState.Invalid -> appActivity?.finish()
                }
            }
    }

    private fun configureUI(state: MobileReportsViewModel.ViewState.Menu) {
        val root = view ?: return
        setActionBarDisplayHomeAsUpEnabled(!state.shouldHideBackArrow())
        root.findViewById<ProgressBar>(R.id.mobile_reports_progress_bar).visibility = View.GONE
        val holder = root.findViewById<FrameLayout>(R.id.menu_reports_node_view_holder)
        val current = holder.getChildAt(0) as? ReportsMenuNode
        val nodeState = state.genNodeState()
        if (current == null) {
            holder.addView(createNodeView(state).also { it.visibility = View.GONE })
            holder.getChildAt(0).fadeIn(0L, onAnimationEnd = {
                setActionBarDisplayHomeAsUpEnabled(!state.shouldHideBackArrow())
            })
        } else if (current.viewState?.node != nodeState.node) {
            val next = createNodeView(state).also { it.visibility = View.GONE }
            current.fadeOut(0L, onAnimationEnd = {
                holder.removeAllViewsInLayout()
                holder.addView(next)
                next.fadeIn(0L, onAnimationEnd = {
                    setActionBarDisplayHomeAsUpEnabled(!state.shouldHideBackArrow())
                })
            })
        } else {
            current.setup(nodeState)
            setActionBarDisplayHomeAsUpEnabled(!state.shouldHideBackArrow())
        }
    }

    private fun createNodeView(state: MobileReportsViewModel.ViewState.Menu) =
        ReportsMenuNode(requireContext()).apply {
            handleSelectChild = { destination, nodeState ->
                val checkbox = nodeState.checkboxElement
                viewModel.handleNext(
                    destination,
                    checkbox?.let { NodeElementResult(it.name, it.selections.toList()) },
                )
            }
            handleBlock = viewModel::handleBlockUser
            handleCancel = { appActivity?.finish() }
            handleSubmit = viewModel::handleSubmit
            setup(state.genNodeState())
        }

    @Suppress("DEPRECATION")
    private fun readArgs(): MobileReportArgs? {
        val bundle = arguments ?: mostRecentIntent.extras
        return if (Build.VERSION.SDK_INT >= 33) {
            bundle?.getParcelable(INTENT_ARGS_KEY, MobileReportArgs::class.java)
        } else {
            bundle?.getParcelable(INTENT_ARGS_KEY)
        }
    }

    companion object {
        private const val INTENT_ARGS_KEY = "intent_args_key"

        @JvmStatic
        fun launchMessageReport(context: Context, messageId: Long, channelId: Long) =
            launch(context, MobileReportArgs.Message(messageId, channelId))

        @JvmStatic
        fun launchStageChannelReport(context: Context, channelId: Long) =
            launch(context, MobileReportArgs.StageChannel(channelId))

        @JvmStatic
        fun launchDirectoryServerReport(
            context: Context,
            guildId: Long,
            hubId: Long,
            channelId: Long,
        ) = launch(context, MobileReportArgs.DirectoryServer(guildId, hubId, channelId))

        @JvmStatic
        fun launchGuildScheduledEventReport(context: Context, guildId: Long, eventId: Long) =
            launch(context, MobileReportArgs.GuildScheduledEvent(guildId, eventId))

        private fun launch(context: Context, args: MobileReportArgs) {
            AppScreenRouter.launch(
                context,
                WidgetMobileReports::class.java,
                Intent().putExtra(INTENT_ARGS_KEY, args),
            )
        }
    }
}
