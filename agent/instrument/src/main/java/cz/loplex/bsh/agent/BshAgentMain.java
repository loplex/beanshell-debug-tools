package cz.loplex.bsh.agent;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
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
 * so the hook is published there.
 *
 * <p>It travels as a <b>nested jar</b> inside this one rather than as loose classes, which is
 * what keeps it to a single copy: the system classloader has no hook classes to define, so the
 * bootstrap copy is the only one in the JVM. Two copies would mean two sets of static state,
 * with the instrumented interpreter using one and the agent configuring the other. For the same
 * reason configuration travels through system properties, which are loader-independent, and the
 * hook type is never named in agent code — only as the string constant in
 * {@link EvalTransformer}.
 */
public final class BshAgentMain {

    /** Set once the agent has installed itself, so a double attach is a no-op. */
    private static final String INSTALLED_PROPERTY = "bsh.debug.agent.installed";

    /** Name of the nested jar holding the hook, placed at the root of this jar by the build. */
    private static final String HOOK_JAR_RESOURCE = "/bsh-debug-hook.jar";

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

        if (!publishHookToBootstrap(inst)) {
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

    /**
     * Extracts the nested hook jar and publishes it to the bootstrap classloader.
     *
     * <p>{@code appendToBootstrapClassLoaderSearch} takes a {@link JarFile}, so a jar nested
     * inside another cannot be handed over directly and has to be unpacked to a real file first.
     * The JVM keeps that file open and loads from it lazily for the rest of the run, so it must
     * not be deleted here — only marked for deletion at exit.
     */
    private static boolean publishHookToBootstrap(Instrumentation inst) {
        InputStream source = BshAgentMain.class.getResourceAsStream(HOOK_JAR_RESOURCE);
        if (source == null) {
            System.err.println("[bsh-agent] " + HOOK_JAR_RESOURCE + " is missing from the agent jar");
            return false;
        }
        try {
            File extracted = File.createTempFile("bsh-debug-hook", ".jar");
            extracted.deleteOnExit();
            try (OutputStream target = Files.newOutputStream(extracted.toPath())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) > 0) {
                    target.write(buffer, 0, read);
                }
            }
            inst.appendToBootstrapClassLoaderSearch(new JarFile(extracted));
            return true;
        } catch (Throwable t) {
            System.err.println("[bsh-agent] failed to publish the hook to the bootstrap classloader: " + t);
            return false;
        } finally {
            try {
                source.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
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
        List<Class<?>> targets = new ArrayList<>();
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
