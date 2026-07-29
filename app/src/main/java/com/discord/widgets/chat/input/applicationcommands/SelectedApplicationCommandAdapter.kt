package com.discord.widgets.chat.input.applicationcommands

import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.models.commands.Application
import com.discord.models.commands.ApplicationCommand
import com.discord.models.commands.ApplicationCommandOption
import com.discord.utilities.mg_recycler.MGRecyclerAdapterSimple
import com.discord.utilities.mg_recycler.MGRecyclerViewHolder

class SelectedApplicationCommandAdapter(
    recyclerView: RecyclerView,
    val onClickApplicationCommandOption: (ApplicationCommandOption) -> Unit,
) : MGRecyclerAdapterSimple<SelectedApplicationCommandItem>(recyclerView, false) {
    private var currentCommand: ApplicationCommand? = null
    private var highlightedCommandOption: ApplicationCommandOption? = null
    private val paramPositions = LinkedHashMap<String, Int>()
    private var scroller: RecyclerView.SmoothScroller = object : LinearSmoothScroller(context) {
        override fun getHorizontalSnapPreference(): Int = -1
    }

    fun clear() {
        setData(emptyList())
        paramPositions.clear()
        currentCommand = null
        highlightedCommandOption = null
    }

    fun clearParamOptionHighlight() {
        if (itemCount == 0) return
        paramPositions[highlightedCommandOption?.name]?.let { position ->
            getItem(position).highlighted = false
            notifyItemChanged(position)
        }
    }

    fun highlightOption(commandOption: ApplicationCommandOption) {
        if (highlightedCommandOption == commandOption) return
        clearParamOptionHighlight()
        highlightedCommandOption = commandOption
        if (itemCount == 0) return
        paramPositions[commandOption.name]?.let { position ->
            highlightedCommandOption = commandOption
            getItem(position).highlighted = true
            notifyItemChanged(position)
            recycler.post {
                scroller.targetPosition = if (position == 1) 0 else position
                (recycler.layoutManager as LinearLayoutManager?)?.startSmoothScroll(scroller)
            }
        }
    }

    fun onParamClicked(option: ApplicationCommandOption) {
        onClickApplicationCommandOption(option)
    }

    fun setApplicationCommand(command: ApplicationCommand, application: Application) {
        if (currentCommand?.id == command.id) return
        currentCommand = command

        val items = ArrayList<SelectedApplicationCommandItem>()
        items += SelectedApplicationCommandItem(application = application, title = command.name)
        command.options.filter { it.required }.forEach { option ->
            paramPositions[option.name] = items.size
            items += SelectedApplicationCommandItem(option = option)
        }
        if (command.options.any { !it.required }) {
            items += SelectedApplicationCommandItem(
                heading = recycler.resources.getString(R.string.commands_optional_header),
            )
        }
        command.options.filter { !it.required }.forEach { option ->
            paramPositions[option.name] = items.size
            items += SelectedApplicationCommandItem(option = option)
        }
        setData(items)
    }

    fun setVerified(
        verifiedInputs: Map<ApplicationCommandOption, Boolean>,
        showOptionErrorSet: Set<ApplicationCommandOption>,
    ) {
        if (itemCount == 0) return
        verifiedInputs.forEach { (option, valid) ->
            paramPositions[option.name]?.let { position ->
                getItem(position).completed = valid
                getItem(position).error = option in showOptionErrorSet
            }
        }
        configureVerified()
    }

    private fun configureVerified() = Unit

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): MGRecyclerViewHolder<SelectedApplicationCommandAdapter, SelectedApplicationCommandItem> =
        when (viewType) {
            SelectedApplicationCommandItem.TYPE_TITLE -> SelectedAppCommandTitleAdapterItem(this)
            SelectedApplicationCommandItem.TYPE_PARAM -> SelectedAppCommandOptionAdapterItem(this)
            SelectedApplicationCommandItem.TYPE_SECTION_HEADING ->
                SelectedAppCommandSectionHeadingAdapterItem(this)
            else -> throw invalidViewTypeException(viewType)
        }
}
