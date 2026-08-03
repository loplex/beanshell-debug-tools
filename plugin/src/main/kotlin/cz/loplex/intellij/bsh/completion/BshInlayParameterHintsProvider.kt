package cz.loplex.intellij.bsh.completion

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Shows `name:` hints before the arguments of a resolved `method(...)` call. */
class BshInlayParameterHintsProvider : InlayHintsProvider {

    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector = Collector

    private object Collector : SharedBypassCollector {

        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element.node.elementType !== E.METHOD_INVOCATION) return

            val name = element.children.filterIsInstance<BshAmbiguousName>().firstOrNull() ?: return
            val method = name.reference?.resolve() as? BshMethodDeclaration ?: return

            val paramNames = parameterNames(method)
            if (paramNames.isEmpty()) return

            val argument = element.node.findChildByType(E.ARGUMENTS) ?: return
            val args = argument.getChildren(null)
                .filter { it.psi.firstChild != null } // argument expressions (composite)
                .map { it.psi }

            args.forEachIndexed { index, arg ->
                val paramName = paramNames.getOrNull(index) ?: return@forEachIndexed
                sink.addPresentation(
                    InlineInlayPosition(arg.textRange.startOffset, relatedToPrevious = false),
                    tooltip = null,
                    hintFormat = HintFormat.default.withColorKind(HintColorKind.Parameter),
                ) {
                    text("$paramName:")
                }
            }
        }

        private fun parameterNames(method: BshMethodDeclaration): List<String> {
            val params = method.node.findChildByType(E.FORMAL_PARAMETERS) ?: return emptyList()
            return params.getChildren(null)
                .filter { it.elementType === E.FORMAL_PARAMETER }
                .mapNotNull { it.findChildByType(BshTokenTypes.IDENTIFIER)?.text }
        }
    }
}
