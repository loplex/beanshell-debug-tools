package cz.loplex.intellij.bsh.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.lexer.Lexer
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.psi.BshTokenTypes

class BshSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = BshLexer()

    override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> =
        pack(ATTRIBUTES[tokenType])

    companion object {
        private val ATTRIBUTES: Map<IElementType, TextAttributesKey> = buildMap {
            put(BshTokenTypes.KEYWORD, BshColors.KEYWORD)
            put(BshTokenTypes.IDENTIFIER, BshColors.IDENTIFIER)
            put(BshTokenTypes.INTEGER_LITERAL, BshColors.NUMBER)
            put(BshTokenTypes.FLOAT_LITERAL, BshColors.NUMBER)
            put(BshTokenTypes.STRING_LITERAL, BshColors.STRING)
            put(BshTokenTypes.CHARACTER_LITERAL, BshColors.CHARACTER)
            put(BshTokenTypes.LINE_COMMENT, BshColors.LINE_COMMENT)
            put(BshTokenTypes.BLOCK_COMMENT, BshColors.BLOCK_COMMENT)
            put(BshTokenTypes.DOC_COMMENT, BshColors.DOC_COMMENT)
            put(BshTokenTypes.OPERATOR, BshColors.OPERATOR)
            put(BshTokenTypes.LPAREN, BshColors.PARENTHESES)
            put(BshTokenTypes.RPAREN, BshColors.PARENTHESES)
            put(BshTokenTypes.LBRACE, BshColors.BRACES)
            put(BshTokenTypes.RBRACE, BshColors.BRACES)
            put(BshTokenTypes.LBRACKET, BshColors.BRACKETS)
            put(BshTokenTypes.RBRACKET, BshColors.BRACKETS)
            put(BshTokenTypes.SEMICOLON, BshColors.SEMICOLON)
            put(BshTokenTypes.COMMA, BshColors.COMMA)
            put(BshTokenTypes.DOT, BshColors.DOT)
            put(TokenType.BAD_CHARACTER, BshColors.BAD_CHARACTER)
        }
    }
}
