package cz.loplex.intellij.bsh.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Captures the result of an expression statement in a new variable:
 * `compute();` becomes `x = compute();`, with the caret on the new name.
 */
class BshIntroduceVariableIntention : IntentionAction {

    override fun getText(): String = "Introduce variable"

    override fun getFamilyName(): String = "Introduce variable"

    override fun startInWriteAction(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        editor != null && file is BshFile && target(editor, file) != null

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val target = target(editor, file) ?: return
        val offset = target.textRange.startOffset
        val name = freshName(file)
        editor.document.insertString(offset, "$name = ")
        PsiDocumentManager.getInstance(project).commitDocument(editor.document)
        editor.caretModel.moveToOffset(offset)
    }

    /** The top-level expression of an expression statement at the caret. */
    private fun target(editor: Editor, file: PsiFile): PsiElement? {
        val offset = editor.caretModel.offset
        val leaf = file.findElementAt(offset) ?: file.findElementAt((offset - 1).coerceAtLeast(0)) ?: return null
        var current: PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            if (current.node.elementType in EXPRESSION_TYPES) {
                val parent = current.parent
                if (parent is PsiFile || parent?.node?.elementType === E.BLOCK) return current
            }
            current = current.parent
        }
        return null
    }

    private fun freshName(file: PsiFile): String {
        val text = file.text
        return CANDIDATES.firstOrNull { !Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) } ?: "x1"
    }
}

private val EXPRESSION_TYPES = setOf(
    E.PRIMARY_EXPRESSION, E.BINARY_EXPRESSION, E.UNARY_EXPRESSION, E.TERNARY_EXPRESSION,
    E.CAST_EXPRESSION, E.METHOD_INVOCATION, E.ALLOCATION_EXPRESSION, E.LITERAL,
)
private val CANDIDATES = listOf("x", "y", "z", "value", "result")
