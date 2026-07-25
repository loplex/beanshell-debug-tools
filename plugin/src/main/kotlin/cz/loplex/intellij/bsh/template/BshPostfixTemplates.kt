package cz.loplex.intellij.bsh.template

import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateExpressionSelector
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider
import com.intellij.codeInsight.template.postfix.templates.StringBasedPostfixTemplate
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Function
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/** Postfix templates such as `expr.sout`, `expr.if`, `expr.while`. */
class BshPostfixTemplateProvider : PostfixTemplateProvider {

    private val templateSet: Set<PostfixTemplate> = setOf(
        BshStringPostfixTemplate("sout", "print(expr);", "print(\$expr\$);\$END\$", this),
        BshStringPostfixTemplate("if", "if (expr) {...}", "if (\$expr\$) {\n\$END\$\n}", this),
        BshStringPostfixTemplate("while", "while (expr) {...}", "while (\$expr\$) {\n\$END\$\n}", this),
    )

    override fun getTemplates(): Set<PostfixTemplate> = templateSet

    override fun isTerminalSymbol(currentChar: Char): Boolean = currentChar == '.'

    override fun preExpand(file: PsiFile, editor: Editor) {}

    override fun afterExpand(file: PsiFile, editor: Editor) {}

    override fun preCheck(copyFile: PsiFile, realEditor: Editor, currentOffset: Int): PsiFile = copyFile

    override fun getId(): String = "bsh.postfix"

    override fun getPresentableName(): String = "BeanShell"
}

private class BshStringPostfixTemplate(
    name: String,
    example: String,
    private val template: String,
    provider: PostfixTemplateProvider,
) : StringBasedPostfixTemplate(name, example, BshExpressionSelector, provider) {

    override fun getTemplateString(element: PsiElement): String = template

    override fun getElementToRemove(expr: PsiElement): PsiElement = expr
}

/** Selects the whole expression that ends at the postfix dot. */
private object BshExpressionSelector : PostfixTemplateExpressionSelector {

    private val EXPRESSION_TYPES = setOf(
        E.PRIMARY_EXPRESSION, E.BINARY_EXPRESSION, E.UNARY_EXPRESSION, E.TERNARY_EXPRESSION,
        E.CAST_EXPRESSION, E.METHOD_INVOCATION, E.ALLOCATION_EXPRESSION, E.LITERAL, E.AMBIGUOUS_NAME,
    )

    override fun hasExpression(context: PsiElement, copyDocument: Document, newOffset: Int): Boolean =
        expressionOf(context) != null

    override fun getExpressions(context: PsiElement, document: Document, offset: Int): List<PsiElement> =
        listOfNotNull(expressionOf(context))

    override fun getRenderer(): Function<PsiElement, String> = Function { it.text }

    private fun expressionOf(context: PsiElement): PsiElement? {
        var expr: PsiElement? = null
        var current: PsiElement? = context
        while (current != null && current !is PsiFile) {
            if (current.node?.elementType in EXPRESSION_TYPES) expr = current
            current = current.parent
        }
        return expr
    }
}
