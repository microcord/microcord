package com.discord.widgets.servers.auditlog

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.member.GuildMember
import com.discord.models.user.User
import com.discord.stores.StoreStream
import com.discord.utilities.auditlogs.AuditLogUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.CheckedSetting
import com.discord.views.user.SettingsMemberView
import android.widget.TextView
import com.google.android.material.radiobutton.MaterialRadioButton
import rx.functions.Action1

/**
 * Backs both audit-log filter pickers — the actor list and the action list — because they are the
 * same screen with a different row type.
 *
 * Selecting a row writes straight to [com.discord.stores.StoreAuditLog] and then closes the picker
 * through [onFilterSelectedCallback]; there is no "apply" step, and no local selection state.
 */
class WidgetServerSettingsAuditLogFilterAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<MGRecyclerDataPayload>(recycler, deferredDiffs = false) {

    /** Opens the acting user's profile sheet from the row's avatar. */
    var onAuditLogAvatarClicked: (userId: Long, guildId: Long?) -> Unit = { _, _ -> }

    private var onFilterSelectedCallback: (() -> Unit)? = null

    fun configure(listItems: List<MGRecyclerDataPayload>, onFilterSelectedCallback: () -> Unit) {
        this.onFilterSelectedCallback = onFilterSelectedCallback
        setData(listItems)
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MGRecyclerDataPayload> = when (viewType) {
        TYPE_USER -> AuditLogFilterUserViewHolder(this)
        TYPE_ACTION -> AuditLogFilterActionViewHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    /**
     * One selectable actor.
     *
     * The "All users" pseudo-row is the same type with [id] `0` and no [user]; that is also the
     * value the store uses for "no filter", so selecting it needs no special case.
     */
    data class AuditLogUserFilterItem(
        val user: User?,
        val guildMember: GuildMember?,
        val id: Long,
        val text: CharSequence?,
        val discriminator: Int?,
        val isChecked: Boolean,
    ) : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_USER
        override val key: String get() = id.toString()
    }

    /** One selectable action type. `actionId` `0` is "All actions". */
    data class AuditLogActionFilterItem(
        val actionId: Int,
        val text: String,
        val isChecked: Boolean,
    ) : MGRecyclerDataPayload {
        override val type: Int get() = TYPE_ACTION
        override val key: String get() = actionId.toString()
    }

    private class AuditLogFilterUserViewHolder(
        adapter: WidgetServerSettingsAuditLogFilterAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsAuditLogFilterAdapter, MGRecyclerDataPayload>(
        R.layout.widget_server_settings_audit_log_filter_user_listitem,
        adapter,
    ) {
        private val memberView: SettingsMemberView = itemView.findViewById(R.id.settings_member_view)
        private val itemIcon: ImageView = itemView.findViewById(R.id.item_icon)
        private val nonMemberText: TextView = itemView.findViewById(R.id.non_member_text)
        private val radioButton: MaterialRadioButton =
            itemView.findViewById(R.id.audit_log_filter_radio_button)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val item = data as AuditLogUserFilterItem
            val user = item.user

            // "All users" and any actor who has left the guild render as a plain labelled row —
            // there is no avatar or member colour to show.
            val isMember = item.id != NO_USER_FILTER && user != null
            memberView.visibility = if (isMember) View.VISIBLE else View.GONE
            nonMemberText.visibility = if (isMember) View.GONE else View.VISIBLE
            itemIcon.visibility = if (isMember) View.GONE else View.VISIBLE

            if (isMember) {
                memberView.configure(requireNotNull(user), item.guildMember)
            } else {
                nonMemberText.text = item.text ?: ""
            }

            radioButton.isChecked = item.isChecked
            radioButton.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked) return@setOnCheckedChangeListener
                StoreStream.getAuditLog().setAuditLogFilterUserId(item.id)
                adapter.onFilterSelectedCallback?.invoke()
            }
            // The radio is small; the whole row is the touch target.
            itemView.setOnClickListener { radioButton.isChecked = true }
        }
    }

    private class AuditLogFilterActionViewHolder(
        adapter: WidgetServerSettingsAuditLogFilterAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsAuditLogFilterAdapter, MGRecyclerDataPayload>(
        R.layout.widget_server_settings_audit_log_filter_action_listitem,
        adapter,
    ) {
        private val checkedSetting: CheckedSetting = itemView.findViewById(R.id.audit_log_filter_cs)
        private val targetImage: ImageView = itemView.findViewById(R.id.audit_log_filter_image_target)
        private val actionImage: ImageView = itemView.findViewById(R.id.audit_log_filter_image_action)

        override fun onConfigure(position: Int, data: MGRecyclerDataPayload) {
            super.onConfigure(position, data)
            val item = data as AuditLogActionFilterItem

            targetImage.setImageResource(AuditLogUtils.getTargetTypeImage(itemView, item.actionId))
            actionImage.setImageResource(AuditLogUtils.getActionTypeImage(item.actionId))
            checkedSetting.setText(item.text)
            // Set the state before installing the listener, otherwise recycling a row would fire a
            // selection for whatever item happened to be bound previously.
            checkedSetting.setOnCheckedListener(null)
            checkedSetting.setChecked(item.isChecked, notifyListener = false)
            checkedSetting.setOnCheckedListener(
                Action1 {
                    StoreStream.getAuditLog().setAuditLogFilterActionId(item.actionId)
                    adapter.onFilterSelectedCallback?.invoke()
                },
            )
        }
    }

    companion object {
        const val FILTER_TYPE_USER = 0
        const val FILTER_TYPE_ACTION = 1

        private const val TYPE_USER = 0
        private const val TYPE_ACTION = 1

        /** The store's "no actor filter" sentinel, and this list's "All users" row id. */
        internal const val NO_USER_FILTER = 0L
    }
}
