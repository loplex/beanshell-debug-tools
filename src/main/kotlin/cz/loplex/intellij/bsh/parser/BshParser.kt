package cz.loplex.intellij.bsh.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType

/**
 * Minimal parser producing a flat token tree under the file root.
 *
 * Highlighting, brace matching, commenting, folding and inspection all operate
 * on the lexer token stream, so a full syntactic AST is not required. Keeping
 * the tree flat avoids the maintenance cost of translating the large JavaCC
 * grammar while still giving every token a stable PSI leaf.
 */
class BshParser : PsiParser {
    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            builder.advanceLexer()
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }
}
