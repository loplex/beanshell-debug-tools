package cz.loplex.intellij.bsh

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import cz.loplex.intellij.bsh.psi.BshElementTypes

class BshParsingTest : ParsingTestCase("", "bsh", BshParserDefinition()) {

    override fun getTestDataPath(): String = "src/test/testData"

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = true

    private fun parse(text: String): com.intellij.psi.PsiFile = parseFile("test", text)

    private fun errors(root: PsiElement): List<PsiErrorElement> =
        PsiTreeUtil.collectElementsOfType(root, PsiErrorElement::class.java).toList()

    private fun typesIn(root: PsiElement): Set<com.intellij.psi.tree.IElementType> {
        val result = HashSet<com.intellij.psi.tree.IElementType>()
        fun visit(e: PsiElement) {
            result += e.node.elementType
            e.children.forEach { visit(it) }
        }
        visit(root)
        return result
    }

    private fun assertParsesCleanly(text: String) {
        val file = parse(text)
        val errs = errors(file)
        assertTrue(
            "expected no parse errors but found: " + errs.joinToString { "'${it.text}' (${it.errorDescription})" },
            errs.isEmpty()
        )
    }

    fun testScriptWithMethodsAndControlFlow() {
        val script = """
            import bsh.*;
            package demo;

            int add(int a, int b) {
                return a + b;
            }

            greet(name) {
                print("Hi " + name);
                return name != null ? name : "world";
            }

            list = new ArrayList();
            total = 0;
            for (int i = 0; i < 10; i++) {
                total += add(i, i * 2);
            }
            for (x : list) {
                print(x);
            }
            if (total > 5) print("big"); else print("small");
            while (total > 0) total--;
        """.trimIndent()
        assertParsesCleanly(script)

        val types = typesIn(parse(script))
        assertTrue("method decl", types.contains(BshElementTypes.METHOD_DECLARATION))
        assertTrue("for", types.contains(BshElementTypes.FOR_STATEMENT))
        assertTrue("enhanced for", types.contains(BshElementTypes.ENHANCED_FOR_STATEMENT))
        assertTrue("if", types.contains(BshElementTypes.IF_STATEMENT))
        assertTrue("ternary", types.contains(BshElementTypes.TERNARY_EXPRESSION))
        assertTrue("binary", types.contains(BshElementTypes.BINARY_EXPRESSION))
        assertTrue("allocation", types.contains(BshElementTypes.ALLOCATION_EXPRESSION))
        assertTrue("method invocation", types.contains(BshElementTypes.METHOD_INVOCATION))
    }

    fun testClassDeclaration() {
        val script = """
            class Point implements Comparable {
                int x;
                int y;
                Point(int x, int y) { this.x = x; this.y = y; }
                int sum() { return x + y; }
            }
        """.trimIndent()
        assertParsesCleanly(script)
        val types = typesIn(parse(script))
        assertTrue(types.contains(BshElementTypes.CLASS_DECLARATION))
        assertTrue(types.contains(BshElementTypes.TYPED_VARIABLE_DECLARATION))
    }

    fun testExpressionsAndOperators() {
        val script = """
            x = (1 + 2) * 3 - 4 / 2 % 3;
            y = a << 2 | b & 0xFF ^ c;
            z = !flag && (p || q);
            n = (int) 3.5;
            arr = new int[] { 1, 2, 3 };
            m = new HashMap() { };
            cls = String.class;
            r = obj.field.method(1, 2).chain;
            try { risky(); } catch (Exception e) { handle(e); } finally { cleanup(); }
            switch (n) { case 1: print("one"); break; default: print("other"); }
        """.trimIndent()
        assertParsesCleanly(script)
    }

    fun testSyntaxErrorIsReported() {
        val file = parse("int x = ;")
        assertFalse("a missing expression should produce an error", errors(file).isEmpty())
    }

    fun testTrailingExpressionWithoutSemicolonIsTheReturnValue() {
        // BeanShell allows a final expression with no ';' as the eval/block return value
        // (e.g. an enforcer <condition>); it must not be flagged as a parse error.
        assertParsesCleanly("a = 1;\nb = 2;\na > b")
        assertParsesCleanly("x = 1;\nif (x > 0) {\n    y = 2;\n    y\n}")
    }

    fun testMissingSemicolonBeforeAnotherStatementStillErrors() {
        val file = parse("a = 1\nb = 2;")
        assertFalse("a missing ';' before a following statement is still an error", errors(file).isEmpty())
    }
}
