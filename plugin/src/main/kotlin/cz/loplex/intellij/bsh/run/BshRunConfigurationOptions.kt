package cz.loplex.intellij.bsh.run

import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.openapi.components.StoredProperty

class BshRunConfigurationOptions : RunConfigurationOptions() {
    private val scriptPathProp: StoredProperty<String?> =
        string("").provideDelegate(this, "scriptPath")
    private val programArgumentsProp: StoredProperty<String?> =
        string("").provideDelegate(this, "programArguments")
    private val workingDirectoryProp: StoredProperty<String?> =
        string("").provideDelegate(this, "workingDirectory")
    private val interpreterClasspathProp: StoredProperty<String?> =
        string("").provideDelegate(this, "interpreterClasspath")
    private val jrePathProp: StoredProperty<String?> =
        string("").provideDelegate(this, "jrePath")
    private val instrumentationProp: StoredProperty<String?> =
        string("").provideDelegate(this, "instrumentation")

    var scriptPath: String
        get() = scriptPathProp.getValue(this).orEmpty()
        set(value) = scriptPathProp.setValue(this, value)

    var programArguments: String
        get() = programArgumentsProp.getValue(this).orEmpty()
        set(value) = programArgumentsProp.setValue(this, value)

    var workingDirectory: String
        get() = workingDirectoryProp.getValue(this).orEmpty()
        set(value) = workingDirectoryProp.setValue(this, value)

    /** Classpath entries (jar or classes directory) that provide `bsh.Interpreter`. */
    var interpreterClasspath: String
        get() = interpreterClasspathProp.getValue(this).orEmpty()
        set(value) = interpreterClasspathProp.setValue(this, value)

    /** Optional JRE home; defaults to the IDE's runtime when blank. */
    var jrePath: String
        get() = jrePathProp.getValue(this).orEmpty()
        set(value) = jrePathProp.setValue(this, value)

    /**
     * Name of the `BshInstrumentationMode` to debug with; blank means the default.
     *
     * Stored as the enum's name rather than its ordinal, so reordering the enum cannot silently
     * repoint every saved configuration at the other mechanism.
     */
    var instrumentation: String
        get() = instrumentationProp.getValue(this).orEmpty()
        set(value) = instrumentationProp.setValue(this, value)
}
