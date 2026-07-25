package cz.loplex.intellij.bsh.reference

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Resolves Java class names referenced from a BeanShell script to their Java PSI,
 * enabling Go to Declaration (Ctrl+Click) into Java code.
 *
 * Handles fully-qualified names, `java.lang.*`, and names brought in by the
 * script's `import` statements (both single-type and on-demand `.*`). Only class
 * names are resolved; instance method/field resolution would require type
 * inference, which BeanShell's dynamic typing does not provide.
 *
 * Must only be touched when [BshJavaSupport.isAvailable] is true.
 */
object BshJavaResolver {

    private data class Import(val path: String, val onDemand: Boolean)

    fun resolveClass(context: PsiElement, name: String): PsiElement? {
        if (name.isEmpty()) return null
        val facade = JavaPsiFacade.getInstance(context.project)
        val scope = GlobalSearchScope.allScope(context.project)

        facade.findClass(name, scope)?.let { return it }
        if (name.contains('.')) return null // an explicit FQN that was not found

        facade.findClass("java.lang.$name", scope)?.let { return it }
        for (imp in imports(context)) {
            val candidate = when {
                imp.onDemand -> "${imp.path}.$name"
                imp.path.substringAfterLast('.') == name -> imp.path
                else -> continue
            }
            facade.findClass(candidate, scope)?.let { return it }
        }
        return null
    }

    /** Resolves a method or field named [memberName] on the Java class [typeName]. */
    fun resolveMember(context: PsiElement, typeName: String, memberName: String): PsiElement? {
        val psiClass = resolveClass(context, typeName) as? PsiClass ?: return null
        return member(psiClass, memberName)
    }

    fun resolveClassPsi(context: PsiElement, name: String): PsiClass? =
        resolveClass(context, name) as? PsiClass

    /** The method or field named [memberName] on [psiClass] (methods win over fields). */
    fun member(psiClass: PsiClass, memberName: String): PsiElement? {
        psiClass.findMethodsByName(memberName, true).firstOrNull()?.let { return it }
        return psiClass.findFieldByName(memberName, true)
    }

    /** The class produced by a member access — a method's return type or a field's type. */
    fun memberType(psiClass: PsiClass, memberName: String): PsiClass? {
        psiClass.findMethodsByName(memberName, true).firstOrNull()?.let {
            return (it.returnType as? PsiClassType)?.resolve()
        }
        psiClass.findFieldByName(memberName, true)?.let {
            return (it.type as? PsiClassType)?.resolve()
        }
        return null
    }

    private fun imports(context: PsiElement): List<Import> {
        val file = context.containingFile ?: return emptyList()
        val result = ArrayList<Import>()
        var child = file.node.firstChildNode
        while (child != null) {
            if (child.elementType === E.IMPORT_DECLARATION) {
                val nameNode = child.psi.let { PsiTreeUtil.findChildOfType(it, BshAmbiguousName::class.java) }
                val path = nameNode?.text
                if (path != null) {
                    val onDemand = child.text.contains(".*") || child.text.contains(". *")
                    result.add(Import(path, onDemand))
                }
            }
            child = child.treeNext
        }
        return result
    }
}
