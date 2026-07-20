package cz.loplex.intellij.bsh.structure

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import cz.loplex.intellij.bsh.BshIcons
import cz.loplex.intellij.bsh.psi.BshElementTypes as E
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import javax.swing.Icon

/**
 * A node in the BeanShell Structure View. Exposes classes, methods and
 * top-level / field variables discovered in the AST built by the parser.
 */
class BshStructureViewElement(private val element: PsiElement) :
    StructureViewTreeElement, SortableTreeElement {

    override fun getValue(): Any = element

    override fun navigate(requestFocus: Boolean) {
        (element as? Navigatable)?.navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = (element as? Navigatable)?.canNavigate() ?: false

    override fun canNavigateToSource(): Boolean =
        (element as? Navigatable)?.canNavigateToSource() ?: false

    override fun getAlphaSortKey(): String = presentableText()

    override fun getPresentation(): ItemPresentation =
        PresentationData(presentableText(), null, icon(), null)

    override fun getChildren(): Array<TreeElement> =
        childDeclarations(element).map { BshStructureViewElement(it) }.toTypedArray()

    private fun presentableText(): String {
        if (element is PsiFile) return element.name
        return when (element.node.elementType) {
            E.CLASS_DECLARATION, E.VARIABLE_DECLARATOR -> nameOf(element.node)
            E.METHOD_DECLARATION -> nameOf(element.node) + "()"
            else -> element.text
        }
    }

    private fun icon(): Icon? = when {
        element is PsiFile -> BshIcons.FILE
        element.node.elementType === E.CLASS_DECLARATION -> AllIcons.Nodes.Class
        element.node.elementType === E.METHOD_DECLARATION -> AllIcons.Nodes.Method
        element.node.elementType === E.VARIABLE_DECLARATOR -> AllIcons.Nodes.Field
        else -> null
    }

    private fun nameOf(node: ASTNode): String =
        node.findChildByType(BshTokenTypes.IDENTIFIER)?.text ?: "<anonymous>"

    private fun childDeclarations(from: PsiElement): List<PsiElement> {
        val out = ArrayList<PsiElement>()

        fun visit(node: ASTNode) {
            var child = node.firstChildNode
            while (child != null) {
                when (child.elementType) {
                    E.CLASS_DECLARATION, E.METHOD_DECLARATION -> out.add(child.psi)
                    E.TYPED_VARIABLE_DECLARATION -> {
                        var d = child.firstChildNode
                        while (d != null) {
                            if (d.elementType === E.VARIABLE_DECLARATOR) out.add(d.psi)
                            d = d.treeNext
                        }
                    }
                    else -> visit(child) // descend into blocks / statements
                }
                child = child.treeNext
            }
        }

        visit(from.node)
        return out
    }
}
