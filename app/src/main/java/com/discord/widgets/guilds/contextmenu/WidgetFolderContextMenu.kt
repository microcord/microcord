package com.discord.widgets.guilds.contextmenu

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.discord.R
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle

/** The folder long-press menu: mark every guild in the folder read, or open folder settings. */
class WidgetFolderContextMenu : AnchoredContextMenu(R.layout.widget_folder_context_menu) {

    private val folderId: Long
        get() = requireArguments().getLong(EXTRA_FOLDER_ID)

    private val viewModel: FolderContextMenuViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                FolderContextMenuViewModel(folderId) as T
        }
    }

    private lateinit var cardView: CardView
    private lateinit var header: TextView
    private lateinit var markAsRead: TextView
    private lateinit var settings: TextView

    override val card: CardView get() = cardView

    override fun onViewBound(view: View) {
        super.onViewBound(view)
        cardView = view.findViewById(R.id.folder_context_menu_card)
        header = view.findViewById(R.id.folder_context_menu_header)
        markAsRead = view.findViewById(R.id.folder_context_menu_mark_as_read)
        settings = view.findViewById(R.id.folder_context_menu_settings)
    }

    override fun onViewBoundOrOnResume() {
        super.onViewBoundOrOnResume()
        viewModel.observeViewState()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetFolderContextMenu::class.java) { configureUI(it) }
        viewModel.observeEvents()
            .bindToComponentLifecycle(this)
            .appSubscribe(WidgetFolderContextMenu::class.java) { dismiss() }
    }

    private fun configureUI(viewState: FolderContextMenuViewModel.ViewState) = when (viewState) {
        FolderContextMenuViewModel.ViewState.Invalid -> hide(requireActivity(), animate = false)
        is FolderContextMenuViewModel.ViewState.Valid -> configureValidUI(viewState)
    }

    private fun configureValidUI(viewState: FolderContextMenuViewModel.ViewState.Valid) {
        header.text = viewState.folder.name ?: getString(R.string.guild_folder_unnamed)

        markAsRead.setOnClickListener {
            dismiss()
            viewModel.onMarkAsReadClicked()
        }
        settings.setOnClickListener {
            dismiss()
            viewState.folder.id?.let { ContextMenuNavigation.showFolderSettings(settings.context, it) }
            viewModel.onSettingsClicked()
        }

        markAsRead.visibility = if (viewState.showMarkAsRead) View.VISIBLE else View.GONE
        cardView.setContentPadding(0, 0, 0, 0)
        revealCard()
    }

    private fun dismiss() = dismissCard { hide(it, animate = false) }

    companion object : AnchoredContextMenu.Host(
        fragmentTag = "WidgetFolderContextMenu",
        viewContainerTag = "WidgetFolderContextMenuViewContainer",
        contentLayoutId = R.layout.widget_folder_context_menu,
    ) {
        private const val EXTRA_FOLDER_ID = "com.discord.intent.extra.EXTRA_FOLDER_ID"

        override fun newInstance(id: Long): Fragment = WidgetFolderContextMenu().apply {
            arguments = Bundle().apply { putLong(EXTRA_FOLDER_ID, id) }
        }
    }
}
