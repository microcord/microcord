package com.discord.widgets.chat.input.applauncher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.databinding.WidgetAppLauncherRowBinding
import com.discord.databinding.WidgetContextCommandsSheetBinding
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.stores.StoreStream
import com.discord.utilities.channel.ChannelSelector
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.ui
import com.discord.widgets.chat.input.models.ApplicationCommandData
import java.util.Locale
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subscriptions.CompositeSubscription

/**
 * User and message context-menu commands ("Apps"), backported from the modern client's
 * `ContextMenuCommandRootScreen`: a search box, a "Frequently Used" section, then one section per
 * application. Tapping a command runs it against [ARG_TARGET_ID] through the ordinary interaction
 * send path, so it gets the same pending message and retry as a slash command.
 */
class WidgetContextCommandsSheet : AppBottomSheet() {
    private var _binding: WidgetContextCommandsSheetBinding? = null
    private val binding get() = checkNotNull(_binding)
    private val adapter = Adapter(::run)
    private val query = BehaviorSubject.create("")

    override fun getContentViewResId() = R.layout.widget_context_commands_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = WidgetContextCommandsSheetBinding.bind(view)
        binding.contextCommandsList.layoutManager = LinearLayoutManager(view.context)
        binding.contextCommandsList.adapter = adapter
        binding.contextCommandsSearch.bindTextWatcher(this) { query.onNext(it) }
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        val commands = StoreStream.getApplicationCommands()
        val type = argumentsOrDefault.getInt(ARG_TYPE)
        val guildId = StoreStream.getChannels().getChannel(channelId())?.guildId?.takeUnless { it == 0L }
        val available = if (isPrivate()) {
            Observable.just(commands.getPrivateChannelContextCommands(type))
        } else {
            commands.observeContextCommands(channelId(), type)
        }
        Observable.combineLatest(
            available,
            StoreStream.getApplicationCommandFrecency().observeTopCommandIds(guildId),
            query.distinctUntilChanged(),
        ) { contextCommands, recentIds, search ->
            val applications = if (isPrivate()) commands.getPrivateChannelContextApplications() else commands.getApplicationMap()
            buildRows(contextCommands, applications, recentIds, search, getString(R.string.frequently_used))
        }
            .ui(this)
            .appSubscribe(WidgetContextCommandsSheet::class.java, subscriptionHandler = compositeSubscription::add) {
                adapter.submit(it)
                _binding?.contextCommandsEmpty?.isVisible = it.isEmpty()
            }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun channelId() = argumentsOrDefault.getLong(ARG_CHANNEL_ID)

    /** Opened without a channel: the commands come from user-installed apps and run in the target's DM. */
    private fun isPrivate() = channelId() == 0L

    private fun run(command: ApplicationCommand, application: Application?) {
        if (isPrivate()) {
            ChannelSelector.getInstance().findAndSetDirectMessage(
                requireContext().applicationContext,
                argumentsOrDefault.getLong(ARG_TARGET_ID),
            ) { send(it, null, command, application) }
        } else {
            StoreStream.getChannels().getChannel(channelId())
                ?.let { send(it.id, it.guildId.takeUnless { id -> id == 0L }, command, application) }
        }
        dismiss()
    }

    private fun send(channelId: Long, guildId: Long?, command: ApplicationCommand, application: Application?) {
        StoreStream.getInteractions().sendApplicationCommand(
            channelId = channelId,
            guildId = guildId,
            version = command.version,
            data = ApplicationCommandData(
                application ?: Application(id = command.applicationId, name = command.name, commandCount = 0),
                command,
                emptyList(),
                validInputs = true,
            ),
            targetId = argumentsOrDefault.getLong(ARG_TARGET_ID),
        )
    }

    sealed class Row {
        data class Header(val title: String) : Row()
        data class Command(
            val command: ApplicationCommand,
            val application: Application?,
            val isFirst: Boolean,
            val isLast: Boolean,
        ) : Row()
    }

