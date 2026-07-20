package cz.loplex.intellij.bsh.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/**
 * Folds `{ ... }` blocks and multi-line block/doc comments. Works directly on
 * the lexer token leaves, so it does not depend on a full syntactic tree.
 */
class BshFoldingBuilder : FoldingBuilderEx(), DumbAware {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val descriptors = mutableListOf<FoldingDescriptor>()
        val braceStack = ArrayDeque<ASTNode>()

        collectLeaves(root.node).forEach { leaf ->
            when (leaf.elementType) {
                BshTokenTypes.LBRACE -> braceStack.addLast(leaf)
                BshTokenTypes.RBRACE -> {
                    val open = braceStack.removeLastOrNull() ?: return@forEach
                    // Fold from the opening brace to the end of the closing brace;
                    // the opening-brace node supplies the "{...}" placeholder text.
                    addRegion(descriptors, document, open, open.startOffset, leaf.textRange.endOffset)
                }
                BshTokenTypes.BLOCK_COMMENT, BshTokenTypes.DOC_COMMENT -> {
                    addRegion(descriptors, document, leaf, leaf.startOffset, leaf.textRange.endOffset)
                }
            }
        }
        return descriptors.toTypedArray()
    }

    override fun getPlaceholderText(node: ASTNode): String = when (node.elementType) {
        BshTokenTypes.BLOCK_COMMENT, BshTokenTypes.DOC_COMMENT -> "/*...*/"
        else -> "{...}"
    }

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false

    private fun addRegion(
        target: MutableList<FoldingDescriptor>,
        document: Document,
        node: ASTNode,
        start: Int,
        end: Int,
    ) {
        if (end <= start || end > document.textLength) return
        if (document.getLineNumber(start) == document.getLineNumber(end - 1)) return
        target += FoldingDescriptor(node, TextRange(start, end))
    }

    private fun collectLeaves(node: ASTNode): List<ASTNode> {
        val result = mutableListOf<ASTNode>()
        fun visit(n: ASTNode) {
            val children = n.getChildren(null)
            if (children.isEmpty()) {
                result += n
            } else {
                children.forEach { visit(it) }
            }
        }
        visit(node)
        return result
    }
}
