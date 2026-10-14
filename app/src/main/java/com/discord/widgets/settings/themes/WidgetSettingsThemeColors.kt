package com.discord.widgets.settings.themes

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.discord.R
import com.discord.databinding.WidgetSettingsThemeListPageBinding
import com.discord.utilities.rx.ui
import com.discord.utilities.theme.ThemeColorParser
import com.discord.utilities.theme.ThemeDefinition
import com.discord.utilities.theme.ThemeLinks
import com.discord.utilities.theme.ThemeResolver
import com.discord.widgets.settings.profile.ProfileColorPickerDialog
import rx.Observable
import rx.schedulers.Schedulers

/**
 * Every color a theme can change, one group per page.
 *
 * Opened without a group, it lists the groups, with what the theme changed first. Searching
 * reaches every color from there. Opened on a group, it lists that group's colors.
 *
 * A color shows, in priority order:
 * - the theme's own setting;
 * - whatever its Aliucord sections resolve to ([ThemeResolver]);
 * - the base theme.
 *
 * The reset button removes only the first.
 */
class WidgetSettingsThemeColors : ThemeEditorPage(R.layout.widget_settings_theme_list_page, R.string.microcord_theme_colors) {

    private var _binding: WidgetSettingsThemeListPageBinding? = null
    private val binding get() = requireNotNull(_binding)
    private val adapter = ThemeEditorListAdapter()
    private var tokens: List<ThemeTokens.Token> = emptyList()
    private var loadedBase: String? = null
    private var query = ""
    private val group: ThemeTokens.Group?
        get() = arguments?.getString(ARG_GROUP)?.let { name -> ThemeTokens.Group.values().firstOrNull { it.name == name } }

    override fun onThemeLoaded(view: View) {
        _binding = WidgetSettingsThemeListPageBinding.bind(view)
        group?.let { setActionBarTitle(it.title) }
        binding.themeListPageList.layoutManager = LinearLayoutManager(requireContext())
        binding.themeListPageList.itemAnimator = null
        binding.themeListPageList.adapter = adapter
        binding.themeListPageSearch.bindTextWatcher(this) { text ->
            query = text
            render()
        }
        childFragmentManager.setFragmentResultListener(ProfileColorPickerDialog.REQUEST_KEY, viewLifecycleOwner) { _, result ->
            val token = tokens.firstOrNull { it.key == result.getString(ProfileColorPickerDialog.RESULT_TARGET) }
                ?: return@setFragmentResultListener
            setColor(token, result.getInt(ProfileColorPickerDialog.RESULT_COLOR).takeIf { result.getBoolean(ProfileColorPickerDialog.RESULT_HAS_COLOR) })
        }
    }

    override fun onDestroyView() {
        _binding?.themeListPageList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    override fun render() {
        if (_binding == null) return
        if (loadedBase != theme.base) {
            loadTokens()
            return
        }
        val baseColors = tokens.filter { it.kind == ThemeTokens.Kind.ATTR }.associate { it.name to it.base }
        val resolved = ThemeLinks.link(ThemeResolver.resolve(theme), baseColors)
        val needle = query.trim().lowercase()
        val group = group
        val items = mutableListOf<ThemeEditorListAdapter.Item>()
        if (group == null && needle.isEmpty()) {
            val changed = tokens.filter { it.name in own(it) }
            if (changed.isNotEmpty()) {
                items += ThemeEditorListAdapter.Item.Header("changed", getString(ThemeTokens.Group.CHANGED.title))
                changed.forEach { items += colorItem(it, resolved, ThemeTokens.Group.CHANGED) }
                items += ThemeEditorListAdapter.Item.Header("groups", getString(R.string.microcord_theme_colors))
            }
            tokens.groupBy { it.group }.forEach { (g, groupTokens) ->
                val count = groupTokens.count { it.name in own(it) }
                val detail = if (count > 0) getString(R.string.microcord_theme_changes, count) else groupTokens.size.toString()
                items += ThemeEditorListAdapter.Item.Nav("group:${g.name}", getString(g.title), detail) {
                    launch(requireContext(), WidgetSettingsThemeColors::class.java, themeId, Intent().putExtra(ARG_GROUP, g.name))
                }
            }
        } else {
            val matches = tokens.filter { token ->
                (group == null || token.group == group) &&
                    (needle.isEmpty() || needle in token.name.lowercase() || needle in ThemeTokens.label(token.name).lowercase())
            }
            matches.groupBy { it.group }.forEach { (g, groupTokens) ->
                if (group == null) items += ThemeEditorListAdapter.Item.Header("header:${g.name}", getString(g.title))
                groupTokens.forEach { items += colorItem(it, resolved, g) }
            }
        }
        adapter.submitList(items)
    }

    private fun colorItem(token: ThemeTokens.Token, resolved: ThemeResolver.Resolved, section: ThemeTokens.Group): ThemeEditorListAdapter.Item {
        val own = own(token)
        val shown = own[token.name] ?: (if (token.kind == ThemeTokens.Kind.ATTR) resolved.attrs else resolved.colors)[token.name] ?: token.base
        val label = ThemeTokens.label(token.name)
        return ThemeEditorListAdapter.Item.Color(
            id = "${section.name}:${token.key}",
            label = label,
            color = shown,
            detail = "${token.name} · ${ThemeColorParser.toHex(shown)}",
            overridden = token.name in own,
            onClick = {
                ProfileColorPickerDialog.show(childFragmentManager, initialColor = shown, target = token.key, title = label, allowAlpha = true)
            },
            onReset = { setColor(token, null) },
        )
    }

    /** Base values depend on the base theme, so they are re-read whenever it changes. */
    private fun loadTokens() {
        val context = requireContext().applicationContext
        val base = theme.base
        loadedBase = base
        Observable.fromCallable { ThemeTokens.load(context, base) }
            .subscribeOn(Schedulers.computation())
            .ui(this)
            .subscribe { loaded ->
                tokens = loaded
                render()
            }
    }

    private fun own(token: ThemeTokens.Token) = if (token.kind == ThemeTokens.Kind.ATTR) theme.attrs else theme.colors

    private fun setColor(token: ThemeTokens.Token, color: Int?) {
        val own = own(token).toMutableMap()
        if (color == null) own.remove(token.name) else own[token.name] = color
        val section = if (token.kind == ThemeTokens.Kind.ATTR) ThemeDefinition.SECTION_ATTRS else ThemeDefinition.SECTION_COLORS
        val systemColors = theme.systemColors - "$section.${token.name}"
        save(if (token.kind == ThemeTokens.Kind.ATTR) theme.copy(attrs = own, systemColors = systemColors) else theme.copy(colors = own, systemColors = systemColors))
    }

    companion object {
        internal const val ARG_GROUP = "group"
    }
}
