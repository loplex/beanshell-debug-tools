package cz.loplex.intellij.bsh.reference

import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.BshFileType
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Name resolution for BeanShell.
 *
 * BeanShell is loosely typed and loosely scoped, so resolution is intentionally
 * lenient:
 *  - typed variables and parameters resolve to the declaration in the nearest
 *    enclosing lexical scope,
 *  - untyped variables have no declaration node, so the *first assignment* to a
 *    simple name in the nearest enclosing scope acts as the declaration,
 *  - methods and classes are file-global and, as a fallback, resolved across
 *    the other BeanShell files of the project.
 *
 * Unresolved names are left unresolved (no error), matching the dynamic nature
 * of the language.
 */
object BshResolver {

    fun resolve(reference: PsiElement, name: String): PsiElement? {
        if (name.isEmpty()) return null
        val isCall = reference.parent?.node?.elementType === E.METHOD_INVOCATION

        if (isCall) {
            fileMethod(reference, name)?.let { return it }
        }
        typedVariableInScope(reference, name)?.let { return it }
        untypedVariableInScope(reference, name)?.let { return it }
        fileMethod(reference, name)?.let { return it }
        fileClass(reference, name)?.let { return it }
        // Cross-file fall-back for the file-global concepts.
        projectDeclaration(reference, name, E.METHOD_DECLARATION)?.let { return it }
        projectDeclaration(reference, name, E.CLASS_DECLARATION)?.let { return it }
        return null
    }

    private fun namedInFile(file: PsiElement?, name: String, type: IElementType): List<BshNamedElement> {
        if (file == null) return emptyList()
        return PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java)
            .filter { it.node.elementType === type && it.name == name }
    }

    private fun fileMethod(reference: PsiElement, name: String): PsiElement? =
        namedInFile(reference.containingFile, name, E.METHOD_DECLARATION).firstOrNull()

    private fun fileClass(reference: PsiElement, name: String): PsiElement? =
        namedInFile(reference.containingFile, name, E.CLASS_DECLARATION).firstOrNull()

    private fun typedVariableInScope(reference: PsiElement, name: String): PsiElement? {
        val file = reference.containingFile ?: return null
        val candidates = PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java)
            .filter {
                (it.node.elementType === E.VARIABLE_DECLARATOR ||
                    it.node.elementType === E.FORMAL_PARAMETER) && it.name == name
            }
        return candidates
            .filter { PsiTreeUtil.isAncestor(BshScopes.scopeOf(it), reference, false) }
            .maxByOrNull { BshScopes.scopeDepth(BshScopes.scopeOf(it)) }
    }

    private fun untypedVariableInScope(reference: PsiElement, name: String): PsiElement? {
        val file = reference.containingFile ?: return null
        val writes = PsiTreeUtil.collectElementsOfType(file, BshAmbiguousName::class.java)
            .filter { it.name == name && BshScopes.isSimpleAssignmentTarget(it) }

        val visible = writes.filter { PsiTreeUtil.isAncestor(BshScopes.scopeOf(it), reference, false) }
        if (visible.isEmpty()) return null

        // The declaration is the earliest assignment in the most tightly enclosing scope.
        val deepest = visible.maxOf { BshScopes.scopeDepth(BshScopes.scopeOf(it)) }
        return visible
            .filter { BshScopes.scopeDepth(BshScopes.scopeOf(it)) == deepest }
            .minByOrNull { it.textRange.startOffset }
    }

    private fun projectDeclaration(reference: PsiElement, name: String, type: IElementType): PsiElement? {
        val project = reference.project
        if (DumbService.isDumb(project)) return null
        val current = reference.containingFile?.virtualFile
        val scope = GlobalSearchScope.allScope(project)
        val manager = PsiManager.getInstance(project)
        for (virtualFile in FileTypeIndex.getFiles(BshFileType, scope)) {
            if (virtualFile == current) continue
            val psi = manager.findFile(virtualFile) as? BshFile ?: continue
            namedInFile(psi, name, type).firstOrNull()?.let { return it }
        }
        return null
    }
}
