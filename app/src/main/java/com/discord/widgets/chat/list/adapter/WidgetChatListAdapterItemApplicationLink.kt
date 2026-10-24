package com.discord.widgets.chat.list.adapter

import android.view.LayoutInflater
import android.view.View
import com.discord.api.applicationdirectory.ApplicationDirectoryApplication
import com.discord.databinding.WidgetChatListAdapterItemApplicationLinkBinding
import com.discord.utilities.icon.IconUtils
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import com.discord.widgets.applicationdirectory.ApplicationInstallCoordinator
import com.discord.widgets.chat.list.entries.ApplicationLinkEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import rx.Observable
import rx.Subscription

/**
 * The card under a message with an app install link: the bot's profile banner, the app's icon and
 * name. As in Discord's mobile app it has no button: tapping the card starts the same install flow
 * as the link. Nothing shows while the app loads, or when it cannot be installed.
 */
class WidgetChatListAdapterItemApplicationLink private constructor(
    adapter: WidgetChatListAdapter,
    private val binding: WidgetChatListAdapterItemApplicationLinkBinding,
) : WidgetChatListItem(binding.root, adapter) {

    constructor(adapter: WidgetChatListAdapter) : this(
        adapter,
        WidgetChatListAdapterItemApplicationLinkBinding.inflate(
            LayoutInflater.from(adapter.recycler.context),
            adapter.recycler,
            false,
        ),
    )

    private var modelSubscription: Subscription? = null
    override val subscription: Subscription? get() = modelSubscription

    override fun onConfigure(position: Int, data: ChatListEntry) {
        super.onConfigure(position, data)
        val entry = data as ApplicationLinkEntry
        modelSubscription?.unsubscribe()
        configureCellHighlight(
            entry.message,
            binding.chatListAdapterItemHighlightedBg,
            binding.chatListAdapterItemGutterBg,
            entry.isPendingReply,
        )
        binding.applicationLinkCard.visibility = View.GONE
        load(entry.applicationId).ui().appSubscribe(
            errorClass = WidgetChatListAdapterItemApplicationLink::class.java,
            subscriptionHandler = { modelSubscription = it },
            errorHandler = { it.showErrorToasts = false },
        ) { bind(it) }
    }

    private fun bind(application: ApplicationDirectoryApplication) {
        val installable = application.customInstallUrl != null ||
            ApplicationInstallCoordinator.installTargets(application).isNotEmpty()
        binding.applicationLinkCard.visibility = if (installable) View.VISIBLE else View.GONE
        if (!installable) return

        val bot = application.bot
        val banner = bot?.let { IconUtils.getForUserBanner(it.id, it.banner?.value, BANNER_SIZE) }
        binding.applicationLinkBanner.visibility = if (banner == null) View.GONE else View.VISIBLE
        banner?.let(binding.applicationLinkBanner::setImageURI)
        binding.applicationLinkIcon.setImageURI(
            application.icon?.let { IconUtils.getApplicationIcon(application.id, it, ICON_SIZE) }
                ?: bot?.let { IconUtils.getForUser(it.id, it.avatar?.value, it.discriminator.toIntOrNull()) },
        )
        binding.applicationLinkName.text = application.name
        binding.applicationLinkCard.contentDescription = application.name
        binding.applicationLinkCard.setOnClickListener {
            ApplicationInstallCoordinator.install(itemView.context, adapter.fragmentManager, application, null)
        }
    }

    private companion object {
        const val BANNER_SIZE = 512
        const val ICON_SIZE = 128

        /** One request per application while the chat is open; a failure is retried on the next bind. */
        val applications = HashMap<Long, Observable<ApplicationDirectoryApplication>>()

        fun load(applicationId: Long): Observable<ApplicationDirectoryApplication> =
            applications.getOrPut(applicationId) {
                val api = RestAPI.applicationDirectoryApiOrNull ?: return Observable.empty()
                api.publicApplication(applicationId)
                    .restSubscribeOn()
                    .doOnError { applications.remove(applicationId) }
                    .cache()
            }
    }
}
