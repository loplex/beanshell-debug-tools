package cz.loplex.intellij.bsh.run

import com.intellij.execution.configurations.ConfigurationTypeBase
import cz.loplex.intellij.bsh.BshIcons

class BshRunConfigurationType : ConfigurationTypeBase(
    ID,
    "BeanShell",
    "Runs a BeanShell script",
    BshIcons.FILE,
) {
    init {
        addFactory(BshConfigurationFactory(this))
    }

    companion object {
        const val ID = "BshRunConfiguration"
    }
}
