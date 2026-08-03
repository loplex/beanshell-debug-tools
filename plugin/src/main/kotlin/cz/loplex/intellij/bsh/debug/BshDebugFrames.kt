package cz.loplex.intellij.bsh.debug

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.util.LocalTimeCounter
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import cz.loplex.intellij.bsh.BshFileType

/** One frame as the agent reports it: a name, where it is, and its index in the stack. */
data class BshFrameInfo(val id: Int, val name: String, val sourceFile: String, val line: Int)

/** One variable as the agent reports it. [childHandle] is 0 when there is nothing to expand. */
data class BshVariable(val name: String, val value: String, val type: String, val childHandle: Int)

/**
 * The answer to an expression the agent ran for us.
 *
 * A failure is an ordinary answer rather than an exception, because a mistyped watch expression is
 * ordinary use: [value] then carries the reason instead of a rendering.
 */
data class BshEvalResult(val ok: Boolean, val value: String, val type: String, val childHandle: Int)

/**
 * Fetches on demand what the agent no longer pushes, and asks it to run expressions.
 *
 * Every call blocks the thread it is made on until the agent answers, which is what the platform
 * expects: it calls `computeChildren` off the UI thread precisely so an implementation may go and
 * ask something slow. The two evaluating calls return null when the agent never answered at all —
 * distinct from an answer that says "no".
 */
interface BshValueSource {
    /**
     * Whether the agent on the other end can run expressions at all.
     *
     * False on the source-rewriting path, which is handed a `NameSpace` and no `Interpreter`. The UI
     * then offers neither Watches nor Set Value, rather than offering them and failing.
     */
    val supportsEvaluation: Boolean

    /**
     * Scopes of one frame, as `name to handle`.
     *
     * Every call takes a `threadId` because more than one thread may be suspended, each with its own
     * frames and its own handle table. Passing the wrong one does not silently answer about the wrong
     * thread — handle ids are unique across threads, so a mismatched request finds nothing.
     */
    fun scopes(threadId: Int, frameId: Int): List<Pair<String, Int>>

    fun variables(threadId: Int, handle: Int): List<BshVariable>

    fun evaluate(threadId: Int, frameId: Int, expression: String): BshEvalResult?

    /** Stores the value of [expression] into the [name] child of [containerHandle]. */
    fun setVariable(
        threadId: Int,
        frameId: Int,
        containerHandle: Int,
        name: String,
        expression: String,
    ): BshEvalResult?
}

/**
 * One suspended script thread, as the Threads combo and the Frames panel see it.
 *
 * The [threadId] is what makes every later request unambiguous: scopes, variables, evaluation and
 * resume all name the thread they concern, because more than one may be suspended at once.
 */
class BshThreadStack(
    val threadId: Int,
    threadName: String,
    frames: List<BshFrameInfo>,
    scriptFile: VirtualFile,
    lineOf: (BshFrameInfo) -> Int,
    source: BshValueSource,
) : XExecutionStack(displayName(threadId, threadName)) {

    private val frames = frames.map { BshStackFrame(it, scriptFile, lineOf(it), source, threadId) }

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(frames.drop(firstFrameIndex), true)
    }

    private companion object {
        /**
         * What the Threads combo shows. The JVM thread name leads, because that is what the script
         * chose ("bsh-X") and what its own output will mention; the protocol id follows so two
         * threads sharing a name are still distinguishable.
         */
        fun displayName(threadId: Int, threadName: String): String =
            if (threadName.isBlank()) "BeanShell thread $threadId" else "$threadName [$threadId]"
    }
}

/**
 * The stop, from the platform's point of view: which thread the user is looking at, and which others
 * are also suspended.
 *
 * [getExecutionStacks] is what populates the Threads combo. Returning every suspended thread rather
 * than only the active one is the whole point — the agent suspends threads independently, so a second
 * one can be parked while the user inspects the first, and it should be reachable without waiting for
 * it to be resumed.
 */
class BshSuspendContext(
    private val active: BshThreadStack,
    private val all: List<BshThreadStack>,
) : XSuspendContext() {

    override fun getActiveExecutionStack(): XExecutionStack = active

    override fun getExecutionStacks(): Array<XExecutionStack> = all.toTypedArray()
}

class BshStackFrame(
    private val info: BshFrameInfo,
    private val scriptFile: VirtualFile,
    private val line: Int,
    private val source: BshValueSource,
    /** Which suspended thread this frame belongs to; every request about it carries this. */
    private val threadId: Int,
) : XStackFrame() {

    /**
     * Null for a frame outside the file being debugged — a `source()`d script, or the synthetic
     * outermost frame entered from Java, which bsh reports at line -1. The frame still appears in
     * the stack; it just cannot be navigated to.
     */
    override fun getSourcePosition(): XSourcePosition? =
        if (line >= 1) XDebuggerUtil.getInstance().createPosition(scriptFile, line - 1) else null

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList()
        // Locals and Global overlap: a method namespace's parent chain already reaches Global (bsh
        // closure semantics), so anything declared at script level is reported by both scopes. Locals
        // is listed first by `scopes`, so keeping its entry over Global's matches BeanShell's own
        // inner-scope-wins lookup order.
        val seen = mutableSetOf<String>()
        for ((_, handle) in source.scopes(threadId, info.id)) {
            for (variable in source.variables(threadId, handle)) {
                if (seen.add(variable.name)) {
                    children.add(variable.name, BshValue(variable, source, threadId, info.id, handle))
                }
            }
        }
        node.addChildren(children, true)
    }

    /** Watches and the Evaluate dialog evaluate in the scope of whichever frame is selected. */
    override fun getEvaluator(): XDebuggerEvaluator? =
        if (source.supportsEvaluation) BshEvaluator(threadId, info.id, source) else null

    /** What the Frames panel shows. `global` is bsh's name for the script's top level. */
    override fun customizePresentation(component: com.intellij.ui.ColoredTextContainer) {
        val where = if (line >= 1) ":$line" else ""
        component.append(
            (info.name.ifEmpty { "?" }) + where,
            com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES,
        )
        component.setIcon(AllIcons.Debugger.Frame)
    }
}

