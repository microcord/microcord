@file:JvmName("WidgetMemberVerificationViewModelKt")

package com.discord.widgets.servers.member_verification

import com.discord.simpleast.core.node.Node
import com.discord.simpleast.core.parser.Parser
import com.discord.utilities.textprocessing.MessageParseState
import com.discord.utilities.textprocessing.MessageRenderContext

typealias AST = MutableList<Node<MessageRenderContext>>
typealias RulesParser = Parser<MessageRenderContext, Node<MessageRenderContext>, MessageParseState>
