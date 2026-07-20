package cz.loplex.intellij.bsh.editor

import com.intellij.lang.surroundWith.Surrounder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Base surrounder: replaces the selected statements with a wrapped form and
 * places the caret where the user should keep typing.
 */
abstract class BshSurrounderBase : Surrounder {

    /** Builds the replacement text for [body] and the caret offset within it. */
    protected abstract fun wrap(body: String): Pair<String, Int>

    override fun isApplicable(elements: Array<out PsiElement>): Boolean = elements.isNotEmpty()

    override fun surroundElements(
        project: Project,
        editor: Editor,
        elements: Array<out PsiElement>,
    ): TextRange? {
        if (elements.isEmpty()) return null
        val start = elements.first().textRange.startOffset
        val end = elements.last().textRange.endOffset

        val document = editor.document
        val body = document.getText(TextRange(start, end))
        val (text, caretInText) = wrap(body)

        document.replaceString(start, end, text)
        val manager = PsiDocumentManager.getInstance(project)
        manager.commitDocument(document)

        val file = manager.getPsiFile(document)
        if (file != null) {
            CodeStyleManager.getInstance(project).reformatText(file, start, start + text.length)
            manager.commitDocument(document)
        }

        val caret = (start + caretInText).coerceAtMost(document.textLength)
        return TextRange(caret, caret)
    }
}

class BshIfSurrounder : BshSurrounderBase() {
    override fun getTemplateDescription(): String = "if"
    override fun wrap(body: String): Pair<String, Int> = "if () {\n$body\n}" to "if (".length
}

class BshWhileSurrounder : BshSurrounderBase() {
    override fun getTemplateDescription(): String = "while"
    override fun wrap(body: String): Pair<String, Int> = "while () {\n$body\n}" to "while (".length
}

class BshTrySurrounder : BshSurrounderBase() {
    override fun getTemplateDescription(): String = "try / catch"
    override fun wrap(body: String): Pair<String, Int> {
        val text = "try {\n$body\n} catch (e) {\n}"
        return text to text.length - 1 // caret inside the catch block
    }
}
