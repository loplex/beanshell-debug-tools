package cz.loplex.intellij.bsh.psi

import com.intellij.icons.AllIcons
import com.intellij.lang.ASTNode
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiReference
import cz.loplex.intellij.bsh.reference.BshChainResolver
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

        val references = ArrayList<PsiReference>()

        // `receiver.member` where `receiver` is typed as a BeanShell class in the project:
        // resolve the member to that class's own method/field (no Java involved).
        if (identifiers.size >= 2) {
            val receiverType = BshTypeInference.variableType(this, first.text)
            val bshClass = if (receiverType != null) BshResolver.findClassNamed(this, receiverType) else null
            if (bshClass != null) {
                val target = BshResolver.resolve(this, first.text)
                if (target != null && target !== this) references.add(BshReference(this, rangeOf(first)))
                val member = identifiers[1]
                references.add(BshMemberReference(this, rangeOf(member)) {
                    BshResolver.classMember(bshClass, member.text)
                })
                return references.toTypedArray()
            }
        }

        val (startClass, memberStart, isVariable) =
            if (BshJavaSupport.isAvailable()) BshChainResolver.startInfo(this) else Triple(null, -1, false)

        when {
            // A Java class name spanning segments [0, memberStart): one class reference.
            startClass != null && !isVariable -> {
                val last = identifiers[memberStart - 1]
                val start = first.startOffset - node.startOffset
                val range = TextRange(start, last.startOffset - node.startOffset + last.textLength)
                references.add(BshJavaClassReference(this, range, node.text.substring(range.startOffset, range.endOffset)))
            }
            // A variable receiver, or no Java type: the first segment is a BeanShell symbol.
            else -> {
                val target = BshResolver.resolve(this, first.text)
                when {
                    target === this -> Unit // this name is itself the declaration
                    target != null -> references.add(BshReference(this, rangeOf(first)))
                    BshJavaSupport.isAvailable() && BshJavaResolver.resolveClass(this, first.text) != null ->
                        references.add(BshJavaClassReference(this, rangeOf(first), first.text))
                }
            }
        }

        // Remaining segments resolve to Java members via type propagation.
        if (startClass != null) {
            for (i in memberStart until identifiers.size) {
                references.add(BshMemberReference(this, rangeOf(identifiers[i])) {
                    BshChainResolver.resolveNameSegment(this, i)
                })
            }
        }
        return references.toTypedArray()
    }

    private fun rangeOf(identifier: ASTNode): TextRange {
        val start = identifier.startOffset - node.startOffset
        return TextRange(start, start + identifier.textLength)
    }

    override fun getIcon(flags: Int): Icon = AllIcons.Nodes.Variable
}

/**
 * A `.member` access after a prefix (e.g. `foo().bar`). Carries a reference into
 * Java, resolved through static type propagation over the enclosing chain.
 */
class BshPrimarySuffix(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? {
        if (!BshJavaSupport.isAvailable()) return null
        val identifier = node.findChildByType(BshTokenTypes.IDENTIFIER) ?: return null
        val start = identifier.startOffset - node.startOffset
        return BshMemberReference(this, TextRange(start, start + identifier.textLength)) {
            BshChainResolver.resolveSuffix(this)
        }
    }
}
