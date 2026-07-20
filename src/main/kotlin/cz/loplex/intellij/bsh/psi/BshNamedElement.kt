package cz.loplex.intellij.bsh.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import javax.swing.Icon

/**
 * Base class for BeanShell declarations that introduce a name (classes,
 * methods, variables, parameters). Implements the platform contracts that
 * enable rename, find-usages and Go To Symbol.
 */
abstract class BshNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {

    override fun getNameIdentifier(): PsiElement? =
        node.findChildByType(BshTokenTypes.IDENTIFIER)?.psi

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val identifier = nameIdentifier ?: return this
        identifier.replace(BshPsiFactory.createIdentifier(project, name))
        return this
    }

    override fun getTextOffset(): Int = nameIdentifier?.textOffset ?: super.getTextOffset()

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String? = name
        override fun getLocationString(): String? = containingFile?.name
        override fun getIcon(unused: Boolean): Icon? = this@BshNamedElement.getIcon(0)
    }
}
