package com.discord.widgets.servers.settings.members

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.views.user.SettingsMemberView
import com.discord.widgets.roles.RolesListView

/**
 * The server-settings member list.
 *
 * A row is either actionable or not: a member the acting user outranks shows a chevron and opens
 * the edit screen, one they do not shows a padlock instead. The tap is still delivered for the
 * locked case — the *screen* decides which "you cannot manage this member" message to show, because
 * only it knows whether the obstacle is ownership, an equal rank or a higher one.
 */
class WidgetServerSettingsMembersAdapter(
    recycler: RecyclerView,
) : MGRecyclerAdapterSimple<WidgetServerSettingsMembersModel.MemberItem>(
    recycler,
    deferredDiffs = false,
) {

    private var memberSelectedListener: (userId: Long, userRoles: List<Long>) -> Unit = { _, _ -> }

    fun configure(
        model: WidgetServerSettingsMembersModel,
        memberSelectedListener: (userId: Long, userRoles: List<Long>) -> Unit,
    ) {
        this.memberSelectedListener = memberSelectedListener
        setData(model.memberItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberListItem =
        if (viewType == WidgetServerSettingsMembersModel.MEMBER_LIST_ITEM_TYPE) {
            MemberListItem(this)
        } else {
            throw invalidViewTypeException(viewType)
        }

    class MemberListItem(
        adapter: WidgetServerSettingsMembersAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsMembersAdapter, WidgetServerSettingsMembersModel.MemberItem>(
        R.layout.widget_server_settings_member_list_item,
        adapter,
    ) {
        private val container: View = itemView.findViewById(R.id.member_list_item_container)
        private val memberView: SettingsMemberView = itemView.findViewById(R.id.settings_member_view)
        private val rolesList: RolesListView = itemView.findViewById(R.id.member_list_item_roles_list)
        private val lock: ImageView = itemView.findViewById(R.id.member_list_item_lock)
        private val overflow: ImageView = itemView.findViewById(R.id.member_list_item_overflow)

        override fun onConfigure(
            position: Int,
            data: WidgetServerSettingsMembersModel.MemberItem,
        ) {
            super.onConfigure(position, data)
            memberView.configure(data.user, data.guildMember)

            rolesList.updateView(data.roles, data.guildMember.guildId)
            rolesList.visibility = if (data.roles.isNotEmpty()) View.VISIBLE else View.GONE

            // Exactly one of the two is shown; the padlock is the only signal that the row is
            // read-only, since it still responds to a tap.
            lock.visibility = if (data.isManagable) View.GONE else View.VISIBLE
            overflow.visibility = if (data.isManagable) View.VISIBLE else View.GONE

            container.setOnClickListener {
                adapter.memberSelectedListener(data.user.id, data.roles.map { role -> role.id })
            }
        }
    }
}
