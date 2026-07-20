package cz.loplex.intellij.bsh.injection

import com.intellij.openapi.diagnostic.logger

/**
 * The curated list of Maven plugins whose `<configuration>` carries an inline
 * BeanShell script, loaded from `/beanshell/maven-scripts.txt` on the classpath
 * so it can be edited without touching code.
 */
object MavenBeanshellScripts {

    /** A `<configuration>` property that holds an inline BeanShell script. */
    data class Property(val tag: String, val directChildOfConfiguration: Boolean)

    private const val RESOURCE = "/beanshell/maven-scripts.txt"
    private val LOG = logger<MavenBeanshellScripts>()

    private val byArtifact: Map<String, List<Property>> by lazy { load() }

    fun propertiesFor(artifactId: String): List<Property> = byArtifact[artifactId].orEmpty()

    private fun load(): Map<String, List<Property>> {
        val stream = javaClass.getResourceAsStream(RESOURCE)
        if (stream == null) {
            LOG.warn("BeanShell Maven injection list not found on classpath: $RESOURCE")
            return emptyMap()
        }
        val result = LinkedHashMap<String, MutableList<Property>>()
        stream.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split('|').map { it.trim() }
                if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                    LOG.warn("Ignoring malformed BeanShell Maven injection entry: $raw")
                    continue
                }
                val direct = !parts.getOrNull(2).equals("nested", ignoreCase = true)
                result.getOrPut(parts[0]) { ArrayList() }.add(Property(parts[1], direct))
            }
        }
        return result
    }
}
