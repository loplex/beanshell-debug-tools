package cz.loplex.intellij.bsh

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Verifies Ctrl+Click into Java through a chain, using a real project Java class
 * so type propagation resolves deterministically.
 */
class BshChainNavigationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "Chain.java",
            """
            public class Chain {
                public Chain next() { return this; }
                public int value;
            }
            """.trimIndent(),
        )
    }

    private fun resolveAtCaret(text: String): PsiElement? {
        myFixture.configureByText("a.bsh", text)
        return myFixture.getReferenceAtCaretPosition()?.resolve()
    }

    fun testFieldOnNewInstance() {
        val target = resolveAtCaret("c = new Chain();\nc.<caret>value;")
        assertTrue(target is PsiField)
        assertEquals("value", (target as PsiNamedElement).name)
    }

    fun testFieldThroughMethodReturn() {
        val target = resolveAtCaret("c = new Chain();\nc.next().<caret>value;")
        assertTrue("field reached through next()", target is PsiField)
    }

    fun testDeepChain() {
        val target = resolveAtCaret("c = new Chain();\nc.next().next().<caret>value;")
        assertTrue("field reached through next().next()", target is PsiField)
    }

    fun testMethodInChain() {
        val target = resolveAtCaret("c = new Chain();\nc.next().<caret>next();")
        assertTrue("resolves the chained method", target is PsiMethod)
    }
}
