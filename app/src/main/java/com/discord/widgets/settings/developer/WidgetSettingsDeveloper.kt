package com.discord.widgets.settings.developer

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.premium.PremiumTier
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsDeveloperBinding
import com.discord.stores.StoreDebugOptions
import com.discord.stores.StoreStream
import com.discord.utilities.developer.DeveloperSettings
import com.discord.utilities.experiments.ExperimentRegistry
import com.discord.utilities.logging.AppGatewaySocketLogger
import com.discord.utilities.logging.AppRestRequestLogger
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.hubs.RadioSelectorItem
import com.discord.widgets.hubs.RadioSelectorItems
import com.discord.widgets.hubs.WidgetRadioSelectorBottomSheet
import com.hammerandchisel.libdiscord.Discord
import java.text.DateFormat
import java.util.Date

class WidgetSettingsDeveloper : AppFragment(R.layout.widget_settings_developer) {
    private lateinit var experiments: ExperimentOverridesAdapter
    private lateinit var notices: NoticeAdapter
    private var _binding: WidgetSettingsDeveloperBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsDeveloperBinding.bind(view)
        setActionBarTitle(R.string.developer_options)
        setActionBarDisplayHomeAsUpEnabled()
        experiments = ExperimentOverridesAdapter()
        notices = NoticeAdapter(StoreStream.getNotices()::clearSeen)
        binding.developerSettingsExperiments.adapter = experiments
        val noticesView = binding.developerSettingsNotices
        noticesView.adapter = notices
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                (viewHolder as? NoticeViewHolder)?.noticeName?.let(StoreStream.getNotices()::clearSeen)
            }
        }).attachToRecyclerView(noticesView)

        val debugOptions = StoreStream.getDebugOptions()
        binding.settingsDeveloperForceCanary.apply {
            isChecked = debugOptions.isEnabled(StoreDebugOptions.Option.CANARY)
            setOnCheckedListener { debugOptions.setEnabled(StoreDebugOptions.Option.CANARY, it) }
        }
        binding.settingsDeveloperVerboseBoot.apply {
            isChecked = DeveloperSettings.isVerboseBootEnabled()
            setOnCheckedListener(DeveloperSettings::setVerboseBootEnabled)
        }
        binding.settingsDeveloperLogGatewayEvents.apply {
            isChecked = AppGatewaySocketLogger.isEnabled()
            setOnCheckedListener(AppGatewaySocketLogger::setEnabled)
        }
        binding.settingsDeveloperLogRestRequests.apply {
            isChecked = AppRestRequestLogger.isEnabled()
            setOnCheckedListener(AppRestRequestLogger::setEnabled)
        }
        binding.settingsDeveloperStartupMetrics.setOnClickListener {
            WidgetSettingsStartupMetrics.launch(it.context)
        }
        binding.settingsDeveloperNitroOverride.apply {
            text = premiumTierLabel(DeveloperSettings.premiumTierOverride())
            setOnClickListener { showPremiumTierSelector() }
        }
        binding.developerSettingsCrashKotlin.setOnClickListener {
            throw RuntimeException("This is a developer triggered crash.")
        }
        binding.developerSettingsCrashDiscordNonfatal.setOnClickListener {
            runCatching { throw RuntimeException("This is a developer triggered crash (caught).") }
                .onFailure { AppLog.e("setupCrashes", it) }
        }
        binding.developerSettingsCrashDiscordJnibridge.setOnClickListener {
            Discord(requireContext()).crash()
        }
        binding.developerSettingsSimulateGatewayReconnect.setOnClickListener {
            StoreStream.getGatewayConnection().simulateReconnectForTesting()
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showPremiumTierSelector() {
        val current = DeveloperSettings.premiumTierOverride()
        val items = listOf(RadioSelectorItem(DeveloperSettings.ACTUAL_TIER, "Actual account tier", current == null)) +
            PremiumTier.entries.map { tier -> RadioSelectorItem(tier.ordinal, premiumTierLabel(tier), tier == current) }
        WidgetRadioSelectorBottomSheet.show(
            parentFragmentManager,
            RadioSelectorItems("Premium tier override", items),
        ) { id ->
            val tier = PremiumTier.entries.getOrNull(id)
            DeveloperSettings.setPremiumTierOverride(tier)
            StoreStream.getUsers().refreshDeveloperOverrides()
            if (_binding != null) binding.settingsDeveloperNitroOverride.text = premiumTierLabel(tier)
            (parentFragmentManager.findFragmentByTag(WidgetRadioSelectorBottomSheet::class.java.name)
                as? WidgetRadioSelectorBottomSheet)?.dismiss()
        }
    }

    private fun premiumTierLabel(tier: PremiumTier?): String = "Premium tier: " + when (tier) {
        null -> "Actual account tier"
        PremiumTier.NONE -> "No Nitro"
        PremiumTier.PREMIUM_GUILD_SUBSCRIPTION_ONLY -> "Guild subscription only"
        PremiumTier.TIER_0 -> "Nitro Basic"
        PremiumTier.TIER_1 -> "Nitro Classic"
        PremiumTier.TIER_2 -> "Nitro"
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val store = StoreStream.getExperiments()
        store.observeOverrides().ui(this).appSubscribe(javaClass) { overrides ->
            experiments.setData(ExperimentRegistry.registeredExperiments.values.map { registered ->
                object : ExperimentOverridesAdapter.Item {
                    override val name = registered.readableName
                    override val apiName = registered.name
                    override val overrideBucket = overrides[registered.name]
                    override val bucketDescriptions = registered.buckets
                    override val onOverrideBucketSelected: (Int) -> Unit = {
                        store.setOverride(registered.name, it)
                    }
                    override val onOverrideBucketCleared: () -> Unit = {
                        store.clearOverride(registered.name)
                    }
                }
            })
        }
        StoreStream.getNotices().observeNoticesSeen().ui(this).appSubscribe(javaClass) { seen ->
            notices.submit(seen.entries.sortedByDescending { it.value }.map { it.key to it.value })
        }
    }

    companion object {
        fun launch(context: Context) {
            AppScreenRouter.launch(context, WidgetSettingsDeveloper::class.java)
        }
    }

    private class NoticeAdapter(private val onClear: (String) -> Unit) : RecyclerView.Adapter<NoticeViewHolder>() {
        private var items = emptyList<Pair<String, Long>>()
        fun submit(items: List<Pair<String, Long>>) { this.items = items; notifyDataSetChanged() }
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = NoticeViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.icon_list_item_text_view, parent, false) as TextView,
            onClear,
        )
        override fun onBindViewHolder(holder: NoticeViewHolder, position: Int) = holder.bind(items[position])
    }

    class NoticeViewHolder(private val text: TextView, private val onClear: (String) -> Unit) : RecyclerView.ViewHolder(text) {
        lateinit var noticeName: String
        @SuppressLint("SetTextI18n")
        fun bind(item: Pair<String, Long>) {
            noticeName = item.first
            text.text = "$noticeName @ ${DateFormat.getDateTimeInstance().format(Date(item.second))}"
            text.setOnClickListener { onClear(noticeName) }
        }
    }
}
