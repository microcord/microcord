package com.discord.widgets.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsChatInputCustomizationBinding
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.ChatInputCustomization
import com.discord.utilities.feature.ChatInputCustomization.Button
import com.discord.utilities.feature.ChatInputCustomization.Entry
import com.discord.utilities.feature.ChatInputCustomization.Zone
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.facebook.drawee.view.SimpleDraweeView
import com.google.android.material.snackbar.Snackbar
import kotlin.math.abs

class WidgetSettingsChatInputCustomization : AppFragment(R.layout.widget_settings_chat_input_customization) {
    private val entries = ChatInputCustomization.get().toMutableList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.chat_input_customization_title)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsChatInputCustomizationBinding.bind(view)
        val restartNotice = Snackbar.make(
            binding.root,
            R.string.user_settings_restart_app_mobile,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
        val toggles = mapOf(
            Button.PROFILE to binding.chatInputCustomizationProfile,
            Button.ATTACHMENT to binding.chatInputCustomizationAttachment,
            Button.APPS to binding.chatInputCustomizationApps,
            Button.EXPAND to binding.chatInputCustomizationExpand,
            Button.EXPRESSION to binding.chatInputCustomizationExpression,
            Button.SEND to binding.chatInputCustomizationSend,
        )
        lateinit var changed: () -> Unit
        val bindToggles = {
            toggles.forEach { (button, toggle) ->
                val entry = entries.first { it.button == button }
                toggle.apply {
                    setText(context.getString(button.label))
                    setSubtext(context.getString(entry.zone.label))
                    setOnCheckedListener { enabled ->
                        val index = entries.indexOfFirst { it.button == button }
                        entries[index] = entries[index].copy(
                            zone = if (enabled) ChatInputCustomization.defaultZone(button) else Zone.HIDDEN,
                        )
                        changed()
                    }
                    setChecked(entry.zone != Zone.HIDDEN, false)
                    setContainerClickListener {
                        val index = entries.indexOfFirst { it.button == button }
                        entries[index] = entries[index].copy(
                            zone = if (entry.zone == Zone.HIDDEN) {
                                ChatInputCustomization.defaultZone(button)
                            } else {
                                Zone.HIDDEN
                            },
                        )
                        changed()
                    }
                }
            }
        }
        changed = {
            ChatInputCustomization.save(entries)
            bindToggles()
            renderPreview(binding)
            restartNotice.show()
        }
        bindToggles()
        installDropTarget(binding.chatInputCustomizationLeft, { Zone.LEFT }, changed)
        installDropTarget(binding.chatInputCustomizationInside, { event ->
            if (event.x > binding.chatInputCustomizationHint.x + binding.chatInputCustomizationHint.width / 2f) {
                Zone.INSIDE_END
            } else {
                Zone.INSIDE
            }
        }, changed)
        installDropTarget(binding.chatInputCustomizationRight, { Zone.RIGHT }, changed)
        renderPreview(binding)
    }

    private fun renderPreview(binding: WidgetSettingsChatInputCustomizationBinding) {
        val zones = mapOf(
            Zone.LEFT to binding.chatInputCustomizationLeft,
            Zone.INSIDE to binding.chatInputCustomizationInside,
            Zone.RIGHT to binding.chatInputCustomizationRight,
        )
        zones.values.forEach { zone ->
            (zone.childCount - 1 downTo 0).forEach { index ->
                if (zone.getChildAt(index) !== binding.chatInputCustomizationHint) zone.removeViewAt(index)
            }
        }
        entries.filter { it.zone != Zone.HIDDEN }.forEach { entry ->
            val target = zones.getValue(if (entry.zone == Zone.INSIDE_END) Zone.INSIDE else entry.zone)
            val button = previewButton(target.context, entry)
            if (entry.zone == Zone.INSIDE) {
                target.addView(button, target.indexOfChild(binding.chatInputCustomizationHint).coerceAtLeast(0))
            } else {
                target.addView(button)
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun previewButton(context: Context, entry: Entry): View {
        val button = entry.button
        val view: View = if (button == Button.PROFILE) {
            val me = StoreStream.getUsers().getMe()
            val guildId = StoreStream.getChannelsSelected().getSelectedChannel()?.guildId ?: 0L
            SimpleDraweeView(context).apply {
                MGImages.setRoundingParams(this, cornerRadius = 0f, circle = true)
                setImageURI(
                    IconUtils.getForGuildMemberOrUser(
                        me,
                        StoreStream.getGuilds().getMember(guildId, me.id),
                        size = 128,
                    ),
                )
            }
        } else {
            AppCompatImageButton(context).apply {
                setImageResource(icon(entry))
                ImageViewCompat.setImageTintList(
                    this,
                    android.content.res.ColorStateList.valueOf(
                        ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal),
                    ),
                )
                background = null
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
        }
        return view.apply {
            tag = button
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            contentDescription = context.getString(button.label)
            var downX = 0f
            var downY = 0f
            setOnTouchListener { dragged, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.x
                        downY = event.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > dp(8) || abs(event.y - downY) > dp(8)) {
                        dragged.startDragAndDrop(
                            ClipData.newPlainText("chat-input-button", button.name),
                            View.DragShadowBuilder(dragged),
                            button,
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
        }
    }

    private fun installDropTarget(
        target: LinearLayout,
        zone: (DragEvent) -> Zone,
        changed: () -> Unit,
    ) {
        target.setOnDragListener { view, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> view.alpha = 0.65f
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> view.alpha = 1f
                DragEvent.ACTION_DROP -> {
                    view.alpha = 1f
                    val button = event.localState as? Button ?: return@setOnDragListener false
                    val visibleChildren = (0 until target.childCount).map(target::getChildAt).filter { it.tag is Button }
                    val position = if (visibleChildren.isEmpty() || target.width == 0) 0 else {
                        ((event.x / target.width) * (visibleChildren.size + 1)).toInt().coerceIn(0, visibleChildren.size)
                    }
                    move(button, zone(event), position)
                    changed()
                }
            }
            true
        }
    }

    private fun move(button: Button, zone: Zone, zonePosition: Int) {
        val moving = entries.first { it.button == button }.copy(zone = zone)
        entries.removeAll { it.button == button }
        val sameZone = entries.withIndex().filter { it.value.zone == zone }
        val insertion = sameZone.getOrNull(zonePosition)?.index
            ?: sameZone.lastOrNull()?.index?.plus(1)
            ?: entries.size
        entries.add(insertion.coerceIn(0, entries.size), moving)
    }

    private fun icon(entry: Entry) = when (entry.button) {
        Button.PROFILE -> R.drawable.ic_account_circle_grey_24dp
        Button.ATTACHMENT -> if (entry.zone == Zone.INSIDE || entry.zone == Zone.INSIDE_END) {
            R.drawable.ic_add_circle
        } else {
            R.drawable.ic_add_24dp
        }
        Button.APPS -> R.drawable.ic_compass
        Button.EXPAND -> R.drawable.ic_carot_right_24dp
        Button.EXPRESSION -> R.drawable.ic_emoji_24dp
        Button.SEND -> R.drawable.ic_send_24dp
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun launch(context: Context) = AppScreenRouter.launch(
            context,
            WidgetSettingsChatInputCustomization::class.java,
            Intent(),
        )
    }
}
