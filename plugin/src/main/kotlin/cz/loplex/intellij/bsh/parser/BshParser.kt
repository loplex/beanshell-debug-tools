package cz.loplex.intellij.bsh.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiParser
import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.psi.BshElementTypes as E
import cz.loplex.intellij.bsh.psi.BshTokenTypes as T

/**
 * Recursive-descent parser for BeanShell (2.0b6 grammar) that builds a full AST.
 *
 * The BeanShell reference grammar (`bsh.jjt`) relies heavily on JavaCC syntactic
 * lookaheads to disambiguate declarations from statements, casts from
 * parenthesized expressions, and so on. Those lookaheads are reproduced here
 * with bounded backtracking: an alternative is attempted behind a
 * [PsiBuilder.Marker], and [PsiBuilder.Marker.rollbackTo] rewinds the builder if
 * it does not match, so the next alternative can be tried. This mirrors the
 * ordered-choice semantics of the original grammar while remaining tolerant of
 * incomplete input in the editor.
 */
@Suppress("SameReturnValue")
class BshParser : PsiParser {

    private lateinit var b: PsiBuilder

    override fun parse(root: IElementType, builder: PsiBuilder): ASTNode {
        b = builder
        val rootMarker = builder.mark()
        while (!builder.eof()) {
            if (!parseBlockStatement()) {
                val err = builder.mark()
                builder.advanceLexer()
                err.error("Unexpected token")
            }
        }
        rootMarker.done(root)
        return builder.treeBuilt
    }

    // ---------------------------------------------------------------------
    // Token helpers
    // ---------------------------------------------------------------------

    private fun at(type: IElementType): Boolean = b.tokenType === type

    /** Matches by token text; works for keywords, operators and separators. */
    private fun atText(text: String): Boolean = b.tokenText == text

    @Suppress("SameParameterValue")
    private fun lookAhead(n: Int): IElementType? = b.lookAhead(n)

    private fun consume() = b.advanceLexer()

    private fun expect(type: IElementType, name: String) {
        if (at(type)) consume() else b.error("'$name' expected")
    }

    private fun expectText(text: String) {
        if (atText(text)) consume() else b.error("'$text' expected")
    }

    private fun isKeywordText(text: String): Boolean = at(T.KEYWORD) && atText(text)

    // ---------------------------------------------------------------------
    // Top level: BlockStatement
    // ---------------------------------------------------------------------

    private fun parseBlockStatement(): Boolean {
        if (tryClassDeclaration()) return true
        if (tryMethodDeclaration()) return true
        if (tryTypedVariableDeclaration()) return true
        if (tryImportDeclaration()) return true
        if (tryPackageDeclaration()) return true
        return parseStatement()
    }

    private fun tryClassDeclaration(): Boolean {
        val m = b.mark()
        parseModifiers()
        if (!(isKeywordText("class") || isKeywordText("interface"))) {
            m.rollbackTo(); return false
        }
        consume() // class | interface
        expect(T.IDENTIFIER, "class name")
        if (isKeywordText("extends")) { consume(); parseAmbiguousName() }
        if (isKeywordText("implements")) { consume(); parseNameList() }
        parseBlock()
        m.done(E.CLASS_DECLARATION)
        return true
    }

    private fun tryMethodDeclaration(): Boolean {
        val m = b.mark()
        parseModifiers()

        // Loose method: IDENTIFIER "(" ... must be followed by a body block.
        if (at(T.IDENTIFIER) && lookAhead(1) === T.LPAREN) {
            consume() // name
            parseFormalParameters()
            if (isKeywordText("throws")) { consume(); parseNameList() }
            if (at(T.LBRACE) || isKeywordText("static")) {
                parseBlock()
                m.done(E.METHOD_DECLARATION)
                return true
            }
            m.rollbackTo()
            return false
        }

        // Typed method: ReturnType IDENTIFIER "(" ... ( block | ";" )
        if (parseReturnType() && at(T.IDENTIFIER) && lookAhead(1) === T.LPAREN) {
            consume() // name
            parseFormalParameters()
            if (isKeywordText("throws")) { consume(); parseNameList() }
            if (at(T.LBRACE)) parseBlock() else expect(T.SEMICOLON, ";")
            m.done(E.METHOD_DECLARATION)
            return true
        }

        m.rollbackTo()
        return false
    }

