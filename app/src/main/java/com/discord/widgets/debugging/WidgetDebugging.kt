package com.discord.widgets.debugging

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppLog
import com.discord.app.AppScreenRouter
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapter
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.computationBuffered
import com.discord.utilities.rx.ui
import com.discord.utilities.view.extensions.setOnLongClickListenerConsumeClick
import java.util.concurrent.TimeUnit
import rx.Observable
import rx.subjects.BehaviorSubject

/**
 * The in-app log viewer: everything [AppLog] has recorded since the screen was opened, newest
 * first, colour-coded by priority and expandable per row.
 *
 * The list accumulates rather than replaying: [AppLog.observeLogs] is a hot `PublishSubject`, so
 * lines are buffered in 200 ms windows and `scan`-ed into a growing list. Opening the screen twice
 * therefore starts from empty both times, which is the reference behaviour.
 *
 * **No secrets reach this screen.** `AppLog` redacts tokens, cookies, passwords, MFA codes and
 * message content before a line is ever recorded, so the viewer renders whatever survived that
 * pass and adds nothing of its own. Long-pressing a row copies exactly the text on screen.
 */
class WidgetDebugging : AppFragment(R.layout.widget_debugging) {

    private val filterSubject: BehaviorSubject<Boolean> = BehaviorSubject.create(true)
    private var logsAdapter: Adapter? = null

    private data class Model(val logs: List<LogRow>, val isFiltered: Boolean)

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
        val bufferedLogs = AppLog.observeLogs()
            .computationBuffered()
            .distinctUntilChanged()
            .buffer(LOG_BUFFER_MS, TimeUnit.MILLISECONDS)
            .filter { it.isNotEmpty() }
            .scan(emptyList<AppLog.LoggedItem>()) { existing, new -> existing + new }

        Observable.combineLatest(
            bufferedLogs,
            filterSubject.distinctUntilChanged(),
        ) { logs, isFiltered ->
            // "Filtered" hides VERBOSE, which is almost all of the traffic.
            val visible = if (isFiltered) logs.filter { it.priority > Log.VERBOSE } else logs
            Model(visible.map(::LogRow), isFiltered)
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
                if (menuItem.itemId == R.id.menu_debugging_filter) {
                    val checked = !menuItem.isChecked
                    menuItem.isChecked = checked
                    filterSubject.onNext(checked)
                }
            },
            onConfigured = { menu ->
                menu.findItem(R.id.menu_debugging_filter).isChecked = model.isFiltered
            },
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
                logMessage.text = item.message + (item.throwable?.let { "\n$it" } ?: "")
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
        private const val LOG_BUFFER_MS = 200L

        fun launch(context: Context) {
            AppScreenRouter.launch(context, WidgetDebugging::class.java)
        }
    }
}
