package cz.loplex.intellij.bsh

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.debug.BshLineBreakpointType

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
}
