package cz.loplex.intellij.bsh.psi

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import cz.loplex.intellij.bsh.reference.BshJavaClassReference
import cz.loplex.intellij.bsh.reference.BshJavaResolver
import cz.loplex.intellij.bsh.reference.BshJavaSupport
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

        // 1. BeanShell-local resolution (methods, classes, typed/untyped variables).
        val target = BshResolver.resolve(this, identifier.text)
        if (target === this) return null // this name is itself the declaration
        if (target != null) {
            val start = identifier.startOffset - node.startOffset
            return BshReference(this, TextRange(start, start + identifier.textLength))
        }

        // 2. Fall back to Java class navigation (Ctrl+Click into Java code).
        if (BshJavaSupport.isAvailable()) {
            val fullText = node.text
            if (BshJavaResolver.resolveClass(this, fullText) != null) {
                return BshJavaClassReference(this, TextRange(0, node.textLength), fullText)
            }
            val firstSegment = identifier.text
            if (BshJavaResolver.resolveClass(this, firstSegment) != null) {
                val start = identifier.startOffset - node.startOffset
                return BshJavaClassReference(this, TextRange(start, start + identifier.textLength), firstSegment)
            }
        }
        return null
    }

    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Variable
}
