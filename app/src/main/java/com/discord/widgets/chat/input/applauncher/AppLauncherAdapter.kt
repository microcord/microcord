package com.discord.widgets.chat.input.applauncher

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.databinding.WidgetAppLauncherActivityCardBinding
import com.discord.databinding.WidgetAppLauncherActivityPairBinding
import com.discord.databinding.WidgetAppLauncherBannerBinding
import com.discord.databinding.WidgetAppLauncherRowBinding
import com.discord.databinding.WidgetAppLauncherStateBinding

class AppLauncherAdapter(
    private val onApplicationSelected: (AppLauncherApplication) -> Unit,
    private val onActivitySelected: (AppLauncherApplication) -> Unit,
    private val onCommandSelected: (com.discord.models.commands.ApplicationCommand) -> Unit,
    private val onViewAllSelected: (AppLauncherRow.ViewAll) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<AppLauncherRow> = emptyList()

    init {
        setHasStableIds(true)
    }

    fun setData(value: List<AppLauncherRow>) {
        rows = value
        notifyDataSetChanged()
    }

    override fun getItemCount() = rows.size

    override fun getItemId(position: Int) = rows[position].key.hashCode().toLong()

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is AppLauncherRow.ActivityPair -> TYPE_ACTIVITY
        is AppLauncherRow.Banner -> TYPE_BANNER
        is AppLauncherRow.Empty, AppLauncherRow.Loading -> TYPE_STATE
        else -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_ACTIVITY -> ActivityHolder(WidgetAppLauncherActivityPairBinding.inflate(inflater, parent, false))
            TYPE_BANNER -> BannerHolder(WidgetAppLauncherBannerBinding.inflate(inflater, parent, false))
            TYPE_STATE -> StateHolder(WidgetAppLauncherStateBinding.inflate(inflater, parent, false))
            else -> RowHolder(WidgetAppLauncherRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ActivityHolder -> holder.bind(rows[position] as AppLauncherRow.ActivityPair)
            is BannerHolder -> holder.bind(rows[position] as AppLauncherRow.Banner)
            is StateHolder -> holder.bind(rows[position])
            is RowHolder -> holder.bind(rows[position])
        }
    }

    private inner class RowHolder(private val binding: WidgetAppLauncherRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AppLauncherRow) = with(binding) {
            appLauncherRowIcon.visibility = View.GONE
            appLauncherRowSubtitle.visibility = View.GONE
            appLauncherRowPromoted.visibility = View.GONE
            appLauncherRowArrow.visibility = View.VISIBLE
            root.isClickable = true
            root.isFocusable = true
            root.setOnClickListener(null)
            when (row) {
                is AppLauncherRow.Header -> {
                    appLauncherRowTitle.text = row.title
                    appLauncherRowTitle.setTextAppearance(R.style.UiKit_TextView_H2)
                    appLauncherRowArrow.visibility = View.GONE
                    root.isClickable = false
                    root.isFocusable = false
                    root.contentDescription = row.title
                }
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
                    appLauncherRowTitle.text = row.title
                    appLauncherRowTitle.setTextAppearance(R.style.UiKit_TextView_Semibold)
                    root.contentDescription = row.title
                    root.setOnClickListener { onViewAllSelected(row) }
                }
                else -> Unit
            }
        }

        private fun WidgetAppLauncherRowBinding.bindApplication(application: AppLauncherApplication) {
            appLauncherRowIcon.visibility = View.VISIBLE
            setIcon(application.id, application.icon)
            appLauncherRowTitle.text = application.name
            appLauncherRowTitle.setTextAppearance(R.style.UiKit_TextView_Semibold)
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
            binding.appLauncherActivityImage.setImageURI(iconUrl(application.id, application.icon))
            binding.appLauncherActivityName.text = application.name
            binding.appLauncherActivityDescription.text = application.description
            binding.root.contentDescription = listOf(application.name, application.description).filter(String::isNotBlank).joinToString(", ")
            binding.root.setOnClickListener { onActivitySelected(application) }
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

    private fun iconUrl(id: Long, icon: String?): String? =
        icon?.let { "https://cdn.discordapp.com/app-icons/$id/$it.webp?size=128" }

    companion object {
        private const val TYPE_ROW = 0
        private const val TYPE_ACTIVITY = 1
        private const val TYPE_BANNER = 2
        private const val TYPE_STATE = 3
    }
}