    private fun tryTypedVariableDeclaration(): Boolean {
        val m = b.mark()
        parseModifiers()
        if (parseType() && at(T.IDENTIFIER)) {
            parseVariableDeclarator()
            while (at(T.COMMA)) { consume(); parseVariableDeclarator() }
            expect(T.SEMICOLON, ";")
            m.done(E.TYPED_VARIABLE_DECLARATION)
            return true
        }
        m.rollbackTo()
        return false
    }

    private fun tryImportDeclaration(): Boolean {
        if (!isKeywordText("import")) return false
        val m = b.mark()
        consume()
        if (atText("*")) {
            consume()
            expect(T.SEMICOLON, ";")
            m.done(E.IMPORT_DECLARATION)
            return true
        }
        if (isKeywordText("static")) consume()
        parseAmbiguousName()
        if (at(T.DOT)) {
            consume()
            if (atText("*")) consume() else b.error("'*' expected")
        }
        expect(T.SEMICOLON, ";")
        m.done(E.IMPORT_DECLARATION)
        return true
    }

    private fun tryPackageDeclaration(): Boolean {
        if (!isKeywordText("package")) return false
        val m = b.mark()
        consume()
        parseAmbiguousName()
        if (at(T.SEMICOLON)) consume() // tolerate the customary trailing ';'
        m.done(E.PACKAGE_DECLARATION)
        return true
    }

    // ---------------------------------------------------------------------
    // Modifiers, types, names
    // ---------------------------------------------------------------------

    private fun parseModifiers() {
        if (at(T.KEYWORD) && b.tokenText in MODIFIERS) {
            val m = b.mark()
            while (at(T.KEYWORD) && b.tokenText in MODIFIERS) consume()
            m.done(E.MODIFIERS)
        }
    }

    private fun parseReturnType(): Boolean {
        if (isKeywordText("void")) { consume(); return true }
        return parseType()
    }

    private fun parseType(): Boolean {
        val m = b.mark()
        if (parsePrimitiveType() || parseAmbiguousName()) {
            while (at(T.LBRACKET) && lookAhead(1) === T.RBRACKET) { consume(); consume() }
            m.done(E.TYPE)
            return true
        }
        m.rollbackTo()
        return false
    }

    private fun parsePrimitiveType(): Boolean {
        if (at(T.KEYWORD) && b.tokenText in PRIMITIVES) {
            val m = b.mark()
            consume()
            m.done(E.PRIMITIVE_TYPE)
            return true
        }
        return false
    }

    private fun parseAmbiguousName(): Boolean {
        if (!at(T.IDENTIFIER)) return false
        val m = b.mark()
        consume()
        while (at(T.DOT) && lookAhead(1) === T.IDENTIFIER) { consume(); consume() }
        m.done(E.AMBIGUOUS_NAME)
        return true
    }

    private fun parseNameList() {
        val m = b.mark()
        if (parseAmbiguousName()) {
            while (at(T.COMMA)) { consume(); parseAmbiguousName() }
        }
        m.done(E.NAME_LIST)
    }

    private fun parseFormalParameters() {
        val m = b.mark()
        expect(T.LPAREN, "(")
        if (!at(T.RPAREN)) {
            parseFormalParameter()
            while (at(T.COMMA)) { consume(); parseFormalParameter() }
        }
        expect(T.RPAREN, ")")
        m.done(E.FORMAL_PARAMETERS)
    }

