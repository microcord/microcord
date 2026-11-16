package com.discord.widgets.debugging

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.databinding.WidgetDebuggingAdapterItemBinding
import com.discord.databinding.WidgetDebuggingBinding
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationLatest
import com.discord.utilities.rx.ui
import com.discord.utilities.systemlog.SystemLogUtils
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/**
 * The in-app log viewer: the bounded [AppLog] snapshot, newest first, colour-coded by priority and
 * expandable per row.
 *
 * The buffer holds at most [AppLog.MAX_BUFFERED_LOGS] lines, so while logs stream in the oldest are
 * dropped. The list therefore only follows live updates while it is scrolled to the newest line;
 * scrolled away it holds still and offers a "new logs" button, instead of shifting or losing the
 * lines being read.
 *
 * **No secrets reach this screen.** `AppLog` redacts tokens, cookies, passwords, MFA codes and
 * message content before a line is ever recorded, so the viewer renders whatever survived that
 * pass and adds nothing of its own. Long-pressing a row copies exactly the text on screen.
 */
class WidgetDebugging : AppFragment(R.layout.widget_debugging) {

    private val filterSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(true)
    private val showSystemLogsSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(false)
    private val querySubject: BehaviorSubject<String> = BehaviorSubject.create("")
    private var _binding: WidgetDebuggingBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = LogAdapter()

    /** The newest rows, held back while the user reads older ones. */
    private var pending: List<AppLog.LoggedItem>? = null

