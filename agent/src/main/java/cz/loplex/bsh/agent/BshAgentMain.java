package cz.loplex.bsh.agent;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Agent entry point, for both {@code -javaagent} at startup and dynamic attach.
 *
 * <p><b>Bootstrap visibility.</b> The instrumented {@code bsh.*} classes contain a call to
 * {@link cz.loplex.bsh.hook.BshHook}, so that class has to be resolvable from whatever
 * classloader loaded BeanShell. In a plain application that is the system loader, but in a
 * Maven build BeanShell lives in a plugin classloader that cannot see the application
 * classpath at all. The only loader every other loader delegates to is the bootstrap loader,
 * so the agent jar is appended there.
 *
 * <p><b>Why this class must never reference {@code BshHook}.</b> Appending the agent jar to
 * the bootstrap search does not remove it from the system classpath, so both loaders can
 * define the hook. If this class touched {@code BshHook}, the system loader would define
 * copy #1 while instrumented BeanShell code resolved copy #2 from bootstrap — two sets of
 * static state, and any configuration applied here would be invisible to the copy that
 * actually runs. Configuration therefore travels through system properties, which are
 * loader-independent, and the hook class name appears only as a string constant (here and
 * in {@link EvalTransformer}).
 */
public final class BshAgentMain {

    /** Set once the agent has installed itself, so a double attach is a no-op. */
    private static final String INSTALLED_PROPERTY = "bsh.debug.agent.installed";

    private BshAgentMain() {
    }

    /** {@code -javaagent:bsh-debug-agent.jar=<options>} */
    public static void premain(String options, Instrumentation inst) {
        install(options, inst);
    }

    /** Dynamic attach into an already running JVM. */
    public static void agentmain(String options, Instrumentation inst) {
        install(options, inst);
    }

    private static void install(String options, Instrumentation inst) {
        if (System.getProperty(INSTALLED_PROPERTY) != null) {
            return;
        }

        applyOptions(options);

        if (!appendSelfToBootstrap(inst)) {
            // Without bootstrap visibility the injected call would throw
            // NoClassDefFoundError inside the interpreter, which is far worse than
            // not debugging at all.
            System.err.println("[bsh-agent] cannot publish the hook to the bootstrap classloader; not instrumenting");
            return;
        }

        System.setProperty(INSTALLED_PROPERTY, "true");
        inst.addTransformer(new EvalTransformer(), true);
        retransformLoadedBshClasses(inst);
    }

    /**
     * Turns {@code key=value,key=value} agent options into system properties, so that
     * {@code -javaagent:agent.jar=port=5005} is equivalent to {@code -Dbsh.debug.port=5005}.
     * Keys without a {@code bsh.debug.} prefix get one; existing properties win, so an
     * explicit {@code -D} on the command line is never overwritten.
     */
    private static void applyOptions(String options) {
        if (options == null || options.trim().isEmpty()) {
            return;
        }
        for (String pair : options.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!key.startsWith("bsh.debug.")) {
                key = "bsh.debug." + key;
            }
            if (System.getProperty(key) == null) {
                System.setProperty(key, value);
            }
        }
    }

    /** Publishes this jar — hook class included — to the bootstrap classloader. */
    private static boolean appendSelfToBootstrap(Instrumentation inst) {
        JarFile jar = null;
        try {
            File self = new File(BshAgentMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!self.isFile()) {
                // Running from a directory (e.g. an IDE run configuration) rather than a jar.
                // appendToBootstrapClassLoaderSearch only accepts jars.
                System.err.println("[bsh-agent] agent is not packaged as a jar: " + self);
                return false;
            }
            jar = new JarFile(self);
            inst.appendToBootstrapClassLoaderSearch(jar);
            return true;
        } catch (Throwable t) {
            System.err.println("[bsh-agent] failed to reach the bootstrap classloader: " + t);
            if (jar != null) {
                try {
                    jar.close();
                } catch (Throwable ignored) {
                    // nothing useful to do
                }
            }
            return false;
        }
    }

    /**
     * On dynamic attach BeanShell may already be loaded, in which case registering a
     * transformer alone would never see its classes. Retransform whatever is already there.
     * Startup ({@code -javaagent}) normally finds nothing loaded yet, so this is a no-op.
     */
    private static void retransformLoadedBshClasses(Instrumentation inst) {
        if (!inst.isRetransformClassesSupported()) {
            return;
        }
        List<Class<?>> targets = new ArrayList<Class<?>>();
        for (Class<?> candidate : inst.getAllLoadedClasses()) {
            if (isBshClass(candidate) && inst.isModifiableClass(candidate)) {
                targets.add(candidate);
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        try {
            inst.retransformClasses(targets.toArray(new Class<?>[0]));
        } catch (Throwable t) {
            // Retransforming in bulk fails as a unit, so fall back to one at a time and keep
            // whatever succeeds: a partially instrumented interpreter still debugs the paths
            // it covers.
            for (Class<?> target : targets) {
                try {
                    inst.retransformClasses(target);
                } catch (Throwable ignored) {
                    // this class stays uninstrumented
                }
            }
        }
    }

    private static boolean isBshClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("bsh.") && name.indexOf('.', 4) < 0;
    }
}
