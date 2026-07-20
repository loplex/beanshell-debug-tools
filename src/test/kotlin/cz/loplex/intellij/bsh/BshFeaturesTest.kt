package cz.loplex.intellij.bsh

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.completion.BshInlayParameterHintsProvider
import cz.loplex.intellij.bsh.inspection.BshUnreachableCodeInspection
import cz.loplex.intellij.bsh.inspection.BshUnresolvedMethodInspection
import cz.loplex.intellij.bsh.inspection.BshUnusedVariableInspection
import cz.loplex.intellij.bsh.navigation.BshChooseByNameContributor
import cz.loplex.intellij.bsh.psi.BshElementTypes
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration

class BshFeaturesTest : BasePlatformTestCase() {

    fun testCompletionOffersKeywordsAndDeclarations() {
        myFixture.configureByText("a.bsh", "int helper(int val) { return val; }\ncount = 5;\n<caret>")
        myFixture.completeBasic()
        val items = myFixture.lookupElementStrings
        assertNotNull(items)
        assertContainsElements(items!!, "helper", "count", "if", "while", "return")
        // A parameter is not visible outside its method.
        assertDoesntContain(items, "val")
    }

    fun testUnusedParameterIsReported() {
        myFixture.configureByText("a.bsh", "int f(int used, int notUsed) { return used; }")
        myFixture.enableInspections(BshUnusedVariableInspection())
        val highlights = myFixture.doHighlighting()
        assertTrue(
            "expected an 'is never used' warning for notUsed",
            highlights.any { it.description?.contains("notUsed") == true && it.description!!.contains("never used") },
        )
    }

    fun testUnreachableCodeIsReported() {
        myFixture.configureByText("a.bsh", "int f() { return 1; print(2); }")
        myFixture.enableInspections(BshUnreachableCodeInspection())
        val highlights = myFixture.doHighlighting()
        assertTrue(
            "expected an 'Unreachable code' warning",
            highlights.any { it.description == "Unreachable code" },
        )
    }

    fun testCrossFileMethodResolution() {
        myFixture.addFileToProject("lib.bsh", "int shared() { return 42; }")
        myFixture.configureByText("main.bsh", "print(<caret>shared());")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to a method in another file", target is BshMethodDeclaration)
        assertEquals("lib.bsh", target!!.containingFile.name)
    }

    fun testRemoveUnusedVariableQuickFix() {
        myFixture.configureByText("a.bsh", "int f() { int <caret>tmp = 5; return 1; }")
        myFixture.enableInspections(BshUnusedVariableInspection())
        myFixture.launchAction(myFixture.findSingleIntention("Remove declaration"))
        assertFalse("declaration removed", myFixture.file.text.contains("tmp"))
    }

    fun testRemoveUnreachableCodeQuickFix() {
        myFixture.configureByText("a.bsh", "int f() { return 1; <caret>dead(); }")
        myFixture.enableInspections(BshUnreachableCodeInspection())
        myFixture.launchAction(myFixture.findSingleIntention("Remove unreachable code"))
        assertFalse("unreachable statement removed", myFixture.file.text.contains("dead"))
    }

    fun testTodoInComment() {
        myFixture.configureByText("a.bsh", "// TODO: fix this\nx = 1;")
        val todos = com.intellij.psi.search.PsiTodoSearchHelper.getInstance(project)
            .findTodoItems(myFixture.file)
        assertEquals(1, todos.size)
    }

    fun testUnresolvedMethodIsReportedWhenEnabled() {
        myFixture.enableInspections(BshUnresolvedMethodInspection())
        myFixture.configureByText("a.bsh", "doesNotExist();")
        val highlights = myFixture.doHighlighting()
        assertTrue(highlights.any { it.description?.contains("Cannot resolve method") == true })
    }

    fun testResolvedMethodIsNotReported() {
        myFixture.enableInspections(BshUnresolvedMethodInspection())
        myFixture.configureByText("a.bsh", "int ok() { return 1; }\nok();")
        val highlights = myFixture.doHighlighting()
        assertFalse(highlights.any { it.description?.contains("Cannot resolve method") == true })
    }

    fun testIntroduceVariableIntention() {
        myFixture.configureByText("a.bsh", "comp<caret>ute();")
        myFixture.launchAction(myFixture.findSingleIntention("Introduce variable"))
        myFixture.checkResult("x = compute();")
    }

    fun testParameterHints() {
        val file = myFixture.configureByText("a.bsh", "int add(int a, int b) { return a + b; }\nadd(1, 2);")
        val invocation = PsiTreeUtil.collectElements(file) {
            it.node.elementType === BshElementTypes.METHOD_INVOCATION
        }.first()
        val hints = BshInlayParameterHintsProvider().getParameterHints(invocation)
        assertEquals(listOf("a", "b"), hints.map { it.text })
    }

    fun testGotoSymbolListsDeclarations() {
        myFixture.addFileToProject("lib.bsh", "int alpha() { return 0; }\nclass Beta { }")
        myFixture.configureByText("main.bsh", "")
        val contributor = BshChooseByNameContributor()
        assertContainsElements(contributor.getNames(project, false).toList(), "alpha", "Beta")
        assertTrue(contributor.getItemsByName("alpha", "alpha", project, false).isNotEmpty())
    }
}
