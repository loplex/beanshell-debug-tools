package cz.loplex.intellij.bsh.highlight

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey

/**
 * Text attribute keys used by the BeanShell highlighter, derived from the
 * platform defaults so they follow the active colour scheme.
 */
object BshColors {
    val KEYWORD: TextAttributesKey =
        key("BSH_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    val IDENTIFIER: TextAttributesKey =
        key("BSH_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    val NUMBER: TextAttributesKey =
        key("BSH_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    val STRING: TextAttributesKey =
        key("BSH_STRING", DefaultLanguageHighlighterColors.STRING)
    val CHARACTER: TextAttributesKey =
        key("BSH_CHARACTER", DefaultLanguageHighlighterColors.STRING)
    val LINE_COMMENT: TextAttributesKey =
        key("BSH_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    val BLOCK_COMMENT: TextAttributesKey =
        key("BSH_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    val DOC_COMMENT: TextAttributesKey =
        key("BSH_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    val OPERATOR: TextAttributesKey =
        key("BSH_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    val PARENTHESES: TextAttributesKey =
        key("BSH_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    val BRACES: TextAttributesKey =
        key("BSH_BRACES", DefaultLanguageHighlighterColors.BRACES)
    val BRACKETS: TextAttributesKey =
        key("BSH_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    val SEMICOLON: TextAttributesKey =
        key("BSH_SEMICOLON", DefaultLanguageHighlighterColors.SEMICOLON)
    val COMMA: TextAttributesKey =
        key("BSH_COMMA", DefaultLanguageHighlighterColors.COMMA)
    val DOT: TextAttributesKey =
        key("BSH_DOT", DefaultLanguageHighlighterColors.DOT)
    val BAD_CHARACTER: TextAttributesKey =
        key("BSH_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)

    private fun key(name: String, fallback: TextAttributesKey): TextAttributesKey =
        TextAttributesKey.createTextAttributesKey(name, fallback)
}
