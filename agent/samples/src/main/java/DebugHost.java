/**
 * Embeds BeanShell the way a third-party library does, so the agent under test
 * sees a realistic entry pattern rather than the CLI one.
 *
 * Why this matters: running a script with `java bsh.Interpreter foo.bsh` goes
 * through Interpreter.run() (Interpreter.java:471). Everything a library does
 * instead goes through Interpreter.eval(Reader, NameSpace, String)
 * (Interpreter.java:659). Those are two SEPARATE loops -- an agent that hooks
 * only one will look like it works in the CLI and do nothing in production, or
 * vice versa. Scenario 1 below covers eval(); run the CLI runner for the other.
 *
 * Run from the repository root:
 *   ./gradlew :agent:samples:runHost             # all scenarios
 *   ./gradlew :agent:samples:runHostWithAgent    # the same, instrumented
 *
 * Both tasks set the working directory to scripts/, because the fixtures source()
 * each other by bare name. To pick a single scenario, append --args='3'.
 */

import bsh.EvalError;
import bsh.Interpreter;
import bsh.NameSpace;
import bsh.This;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DebugHost {

    /** Directory holding the .bsh fixtures. Override with -Dsamples=/path. */
    private static final String SAMPLES =
            System.getProperty("samples", "agent/samples/scripts");

    public static void main(String[] args) throws Exception {
        int only = args.length > 0 ? Integer.parseInt(args[0]) : 0;

        if (only == 0 || only == 1) scenario1_sourceFile();
        if (only == 0 || only == 2) scenario2_evalString();
        if (only == 0 || only == 3) scenario3_callScriptMethodFromJava();
        if (only == 0 || only == 4) scenario4_scriptImplementsInterface();
        if (only == 0 || only == 5) scenario5_callOnOtherThread();
        if (only == 0 || only == 6) scenario6_scriptedClassAsJavaObject();
        if (only == 0 || only == 7) scenario7_sharedNamespaceTwoInterpreters();
        if (only == 0 || only == 8) scenario8_errorHandling();

        System.out.println("\n[host] done");
    }

    private static Interpreter fresh() {
        Interpreter i = new Interpreter();
        // Uncomment to see the built-in tracing for comparison with your agent.
        // Interpreter.TRACE = true;
        return i;
    }

    private static String path(String name) {
        return new File(SAMPLES, name).getPath();
    }

    private static void banner(String s) {
        System.out.println("\n[host] --- " + s + " ---");
    }

    /**
     * 1. Plain source() of a file. This is the common library pattern and it
     * lands in Interpreter.eval(Reader, NameSpace, String) -- NOT in run().
     */
    private static void scenario1_sourceFile() throws Exception {
        banner("1 source() a file  [Interpreter.java:659]");
        Interpreter i = fresh();
        i.source(path("01_basic.bsh"));
    }

    /**
     * 2. eval() of a string, twice with identical text. Each call reparses:
     * two distinct ASTs for the same source. Breakpoints must therefore be
     * keyed on (sourceFile, line), never on node identity.
     */
    private static void scenario2_evalString() throws EvalError {
        banner("2 eval(String) twice  [reparse each time]");
        Interpreter i = fresh();
        String code = "twice(n) { return n * 2; } twice(21);";
        System.out.println("[host] first  -> " + i.eval(code));
        System.out.println("[host] second -> " + i.eval(code));
    }

    /**
     * 3. Java calls a script-defined method by name. Goes through
     * NameSpace.invokeMethod -> This.invokeMethod -> BshMethod.invoke, with
     * callerInfo == null, so the caller node becomes SimpleNode.JAVACODE and
     * the outermost frame has line == -1.
     */
    private static void scenario3_callScriptMethodFromJava() throws EvalError {
        banner("3 Java -> script method by name  [caller node = JAVACODE, line -1]");
        Interpreter i = fresh();
        i.eval("compute(a, b) { inner = a * b; return inner + 1; }");
        NameSpace global = i.getNameSpace();
        Object result = global.invokeMethod("compute", new Object[]{6, 7}, i);
        System.out.println("[host] compute(6,7) = " + result);
    }

    /**
     * 4. Script object handed to Java as an interface (XThis + Proxy). Java
     * invokes it with no script context at all -- the classic "no caller node"
     * case, and the one most likely to make a naive stepper throw.
     */
    private static void scenario4_scriptImplementsInterface() throws Exception {
        banner("4 script as java.util.Comparator  [proxy, no caller node]");
        Interpreter i = fresh();
        i.eval(
            "makeCmp() {"
          + "  compare(a, b) { return a.length() - b.length(); }"
          + "  return this;"
          + "}");
        This scripted = (This) i.eval("makeCmp();");
        Comparator cmp = (Comparator) scripted.getInterface(Comparator.class);

        List words = new ArrayList();
        words.add("ccc");
        words.add("a");
        words.add("bb");
        java.util.Collections.sort(words, cmp);   // Java drives the script
        System.out.println("[host] sorted = " + words);
    }

    /**
     * 5. Script invoked on a thread the script did not create. CallStack.java:44
     * says each external entry gets a FRESH CallStack -- so debugger state has to
     * be per-thread. Two threads hit the same script line concurrently here.
     */
    private static void scenario5_callOnOtherThread() throws Exception {
        banner("5 two java threads into one script  [separate CallStacks]");
        final Interpreter i = fresh();
        i.eval(
            "work(tag, n) {"
          + "  for (k = 0; k < n; k++) {"
          + "    print(\"  work \" + tag + \" step \" + k);"
          + "  }"
          + "  return tag + \" finished\";"
          + "}");
        final NameSpace global = i.getNameSpace();

        Runnable job1 = makeJob(i, global, "T1");
        Runnable job2 = makeJob(i, global, "T2");

        Thread a = new Thread(job1, "host-T1");
        Thread b = new Thread(job2, "host-T2");
        a.start();
        b.start();
        a.join();
        b.join();
    }

    private static Runnable makeJob(
            final Interpreter i, final NameSpace ns, final String tag) {
        return new Runnable() {
            public void run() {
                try {
                    Object r = ns.invokeMethod(
                            "work", new Object[]{tag, 3}, i);
                    System.out.println("[host] " + r);
                } catch (EvalError e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    /**
     * 6. Scripted class used as a real Java object. The only path where bytecode
     * is generated -- but each generated method just does
     * INVOKEVIRTUAL bsh/This.invokeMethod (ClassGeneratorUtil.java:388) and the
     * body is still interpreted. A JVM stack trace here shows the generated
     * frame plus a pile of bsh.* frames, and no script frame.
     */
    private static void scenario6_scriptedClassAsJavaObject() throws Exception {
        banner("6 scripted class as a Java object  [bytecode shim -> AST]");
        Interpreter i = fresh();
        i.source(path("05_scripted_class.bsh"));
        Object p = i.eval("new Point(5, 12);");
        System.out.println("[host] java class = " + p.getClass().getName());
        System.out.println("[host] toString    = " + p);
        // Reflective call straight into the generated shim.
        Object d2 = p.getClass().getMethod("distanceSquared", new Class[0])
                .invoke(p, new Object[0]);
        System.out.println("[host] distanceSquared = " + d2 + " (expect 169)");
    }

    /**
     * 7. Two interpreters over one shared NameSpace -- what bsh.util.Sessiond
     * does (Sessiond.java:88), and the pattern to copy for a Tier 2 debug REPL:
     * freeze a frame, then hand its NameSpace to a second Interpreter on a
     * socket and evaluate arbitrary expressions in the live scope.
     */
    private static void scenario7_sharedNamespaceTwoInterpreters() throws EvalError {
        banner("7 second interpreter over a shared NameSpace  [Tier 2 pattern]");
        Interpreter first = fresh();
        first.eval("state = \"set by first\"; counter = 10;");
        NameSpace shared = first.getNameSpace();

        Interpreter second = new Interpreter();
        second.setNameSpace(shared);
        System.out.println("[host] second sees state   = " + second.eval("state"));
        System.out.println("[host] second computes     = " + second.eval("counter * 5"));
        second.eval("counter = 99;");
        System.out.println("[host] first sees mutation = " + first.eval("counter"));
    }

    /**
     * 8. The error taxonomy, from the Java side. Note that a script-thrown
     * exception arrives wrapped in TargetError, while an undefined variable
     * arrives as a plain EvalError -- different classification for a
     * "break on exception" feature.
     */
    private static void scenario8_errorHandling() {
        banner("8 TargetError vs EvalError  [break-on-exception classification]");
        Interpreter i = fresh();

        try {
            i.eval("throw new IllegalStateException(\"from script\");");
        } catch (EvalError e) {
            System.out.println("[host] script throw -> " + e.getClass().getName());
            if (e instanceof bsh.TargetError) {
                System.out.println("[host]   target     = "
                        + ((bsh.TargetError) e).getTarget().getClass().getName());
                System.out.println("[host]   inNative   = "
                        + ((bsh.TargetError) e).inNativeCode());
            }
        }

        try {
            i.eval("noSuchVariable + 1;");
        } catch (EvalError e) {
            System.out.println("[host] undefined var -> " + e.getClass().getName());
        }

        try {
            i.eval("if ( { broken");
        } catch (Throwable t) {
            System.out.println("[host] parse error   -> " + t.getClass().getName());
        }
    }
}
