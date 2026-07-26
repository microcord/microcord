package com.discord.stores

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.discord.api.channel.Channel
import com.discord.models.domain.ModelUserRelationship
import com.discord.utilities.uri.UriHandler

/**
 * Which link destinations may be opened without warning the user.
 *
 * This is the phishing guard behind masked links (`[text](url)`): a link whose visible text
 * differs from where it actually goes is only trusted when the domain is first-party, when the
 * user has previously chosen to trust that domain, or when the message is in a DM with someone
 * already on the friend list.
 *
 * Trusted domains are persisted in plain `SharedPreferences` (not the Kryo `Persister`), matching
 * the reference, so the set survives a cache wipe.
 */
class StoreMaskedLinks(
    private val dispatcher: Dispatcher,
    private val storeChannelsSelected: StoreChannelsSelected,
    private val storeUserRelationships: StoreUserRelationships,
) : StoreV2() {
    private var userTrustedDomains: MutableSet<String> = LinkedHashSet()
    private var userTrustedDomainsSnapshot: Set<String> = emptySet()

    fun getUserTrustedDomains(): Set<String> = userTrustedDomainsSnapshot

    override fun init(context: Context) {
        super.init(context)
        userTrustedDomains = HashSet(prefs.getStringSet(USER_TRUSTED_DOMAINS_CACHE_KEY, emptySet()).orEmpty())
        markChanged()
    }

    /**
     * @param mask the text the link was rendered as, or null when it was not masked.
     *
     * A masked link whose text equals its destination is not really masked, which is why an equal
     * [mask] falls through to the relationship allowance instead of being rejected outright.
     */
    fun isTrustedDomain(url: String, mask: String?): Boolean {
        if (isTrustedDomain(url)) return true
        if (mask != null && mask != url) return false
        val recipient = storeChannelsSelected.getSelectedChannel()?.let(::dmRecipientId)
        return ModelUserRelationship.isType(
            storeUserRelationships.getRelationships()[recipient],
            ModelUserRelationship.TYPE_FRIEND,
        )
    }

    fun trustDomain(url: String) = dispatcher.schedule {
        getDomainName(url)?.let {
            userTrustedDomains.add(it)
            markChanged()
        }
    }

    override fun snapshotData() {
        userTrustedDomainsSnapshot = HashSet(userTrustedDomains)
        prefs.edit().putStringSet(USER_TRUSTED_DOMAINS_CACHE_KEY, userTrustedDomainsSnapshot).apply()
    }

    private fun isTrustedDomain(url: String): Boolean {
        val host = getDomainName(url)
        return host in userTrustedDomainsSnapshot ||
            (host != null && UriHandler.isImplicitlyTrustedDomain(host))
    }

    /**
     * Only a one-to-one DM has a "the other person" to check a relationship against; a group DM
     * has several recipients and no single trust decision, so it gets none.
     */
    private fun dmRecipientId(channel: Channel): Long? =
        if (channel.type == Channel.DM) channel.recipients?.singleOrNull()?.id else null

    companion object {
        @VisibleForTesting
        internal const val USER_TRUSTED_DOMAINS_CACHE_KEY = "USER_TRUSTED_DOMAINS_CACHE_KEY"

        @VisibleForTesting
        internal fun getDomainName(url: String): String? = try {
            Uri.parse(url).host
        } catch (e: Exception) {
            null
        }
    }
}
