package com.discord.widgets.servers.auditlog

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.ModelAuditLogEntry
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.auditlogs.AuditLogUtils
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

/**
 * The audit-log list: one collapsible card per entry, plus a spinner row while the next page loads.
 *
 * Expansion state does not live here — it is [com.discord.stores.StoreAuditLog]'s `selectedItemId`,
 * so tapping a row round-trips through the store and comes back as a fresh [AuditLogEntryItem] with
 * `isSelected = true`. That is what makes the expansion survive a page load appending rows below.
 */
class WidgetServerSettingsAuditLogAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler, deferredDiffs = false) {

    /** Opens the acting user's profile sheet. `guildId` is null when the user is not a member. */
    var onAuditLogAvatarClicked: (userId: Long, guildId: Long?) -> Unit = { _, _ -> }

    /**
     * Set when a tap expanded a row, cleared by the first bind that honours it.
     *
     * An expanded card can grow past the bottom of the viewport, so the row that caused it scrolls
     * itself into view — but only once, otherwise every rebind would fight the user's scrolling.
     */
    private var ensureExpandedIsVisible: Boolean = false

    fun configure(auditLogEntryItems: List<MGRecyclerDataPayload>) = setData(auditLogEntryItems)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MGRecyclerDataPayload> = when (viewType) {
        TYPE_ENTRY -> AuditLogEntryViewHolder(this)
        TYPE_LOADING -> AuditLogLoadingViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * One rendered entry.
     *
     * The heavy work — the sentence, the change block and the timestamp — is done off the main
     * thread by the screen's model, so binding is pure assignment.
     */
    data class AuditLogEntryItem(
        val auditLogEntry: ModelAuditLogEntry,
        val isSelected: Boolean,
        val user: User?,
        val guildMember: GuildMember?,
        val headerText: CharSequence?,
        val changeText: CharSequence?,
        val hasChangesToRender: Boolean,
        val timeStampText: CharSequence,
    ) : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_ENTRY
        override val key: String get() = auditLogEntry.id.toString()
    }

    /** The tail spinner shown while the next page is in flight. */
    class AuditLogLoadingItem : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_LOADING
        override val key: String get() = KEY_LOADING
    }

    private class AuditLogEntryViewHolder(
        adapter: WidgetServerSettingsAuditLogAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsAuditLogAdapter, MGRecyclerDataPayload>(
        R.layout.widget_server_settings_audit_log_list_item,
        adapter,
    ) {
        private val topContainer: RelativeLayout = itemView.findViewById(R.id.audit_log_listitem_top_container)
        private val actionTypeImage: ImageView = itemView.findViewById(R.id.audit_log_listitem_actiontype_image)
        private val targetTypeImage: ImageView = itemView.findViewById(R.id.audit_log_listitem_targettype_image)
        private val avatar: ImageView = itemView.findViewById(R.id.audit_log_listitem_avatar)
        private val headerText: TextView = itemView.findViewById(R.id.audit_log_listitem_header_text)
        private val dateText: TextView = itemView.findViewById(R.id.audit_log_listitem_date_text)
        private val changeText: TextView = itemView.findViewById(R.id.audit_log_listitem_change_text)
        private val arrow: ImageView = itemView.findViewById(R.id.audit_log_listitem_arrow)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val item = data as AuditLogEntryItem
            val entry = item.auditLogEntry

            actionTypeImage.setImageResource(AuditLogUtils.getActionTypeImage(entry.actionTypeId))
            targetTypeImage.setImageResource(
                AuditLogUtils.getTargetTypeImage(itemView, entry.actionTypeId),
            )

            IconUtils.setIcon(
                imageView = avatar,
                user = item.user,
                sizeDimenRes = R.dimen.avatar_size_standard,
                guildMember = item.guildMember,
            )
            avatar.setOnClickListener {
                val user = item.user ?: return@setOnClickListener
                adapter.onAuditLogAvatarClicked(user.id, item.guildMember?.guildId)
            }

            dateText.text = item.timeStampText
            headerText.text = item.headerText

            val expanded = item.isSelected && item.hasChangesToRender
            changeText.visibility = if (expanded) View.VISIBLE else View.GONE
            // INVISIBLE, not GONE: the arrow reserves its width so rows without changes keep the
            // same text column as rows with them.
            arrow.visibility = if (item.hasChangesToRender) View.VISIBLE else View.INVISIBLE

            if (expanded) {
                changeText.text = item.changeText
                arrow.setImageResource(R.drawable.ic_chevron_down_grey_12dp)
                topContainer.setBackgroundColor(
                    ColorCompat.getThemedColor(itemView, R.attr.primary_700_alpha_60),
                )
                scrollIntoViewIfRequested()
            } else {
                arrow.setImageResource(R.drawable.ic_chevron_right_grey_12dp)
                topContainer.setBackgroundColor(
                    ColorCompat.getThemedColor(itemView, R.attr.primary_630),
                )
            }

            itemView.setOnClickListener {
                adapter.ensureExpandedIsVisible = true
                StoreStream.getAuditLog().toggleSelectedState(entry.id)
            }
        }

        /**
         * Scrolls this row into view, but only if it is actually off-screen.
         *
         * Posted because the expansion happens during a bind, and the layout manager's
         * first/last-visible positions are only meaningful after the layout pass completes.
         */
        private fun scrollIntoViewIfRequested() {
            if (!adapter.ensureExpandedIsVisible) return
            adapter.ensureExpandedIsVisible = false
            adapter.recycler.post {
                val layoutManager = adapter.recycler.layoutManager as? LinearLayoutManager ?: return@post
                val first = layoutManager.findFirstCompletelyVisibleItemPosition()
                val last = minOf(
                    layoutManager.findLastCompletelyVisibleItemPosition(),
                    adapter.itemCount - 1,
                )
                val position = adapterPosition
                val rangeIsValid = first >= 0 && last >= first && position >= 0
                if (rangeIsValid && position !in first..last) {
                    adapter.recycler.smoothScrollToPosition(position)
                }
            }
        }
    }

    private class AuditLogLoadingViewHolder(
        adapter: WidgetServerSettingsAuditLogAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsAuditLogAdapter, MGRecyclerDataPayload>(
        R.layout.widget_server_settings_audit_log_loading_listitem,
        adapter,
    )

    companion object {
        private const val TYPE_ENTRY = 0
        private const val TYPE_LOADING = 1

        /** Constant: there is at most one loading row, always at the tail. */
        private const val KEY_LOADING = "audit-log-loading"
    }
}