    /** Set when a filter changes: its result is shown even while scrolled away. */
    private var applyNext = true

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetDebuggingBinding.bind(view)
        binding.debuggingLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.debuggingLogs.adapter = adapter
        binding.debuggingLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isAtNewest()) pending?.let(::submit)
            }
        })
        binding.debuggingNewLogs.setOnClickListener {
            scrollToNewest()
            pending?.let(::submit)
        }
        binding.debuggingFilter.bindTextWatcher(this) {
            applyNext = true
            querySubject.onNext(it.trim())
        }
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.debug)
        configureMenu()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Device info leads the list whatever the filters: no report is useful without it.
        val headerRows = debugHeader(requireContext()).map { AppLog.LoggedItem(Log.INFO, it, null) }
        Observable.combineLatest(
            AppLog.observeLogs().distinctUntilChanged(),
            filterSubject.distinctUntilChanged(),
            showSystemLogsSubject.distinctUntilChanged(),
            querySubject.distinctUntilChanged(),
        ) { logs, isFiltered, showSystemLogs, query ->
            val rows = filterDebugLogs(logs, isFiltered, showSystemLogs)
                .filter { query.isEmpty() || logRowText(it).contains(query, ignoreCase = true) }
                .asReversed()
            if (query.isEmpty()) headerRows + rows else rows
        }
            .throttleLast(UPDATE_INTERVAL_MS, TimeUnit.MILLISECONDS)
            .computationLatest()
            .ui(this)
            .appSubscribe(errorClass = WidgetDebugging::class.java, onNext = ::configureUI)
    }

    private fun configureUI(rows: List<AppLog.LoggedItem>) {
        if (applyNext || adapter.itemCount == 0 || isAtNewest()) {
            applyNext = false
            submit(rows)
        } else {
            pending = rows
            binding.debuggingNewLogs.visibility = View.VISIBLE
        }
    }

    private fun submit(rows: List<AppLog.LoggedItem>) {
        val follow = isAtNewest()
        pending = null
        binding.debuggingNewLogs.visibility = View.GONE
        // Rows inserted above the first visible one would otherwise leave the list anchored below them.
        adapter.submitList(rows) { if (follow) scrollToNewest() }
    }

    private fun isAtNewest() = _binding?.debuggingLogs?.canScrollVertically(-1) == false

    private fun scrollToNewest() {
        _binding?.debuggingLogs?.scrollToPosition(0)
    }

    /**
     * Set once per view, not per log update: re-inflating the menu dismisses an open export
     * submenu, and while logs stream in (a voice call heartbeats every few seconds) it closed
     * before the user could pick anything. The checkable items toggle themselves.
     */
    private fun configureMenu() {
        setActionBarOptionsMenu(
            menuResId = R.menu.menu_debugging,
            onSelected = { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_debugging_filter -> {
                        val checked = !menuItem.isChecked
                        menuItem.isChecked = checked
                        applyNext = true
                        filterSubject.onNext(checked)
                    }
                    R.id.menu_debugging_system_logs -> {
                        val checked = !menuItem.isChecked
                        menuItem.isChecked = checked
                        applyNext = true
                        showSystemLogsSubject.onNext(checked)
                    }
                    R.id.menu_debugging_share -> shareLogs()
                    R.id.menu_debugging_save -> startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("text/plain")
                            .putExtra(Intent.EXTRA_TITLE, "manticord-debug-${System.currentTimeMillis()}.txt"),
                        REQUEST_SAVE_LOGS,
                    )
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_debugging_filter).isChecked = filterSubject.value == true
                menu.findItem(R.id.menu_debugging_system_logs).isChecked = showSystemLogsSubject.value == true
            },
        )
    }

    @Deprecated("Uses the existing fragment activity-result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE_LOGS || resultCode != Activity.RESULT_OK) return
        saveLogs(data?.data ?: return)
    }

    private fun exportText(): String {
        val header = debugHeader(requireContext()).joinToString(System.lineSeparator() + System.lineSeparator())
        return debugLogExport(AppLog.getLogs(), SystemLogUtils.fetchPreviousSessionLogs(), header)
    }

    /** Writes the export into the provider-shared cache folder and opens the share sheet on it. */
    private fun shareLogs() {
        val context = requireContext().applicationContext
        val output = exportText()
        Observable.fromCallable {
            val directory = File(context.cacheDir, "debug_logs").apply { mkdirs() }
            // Only the latest export is kept; older ones are already shared or abandoned.
            directory.listFiles()?.forEach { it.delete() }
            File(directory, "microcord-debug-${System.currentTimeMillis()}.txt").apply { writeText(output) }
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, getString(R.string.share)))
                },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    private fun saveLogs(uri: Uri) {
        val resolver = requireContext().contentResolver
        val output = exportText()
        Observable.fromCallable {
            resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(output) }
                ?: error("Unable to open debug log destination")
        }
            .subscribeOn(Schedulers.io())
            .ui(this)
            .subscribe(
                { AppToast.show(this, R.string.debug_logs_saved) },
                { AppToast.show(this, R.string.default_failure_to_perform_action_message) },
            )
    }

    /** Rows keep their expanded state across updates, so a log being read does not snap shut. */
    private class LogAdapter : ListAdapter<AppLog.LoggedItem, LogAdapter.Holder>(DIFF) {
        private val expanded = HashSet<String>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            WidgetDebuggingAdapterItemBinding.inflate(LayoutInflater.from(parent.context), parent, false),
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val context = holder.binding.root.context
            holder.binding.logMeta.apply {
                text = TIME_FORMAT.get()!!.format(Date(item.timeMillis)) + "  " + priorityLabel(item.priority)
                setTextColor(priorityColor(context, item.priority))
            }
            holder.binding.logMessage.apply {
                text = logRowText(item)
                maxLines = if (item.key in expanded) Int.MAX_VALUE else COLLAPSED_MAX_LINES
            }
            holder.binding.root.setOnClickListener {
                if (!expanded.remove(item.key)) expanded += item.key
                notifyItemChanged(holder.bindingAdapterPosition)
            }
            holder.binding.root.setOnLongClickListener {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("", holder.binding.logMessage.text))
                true
            }
        }

        class Holder(val binding: WidgetDebuggingAdapterItemBinding) : RecyclerView.ViewHolder(binding.root)

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<AppLog.LoggedItem>() {
                override fun areItemsTheSame(old: AppLog.LoggedItem, new: AppLog.LoggedItem) = old.key == new.key
                override fun areContentsTheSame(old: AppLog.LoggedItem, new: AppLog.LoggedItem) = old.key == new.key
            }
            val TIME_FORMAT: ThreadLocal<SimpleDateFormat> = object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
            }

            fun priorityLabel(priority: Int) = when (priority) {
                Log.VERBOSE -> "VERBOSE"
                Log.DEBUG -> "DEBUG"
                Log.WARN -> "WARN"
                Log.ERROR -> "ERROR"
                Log.ASSERT -> "ASSERT"
                else -> "INFO"
            }

            @ColorInt
            fun priorityColor(context: Context, priority: Int): Int = ColorCompat.getThemedColor(
                context,
                when (priority) {
                    Log.DEBUG -> R.attr.colorStatusPositive
                    Log.WARN -> R.attr.colorStatusWarning
                    Log.ERROR, Log.ASSERT -> R.attr.colorStatusDanger
                    else -> R.attr.colorTextMuted
                },
            )
        }
    }

    companion object {
        private const val COLLAPSED_MAX_LINES = 2
        private const val REQUEST_SAVE_LOGS = 6_201
        private const val UPDATE_INTERVAL_MS = 250L

        fun launch(context: Context) {
            AppScreenRouter.launch(context, WidgetDebugging::class.java)
        }
    }
}

