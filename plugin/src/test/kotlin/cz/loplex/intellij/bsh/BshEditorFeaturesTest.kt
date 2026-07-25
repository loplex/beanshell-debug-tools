package cz.loplex.intellij.bsh

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.analysis.BshSpellcheckingStrategy
import cz.loplex.intellij.bsh.documentation.BshDocumentationProvider
import cz.loplex.intellij.bsh.editor.BshFoldingBuilder
import cz.loplex.intellij.bsh.editor.BshIfSurrounder
import cz.loplex.intellij.bsh.editor.BshSurroundDescriptor
import cz.loplex.intellij.bsh.navigation.BshBreadcrumbsProvider
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.template.BshPostfixTemplateProvider
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

    fun testFoldingCoversImportsAndBlocks() {
        myFixture.configureByText("a.bsh", "import a.b;\nimport c.d;\nint f() {\n    g();\n}")
        val regions = BshFoldingBuilder().buildFoldRegions(myFixture.file, myFixture.editor.document, false)
        assertTrue("import group is folded", regions.any { it.placeholderText == "..." })
        assertTrue("block is folded too", regions.size >= 2)
    }

    fun testBreadcrumbsAcceptsMethod() {
        val file = myFixture.configureByText("a.bsh", "int f() { return 1; }")
        val method = PsiTreeUtil.findChildOfType(file, BshMethodDeclaration::class.java)!!
        val provider = BshBreadcrumbsProvider()
        assertTrue(provider.acceptElement(method))
        assertEquals("f", provider.getElementInfo(method))
    }

    fun testSpellcheckerTokenizesComments() {
        val file = myFixture.configureByText("a.bsh", "// note here\nx = 1;")
        val comment = PsiTreeUtil.collectElements(file) {
            it.node?.elementType === BshTokenTypes.LINE_COMMENT
        }.first()
        val tokenizer = BshSpellcheckingStrategy().getTokenizer(comment)
        assertNotSame(SpellcheckingStrategy.EMPTY_TOKENIZER, tokenizer)
    }

    fun testSurroundWithIf() {
        myFixture.configureByText("a.bsh", "<selection>g();</selection>")
        val selection = myFixture.editor.selectionModel
        val descriptor = BshSurroundDescriptor()
        val elements = descriptor.getElementsToSurround(
            myFixture.file, selection.selectionStart, selection.selectionEnd,
        )
        assertTrue("statements found to surround", elements.isNotEmpty())
        val surrounder = descriptor.surrounders.first { it is BshIfSurrounder }
        WriteCommandAction.runWriteCommandAction(project) {
            surrounder.surroundElements(project, myFixture.editor, elements)
        }
        val text = myFixture.file.text
        assertTrue("wrapped in if", text.contains("if ("))
        assertTrue("kept the statement", text.contains("g();"))
    }

    fun testPostfixTemplatesRegistered() {
        val templates = BshPostfixTemplateProvider().templates
        assertEquals(3, templates.size)
        assertTrue(templates.any { it.key.contains("sout") })
    }
}
