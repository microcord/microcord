package com.discord.widgets.user.usersheet

import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext

typealias AST = MutableList<Node<MessageRenderContext>>
typealias BioParser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>
