package cz.loplex.intellij.bsh.psi

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import cz.loplex.intellij.bsh.reference.BshJavaClassReference
import cz.loplex.intellij.bsh.reference.BshJavaResolver
import cz.loplex.intellij.bsh.reference.BshJavaSupport
import cz.loplex.intellij.bsh.reference.BshMemberReference
import cz.loplex.intellij.bsh.reference.BshReference
import cz.loplex.intellij.bsh.reference.BshResolver
import cz.loplex.intellij.bsh.reference.BshTypeInference
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

    override fun getReference(): PsiReference? = references.firstOrNull()

    /**
     * A dotted name `a.b.c` yields several references:
     *  - the first segment resolves to a BeanShell declaration or a Java class,
     *  - a second segment resolves to a Java member on the first segment's type
     *    (e.g. `list.add` → `ArrayList.add`), enabling Ctrl+Click into Java.
     */
    override fun getReferences(): Array<PsiReference> {
        val identifiers = node.getChildren(null).filter { it.elementType === BshTokenTypes.IDENTIFIER }
        if (identifiers.isEmpty()) return PsiReference.EMPTY_ARRAY
        val first = identifiers[0]

        // Whole dotted name is a Java class (FQN)? Then it's one class reference.
        if (identifiers.size > 1 && BshJavaSupport.isAvailable() &&
            BshResolver.resolve(this, first.text) == null &&
            BshJavaResolver.resolveClass(this, node.text) != null
        ) {
            return arrayOf(BshJavaClassReference(this, rangeOf(node.startOffset, node.textLength), node.text))
        }

        val references = ArrayList<PsiReference>()

        // First segment: BeanShell declaration, or a Java class.
        val target = BshResolver.resolve(this, first.text)
        when {
            target === this -> Unit // this name is itself the declaration
            target != null -> references.add(BshReference(this, rangeOf(first)))
            BshJavaSupport.isAvailable() && BshJavaResolver.resolveClass(this, first.text) != null ->
                references.add(BshJavaClassReference(this, rangeOf(first), first.text))
        }

        // Second segment: a Java member on the first segment's (variable) type.
        if (identifiers.size >= 2 && BshJavaSupport.isAvailable()) {
            val type = BshTypeInference.variableType(this, first.text)
            if (type != null) {
                references.add(BshMemberReference(this, rangeOf(identifiers[1]), type, identifiers[1].text))
            }
        }
        return references.toTypedArray()
    }

    private fun rangeOf(identifier: ASTNode): TextRange {
        val start = identifier.startOffset - node.startOffset
        return TextRange(start, start + identifier.textLength)
    }

    private fun rangeOf(start: Int, length: Int): TextRange {
        val offset = start - node.startOffset
        return TextRange(offset, offset + length)
    }

    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Variable
}