    private class Adapter(
        private val onClick: (ApplicationCommand, Application?) -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var rows: List<Row> = emptyList()

        fun submit(rows: List<Row>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) TYPE_HEADER else TYPE_COMMAND

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                object : RecyclerView.ViewHolder(inflater.inflate(R.layout.widget_context_commands_header, parent, false)) {}
            } else {
                CommandHolder(WidgetAppLauncherRowBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.title
                is Row.Command -> (holder as CommandHolder).bind(row)
            }
        }

        private inner class CommandHolder(private val binding: WidgetAppLauncherRowBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(row: Row.Command) = with(binding) {
                val application = row.application
                appLauncherRow.setBackgroundResource(
                    when {
                        row.isFirst && row.isLast -> R.drawable.drawable_app_launcher_group_single
                        row.isFirst -> R.drawable.drawable_app_launcher_group_top
                        row.isLast -> R.drawable.drawable_app_launcher_group_bottom
                        else -> R.drawable.drawable_app_launcher_group_middle
                    },
                )
                appLauncherRowIcon.isVisible = true
                appLauncherRowIcon.setImageURI(application?.let { AppLauncherAdapter.iconUrl(it.id, it.icon) })
                appLauncherRowTitle.text = row.command.name
                appLauncherRowSubtitle.text = application?.name
                appLauncherRowSubtitle.isVisible = application != null
                appLauncherRowDivider.isVisible = !row.isLast
                appLauncherRowArrow.setImageResource(R.drawable.ic_slash_command_24dp)
                root.setOnClickListener { onClick(row.command, application) }
            }
        }
    }

    companion object {
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"
        private const val ARG_TYPE = "ARG_TYPE"
        private const val ARG_TARGET_ID = "ARG_TARGET_ID"
        private const val TYPE_HEADER = 0
        private const val TYPE_COMMAND = 1
        private const val FRECENCY_LIMIT = 5

        /**
         * [type] is `ApplicationCommandIndexCommand.TYPE_USER` or `TYPE_MESSAGE`. A [channelId] of
         * `0` lists user-installed app commands and runs the chosen one in the DM with [targetId].
         */
        fun show(fragmentManager: FragmentManager, channelId: Long, type: Int, targetId: Long) {
            WidgetContextCommandsSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_CHANNEL_ID, channelId)
                    putInt(ARG_TYPE, type)
                    putLong(ARG_TARGET_ID, targetId)
                }
            }.show(fragmentManager, WidgetContextCommandsSheet::class.java.name)
        }

        /** The frecency section (up to five) followed by one alphabetical section per application. */
        internal fun buildRows(
            commands: List<ApplicationCommand>,
            applications: Map<Long, Application>,
            recentIds: List<String>,
            query: String,
            frequentlyUsedTitle: String,
        ): List<Row> {
            val needle = query.trim().lowercase(Locale.ROOT)
            val matches = commands.filter { command ->
                needle.isEmpty() ||
                    command.name.lowercase(Locale.ROOT).contains(needle) ||
                    applications[command.applicationId]?.name?.lowercase(Locale.ROOT)?.contains(needle) == true
            }
            val byId = matches.associateBy(ApplicationCommand::id)
            val recent = recentIds.mapNotNull(byId::get).take(FRECENCY_LIMIT)
            val sections = listOf(frequentlyUsedTitle to recent) +
                matches.groupBy(ApplicationCommand::applicationId)
                    .map { (appId, appCommands) -> (applications[appId]?.name ?: appCommands.first().name) to appCommands }
                    .sortedBy { it.first.lowercase(Locale.ROOT) }
            return sections.filter { it.second.isNotEmpty() }.flatMap { (title, sectionCommands) ->
                listOf(Row.Header(title)) + sectionCommands.mapIndexed { index, command ->
                    Row.Command(command, applications[command.applicationId], index == 0, index == sectionCommands.lastIndex)
                }
            }
        }
    }
}
