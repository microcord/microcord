package com.discord.widgets.servers

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.CheckedSetting

/**
 * The role checklist on the member-edit screen.
 *
 * Every role in the guild is listed, including the ones the acting user may not grant — a moderator
 * needs to *see* that a member holds an admin role even when they cannot take it away. A role that
 * cannot be toggled is disabled rather than hidden, and a bot-managed role explains why.
 */
class WidgetServerSettingsEditMemberRolesAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<WidgetServerSettingsEditMemberRolesAdapter.RoleItem>(
    recycler,
    deferredDiffs = false,
) {

    private var roleClickListener: ((roleId: Long) -> Unit)? = null

    fun configure(roleItems: List<RoleItem>, roleClickListener: (roleId: Long) -> Unit) {
        this.roleClickListener = roleClickListener
        setData(roleItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoleListItem =
        if (viewType == RoleItem.TYPE_ROLE) {
            RoleListItem(this)
        } else {
            throw invalidViewTypeException(viewType)
        }

    /**
     * One role, with whether the member holds it and whether the acting user may change that.
     *
     * @param isManageable computed by the secondary constructor from the whole hierarchy context;
     *   see it for the rule.
     */
    data class RoleItem(
        val role: GuildRole,
        val isUserHasRole: Boolean,
        val isManageable: Boolean,
    ) : MGRecyclerDataPayload {

        /**
         * A role is manageable when all three hold:
         *
         * - it is not bot-managed (an integration owns those; nobody may assign them by hand),
         * - the acting user owns the guild *or* outranks the role, and
         * - the acting user has Manage Roles at all.
         */
        constructor(
            role: GuildRole,
            userHasRole: Boolean,
            myHighestRole: GuildRole?,
            iAmOwner: Boolean,
            canManageRoles: Boolean,
        ) : this(
            role = role,
            isUserHasRole = userHasRole,
            isManageable = !role.managed &&
                (iAmOwner || RoleUtils.rankIsHigher(myHighestRole, role)) &&
                canManageRoles,
        )

        override val type: Int get() = TYPE_ROLE
        override val key: String get() = role.id.toString()

        /** Owned by an integration, so its membership is not the moderator's to change. */
        val isRoleManaged: Boolean get() = role.managed

        companion object {
            const val TYPE_ROLE = 1
        }
    }

    class RoleListItem(
        adapter: WidgetServerSettingsEditMemberRolesAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsEditMemberRolesAdapter, RoleItem>(
        R.layout.view_checkable_role_list_item,
        adapter,
    ) {
        /** The row's root *is* the control; the layout has no wrapper. */
        private val checkedSetting: CheckedSetting = itemView as CheckedSetting

        override fun onConfigure(position: Int, data: RoleItem) {
            super.onConfigure(position, data)
            checkedSetting.setText(data.role.name)
            checkedSetting.setTextColor(RoleUtils.getRoleColor(data.role, checkedSetting.context))
            checkedSetting.setChecked(data.isUserHasRole)

            when {
                // The container, not the checkbox, is the touch target: the caller toggles the role
                // through REST and the checked state comes back from the store, so the control is
                // never allowed to flip on its own.
                data.isManageable -> checkedSetting.setContainerClickListener {
                    adapter.roleClickListener?.invoke(data.role.id)
                }
                data.isRoleManaged ->
                    checkedSetting.setDisabledTooltip(R.string.managed_role_explaination)
                // Out-ranked or no Manage Roles: disabled with nothing to explain beyond the fact.
                else -> checkedSetting.setDisabledTooltip(null)
            }
        }
    }
}
