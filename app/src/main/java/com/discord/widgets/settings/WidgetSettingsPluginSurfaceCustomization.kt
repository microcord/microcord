package com.discord.widgets.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.view.DragEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.TextViewCompat
import com.discord.R
import com.discord.app.AppFragment
import com.discord.app.AppScreenRouter
import com.discord.databinding.WidgetSettingsPluginSurfaceCustomizationBinding
import com.discord.plugins.PluginUiRegistry
import com.discord.stores.StoreStream
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.feature.PluginSurfaceCustomization
import com.discord.utilities.feature.PluginSurfaceCustomization.Entry
import com.discord.utilities.feature.PluginSurfaceCustomization.ProfileElement
import com.discord.utilities.feature.PluginSurfaceCustomization.Surface
import com.discord.views.CheckedSetting
import com.discord.views.user.UserAvatarPresenceView
import com.discord.views.user.UserAvatarPresenceViewController
import com.discord.widgets.tabs.NavigationTab
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import dev.mantikafasi.manticord.plugins.api.PluginRegistration
import dev.mantikafasi.manticord.plugins.api.pluginBottomTabContext

class WidgetSettingsPluginSurfaceCustomization :
    AppFragment(R.layout.widget_settings_plugin_surface_customization) {

    private var _binding: WidgetSettingsPluginSurfaceCustomizationBinding? = null
    private val binding get() = requireNotNull(_binding)
    private var entries = mutableListOf<Entry>()
    private var registryRegistration: PluginRegistration? = null
    private var previewAvatar: UserAvatarPresenceView? = null
    private var previewAvatarController: UserAvatarPresenceViewController? = null
    private val previewBottomPluginViews = mutableMapOf<String, View>()

    private val surface: Surface
        get() = arguments?.getString(ARG_SURFACE)?.let(Surface::valueOf) ?: Surface.BOTTOM_TABS

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        _binding = WidgetSettingsPluginSurfaceCustomizationBinding.bind(view)
        setActionBarTitle(
            when (surface) {
                Surface.BOTTOM_TABS -> R.string.bottom_tabs_customization_title
                Surface.PROFILE -> R.string.profile_popout_customization_title
                Surface.PROFILE_TABS -> R.string.profile_tabs_customization_title
                Surface.COMPOSER -> error("Composer customization has its own screen")
            },
        )
        setActionBarDisplayHomeAsUpEnabled()
        binding.pluginSurfaceCustomizationDescription.setText(R.string.plugin_surface_customization_description)
        registryRegistration = when (surface) {
            Surface.BOTTOM_TABS -> PluginUiRegistry.addBottomTabListener { view.post(::reload) }
            Surface.PROFILE -> PluginUiRegistry.addProfileListener { view.post(::reload) }
            Surface.PROFILE_TABS -> PluginUiRegistry.addProfileTabListener { view.post(::reload) }
            Surface.COMPOSER -> error("Composer customization has its own screen")
        }
        binding.pluginSurfaceCustomizationItems.setOnDragListener { target, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_ENTERED -> target.alpha = 0.65f
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> target.alpha = 1f
                DragEvent.ACTION_DROP -> {
                    target.alpha = 1f
                    val key = event.localState as? String ?: return@setOnDragListener false
                    val moving = entries.firstOrNull { it.key == key } ?: return@setOnDragListener false
                    val plugin = key.startsWith(PLUGIN_PREFIX)
                    val container = binding.pluginSurfaceCustomizationItems
                    val beforeKey = (0 until container.childCount)
                        .map(container::getChildAt)
                        .firstOrNull { child ->
                            val childKey = child.tag as? String
                            childKey != null && childKey != key && childKey.startsWith(PLUGIN_PREFIX) == plugin &&
                                event.y < (child.top + child.bottom) / 2f
                        }?.tag as? String
                    entries.remove(moving)
                    val insertion = beforeKey?.let { targetKey -> entries.indexOfFirst { it.key == targetKey } }
                        ?.takeIf { it >= 0 }
                        ?: entries.indexOfLast { it.key.startsWith(PLUGIN_PREFIX) == plugin }.let { it + 1 }
                    entries.add(insertion.coerceIn(0, entries.size), moving)
                    saveAndRender()
                }
            }
            true
        }
        reload()
    }

    override fun onDestroyView() {
        registryRegistration?.unregister()
        registryRegistration = null
        previewAvatarController?.unbind()
        previewAvatarController = null
        previewAvatar = null
        previewBottomPluginViews.clear()
        _binding = null
        super.onDestroyView()
    }

    private fun reload() {
        val items = items()
        entries = PluginSurfaceCustomization.get(surface, items.map(Item::key)).toMutableList()
        render(items.associateBy(Item::key))
    }

    private fun saveAndRender() {
        PluginSurfaceCustomization.save(surface, entries)
        render(items().associateBy(Item::key))
        when (surface) {
            Surface.BOTTOM_TABS -> PluginUiRegistry.notifyBottomTabsChanged()
            Surface.PROFILE -> PluginUiRegistry.notifyProfilesChanged()
            Surface.PROFILE_TABS -> PluginUiRegistry.notifyProfileTabsChanged()
            Surface.COMPOSER -> Unit
        }
    }

    private fun render(items: Map<String, Item>) {
        val container = binding.pluginSurfaceCustomizationItems
        container.removeAllViews()
        listOf(false, true).forEach { pluginGroup ->
            val grouped = entries.filter { it.key.startsWith(PLUGIN_PREFIX) == pluginGroup }
            if (grouped.isEmpty()) return@forEach
            container.addView(
                LayoutInflater.from(requireContext()).inflate(
                    R.layout.widget_settings_plugin_section,
                    container,
                    false,
                ).apply {
                    (this as TextView).setText(if (pluginGroup) R.string.plugins else R.string.plugin_surface_builtin)
                },
            )
            grouped.forEach { entry ->
            val item = items[entry.key] ?: return@forEach
            val row = LayoutInflater.from(requireContext()).inflate(
                R.layout.widget_settings_plugin_toggle,
                container,
                false,
            ) as CheckedSetting
            row.apply {
                tag = entry.key
                val required = isRequired(entry.key)
                setText(item.title)
                setSubtext(
                    when {
                        required -> getString(R.string.plugin_surface_required)
                        item.owner != null -> item.owner
                        else -> getString(R.string.plugin_surface_builtin)
                    },
                )
                setChecked(entry.visible || required, false)
                fun update(visible: Boolean) {
                    entries[entries.indexOfFirst { it.key == entry.key }] = entry.copy(visible = visible)
                    saveAndRender()
                }
                setOnCheckedListener(if (required) null else ::update)
                setContainerClickListener(if (required) null else View.OnClickListener { update(!isChecked) })
                findViewById<View>(R.id.setting_container).setOnLongClickListener {
                    ViewCompat.startDragAndDrop(
                        this,
                        ClipData.newPlainText("plugin-surface", entry.key),
                        View.DragShadowBuilder(this),
                        entry.key,
                        0,
                    )
                }
            }
            container.addView(row)
            }
        }
        renderPreview(items)
    }

    private fun renderPreview(items: Map<String, Item>) {
        val preview = binding.pluginSurfaceCustomizationPreview
        preview.removeAllViews()
        preview.visibility = if (surface == Surface.BOTTOM_TABS) View.VISIBLE else View.GONE
        binding.pluginSurfaceProfilePreview.visibility =
            if (surface == Surface.BOTTOM_TABS) View.GONE else View.VISIBLE
        val visibleItems = entries.filter { it.visible || isRequired(it.key) }.mapNotNull { items[it.key] }
        if (surface != Surface.BOTTOM_TABS) {
            configureProfilePreview()
            if (surface == Surface.PROFILE_TABS) {
                binding.pluginSurfaceProfilePreview.renderTabs(visibleItems.map(Item::title))
                binding.pluginSurfaceProfilePreview.renderSections(
                    visibleItems.firstOrNull()?.let {
                        listOf(
                            ProfileCustomizationPreviewView.Section(
                                null,
                                it.title,
                                getString(R.string.profile_preview_tab_content),
                            ),
                        )
                    }.orEmpty(),
                )
            } else {
                binding.pluginSurfaceProfilePreview.renderTabs(emptyList())
                binding.pluginSurfaceProfilePreview.renderSections(visibleItems.map(::profilePreviewSection))
            }
            return
        }
        preview.orientation = LinearLayout.HORIZONTAL
        visibleItems.forEach { item ->
                if (item.owner != null) {
                    bottomPluginPreview(item)?.let {
                        preview.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f))
                        return@forEach
                    }
                }
                if (surface == Surface.BOTTOM_TABS && item.key ==
                    PluginSurfaceCustomization.builtinTabKey(NavigationTab.SETTINGS.name)
                ) {
                    preview.addView(bottomTabAvatarPreview())
                    return@forEach
                }
                preview.addView(TextView(requireContext()).apply {
                    text = item.title
                    gravity = Gravity.CENTER
                    setTextColor(ColorCompat.getThemedColor(context, R.attr.colorHeaderPrimary))
                    if (item.iconRes != 0) {
                        setCompoundDrawablesWithIntrinsicBounds(0, item.iconRes, 0, 0)
                        compoundDrawablePadding = dp(4)
                        TextViewCompat.setCompoundDrawableTintList(
                            this,
                            ColorStateList.valueOf(ColorCompat.getThemedColor(context, R.attr.colorInteractiveNormal)),
                        )
                    }
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    layoutParams = if (surface != Surface.PROFILE) {
                        LinearLayout.LayoutParams(0, dp(48), 1f)
                    } else {
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    }
                })
        }
        previewAvatarController?.apply {
            userId = StoreStream.getUsers().getMe().id
            bind()
        }
    }

    private fun bottomPluginPreview(item: Item): View? {
        val owned = PluginUiRegistry.bottomTabs().firstOrNull {
            PluginSurfaceCustomization.pluginKey(it.owner, it.contribution.id) == item.key
        } ?: return null
        val view = previewBottomPluginViews[item.key]
            ?: runCatching { owned.contribution.createView(requireContext()) }.getOrNull()
                ?.also { previewBottomPluginViews[item.key] = it }
            ?: return null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        runCatching {
            owned.contribution.bind(
                view,
                pluginBottomTabContext("HOME", StoreStream.getUsers().getMe().id, true),
            )
        }
        if (view.contentDescription.isNullOrBlank()) view.contentDescription = item.title
        return view
    }

    private fun configureProfilePreview() {
        val me = StoreStream.getUsers().getMe()
        val profile = StoreStream.getUserProfiles().getUserProfile(me.id)
        binding.pluginSurfaceProfilePreview.bindContext(parentFragmentManager, me)
        binding.pluginSurfaceProfilePreview.bindHeader(
            UserProfileHeaderViewModel.ViewState.Loaded(
                user = me,
                banner = me.banner,
                bannerColorHex = me.bannerColor,
                guildMember = null,
                guildMembersForAka = emptyList(),
                presence = StoreStream.getPresences().getPresences()[me.id],
                streamContext = null,
                userProfile = profile,
                isMeUserPremium = false,
                isMeUserVerified = false,
                allowAnimatedEmojis = true,
                showPresence = true,
                editable = false,
                reducedMotionEnabled = StoreStream.getAccessibility().isReducedMotionEnabled(),
                allowAnimationInReducedMotion = false,
                isMe = true,
            ),
        )
    }

    private fun profilePreviewSection(item: Item): ProfileCustomizationPreviewView.Section {
        val element = ProfileElement.entries.firstOrNull {
            PluginSurfaceCustomization.builtinProfileKey(it) == item.key
        }
        val body = when (element) {
            ProfileElement.ABOUT_ME -> R.string.profile_preview_bio
            ProfileElement.MEMBER_SINCE -> R.string.profile_preview_member_since
            ProfileElement.FRIENDS_SINCE -> R.string.profile_preview_friends_since
            ProfileElement.VOICE_SETTINGS -> R.string.profile_preview_voice_settings
            ProfileElement.RICH_PRESENCE -> R.string.profile_preview_activity
            ProfileElement.GUILD -> R.string.profile_preview_guild
            ProfileElement.CONNECTIONS -> R.string.profile_preview_connections
            ProfileElement.NOTE -> R.string.profile_preview_note
            null -> R.string.profile_preview_plugin
        }
        val icon = when (element) {
            ProfileElement.MEMBER_SINCE -> R.drawable.img_logo_square
            ProfileElement.VOICE_SETTINGS -> R.drawable.ic_call_24dp
            ProfileElement.RICH_PRESENCE -> R.drawable.ic_games_24dp
            else -> 0
        }
        return ProfileCustomizationPreviewView.Section(element, item.title, getString(body), icon)
    }

    private fun bottomTabAvatarPreview() = FrameLayout(requireContext()).apply {
        contentDescription = getString(R.string.profile)
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        addView(
            avatarView(),
            FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER),
        )
    }

    private fun avatarView(): UserAvatarPresenceView {
        val avatar = previewAvatar ?: UserAvatarPresenceView(requireContext()).also {
            previewAvatar = it
            previewAvatarController = UserAvatarPresenceViewController(it)
        }
        (avatar.parent as? android.view.ViewGroup)?.removeView(avatar)
        return avatar
    }

    private fun items(): List<Item> = when (surface) {
        Surface.BOTTOM_TABS -> {
            val builtins = BUILTIN_TABS.map { tab ->
                Item(
                    PluginSurfaceCustomization.builtinTabKey(tab.name),
                    getString(tab.label),
                    null,
                    PluginSurfaceCustomization.builtinTabPriority(tab.name),
                    tab.iconRes,
                )
            }
            val plugins = PluginUiRegistry.bottomTabs().map { owned ->
                Item(
                    PluginSurfaceCustomization.pluginKey(owned.owner, owned.contribution.id),
                    owned.contribution.title,
                    owned.owner,
                    owned.contribution.priority,
                )
            }
            val byKey = (builtins + plugins).associateBy(Item::key)
            PluginSurfaceCustomization.defaultOrder((builtins + plugins).map { it.key to it.priority })
                .mapNotNull(byKey::get)
        }
        Surface.PROFILE -> {
            val builtins = ProfileElement.entries.map { element ->
                Item(
                    PluginSurfaceCustomization.builtinProfileKey(element),
                    getString(element.label),
                    null,
                    element.priority,
                )
            }
            val plugins = PluginUiRegistry.profiles().map { owned ->
                Item(
                    PluginSurfaceCustomization.pluginKey(owned.owner, owned.contribution.id),
                    owned.contribution.title,
                    owned.owner,
                    owned.contribution.priority,
                )
            }
            val byKey = (builtins + plugins).associateBy(Item::key)
            PluginSurfaceCustomization.defaultOrder((builtins + plugins).map { it.key to it.priority })
                .mapNotNull(byKey::get)
        }
        Surface.PROFILE_TABS -> {
            val builtins = listOf(
                Item(PluginSurfaceCustomization.builtinProfileTabKey("MAIN"), getString(R.string.profile_tab_main), null, 200),
                Item(PluginSurfaceCustomization.builtinProfileTabKey("BOARD"), getString(R.string.profile_tab_board), null, 100),
            )
            val plugins = PluginUiRegistry.profileTabs().map { owned ->
                Item(
                    PluginSurfaceCustomization.pluginKey(owned.owner, owned.contribution.id),
                    owned.contribution.title,
                    owned.owner,
                    owned.contribution.priority,
                )
            }
            val byKey = (builtins + plugins).associateBy(Item::key)
            PluginSurfaceCustomization.defaultOrder((builtins + plugins).map { it.key to it.priority })
                .mapNotNull(byKey::get)
        }
        Surface.COMPOSER -> emptyList()
    }

    private data class Item(
        val key: String,
        val title: String,
        val owner: String?,
        val priority: Int,
        val iconRes: Int = 0,
    )
    private data class BuiltinTab(val name: String, val label: Int, val iconRes: Int)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun isRequired(key: String) = when (surface) {
        Surface.BOTTOM_TABS -> PluginSurfaceCustomization.isRequiredBottomTab(key)
        Surface.PROFILE_TABS -> PluginSurfaceCustomization.isRequiredProfileTab(key)
        Surface.PROFILE -> false
        Surface.COMPOSER -> false
    }

    companion object {
        private const val ARG_SURFACE = "surface"
        private const val PLUGIN_PREFIX = "plugin:"
        private val BUILTIN_TABS = listOf(
            BuiltinTab(NavigationTab.HOME.name, R.string.home, R.drawable.ic_tab_home),
            BuiltinTab(NavigationTab.FRIENDS.name, R.string.friends, R.drawable.ic_tab_friends),
            BuiltinTab(NavigationTab.SEARCH.name, R.string.search, R.drawable.ic_tab_search),
            BuiltinTab(NavigationTab.MENTIONS.name, R.string.mentions, R.drawable.ic_tab_mentions),
            BuiltinTab(NavigationTab.SETTINGS.name, R.string.settings, R.drawable.ic_profile_24dp),
        )

        fun launch(context: Context, surface: Surface) = AppScreenRouter.launch(
            context,
            WidgetSettingsPluginSurfaceCustomization::class.java,
            Intent().putExtra(ARG_SURFACE, surface.name),
        )
    }
}
