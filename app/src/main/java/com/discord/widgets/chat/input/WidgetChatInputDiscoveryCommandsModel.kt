package com.discord.widgets.chat.input

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ModelApplicationComparator
import com.discord.models.commands.CommandPermissionContext
import com.discord.models.commands.hasAccess
import com.discord.stores.DiscoverCommands
import com.discord.stores.LoadState
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandLoadingPlaceholder
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder
import com.discord.widgets.chat.input.autocomplete.Autocompletable

/** The command-browser rows grouped by application. */
data class WidgetChatInputDiscoveryCommandsModel(
    val commandsByApplication: List<Pair<Application, List<Autocompletable>>>,
    val loadState: LoadState,
) {
    fun getFlattenCommandsModel(): List<Autocompletable> = commandsByApplication.flatMap { it.second }

    companion object {
        fun parseModelDiscoveryCommands(
            permissionContext: CommandPermissionContext,
            applications: List<Application>,
            discoveryCommands: DiscoverCommands,
            includeHeaders: Boolean,
            placeholdersCount: Int,
            frecencyCommands: List<ApplicationCommand>,
        ): WidgetChatInputDiscoveryCommandsModel {
            val applicationsById = applications.associateBy(Application::id)
            val commandsByApplication = discoveryCommands.commands
                .mapNotNull { command ->
                    applicationsById[command.applicationId]?.let { it to command }
                }
                .groupBy({ it.first }, { it.second })
                .toSortedMap(ModelApplicationComparator)
                .map { (application, commands) ->
                    application to buildList {
                        if (includeHeaders) add(ApplicationPlaceholder(application))
                        commands.forEach { command ->
                            add(
                                ApplicationCommandAutocompletable(
                                    application,
                                    command,
                                    command.hasAccess(permissionContext, application),
                                    showAvatar = false,
                                ),
                            )
                        }
                    }
                }
                .toMutableList()

            if (placeholdersCount > 0 && discoveryCommands.loadState.isLoading() &&
                commandsByApplication.isNotEmpty()
            ) {
                val lastIndex = commandsByApplication.lastIndex
                val (application, commands) = commandsByApplication[lastIndex]
                commandsByApplication[lastIndex] = application to
                    (commands + List(placeholdersCount) { ApplicationCommandLoadingPlaceholder(application) })
            }

            val frequentApplication = applicationsById[FREQUENT_APPLICATION_ID]
            if (frequentApplication != null && frecencyCommands.isNotEmpty()) {
                commandsByApplication.add(
                    0,
                    frequentApplication to buildList {
                        add(ApplicationPlaceholder(frequentApplication))
                        frecencyCommands.forEach { command ->
                            val application = applicationsById[command.applicationId] ?: return@forEach
                            add(
                                ApplicationCommandAutocompletable(
                                    application,
                                    command,
                                    command.hasAccess(permissionContext, application),
                                    showAvatar = true,
                                ),
                            )
                        }
                    },
                )
            }

            return WidgetChatInputDiscoveryCommandsModel(commandsByApplication, discoveryCommands.loadState)
        }

        private const val FREQUENT_APPLICATION_ID = -2L
    }
}
