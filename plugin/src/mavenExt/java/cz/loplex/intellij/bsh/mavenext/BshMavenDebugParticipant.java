package cz.loplex.intellij.bsh.mavenext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>{@code bsh.debug.manifest} — path to a manifest file with one tab-separated line per
 *       inline script: {@code artifactId\ttag\toriginalScriptFile\tinstrumentedScriptFile}. The
 *       original text locates the exact node to rewrite; the instrumented text (with
 *       {@code BshDebugAgent.step(...)} calls carrying pom.xml line numbers) replaces it;</li>
 *   <li>{@code bsh.debug.callback.jar} — path to the jar providing the debug-agent callback,
 *       added to each rewritten plugin as a {@code system}-scoped dependency so it resolves inside
 *       the plugin realm without touching the local repository.</li>
 * </ul>
 *
 * <p>Before the build runs, this participant rewrites every listed script node and adds the callback
 * dependency. It is deliberately a dumb substituter: no BeanShell, no parsing — the IDE is the single
 * source of truth for the instrumentation and the pom line numbers baked into it.
 */
@Named("bsh-maven-debug-participant")
@Singleton
public class BshMavenDebugParticipant extends AbstractMavenLifecycleParticipant {

    static final String PROP_MANIFEST = "bsh.debug.manifest";
    static final String PROP_CALLBACK_JAR = "bsh.debug.callback.jar";

    @Override
    public void afterProjectsRead(MavenSession session) throws MavenExecutionException {
        String manifest = System.getProperty(PROP_MANIFEST);
        String callbackJar = System.getProperty(PROP_CALLBACK_JAR);
        if (manifest == null || callbackJar == null) {
            return;
        }

        Map<String, List<BshScriptRewriter.Substitution>> byArtifact = readManifest(manifest);
        if (byArtifact.isEmpty()) {
            return;
        }

        BshScriptRewriter rewriter = new BshScriptRewriter();
        for (MavenProject project : session.getAllProjects()) {
            if (project.getBuild() == null) {
                continue;
            }
            for (Plugin plugin : project.getBuild().getPlugins()) {
                List<BshScriptRewriter.Substitution> substitutions = byArtifact.get(plugin.getArtifactId());
                if (substitutions != null) {
                    rewriter.instrumentPlugin(plugin, substitutions, callbackJar);
                }
            }
        }
    }

    /** Parses the manifest into substitutions grouped by the owning plugin's artifactId. */
    private Map<String, List<BshScriptRewriter.Substitution>> readManifest(String manifest)
            throws MavenExecutionException {
        Map<String, List<BshScriptRewriter.Substitution>> byArtifact = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(Paths.get(manifest), StandardCharsets.UTF_8)) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length != 4) {
                    continue;
                }
                String artifactId = parts[0];
                String tag = parts[1];
                String original = new String(Files.readAllBytes(Paths.get(parts[2])), StandardCharsets.UTF_8);
                String instrumented = new String(Files.readAllBytes(Paths.get(parts[3])), StandardCharsets.UTF_8);

                List<BshScriptRewriter.Substitution> substitutions =
                        byArtifact.computeIfAbsent(artifactId, k -> new ArrayList<>());
                substitutions.add(new BshScriptRewriter.Substitution(tag, original, instrumented));
            }
        } catch (IOException ex) {
            throw new MavenExecutionException("Cannot read BeanShell debug manifest: " + manifest, ex);
        }
        return byArtifact;
    }
}
