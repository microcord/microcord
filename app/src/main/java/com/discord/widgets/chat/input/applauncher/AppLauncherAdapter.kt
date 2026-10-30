package com.discord.widgets.chat.input.applauncher

import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.activities.EmbeddedActivityLauncher
import com.discord.models.commands.Application
import com.discord.models.commands.botUser
import com.discord.models.commands.displayName
import com.discord.utilities.color.ColorCompat
import com.discord.utilities.dimen.dpToPixels
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.uri.UriHandler
import com.discord.databinding.WidgetAppLauncherActivityCardBinding
import com.discord.databinding.WidgetAppLauncherActivityPairBinding
import com.discord.databinding.WidgetAppLauncherBannerBinding
import com.discord.databinding.WidgetAppLauncherHeaderBinding
import com.discord.databinding.WidgetAppLauncherLearnMoreBinding
import com.discord.databinding.WidgetAppLauncherScrollerAppBinding
import com.discord.databinding.WidgetAppLauncherScrollerBinding
import com.discord.databinding.WidgetAppLauncherScrollerCommandBinding
import com.discord.databinding.WidgetAppLauncherScrollerServerAppBinding
import com.discord.databinding.WidgetAppLauncherRowBinding
import com.discord.databinding.WidgetAppLauncherStateBinding

