package com.discord.widgets.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.LayoutInflater
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.widget.ImageViewCompat
import com.discord.R
import com.discord.api.channel.getDisplayNameOrDefault
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.isDMStatusVisible
import com.discord.api.channel.isPrivate
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsHeaderCustomizationBinding
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.HeaderCustomization
import com.discord.utilities.feature.HeaderCustomization.Action
import com.discord.utilities.feature.HeaderCustomization.Entry
import com.discord.utilities.feature.HeaderCustomization.Identity
import com.discord.utilities.feature.HeaderCustomization.Surface
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.images.MGImages
import com.discord.plugins.PluginUiRegistry
import com.discord.views.CheckedSetting
import com.discord.api.channel.Channel
import dev.mantikafasi.manticord.plugins.api.pluginChannelContext
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import kotlin.math.abs
import androidx.core.view.ViewCompat

class WidgetSettingsHeaderCustomization : AppFragment(R.layout.widget_settings_header_customization) {
    private var surface = Surface.DM
    private var entries = HeaderCustomization.get(surface).toMutableList()

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        setActionBarTitle(R.string.header_customization_title)
        setActionBarDisplayHomeAsUpEnabled()
        val binding = WidgetSettingsHeaderCustomizationBinding.bind(view)
        val restartNotice = Snackbar.make(
            binding.root,
            R.string.user_settings_restart_app_mobile,
            Snackbar.LENGTH_INDEFINITE,
        ).setAction(R.string.bundle_ready_restart) { restartApp(requireContext()) }
        val toggles = mapOf(
            Action.VOICE to binding.headerCustomizationVoice,
            Action.VIDEO to binding.headerCustomizationVideo,
            Action.SEARCH to binding.headerCustomizationSearch,
            Action.MEMBERS to binding.headerCustomizationMembers,
            Action.PINS to binding.headerCustomizationPins,
        )
        val identityToggles = mapOf(
            Identity.HEADER to binding.headerCustomizationShowHeader,
            Identity.AVATAR to binding.headerCustomizationShowAvatar,
            Identity.TITLE to binding.headerCustomizationShowTitle,
            Identity.STATUS to binding.headerCustomizationShowStatus,
        )
        lateinit var changed: () -> Unit
        lateinit var bindToggles: () -> Unit
        lateinit var updateIdentity: (Identity, Boolean) -> Unit
        bindToggles = {
            val headerVisible = HeaderCustomization.identityVisible(surface, Identity.HEADER)
            toggles.forEach { (action, toggle) ->
                toggle.visibility = if (action in HeaderCustomization.actions(surface)) View.VISIBLE else View.GONE
                if (action !in HeaderCustomization.actions(surface)) return@forEach
                val entry = entries.first { it.action == action }
                toggle.apply {
                    isEnabled = headerVisible
                    setText(context.getString(action.label))
                    setOnCheckedListener { enabled ->
                        entries[entries.indexOfFirst { it.action == action }] = entry.copy(visible = enabled)
                        changed()
                    }
                    setChecked(entry.visible, false)
                    setContainerClickListener(if (headerVisible) View.OnClickListener {
                        entries[entries.indexOfFirst { it.action == action }] = entry.copy(visible = !entry.visible)
                        changed()
                    } else null)
                }
            }
            identityToggles.forEach { (identity, toggle) ->
                val available = when (identity) {
                    Identity.HEADER -> true
                    Identity.STATUS -> headerVisible && surface == Surface.DM &&
                        HeaderCustomization.identityVisible(surface, Identity.AVATAR)
                    Identity.AVATAR, Identity.TITLE -> headerVisible
                }
                toggle.visibility = if (identity == Identity.STATUS && surface != Surface.DM) View.GONE else View.VISIBLE
                toggle.isEnabled = available
                toggle.apply {
                    setText(
                        context.getString(
                            when (identity) {
                                Identity.HEADER -> R.string.header_customization_show_header
                                Identity.AVATAR -> if (surface == Surface.DM) {
                                    R.string.header_customization_avatar
                                } else {
                                    R.string.header_customization_channel_icon
                                }
                                Identity.TITLE -> R.string.header_customization_channel_name
                                Identity.STATUS -> R.string.header_customization_status_indicator
                            },
                        ),
                    )
                    setOnCheckedListener { visible -> updateIdentity(identity, visible) }
                    setChecked(HeaderCustomization.identityVisible(surface, identity), false)
                    setContainerClickListener(if (available) View.OnClickListener {
                        updateIdentity(identity, !HeaderCustomization.identityVisible(surface, identity))
                    } else null)
                }
            }
            val pluginActions = PluginUiRegistry.headers()
            binding.headerCustomizationPluginsHeader.visibility =
                if (pluginActions.isEmpty()) View.GONE else View.VISIBLE
            binding.headerCustomizationPlugins.removeAllViews()
            pluginActions.forEach { owned ->
                val contribution = owned.contribution
                val visible = HeaderCustomization.pluginVisible(surface, owned.owner, contribution.id)
                val toggle = LayoutInflater.from(requireContext()).inflate(
                    R.layout.widget_settings_plugin_toggle,
                    binding.headerCustomizationPlugins,
                    false,
                ) as CheckedSetting
                toggle.apply {
                    isEnabled = headerVisible
                    setText(contribution.title)
                    setSubtext(owned.owner)
                    setChecked(visible, false)
                    fun update(next: Boolean) {
                        HeaderCustomization.setPluginVisible(surface, owned.owner, contribution.id, next)
                        setChecked(next, false)
                        renderPreview(binding)
                        PluginUiRegistry.notifyHeaderChanged()
                    }
                    setOnCheckedListener { next -> update(next) }
                    setContainerClickListener(if (headerVisible) View.OnClickListener { update(!isChecked) } else null)
                }
                binding.headerCustomizationPlugins.addView(toggle)
            }
        }
        updateIdentity = { identity, visible ->
            HeaderCustomization.setIdentityVisible(surface, identity, visible)
            if (identity == Identity.AVATAR && !visible) {
                HeaderCustomization.setIdentityVisible(surface, Identity.STATUS, false)
            }
            bindToggles()
            renderPreview(binding)
            restartNotice.show()
        }
        changed = {
            HeaderCustomization.save(surface, entries)
            bindToggles()
            renderPreview(binding)
            restartNotice.show()
        }
        binding.headerCustomizationTabs.apply {
            addTab(newTab().setText(R.string.direct_messages), true)
            addTab(newTab().setText(R.string.channels))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    surface = if (tab.position == 0) Surface.DM else Surface.CHANNEL
                    entries = HeaderCustomization.get(surface).toMutableList()
                    bindToggles()
                    renderPreview(binding)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        bindToggles()
        binding.headerCustomizationActions.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> target.alpha = 0.65f
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> target.alpha = 1f
                DragEvent.ACTION_DROP -> {
                    target.alpha = 1f
                    val action = event.localState as? Action ?: return@setOnDragListener false
                    val buttons = (0 until binding.headerCustomizationActions.childCount)
                        .map(binding.headerCustomizationActions::getChildAt)
                        .filter { it.tag is Action }
                    val position = if (buttons.isEmpty() || binding.headerCustomizationActions.width == 0) 0 else {
                        ((event.x / binding.headerCustomizationActions.width) * (buttons.size + 1))
                            .toInt().coerceIn(0, buttons.size)
                    }
                    move(action, position)
                    changed()
                }
            }
            true
        }
        renderPreview(binding)
    }

    private fun renderPreview(binding: WidgetSettingsHeaderCustomizationBinding) {
        binding.headerCustomizationPreview.visibility = if (
            HeaderCustomization.identityVisible(surface, Identity.HEADER)
        ) View.VISIBLE else View.GONE
        if (surface == Surface.DM) {
            val channel = StoreStream.getChannelsSelected().getSelectedChannel()?.takeIf { it.isPrivate() }
            binding.headerCustomizationChannelIcon.visibility = View.GONE
            binding.headerCustomizationTitle.text = channel?.getDisplayNameOrDefault(requireContext(), false)
                ?: getString(R.string.direct_messages)
            MGImages.setRoundingParams(binding.headerCustomizationAvatar, cornerRadius = 0f, circle = true)
            MGImages.setImage(
                binding.headerCustomizationAvatar,
                IconUtils.getForChannel(channel, size = 24)
                    ?: IconUtils.getForUser(StoreStream.getUsers().getMe(), size = 24),
                width = 24,
                height = 24,
                useSmallCache = true,
            )
            binding.headerCustomizationAvatar.visibility =
                if (HeaderCustomization.identityVisible(surface, Identity.AVATAR)) View.VISIBLE else View.GONE
            binding.headerCustomizationTitle.visibility =
                if (HeaderCustomization.identityVisible(surface, Identity.TITLE)) View.VISIBLE else View.GONE
            val presence = channel?.getDMRecipient()?.id
                ?.let { StoreStream.getPresences().getPresences()[it] }
            val showStatus = FeatureFlags.chatHeaderStatusIndicatorsEnabled() &&
                HeaderCustomization.identityVisible(surface, Identity.AVATAR) &&
                HeaderCustomization.identityVisible(surface, Identity.STATUS) &&
                channel?.isDMStatusVisible(presence) == true
            binding.headerCustomizationStatus.visibility = if (showStatus) View.VISIBLE else View.GONE
            if (showStatus) binding.headerCustomizationStatus.setPresence(presence)
        } else {
            binding.headerCustomizationAvatar.visibility = View.GONE
            binding.headerCustomizationChannelIcon.visibility =
                if (HeaderCustomization.identityVisible(surface, Identity.AVATAR)) View.VISIBLE else View.GONE
            binding.headerCustomizationTitle.visibility =
                if (HeaderCustomization.identityVisible(surface, Identity.TITLE)) View.VISIBLE else View.GONE
            binding.headerCustomizationStatus.visibility = View.GONE
            binding.headerCustomizationTitle.setText(R.string.header_customization_preview_title)
        }
        binding.headerCustomizationActions.removeAllViews()
        entries.filter(Entry::visible).forEach { entry ->
            binding.headerCustomizationActions.addView(previewButton(binding.root.context, entry.action))
        }
        val selectedChannel = StoreStream.getChannelsSelected().getSelectedChannel()
        val pluginContext = pluginChannelContext(
            channelId = selectedChannel?.id ?: 0L,
            guildId = selectedChannel?.guildId?.takeIf { it > 0L },
            channelType = selectedChannel?.type ?: if (surface == Surface.DM) Channel.DM else Channel.GUILD_TEXT,
            isPrivate = surface == Surface.DM,
        )
        PluginUiRegistry.headers().forEach { owned ->
            val contribution = owned.contribution
            if (!HeaderCustomization.pluginVisible(surface, owned.owner, contribution.id)) return@forEach
            if (!runCatching { contribution.isVisible(pluginContext) }.getOrDefault(false)) return@forEach
            val pluginView = runCatching { contribution.createView(binding.root.context) }.getOrNull()
                ?: return@forEach
            runCatching { contribution.bind(pluginView, pluginContext) }
            binding.headerCustomizationActions.addView(pluginView)
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun previewButton(context: Context, action: Action) = AppCompatImageButton(context).apply {
        tag = action
        setImageResource(icon(action))
        ImageViewCompat.setImageTintList(
            this,
            ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)),
        )
        background = null
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(40), dp(40))
        contentDescription = context.getString(action.label)
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
                    ViewCompat.startDragAndDrop(
                        dragged,
                        ClipData.newPlainText("header-action", action.name),
                        View.DragShadowBuilder(dragged),
                        action,
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

    private fun move(action: Action, position: Int) {
        val moving = entries.first { it.action == action }
        entries.removeAll { it.action == action }
        val visible = entries.withIndex().filter { it.value.visible }
        val insertion = visible.getOrNull(position)?.index
            ?: visible.lastOrNull()?.index?.plus(1)
            ?: entries.size
        entries.add(insertion.coerceIn(0, entries.size), moving)
    }

    private fun icon(action: Action) = when (action) {
        Action.VOICE -> R.drawable.ic_call_24dp
        Action.VIDEO -> R.drawable.ic_videocam_white_24dp
        Action.SEARCH -> R.drawable.ic_search_white_24dp
        Action.MEMBERS -> R.drawable.ic_members_24dp
        Action.PINS -> R.drawable.ic_pin_24dp
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        fun launch(context: Context) = AppScreenRouter.launch(
            context,
            WidgetSettingsHeaderCustomization::class.java,
            Intent(),
        )
    }
}