    private fun parseFormalParameter() {
        val m = b.mark()
        val typed = b.mark()
        if (parseType() && at(T.IDENTIFIER)) {
            typed.drop()
            consume() // name
            m.done(E.FORMAL_PARAMETER)
            return
        }
        typed.rollbackTo()
        if (at(T.IDENTIFIER)) {
            consume()
            m.done(E.FORMAL_PARAMETER)
            return
        }
        m.drop()
        b.error("Parameter expected")
    }

    private fun parseVariableDeclarator() {
        val m = b.mark()
        expect(T.IDENTIFIER, "identifier")
        if (atText("=")) { consume(); parseVariableInitializer() }
        m.done(E.VARIABLE_DECLARATOR)
    }

    private fun parseVariableInitializer() {
        if (at(T.LBRACE)) parseArrayInitializer() else parseExpressionOrError()
    }

    private fun parseArrayInitializer() {
        val m = b.mark()
        expect(T.LBRACE, "{")
        if (!at(T.RBRACE)) {
            parseVariableInitializer()
            while (at(T.COMMA)) {
                consume()
                if (at(T.RBRACE)) break // trailing comma
                parseVariableInitializer()
            }
        }
        expect(T.RBRACE, "}")
        m.done(E.ARRAY_INITIALIZER)
    }

    // ---------------------------------------------------------------------
    // Statements
    // ---------------------------------------------------------------------

    private fun parseStatement(): Boolean {
        // Labeled statement: IDENTIFIER ":" Statement
        run {
            val m = b.mark()
            if (at(T.IDENTIFIER)) {
                consume()
                if (atText(":")) {
                    consume()
                    if (!parseStatement()) b.error("Statement expected")
                    m.done(E.LABELED_STATEMENT)
                    return true
                }
            }
            m.rollbackTo()
        }

        if (at(T.LBRACE) || isKeywordText("static")) return parseBlock()
        if (at(T.SEMICOLON)) {
            val m = b.mark(); consume(); m.done(E.EMPTY_STATEMENT); return true
        }
        if (isKeywordText("switch")) return parseSwitch()
        if (isKeywordText("if")) return parseIf()
        if (isKeywordText("while")) return parseWhile()
        if (isKeywordText("do")) return parseDo()
        if (isKeywordText("for")) return parseFor()
        if (isKeywordText("break") || isKeywordText("continue")) return parseBreakContinue()
        if (isKeywordText("return")) return parseReturn()
        if (isKeywordText("synchronized")) return parseSynchronized()
        if (isKeywordText("throw")) return parseThrow()
        if (isKeywordText("try")) return parseTry()

        // Statement expression: Expression ";". A trailing expression with no ';' before the end
        // of a block or file is the block/eval return value (allowed by BeanShell, and required by
        // e.g. an enforcer <condition>), so only demand the ';' when another statement follows.
        val m = b.mark()
        if (parseExpression()) {
            when {
                at(T.SEMICOLON) -> consume()
                at(T.RBRACE) || b.eof() -> {} // return-value expression; no ';' needed
                else -> expect(T.SEMICOLON, ";")
            }
            m.drop()
            return true
        }
        m.rollbackTo()
        return false
    }

    private fun parseBlock(): Boolean {
        val m = b.mark()
        if (isKeywordText("static")) consume()
        expect(T.LBRACE, "{")
        while (!at(T.RBRACE) && !b.eof()) {
            if (!parseBlockStatement()) {
                val err = b.mark(); consume(); err.error("Unexpected token")
            }
        }
        expect(T.RBRACE, "}")
        m.done(E.BLOCK)
        return true
    }

    private fun parseIf(): Boolean {
        val m = b.mark()
        consume() // if
        expect(T.LPAREN, "("); parseExpressionOrError(); expect(T.RPAREN, ")")
        if (!parseStatement()) b.error("Statement expected")
        if (isKeywordText("else")) {
            consume()
            if (!parseStatement()) b.error("Statement expected")
        }
        m.done(E.IF_STATEMENT)
        return true
    }

