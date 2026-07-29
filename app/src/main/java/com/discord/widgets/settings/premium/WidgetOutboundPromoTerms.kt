package com.discord.widgets.settings.premium

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentManager
import com.discord.R
import com.discord.app.AppBottomSheet
import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.simpleast.core.simple.SimpleMarkdownRules
import com.discord.utilities.textprocessing.AstRenderer
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.Rules
import com.discord.utilities.textprocessing.node.UrlNode
import com.discord.utilities.uri.UriHandler
import com.discord.utilities.view.text.LinkifiedTextView

class WidgetOutboundPromoTerms : AppBottomSheet() {
    private val parser = Parser<UrlNode.RenderContext, Node<UrlNode.RenderContext>, MessageParseState>(false).apply {
        addRule(Rules.createMaskedLinkRule())
        addRule(Rules.createUrlRule())
        addRules(SimpleMarkdownRules.createSimpleMarkdownRules(false, false))
    }

    override fun getContentViewResId() = R.layout.widget_outbound_promo_terms

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.promoTermsClose).setOnClickListener { dismiss() }

        val content = arguments?.getString(ARG_CONTENT).orEmpty()
        view.findViewById<LinkifiedTextView>(R.id.promoTermsContent).setDraweeSpanStringBuilder(
            AstRenderer.render(parser.parse(content, MessageParseState.initialState), renderContext()),
        )
    }

    private fun renderContext() = object : UrlNode.RenderContext {
        override val context: Context = requireContext()
        override val linkColorAttrResId = R.attr.colorTextLink
        override val onClickUrl: (Context, String, String?) -> Unit = { clickContext, url, _ ->
            UriHandler.handle(clickContext, url)
        }
        override val onLongPressUrl: (String) -> Unit = {}
    }

    companion object {
        private const val ARG_CONTENT = "ARG_CONTENT"

        fun show(content: String, fragmentManager: FragmentManager) {
            WidgetOutboundPromoTerms().apply {
                arguments = Bundle().apply { putString(ARG_CONTENT, content) }
            }.show(fragmentManager, WidgetOutboundPromoTerms::class.java.name)
        }
    }
}
