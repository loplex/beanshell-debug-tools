package cz.loplex.intellij.bsh

import com.intellij.codeInsight.highlighting.ReadWriteAccessDetector
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator
import cz.loplex.intellij.bsh.reference.BshJavaSupport
import cz.loplex.intellij.bsh.reference.BshReadWriteAccessDetector
import cz.loplex.intellij.bsh.reference.BshScopes

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

    fun testJavaSupportIsAvailableInIde() {
        // The Java plugin is bundled in IntelliJ IDEA, so class navigation should be active.
        assertTrue(BshJavaSupport.isAvailable())
    }

    fun testLocalResolutionTakesPrecedenceOverJava() {
        // A local method named like a class must still resolve locally, not to Java.
        myFixture.configureByText("a.bsh", "String() { return 1; }\n<caret>String();")
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to the local method", target is BshMethodDeclaration)
    }

    fun testBshClassMethodNavigation() {
        myFixture.configureByText(
            "a.bsh",
            "class Greeter { String greet() { return \"hi\"; } }\nGreeter g = new Greeter();\ng.<caret>greet();",
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertTrue("resolves to the BeanShell class method", target is BshMethodDeclaration)
        assertEquals("greet", (target as PsiNamedElement).name)
    }

    fun testReadWriteAccessDistinguished() {
        val file = myFixture.configureByText("a.bsh", "count = 0;\nprint(count);")
        val names = PsiTreeUtil.collectElementsOfType(file, BshAmbiguousName::class.java).toList()
        val detector = BshReadWriteAccessDetector()
        val write = names.first { BshScopes.isSimpleAssignmentTarget(it) }
        val read = names.first { !BshScopes.isSimpleAssignmentTarget(it) && it.name == "count" }
        assertEquals(ReadWriteAccessDetector.Access.Write, detector.getExpressionAccess(write))
        assertEquals(ReadWriteAccessDetector.Access.Read, detector.getExpressionAccess(read))
    }

    fun testFindUsagesOfParameter() {
        myFixture.configureByText("a.bsh", "int add(int <caret>a, int b) { return a + a + b; }")
        val usages = myFixture.findUsages(myFixture.elementAtCaret)
        // two read usages of 'a' in the body
        assertEquals(2, usages.size)
    }
}
