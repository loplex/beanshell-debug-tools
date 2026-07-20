package cz.loplex.intellij.bsh.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import cz.loplex.intellij.bsh.BshLanguage
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.reference.BshScopes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Basic completion for BeanShell: language keywords plus the declarations
 * visible at the caret (methods, classes, parameters and variables — both typed
 * and untyped).
 */
class BshCompletionContributor : CompletionContributor() {

    init {
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().withLanguage(BshLanguage),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet,
                ) {
                    addKeywords(result)
                    addVisibleDeclarations(parameters.position, result)
                }
            },
        )
    }

    private fun addKeywords(result: CompletionResultSet) {
        BshTokenTypes.KEYWORDS.forEach {
            result.addElement(LookupElementBuilder.create(it).bold())
        }
    }

    private fun addVisibleDeclarations(position: PsiElement, result: CompletionResultSet) {
        val file = position.containingFile ?: return
        val seen = HashSet<String>()

        fun offer(name: String?, element: LookupElement) {
            if (name.isNullOrEmpty() || !seen.add(name)) return
            result.addElement(element)
        }

        // Methods and classes are file-global.
        PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java).forEach { decl ->
            when (decl.node.elementType) {
                E.METHOD_DECLARATION -> offer(
                    decl.name,
                    LookupElementBuilder.create(decl.name ?: return@forEach)
                        .withIcon(AllIcons.Nodes.Method).withTailText("()", true),
                )
                E.CLASS_DECLARATION -> offer(
                    decl.name,
                    LookupElementBuilder.create(decl.name ?: return@forEach)
                        .withIcon(AllIcons.Nodes.Class),
                )
            }
        }

        // Parameters and typed variables in the enclosing scopes.
        PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java)
            .filter {
                (it.node.elementType === E.VARIABLE_DECLARATOR ||
                    it.node.elementType === E.FORMAL_PARAMETER) &&
                    PsiTreeUtil.isAncestor(BshScopes.scopeOf(it), position, false)
            }
            .forEach { decl ->
                val icon = if (decl.node.elementType === E.FORMAL_PARAMETER)
                    AllIcons.Nodes.Parameter else AllIcons.Nodes.Variable
                offer(decl.name, LookupElementBuilder.create(decl.name ?: return@forEach).withIcon(icon))
            }

        // Untyped variables (simple assignment targets) visible in scope.
        PsiTreeUtil.collectElementsOfType(file, BshAmbiguousName::class.java)
            .filter {
                BshScopes.isSimpleAssignmentTarget(it) &&
                    PsiTreeUtil.isAncestor(BshScopes.scopeOf(it), position, false)
            }
            .forEach { offer(it.name, LookupElementBuilder.create(it.name ?: return@forEach).withIcon(AllIcons.Nodes.Variable)) }
    }
}
