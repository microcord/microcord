package com.discord.widgets.settings.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsRestLogBinding
import com.discord.databinding.WidgetSettingsRestLogItemBinding
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.logging.AppRestRequestLogger
import com.discord.utilities.logging.RestRequestLog
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Developer screen listing recent REST calls; tap one for its redacted headers and bodies. */
class WidgetSettingsRestLog : AppFragment(R.layout.widget_settings_rest_log) {
    private var _binding: WidgetSettingsRestLogBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = Adapter()
    @Volatile private var entries: List<RestRequestLog.Entry> = emptyList()
    private var filter = ""
    private var stopObserving: (() -> Unit)? = null
    private val renderEntries = Runnable { _binding?.let { render() } }

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsRestLogBinding.bind(view)
        setActionBarTitle(R.string.rest_log_title)
        setActionBarDisplayHomeAsUpEnabled()
        binding.restLogVerbose.apply {
            isChecked = AppRestRequestLogger.isEnabled()
            setOnCheckedListener(AppRestRequestLogger::setEnabled)
        }
        binding.restLogFilter.bindTextWatcher(this) {
            filter = it.trim()
            render()
        }
        binding.restLogList.layoutManager = LinearLayoutManager(requireContext())
        binding.restLogList.adapter = adapter
        binding.restLogClear.setOnClickListener { RestRequestLog.clear() }
        // Calls arrive on OkHttp threads, often in bursts; render at most every UPDATE_DELAY_MS.
        stopObserving = RestRequestLog.observe {
            entries = it
            _binding?.root?.let { root ->
                root.removeCallbacks(renderEntries)
                root.postDelayed(renderEntries, UPDATE_DELAY_MS)
            }
        }
    }

    override fun onDestroyView() {
        stopObserving?.invoke()
        stopObserving = null
        _binding?.root?.removeCallbacks(renderEntries)
        _binding = null
        super.onDestroyView()
    }

    private fun render() {
        val visible = entries.filter { filter.isEmpty() || summary(it).contains(filter, ignoreCase = true) || it.path.contains(filter, ignoreCase = true) }
        adapter.submit(visible)
        binding.restLogEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        private var items: List<RestRequestLog.Entry> = emptyList()
        private val expanded = HashSet<Long>()

        fun submit(items: List<RestRequestLog.Entry>) {
            this.items = items
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(WidgetSettingsRestLogItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            val context = holder.binding.root.context
            holder.binding.restLogItemSummary.apply {
                text = summary(entry)
                setTextColor(ColorCompat.getThemedColor(context, statusColor(entry.status)))
            }
            holder.binding.restLogItemPath.text = entry.path
            val isExpanded = entry.id in expanded
            holder.binding.restLogItemDetail.apply {
                visibility = if (isExpanded) View.VISIBLE else View.GONE
                if (isExpanded) text = detail(context, entry)
            }
            holder.binding.root.setOnClickListener {
                if (!expanded.remove(entry.id)) expanded += entry.id
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.binding.root.setOnLongClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(
                    ClipData.newPlainText(entry.path, summary(entry) + "\n" + entry.path + "\n\n" + detail(context, entry)),
                )
                Toast.makeText(context, R.string.rest_log_copied, Toast.LENGTH_SHORT).show()
                true
            }
        }

        inner class Holder(val binding: WidgetSettingsRestLogItemBinding) : RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val UPDATE_DELAY_MS = 150L
        private val gson = GsonBuilder().setPrettyPrinting().create()

        fun launch(context: Context) = AppScreenRouter.launch(context, WidgetSettingsRestLog::class.java)

        private fun summary(entry: RestRequestLog.Entry): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(entry.timeMillis))
            return "$time  ${entry.method}  ${entry.status ?: entry.error ?: "failed"}  ${entry.durationMs}ms"
        }

        private fun statusColor(status: Int?): Int = when (status) {
            null, in 400..599 -> R.attr.colorStatusDanger
            in 300..399 -> R.attr.colorStatusWarning
            else -> R.attr.colorStatusPositive
        }

        private fun detail(context: Context, entry: RestRequestLog.Entry): String {
            if (entry.requestHeaders == null && entry.responseHeaders == null) {
                return entry.error?.let { "Error: $it\n\n" }.orEmpty() + context.getString(R.string.rest_log_no_detail)
            }
            return listOfNotNull(
                entry.error?.let { "Error: $it" },
                entry.requestHeaders?.let { "Request headers\n${lines(it)}" },
                entry.requestBody?.let { "Request body\n${pretty(it)}" },
                entry.responseHeaders?.let { "Response headers\n${lines(it)}" },
                entry.responseBody?.let { "Response body\n${pretty(it)}" },
            ).joinToString("\n\n")
        }

        /** `{a=1, b=2}` as one header per line. */
        private fun lines(headers: String): String = headers.removeSurrounding("{", "}").replace(", ", "\n")

        private fun pretty(body: String): String =
            runCatching { gson.toJson(JsonParser.parseString(body)) }.getOrDefault(body)
    }
}
