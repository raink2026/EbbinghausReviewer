package com.ebbinghaus.review.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ebbinghaus.review.data.sync.protocol.ResolvedMarkdownAsset
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private sealed interface MarkdownBlockUi {
    data class HeadingBlock(val level: Int, val tokens: List<InlineToken>) : MarkdownBlockUi
    data class ParagraphBlock(val tokens: List<InlineToken>) : MarkdownBlockUi
    data class CodeBlock(val literal: String) : MarkdownBlockUi
    data class QuoteBlock(val text: String) : MarkdownBlockUi
    data class ListBlock(val ordered: Boolean, val items: List<String>) : MarkdownBlockUi
    data object DividerBlock : MarkdownBlockUi
}

private sealed interface InlineToken {
    data class Literal(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false
    ) : InlineToken

    data class ImageToken(val destination: String, val altText: String) : InlineToken
}

@Composable
fun MarkdownRenderer(
    markdownBody: String,
    resolvedAssets: List<ResolvedMarkdownAsset>,
    modifier: Modifier = Modifier,
    onRepairAsset: (() -> Unit)? = null,
    onImageClick: ((String) -> Unit)? = null
) {
    val blocks = remember(markdownBody) { parseBlocks(markdownBody) }
    val assetsByDestination = remember(resolvedAssets) {
        resolvedAssets.associateBy { it.reference.destination }
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlockUi.HeadingBlock -> Text(
                    text = annotated(block.tokens, MaterialTheme.colorScheme.surfaceVariant),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        else -> MaterialTheme.typography.titleLarge
                    },
                    fontWeight = FontWeight.Bold
                )
                is MarkdownBlockUi.ParagraphBlock -> InlineContent(
                    tokens = block.tokens,
                    assetsByDestination = assetsByDestination,
                    onRepairAsset = onRepairAsset,
                    onImageClick = onImageClick
                )
                is MarkdownBlockUi.CodeBlock -> Text(
                    text = block.literal,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(4.dp)
                        )
                        .padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium
                )
                is MarkdownBlockUi.QuoteBlock -> Text(
                    text = block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(2.dp)
                        )
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is MarkdownBlockUi.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        Text(if (block.ordered) "${index + 1}. $item" else "• $item")
                    }
                }
                MarkdownBlockUi.DividerBlock -> Box(
                    Modifier.fillMaxWidth().height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
            }
        }
    }
}

@Composable
private fun InlineContent(
    tokens: List<InlineToken>,
    assetsByDestination: Map<String, ResolvedMarkdownAsset>,
    onRepairAsset: (() -> Unit)?,
    onImageClick: ((String) -> Unit)?
) {
    val textBuffer = mutableListOf<InlineToken.Literal>()
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    fun flushText(): AnnotatedString? = textBuffer.takeIf { it.isNotEmpty() }?.let {
        annotated(it, codeBackground).also { textBuffer.clear() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tokens.forEach { token ->
            when (token) {
                is InlineToken.Literal -> textBuffer += token
                is InlineToken.ImageToken -> {
                    flushText()?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    val resolved = assetsByDestination[token.destination]
                    val file = resolved?.cachedAsset?.file
                    if (file != null && file.isFile) {
                        AsyncImage(
                            model = file,
                            contentDescription = token.altText,
                            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        MissingAsset(onRepairAsset)
                    }
                }
            }
        }
        flushText()?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun MissingAsset(onRepairAsset: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 5f)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.padding(4.dp))
        Text("图片尚未缓存", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onRepairAsset != null) {
            IconButton(onClick = onRepairAsset) {
                Icon(Icons.Default.Refresh, contentDescription = "重新同步图片")
            }
        }
    }
}

private fun annotated(tokens: List<InlineToken>, codeBackground: Color): AnnotatedString =
    buildAnnotatedString {
    tokens.filterIsInstance<InlineToken.Literal>().forEach { token ->
        val style = SpanStyle(
            fontWeight = if (token.bold) FontWeight.Bold else null,
            fontStyle = if (token.italic) FontStyle.Italic else null,
            fontFamily = if (token.code) FontFamily.Monospace else null,
            background = if (token.code) codeBackground else Color.Unspecified
        )
        pushStyle(style)
        append(token.text)
        pop()
    }
}

private fun parseBlocks(markdown: String): List<MarkdownBlockUi> {
    val document = Parser.builder().build().parse(markdown)
    val blocks = mutableListOf<MarkdownBlockUi>()
    var node = document.firstChild
    while (node != null) {
        when (node) {
            is Heading -> blocks += MarkdownBlockUi.HeadingBlock(node.level, inlineTokens(node))
            is Paragraph -> blocks += MarkdownBlockUi.ParagraphBlock(inlineTokens(node))
            is FencedCodeBlock -> blocks += MarkdownBlockUi.CodeBlock(node.literal)
            is IndentedCodeBlock -> blocks += MarkdownBlockUi.CodeBlock(node.literal)
            is BlockQuote -> blocks += MarkdownBlockUi.QuoteBlock(plainText(node))
            is BulletList -> blocks += MarkdownBlockUi.ListBlock(false, listItems(node))
            is OrderedList -> blocks += MarkdownBlockUi.ListBlock(true, listItems(node))
            is ThematicBreak -> blocks += MarkdownBlockUi.DividerBlock
        }
        node = node.next
    }
    return blocks
}

private fun inlineTokens(parent: Node): List<InlineToken> {
    val result = mutableListOf<InlineToken>()
    fun visit(node: Node, bold: Boolean = false, italic: Boolean = false) {
        when (node) {
            is MarkdownText -> result += InlineToken.Literal(node.literal, bold, italic)
            is Code -> result += InlineToken.Literal(node.literal, bold, italic, code = true)
            is SoftLineBreak -> result += InlineToken.Literal("\n", bold, italic)
            is Image -> result += InlineToken.ImageToken(node.destination, plainText(node))
            is StrongEmphasis -> children(node) { visit(it, bold = true, italic = italic) }
            is Emphasis -> children(node) { visit(it, bold = bold, italic = true) }
            is Link -> children(node) { visit(it, bold, italic) }
            else -> children(node) { visit(it, bold, italic) }
        }
    }
    children(parent, ::visit)
    return result
}

private fun listItems(parent: Node): List<String> {
    val result = mutableListOf<String>()
    var child = parent.firstChild
    while (child != null) {
        if (child is ListItem) result += plainText(child).trim()
        child = child.next
    }
    return result
}

private fun plainText(parent: Node): String = buildString {
    fun appendNode(node: Node) {
        when (node) {
            is MarkdownText -> append(node.literal)
            is Code -> append(node.literal)
            is SoftLineBreak -> append('\n')
            else -> children(node, ::appendNode)
        }
    }
    children(parent, ::appendNode)
}

private fun children(parent: Node, block: (Node) -> Unit) {
    var child = parent.firstChild
    while (child != null) {
        block(child)
        child = child.next
    }
}
