package com.discord.widgets.chat.input

import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ModelApplicationComparator
import com.discord.models.commands.hasPermission
import com.discord.stores.DiscoverCommands
import com.discord.stores.LoadState
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandAutocompletable
import com.discord.widgets.chat.input.autocomplete.ApplicationCommandLoadingPlaceholder
import com.discord.widgets.chat.input.autocomplete.ApplicationPlaceholder
import com.discord.widgets.chat.input.autocomplete.Autocompletable

/** The paged command-browser rows grouped by application. */
data class WidgetChatInputDiscoveryCommandsModel(
    val commandsByApplication: List<Pair<Application, List<Autocompletable>>>,
    val hasMoreBefore: Boolean,
    val hasMoreAfter: Boolean,
    val jumpedSequenceId: Int,
    val jumpedApplicationId: Long?,
    val loadState: LoadState,
) {
    fun getFlattenCommandsModel(): List<Autocompletable> = commandsByApplication.flatMap { it.second }

    companion object {
        fun parseModelDiscoveryCommands(
            myId: Long,
            myRoles: List<Long>,
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
                                    command.hasPermission(myId, myRoles),
                                    showAvatar = false,
                                ),
                            )
                        }
                    }
                }
                .toMutableList()

            if (placeholdersCount > 0 && commandsByApplication.isNotEmpty()) {
                if (discoveryCommands.hasMoreBefore) {
                    val (application, commands) = commandsByApplication.first()
                    commandsByApplication[0] = application to
                        List(placeholdersCount) { ApplicationCommandLoadingPlaceholder(application) } + commands
                }
                if (discoveryCommands.hasMoreAfter) {
                    val lastIndex = commandsByApplication.lastIndex
                    val (application, commands) = commandsByApplication[lastIndex]
                    commandsByApplication[lastIndex] = application to
                        (commands + List(placeholdersCount) { ApplicationCommandLoadingPlaceholder(application) })
                }
            }

            val frequentApplication = applicationsById[FREQUENT_APPLICATION_ID]
            if (!discoveryCommands.hasMoreBefore && frequentApplication != null && frecencyCommands.isNotEmpty()) {
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
                                    command.hasPermission(myId, myRoles),
                                    showAvatar = true,
                                ),
                            )
                        }
                    },
                )
            }

            return WidgetChatInputDiscoveryCommandsModel(
                commandsByApplication,
                discoveryCommands.hasMoreBefore,
                discoveryCommands.hasMoreAfter,
                discoveryCommands.jumpedSequenceId,
                discoveryCommands.jumpedApplicationId,
                discoveryCommands.loadState,
            )
        }

        private const val FREQUENT_APPLICATION_ID = -2L
    }
}
