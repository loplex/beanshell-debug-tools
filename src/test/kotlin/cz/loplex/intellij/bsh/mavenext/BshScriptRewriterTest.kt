package cz.loplex.intellij.bsh.mavenext

import org.apache.maven.model.Plugin
import org.apache.maven.model.PluginExecution
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Maven core extension's model surgery: substituting each inline BeanShell script
 * (matched by tag + content) with the IDE-instrumented text and adding the system-scoped callback
 * dependency. Runs against the light Maven model only (no maven-core, no Maven runtime).
 */
class BshScriptRewriterTest {

    private val callbackJar = "/tmp/plugin-with-agent.jar"

    private fun element(name: String, value: String): Xpp3Dom = Xpp3Dom(name).apply { setValue(value) }

    private fun config(vararg children: Xpp3Dom): Xpp3Dom =
        Xpp3Dom("configuration").apply { children.forEach { addChild(it) } }

    private fun beanshellPlugin(configuration: Xpp3Dom?): Plugin =
        Plugin().apply {
            groupId = "com.github.genthaler"
            artifactId = "beanshell-maven-plugin"
            version = "1.4"
            setConfiguration(configuration)
        }

    private fun sub(tag: String, original: String, instrumented: String) =
        BshScriptRewriter.Substitution(tag, original, instrumented)

    @Test
    fun replacesPluginLevelScriptAndAddsSystemScopedCallback() {
        val plugin = beanshellPlugin(config(element("script", "print(1);")))

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("script", "print(1);", "INSTRUMENTED")), callbackJar)

        assertEquals(1, replaced)
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
        plugin.addExecution(PluginExecution().apply { id = "run"; setConfiguration(config(element("script", "old;"))) })

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("script", "old;", "NEW")), callbackJar)

        assertEquals(1, replaced)
        assertEquals("NEW", (plugin.executions[0].configuration as Xpp3Dom).getChild("script").value)
        assertEquals(1, plugin.dependencies.size)
    }

    @Test
    fun replacesMultipleDistinctScriptsInOneConfigurationByContent() {
        // Two same-named nodes in one <configuration>, each matched to its own instrumented text.
        val plugin = beanshellPlugin(config(element("condition", "a;"), element("condition", "b;")))

        val replaced = BshScriptRewriter().instrumentPlugin(
            plugin,
            listOf(sub("condition", "a;", "A_INSTR"), sub("condition", "b;", "B_INSTR")),
            callbackJar,
        )

        assertEquals(2, replaced)
        val conditions = (plugin.configuration as Xpp3Dom).getChildren("condition").map { it.value }
        assertTrue(conditions.contains("A_INSTR"))
        assertTrue(conditions.contains("B_INSTR"))
        assertEquals("callback added once", 1, plugin.dependencies.size)
    }

    @Test
    fun replacesNestedScript() {
        // Enforcer-style nesting: <configuration><rules><evaluateBeanshell><condition>.
        val condition = element("condition", "x > 0")
        val nested = config(
            Xpp3Dom("rules").apply {
                addChild(Xpp3Dom("evaluateBeanshell").apply { addChild(condition) })
            },
        )
        val plugin = beanshellPlugin(nested).apply { artifactId = "maven-enforcer-plugin" }

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("condition", "x > 0", "CHECKED")), callbackJar)

        assertEquals(1, replaced)
        assertEquals("CHECKED", condition.value)
        assertEquals(1, plugin.dependencies.size)
    }

    @Test
    fun matchesScriptWhoseInterpolatedValueDiffersOnlyInsidePlaceholders() {
        // Maven interpolates ${...} in plugin config before afterProjectsRead, so the value we see is
        // already expanded; the manifest still holds the raw script the IDE captured. It must match.
        val expanded = "version = \"1.0.0\";\ngroupId = \"com.example.bsh\";\nok"
        val raw = "version = \"\${project.version}\";\ngroupId = \"\${project.groupId}\";\nok"
        val condition = element("condition", expanded)
        val plugin = beanshellPlugin(config(condition)).apply { artifactId = "maven-enforcer-plugin" }

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("condition", raw, "INSTR")), callbackJar)

        assertEquals(1, replaced)
        assertEquals("INSTR", condition.value)
    }

    @Test
    fun doesNotMatchDifferentScriptEvenWhenOriginalHasPlaceholders() {
        // The ${...} tolerance must not turn into a wildcard that swallows an unrelated script.
        val condition = element("condition", "somethingElse();")
        val plugin = beanshellPlugin(config(condition)).apply { artifactId = "maven-enforcer-plugin" }
        val raw = "version = \"\${project.version}\";\nok"

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("condition", raw, "INSTR")), callbackJar)

        assertEquals(0, replaced)
        assertEquals("somethingElse();", condition.value)
    }

    @Test
    fun leavesPluginUntouchedWhenNoScriptMatches() {
        val plugin = beanshellPlugin(config(element("script", "print(1);")))

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("script", "different;", "NEW")), callbackJar)

        assertEquals(0, replaced)
        assertEquals("print(1);", (plugin.configuration as Xpp3Dom).getChild("script").value)
        assertTrue(plugin.dependencies.isEmpty())
    }

    @Test
    fun toleratesWhitespaceDifferencesWhenMatching() {
        // Xpp3Dom trims leading/trailing whitespace; matching compares trimmed content.
        val plugin = beanshellPlugin(config(element("script", "\n  print(1);\n  ")))

        val replaced = BshScriptRewriter().instrumentPlugin(plugin, listOf(sub("script", "print(1);", "OK")), callbackJar)

        assertEquals(1, replaced)
        assertEquals("OK", (plugin.configuration as Xpp3Dom).getChild("script").value)
    }
}
