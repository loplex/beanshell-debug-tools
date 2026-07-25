package cz.loplex.bsh.agent;

// Compiled against ASM's real coordinates; maven-shade rewrites these references to the
// relocated package at package time (see the relocation in pom.xml).
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * Prepends a hook call to every BeanShell AST evaluation method.
 *
 * <p>The target is any method matching
 *
 * <pre>Object eval(bsh.CallStack, bsh.Interpreter)</pre>
 *
 * which is the contract every {@code BSH*} node implements (see {@code bsh.SimpleNode}). Keying
 * off the signature rather than a list of class names is what makes the agent
 * version-independent: node classes added, removed or renamed between BeanShell releases are
 * picked up or ignored automatically, and nothing here needs to know which version is running.
 *
 * <p>The injected prologue is four instructions:
 *
 * <pre>
 *   ALOAD 0                  // the AST node
 *   ALOAD 1                  // bsh.CallStack
 *   ALOAD 2                  // bsh.Interpreter
 *   INVOKESTATIC BshHook.onEval(Object, Object, Object)V
 * </pre>
 *
 * <p>The hook takes {@code Object} parameters on purpose. It is loaded by the bootstrap
 * classloader, which cannot see {@code bsh.CallStack}, so it could not be linked against those
 * types even though they are public. Passing them as {@code Object} needs no {@code checkcast}
 * — the verifier accepts a reference where its supertype is expected — and the hook recovers
 * what it needs reflectively.
 */
final class EvalTransformer implements ClassFileTransformer {

    /** The BeanShell AST evaluation contract. */
    private static final String EVAL_NAME = "eval";
    private static final String EVAL_DESCRIPTOR = "(Lbsh/CallStack;Lbsh/Interpreter;)Ljava/lang/Object;";

    /**
     * Referenced as a string, never as a class literal: this class runs in the system
     * classloader and must not trigger loading of the bootstrap copy of the hook. See
     * {@link BshAgentMain} for why that matters.
     */
    private static final String HOOK_INTERNAL_NAME = "cz/loplex/bsh/hook/BshHook";
    private static final String HOOK_METHOD = "onEval";
    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!isTopLevelBshClass(className)) {
            return null;
        }
        try {
            return instrument(classfileBuffer);
        } catch (Throwable t) {
            // A transformer that throws is silently ignored by the JVM, which would leave the
            // failure invisible. Report it and hand back the original bytes so the interpreter
            // keeps working uninstrumented.
            System.err.println("[bsh-agent] failed to instrument " + className + ": " + t);
            return null;
        }
    }

    /**
     * Restricted to the {@code bsh} package itself. Subpackages ({@code bsh.util},
     * {@code bsh.classpath}, {@code bsh.org.objectweb.asm}, …) hold no AST nodes, and skipping
     * them keeps the agent away from BeanShell's own bundled ASM.
     */
    private static boolean isTopLevelBshClass(String internalName) {
        return internalName != null
                && internalName.startsWith("bsh/")
                && internalName.indexOf('/', 4) < 0;
    }

    private static byte[] instrument(byte[] original) {
        ClassReader reader = new ClassReader(original);
        // COMPUTE_MAXS only. COMPUTE_FRAMES would make ASM resolve common superclasses, which
        // loads classes from inside a transformer — a well known way to deadlock or to recurse
        // into the very code being instrumented. The prologue is straight-line code inserted
        // before everything else, so existing stack map frames stay valid.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Instrumenter instrumenter = new Instrumenter(writer);
        reader.accept(instrumenter, 0);
        return instrumenter.instrumented ? writer.toByteArray() : null;
    }

    private static final class Instrumenter extends ClassVisitor {

        boolean instrumented;

        Instrumenter(ClassWriter writer) {
            super(Opcodes.ASM9, writer);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                        String signature, String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (delegate == null || !isEvalMethod(access, name, descriptor)) {
                return delegate;
            }
            instrumented = true;
            return new PrologueInserter(delegate);
        }

        private static boolean isEvalMethod(int access, String name, String descriptor) {
            if ((access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
                return false;
            }
            // An instance method is required: the prologue reads the node from local 0.
            if ((access & Opcodes.ACC_STATIC) != 0) {
                return false;
            }
            return EVAL_NAME.equals(name) && EVAL_DESCRIPTOR.equals(descriptor);
        }
    }

    private static final class PrologueInserter extends MethodVisitor {

        PrologueInserter(MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK_INTERNAL_NAME, HOOK_METHOD, HOOK_DESCRIPTOR, false);
        }
    }
}
