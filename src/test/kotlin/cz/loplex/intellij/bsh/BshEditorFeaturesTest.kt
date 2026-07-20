package cz.loplex.intellij.bsh

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.documentation.BshDocumentationProvider
import cz.loplex.intellij.bsh.template.BshTemplateContextType

class BshEditorFeaturesTest : BasePlatformTestCase() {

    fun testReformatIndentsNestedBlocks() {
        myFixture.configureByText("a.bsh", "int f() {\nif (x) {\ng();\n}\n}")
        WriteCommandAction.runWriteCommandAction(project) {
            CodeStyleManager.getInstance(project).reformat(myFixture.file)
        }
        myFixture.checkResult("int f() {\n    if (x) {\n        g();\n    }\n}")
    }

    fun testDocumentationForMethod() {
        myFixture.configureByText("a.bsh", "int sq(int n) { return n * n; }\n<caret>sq(2);")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull(target)
        val doc = BshDocumentationProvider().generateDoc(target, null)
        assertNotNull(doc)
        assertTrue("mentions method name", doc!!.contains("sq"))
        assertTrue("mentions kind", doc.contains("method"))
    }

    fun testLiveTemplateContextMatchesBshFiles() {
        val file = myFixture.configureByText("a.bsh", "x")
        val context = TemplateActionContext.expanding(file, 0)
        assertTrue(BshTemplateContextType().isInContext(context))
    }
}
