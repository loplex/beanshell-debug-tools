package cz.loplex.intellij.bsh.reference

/**
 * Detects whether Java PSI is available, without referencing any Java-plugin
 * class directly (so this stays loadable when the Java plugin is absent).
 */
object BshJavaSupport {
    private val available: Boolean by lazy {
        runCatching { Class.forName("com.intellij.psi.JavaPsiFacade") }.isSuccess
    }

    fun isAvailable(): Boolean = available
}
