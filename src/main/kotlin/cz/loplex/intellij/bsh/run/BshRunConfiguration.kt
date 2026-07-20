package cz.loplex.intellij.bsh.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import java.io.File

class BshRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<BshRunConfigurationOptions>(project, factory, name) {

    public override fun getOptions(): BshRunConfigurationOptions =
        super.getOptions() as BshRunConfigurationOptions

    var scriptPath: String
        get() = options.scriptPath
        set(value) { options.scriptPath = value }

    var programArguments: String
        get() = options.programArguments
        set(value) { options.programArguments = value }

    var workingDirectory: String
        get() = options.workingDirectory
        set(value) { options.workingDirectory = value }

    var interpreterClasspath: String
        get() = options.interpreterClasspath
        set(value) { options.interpreterClasspath = value }

    var jrePath: String
        get() = options.jrePath
        set(value) { options.jrePath = value }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = BshSettingsEditor()

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState =
        BshCommandLineState(environment, this)

    @Throws(RuntimeConfigurationException::class)
    override fun checkConfiguration() {
        if (scriptPath.isBlank()) {
            throw RuntimeConfigurationError("No BeanShell script specified")
        }
        if (!File(scriptPath).isFile) {
            throw RuntimeConfigurationError("Script does not exist: $scriptPath")
        }
        // A blank classpath falls back to the BeanShell interpreter bundled with the plugin.
    }
}
