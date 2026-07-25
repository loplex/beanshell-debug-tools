package cz.loplex.intellij.bsh.debug.maven

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.impl.RunDialog
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.jetbrains.idea.maven.execution.MavenRunnerParameters
import org.jetbrains.idea.maven.utils.MavenDataKeys
import org.jetbrains.idea.maven.utils.actions.MavenActionUtil

/**
 * Creates a [BshMavenRunConfiguration] from the goal/phase selected in the Maven tool window —
 * the BeanShell-aware sibling of Maven's own "Create Run Configuration…". The new configuration
 * runs exactly like a normal Maven build, but in Debug mode it also stops on breakpoints inside
 * inline BeanShell `<script>` blocks the build evaluates.
 */
class BshMavenCreateRunConfigurationAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val goals = MavenDataKeys.MAVEN_GOALS.getData(e.dataContext)
        e.presentation.isEnabledAndVisible =
            MavenActionUtil.getProject(e.dataContext) != null && !goals.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val context = e.dataContext
        val project = MavenActionUtil.getProject(context) ?: return
        val goals = MavenDataKeys.MAVEN_GOALS.getData(context)?.takeIf { it.isNotEmpty() } ?: return
        val mavenProject = MavenActionUtil.getMavenProject(context) ?: return

        val type = ConfigurationTypeUtil.findConfigurationType(BshMavenRunConfigurationType::class.java)
        val factory = type.configurationFactories.first()
        val runManager = RunManager.getInstance(project)

        val name = "${mavenProject.mavenId.artifactId ?: "maven"} [${goals.joinToString(" ")}] (bsh)"
        val settings = runManager.createConfiguration(name, factory)
        (settings.configuration as BshMavenRunConfiguration).setRunnerParameters(
            MavenRunnerParameters(false, mavenProject.directory, mavenProject.file.name, goals, emptyList<String>(), emptyList<String>()),
        )

        if (RunDialog.editConfiguration(project, settings, "Create BeanShell-Enhanced Maven Configuration")) {
            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings
        }
    }
}
