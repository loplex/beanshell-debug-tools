package cz.loplex.intellij.bsh.debug

import com.intellij.lang.ASTNode
import com.intellij.openapi.util.text.StringUtil
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshElementTypes as E

/**
 * Rewrites a BeanShell script for debugging by inserting a hidden call to the
 * debug agent in front of every executable statement:
 *
 *     cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(<line>, this.namespace);
 *
 * Hooks are placed **only** in front of the direct children of the file root and
 * of `{ ... }` blocks — i.e. at genuine statement boundaries — so instrumentation
 * never lands inside an expression or splits a brace-less control-flow body. Each
 * hook is simply one extra statement executed just before the original one, which
 * keeps the transformation semantics-preserving. Reported line numbers are the
 * originals, so breakpoints keep mapping correctly.
 */
object BshDebugInstrumenter {

    private const val HOOK = "cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step"

    fun instrument(file: BshFile): String {
        val text = file.text
        val offsets = sortedSetOf<Int>()
        collect(file.node, offsets)

        val sb = StringBuilder(text.length + offsets.size * HOOK.length)
        var prev = 0
        for (offset in offsets) {
            sb.append(text, prev, offset)
            sb.append(HOOK).append('(').append(StringUtil.offsetToLineNumber(text, offset) + 1)
                .append(", this.namespace); ")
            prev = offset
        }
        sb.append(text, prev, text.length)
        return sb.toString()
    }

    private fun collect(node: ASTNode, out: MutableSet<Int>) {
        if (node.treeParent == null || node.elementType === E.BLOCK) {
            var child = node.firstChildNode
            while (child != null) {
                if (child.elementType in INSERTABLE) out.add(child.startOffset)
                child = child.treeNext
            }
        }
        var child = node.firstChildNode
        while (child != null) {
            collect(child, out)
            child = child.treeNext
        }
    }

    private val INSERTABLE = setOf(
        // Statements
        E.TYPED_VARIABLE_DECLARATION, E.IF_STATEMENT, E.WHILE_STATEMENT, E.FOR_STATEMENT,
        E.ENHANCED_FOR_STATEMENT, E.SWITCH_STATEMENT, E.RETURN_STATEMENT, E.THROW_STATEMENT,
        E.TRY_STATEMENT, E.SYNCHRONIZED_STATEMENT, E.LABELED_STATEMENT, E.BLOCK,
        // Expression statements
        E.ASSIGNMENT, E.PRIMARY_EXPRESSION, E.BINARY_EXPRESSION, E.UNARY_EXPRESSION,
        E.TERNARY_EXPRESSION, E.CAST_EXPRESSION, E.METHOD_INVOCATION, E.ALLOCATION_EXPRESSION,
        E.LITERAL, E.AMBIGUOUS_NAME,
    )
}
