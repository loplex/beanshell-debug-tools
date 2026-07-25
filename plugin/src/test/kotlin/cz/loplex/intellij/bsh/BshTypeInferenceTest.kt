package cz.loplex.intellij.bsh

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.reference.BshTypeInference

class BshTypeInferenceTest : BasePlatformTestCase() {

    /** Returns the dotted `receiver.member` usage and its first (receiver) segment. */
    private fun receiver(text: String): Pair<PsiElement, String> {
        val file = myFixture.configureByText("a.bsh", text)
        val dotted = PsiTreeUtil.collectElementsOfType(file, BshAmbiguousName::class.java)
            .first { it.text.contains('.') }
        return dotted to dotted.text.substringBefore('.')
    }

    fun testTypedClassVariable() {
        val (context, name) = receiver("ArrayList list;\nlist.add(x);")
        assertEquals("ArrayList", BshTypeInference.variableType(context, name))
    }

    fun testTypedInterfaceVariable() {
        val (context, name) = receiver("List l;\nl.size();")
        assertEquals("List", BshTypeInference.variableType(context, name))
    }

    fun testUntypedVariableFromNew() {
        val (context, name) = receiver("data = new ArrayList();\ndata.add(x);")
        assertEquals("ArrayList", BshTypeInference.variableType(context, name))
    }

    fun testTypedParameter() {
        val (context, name) = receiver("f(StringBuilder sb) { sb.append(1); }")
        assertEquals("StringBuilder", BshTypeInference.variableType(context, name))
    }

    fun testUnknownReceiverReturnsNull() {
        val (context, name) = receiver("x.y();")
        assertNull(BshTypeInference.variableType(context, name))
    }
}