class AppLauncherAdapter(
    private val onApplicationSelected: (AppLauncherApplication) -> Unit,
    private val onActivitySelected: (AppLauncherApplication) -> Unit,
    private val onCommandSelected: (com.discord.models.commands.ApplicationCommand) -> Unit,
    private val onViewAllSelected: (AppLauncherRow.ViewAll) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<AppLauncherRow> = emptyList()

    /** Held on the adapter so a rebind does not throw away what the user picked. */
    private var recentsShowsCommands: Boolean? = null

    init {
        setHasStableIds(true)
    }

    fun setData(value: List<AppLauncherRow>) {
        rows = value
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()

    /** Rows drawn by [RowHolder] form one rounded card per run, the way iOS groups `TableRow`s. */
    private fun isGrouped(position: Int) =
        rows.getOrNull(position)?.let { getItemViewType(position) == TYPE_ROW } == true

    private fun isGroupStart(position: Int) = !isGrouped(position - 1)

    private fun isGroupEnd(position: Int) = !isGrouped(position + 1)

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is AppLauncherRow.Scroller -> TYPE_SCROLLER
        AppLauncherRow.LearnMore -> TYPE_LEARN_MORE
        is AppLauncherRow.ActivityPair -> TYPE_ACTIVITY
        is AppLauncherRow.Banner -> TYPE_BANNER
        is AppLauncherRow.Empty, AppLauncherRow.Loading -> TYPE_STATE
        is AppLauncherRow.Header -> TYPE_HEADER
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SCROLLER -> ScrollerHolder(WidgetAppLauncherScrollerBinding.inflate(inflater, parent, false))
            TYPE_LEARN_MORE -> LearnMoreHolder(WidgetAppLauncherLearnMoreBinding.inflate(inflater, parent, false))
            TYPE_ACTIVITY -> ActivityHolder(WidgetAppLauncherActivityPairBinding.inflate(inflater, parent, false))
            TYPE_BANNER -> BannerHolder(WidgetAppLauncherBannerBinding.inflate(inflater, parent, false))
            TYPE_STATE -> StateHolder(WidgetAppLauncherStateBinding.inflate(inflater, parent, false))
            TYPE_HEADER -> HeaderHolder(WidgetAppLauncherHeaderBinding.inflate(inflater, parent, false))
            else -> RowHolder(WidgetAppLauncherRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ScrollerHolder -> holder.bind(rows[position] as AppLauncherRow.Scroller)
            is LearnMoreHolder -> holder.bind()
            is ActivityHolder -> holder.bind(rows[position] as AppLauncherRow.ActivityPair)
            is BannerHolder -> holder.bind(rows[position] as AppLauncherRow.Banner)
            is StateHolder -> holder.bind(rows[position])
            is HeaderHolder -> holder.bind(
                rows[position] as AppLauncherRow.Header,
                // The shelves above sit in the reference's list header, so the first section title
                // under them keeps the tighter 8dp gap rather than a full section break.
                isFirst = position == 0 || rows.getOrNull(position - 1) is AppLauncherRow.Scroller,
            )
            is RowHolder -> holder.bind(rows[position], isGroupStart(position), isGroupEnd(position))
        }
    }

    private class LearnMoreHolder(private val binding: WidgetAppLauncherLearnMoreBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.appLauncherLearnMoreButton.setOnClickListener {
                UriHandler.handle(it.context, LEARN_MORE_URL)
            }
        }
    }

    /** Mirrors the reference's "Recents" / "In This Server" shelves. */
    private inner class ScrollerHolder(private val binding: WidgetAppLauncherScrollerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: AppLauncherRow.Scroller) = with(binding) {
            appLauncherScrollerViewAll.visibility = if (row.showsViewAll) View.VISIBLE else View.GONE
            appLauncherScrollerViewAll.setOnClickListener {
                onViewAllSelected(
                    AppLauncherRow.ViewAll(
                        row.titleRes,
                        row.section,
                        applications = row.applications,
                        commands = if (showsCommands(row)) row.commands.map { cell -> cell.command } else emptyList(),
                    ),
                )
            }
            appLauncherScrollerList.layoutManager =
                LinearLayoutManager(root.context, LinearLayoutManager.HORIZONTAL, false)
            appLauncherScrollerTitle.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0,
                0,
                if (row.canToggle) R.drawable.ic_chevron_down_primary_300_12dp else 0,
                0,
            )
            appLauncherScrollerTitle.setOnClickListener(
                if (row.canToggle) View.OnClickListener { showMenu(row) } else null,
            )
            renderCells(row)
        }

        /** Commands unless the user chose otherwise, matching the reference's stored default. */
        private fun showsCommands(row: AppLauncherRow.Scroller): Boolean = when {
            row.commands.isEmpty() -> false
            row.applications.isEmpty() -> true
            else -> recentsShowsCommands ?: true
        }

        private fun showMenu(row: AppLauncherRow.Scroller) {
            val anchor = binding.appLauncherScrollerTitle
            val menu = PopupMenu(anchor.context, anchor)
            menu.menu.add(Menu.NONE, MENU_COMMANDS, 0, R.string.app_launcher_recents_commands)
            menu.menu.add(Menu.NONE, MENU_APPS, 1, R.string.app_launcher_recents_apps)
            menu.menu.setGroupCheckable(Menu.NONE, true, true)
            menu.menu.findItem(if (showsCommands(row)) MENU_COMMANDS else MENU_APPS).isChecked = true
            menu.setOnMenuItemClickListener { item ->
                recentsShowsCommands = item.itemId == MENU_COMMANDS
                renderCells(row)
                true
            }
            menu.show()
        }

        private fun renderCells(row: AppLauncherRow.Scroller) = with(binding) {
            appLauncherScrollerTitle.setText(row.titleRes)
            val commands = showsCommands(row)
            val cells: List<Any> = if (commands) row.commands else row.applications
            appLauncherScrollerList.adapter = ScrollerCellAdapter(row, cells, commands)
        }
    }

    private inner class ScrollerCellAdapter(
        private val row: AppLauncherRow.Scroller,
        private val cells: List<Any>,
        private val showingCommands: Boolean,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cells.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            val holder = when {
                showingCommands ->
                    CommandCellHolder(WidgetAppLauncherScrollerCommandBinding.inflate(inflater, parent, false))
                row.showsAppsAsTiles ->
                    AppTileHolder(WidgetAppLauncherScrollerAppBinding.inflate(inflater, parent, false))
                else ->
                    ServerAppHolder(WidgetAppLauncherScrollerServerAppBinding.inflate(inflater, parent, false))
            }
            (holder.itemView.layoutParams as? RecyclerView.LayoutParams)?.marginEnd = dpToPixels(8)
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is CommandCellHolder -> holder.bind(cells[position] as AppLauncherRow.CommandCell)
                is AppTileHolder -> holder.bind(cells[position] as AppLauncherApplication)
                is ServerAppHolder -> holder.bind(cells[position] as AppLauncherApplication)
            }
        }
    }

    private inner class CommandCellHolder(private val binding: WidgetAppLauncherScrollerCommandBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(cell: AppLauncherRow.CommandCell) = with(binding) {
            appLauncherScrollerCommandIcon.setImageURI(cell.application?.let(::commandIconUrl))
            appLauncherScrollerCommandName.text = displayName(cell.command)
            appLauncherScrollerCommandApp.text = cell.application?.displayName()
                ?: root.context.getString(R.string.app_launcher_section_built_in)
            root.setOnClickListener { onCommandSelected(cell.command) }
        }
    }

    private inner class AppTileHolder(private val binding: WidgetAppLauncherScrollerAppBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(application: AppLauncherApplication) = with(binding) {
            appLauncherScrollerAppIcon.setImageURI(launcherIconUrl(application))
            root.contentDescription = application.name
            root.setOnClickListener { onApplicationSelected(application) }
        }
    }

    private inner class ServerAppHolder(private val binding: WidgetAppLauncherScrollerServerAppBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(application: AppLauncherApplication) = with(binding) {
            appLauncherScrollerServerAppIcon.setImageURI(launcherIconUrl(application))
            appLauncherScrollerServerAppName.text = application.name
            root.setOnClickListener { onApplicationSelected(application) }
        }
    }

    private class HeaderHolder(private val binding: WidgetAppLauncherHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow.Header, isFirst: Boolean) {
            if (row.title != null) binding.root.text = row.title else binding.root.setText(row.titleRes)
            binding.root.updatePadding(top = dpToPixels(if (isFirst) 8 else 24))
        }
    }

    private inner class RowHolder(private val binding: WidgetAppLauncherRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow, isGroupStart: Boolean, isGroupEnd: Boolean) = with(binding) {
            root.setBackgroundResource(
                when {
                    isGroupStart && isGroupEnd -> R.drawable.drawable_app_launcher_group_single
                    isGroupStart -> R.drawable.drawable_app_launcher_group_top
                    isGroupEnd -> R.drawable.drawable_app_launcher_group_bottom
                    else -> R.drawable.drawable_app_launcher_group_middle
                },
            )
            appLauncherRowDivider.visibility = if (isGroupEnd) View.GONE else View.VISIBLE
            appLauncherRowIcon.visibility = View.GONE
            appLauncherRowTitle.setTextColor(
                ColorCompat.getThemedColor(root.context, R.attr.primary_100),
            )
            appLauncherRowSubtitle.visibility = View.GONE
            appLauncherRowPromoted.visibility = View.GONE
            appLauncherRowArrow.visibility = View.VISIBLE
            root.isClickable = true
            root.isFocusable = true
            root.setOnClickListener(null)
            when (row) {
                is AppLauncherRow.ApplicationRow -> bindApplication(row.application)
                is AppLauncherRow.CommandRow -> {
                    appLauncherRowIcon.visibility = View.VISIBLE
                    setIcon(row.application?.id ?: row.command.applicationId, row.application?.icon)
                    appLauncherRowTitle.text = "/${row.command.name}"
                    val description = row.command.description
                        ?: row.command.descriptionRes?.let(root.resources::getString)
                        ?: row.command.options.joinToString { it.name }
                    appLauncherRowSubtitle.text = description
                    appLauncherRowSubtitle.visibility = if (description.isBlank()) View.GONE else View.VISIBLE
                    root.contentDescription = listOfNotNull(appLauncherRowTitle.text, description).joinToString(", ")
                    root.setOnClickListener { onCommandSelected(row.command) }
                }
                is AppLauncherRow.ViewAll -> {
                    appLauncherRowArrow.visibility = View.GONE
                    appLauncherRowTitle.setTextColor(
                        ColorCompat.getThemedColor(root.context, R.attr.colorTextBrand),
                    )
                    val label = root.context.getString(R.string.app_launcher_view_more)
                    appLauncherRowTitle.text = label
                    root.contentDescription = label
                    root.setOnClickListener { onViewAllSelected(row) }
                }
                else -> Unit
            }
        }

        private fun WidgetAppLauncherRowBinding.bindApplication(application: AppLauncherApplication) {
            appLauncherRowIcon.visibility = View.VISIBLE
            appLauncherRowIcon.setImageURI(launcherIconUrl(application))
            appLauncherRowTitle.text = application.name
            appLauncherRowSubtitle.text = application.description
            appLauncherRowSubtitle.visibility = if (application.description.isBlank()) View.GONE else View.VISIBLE
            appLauncherRowPromoted.visibility = if (application.promoted) View.VISIBLE else View.GONE
            root.contentDescription = listOfNotNull(
                application.name,
                application.description.takeIf(String::isNotBlank),
                root.context.getString(R.string.app_launcher_promoted).takeIf { application.promoted },
            ).joinToString(", ")
            root.setOnClickListener { onApplicationSelected(application) }
        }

        private fun WidgetAppLauncherRowBinding.setIcon(id: Long, icon: String?) {
            appLauncherRowIcon.setImageURI(iconUrl(id, icon))
        }
    }

    private inner class ActivityHolder(private val binding: WidgetAppLauncherActivityPairBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow.ActivityPair) {
            bindCard(binding.appLauncherActivityFirst, row.first)
            binding.appLauncherActivitySecond.root.visibility = if (row.second == null) View.INVISIBLE else View.VISIBLE
            row.second?.let { bindCard(binding.appLauncherActivitySecond, it) }
        }

        private fun bindCard(binding: WidgetAppLauncherActivityCardBinding, application: AppLauncherApplication) {
            binding.appLauncherActivityImage.setImageURI(
                application.imageUrl ?: iconUrl(application.id, application.icon),
            )
            binding.appLauncherActivityName.text = application.name
            binding.appLauncherActivityBadge.bindBadge(application.badge)
            binding.root.contentDescription = application.name
            binding.root.setOnClickListener { onActivitySelected(application) }
        }

        private fun TextView.bindBadge(badge: ActivityBadge?) {
            visibility = if (badge == null) View.GONE else View.VISIBLE
            if (badge == null) return
            setText(
                if (badge == ActivityBadge.NEW) R.string.app_launcher_activity_badge_new
                else R.string.app_launcher_activity_badge_updated,
            )
            setBackgroundResource(
                if (badge == ActivityBadge.NEW) R.drawable.drawable_app_launcher_badge_new
                else R.drawable.drawable_app_launcher_badge_updated,
            )
        }
    }

    private inner class BannerHolder(private val binding: WidgetAppLauncherBannerBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow.Banner) {
            val application = row.application
            binding.appLauncherBannerIcon.setImageURI(iconUrl(application.id, application.icon))
            binding.appLauncherBannerName.text = application.name
            binding.appLauncherBannerDescription.text = application.description
            binding.root.contentDescription = listOf(application.name, application.description).filter(String::isNotBlank).joinToString(", ")
            binding.root.isClickable = row.interactive
            binding.root.isFocusable = row.interactive
            binding.root.setOnClickListener(if (row.interactive) View.OnClickListener { onApplicationSelected(application) } else null)
        }
    }

    private class StateHolder(private val binding: WidgetAppLauncherStateBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow) {
            val loading = row == AppLauncherRow.Loading
            binding.appLauncherStateLoading.visibility = if (loading) View.VISIBLE else View.GONE
            binding.appLauncherStateIcon.visibility = if (loading) View.GONE else View.VISIBLE
            binding.appLauncherStateTitle.visibility = if (loading) View.GONE else View.VISIBLE
            binding.appLauncherStateBody.visibility = if (loading) View.GONE else View.VISIBLE
            if (row !is AppLauncherRow.Empty) return
            val strings = when (row.type) {
                AppLauncherEmptyStateType.SEARCH_EMPTY ->
                    R.string.app_launcher_search_empty_title to R.string.app_launcher_search_empty_body
                AppLauncherEmptyStateType.HOME_NO_PERMISSIONS,
                AppLauncherEmptyStateType.COMMAND_NO_PERMISSIONS,
                -> R.string.app_launcher_no_permissions_title to R.string.app_launcher_no_permissions_body
                AppLauncherEmptyStateType.COMMAND_NOT_FOUND ->
                    R.string.app_launcher_command_not_found_title to R.string.app_launcher_command_not_found_body
                else -> R.string.app_launcher_home_empty_title to R.string.app_launcher_home_empty_body
            }
            binding.appLauncherStateTitle.setText(strings.first)
            binding.appLauncherStateBody.setText(strings.second)
            binding.root.contentDescription = "${binding.appLauncherStateTitle.text}. ${binding.appLauncherStateBody.text}"
        }
    }

    /**
     * An activity's entry-point command is named like an identifier (`launch`, `watch_together`),
     * so the reference title-cases it and unpicks the underscores before showing it.
     */
    private fun displayName(command: com.discord.models.commands.ApplicationCommand): String =
        if (command.type == EmbeddedActivityLauncher.PRIMARY_ENTRY_POINT_COMMAND_TYPE) {
            command.name.replaceFirstChar { it.titlecase() }.replace('_', ' ')
        } else {
            command.name
        }

    companion object {
        internal fun iconUrl(id: Long, icon: String?): String? =
            icon?.let { "https://cdn.discordapp.com/app-icons/$id/$it.webp?size=128" }

        /** A command app's icon, or its bot's avatar when the app has none (as the official client shows). */
        internal fun commandIconUrl(application: Application): String? =
            iconUrl(application.id, application.icon) ?: application.botUser()?.let { IconUtils.getForUser(it) }

        private fun launcherIconUrl(application: AppLauncherApplication): String? =
            application.commandApplication?.let(::commandIconUrl) ?: iconUrl(application.id, application.icon)

        private const val TYPE_ROW = 0
        private const val TYPE_ACTIVITY = 1

        /** Dims the artwork of an activity that cannot run here. */
        private const val TYPE_BANNER = 2
        private const val TYPE_STATE = 3
        private const val TYPE_HEADER = 4
        private const val TYPE_SCROLLER = 5
        private const val TYPE_LEARN_MORE = 6
        private const val MENU_COMMANDS = 1
        private const val MENU_APPS = 2
        private const val LEARN_MORE_URL =
            "https://support.discord.com/hc/en-us/articles/26501837786775"
    }
}
