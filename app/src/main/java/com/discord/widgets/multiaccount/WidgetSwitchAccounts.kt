package com.discord.widgets.multiaccount

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.i18n.I18nHooks
import com.discord.utilities.color.ColorCompat
import com.discord.stores.StoreMultiAccount
import com.discord.stores.StoreMultiAccountSwitch
import com.discord.stores.StoreStream
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.utilities.rx.ui
import com.discord.widgets.notice.WidgetNoticeDialog
import com.facebook.drawee.view.SimpleDraweeView
import rx.Observable
import rx.subscriptions.CompositeSubscription

/**
 * The account switcher.
 *
 * Doubles as the reference's manage-accounts screen: the same list gains a remove control while
 * editing rather than pushing a second screen for it.
 */
class WidgetSwitchAccounts : AppBottomSheet() {

    private val adapter = Adapter(
        onClick = ::onAccountClicked,
        onRemove = ::confirmRemove,
        onDragStart = { holder -> touchHelper?.startDrag(holder) },
        onOrderCommitted = { order -> StoreStream.getMultiAccount().setOrder(order) },
    )
    private var touchHelper: ItemTouchHelper? = null
    private var editing = false

    override fun getContentViewResId() = R.layout.widget_switch_accounts

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.switch_accounts_recycler).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@WidgetSwitchAccounts.adapter
        }
        touchHelper = ItemTouchHelper(adapter.DragCallback()).also { it.attachToRecyclerView(recycler) }
        view.findViewById<View>(R.id.switch_accounts_manage).setOnClickListener {
            editing = !editing
            adapter.setEditing(editing)
            updateManageLabel(view)
        }
        view.findViewById<View>(R.id.switch_accounts_add).setOnClickListener { onAddClicked() }
        updateManageLabel(view)
    }

    override fun onDestroyView() {
        touchHelper?.attachToRecyclerView(null)
        touchHelper = null
        super.onDestroyView()
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        compositeSubscription.add(
            Observable.combineLatest(
                StoreStream.getMultiAccount().observeAccounts(),
                StoreStream.getUsers().observeMe(true).map { it.id },
            ) { accounts, meId -> accounts to meId }
                .ui(this)
                // A list update mid-drag would fight the finger for row positions.
                .filter { !adapter.isDragging }
                .subscribe { (accounts, meId) -> adapter.setAccounts(accounts, meId) },
        )
    }

    private fun updateManageLabel(view: View) {
        view.findViewById<TextView>(R.id.switch_accounts_manage)
            .setText(if (editing) R.string.done else R.string.switch_accounts_manage_accounts)
    }

    private fun onAccountClicked(account: StoreMultiAccount.Account, isCurrent: Boolean) {
        when {
            editing -> Unit
            // The list outlived the credential, so the row offers to sign in rather than switch.
            account.tokenStatus == StoreMultiAccount.TokenStatus.INVALID -> openLogin()
            isCurrent -> dismiss()
            else -> {
                StoreStream.switchAccount(account.id, StoreMultiAccountSwitch.Location.CHOOSE_ACCOUNT)
                dismiss()
            }
        }
    }

    private fun onAddClicked() {
        if (!StoreStream.getMultiAccount().canAddAccount()) {
            WidgetNoticeDialog.Builder(requireContext())
                .setTitle(R.string.switch_accounts_add_an_account_button)
                .setMessage(
                    I18nHooks.format(
                        requireContext(),
                        R.string.multi_account_max_accounts_error,
                        mapOf("maxNumAccounts" to StoreMultiAccount.MAX_ACCOUNTS),
                    ).toString(),
                )
                .setPositiveButton(R.string.okay)
                .show(parentFragmentManager)
            return
        }
        openLogin()
    }

    /** Already signed out, and the landing behind this sheet is the login screen. */
    private fun openLogin() {
        dismiss()
        if (StoreStream.getAuthentication().isAuthed()) {
            StoreStream.getAuthentication().logoutToAddAccount()
        }
    }

    /**
     * Signing out of another account is irreversible from here, and the rows are close enough
     * together that a mis-tap while editing would otherwise cost a session.
     */
    private fun confirmRemove(account: StoreMultiAccount.Account) {
        WidgetNoticeDialog.Builder(requireContext())
            .setTitle(R.string.switch_accounts_remove_account)
            .setMessage(account.username)
            .setNegativeButton(R.string.cancel)
            .setPositiveButton(R.string.switch_accounts_remove_account) {
                StoreStream.getMultiAccount().removeAccount(account.id)
            }
            .setDialogAttrTheme(R.attr.notice_theme_positive_red)
            .show(parentFragmentManager)
    }

    private class Adapter(
        private val onClick: (StoreMultiAccount.Account, Boolean) -> Unit,
        private val onRemove: (StoreMultiAccount.Account) -> Unit,
        private val onDragStart: (RecyclerView.ViewHolder) -> Unit,
        private val onOrderCommitted: (List<Long>) -> Unit,
    ) : RecyclerView.Adapter<Adapter.ViewHolder>() {

        private var accounts: List<StoreMultiAccount.Account> = emptyList()
        private var currentUserId: Long = 0L
        private var editing = false
        var isDragging = false
            private set

        fun setAccounts(accounts: List<StoreMultiAccount.Account>, currentUserId: Long) {
            this.accounts = accounts
            this.currentUserId = currentUserId
            notifyDataSetChanged()
        }

        fun setEditing(editing: Boolean) {
            this.editing = editing
            notifyDataSetChanged()
        }

        override fun getItemCount() = accounts.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.switch_accounts_list_item, parent, false),
        )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val account = accounts[position]
            val isCurrent = account.id == currentUserId
            holder.bind(account, isCurrent, editing, onClick, onRemove, onDragStart)
        }

        /**
         * Drag-to-reorder, live only while editing.
         *
         * Rows move locally as the finger does and the result is committed on drop, so the store is
         * written once per gesture rather than once per crossed row.
         */
        inner class DragCallback : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0,
        ) {
            override fun isLongPressDragEnabled() = false

            override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
                if (editing) super.getDragDirs(recyclerView, viewHolder) else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from !in accounts.indices || to !in accounts.indices) return false
                isDragging = true
                accounts = accounts.toMutableList().apply { add(to, removeAt(from)) }
                notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!isDragging) return
                isDragging = false
                onOrderCommitted(accounts.map { it.id })
            }
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val avatar: SimpleDraweeView = view.findViewById(R.id.switch_accounts_item_avatar)
            private val name: TextView = view.findViewById(R.id.switch_accounts_item_name)
            private val hint: TextView = view.findViewById(R.id.switch_accounts_item_hint)
            private val status: ImageView = view.findViewById(R.id.switch_accounts_item_status)
            private val remove: ImageView = view.findViewById(R.id.switch_accounts_item_remove)
            private val drag: ImageView = view.findViewById(R.id.switch_accounts_item_drag)

            @SuppressLint("ClickableViewAccessibility")
            fun bind(
                account: StoreMultiAccount.Account,
                isCurrent: Boolean,
                editing: Boolean,
                onClick: (StoreMultiAccount.Account, Boolean) -> Unit,
                onRemove: (StoreMultiAccount.Account) -> Unit,
                onDragStart: (RecyclerView.ViewHolder) -> Unit,
            ) {
                MGImages.setImage(
                    avatar,
                    IconUtils.getForUser(account.id, account.avatar, account.discriminator, size = 128),
                )
                name.text = account.globalName?.takeIf(String::isNotBlank) ?: account.username
                hint.text = when {
                    account.tokenStatus == StoreMultiAccount.TokenStatus.INVALID ->
                        hint.context.getString(R.string.switch_accounts_invalid_token)
                    isCurrent -> hint.context.getString(R.string.switch_accounts_active_account)
                    else -> account.username
                }
                status.visibility = if (editing) View.GONE else View.VISIBLE
                when {
                    // The warning icon carries its own amber; the check is a black glyph and needs
                    // the brand colour or it disappears into the surface behind it.
                    account.tokenStatus == StoreMultiAccount.TokenStatus.INVALID -> {
                        status.setImageResource(R.drawable.ic_warning_circle_24dp)
                        ImageViewCompat.setImageTintList(status, null)
                    }
                    isCurrent -> {
                        status.setImageResource(R.drawable.ic_check_circle_24dp)
                        ImageViewCompat.setImageTintList(
                            status,
                            ColorStateList.valueOf(
                                ColorCompat.getThemedColor(status.context, R.attr.colorControlBrandForeground),
                            ),
                        )
                    }
                    else -> status.setImageDrawable(null)
                }
                // Removing the account in use would sign the user out on the spot, which the
                // switcher is not the place to do.
                remove.visibility = if (editing && !isCurrent) View.VISIBLE else View.GONE
                remove.setOnClickListener { onRemove(account) }
                // Dragged from the handle rather than by long-press, so holding a row to read it
                // does not pick it up.
                drag.visibility = if (editing) View.VISIBLE else View.GONE
                drag.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onDragStart(this)
                    false
                }
                itemView.setOnClickListener { onClick(account, isCurrent) }
            }
        }
    }

    companion object {
        fun show(fragmentManager: FragmentManager) =
            WidgetSwitchAccounts().show(fragmentManager, WidgetSwitchAccounts::class.java.name)
    }
}
