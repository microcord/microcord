package com.discord.widgets.servers.member_verification

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppFragment
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.view.rounded.RoundedRelativeLayout
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.utilities.view.text.addBindedTextWatcher
import com.discord.views.CheckedSetting
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItem
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemApproveTerms
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemMultipleChoice
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemParagraph
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTerm
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTermsHeader
import com.discord.widgets.servers.member_verification.form_fields.MemberVerificationItemTextInput
import com.google.android.material.textfield.TextInputEditText

class MemberVerificationRulesAdapter(
    recycler: RecyclerView,
    val fragment: AppFragment,
) : MGRecyclerAdapterSimple<MemberVerificationItem>(recycler) {

    var onUpdateRulesApproval: (Boolean) -> Unit = {}
    var onUserInputDataEntered: (Int, Any) -> Unit = { _, _ -> }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<*, MemberVerificationItem> = when (viewType) {
        MemberVerificationItem.HEADER -> HeaderItemHolder(this)
        MemberVerificationItem.TERMS_HEADER -> TermsHeaderItemHolder(this)
        MemberVerificationItem.APPROVE_TERMS -> ApproveTermsItemHolder(this)
        MemberVerificationItem.TERM -> TermItemHolder(this)
        MemberVerificationItem.TEXT_INPUT -> TextInputItemHolder(this)
        MemberVerificationItem.PARAGRAPH -> ParagraphItemHolder(this)
        MemberVerificationItem.MULTIPLE_CHOICE -> MultipleChoiceItemHolder(this)
        else -> throw invalidViewTypeException(viewType)
    }

    private class HeaderItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_header_item,
            adapter,
        ) {
        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            (itemView as TextView).text = (data as MemberVerificationItemHeader).title
        }
    }

    private class TermsHeaderItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_header_item,
            adapter,
        ) {
        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            data as MemberVerificationItemTermsHeader
            (itemView as TextView).setText(R.string.member_verification_form_rules_label)
        }
    }

    private class ApproveTermsItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_approve_terms_item,
            adapter,
        ) {
        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            val item = data as MemberVerificationItemApproveTerms
            (itemView as CheckedSetting).apply {
                isChecked = item.isApproved
                setContainerClickListener { adapter.onUpdateRulesApproval(!item.isApproved) }
            }
        }
    }

    private class TermItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_rule_item,
            adapter,
        ) {
        private val description = itemView.findViewById<SimpleDraweeSpanTextView>(R.id.member_verification_rule_description)
        private val divider = itemView.findViewById<View>(R.id.member_verification_rule_divider)
        private val index = itemView.findViewById<TextView>(R.id.member_verification_rule_index)
        private val root = itemView as RoundedRelativeLayout

        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            val item = data as MemberVerificationItemTerm
            index.text = itemView.context.getString(R.string.member_verification_rule_index)
                .replace("{ruleIndex}", item.index.toString())
            description.setDraweeSpanStringBuilder(
                AstRenderer.render(
                    item.ast,
                    MessageRenderContext(
                        context = itemView.context,
                        myId = 0L,
                        isAnimationEnabled = item.allowAnimatedEmojis,
                        channelNames = item.channelNames,
                        roles = item.roles,
                    ),
                ),
            )
            divider.visibility = if (item.isLastItem) View.GONE else View.VISIBLE
            val radius = dpToPixels(4).toFloat()
            if (item.isFirstItem) {
                root.updateTopLeftRadius(radius)
                root.updateTopRightRadius(radius)
            }
            if (item.isLastItem) {
                root.updateBottomLeftRadius(radius)
                root.updateBottomRightRadius(radius)
            }
        }
    }

    private class TextInputItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_text_input_item,
            adapter,
        ) {
        private val input = itemView.findViewById<TextInputEditText>(R.id.member_verification_text_input_field)
        private var fieldIndex = 0

        init {
            input.addBindedTextWatcher(adapter.fragment) {
                adapter.onUserInputDataEntered(fieldIndex, it.toString())
            }
        }

        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            val item = data as MemberVerificationItemTextInput
            fieldIndex = item.fieldIndex
            input.setText(item.response)
            input.setSelection(item.response?.length ?: 0)
        }
    }

    private class ParagraphItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_paragraph_item,
            adapter,
        ) {
        private val input = itemView.findViewById<TextInputEditText>(R.id.member_verification_paragraph_field)
        private var fieldIndex = 0

        init {
            input.addBindedTextWatcher(adapter.fragment) {
                adapter.onUserInputDataEntered(fieldIndex, it.toString())
            }
        }

        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            val item = data as MemberVerificationItemParagraph
            fieldIndex = item.fieldIndex
            input.setText(item.response)
            input.setSelection(item.response?.length ?: 0)
        }
    }

    private class MultipleChoiceItemHolder(adapter: MemberVerificationRulesAdapter) :
        MGRecyclerViewHolder<MemberVerificationRulesAdapter, MemberVerificationItem>(
            R.layout.widget_member_verification_multiple_choice_item,
            adapter,
        ) {
        private val radioGroup = itemView as RadioGroup

        override fun onConfigure(position: Int, data: MemberVerificationItem) {
            val item = data as MemberVerificationItemMultipleChoice
            createMultipleChoiceButtons(item.choices) { choice ->
                adapter.onUserInputDataEntered(item.fieldIndex, choice)
            }
            item.response?.let { (radioGroup.getChildAt(it) as RadioButton).isChecked = true }
        }

        private fun createMultipleChoiceButtons(choices: List<String>, onItemSelected: (Int) -> Unit) {
            choices.forEachIndexed { index, choice ->
                val button = if (index >= radioGroup.childCount) {
                    (LayoutInflater.from(itemView.context).inflate(
                        R.layout.widget_member_verification_multiple_choice_radio_item,
                        radioGroup,
                        false,
                    ) as RadioButton).also { radioGroup.addView(it) }
                } else {
                    radioGroup.getChildAt(index) as RadioButton
                }
                button.text = choice
                button.setOnCheckedChangeListener { _, checked -> if (checked) onItemSelected(index) }
            }
            for (index in choices.size until radioGroup.childCount) {
                radioGroup.getChildAt(index).visibility = View.GONE
            }
        }
    }
}