    private fun parseWhile(): Boolean {
        val m = b.mark()
        consume() // while
        expect(T.LPAREN, "("); parseExpressionOrError(); expect(T.RPAREN, ")")
        if (!parseStatement()) b.error("Statement expected")
        m.done(E.WHILE_STATEMENT)
        return true
    }

    private fun parseDo(): Boolean {
        val m = b.mark()
        consume() // do
        if (!parseStatement()) b.error("Statement expected")
        expectText("while")
        expect(T.LPAREN, "("); parseExpressionOrError(); expect(T.RPAREN, ")")
        expect(T.SEMICOLON, ";")
        m.done(E.WHILE_STATEMENT)
        return true
    }

    private fun parseFor(): Boolean {
        // Regular for: for ( [init] ; [expr] ; [update] ) stmt
        val m = b.mark()
        consume() // for
        expect(T.LPAREN, "(")
        if (tryRegularForRest()) {
            m.done(E.FOR_STATEMENT)
            return true
        }
        m.rollbackTo()

        // Enhanced for: for ( [Type] IDENTIFIER : Expression ) stmt
        val m2 = b.mark()
        consume() // for
        expect(T.LPAREN, "(")
        val typed = b.mark()
        if (parseType() && at(T.IDENTIFIER)) typed.drop() else typed.rollbackTo()
        expect(T.IDENTIFIER, "identifier")
        expectText(":")
        parseExpressionOrError()
        expect(T.RPAREN, ")")
        if (!parseStatement()) b.error("Statement expected")
        m2.done(E.ENHANCED_FOR_STATEMENT)
        return true
    }

    private fun tryRegularForRest(): Boolean {
        if (!at(T.SEMICOLON)) { if (!parseForInit()) return false }
        if (!at(T.SEMICOLON)) return false
        consume()
        if (!at(T.SEMICOLON)) { if (!parseExpression()) return false }
        if (!at(T.SEMICOLON)) return false
        consume()
        if (!at(T.RPAREN)) { if (!parseStatementExpressionList()) return false }
        if (!at(T.RPAREN)) return false
        consume()
        return parseStatement()
    }

    private fun parseForInit(): Boolean {
        val m = b.mark()
        parseModifiers()
        if (parseType() && at(T.IDENTIFIER)) {
            parseVariableDeclarator()
            while (at(T.COMMA)) { consume(); parseVariableDeclarator() }
            m.done(E.TYPED_VARIABLE_DECLARATION)
            return true
        }
        m.rollbackTo()
        return parseStatementExpressionList()
    }

    private fun parseStatementExpressionList(): Boolean {
        val m = b.mark()
        if (!parseExpression()) { m.drop(); return false }
        while (at(T.COMMA)) { consume(); parseExpressionOrError() }
        m.done(E.STATEMENT_EXPRESSION_LIST)
        return true
    }

    private fun parseBreakContinue(): Boolean {
        val m = b.mark()
        consume() // break | continue
        if (at(T.IDENTIFIER)) consume()
        expect(T.SEMICOLON, ";")
        m.done(E.RETURN_STATEMENT)
        return true
    }

    private fun parseReturn(): Boolean {
        val m = b.mark()
        consume() // return
        if (!at(T.SEMICOLON)) parseExpressionOrError()
        expect(T.SEMICOLON, ";")
        m.done(E.RETURN_STATEMENT)
        return true
    }

    private fun parseThrow(): Boolean {
        val m = b.mark()
        consume() // throw
        parseExpressionOrError()
        expect(T.SEMICOLON, ";")
        m.done(E.THROW_STATEMENT)
        return true
    }

