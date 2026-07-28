package cz.loplex.intellij.bsh

import com.intellij.openapi.application.ReadAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.debug.BshLineBreakpointType
import cz.loplex.intellij.bsh.debug.maven.BshMavenDebugSupport

/**
 * IDE-side wiring for debugging an inline BeanShell `<script>` in a pom.xml: breakpoints must be
 * placeable on the injected script lines (and only there). The launch itself (a Maven subprocess
 * driven by [cz.loplex.intellij.bsh.debug.maven.BshMavenRunConfiguration] plus a live session) is
 * covered by the extension unit test and the standalone debug-transport test.
 */
class BshMavenDebugIdeTest : BasePlatformTestCase() {

    private val pom = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>demo</groupId>
            <artifactId>demo</artifactId>
            <version>1.0</version>
            <build><plugins><plugin>
                <groupId>com.github.genthaler</groupId>
                <artifactId>beanshell-maven-plugin</artifactId>
                <version>1.4</version>
                <configuration><script><![CDATA[
int x = 1;
print(x);
]]></script></configuration>
            </plugin></plugins></build>
        </project>
    """.trimIndent()

    fun testBreakpointAllowedInsideInjectedScriptOnly() {
        val file = myFixture.configureByText("pom.xml", pom)
        val document = myFixture.editor.document
        val insideLine = document.getLineNumber(file.text.indexOf("print(x);"))
        val outsideLine = document.getLineNumber(file.text.indexOf("<artifactId>"))

        val type = BshLineBreakpointType()
        assertTrue("breakpoint allowed on a script line", type.canPutAt(file.virtualFile, insideLine, project))
        assertFalse("breakpoint rejected outside the script", type.canPutAt(file.virtualFile, outsideLine, project))
    }

    private val twoScriptPom = """
        <project>
            <modelVersion>4.0.0</modelVersion>
            <groupId>demo</groupId>
            <artifactId>demo</artifactId>
            <version>1.0</version>
            <build><plugins><plugin>
                <groupId>com.github.genthaler</groupId>
                <artifactId>beanshell-maven-plugin</artifactId>
                <version>1.4</version>
                <configuration><script><![CDATA[
print("first");
]]></script></configuration>
                <executions><execution><id>second</id>
                    <configuration><script><![CDATA[
print("second");
]]></script></configuration>
                </execution></executions>
            </plugin></plugins></build>
        </project>
    """.trimIndent()

    fun testPrepareFindsEveryScriptAndBakesPomLines() {
        val file = myFixture.configureByText("pom.xml", twoScriptPom)
        val document = myFixture.editor.document

        val prepared = ReadAction.compute<List<BshMavenDebugSupport.Prepared>, RuntimeException> {
            BshMavenDebugSupport.prepare(project, file.virtualFile)
        }

        assertEquals("both inline scripts discovered", 2, prepared.size)
        assertTrue(prepared.all { it.artifactId == "beanshell-maven-plugin" && it.tag == "script" })

        // Each instrumented script must bake the statement's absolute pom.xml line (not a snippet line).
        for (marker in listOf("first", "second")) {
            val entry = prepared.first { it.original.contains(marker) }
            val hostLine = document.getLineNumber(file.text.indexOf("print(\"$marker\");")) + 1
            assertTrue(
                "step() for \"$marker\" must carry pom line $hostLine",
                entry.instrumented.contains("BshDebugAgent.step($hostLine,"),
            )
        }
    }

    /**
     * Under the instrumenting agent nothing is baked in: BeanShell reports lines relative to the
     * snippet it was handed, so the pom line has to come from the map built here. The two snippets
     * must stay apart, which is what the name prefix is for.
     */
    fun testAgentLineMapperResolvesEachScriptToItsPomLine() {
        val file = myFixture.configureByText("pom.xml", twoScriptPom)
        val document = myFixture.editor.document

        val prepared = ReadAction.compute<List<BshMavenDebugSupport.Prepared>, RuntimeException> {
            BshMavenDebugSupport.prepare(project, file.virtualFile)
        }
        val mapper = BshMavenDebugSupport.lineMapper(prepared)

        for (marker in listOf("first", "second")) {
            val entry = prepared.first { it.original.contains(marker) }
            val hostLine = document.getLineNumber(file.text.indexOf("print(\"$marker\");")) + 1

            // The name BeanShell derives for a script it was handed as a string. The plugin trims the
            // XML text, so the statement is the snippet's line 1 -- the reading agentSources offers
            // first when the injected text has leading whitespace.
            val reported = BshMavenDebugSupport.beanShellSourceName(entry.original.trim())
            assertEquals("\"$marker\" line 1 maps to pom line $hostLine", hostLine, mapper(reported, 1))
        }

        assertEquals("an unknown source has no line in this file", -1, mapper("print.bsh", 1))
    }
}
