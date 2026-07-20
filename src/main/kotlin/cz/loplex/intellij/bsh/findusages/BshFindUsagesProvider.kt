package cz.loplex.intellij.bsh.findusages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.TokenSet
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.psi.BshElementTypes as E
import cz.loplex.intellij.bsh.psi.BshTokenTypes

class BshFindUsagesProvider : FindUsagesProvider {

    override fun getWordsScanner(): WordsScanner = DefaultWordsScanner(
        BshLexer(),
        TokenSet.create(BshTokenTypes.IDENTIFIER),
        BshTokenTypes.COMMENTS,
        BshTokenTypes.STRING_LITERALS,
    )

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is PsiNamedElement

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element.node?.elementType) {
        E.METHOD_DECLARATION -> "method"
        E.CLASS_DECLARATION -> "class"
        E.FORMAL_PARAMETER -> "parameter"
        E.VARIABLE_DECLARATOR -> "variable"
        else -> ""
    }

    override fun getDescriptiveName(element: PsiElement): String =
        (element as? PsiNamedElement)?.name ?: ""

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String =
        (element as? PsiNamedElement)?.name ?: ""
}
