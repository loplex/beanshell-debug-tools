package cz.loplex.intellij.bsh

import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator

class BshReferenceTest : BasePlatformTestCase() {

    fun testResolveParameter() {
        myFixture.configureByText("a.bsh", "int add(int a, int b) { return a + <caret>b; }")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to a parameter", target is BshFormalParameter)
        assertEquals("b", (target as PsiNamedElement).name)
    }

    fun testResolveMethodCall() {
        myFixture.configureByText("a.bsh", "int sq(int n) { return n * n; }\nprint(<caret>sq(3));")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to a method", target is BshMethodDeclaration)
        assertEquals("sq", (target as PsiNamedElement).name)
    }

    fun testResolveTypedVariable() {
        myFixture.configureByText("a.bsh", "int total = 0;\ntotal = <caret>total + 1;")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to a variable declarator", target is BshVariableDeclarator)
    }

    fun testRenameParameterUpdatesUsages() {
        myFixture.configureByText("a.bsh", "int add(int <caret>a, int b) { return a + b; }")
        myFixture.renameElementAtCaret("x")
        myFixture.checkResult("int add(int x, int b) { return x + b; }")
    }

    fun testRenameMethodFromCallSite() {
        myFixture.configureByText("a.bsh", "greet() { print(1); }\n<caret>greet();")
        myFixture.renameElementAtCaret("hello")
        myFixture.checkResult("hello() { print(1); }\nhello();")
    }

    fun testResolveUntypedVariable() {
        myFixture.configureByText("a.bsh", "count = 0;\ncount = count + 1;\nprint(<caret>count);")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("untyped variable resolves to its first assignment", target is BshAmbiguousName)
    }

    fun testRenameUntypedVariable() {
        myFixture.configureByText("a.bsh", "count = 0;\nprint(<caret>count);")
        myFixture.renameElementAtCaret("total")
        myFixture.checkResult("total = 0;\nprint(total);")
    }

    fun testFindUsagesOfParameter() {
        myFixture.configureByText("a.bsh", "int add(int <caret>a, int b) { return a + a + b; }")
        val usages = myFixture.findUsages(myFixture.elementAtCaret)
        // two read usages of 'a' in the body
        assertEquals(2, usages.size)
    }
}
