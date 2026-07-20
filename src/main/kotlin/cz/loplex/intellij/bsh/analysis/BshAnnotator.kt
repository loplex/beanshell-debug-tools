package cz.loplex.intellij.bsh.analysis

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.TokenType
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/**
 * Lexical analysis that complements the parser's structural error reporting:
 *  - unterminated string / character literals,
 *  - unterminated block or doc comments,
 *  - stray illegal characters.
 *
 * Structural problems (unbalanced braces, missing semicolons, …) are reported
 * by [cz.loplex.intellij.bsh.parser.BshParser] through builder error markers.
 */
class BshAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.firstChild != null) return // only leaf tokens
        when (element.node.elementType) {
            BshTokenTypes.STRING_LITERAL ->
                if (!isTerminatedString(element.text)) {
                    error(holder, element.textRange, "Unterminated string literal")
                }

            BshTokenTypes.CHARACTER_LITERAL ->
                if (!isTerminated(element.text, '\'')) {
                    error(holder, element.textRange, "Unterminated character literal")
                }

            BshTokenTypes.BLOCK_COMMENT, BshTokenTypes.DOC_COMMENT ->
                if (!element.text.endsWith("*/") || element.text.length < 4) {
                    error(holder, element.textRange, "Unterminated comment")
                }

            TokenType.BAD_CHARACTER ->
                error(holder, element.textRange, "Illegal character")
        }
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
