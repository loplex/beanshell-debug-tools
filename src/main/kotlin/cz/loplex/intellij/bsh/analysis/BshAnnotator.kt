package cz.loplex.intellij.bsh.analysis

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/**
 * Lightweight structural analysis for BeanShell files:
 *  - unbalanced parentheses, braces and brackets,
 *  - unterminated string / character literals,
 *  - unterminated block or doc comments,
 *  - stray illegal characters.
 *
 * The checks run once per file over the lexer token leaves.
 */
class BshAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element !is BshFile) return

        val opens = ArrayDeque<PsiElement>()

        forEachLeaf(element) { leaf ->
            when (leaf.node.elementType) {
                BshTokenTypes.LPAREN, BshTokenTypes.LBRACE, BshTokenTypes.LBRACKET ->
                    opens.addLast(leaf)

                BshTokenTypes.RPAREN, BshTokenTypes.RBRACE, BshTokenTypes.RBRACKET -> {
                    val open = opens.removeLastOrNull()
                    if (open == null || !matches(open.text, leaf.text)) {
                        error(holder, leaf.textRange, "Unmatched '${leaf.text}'")
                    }
                }

                BshTokenTypes.STRING_LITERAL ->
                    if (!isTerminatedString(leaf.text)) {
                        error(holder, leaf.textRange, "Unterminated string literal")
                    }

                BshTokenTypes.CHARACTER_LITERAL ->
                    if (!isTerminated(leaf.text, '\'')) {
                        error(holder, leaf.textRange, "Unterminated character literal")
                    }

                BshTokenTypes.BLOCK_COMMENT, BshTokenTypes.DOC_COMMENT ->
                    if (!leaf.text.endsWith("*/") || leaf.text.length < 4) {
                        error(holder, leaf.textRange, "Unterminated comment")
                    }

                TokenType.BAD_CHARACTER ->
                    error(holder, leaf.textRange, "Illegal character")
            }
        }

        opens.forEach { open ->
            error(holder, open.textRange, "Unmatched '${open.text}'")
        }
    }

    /** Visits every leaf (childless) PSI element under [root] in document order. */
    private fun forEachLeaf(root: PsiElement, action: (PsiElement) -> Unit) {
        var child = root.firstChild
        if (child == null) {
            action(root)
            return
        }
        while (child != null) {
            forEachLeaf(child, action)
            child = child.nextSibling
        }
    }

    private fun matches(open: String, close: String): Boolean = when (open) {
        "(" -> close == ")"
        "{" -> close == "}"
        "[" -> close == "]"
        else -> false
    }

    private fun isTerminatedString(text: String): Boolean {
        if (text.startsWith("\"\"\"")) {
            return text.length >= 6 && text.endsWith("\"\"\"")
        }
        return isTerminated(text, '"')
    }

    private fun isTerminated(text: String, quote: Char): Boolean =
        text.length >= 2 && text[text.length - 1] == quote

    private fun error(holder: AnnotationHolder, range: TextRange, message: String) {
        holder.newAnnotation(HighlightSeverity.ERROR, message).range(range).create()
    }
}
