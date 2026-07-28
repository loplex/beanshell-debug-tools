package cz.loplex.intellij.bsh

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.InjectionTestFixture
import cz.loplex.intellij.bsh.injection.MavenBeanshellScripts

class BshInjectionTest : BasePlatformTestCase() {

    private fun injection() = InjectionTestFixture(myFixture)

    private fun pom(artifactId: String, configBody: String): String =
        "<project><build><plugins><plugin>" +
            "<artifactId>$artifactId</artifactId>" +
            "<configuration>$configBody</configuration>" +
            "</plugin></plugins></build></project>"

    fun testBeanshellPluginScriptInjected() {
        myFixture.configureByText("pom.xml", pom("beanshell-maven-plugin", "<script>print(<caret>1);</script>"))
        injection().assertInjectedLangAtCaret("BeanShell")
    }

    fun testBuildHelperBshPropertySourceInjected() {
        myFixture.configureByText("pom.xml", pom("build-helper-maven-plugin", "<source>x = <caret>1;</source>"))
        injection().assertInjectedLangAtCaret("BeanShell")
    }

    fun testEnforcerConditionInjected() {
        val body = "<rules><evaluateBeanshell><condition>1 <caret>== 1</condition></evaluateBeanshell></rules>"
        myFixture.configureByText("pom.xml", pom("maven-enforcer-plugin", body))
        injection().assertInjectedLangAtCaret("BeanShell")
    }

    fun testBuildHelperAddSourceDirectoryNotInjected() {
        // <source> nested in <sources> is a directory path, not a script.
        val body = "<sources><source>src/<caret>gen</source></sources>"
        myFixture.configureByText("pom.xml", pom("build-helper-maven-plugin", body))
        injection().assertInjectedLangAtCaret(null)
    }

    fun testUnlistedPluginNotInjected() {
        myFixture.configureByText("pom.xml", pom("some-other-plugin", "<script>print(<caret>1);</script>"))
        injection().assertInjectedLangAtCaret(null)
    }

    fun testCommentBasedInjectionInAnyXml() {
        myFixture.configureByText(
            "beans.xml",
            "<beans><!--language=BeanShell--><expr>print(<caret>1);</expr></beans>",
        )
        injection().assertInjectedLangAtCaret("BeanShell")
    }

    fun testScriptListLoadedFromClasspath() {
        assertTrue(
            MavenBeanshellScripts.propertiesFor("maven-enforcer-plugin")
                .any { it.tag == "condition" && !it.directChildOfConfiguration },
        )
        assertTrue(
            MavenBeanshellScripts.propertiesFor("beanshell-maven-plugin")
                .any { it.tag == "script" && it.directChildOfConfiguration },
        )
        assertTrue(MavenBeanshellScripts.propertiesFor("unknown-plugin").isEmpty())
    }

    fun testNonPomNotInjected() {
        myFixture.configureByText(
            "beans.xml",
            "<project><build><plugins><plugin><artifactId>beanshell-maven-plugin</artifactId>" +
                "<configuration><script>print(<caret>1);</script></configuration>" +
                "</plugin></plugins></build></project>",
        )
        injection().assertInjectedLangAtCaret(null)
    }
}
