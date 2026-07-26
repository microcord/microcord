package com.discord.utilities.search.query.node

import android.content.Context
import com.discord.simpleast.core.node.Node
import com.discord.utilities.search.query.node.answer.AnswerNode
import com.discord.utilities.search.query.node.content.ContentNode
import com.discord.utilities.search.validation.SearchData

/**
 * One token of a parsed search query.
 *
 * A query is an AST, not a string: `from:` becomes a `FilterNode`, the `alice#0001` that follows it
 * becomes a `UserNode`, and everything else falls through to `ContentNode`. Rendering the AST is how
 * the search input paints its filter chips, and walking it is how the REST query is built.
 */
abstract class QueryNode : Node<Context>() {

    /** The literal text this node contributes back to the input field. */
    abstract val text: CharSequence

    companion object Preprocessor {

        /**
         * Merges adjacent free text and drops a trailing all-whitespace token.
         *
         * Mutates the receiver in place, matching the reference.
         *
         * **Reference bug reproduced.** The reference additionally intends to demote answer nodes
         * that no longer resolve against [searchData] — a `from:` user who left the guild should
         * become plain content — but it maps into a fresh `ArrayList` and then consolidates the
         * *original* list, discarding the mapped one. JADX and Vineflower agree instruction for
         * instruction (`QueryNode.java:54-65`), so the demotion never takes effect in 126.21 and an
         * unresolvable `from:`/`in:` answer still reaches `SearchQuery.Builder` (where it
         * contributes a `null` parameter value). Behaviour is preserved here; [demote] exists so the
         * fix is one call away once someone decides parity or correctness wins.
         */
        fun MutableList<QueryNode>.preprocess(searchData: SearchData) {
            consolidateContentNodes()
        }

        /** The demotion pass the reference builds but never applies. Not called; see [preprocess]. */
        fun MutableList<QueryNode>.demote(searchData: SearchData) {
            for (index in indices) {
                val node = this[index]
                if (node is AnswerNode && !node.isValid(searchData)) {
                    this[index] = ContentNode(node.text)
                }
            }
        }

        private fun MutableList<QueryNode>.consolidateContentNodes() {
            var index = 1
            while (index < size) {
                val previous = this[index - 1]
                val current = this[index]
                if (previous is ContentNode && current is ContentNode) {
                    this[index - 1] = ContentNode(previous, current)
                    removeAt(index)
                } else {
                    index++
                }
            }
            val last = lastOrNull()
            if (last is ContentNode && last.content.trim().isEmpty()) {
                removeAt(size - 1)
            }
        }
    }
}
