package cz.loplex.intellij.bsh.documentation

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.psi.BshClassDeclaration
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator
import cz.loplex.intellij.bsh.psi.BshElementTypes as E
import org.jetbrains.annotations.Nls

/** Quick documentation (Ctrl+Q) and navigation hints for BeanShell declarations. */
class BshDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element !is BshNamedElement) return null
        return "${kindOf(element)} ${signatureOf(element)}"
    }

    @Nls
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element !is BshNamedElement) return null
        val sb = StringBuilder()
        sb.append("<b>").append(kindOf(element)).append("</b> ")
        sb.append(escape(signatureOf(element)))
        val file = element.containingFile?.name
        if (file != null) sb.append("<br/><i>in ").append(escape(file)).append("</i>")
        docComment(element)?.let { sb.append("<hr/>").append(escape(it)) }
        return sb.toString()
    }

    private fun kindOf(element: BshNamedElement): String = when (element) {
        is BshMethodDeclaration -> "method"
        is BshClassDeclaration -> "class"
        is BshFormalParameter -> "parameter"
        is BshVariableDeclarator -> "variable"
        else -> "symbol"
    }

    private fun signatureOf(element: BshNamedElement): String {
        val name = element.name ?: "?"
        if (element is BshMethodDeclaration) {
            val params = element.node.findChildByType(E.FORMAL_PARAMETERS)?.text ?: "()"
            return name + params
        }
        return name
    }

    /** Text of a Javadoc-style doc comment immediately preceding the declaration, if any. */
    private fun docComment(element: BshNamedElement): String? {
        var leaf = PsiTreeUtil.prevVisibleLeaf(element)
        // Skip leading declaration tokens (modifiers, type, identifier) to reach a preceding comment.
        var hops = 0
        while (leaf != null && hops < 6) {
            if (leaf.node.elementType === BshTokenTypes.DOC_COMMENT) {
                return leaf.text.trim()
            }
            if (leaf.node.elementType !== BshTokenTypes.KEYWORD &&
                leaf.node.elementType !== BshTokenTypes.IDENTIFIER
            ) {
                break
            }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
            hops++
        }
        return null
    }

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
