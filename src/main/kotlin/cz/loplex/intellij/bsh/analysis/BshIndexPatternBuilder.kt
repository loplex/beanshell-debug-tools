package cz.loplex.intellij.bsh.analysis

import com.intellij.lexer.Lexer
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/** Enables TODO/FIXME detection inside BeanShell comments. */
class BshIndexPatternBuilder : IndexPatternBuilder {
    override fun getIndexingLexer(file: PsiFile): Lexer? =
        if (file is BshFile) BshLexer() else null

    override fun getCommentTokenSet(file: PsiFile): TokenSet? =
        if (file is BshFile) BshTokenTypes.COMMENTS else null

    override fun getCommentStartDelta(tokenType: IElementType): Int = 0

    override fun getCommentEndDelta(tokenType: IElementType): Int = 0
}
