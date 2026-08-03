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
 * keeps the transformation semantics-preserving.
 *
 * The line number baked into each hook is chosen by the `lineFor` callback. For a standalone
 * `.bsh` file it is the statement's own line, so breakpoints map directly. For an
 * inline script injected into a pom.xml the caller bakes the **host pom.xml line**
 * instead, so every instrumented snippet in the build reports absolute pom lines to
 * one debug session — no per-snippet line map, no ambiguity between snippets.
 */
object BshDebugInstrumenter {

    private const val HOOK = "cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step"

    /** Instruments a standalone script, reporting each statement's own 1-based line. */
    fun instrument(file: BshFile): String {
        val text = file.text
        return instrument(file) { offset -> StringUtil.offsetToLineNumber(text, offset) + 1 }
    }

    /**
     * Instruments [file], baking the line number returned by [lineFor] (given the statement's start
     * offset within [file]) into each hook.
     */
    fun instrument(file: BshFile, lineFor: (offset: Int) -> Int): String {
        val text = file.text
        val offsets = sortedSetOf<Int>()
        collect(file.node, offsets)

        val sb = StringBuilder(text.length + offsets.size * HOOK.length)
        var prev = 0
        for (offset in offsets) {
            sb.append(text, prev, offset)
            sb.append(HOOK).append('(').append(lineFor(offset))
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
