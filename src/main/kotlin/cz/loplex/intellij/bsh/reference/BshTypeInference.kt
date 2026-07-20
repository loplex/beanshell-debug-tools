package cz.loplex.intellij.bsh.reference

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Best-effort static type inference for a BeanShell variable, used to navigate
 * into Java methods/fields called on it. Only cases where the type is evident
 * from the code are handled (a reference has no access to runtime information):
 *  - a typed variable declaration (`ArrayList list;`),
 *  - a typed parameter,
 *  - an untyped variable whose first assignment is `= new Type(...)`.
 */
object BshTypeInference {

    /** Java type name of the variable [name] as seen from [context], if evident. */
    fun variableType(context: PsiElement, name: String): String? {
        val declaration = BshResolver.resolve(context, name) ?: return null
        return typeNameOf(declaration)
    }

    private fun typeNameOf(declaration: PsiElement): String? = when (declaration.node.elementType) {
        E.FORMAL_PARAMETER -> typeText(declaration)
        E.VARIABLE_DECLARATOR -> typeText(declaration.parent)
        E.AMBIGUOUS_NAME -> allocationType(declaration) // untyped variable: first `= new Type()`
        else -> null
    }

    private fun typeText(owner: PsiElement?): String? {
        val type = owner?.node?.findChildByType(E.TYPE) ?: return null
        return type.text.trim().substringBefore('[').substringBefore('<').trim().ifEmpty { null }
    }

    /** For `name = new Type(...)`, returns `Type`. */
    private fun allocationType(untypedDeclaration: PsiElement): String? {
        val assignment = PsiTreeUtil.findFirstParent(untypedDeclaration) {
            it.node?.elementType === E.ASSIGNMENT
        } ?: return null
        val alloc = PsiTreeUtil.collectElements(assignment) { it.node.elementType === E.ALLOCATION_EXPRESSION }
            .firstOrNull() ?: return null
        val typeName = alloc.node.findChildByType(E.AMBIGUOUS_NAME) ?: return null
        return typeName.text.trim().substringBefore('<').ifEmpty { null }
    }
}
