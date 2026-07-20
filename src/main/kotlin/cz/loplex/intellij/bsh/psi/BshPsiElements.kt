package cz.loplex.intellij.bsh.psi

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import cz.loplex.intellij.bsh.reference.BshReference
import cz.loplex.intellij.bsh.reference.BshResolver
import javax.swing.Icon

class BshClassDeclaration(node: ASTNode) : BshNamedElement(node) {
    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Class
}

class BshMethodDeclaration(node: ASTNode) : BshNamedElement(node) {
    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Method
}

class BshVariableDeclarator(node: ASTNode) : BshNamedElement(node) {
    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Variable
}

class BshFormalParameter(node: ASTNode) : BshNamedElement(node) {
    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Parameter
}

/**
 * A (possibly dotted) name used as an expression, type or invocation target.
 *
 * It is treated as a named element so that untyped BeanShell variables (which
 * have no declaration node — the first assignment acts as the declaration) can
 * be renamed and have their usages found. Its first identifier segment carries
 * a [BshReference] to the declaration, unless this name *is* the declaration.
 */
class BshAmbiguousName(node: ASTNode) : BshNamedElement(node) {

    override fun getReference(): PsiReference? {
        val identifier = node.findChildByType(BshTokenTypes.IDENTIFIER) ?: return null
        val target = BshResolver.resolve(this, identifier.text)
        // No reference when unresolved, or when this name is itself the declaration.
        if (target == null || target === this) return null
        val start = identifier.startOffset - node.startOffset
        return BshReference(this, TextRange(start, start + identifier.textLength))
    }

    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Variable
}
