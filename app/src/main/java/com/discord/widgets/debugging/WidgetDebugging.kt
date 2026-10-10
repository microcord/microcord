package com.discord.widgets.debugging

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.app.AppToast
import com.discord.utilities.analytics.AnalyticSuperProperties
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.ui
import com.discord.utilities.systemlog.SystemLogUtils
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import java.io.File
import rx.Observable
import rx.schedulers.Schedulers
import rx.subjects.BehaviorSubject

/**
 * The in-app log viewer: the bounded [AppLog] snapshot, newest first, colour-coded by priority and
 * expandable per row.
 *
 * The reference's `AppLog` subject replays at most 5,000 entries. [AppLog.observeLogs] exposes that
 * same bounded lifecycle snapshot so pausing or reopening the screen neither loses nor duplicates
 * lines.
 *
 * **No secrets reach this screen.** `AppLog` redacts tokens, cookies, passwords, MFA codes and
 * message content before a line is ever recorded, so the viewer renders whatever survived that
 * pass and adds nothing of its own. Long-pressing a row copies exactly the text on screen.
 */
class WidgetDebugging : AppFragment(R.layout.widget_debugging) {

    private val filterSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(true)
    private val showSystemLogsSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(false)
    private var logsAdapter: Adapter? = null

    private data class Model(
        val logs: List<LogRow>,
        val isFiltered: Boolean,
        val showSystemLogs: Boolean,
    )

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        val recycler: RecyclerView = view.findViewById(R.id.debugging_logs)
        logsAdapter = MGRecyclerAdapter.configure(Adapter(recycler))
        // Newest at the top and the list pinned there: `configure` installs a forward layout
        // manager, so it has to be reversed afterwards.
        (recycler.layoutManager as? LinearLayoutManager)?.apply {
            reverseLayout = true
            isSmoothScrollbarEnabled = true
        }
        setActionBarDisplayHomeAsUpEnabled()
        setActionBarTitle(R.string.debug)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        // Pinned above the newest log line (the list is reversed, so the end renders at the top),
        // and shown whatever the filters: no report is useful without them.
        val headerRows = debugHeader(requireContext()).asReversed()
            .map { LogRow(AppLog.LoggedItem(Log.INFO, it, null)) }
        Observable.combineLatest(
            AppLog.observeLogs().distinctUntilChanged(),
            filterSubject.distinctUntilChanged(),
            showSystemLogsSubject.distinctUntilChanged(),
        ) { logs, isFiltered, showSystemLogs ->
            Model(
                filterDebugLogs(logs, isFiltered, showSystemLogs).map(::LogRow) + headerRows,
                isFiltered,
                showSystemLogs,
            )
        }
            .computationBuffered()
            .ui(this)
            .appSubscribe(errorClass = WidgetDebugging::class.java, onNext = ::configureUI)
    }

    private fun configureUI(model: Model) {
        logsAdapter?.setData(model.logs)
        setActionBarOptionsMenu(
            menuResId = R.menu.menu_debugging,
            onSelected = { menuItem ->
                when (menuItem.itemId) {
                    R.id.menu_debugging_filter -> {
                        val checked = !menuItem.isChecked
                        menuItem.isChecked = checked
                        filterSubject.onNext(checked)
                    }
                    R.id.menu_debugging_system_logs -> {
                        val checked = !menuItem.isChecked
                        menuItem.isChecked = checked
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
                menu.findItem(R.id.menu_debugging_filter).isChecked = model.isFiltered
                menu.findItem(R.id.menu_debugging_system_logs).isChecked = model.showSystemLogs
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

    /**
     * One log line as a diffable row.
     *
     * `AppLog.LoggedItem` cannot implement [MGRecyclerDataPayload] itself — `com.discord.app` must
     * not depend on the recycler utilities — so the row type lives here and forwards the item's own
     * stable key.
     */
    private data class LogRow(val item: AppLog.LoggedItem) : MGRecyclerDataPayload {
        override val type: Int = VIEW_TYPE_LOG
        override val key: String = item.key
    }

    private class Adapter(recycler: RecyclerView) : MGRecyclerAdapterSimple<LogRow>(recycler) {

        init {
            setOnUpdated { _, _ ->
                // Follow the tail only while the user is already at it; otherwise leave their
                // scroll position alone.
                val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return@setOnUpdated
                if (layoutManager.findFirstCompletelyVisibleItemPosition() == 0) {
                    recycler.scrollToPosition(0)
                }
            }
        }

        /** Newest first: the backing list grows at the end, the list renders from the end. */
        override fun getItem(position: Int): LogRow = internalData[internalData.size - 1 - position]

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): MGRecyclerViewHolder<*, LogRow> = when (viewType) {
            VIEW_TYPE_LOG -> Item(R.layout.widget_debugging_adapter_item, this)
            else -> throw invalidViewTypeException(viewType)
        }

        private class Item(
            layout: Int,
            adapter: Adapter,
        ) : MGRecyclerViewHolder<Adapter, LogRow>(layout, adapter) {

            private val logMessage: TextView = itemView.findViewById(R.id.log_message)

            @SuppressLint("SetTextI18n")
            override fun onConfigure(position: Int, data: LogRow) {
                super.onConfigure(position, data)
                val item = data.item
                logMessage.setTextColor(getColor(logMessage.context, item.priority))
                logMessage.text = logRowText(item)
                logMessage.maxLines = COLLAPSED_MAX_LINES

                itemView.setOnLongClickListenerConsumeClick {
                    copyToClipboard(itemView.context, logMessage.text)
                }
                itemView.setOnClickListener {
                    logMessage.maxLines =
                        if (logMessage.maxLines == Int.MAX_VALUE) COLLAPSED_MAX_LINES else Int.MAX_VALUE
                    itemView.requestLayout()
                }
            }

            @ColorInt
            private fun getColor(context: Context, priority: Int): Int = when (priority) {
                Log.VERBOSE -> ColorCompat.getThemedColor(context, R.attr.primary_300)
                Log.DEBUG -> ColorCompat.getColor(context, R.color.status_green_600)
                Log.WARN -> ColorCompat.getColor(context, R.color.status_yellow_500)
                Log.ERROR -> ColorCompat.getColor(context, R.color.status_red_500)
                else -> ColorCompat.getThemedColor(context, R.attr.primary_100)
            }

            private fun copyToClipboard(context: Context, text: CharSequence) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as? android.content.ClipboardManager ?: return
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("", text))
            }
        }
    }

    companion object {
        private const val COLLAPSED_MAX_LINES = 2
        private const val VIEW_TYPE_LOG = 0
        private const val REQUEST_SAVE_LOGS = 6_201

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
)

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
