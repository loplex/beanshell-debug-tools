package cz.loplex.intellij.bsh.reference

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.psi.BshTokenTypes

/** Validates identifiers entered in the rename dialog. */
class BshNamesValidator : NamesValidator {

    override fun isKeyword(name: String, project: Project?): Boolean =
        BshTokenTypes.KEYWORDS.contains(name)

    override fun isIdentifier(name: String, project: Project?): Boolean {
        if (name.isEmpty() || BshTokenTypes.KEYWORDS.contains(name)) return false
        val lexer = BshLexer()
        lexer.start(name, 0, name.length, 0)
        return lexer.tokenType === BshTokenTypes.IDENTIFIER && lexer.tokenEnd == name.length
    }
}