    private fun parseSynchronized(): Boolean {
        val m = b.mark()
        consume() // synchronized
        expect(T.LPAREN, "("); parseExpressionOrError(); expect(T.RPAREN, ")")
        parseBlock()
        m.done(E.SYNCHRONIZED_STATEMENT)
        return true
    }

    private fun parseTry(): Boolean {
        val m = b.mark()
        consume() // try
        parseBlock()
        while (isKeywordText("catch")) {
            consume()
            expect(T.LPAREN, "(")
            parseFormalParameter()
            expect(T.RPAREN, ")")
            parseBlock()
        }
        if (isKeywordText("finally")) { consume(); parseBlock() }
        m.done(E.TRY_STATEMENT)
        return true
    }

    private fun parseSwitch(): Boolean {
        val m = b.mark()
        consume() // switch
        expect(T.LPAREN, "("); parseExpressionOrError(); expect(T.RPAREN, ")")
        expect(T.LBRACE, "{")
        while (!at(T.RBRACE) && !b.eof()) {
            if (isKeywordText("case") || isKeywordText("default")) {
                parseSwitchLabel()
            } else if (!parseBlockStatement()) {
                val err = b.mark(); consume(); err.error("Unexpected token")
            }
        }
        expect(T.RBRACE, "}")
        m.done(E.SWITCH_STATEMENT)
        return true
    }

    private fun parseSwitchLabel() {
        val m = b.mark()
        if (isKeywordText("case")) {
            consume(); parseExpressionOrError(); expectText(":")
        } else {
            expectText("default"); expectText(":")
        }
        m.done(E.SWITCH_LABEL)
    }

    // ---------------------------------------------------------------------
    // Expressions
    // ---------------------------------------------------------------------

    private fun parseExpressionOrError() {
        if (!parseExpression()) b.error("Expression expected")
    }

    private fun parseExpression(): Boolean {
        // Assignment: PrimaryExpression AssignmentOperator Expression
        val m = b.mark()
        if (parsePrimaryExpression() && atAssignmentOperator()) {
            consume() // operator
            parseExpressionOrError()
            m.done(E.ASSIGNMENT)
            return true
        }
        m.rollbackTo()
        return parseConditionalExpression()
    }

    private fun atAssignmentOperator(): Boolean =
        at(T.OPERATOR) && b.tokenText in ASSIGNMENT_OPERATORS

    private fun parseConditionalExpression(): Boolean {
        val m = b.mark()
        if (!parseConditionalOr()) { m.drop(); return false }
        if (atText("?")) {
            consume()
            parseExpressionOrError()
            expectText(":")
            if (!parseConditionalExpression()) b.error("Expression expected")
            m.done(E.TERNARY_EXPRESSION)
        } else {
            m.drop()
        }
        return true
    }

    private fun parseConditionalOr() = binaryLevel(::parseConditionalAnd, OR_OPS)
    private fun parseConditionalAnd() = binaryLevel(::parseInclusiveOr, AND_OPS)
    private fun parseInclusiveOr() = binaryLevel(::parseExclusiveOr, BIT_OR_OPS)
    private fun parseExclusiveOr() = binaryLevel(::parseAnd, XOR_OPS)
    private fun parseAnd() = binaryLevel(::parseEquality, BIT_AND_OPS)
    private fun parseEquality() = binaryLevel(::parseInstanceOf, EQUALITY_OPS)

    private fun parseInstanceOf(): Boolean {
        val m = b.mark()
        if (!parseRelational()) { m.drop(); return false }
        if (isKeywordText("instanceof")) {
            consume()
            if (!parseType()) b.error("Type expected")
            m.done(E.BINARY_EXPRESSION)
        } else {
            m.drop()
        }
        return true
    }

    private fun parseRelational() = binaryLevel(::parseShift, RELATIONAL_OPS)
    private fun parseShift() = binaryLevel(::parseAdditive, SHIFT_OPS)
    private fun parseAdditive() = binaryLevel(::parseMultiplicative, ADDITIVE_OPS)
    private fun parseMultiplicative() = binaryLevel(::parseUnary, MULTIPLICATIVE_OPS)

