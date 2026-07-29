package com.discord.widgets.settings.billing

import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.domain.ModelPaymentSource
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class PaymentSourceAdapter(
    recycler: RecyclerView,
    private val onAddClick: () -> Unit,
    private val onEditPaymentSource: (ModelPaymentSource) -> Unit,
) : MGRecyclerAdapterSimple<PaymentSourceAdapter.Item>(recycler, false) {

    sealed interface Item : MGRecyclerDataPayload

    data class PaymentSourceItem(
        val paymentSource: ModelPaymentSource,
        val isPremium: Boolean,
    ) : Item {
        override val key = paymentSource.id
        override val type = VIEW_TYPE_PAYMENT_SOURCE
    }

    data class PaymentSourceHeader(val headerType: Type) : Item {
        enum class Type { DEFAULT, OTHER }

        override val key = "headerType$headerType"
        override val type = VIEW_TYPE_HEADER
    }

    class PaymentSourceAddItem : Item {
        override val key = "payment_source_add"
        override val type = VIEW_TYPE_PAYMENT_ADD

        override fun equals(other: Any?) = other is PaymentSourceAddItem
        override fun hashCode() = key.hashCode()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MGRecyclerViewHolder<*, Item> =
        when (viewType) {
            VIEW_TYPE_PAYMENT_SOURCE -> PaymentSourceItemViewHolder(this)
            VIEW_TYPE_PAYMENT_ADD -> PaymentSourceAddViewHolder(this)
            VIEW_TYPE_HEADER -> PaymentSourceHeaderViewHolder(this)
            else -> throw invalidViewTypeException(viewType)
        }

    private class PaymentSourceItemViewHolder(
        adapter: PaymentSourceAdapter,
    ) : MGRecyclerViewHolder<PaymentSourceAdapter, Item>(R.layout.payment_method_list_item, adapter) {
        override fun onConfigure(position: Int, data: Item) {
            val item = data as PaymentSourceItem
            itemView.findViewById<PaymentSourceView>(R.id.payment_method_summary)
                .bind(item.paymentSource, item.isPremium)
            itemView.findViewById<android.view.View>(R.id.payment_method_edit)
                .setOnClickListener { adapter.onEditPaymentSource(item.paymentSource) }
        }
    }

    private class PaymentSourceAddViewHolder(
        adapter: PaymentSourceAdapter,
    ) : MGRecyclerViewHolder<PaymentSourceAdapter, Item>(R.layout.payment_method_list_add_item, adapter) {
        override fun onConfigure(position: Int, data: Item) {
            itemView.setOnClickListener { adapter.onAddClick() }
        }
    }

    private class PaymentSourceHeaderViewHolder(
        adapter: PaymentSourceAdapter,
    ) : MGRecyclerViewHolder<PaymentSourceAdapter, Item>(R.layout.payment_method_list_header, adapter) {
        override fun onConfigure(position: Int, data: Item) {
            (itemView as TextView).setText(
                when ((data as PaymentSourceHeader).headerType) {
                    PaymentSourceHeader.Type.DEFAULT -> R.string._default
                    PaymentSourceHeader.Type.OTHER -> R.string.other
                },
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_PAYMENT_SOURCE = 0
        const val VIEW_TYPE_PAYMENT_ADD = 1
        const val VIEW_TYPE_HEADER = 2
    }
}
