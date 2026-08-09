package com.discord.widgets.settings.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.Toast
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsStartupMetricsBinding
import com.discord.utilities.logging.StartupMetrics
import com.google.android.material.tabs.TabLayout

class WidgetSettingsStartupMetrics : AppFragment(R.layout.widget_settings_startup_metrics) {
    private var _binding: WidgetSettingsStartupMetricsBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var selectedTab = SUMMARY_TAB
    private var stopObserving: (() -> Unit)? = null
    private val renderReport = Runnable { _binding?.let { render() } }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsStartupMetricsBinding.bind(view)
        setActionBarTitle(R.string.startup_metrics_title)
        setActionBarDisplayHomeAsUpEnabled()
        binding.startupMetricsTabs.apply {
            addTab(newTab().setText(R.string.startup_metrics_summary))
            addTab(newTab().setText(R.string.startup_metrics_timeline))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    selectedTab = tab.position
                    render()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        binding.startupMetricsCopy.setOnClickListener {
            val report = binding.startupMetricsReport.text
            (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.startup_metrics_title), report))
            Toast.makeText(requireContext(), R.string.copied_text, Toast.LENGTH_SHORT).show()
        }
        stopObserving = StartupMetrics.observe {
            binding.root.removeCallbacks(renderReport)
            binding.root.postDelayed(renderReport, UPDATE_DELAY_MS)
        }
        render()
    }

    override fun onDestroyView() {
        stopObserving?.invoke()
        stopObserving = null
        _binding?.root?.removeCallbacks(renderReport)
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        binding.startupMetricsReport.text =
            if (selectedTab == SUMMARY_TAB) StartupMetrics.summary() else StartupMetrics.timeline()
    }

    companion object {
        private const val SUMMARY_TAB = 0
        private const val UPDATE_DELAY_MS = 100L

        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetSettingsStartupMetrics::class.java)
    }
}
