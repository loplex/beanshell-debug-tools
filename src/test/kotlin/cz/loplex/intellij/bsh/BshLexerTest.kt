package cz.loplex.intellij.bsh

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BshLexerTest {

    private data class Tok(val type: IElementType, val text: String)

    private fun lex(input: String): List<Tok> {
        val lexer = BshLexer()
        lexer.start(input, 0, input.length, 0)
        val result = mutableListOf<Tok>()
        while (lexer.tokenType != null) {
            result += Tok(lexer.tokenType!!, input.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return result
    }

    /** The lexer must tile the whole input with no gaps or overlaps. */
    private fun assertCoversInput(input: String) {
        val toks = lex(input)
        assertEquals("reconstructed text must equal input", input, toks.joinToString("") { it.text })
    }

    @Test
    fun coversRepresentativeScript() {
        val script = """
            /** doc */
            import bsh.*;
            #!shebang line
            x = 0x2A;            // hex int
            y = 3.14f;
            s = "a\"b";
            t = ${"\"\"\""}long
            string${"\"\"\""};
            c = 'q';
            r = a @gt b ? a >>>= 2 : a ** 2;
            for (int i = 0; i < 10; i++) { print(i); }
        """.trimIndent()
        assertCoversInput(script)
        assertFalse(
            "no bad characters in valid script",
            lex(script).any { it.type == TokenType.BAD_CHARACTER }
        )
    }

    @Test
    fun classifiesCoreTokens() {
        val toks = lex("if x == 0x1F 3.0d \"hi\" 'c' @and { } ; . , ( )")
            .filter { it.type != TokenType.WHITE_SPACE }
        val byText = toks.associate { it.text to it.type }
        assertEquals(BshTokenTypes.KEYWORD, byText["if"])
        assertEquals(BshTokenTypes.IDENTIFIER, byText["x"])
        assertEquals(BshTokenTypes.OPERATOR, byText["=="])
        assertEquals(BshTokenTypes.INTEGER_LITERAL, byText["0x1F"])
        assertEquals(BshTokenTypes.FLOAT_LITERAL, byText["3.0d"])
        assertEquals(BshTokenTypes.STRING_LITERAL, byText["\"hi\""])
        assertEquals(BshTokenTypes.CHARACTER_LITERAL, byText["'c'"])
        assertEquals(BshTokenTypes.OPERATOR, byText["@and"])
        assertEquals(BshTokenTypes.LBRACE, byText["{"])
        assertEquals(BshTokenTypes.RBRACE, byText["}"])
        assertEquals(BshTokenTypes.SEMICOLON, byText[";"])
        assertEquals(BshTokenTypes.DOT, byText["."])
        assertEquals(BshTokenTypes.COMMA, byText[","])
        assertEquals(BshTokenTypes.LPAREN, byText["("])
        assertEquals(BshTokenTypes.RPAREN, byText[")"])
    }

    @Test
    fun maximalMunchOperators() {
        val ops = lex(">>>= <=> ??= ... ->").filter { it.type != TokenType.WHITE_SPACE }
        assertEquals(listOf(">>>=", "<=>", "??=", "...", "->"), ops.map { it.text })
        assertTrue(ops.all { it.type == BshTokenTypes.OPERATOR })
    }

    @Test
    fun handlesUnterminatedAndEmptyInput() {
        assertCoversInput("")
        assertCoversInput("\"unterminated")
        assertCoversInput("/* unterminated block")
        assertCoversInput("'")
    }

    @Test
    fun floatVsIntegerSuffixes() {
        val toks = lex("100L 100w 1_000 .5 1e3 0b1010").filter { it.type != TokenType.WHITE_SPACE }
        assertEquals(BshTokenTypes.INTEGER_LITERAL, toks[0].type) // 100L
        assertEquals(BshTokenTypes.INTEGER_LITERAL, toks[1].type) // 100w
        assertEquals(BshTokenTypes.INTEGER_LITERAL, toks[2].type) // 1_000
        assertEquals(BshTokenTypes.FLOAT_LITERAL, toks[3].type)   // .5
        assertEquals(BshTokenTypes.FLOAT_LITERAL, toks[4].type)   // 1e3
        assertEquals(BshTokenTypes.INTEGER_LITERAL, toks[5].type) // 0b1010
    }
}
