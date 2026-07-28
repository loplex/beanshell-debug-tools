package cz.loplex.intellij.bsh.run

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.project.Project

class BshConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
    override fun getId(): String = BshRunConfigurationType.ID

    override fun createTemplateConfiguration(project: Project): RunConfiguration =
        BshRunConfiguration(project, this, "BeanShell")

    override fun getOptionsClass(): Class<out BaseState> = BshRunConfigurationOptions::class.java
}
