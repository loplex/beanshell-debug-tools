package cz.loplex.intellij.bsh.mavenext

import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Maven core extension's model surgery: substituting the inline BeanShell script
 * with the IDE-instrumented text and adding the system-scoped callback dependency. Runs against the
 * light Maven model only (no maven-core, no Maven runtime).
 */
class BshScriptRewriterTest {

    private val callbackJar = "/tmp/plugin-with-agent.jar"

    private fun config(tag: String, value: String): Xpp3Dom =
        Xpp3Dom("configuration").apply { addChild(Xpp3Dom(tag).apply { setValue(value) }) }

    private fun beanshellPlugin(configuration: Xpp3Dom?): Plugin =
        Plugin().apply {
            groupId = "com.github.genthaler"
            artifactId = "beanshell-maven-plugin"
            version = "1.4"
            setConfiguration(configuration)
        }

    @Test
    fun replacesPluginLevelScriptAndAddsSystemScopedCallback() {
        val plugin = beanshellPlugin(config("script", "print(1);"))

        val touched = BshScriptRewriter().instrumentPlugin(plugin, "script", "INSTRUMENTED", callbackJar)

        assertTrue(touched)
        assertEquals("INSTRUMENTED", (plugin.configuration as Xpp3Dom).getChild("script").value)
        assertEquals(1, plugin.dependencies.size)
        val dependency = plugin.dependencies[0]
        assertEquals("system", dependency.scope)
        assertEquals(callbackJar, dependency.systemPath)
        assertEquals(BshScriptRewriter.CALLBACK_GROUP_ID, dependency.groupId)
        assertEquals(BshScriptRewriter.CALLBACK_ARTIFACT_ID, dependency.artifactId)
    }

    @Test
    fun replacesExecutionLevelScript() {
        val plugin = beanshellPlugin(null)
        plugin.addExecution(PluginExecution().apply { id = "run"; setConfiguration(config("script", "old;")) })

        val touched = BshScriptRewriter().instrumentPlugin(plugin, "script", "NEW", callbackJar)

        assertTrue(touched)
        assertEquals("NEW", (plugin.executions[0].configuration as Xpp3Dom).getChild("script").value)
        assertEquals(1, plugin.dependencies.size)
    }

    @Test
    fun isIdempotentAcrossRuns() {
        val plugin = beanshellPlugin(config("script", "print(1);"))
        val rewriter = BshScriptRewriter()

        rewriter.instrumentPlugin(plugin, "script", "A", callbackJar)
        rewriter.instrumentPlugin(plugin, "script", "B", callbackJar)

        assertEquals("B", (plugin.configuration as Xpp3Dom).getChild("script").value)
        assertEquals("no duplicate callback dependency", 1, plugin.dependencies.size)
    }

    @Test
    fun leavesPluginUntouchedWhenScriptTagAbsent() {
        val plugin = beanshellPlugin(config("somethingElse", "x;"))

        val touched = BshScriptRewriter().instrumentPlugin(plugin, "script", "NEW", callbackJar)

        assertFalse(touched)
        assertTrue(plugin.dependencies.isEmpty())
    }
}
