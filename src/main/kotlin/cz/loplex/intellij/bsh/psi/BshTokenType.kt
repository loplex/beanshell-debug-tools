package cz.loplex.intellij.bsh.psi

import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.BshLanguage
import org.jetbrains.annotations.NonNls

/** Lexer token element type for the BeanShell language. */
class BshTokenType(@NonNls debugName: String) : IElementType(debugName, BshLanguage) {
    override fun toString(): String = "BshTokenType." + super.toString()
}
