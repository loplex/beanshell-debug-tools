package cz.loplex.intellij.bsh.analysis

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/** Spell-checks BeanShell comments and string literals. */
class BshSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement): Tokenizer<*> {
        val type = element.node?.elementType
        return when {
            type != null && type in BshTokenTypes.COMMENTS.types -> TEXT_TOKENIZER
            type === BshTokenTypes.STRING_LITERAL -> TEXT_TOKENIZER
            else -> EMPTY_TOKENIZER
        }
    }
}
