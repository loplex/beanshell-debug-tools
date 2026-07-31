package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.project.Project
import cz.loplex.intellij.bsh.BshIcons

/**
 * Run-configuration type backing [BshMavenRunConfiguration]. Registered only through the
 * optional `bsh-maven.xml` descriptor, so it exists solely when the IntelliJ Maven plugin
 * is present.
 */
class BshMavenRunConfigurationType : ConfigurationTypeBase(
    ID,
    "BeanShell-enhanced Maven",
    "Maven run configuration that also debugs inline BeanShell scripts",
    BshIcons.MAVEN,
) {
    init {
        addFactory(Factory(this))
    }

    private class Factory(type: ConfigurationType) : ConfigurationFactory(type) {
        override fun getId(): String = ID

        override fun createTemplateConfiguration(project: Project): RunConfiguration =
            BshMavenRunConfiguration(project, this, "")
    }

    companion object {
        const val ID = "BshMavenRunConfiguration"
    }
}
