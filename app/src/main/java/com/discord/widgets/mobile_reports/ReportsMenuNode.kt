package com.discord.widgets.mobile_reports

import android.content.Context
import android.text.Html
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import com.discord.R
import com.discord.api.report.ReportNodeBottomButton
import com.discord.api.report.ReportNodeChild
import com.discord.api.report.ReportNodeElementData
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.user.UserUtils.getUserNameWithDiscriminator
import com.discord.utilities.view.extensions.setEnabledAndAlpha
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.views.CheckedSetting
import com.discord.views.GuildView
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ReportsMenuNode @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ConstraintLayout(context, attrs, defStyleAttr) {

    var handleBlock: (() -> Unit)? = null
    var handleCancel: (() -> Unit)? = null
    var handlePressBottomButton: ((ReportNodeBottomButton) -> Unit)? = null
    var handleSelectChild: ((ReportNodeChild, MobileReportsViewModel.NodeState) -> Unit)? = null
    var handleSubmit: (() -> Unit)? = null
    var viewState: MobileReportsViewModel.NodeState? = null
        private set

    init {
        LayoutInflater.from(context).inflate(R.layout.view_reports_menu_node, this, true)
    }

    fun setup(viewState: MobileReportsViewModel.NodeState) {
        setupSuccess(viewState.successElement)
        setupMessagePreview(viewState.messagePreviewElement)
        setupChannelPreview(viewState.channelPreviewElement)
        setupDirectoryServerPreview(viewState.directoryServerPreviewElement)
        setupEventPreview(viewState.eventPreviewElement)
        setupBreadcrumbs(viewState)
        setupTextElements(viewState)
        setupChildren(viewState)
        setupCheckbox(viewState)
        setupBottomButton(viewState)
        setupBlockUser(viewState.blockUserElement)
        this.viewState = viewState
    }

    private fun setupSuccess(show: Boolean) {
        findViewById<ImageView>(R.id.mobile_reports_node_success_shield).visibility = visible(show)
    }

    private fun setupMessagePreview(preview: MobileReportsViewModel.MessagePreview?) {
        val root = findViewById<View>(R.id.mobile_reports_message_preview)
        root.visibility = visible(preview != null)
        if (preview == null) return
        findViewById<TextView>(R.id.chat_list_adapter_item_text_name).apply {
            text = preview.authorName
            setTextColor(preview.authorNameColor)
        }
        IconUtils.setIcon(
            findViewById<SimpleDraweeView>(R.id.chat_list_adapter_item_text_avatar),
            preview.author,
            R.dimen.avatar_size_small,
        )
        findViewById<SimpleDraweeSpanTextView>(R.id.chat_list_adapter_item_text)
            .setDraweeSpanStringBuilder(preview.text)
        findViewById<ImageView>(R.id.chat_list_adapter_item_chat_attachment_icon).visibility =
            visible(preview.hasEmbeds)
    }

    private fun setupChannelPreview(preview: MobileReportsViewModel.ChannelPreview?) {
        val card = findViewById<MaterialCardView>(R.id.mobile_reports_node_channel_preview)
        card.visibility = visible(preview != null)
        if (preview != null) bindGuildPreview(
            R.id.mobile_reports_channel_preview,
            preview.stageInstance.topic,
            preview.guild.name,
            IconUtils.getForGuild(preview.guild),
            preview.guild.shortName,
        )
    }

    private fun setupEventPreview(preview: MobileReportsViewModel.GuildScheduledEventPreview?) {
        val card = findViewById<MaterialCardView>(R.id.mobile_reports_node_channel_preview)
        card.visibility = visible(preview != null)
        if (preview != null) bindGuildPreview(
            R.id.mobile_reports_channel_preview,
            preview.guild.name,
            preview.event.name,
            IconUtils.getForGuild(preview.guild),
            preview.guild.shortName,
        )
    }

    private fun setupDirectoryServerPreview(preview: MobileReportsViewModel.DirectoryServerPreview?) {
        val show = preview != null
        findViewById<MaterialCardView>(R.id.mobile_reports_node_directory_channel_preview).visibility = visible(show)
        findViewById<TextView>(R.id.mobile_reports_node_directory_channel_preview_title).visibility = visible(show)
        if (preview != null) bindGuildPreview(
            R.id.mobile_reports_directory_channel_preview,
            preview.hub.name,
            preview.directoryEntry.guild.name,
            IconUtils.getForGuild(preview.directoryEntry.guild.id, preview.directoryEntry.guild.icon),
            preview.directoryEntry.guild.name.toShortName(),
        )
    }

    private fun bindGuildPreview(rootId: Int, kicker: String, text: String, icon: String?, shortName: String) {
        val root = findViewById<View>(rootId)
        root.findViewById<TextView>(R.id.kicker).text = kicker
        root.findViewById<LinkifiedTextView>(R.id.text).text = text
        root.findViewById<GuildView>(R.id.image).configure(icon, shortName)
    }

    private fun setupBreadcrumbs(state: MobileReportsViewModel.NodeState) {
        findViewById<MobileReportsBreadcrumbs>(R.id.mobile_reports_node_breadcrumbs).apply {
            visibility = visible(state.breadcrumbsElement != null)
            if (state.breadcrumbsElement != viewState?.breadcrumbsElement) setup(state.breadcrumbsElement)
        }
    }

    private fun setupTextElements(state: MobileReportsViewModel.NodeState) {
        findViewById<TextView>(R.id.mobile_reports_node_header).text = markdown(state.node.header)
        findViewById<LinkifiedTextView>(R.id.mobile_reports_node_subheader).apply {
            visibility = visible(state.node.subheader != null)
            text = markdown(state.node.subheader)
        }
        findViewById<MaterialCardView>(R.id.mobile_reports_node_info_box).visibility =
            visible(state.node.info != null)
        findViewById<TextView>(R.id.mobile_reports_node_info_text).text = markdown(state.node.info)
    }

    private fun setupChildren(state: MobileReportsViewModel.NodeState) {
        if (viewState?.node?.children == state.node.children) return
        findViewById<LinearLayout>(R.id.mobile_reports_node_child_list).apply {
            children.forEach { it.setOnClickListener(null) }
            removeAllViewsInLayout()
            state.node.children.forEach { child ->
                val row = LayoutInflater.from(context)
                    .inflate(R.layout.view_mobile_reports_child, this, false) as FrameLayout
                addView(row)
                row.findViewById<TextView>(R.id.mobile_reports_child_menu_title).text = child.name
                row.setOnClickListener { handleSelectChild?.invoke(child, state) }
            }
        }
    }

    private fun setupCheckbox(state: MobileReportsViewModel.NodeState) {
        val checkbox = state.checkboxElement
        findViewById<LinearLayout>(R.id.mobile_reports_multiselect).apply {
            visibility = visible(checkbox != null)
            if (checkbox == viewState?.checkboxElement) return
            removeAllViewsInLayout()
            checkbox?.data?.forEach { entry -> addCheckbox(entry, checkbox) }
        }
    }

    private fun LinearLayout.addCheckbox(
        entry: ReportNodeElementData,
        checkbox: MobileReportsViewModel.CheckboxElement,
    ) {
        val setting = LayoutInflater.from(context)
            .inflate(R.layout.view_mobile_reports_multicheck_item, this, false) as CheckedSetting
        addView(setting)
        setting.setText(entry.elementValue)
        setting.setChecked(entry in checkbox.selections)
        setting.setOnCheckedListener { checked ->
            if (checked) checkbox.selections.add(entry) else checkbox.selections.remove(entry)
        }
    }

    private fun setupBottomButton(state: MobileReportsViewModel.NodeState) {
        findViewById<MobileReportsBottomButton>(R.id.mobile_reports_node_bottom_button).apply {
            visibility = visible(state.bottomButton != null)
            setup(state.bottomButton, state.submitState, ::bottomButtonClickListener)
        }
    }

    private fun bottomButtonClickListener(button: ReportNodeBottomButton) {
        when (button) {
            is ReportNodeBottomButton.Cancel, is ReportNodeBottomButton.Done -> handleCancel?.invoke()
            is ReportNodeBottomButton.Next -> {
                val state = viewState ?: return
                handleSelectChild?.invoke(ReportNodeChild("", button.target), state)
            }
            is ReportNodeBottomButton.Submit -> handleSubmit?.invoke()
        }
    }

    private fun setupBlockUser(block: MobileReportsViewModel.BlockUserElement?) {
        findViewById<LinearLayout>(R.id.mobile_reports_node_block_user).apply {
            visibility = visible(block != null)
        }
        if (block == null) return
        findViewById<TextView>(R.id.mobile_reports_block_user_name).text =
            block.user.getUserNameWithDiscriminator(
                ColorCompat.getThemedColor(context, R.attr.colorHeaderSecondary),
                0.8f,
            )
        IconUtils.setIcon(
            findViewById<SimpleDraweeView>(R.id.mobile_reports_block_user_avatar),
            block.user,
            R.dimen.avatar_size_standard,
        )
        findViewById<MaterialButton>(R.id.mobile_reports_block_user_button).apply {
            setOnClickListener { handleBlock?.invoke() }
            setEnabledAndAlpha(!block.isBlocked, 0.5f)
            setText(if (block.isBlocked) R.string.blocked else R.string.block)
        }
    }

    private fun markdown(value: String?): CharSequence? = value?.let {
        Html.fromHtml(it.replace(MARKDOWN_LINK, "<a href=\"$2\">$1</a>"))
    }

    private fun visible(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun String.toShortName(): String = replace(Regex("'s "), " ")
        .replace(Regex("(\\w)(\\w*)"), "$1")
        .replace(Regex("\\s"), "")

    private companion object {
        val MARKDOWN_LINK = Regex("\\[([^]]+)]\\(([^)]+)\\)")
    }
}
