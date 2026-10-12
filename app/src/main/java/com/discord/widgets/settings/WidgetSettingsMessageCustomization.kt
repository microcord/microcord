package com.discord.widgets.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsMessageCustomizationBinding
import com.discord.databinding.WidgetChatListAdapterItemTextBinding
import com.discord.stores.StoreStream
import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.MessageCustomization
import com.discord.utilities.feature.MessageCustomization.CoreElement
import com.discord.utilities.feature.MessageCustomization.Entry
import com.discord.utilities.feature.MessageCustomization.Section
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.drawable.getThemedDrawableRes
import com.discord.views.ReactionView
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs
import androidx.core.view.ViewCompat

class WidgetSettingsMessageCustomization : AppFragment(R.layout.widget_settings_message_customization) {
    private val entries = MessageCustomization.get().toMutableList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.message_customization_title)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsMessageCustomizationBinding.bind(view)
        mapOf(
            binding.messageCustomizationPluginHeader to PluginSurfaceCustomization.Surface.MESSAGE_HEADER,
            binding.messageCustomizationPluginContent to PluginSurfaceCustomization.Surface.MESSAGE_CONTENT,
            binding.messageCustomizationPluginStandalone to PluginSurfaceCustomization.Surface.MESSAGE_STANDALONE,
        ).forEach { (row, surface) ->
            row.setOnClickListener {
                WidgetSettingsPluginSurfaceCustomization.launch(requireContext(), surface)
            }
        }
        val restartNotice = Snackbar.make(
            binding.root,
            R.string.user_settings_restart_app_mobile,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
        val sectionToggles = mapOf(
            Section.CORE to binding.messageCustomizationCore,
            Section.MEDIA to binding.messageCustomizationMedia,
            Section.EMBEDS to binding.messageCustomizationEmbeds,
            Section.STICKERS to binding.messageCustomizationStickers,
            Section.COMPONENTS to binding.messageCustomizationComponents,
            Section.CARDS to binding.messageCustomizationCards,
            Section.REACTIONS to binding.messageCustomizationReactions,
            Section.THREAD to binding.messageCustomizationThread,
        )
        val coreToggles = mapOf(
            CoreElement.AVATAR to binding.messageCustomizationAvatar,
            CoreElement.DECORATION_STATUS to binding.messageCustomizationDecorationStatus,
            CoreElement.AUTHOR to binding.messageCustomizationAuthor,
            CoreElement.TAGS to binding.messageCustomizationTags,
            CoreElement.GUILD_TAG to binding.messageCustomizationGuildTag,
            CoreElement.TIMESTAMP to binding.messageCustomizationTimestamp,
            CoreElement.REPLY to binding.messageCustomizationReply,
            CoreElement.CONTENT to binding.messageCustomizationContent,
        )
        lateinit var changed: () -> Unit
        lateinit var updateCore: (CoreElement, Boolean) -> Unit
        val updateSection = { section: Section, visible: Boolean ->
            val index = entries.indexOfFirst { it.section == section }
            entries[index] = entries[index].copy(visible = visible)
            changed()
        }
        val bindToggles = {
            sectionToggles.forEach { (section, toggle) ->
                val entry = entries.first { it.section == section }
                toggle.apply {
                    setText(context.getString(section.label))
                    setChecked(entry.visible, false)
                    setOnCheckedListener { visible -> updateSection(section, visible) }
                    setContainerClickListener { updateSection(section, !entry.visible) }
                }
            }
            coreToggles.forEach { (element, toggle) ->
                toggle.apply {
                    setText(context.getString(element.label))
                    setChecked(MessageCustomization.coreVisible(element), false)
                    setOnCheckedListener { visible -> updateCore(element, visible) }
                    setContainerClickListener {
                        updateCore(element, !MessageCustomization.coreVisible(element))
                    }
                }
            }
        }
        updateCore = { element, visible ->
            MessageCustomization.setCoreVisible(element, visible)
            bindToggles()
            renderPreview(binding)
            restartNotice.show()
        }
        changed = {
            MessageCustomization.save(entries)
            bindToggles()
            renderPreview(binding)
            restartNotice.show()
        }
        bindToggles()
        binding.messageCustomizationOrder.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> target.alpha = 0.65f
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> target.alpha = 1f
                DragEvent.ACTION_DROP -> {
                    target.alpha = 1f
                    val section = event.localState as? Section ?: return@setOnDragListener false
                    val children = (0 until binding.messageCustomizationOrder.childCount)
                        .map(binding.messageCustomizationOrder::getChildAt)
                        .filter { it.tag is Section }
                    val position = children.indexOfFirst { event.x < it.x + it.width / 2f }
                        .takeIf { it >= 0 } ?: children.size
                    move(section, position)
                    changed()
                }
            }
            true
        }
        renderPreview(binding)
    }

    private fun renderPreview(binding: WidgetSettingsMessageCustomizationBinding) {
        binding.messageCustomizationPreview.removeAllViews()
        binding.messageCustomizationOrder.removeAllViews()
        entries.filter(Entry::visible).filter { it.section == Section.CORE || it.section == Section.REACTIONS }
            .forEach { entry ->
                binding.messageCustomizationPreview.addView(
                    if (entry.section == Section.CORE) corePreview(binding.root.context)
                    else reactionPreview(binding.root.context),
                )
        }
        entries.filter(Entry::visible).forEach { entry ->
            binding.messageCustomizationOrder.addView(orderChip(binding.root.context, entry.section))
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun orderChip(context: Context, section: Section): View {
        val view = TextView(context).apply {
            text = context.getString(section.label)
            gravity = Gravity.CENTER
            setTextColor(ColorCompat.getThemedColor(context, R.attr.colorTextNormal))
            setBackgroundResource(R.drawable.drawable_flex_input_bg)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)).apply {
                marginEnd = dp(6)
            }
        }
        view.tag = section
        var downX = 0f
        var downY = 0f
        view.setOnTouchListener { dragged, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    true
                }
                MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > dp(8) || abs(event.y - downY) > dp(8)) {
                    ViewCompat.startDragAndDrop(
                        dragged,
                        ClipData.newPlainText("message-section", section.name),
                        View.DragShadowBuilder(dragged),
                        section,
                        0,
                    )
                    true
                } else false
                MotionEvent.ACTION_UP -> {
                    dragged.performClick()
                    true
                }
                else -> true
            }
        }
        return view
    }

    private fun corePreview(context: Context): View {
        val binding = WidgetChatListAdapterItemTextBinding.inflate(layoutInflater)
        val me = StoreStream.getUsers().getMe()
        val avatar = MessageCustomization.coreVisible(CoreElement.AVATAR)
        binding.chatListAdapterItemTextAvatar.visibility = if (avatar) View.VISIBLE else View.GONE
        if (avatar) {
            binding.chatListAdapterItemTextAvatar.setImageURI(
                IconUtils.getForUser(me, size = 40),
            )
        }
        binding.chatListAdapterItemTextStatus.apply {
            visibility = if (
            avatar && MessageCustomization.coreVisible(CoreElement.DECORATION_STATUS)
            ) View.VISIBLE else View.GONE
            setPresence(StoreStream.getPresences().getLocalPresence())
        }
        binding.chatListAdapterItemTextAvatarDecoration.visibility = View.GONE
        binding.chatListAdapterItemTextName.apply {
            text = me.username
            visibility = coreVisibility(CoreElement.AUTHOR)
        }
        if (!MessageCustomization.coreVisible(CoreElement.AUTHOR)) {
            ConstraintSet().apply {
                clone(binding.chatListAdapterItemTextHeader)
                clear(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.TOP)
                clear(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.BOTTOM)
                clear(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.START)
                clear(R.id.chat_list_adapter_item_communication_disabled_icon, ConstraintSet.END)
                connect(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                connect(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                connect(R.id.chat_list_adapter_item_text_guild_tag, ConstraintSet.START, R.id.chat_list_adapter_item_communication_disabled_icon, ConstraintSet.END)
                clear(R.id.chat_list_adapter_item_text_timestamp, ConstraintSet.BASELINE)
                clear(R.id.chat_list_adapter_item_text_timestamp, ConstraintSet.END)
                connect(R.id.chat_list_adapter_item_text_timestamp, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
                connect(R.id.chat_list_adapter_item_text_timestamp, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
                applyTo(binding.chatListAdapterItemTextHeader)
            }
        }
        binding.chatListAdapterItemTextRoleIcon.visibility = View.GONE
        binding.chatListAdapterItemTextGuildTag.apply {
            bind(me.primaryGuild)
            if (!MessageCustomization.coreVisible(CoreElement.GUILD_TAG)) visibility = View.GONE
        }
        binding.chatListAdapterItemTextTag.apply {
            text = context.getString(R.string.bot_tag_bot)
            visibility = coreVisibility(CoreElement.TAGS)
        }
        binding.chatListAdapterItemTextTimestamp.apply {
            text = context.getString(R.string.message_customization_preview_timestamp)
            visibility = coreVisibility(CoreElement.TIMESTAMP)
        }
        val reply = coreVisibility(CoreElement.REPLY)
        binding.chatListAdapterItemTextDecorator.visibility = reply
        binding.chatListAdapterItemTextDecoratorReplyLinkIcon.visibility = reply
        binding.chatListAdapterItemTextDecoratorAvatar.visibility = View.GONE
        binding.chatListAdapterItemTextReplyGuildTag.visibility = View.GONE
        binding.chatListAdapterItemTextDecoratorReplyName.text =
            context.getString(R.string.message_customization_preview_reply_author)
        binding.chatListAdapterItemTextReplyContent.text = SpannableString(
            context.getString(R.string.message_customization_preview_reply),
        ).apply {
            setSpan(LeadingMarginSpan.Standard(dp(64)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.chatListAdapterItemText.apply {
            text = context.getString(R.string.message_customization_preview_content)
            visibility = coreVisibility(CoreElement.CONTENT)
        }
        binding.chatListAdapterItemAttachments.visibility = View.GONE
        binding.chatOverlayTypingDots.visibility = View.GONE
        binding.chatListAdapterItemTextError.visibility = View.GONE
        binding.chatListAdapterItemTextLoading.visibility = View.GONE
        binding.chatListAdapterItemCommunicationDisabledIcon.visibility = View.GONE
        (binding.uikitChatGuideline.layoutParams as ConstraintLayout.LayoutParams).guideBegin = if (avatar) {
                resources.getDimensionPixelSize(R.dimen.uikit_guideline_chat)
            } else {
                dp(8)
            }
        binding.root.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(6) }
        return binding.root
    }

    private fun reactionPreview(context: Context) = ReactionView(context).apply {
        configure(
            MessageReaction(3, MessageReactionEmoji(null, "\uD83D\uDC4D", false), true),
            1L,
            false,
        )
        val padding = dp(6)
        setPadding(padding, paddingTop, padding, paddingBottom)
        background = ContextCompat.getDrawable(context, context.getThemedDrawableRes(R.attr.theme_chat_reaction_background))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(26)).apply {
            marginStart = resources.getDimensionPixelSize(R.dimen.uikit_guideline_chat)
            bottomMargin = dp(6)
        }
    }

    private fun coreVisibility(element: CoreElement) =
        if (MessageCustomization.coreVisible(element)) View.VISIBLE else View.GONE

    private fun move(section: Section, position: Int) {
        val moving = entries.first { it.section == section }
        entries.removeAll { it.section == section }
        val visible = entries.withIndex().filter { it.value.visible }
        val insertion = visible.getOrNull(position)?.index
            ?: visible.lastOrNull()?.index?.plus(1)
            ?: entries.size
        entries.add(insertion.coerceIn(0, entries.size), moving)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun launch(context: Context) = AppScreenRouter.launch(
            context,
            WidgetSettingsMessageCustomization::class.java,
            Intent(),
        )
    }
}