    private fun binaryLevel(next: () -> Boolean, ops: Set<String>): Boolean {
        var m = b.mark()
        if (!next()) { m.drop(); return false }
        while (at(T.OPERATOR) && b.tokenText in ops) {
            consume()
            if (!next()) b.error("Expression expected")
            m.done(E.BINARY_EXPRESSION)
            m = m.precede()
        }
        m.drop()
        return true
    }

    private fun parseUnary(): Boolean {
        if (atText("+") || atText("-")) {
            val m = b.mark(); consume()
            if (!parseUnary()) b.error("Expression expected")
            m.done(E.UNARY_EXPRESSION); return true
        }
        if (atText("++") || atText("--")) {
            val m = b.mark(); consume()
            if (!parsePrimaryExpression()) b.error("Expression expected")
            m.done(E.UNARY_EXPRESSION); return true
        }
        return parseUnaryNotPlusMinus()
    }

    private fun parseUnaryNotPlusMinus(): Boolean {
        if (atText("~") || atText("!")) {
            val m = b.mark(); consume()
            if (!parseUnary()) b.error("Expression expected")
            m.done(E.UNARY_EXPRESSION); return true
        }
        if (tryCast()) return true
        return parsePostfix()
    }

    private fun tryCast(): Boolean {
        if (!at(T.LPAREN)) return false
        val m = b.mark()
        consume() // (
        val primitive = at(T.KEYWORD) && b.tokenText in PRIMITIVES
        if (!parseType()) { m.rollbackTo(); return false }
        if (!at(T.RPAREN)) { m.rollbackTo(); return false }
        consume() // )
        val operand = if (primitive) parseUnary() else parseUnaryNotPlusMinus()
        if (!operand) { m.rollbackTo(); return false }
        m.done(E.CAST_EXPRESSION)
        return true
    }

    private fun parsePostfix(): Boolean {
        val m = b.mark()
        if (!parsePrimaryExpression()) { m.rollbackTo(); return false }
        if (atText("++") || atText("--")) {
            consume()
            m.done(E.UNARY_EXPRESSION)
            return true
        }
        m.drop()
        return true
    }

    private fun parsePrimaryExpression(): Boolean {
        val m = b.mark()
        if (!parsePrimaryPrefix()) { m.rollbackTo(); return false }
        while (parsePrimarySuffix()) { /* consume suffixes */ }
        m.done(E.PRIMARY_EXPRESSION)
        return true
    }

    private fun parsePrimaryPrefix(): Boolean {
        if (parseLiteral()) return true

        if (at(T.LPAREN)) { // "(" Expression ")"
            consume()
            parseExpressionOrError()
            expect(T.RPAREN, ")")
            return true
        }

        if (isKeywordText("new")) return parseAllocation()

        // MethodInvocation: AmbiguousName Arguments
        run {
            val m = b.mark()
            if (parseAmbiguousName() && at(T.LPAREN)) {
                parseArguments()
                m.done(E.METHOD_INVOCATION)
                return true
            }
            m.rollbackTo()
        }

        // Type "." "class"  (e.g. int.class, Foo.class)
        run {
            val m = b.mark()
            if (parseType() && at(T.DOT)) {
                consume()
                if (atText("class")) { consume(); m.drop(); return true }
            }
            m.rollbackTo()
        }

        return parseAmbiguousName()
    }

    private fun parsePrimarySuffix(): Boolean {
        if (at(T.DOT)) {
            // Field access or method call after a prefix (e.g. foo().bar) — wrapped so it
            // can carry a reference into Java via type propagation.
            if (lookAhead(1) === T.IDENTIFIER) {
                val m = b.mark()
                consume() // .
                consume() // identifier
                if (at(T.LPAREN)) parseArguments()
                m.done(E.PRIMARY_SUFFIX)
                return true
            }
            consume() // .
            if (atText("class")) consume() else b.error("Identifier or 'class' expected")
            return true
        }
        if (at(T.LBRACKET)) {
            consume()
            parseExpressionOrError()
            expect(T.RBRACKET, "]")
            return true
        }
        return false
    }

