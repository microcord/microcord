package com.discord.widgets.guildscheduledevent

import com.discord.api.role.GuildRole
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.stores.StoreStream
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.DiscordParser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessagePreprocessor
import com.discord.utilities.textprocessing.MessageRenderContext
import com.discord.utilities.textprocessing.node.SpoilerNode
import com.discord.utilities.view.text.LinkifiedTextView
import com.discord.models.member.GuildMember
import java.lang.ref.WeakReference
import rx.Observable

/**
 * Renders an event description as Discord markdown into a [LinkifiedTextView].
 *
 * Two pieces of state make this a class rather than a function:
 *
 *  - the parsed AST is cached against the description string, because the card re-binds on every
 *    store emission and re-parsing is both wasteful and visually unstable;
 *  - revealed spoilers are remembered per description. A spoiler tap has to re-render, and the
 *    only way to keep it revealed across that render is to hold the revealed indices here — the
 *    event has no `StoreMessageState` equivalent.
 *
 * The text view is held weakly so a card that has been recycled while its render context request
 * is in flight does not keep the old view alive.
 */
class GuildScheduledEventDescriptionParser(textView: LinkifiedTextView) {

    private val weakRefTextView = WeakReference(textView)
    private var savedDescriptionCache: DescriptionCache = DescriptionCache.NoDescription

    private sealed class DescriptionCache(
        val maybeDescription: String?,
        val maybeAst: MutableList<Node<MessageRenderContext>>?,
    ) {
        object NoDescription : DescriptionCache(null, null)

        class HasDescription(
            val description: String,
            val ast: MutableList<Node<MessageRenderContext>>,
            val shownSpoilerIndices: MutableSet<Int>,
        ) : DescriptionCache(description, ast)
    }

    private data class MessageRenderContextModel(
        val channelNames: Map<Long, String>? = null,
        val userNames: Map<Long, String>? = null,
        val roles: Map<Long, GuildRole>? = null,
    )

    /**
     * Parses [description] and pushes the rendered spans into the text view, hiding it when there
     * is nothing to show.
     */
    fun configureDescription(description: String?, guildId: Long) {
        val ast = parseAndSaveDescription(description)
        val textView = weakRefTextView.get() ?: return
        if (ast.isNullOrEmpty()) {
            textView.visibility = android.view.View.GONE
            return
        }
        textView.visibility = android.view.View.VISIBLE
        getRenderContext(guildId)
            .first()
            .appSubscribe(LinkifiedTextView::class.java) { model ->
                textView.setDraweeSpanStringBuilder(
                    AstRenderer.render(
                        ast,
                        MessageRenderContext(
                            context = textView.context,
                            myId = 0L,
                            isAnimationEnabled = false,
                            userNames = model.userNames,
                            channelNames = model.channelNames,
                            roles = model.roles,
                            spoilerOnClick = { spoilerNode -> handleSpoilerClicked(spoilerNode, guildId) },
                        ),
                    ),
                )
            }
    }

    /**
     * Mention names for the description.
     *
     * The reference additionally resolves the *global* usernames of every user mentioned in the
     * text, via a `Tags` scan. That type is not reconstructed in this target, and the reference's
     * own scan is seeded with an empty `TagsBuilder().build()`, so only guild nicknames actually
     * reach the renderer there either. Unnicknamed mentions therefore fall back to the renderer's
     * own "@unknown-user" handling in both.
     */
    private fun getRenderContext(guildId: Long): Observable<MessageRenderContextModel> =
        Observable.combineLatest(
            StoreStream.getChannels().observeNames(),
            StoreStream.getGuilds().observeComputed(guildId),
            StoreStream.getGuilds().observeRoles(guildId),
        ) { channelNames: Map<Long, String>, members: Map<Long, GuildMember>, roles: Map<Long, GuildRole> ->
            MessageRenderContextModel(
                channelNames = channelNames,
                userNames = members.mapNotNull { (id, member) -> member.identityNick()?.let { id to it } }.toMap(),
                roles = roles,
            )
        }

    private fun handleSpoilerClicked(spoilerNode: SpoilerNode<*>, guildId: Long) {
        val cache = savedDescriptionCache as? DescriptionCache.HasDescription ?: return
        cache.shownSpoilerIndices.add(spoilerNode.id)
        updateAstForSpoiler(cache.ast, cache.shownSpoilerIndices)
        configureDescription(cache.description, guildId)
    }

    private fun parseAndSaveDescription(
        description: String?,
    ): MutableList<Node<MessageRenderContext>>? {
        if (savedDescriptionCache.maybeDescription == description) return savedDescriptionCache.maybeAst
        if (description == null) {
            savedDescriptionCache = DescriptionCache.NoDescription
            return null
        }
        val cache = DescriptionCache.HasDescription(
            description,
            ArrayList(PARSER.parse(description, MessageParseState.initialState)),
            LinkedHashSet(),
        )
        savedDescriptionCache = cache
        return cache.ast
    }

    /** Re-numbers the spoilers so the ones in [visibleSpoilerNodeIndices] render revealed. */
    private fun updateAstForSpoiler(
        ast: MutableList<Node<MessageRenderContext>>,
        visibleSpoilerNodeIndices: Set<Int>,
    ) {
        MessagePreprocessor(
            myUserId = -1L,
            visibleSpoilerNodeIndices = visibleSpoilerNodeIndices,
            shouldJumboify = false,
        ).process(ast)
    }

    private companion object {
        /**
         * No masked links — an event description is not trusted enough to let its author hide a
         * destination behind arbitrary text — and no block quotes, which would not fit the card.
         */
        private val PARSER: Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState> =
            DiscordParser.createParser(
                allowMaskedLinks = false,
                supportEntityMentions = true,
                renderBlockQuotes = false,
            )
    }
}
