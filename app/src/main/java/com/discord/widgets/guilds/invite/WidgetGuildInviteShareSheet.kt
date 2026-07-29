package com.discord.widgets.guilds.invite

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.app.AppViewFlipper
import com.discord.app.appViewModels
import com.discord.stores.StoreNotices
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.bindToComponentLifecycle
import com.discord.views.SearchInputView
import rx.subscriptions.CompositeSubscription

class WidgetGuildInviteShareSheet : AppBottomSheet() {

    private val viewModel by appViewModels {
        GuildInviteShareSheetViewModel(
            argumentsOrDefault.getLong(ARG_CHANNEL_ID).takeIf { it != 0L },
            argumentsOrDefault.getLong(ARG_GUILD_ID),
        )
    }
    private val guildInviteSettingsLauncher by lazy {
        WidgetGuildInviteSettings.registerForResult(this, viewModel::updateInvite)
    }
    private lateinit var flipper: AppViewFlipper
    private lateinit var search: SearchInputView
    private lateinit var adapter: InviteSuggestionsAdapter
    private lateinit var copyLink: TextView
    private lateinit var copySubtitle: TextView
    private lateinit var share: CardView
    private lateinit var settings: View
    private lateinit var emptyLink: TextView
    private lateinit var emptySettings: TextView
    private lateinit var emptyOptions: ImageButton
    private lateinit var emptyShare: View

    override fun getContentViewResId() = R.layout.widget_guild_invite_share_sheet

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setBottomSheetCollapsedStateDisabled()
        flipper = view.findViewById(R.id.suggestions_flipper)
        search = view.findViewById(R.id.search_input)
        copyLink = view.findViewById(R.id.copy_link_title)
        copySubtitle = view.findViewById(R.id.copy_link_subtitle)
        share = view.findViewById(R.id.share_button)
        settings = view.findViewById(R.id.invite_settings_button)
        emptyLink = view.findViewById(R.id.empty_state_invite_link)
        emptySettings = view.findViewById(R.id.empty_state_settings_subtext)
        emptyOptions = view.findViewById(R.id.empty_state_link_options)
        emptyShare = view.findViewById(R.id.empty_state_share_btn)
        val suggestionList = view.findViewById<RecyclerView>(R.id.suggestion_list)
        suggestionList.layoutManager = LinearLayoutManager(requireContext())
        adapter = InviteSuggestionsAdapter(suggestionList).also {
            it.onClick = viewModel::sendInvite
            suggestionList.adapter = it
        }
        search.bindTextWatcher(this, viewModel::onSearchTextChanged)
    }

    override fun bindSubscriptions(compositeSubscription: CompositeSubscription) {
        super.bindSubscriptions(compositeSubscription)
        viewModel.observeViewState().bindToComponentLifecycle(this).appSubscribe(
            WidgetGuildInviteShareSheet::class.java,
            subscriptionHandler = compositeSubscription::add,
            onNext = ::configureUI,
        )
    }

    fun configureUI(state: GuildInviteShareSheetViewModel.ViewState) {
        val invite = state.invite
        val openSettings = View.OnClickListener {
            WidgetGuildInviteSettings.launch(
                requireContext(),
                guildInviteSettingsLauncher,
                invite?.channelId,
                state.guildId,
                "Instant Invite Action Sheet",
            )
        }
        if (state.hasResults) {
            flipper.displayedChild = 1
            adapter.setData(state.inviteSuggestionItems)
            search.setHint(getString(R.string.invite_your_friends))
            val link = invite?.toLink()
            copySubtitle.text = link?.let {
                "$it ${getInviteSettingsText(requireContext(), invite.expirationTimeMs - System.currentTimeMillis())}"
            }
            copyLink.setOnClickListener {
                invite?.let { copyLinkClick(requireContext(), it, state.channel, analyticsSource) }
            }
            share.visibility = if (invite == null) View.GONE else View.VISIBLE
            share.setOnClickListener { invite?.let { shareLinkClick(context, it, state.channel) } }
            settings.visibility = if (state.showInviteSettings) View.VISIBLE else View.GONE
            settings.setOnClickListener(openSettings)
        } else {
            flipper.displayedChild = 0
            emptyLink.text = invite?.toLink() ?: getString(R.string.invite_link_example_verified)
            emptySettings.text = state.inviteSettings?.let {
                getInviteSettingsText(requireContext(), (invite?.expirationTimeMs ?: 0L) - System.currentTimeMillis(), it.maxUses)
            }
            emptyLink.setOnClickListener {
                invite?.let { copyLinkClick(requireContext(), it, state.channel, analyticsSource) }
            }
            emptyShare.setOnClickListener { invite?.let { shareLinkClick(context, it, state.channel) } }
            emptyOptions.visibility = if (state.showInviteSettings) View.VISIBLE else View.GONE
            emptyOptions.setOnClickListener(openSettings)
        }
    }

    private val analyticsSource: String
        get() = argumentsOrDefault.getString(ARG_ANALYTICS_SOURCE).orEmpty()

    companion object {
        private const val ARG_ANALYTICS_SOURCE = "ARG_ANALYTICS_SOURCE"
        private const val ARG_CHANNEL_ID = "ARG_CHANNEL_ID"
        private const val ARG_GUILD_ID = "ARG_GUILD_ID"

        fun enqueueNoticeForHomeTab(
            channelId: Long? = null,
            guildId: Long,
            source: String,
        ) {
            val notices = StoreStream.getNotices()
            val name = "${WidgetGuildInviteShareSheet::class.java.simpleName} - ${System.currentTimeMillis()}"
            notices.requestToShow(StoreNotices.Notice(name = name) { activity ->
                show(activity.supportFragmentManager, channelId, guildId, source)
                true
            })
        }

        fun show(
            fragmentManager: FragmentManager,
            channelId: Long? = null,
            guildId: Long,
            source: String,
        ) = WidgetGuildInviteShareSheet().apply {
            arguments = Bundle().apply {
                channelId?.let { putLong(ARG_CHANNEL_ID, it) }
                putLong(ARG_GUILD_ID, guildId)
                putString(ARG_ANALYTICS_SOURCE, source)
            }
        }.show(fragmentManager, WidgetGuildInviteShareSheet::class.java.name)
    }
}
