package cz.loplex.intellij.bsh.completion

import com.intellij.codeInsight.hints.HintInfo
import com.intellij.codeInsight.hints.InlayInfo
import com.intellij.codeInsight.hints.InlayParameterHintsProvider
import com.intellij.psi.PsiElement
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Shows `name:` hints before the arguments of a resolved `method(...)` call. */
class BshInlayParameterHintsProvider : InlayParameterHintsProvider {

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        if (element.node.elementType !== E.METHOD_INVOCATION) return emptyList()

        val name = element.children.filterIsInstance<BshAmbiguousName>().firstOrNull() ?: return emptyList()
        val method = name.reference?.resolve() as? BshMethodDeclaration ?: return emptyList()

        val paramNames = parameterNames(method)
        if (paramNames.isEmpty()) return emptyList()

        val argument = element.node.findChildByType(E.ARGUMENTS) ?: return emptyList()
        val args = argument.getChildren(null)
            .filter { it.psi.firstChild != null } // argument expressions (composite)
            .map { it.psi }

        return args.mapIndexedNotNull { index, arg ->
            paramNames.getOrNull(index)?.let { InlayInfo(it, arg.textRange.startOffset) }
        }
    }

    override fun getDefaultBlackList(): Set<String> = emptySet()

    override fun getHintInfo(element: PsiElement): HintInfo? = null

    private fun parameterNames(method: BshMethodDeclaration): List<String> {
        val params = method.node.findChildByType(E.FORMAL_PARAMETERS) ?: return emptyList()
        return params.getChildren(null)
            .filter { it.elementType === E.FORMAL_PARAMETER }
            .mapNotNull { it.findChildByType(cz.loplex.intellij.bsh.psi.BshTokenTypes.IDENTIFIER)?.text }
    }
}
