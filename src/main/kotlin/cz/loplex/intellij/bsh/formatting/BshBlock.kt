package cz.loplex.intellij.bsh.formatting

import com.intellij.formatting.Block
import com.intellij.formatting.Indent
import com.intellij.formatting.Spacing
import com.intellij.formatting.SpacingBuilder
import com.intellij.formatting.Wrap
import com.intellij.lang.ASTNode
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * A formatting block over one AST node. Statements inside a `{ ... }` block are
 * indented one level; braces themselves stay at the enclosing level, which
 * produces standard nested indentation.
 */
class BshBlock(
    node: ASTNode,
    wrap: Wrap?,
    private val spacingBuilder: SpacingBuilder,
) : AbstractBlock(node, wrap, null) {

    override fun buildChildren(): List<Block> {
        val children = ArrayList<Block>()
        var child = myNode.firstChildNode
        while (child != null) {
            if (child.elementType !== TokenType.WHITE_SPACE && child.textLength > 0) {
                children.add(BshBlock(child, null, spacingBuilder))
            }
            child = child.treeNext
        }
        return children
    }

    override fun getIndent(): Indent {
        val parentType = myNode.treeParent?.elementType
        if (parentType === E.BLOCK) {
            val type = myNode.elementType
            return if (type === BshTokenTypes.LBRACE || type === BshTokenTypes.RBRACE) {
                Indent.getNoneIndent()
            } else {
                Indent.getNormalIndent()
            }
        }
        return Indent.getNoneIndent()
    }

    override fun getChildIndent(): Indent =
        if (myNode.elementType === E.BLOCK) Indent.getNormalIndent() else Indent.getNoneIndent()

    override fun getSpacing(child1: Block?, child2: Block): Spacing? =
        spacingBuilder.getSpacing(this, child1, child2)

    override fun isLeaf(): Boolean = myNode.firstChildNode == null
}
