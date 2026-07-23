package cz.loplex.intellij.bsh.mavenext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.AbstractMavenLifecycleParticipant;
import org.apache.maven.MavenExecutionException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;

/**
 * Maven core extension that makes an inline BeanShell {@code <script>} debuggable.
 *
 * <p>The IDE launches Maven with this jar on {@code -Dmaven.ext.class.path} and the
 * following system properties:
 * <ul>
 *   <li>{@code bsh.debug.target} — {@code artifactId:tag} identifying the plugin and the
 *       configuration element that holds the inline script (e.g. {@code beanshell-maven-plugin:script});</li>
 *   <li>{@code bsh.debug.script.file} — path to a temp file holding the already-instrumented
 *       script (the IDE inserts the {@code BshDebugAgent.step(...)} calls and owns the line map);</li>
 *   <li>{@code bsh.debug.callback.jar} — path to the jar providing the debug-agent callback,
 *       added to the target plugin as a {@code system}-scoped dependency so it resolves inside
 *       the plugin realm without touching the local repository.</li>
 * </ul>
 *
 * <p>Before the build runs, this participant finds the target configuration node, replaces its
 * value with the instrumented script, and adds the callback dependency. It is deliberately a
 * dumb substituter: no BeanShell, no parsing — the IDE is the single source of truth for both
 * the instrumentation and the line mapping.
 */
@Named("bsh-maven-debug-participant")
@Singleton
public class BshMavenDebugParticipant extends AbstractMavenLifecycleParticipant {

    static final String PROP_TARGET = "bsh.debug.target";
    static final String PROP_SCRIPT_FILE = "bsh.debug.script.file";
    static final String PROP_CALLBACK_JAR = "bsh.debug.callback.jar";

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String target = System.getProperty(PROP_TARGET);
        String scriptFile = System.getProperty(PROP_SCRIPT_FILE);
        String callbackJar = System.getProperty(PROP_CALLBACK_JAR);
        if (target == null || scriptFile == null || callbackJar == null) {
            return;
        }
        int colon = target.indexOf(':');
        if (colon <= 0 || colon >= target.length() - 1) {
            return;
        }
        String artifactId = target.substring(0, colon);
        String tag = target.substring(colon + 1);

        String instrumented;
        try {
            instrumented = new String(Files.readAllBytes(Paths.get(scriptFile)), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new MavenExecutionException("Cannot read instrumented BeanShell script: " + scriptFile, ex);
        }

        BshScriptRewriter rewriter = new BshScriptRewriter();
        for (MavenProject project : session.getAllProjects()) {
            if (project.getBuild() == null) {
                continue;
            }
            for (Plugin plugin : project.getBuild().getPlugins()) {
                if (artifactId.equals(plugin.getArtifactId())) {
                    rewriter.instrumentPlugin(plugin, tag, instrumented, callbackJar);
                }
            }
        }
    }
}
