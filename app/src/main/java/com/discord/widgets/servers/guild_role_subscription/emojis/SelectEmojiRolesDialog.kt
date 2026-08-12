package com.discord.widgets.servers.guild_role_subscription.emojis

import android.view.View
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppDialog
import com.discord.utilities.guild.RoleSubscriptionEmojis.AccessTier
import com.discord.utilities.view.recycler.MaxHeightRecyclerView
import com.discord.utilities.views.SimpleRecyclerAdapter
import com.discord.utilities.views.SimpleRecyclerAdapter.Companion.addThemedDivider
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Which subscription tiers may use an emoji.
 *
 * Rows are tiers rather than roles: an archived tier keeps its role and the subscribers who bought
 * it, so it still has to be offered — marked, and after the live ones.
 *
 * Nothing is committed until save, and save stays disabled while the selection is empty. An emoji
 * with no tier is not a premium emoji at all, so an empty selection has no meaning to submit.
 */
class SelectEmojiRolesDialog : AppDialog(R.layout.dialog_select_emoji_roles) {

    private var tiers: List<AccessTier> = emptyList()
    private val selected = mutableSetOf<Long>()
    private var onSaved: ((List<Long>) -> Unit)? = null

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        view.findViewById<MaterialButton>(R.id.dialog_cancel).setOnClickListener { dismiss() }
        val confirm = view.findViewById<MaterialButton>(R.id.dialog_confirm)
        confirm.isEnabled = selected.isNotEmpty()
        confirm.setOnClickListener {
            onSaved?.invoke(tiers.map(AccessTier::roleId).filter(selected::contains))
            dismiss()
        }
        view.findViewById<MaxHeightRecyclerView>(R.id.dialog_selections).apply {
            adapter = SimpleRecyclerAdapter(tiers) { inflater, parent ->
                TierViewHolder(
                    inflater.inflate(R.layout.dialog_select_emoji_roles_item, parent, false),
                    confirm,
                )
            }
            setHasFixedSize(false)
            isNestedScrollingEnabled = false
            addThemedDivider()
        }
    }

    /** The callback cannot survive process death, so the dialog leaves rather than come back dead. */
    override fun onPause() {
        super.onPause()
        dismiss()
    }

    private inner class TierViewHolder(
        itemView: View,
        private val confirm: MaterialButton,
    ) : SimpleRecyclerAdapter.ViewHolder<AccessTier>(itemView) {

        private val name: TextView = itemView.findViewById(R.id.select_emoji_roles_item_name)
        private val archived: TextView = itemView.findViewById(R.id.select_emoji_roles_item_archived)
        private val checkBox: MaterialCheckBox = itemView.findViewById(R.id.select_emoji_roles_item_checkbox)

        override fun bind(data: AccessTier) {
            name.text = data.name
            archived.visibility = if (data.archived) View.VISIBLE else View.GONE
            checkBox.isChecked = data.roleId in selected
            itemView.setOnClickListener {
                if (!selected.add(data.roleId)) selected.remove(data.roleId)
                checkBox.isChecked = data.roleId in selected
                confirm.isEnabled = selected.isNotEmpty()
            }
        }
    }

    companion object {
        /**
         * @param tiers Live tiers first, then archived, as the reference orders them.
         * @param selectedRoleIds The emoji's current access; empty for one being uploaded.
         */
        fun show(
            fragmentManager: FragmentManager,
            tiers: List<AccessTier>,
            selectedRoleIds: Set<Long>,
            onSaved: (List<Long>) -> Unit,
        ) = SelectEmojiRolesDialog().apply {
            this.tiers = tiers
            this.selected.addAll(selectedRoleIds)
            this.onSaved = onSaved
            show(fragmentManager, SelectEmojiRolesDialog::class.java.name)
        }
    }
}