/**
 * The exact text a log row renders — and, because long-press copies `logMessage.text` verbatim, the
 * exact text this screen can put on the clipboard.
 *
 * **Deliberate divergence from the reference.** [AppLog] redacts the *message* before recording it
 * but stores the [Throwable] as-is, and the reference appends `"\n$throwable"` raw.
 * `Throwable.toString()` is `ClassName: message`, so an exception carrying a token in its message
 * (a REST failure quoting the request, say) would be rendered — and copied — in the clear. Per the
 * project's "redact secrets even where the reference did not" rule the throwable goes through the
 * same [AppLog.redactSecrets] pass as the message.
 *
 * Top-level rather than a companion member so it stays reachable from a plain JVM unit test without
 * initialising the fragment.
 */
internal fun logRowText(item: AppLog.LoggedItem): String =
    AppLog.redactSecrets(item.message + (item.throwable?.let { "\n$it" } ?: ""))

/**
 * What a bug report needs to tell devices apart, and nothing about the account: app build, Android
 * version, device model, CPU ABIs, language and navigation mode (gestures change insets).
 */
internal fun deviceInfo(context: Context): String {
    val navigationMode = when (
        runCatching { android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode") }.getOrNull()
    ) {
        0 -> "3-button"
        1 -> "2-button"
        2 -> "gestures"
        else -> "unknown"
    }
    val metrics = context.resources.displayMetrics
    return listOf(
        "App: ${context.getString(R.string.manticord_build_label)} (${context.packageName})",
        "Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})",
        "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE})",
        "ABIs: ${android.os.Build.SUPPORTED_ABIS.joinToString()}",
        "Screen: ${metrics.widthPixels}x${metrics.heightPixels} @ ${metrics.densityDpi}dpi, font scale ${context.resources.configuration.fontScale}",
        "Locale: ${java.util.Locale.getDefault().toLanguageTag()}",
        "Navigation: $navigationMode",
    ).joinToString(separator = System.lineSeparator())
}

/** Device info and the X-Super-Properties sent with every request: required context for any bug report. */
internal fun debugHeader(context: Context): List<String> = listOf(
    deviceInfo(context),
    "X-Super-Properties: ${AnalyticSuperProperties.superPropertiesString}",
    recentProcessExits(context),
)

/**
 * Why the last few processes ended. A low-memory kill leaves no line in the app's own logcat, so
 * without this it is indistinguishable from a native crash in a report.
 */
private fun recentProcessExits(context: Context): String {
    if (android.os.Build.VERSION.SDK_INT < 30) return "Recent exits: unavailable before Android 11"
    val exits = runCatching {
        context.getSystemService(ActivityManager::class.java)?.getHistoricalProcessExitReasons(null, 0, 3)
    }.getOrNull().orEmpty()
    if (exits.isEmpty()) return "Recent exits: none recorded"
    return exits.joinToString(separator = System.lineSeparator(), prefix = "Recent exits:" + System.lineSeparator()) {
        val reason = when (it.reason) {
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource usage"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
            ApplicationExitInfo.REASON_EXIT_SELF -> "exited itself"
            else -> "reason ${it.reason}"
        }
        "  ${java.util.Date(it.timestamp)}: $reason (status ${it.status}, importance ${it.importance}, " +
            "pss ${it.pss / 1024} MB, rss ${it.rss / 1024} MB) ${it.description.orEmpty()}"
    }
}

internal fun debugLogExport(
    logs: List<AppLog.LoggedItem>,
    previousSessionLogs: String = "",
    header: String = "",
): String = buildString {
    appendLine("Microcord debug log")
    if (header.isNotBlank()) {
        appendLine(header)
        appendLine()
    }
    logs.forEach { item ->
        appendLine(logRowText(item))
    }
    if (previousSessionLogs.isNotBlank()) {
        appendLine()
        appendLine("Previous session captured logcat tail")
        append(AppLog.redactSecrets(previousSessionLogs))
    }
}

/** Checked hides verbose rows and Android/platform noise; exports always retain every captured row. */
internal fun filterDebugLogs(
    logs: List<AppLog.LoggedItem>,
    isFiltered: Boolean,
    showSystemLogs: Boolean = false,
): List<AppLog.LoggedItem> = if (isFiltered) {
    logs.filter { it.priority > Log.VERBOSE && (showSystemLogs || !it.isSystem) }
} else {
    logs
}
