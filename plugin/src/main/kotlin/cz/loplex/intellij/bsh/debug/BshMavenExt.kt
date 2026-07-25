package cz.loplex.intellij.bsh.debug

import com.intellij.execution.ExecutionException
import com.intellij.openapi.util.io.FileUtil
import java.io.File

/**
 * Locates the bundled Maven core extension jar (`bsh-maven-ext.jar`). The jar is shipped
 * as a plugin resource (see the `mavenExt` source set in `build.gradle.kts`) and extracted
 * to a temp file on first use so it can be handed to Maven via `-Dmaven.ext.class.path`.
 */
object BshMavenExt {

    private const val RESOURCE = "/beanshell/bsh-maven-ext.jar"

    @Volatile
    private var cached: String? = null

    @Throws(ExecutionException::class)
    fun extensionJarPath(): String {
        cached?.let { if (File(it).isFile) return it }
        synchronized(this) {
            cached?.let { if (File(it).isFile) return it }
            val stream = javaClass.getResourceAsStream(RESOURCE)
                ?: throw ExecutionException("Bundled Maven extension jar not found on the plugin classpath: $RESOURCE")
            val target = FileUtil.createTempFile("bsh-maven-ext", ".jar", true)
            stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            cached = target.absolutePath
            return target.absolutePath
        }
    }
}
