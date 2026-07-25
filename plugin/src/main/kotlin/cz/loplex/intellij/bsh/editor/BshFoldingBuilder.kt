package cz.loplex.intellij.bsh.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes

/**
 * Folds `{ ... }` blocks, multi-line block/doc comments and consecutive import
 * statements. Brace and comment folding work on lexer leaves; import folding
 * uses the top-level AST nodes.
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

        addImportGroups(descriptors, document, root.node)
        return descriptors.toTypedArray()
    }

    /** Collapses a run of two or more consecutive import statements into one region. */
    private fun addImportGroups(
        target: MutableList<FoldingDescriptor>,
        document: Document,
        root: ASTNode,
    ) {
        var child = root.firstChildNode
        while (child != null) {
            if (child.elementType === BshElementTypes.IMPORT_DECLARATION) {
                val first = child
                var last = child
                var count = 1
                var next = child.treeNext
                while (next != null) {
                    if (next.elementType === BshElementTypes.IMPORT_DECLARATION) {
                        last = next; count++
                    } else if (next.psi is com.intellij.psi.PsiWhiteSpace || next.psi is com.intellij.psi.PsiComment) {
                        // keep scanning across whitespace/comments
                    } else {
                        break
                    }
                    next = next.treeNext
                }
                if (count >= 2) {
                    val start = first.startOffset + "import ".length
                    val end = last.textRange.endOffset
                    if (end > start && document.getLineNumber(start) != document.getLineNumber(end - 1)) {
                        target += FoldingDescriptor(first, TextRange(start, end), null, "...")
                    }
                }
                child = last.treeNext
            } else {
                child = child.treeNext
            }
        }
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
