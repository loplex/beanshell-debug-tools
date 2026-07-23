package cz.loplex.intellij.bsh.mavenext;

import java.util.List;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Pure model surgery for the debug extension: substitutes the inline BeanShell script with the
 * IDE-instrumented text and adds the debug-agent callback jar as a system-scoped plugin dependency.
 *
 * <p>Deliberately depends only on the Maven model (plugin/execution/dependency) and plexus-utils
 * ({@link Xpp3Dom}) — no maven-core — so it can be unit-tested without the full Maven runtime.
 */
final class BshScriptRewriter {

    static final String CALLBACK_GROUP_ID = "cz.loplex.bsh.debug";
    static final String CALLBACK_ARTIFACT_ID = "callback";

    /**
     * Rewrites the {@code tag} element (at plugin level and inside every execution) of a plugin with
     * {@code instrumented}, and — if anything was replaced — adds {@code callbackJar} as a
     * system-scoped dependency. Returns whether the plugin was touched.
     */
    boolean instrumentPlugin(Plugin plugin, String tag, String instrumented, String callbackJar) {
        boolean replaced = replaceScript(plugin.getConfiguration(), tag, instrumented);
        for (PluginExecution execution : plugin.getExecutions()) {
            replaced |= replaceScript(execution.getConfiguration(), tag, instrumented);
        }
        if (replaced) {
            addCallbackDependency(plugin, callbackJar);
        }
        return replaced;
    }

    private boolean replaceScript(Object configuration, String tag, String instrumented) {
        if (!(configuration instanceof Xpp3Dom)) {
            return false;
        }
        Xpp3Dom node = ((Xpp3Dom) configuration).getChild(tag);
        if (node == null || node.getValue() == null) {
            return false;
        }
        node.setValue(instrumented);
        return true;
    }

    private void addCallbackDependency(Plugin plugin, String callbackJar) {
        List<Dependency> dependencies = plugin.getDependencies();
        for (Dependency existing : dependencies) {
            if (callbackJar.equals(existing.getSystemPath())) {
                return;
            }
        }
        Dependency dependency = new Dependency();
        dependency.setGroupId(CALLBACK_GROUP_ID);
        dependency.setArtifactId(CALLBACK_ARTIFACT_ID);
        dependency.setVersion("0");
        dependency.setScope("system");
        dependency.setSystemPath(callbackJar);
        dependencies.add(dependency);
    }
}