    private fun parseArguments() {
        val m = b.mark()
        expect(T.LPAREN, "(")
        if (!at(T.RPAREN)) {
            parseExpressionOrError()
            while (at(T.COMMA)) { consume(); parseExpressionOrError() }
        }
        expect(T.RPAREN, ")")
        m.done(E.ARGUMENTS)
    }

    private fun parseAllocation(): Boolean {
        val m = b.mark()
        consume() // new
        if (parsePrimitiveType()) {
            parseArrayDimensions()
        } else if (parseAmbiguousName()) {
            when {
                at(T.LBRACKET) -> parseArrayDimensions()
                at(T.LPAREN) -> { parseArguments(); if (at(T.LBRACE)) parseBlock() }
                else -> b.error("'(' or '[' expected")
            }
        } else {
            b.error("Type expected")
        }
        m.done(E.ALLOCATION_EXPRESSION)
        return true
    }

    private fun parseArrayDimensions() {
        val m = b.mark()
        if (at(T.LBRACKET) && lookAhead(1) === T.RBRACKET) {
            while (at(T.LBRACKET) && lookAhead(1) === T.RBRACKET) { consume(); consume() }
            if (at(T.LBRACE)) parseArrayInitializer()
        } else {
            while (at(T.LBRACKET) && lookAhead(1) !== T.RBRACKET) {
                consume(); parseExpressionOrError(); expect(T.RBRACKET, "]")
            }
            while (at(T.LBRACKET) && lookAhead(1) === T.RBRACKET) { consume(); consume() }
        }
        m.done(E.ARRAY_DIMENSIONS)
    }

    private fun parseLiteral(): Boolean {
        val isLiteral = at(T.INTEGER_LITERAL) || at(T.FLOAT_LITERAL) ||
            at(T.STRING_LITERAL) || at(T.CHARACTER_LITERAL) ||
            isKeywordText("true") || isKeywordText("false") ||
            isKeywordText("null") || isKeywordText("void")
        if (!isLiteral) return false
        val m = b.mark()
        consume()
        m.done(E.LITERAL)
        return true
    }

    companion object {
        private val PRIMITIVES = setOf(
            "boolean", "char", "byte", "short", "int", "long", "float", "double"
        )
        private val MODIFIERS = setOf(
            "private", "protected", "public", "synchronized", "final", "native",
            "transient", "volatile", "abstract", "static", "strictfp"
        )
        private val ASSIGNMENT_OPERATORS = setOf(
            "=", "*=", "/=", "%=", "+=", "-=", "&=", "^=", "|=", "<<=", ">>=", ">>>=",
            "@left_shift_assign", "@right_shift_assign", "@right_unsigned_shift_assign"
        )
        private val OR_OPS = setOf("||", "@or")
        private val AND_OPS = setOf("&&", "@and")
        private val BIT_OR_OPS = setOf("|", "@bitwise_or")
        private val XOR_OPS = setOf("^")
        private val BIT_AND_OPS = setOf("&", "@bitwise_and")
        private val EQUALITY_OPS = setOf("==", "!=")
        private val RELATIONAL_OPS = setOf(
            "<", ">", "<=", ">=", "@lt", "@gt", "@lteq", "@gteq"
        )
        private val SHIFT_OPS = setOf(
            "<<", ">>", ">>>", "@left_shift", "@right_shift", "@right_unsigned_shift"
        )
        private val ADDITIVE_OPS = setOf("+", "-")
        private val MULTIPLICATIVE_OPS = setOf("*", "/", "%")
    }
}
