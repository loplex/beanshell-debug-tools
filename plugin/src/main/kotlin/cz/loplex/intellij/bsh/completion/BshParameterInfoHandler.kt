package cz.loplex.intellij.bsh.completion

import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.lang.parameterInfo.UpdateParameterInfoContext
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Shows the parameter list of the invoked method while the caret is inside the
 * argument list of a `method(...)` call, and highlights the current argument.
 */
class BshParameterInfoHandler : ParameterInfoHandler<PsiElement, BshMethodDeclaration> {

    override fun findElementForParameterInfo(context: CreateParameterInfoContext): PsiElement? {
        val arguments = argumentsAt(context.file.findElementAt(context.offset)) ?: return null
        val method = resolveCallTarget(arguments) ?: return null
        context.itemsToShow = arrayOf<Any>(method)
        return arguments
    }

    override fun showParameterInfo(element: PsiElement, context: CreateParameterInfoContext) {
        context.showHint(element, element.textRange.startOffset + 1, this)
    }

    override fun findElementForUpdatingParameterInfo(context: UpdateParameterInfoContext): PsiElement? =
        argumentsAt(context.file.findElementAt(context.offset))

    override fun updateParameterInfo(parameterOwner: PsiElement, context: UpdateParameterInfoContext) {
        context.setCurrentParameter(currentParameterIndex(parameterOwner, context.offset))
    }

    override fun updateUI(p: BshMethodDeclaration?, context: ParameterInfoUIContext) {
        if (p == null) return
        val params = parameterTexts(p)
        if (params.isEmpty()) {
            context.setupUIComponentPresentation("<no parameters>", -1, -1, false, false, false, context.defaultParameterColor)
            return
        }

        val sb = StringBuilder()
        var highlightStart = -1
        var highlightEnd = -1
        params.forEachIndexed { index, text ->
            val start = sb.length
            sb.append(text)
            if (index == context.currentParameterIndex) {
                highlightStart = start
                highlightEnd = sb.length
            }
            if (index < params.size - 1) sb.append(", ")
        }
        context.setupUIComponentPresentation(
            sb.toString(), highlightStart, highlightEnd, false, false, false, context.defaultParameterColor,
        )
    }

    private fun argumentsAt(element: PsiElement?): PsiElement? {
        var current = element
        while (current != null) {
            if (current.node?.elementType === E.ARGUMENTS) return current
            current = current.parent
        }
        return null
    }

    private fun resolveCallTarget(arguments: PsiElement): BshMethodDeclaration? {
        val invocation = arguments.parent ?: return null
        if (invocation.node.elementType !== E.METHOD_INVOCATION) return null
        val name = PsiTreeUtil.findChildOfType(invocation, BshAmbiguousName::class.java) ?: return null
        return name.reference?.resolve() as? BshMethodDeclaration
    }

    private fun currentParameterIndex(arguments: PsiElement, offset: Int): Int {
        var index = 0
        var child = arguments.node.firstChildNode
        while (child != null) {
            if (child.startOffset >= offset) break
            if (child.elementType === BshTokenTypes.COMMA) index++
            child = child.treeNext
        }
        return index
    }

    private fun parameterTexts(method: BshMethodDeclaration): List<String> {
        val params = method.node.findChildByType(E.FORMAL_PARAMETERS) ?: return emptyList()
        return params.getChildren(null)
            .filter { it.elementType === E.FORMAL_PARAMETER }
            .map { it.text }
    }
}
