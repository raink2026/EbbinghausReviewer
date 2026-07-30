package com.ebbinghaus.review.data.sync.protocol

import com.ebbinghaus.review.data.sync.CachedAsset
import com.ebbinghaus.review.data.sync.ImmutableAssetCache
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser

data class MarkdownAssetReference(
    val destination: String,
    val sha256: String,
    val extension: String,
    val altText: String
)

data class ResolvedMarkdownAsset(
    val reference: MarkdownAssetReference,
    val cachedAsset: CachedAsset?
)

object MarkdownContent {
    private val parser = Parser.builder().build()

    fun titleProjection(markdownBody: String, fallback: String = "Untitled"): String {
        val document = parser.parse(markdownBody)
        var title: String? = null
        document.accept(object : AbstractVisitor() {
            override fun visit(heading: Heading) {
                if (title == null && heading.level == 1) {
                    title = collectText(heading).trim().takeIf { it.isNotEmpty() }
                }
                super.visit(heading)
            }
        })
        return title ?: fallback
    }

    fun assetReferences(markdownBody: String): List<MarkdownAssetReference> {
        val references = mutableListOf<MarkdownAssetReference>()
        parser.parse(markdownBody).accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                val match = ASSET_REFERENCE_REGEX.matchEntire(image.destination)
                if (match != null) {
                    references += MarkdownAssetReference(
                        destination = image.destination,
                        sha256 = match.groupValues[1],
                        extension = match.groupValues[2],
                        altText = collectText(image).trim()
                    )
                }
                super.visit(image)
            }
        })
        return references
    }

    fun invalidImageDestinations(markdownBody: String): List<String> {
        val invalid = mutableListOf<String>()
        parser.parse(markdownBody).accept(object : AbstractVisitor() {
            override fun visit(image: Image) {
                if (!ASSET_REFERENCE_REGEX.matches(image.destination)) invalid += image.destination
                super.visit(image)
            }
        })
        return invalid
    }

    fun insertAssetReference(
        markdownBody: String,
        cursor: Int,
        asset: CachedAsset,
        altText: String = "image"
    ): String {
        require(cursor in 0..markdownBody.length) { "Cursor is outside Markdown body" }
        val safeAlt = altText.replace("[", "\\[").replace("]", "\\]")
        val syntax = "![$safeAlt](../assets/${asset.sha256}.${asset.extension})"
        return markdownBody.substring(0, cursor) + syntax + markdownBody.substring(cursor)
    }

    private fun collectText(parent: Node): String = buildString {
        var child = parent.firstChild
        while (child != null) {
            when (child) {
                is Text -> append(child.literal)
                is Code -> append(child.literal)
                else -> append(collectText(child))
            }
            child = child.next
        }
    }

    private val ASSET_REFERENCE_REGEX = Regex("\\.\\./assets/([0-9a-f]{64})\\.([a-z0-9]{1,10})")
}

class MarkdownAssetResolver(private val cache: ImmutableAssetCache) {
    suspend fun resolve(profileId: String, markdownBody: String): List<ResolvedMarkdownAsset> =
        MarkdownContent.assetReferences(markdownBody).map { reference ->
            ResolvedMarkdownAsset(
                reference = reference,
                cachedAsset = cache.find(
                    profileId = profileId,
                    sha256 = reference.sha256,
                    extension = reference.extension,
                    verifyHash = false
                )
            )
        }
}
