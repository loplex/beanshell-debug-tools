package cz.loplex.intellij.bsh.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.psi.BshTokenTypes

class BshBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int = openingBraceOffset
}

private val PAIRS = arrayOf(
    BracePair(BshTokenTypes.LBRACE, BshTokenTypes.RBRACE, true),
    BracePair(BshTokenTypes.LPAREN, BshTokenTypes.RPAREN, false),
    BracePair(BshTokenTypes.LBRACKET, BshTokenTypes.RBRACKET, false),
)
