package com.discord.widgets.servers

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.api.role.GuildRole
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.DragAndDropAdapter
import com.discord.utilities.mg_recycler.DragAndDropHelper
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.widgets.roles.RoleIconView
import com.discord.widgets.servers.settings.members.WidgetServerSettingsMembers

class WidgetServerSettingsRolesAdapter(
    recycler: RecyclerView,
) : DragAndDropAdapter<WidgetServerSettingsRolesAdapter.Item>(recycler) {

    private var roleSelected: (GuildRole) -> Unit = {}
    private var rolesMoved: (Map<String, Int>) -> Unit = {}

    fun configure(
        items: List<Item>,
        roleSelected: (GuildRole) -> Unit,
        rolesMoved: (Map<String, Int>) -> Unit,
    ) {
        this.roleSelected = roleSelected
        this.rolesMoved = rolesMoved
        setData(items)
    }

    override fun computeChangedPositions(): Map<String, Int> = changedPositions(dataCopy, origPositions)

    override fun isValidMove(fromPosition: Int, toPosition: Int): Boolean {
        if (!isMoveWithinBounds(fromPosition, toPosition)) return false
        return (minOf(fromPosition, toPosition)..maxOf(fromPosition, toPosition))
            .all { (dataCopy[it] as? RoleItem)?.canReorder() == true }
    }

    override fun onNewPositions(newPositions: Map<String, Int>) = rolesMoved(newPositions)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<*, Item> =
        when (viewType) {
            TYPE_HELP -> HelpViewHolder(this)
            TYPE_ROLE -> RoleViewHolder(this)
            else -> throw invalidViewTypeException(viewType)
        }

    sealed interface Item : DragAndDropAdapter.Payload

    data class HelpItem(val guildId: Long) : Item {
        override val key = "key0"
        override val type = TYPE_HELP
        override val position = 0
    }

    data class RoleItem(
        val role: GuildRole,
        val everyoneRole: Boolean,
        val locked: Boolean = false,
        val userAbleToManageRoles: Boolean,
        val elevated: Boolean,
        val guildId: Long,
    ) : Item {
        override val key = role.id.toString()
        override val type = TYPE_ROLE
        override val position = role.position

        fun canReorder() = !everyoneRole && !locked && userAbleToManageRoles && elevated
    }

    private class HelpViewHolder(
        adapter: WidgetServerSettingsRolesAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsRolesAdapter, Item>(
        R.layout.widget_server_settings_help_item,
        adapter,
    ), DragAndDropHelper.DraggableViewHolder {
        override fun canDrag() = false
        override fun onDragStateChanged(dragging: Boolean) = Unit

        override fun onConfigure(position: Int, data: Item) {
            val item = data as HelpItem
            val textView = itemView as TextView
            val raw = itemView.context.getString(R.string.help_roles_description)
            val label = "[Members](onMembersClick)"
            val start = raw.indexOf(label)
            if (start < 0) {
                textView.text = raw
                return
            }
            textView.text = SpannableString(raw.replace(label, "Members")).apply {
                setSpan(
                    object : ClickableSpan() {
                        override fun onClick(widget: View) {
                            WidgetServerSettingsMembers.launch(widget.context, item.guildId)
                        }
                    },
                    start,
                    start + "Members".length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            textView.movementMethod = LinkMovementMethod.getInstance()
        }
    }

    private class RoleViewHolder(
        adapter: WidgetServerSettingsRolesAdapter,
    ) : MGRecyclerViewHolder<WidgetServerSettingsRolesAdapter, Item>(
        R.layout.widget_server_settings_role_item,
        adapter,
    ), DragAndDropHelper.DraggableViewHolder {
        private val name: TextView = itemView.findViewById(R.id.server_settings_role_item_name)
        private val lock: ImageView = itemView.findViewById(R.id.server_settings_role_item_lock_indicator)
        private val icon: RoleIconView = itemView.findViewById(R.id.server_settings_role_item_role_icon)
        private val dragOverlay: View = itemView.findViewById(R.id.server_settings_role_item_drag_overlay)
        private var draggable = false

        override fun canDrag() = draggable

        override fun onConfigure(position: Int, data: Item) {
            val item = data as RoleItem
            draggable = item.canReorder()
            name.text = item.role.name
            name.setTextColor(RoleUtils.getRoleColor(item.role, itemView.context))
            lock.visibility = if (item.locked) View.VISIBLE else View.GONE
            icon.setRole(item.role, item.guildId)
            itemView.setOnClickListener { adapter.roleSelected(item.role) }
            itemView.setOnLongClickListener {
                val message = when {
                    item.everyoneRole -> R.string.form_label_disabled_for_everyone
                    !item.userAbleToManageRoles -> R.string.help_missing_manage_roles_permission
                    !item.elevated -> R.string.two_fa_guild_mfa_warning_ios
                    else -> return@setOnLongClickListener false
                }
                Toast.makeText(it.context, message, Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onDragStateChanged(dragging: Boolean) {
            dragOverlay.visibility = if (dragging) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val TYPE_HELP = 0
        const val TYPE_ROLE = 1

        internal fun changedPositions(items: List<Item>, original: Map<String, Int>): Map<String, Int> =
            buildMap {
                items.forEachIndexed { index, item ->
                    if (item is RoleItem && !item.everyoneRole) {
                        val position = items.lastIndex - index
                        if (original[item.key] != position) put(item.key, position)
                    }
                }
            }
    }
}
