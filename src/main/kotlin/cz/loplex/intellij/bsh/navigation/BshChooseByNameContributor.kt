package cz.loplex.intellij.bsh.navigation

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import cz.loplex.intellij.bsh.BshFileType
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshNamedElement
import cz.loplex.intellij.bsh.reference.BshScopes
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Feeds BeanShell methods, classes and top-level variables into "Go to Symbol"
 * across all BeanShell files of the project.
 */
class BshChooseByNameContributor : ChooseByNameContributor {

    override fun getNames(project: Project, includeNonProjectItems: Boolean): Array<String> =
        symbols(project).mapNotNull { it.name }.distinct().toTypedArray()

    override fun getItemsByName(
        name: String,
        pattern: String,
        project: Project,
        includeNonProjectItems: Boolean,
    ): Array<NavigationItem> =
        symbols(project).filter { it.name == name }.map { it as NavigationItem }.toTypedArray()

    private fun symbols(project: Project): List<BshNamedElement> {
        if (DumbService.isDumb(project)) return emptyList()
        val manager = PsiManager.getInstance(project)
        val scope = GlobalSearchScope.allScope(project)
        val result = ArrayList<BshNamedElement>()
        for (virtualFile in FileTypeIndex.getFiles(BshFileType, scope)) {
            val file = manager.findFile(virtualFile) as? BshFile ?: continue
            PsiTreeUtil.collectElementsOfType(file, BshNamedElement::class.java).forEach { named ->
                when (named.node.elementType) {
                    E.METHOD_DECLARATION, E.CLASS_DECLARATION -> result.add(named)
                    E.VARIABLE_DECLARATOR ->
                        if (BshScopes.scopeOf(named) is BshFile) result.add(named)
                }
            }
        }
        return result
    }
}