class BshValue(
    private val variable: BshVariable,
    private val source: BshValueSource,
    /** The suspended thread this value belongs to. */
    private val threadId: Int,
    /** The frame this value was read in, which is also where a replacement expression is evaluated. */
    private val frameId: Int,
    /**
     * The handle of the value this one is a child of, or [NO_HANDLE] when there is nothing to write
     * into — an expression's own result has no container, so it cannot be assigned to.
     */
    private val containerHandle: Int,
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(
            AllIcons.Nodes.Variable,
            variable.type.ifEmpty { null },
            variable.value,
            variable.childHandle != NO_HANDLE,
        )
    }

    override fun computeChildren(node: XCompositeNode) {
        if (variable.childHandle == NO_HANDLE) {
            node.addChildren(XValueChildrenList.EMPTY, true)
            return
        }
        val children = XValueChildrenList()
        for (child in source.variables(threadId, variable.childHandle)) {
            children.add(child.name, BshValue(child, source, threadId, frameId, variable.childHandle))
        }
        node.addChildren(children, true)
    }

    override fun getModifier(): XValueModifier? =
        if (source.supportsEvaluation && containerHandle != NO_HANDLE) {
            BshValueModifier(variable, source, threadId, frameId, containerHandle)
        } else {
            null
        }
}

/**
 * Evaluates an expression in the scope of one frame.
 *
 * The exchange with the agent blocks, and the platform calls [evaluate] from the UI thread for the
 * Evaluate dialog, so the request goes to a pooled thread — the callback is built to be invoked
 * later, from wherever the answer arrives.
 */
private class BshEvaluator(
    private val threadId: Int,
    private val frameId: Int,
    private val source: BshValueSource,
) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        onPooledThread {
            val result = source.evaluate(threadId, frameId, expression)
            when {
                result == null -> callback.errorOccurred(NO_ANSWER)
                !result.ok -> callback.errorOccurred(result.value)
                else -> callback.evaluated(
                    BshValue(
                        BshVariable(expression, result.value, result.type, result.childHandle),
                        source,
                        threadId,
                        frameId,
                        // A result is not stored anywhere, so there is nothing to assign back into.
                        NO_HANDLE,
                    ),
                )
            }
        }
    }
}

/**
 * Changes a value by handing the agent a replacement expression, which it evaluates in the frame the
 * value was read in — so `count + 1` and `other.name` mean there what they would mean in the script.
 */
private class BshValueModifier(
    private val variable: BshVariable,
    private val source: BshValueSource,
    private val threadId: Int,
    private val frameId: Int,
    private val containerHandle: Int,
) : XValueModifier() {

    override fun setValue(expression: XExpression, callback: XModificationCallback) {
        onPooledThread {
            val result =
                source.setVariable(threadId, frameId, containerHandle, variable.name, expression.expression)
            when {
                result == null -> callback.errorOccurred(NO_ANSWER)
                !result.ok -> callback.errorOccurred(result.value)
                else -> callback.valueModified()
            }
        }
    }

    /**
     * Prefills the editor with the current value, but only where its rendering happens to be a
     * BeanShell literal too. A `toString()` is not generally an expression, so offering `Point@1c2f`
     * back would hand the user something that cannot even parse; those open empty instead.
     */
    override fun getInitialValueEditorText(): String? = when (variable.type) {
        "String" -> quotedLiteral(variable.value)
        in LITERAL_TYPES -> variable.value
        else -> null
    }
}

/**
 * Types whose rendering can be handed straight back as an expression.
 *
 * Deliberately short of every scalar: a `float` renders as `1.5`, which is a *double* literal and
 * may then be refused as a narrowing assignment, and the same doubt applies to `short` and `byte`.
 * Prefilled text that fails when submitted unchanged is worse than an empty editor.
 */
private val LITERAL_TYPES = setOf("int", "Integer", "long", "Long", "double", "Double", "boolean", "Boolean")

private const val NO_ANSWER = "The debugged script did not answer"

private fun quotedLiteral(value: String): String = buildString(value.length + 2) {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

// Wrapped explicitly rather than passed as a lambda: executeOnPooledThread is overloaded for
// Runnable and Callable, and a Kotlin `() -> Unit` fits both.
@Suppress("RedundantSamConstructor")
private fun onPooledThread(work: () -> Unit) {
    ApplicationManager.getApplication().executeOnPooledThread(Runnable { work() })
}

/** Lets the debugger's expression fields use BeanShell. */
class BshDebuggerEditorsProvider : XDebuggerEditorsProvider() {
    override fun getFileType() = BshFileType

    override fun createDocument(
        project: Project,
        expression: XExpression,
        sourcePosition: XSourcePosition?,
        mode: EvaluationMode,
    ): Document {
        // eventSystemEnabled = true so the fragment gets a real backing Document; a light
        // (event-system-disabled) file yields a null document and NPEs the watches/evaluate UI.
        val fragment = PsiFileFactory.getInstance(project).createFileFromText(
            "fragment.bsh", BshFileType, expression.expression, LocalTimeCounter.currentTime(), true,
        )
        return PsiDocumentManager.getInstance(project).getDocument(fragment)!!
    }
}
