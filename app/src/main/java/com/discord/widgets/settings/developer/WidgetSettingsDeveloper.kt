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
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.stores.StoreStream
import com.discord.utilities.cache.SharedPreferencesProvider
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.views.CheckedSetting
import com.hammerandchisel.libdiscord.Discord
import java.text.DateFormat
import java.util.Date

class WidgetSettingsDeveloper : AppFragment(R.layout.widget_settings_developer) {
    private lateinit var experiments: ExperimentOverridesAdapter
    private lateinit var notices: NoticeAdapter

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.developer_options)
        experiments = ExperimentOverridesAdapter()
        notices = NoticeAdapter(StoreStream.getNotices()::clearSeen)
        view.findViewById<RecyclerView>(R.id.developer_settings_experiments).adapter = experiments
        val noticesView = view.findViewById<RecyclerView>(R.id.developer_settings_notices)
        noticesView.adapter = notices
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                (viewHolder as? NoticeViewHolder)?.noticeName?.let(StoreStream.getNotices()::clearSeen)
            }
        }).attachToRecyclerView(noticesView)

        val preferences = SharedPreferencesProvider.get()
        view.findViewById<CheckedSetting>(R.id.settings_developer_screenshot_bug_reporting).apply {
            isChecked = preferences.getBoolean(PREF_SCREENSHOT_REPORTING, false)
            setOnCheckedListener { preferences.edit().putBoolean(PREF_SCREENSHOT_REPORTING, it).apply() }
        }
        view.findViewById<View>(R.id.developer_settings_crash_kotlin).setOnClickListener {
            throw RuntimeException("This is a developer triggered crash.")
        }
        view.findViewById<View>(R.id.developer_settings_crash_discord_nonfatal).setOnClickListener {
            runCatching { throw RuntimeException("This is a developer triggered crash (caught).") }
                .onFailure { AppLog.e("setupCrashes", it) }
        }
        view.findViewById<View>(R.id.developer_settings_crash_discord_jnibridge).setOnClickListener {
            Discord(requireContext()).crash()
        }
        view.findViewById<View>(R.id.developer_settings_simulate_gateway_reconnect).setOnClickListener {
            StoreStream.getGatewayConnection().simulateReconnectForTesting()
        }
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        val store = StoreStream.getExperiments()
        store.observeOverrides().ui(this).appSubscribe(javaClass) { overrides ->
            experiments.setData(overrides.toSortedMap().map { (name, bucket) ->
                object : ExperimentOverridesAdapter.Item {
                    override val name = name
                    override val apiName = name
                    override val overrideBucket = bucket
                    override val bucketDescriptions = (0..bucket.coerceAtLeast(1)).map(Int::toString)
                    override val onOverrideBucketSelected: (Int) -> Unit = { store.setOverride(name, it) }
                    override val onOverrideBucketCleared: () -> Unit = { store.clearOverride(name) }
                }
            })
        }
        StoreStream.getNotices().observeNoticesSeen().ui(this).appSubscribe(javaClass) { seen ->
            notices.submit(seen.entries.sortedByDescending { it.value }.map { it.key to it.value })
        }
    }

    companion object {
        private const val PREF_SCREENSHOT_REPORTING = "prefs_ss_bug_reporting_enabled"
        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetSettingsDeveloper::class.java)
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
