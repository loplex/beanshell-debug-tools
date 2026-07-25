package cz.loplex.intellij.bsh.mavenext;

import java.util.List;
import java.util.regex.Pattern;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Pure model surgery for the debug extension: substitutes each inline BeanShell script with its
 * IDE-instrumented text and adds the debug-agent callback jar as a system-scoped plugin dependency.
 *
 * <p>A script is located by its owning tag name and its (trimmed) original content, so any number of
 * scripts is handled wherever they sit — plugin-level config, several {@code <execution>}s, or even
 * repeated within one configuration — and {@code nested} elements (e.g. the enforcer's
 * {@code <rules><evaluateBeanshell><condition>}) are found by a recursive walk. Each substitution is
 * consumed once; identical scripts at different locations fall back to encounter order. Content
 * matching tolerates Maven's {@code ${...}} interpolation (see {@link #matches}): the effective model
 * this runs against has already expanded property references the IDE captured raw.
 *
 * <p>Deliberately depends only on the Maven model (plugin/execution/dependency) and plexus-utils
 * ({@link Xpp3Dom}) — no maven-core — so it can be unit-tested without the full Maven runtime.
 */
final class BshScriptRewriter {

    static final String CALLBACK_GROUP_ID = "cz.loplex.bsh.debug";
    static final String CALLBACK_ARTIFACT_ID = "callback";

    /** One script to replace: match a node named {@link #tag} whose trimmed value equals {@link #original}. */
    static final class Substitution {
        final String tag;
        final String original;
        final String instrumented;
        boolean applied;

        Substitution(String tag, String original, String instrumented) {
            this.tag = tag;
            this.original = original;
            this.instrumented = instrumented;
        }
    }

    /**
     * Applies {@code substitutions} to {@code plugin} (its plugin-level configuration and every
     * execution's configuration). Adds the callback dependency once if anything was replaced.
     * Returns the number of script nodes replaced.
     */
    int instrumentPlugin(Plugin plugin, List<Substitution> substitutions, String callbackJar) {
        int replaced = replaceIn(plugin.getConfiguration(), substitutions);
        for (PluginExecution execution : plugin.getExecutions()) {
            replaced += replaceIn(execution.getConfiguration(), substitutions);
        }
        if (replaced > 0) {
            addCallbackDependency(plugin, callbackJar);
        }
        return replaced;
    }

    private int replaceIn(Object configuration, List<Substitution> substitutions) {
        if (!(configuration instanceof Xpp3Dom)) {
            return 0;
        }
        return walk((Xpp3Dom) configuration, substitutions);
    }

    private int walk(Xpp3Dom node, List<Substitution> substitutions) {
        int replaced = 0;
        for (Xpp3Dom child : node.getChildren()) {
            String value = child.getValue();
            if (value != null) {
                Substitution match = findMatch(substitutions, child.getName(), value);
                if (match != null) {
                    child.setValue(match.instrumented);
                    match.applied = true;
                    replaced++;
                }
            }
            replaced += walk(child, substitutions);
        }
        return replaced;
    }

    private Substitution findMatch(List<Substitution> substitutions, String tag, String value) {
        String trimmed = value.trim();
        for (Substitution substitution : substitutions) {
            if (!substitution.applied
                    && substitution.tag.equals(tag)
                    && matches(substitution.original.trim(), trimmed)) {
                return substitution;
            }
        }
        return null;
    }

    /**
     * Whether {@code candidate} is the {@code original} script, allowing for Maven having expanded any
     * {@code ${...}} property references the IDE could not resolve. Maven interpolates POM {@code ${...}}
     * expressions in plugin configuration <em>before</em> {@code afterProjectsRead}, so the value seen at
     * rewrite time can differ from the raw script the IDE captured — but only inside those placeholders
     * (e.g. {@code "${project.version}"} becomes {@code "1.0.0"}). A script with no {@code ${...}} must
     * still match by exact content, so an unrelated script is never rewritten by mistake.
     */
    private boolean matches(String original, String candidate) {
        if (original.equals(candidate)) {
            return true;
        }
        if (!original.contains("${")) {
            return false;
        }
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < original.length()) {
            int start = original.indexOf("${", i);
            int end = start < 0 ? -1 : original.indexOf('}', start);
            if (end < 0) {
                regex.append(Pattern.quote(original.substring(i)));
                break;
            }
            if (start > i) {
                regex.append(Pattern.quote(original.substring(i, start)));
            }
            regex.append(".*"); // the interpolated value of one ${...} reference
            i = end + 1;
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(candidate).matches();
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
