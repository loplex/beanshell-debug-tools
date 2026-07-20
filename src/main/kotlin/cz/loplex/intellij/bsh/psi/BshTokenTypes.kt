package cz.loplex.intellij.bsh.psi

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet

/**
 * All lexer token types produced by [cz.loplex.intellij.bsh.lexer.BshLexer] together with
 * a few [TokenSet]s used by the highlighter, commenter and other editor features.
 *
 * The set of keywords mirrors the reserved words declared in the BeanShell
 * `bsh.jjt` grammar.
 */
object BshTokenTypes {
    // Comments
    @JvmField val LINE_COMMENT = BshTokenType("LINE_COMMENT")
    @JvmField val BLOCK_COMMENT = BshTokenType("BLOCK_COMMENT")
    @JvmField val DOC_COMMENT = BshTokenType("DOC_COMMENT")

    // Words
    @JvmField val KEYWORD = BshTokenType("KEYWORD")
    @JvmField val IDENTIFIER = BshTokenType("IDENTIFIER")

    // Literals
    @JvmField val INTEGER_LITERAL = BshTokenType("INTEGER_LITERAL")
    @JvmField val FLOAT_LITERAL = BshTokenType("FLOAT_LITERAL")
    @JvmField val STRING_LITERAL = BshTokenType("STRING_LITERAL")
    @JvmField val CHARACTER_LITERAL = BshTokenType("CHARACTER_LITERAL")

    // Separators
    @JvmField val LPAREN = BshTokenType("LPAREN")
    @JvmField val RPAREN = BshTokenType("RPAREN")
    @JvmField val LBRACE = BshTokenType("LBRACE")
    @JvmField val RBRACE = BshTokenType("RBRACE")
    @JvmField val LBRACKET = BshTokenType("LBRACKET")
    @JvmField val RBRACKET = BshTokenType("RBRACKET")
    @JvmField val SEMICOLON = BshTokenType("SEMICOLON")
    @JvmField val COMMA = BshTokenType("COMMA")
    @JvmField val DOT = BshTokenType("DOT")

    // Any operator, including BeanShell word operators such as `@gt`, `@and`, ...
    @JvmField val OPERATOR = BshTokenType("OPERATOR")

    /** Reserved words of the BeanShell grammar (`true`/`false`/`null`/`void` included). */
    @JvmField
    val KEYWORDS: Set<String> = setOf(
        "abstract", "boolean", "break", "byte", "case", "catch", "char", "class",
        "const", "continue", "default", "do", "double", "else", "enum", "extends",
        "false", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "null",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "switch", "synchronized", "throw", "throws", "transient", "true",
        "try", "void", "volatile", "while"
    )

    @JvmField
    val COMMENTS: TokenSet = TokenSet.create(LINE_COMMENT, BLOCK_COMMENT, DOC_COMMENT)

    @JvmField
    val STRING_LITERALS: TokenSet = TokenSet.create(STRING_LITERAL, CHARACTER_LITERAL)

    @JvmField
    val NUMBERS: TokenSet = TokenSet.create(INTEGER_LITERAL, FLOAT_LITERAL)

    @JvmField
    val BRACES: TokenSet = TokenSet.create(LBRACE, RBRACE)

    @JvmField
    val WHITESPACES: TokenSet = TokenSet.create(com.intellij.psi.TokenType.WHITE_SPACE)

    fun isKeyword(text: CharSequence): Boolean = KEYWORDS.contains(text.toString())
}
