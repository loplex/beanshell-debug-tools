package cz.loplex.intellij.bsh.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import cz.loplex.intellij.bsh.BshFileType

/**
 * Lets the user run a `.bsh` file straight from its editor tab or the project
 * view (Run '<file>'), creating a [BshRunConfiguration] pre-filled with the
 * script path and working directory.
 */
class BshRunConfigurationProducer : LazyRunConfigurationProducer<BshRunConfiguration>() {

    override fun getConfigurationFactory(): ConfigurationFactory =
        ConfigurationTypeUtil.findConfigurationType(BshRunConfigurationType::class.java)
            .configurationFactories[0]

    override fun setupConfigurationFromContext(
        configuration: BshRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        if (file.fileType != BshFileType) return false

        configuration.scriptPath = file.path
        configuration.name = file.name
        file.parent?.let { configuration.workingDirectory = it.path }
        return true
    }

    override fun isConfigurationFromContext(
        configuration: BshRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = context.location?.virtualFile ?: return false
        return file.fileType == BshFileType && file.path == configuration.scriptPath
    }
}
