package cz.loplex.intellij.bsh.debug

import com.intellij.icons.AllIcons
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
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import cz.loplex.intellij.bsh.BshFileType

/** One frame as the agent reports it: a name, where it is, and its index in the stack. */
data class BshFrameInfo(val id: Int, val name: String, val sourceFile: String, val line: Int)

/** One variable as the agent reports it. [childHandle] is 0 when there is nothing to expand. */
data class BshVariable(val name: String, val value: String, val type: String, val childHandle: Int)

/**
 * Fetches on demand what the agent no longer pushes.
 *
 * Every call blocks the thread it is made on until the agent answers, which is what the platform
 * expects: it calls `computeChildren` off the UI thread precisely so an implementation may go and
 * ask something slow.
 */
interface BshValueSource {
    /** Scopes of one frame, as `name to handle`. */
    fun scopes(frameId: Int): List<Pair<String, Int>>

    fun variables(handle: Int): List<BshVariable>
}

class BshSuspendContext(
    frames: List<BshFrameInfo>,
    scriptFile: VirtualFile,
    lineOf: (BshFrameInfo) -> Int,
    source: BshValueSource,
) : XSuspendContext() {
    private val stack = BshExecutionStack(frames, scriptFile, lineOf, source)
    override fun getActiveExecutionStack(): XExecutionStack = stack
}

class BshExecutionStack(
    frames: List<BshFrameInfo>,
    scriptFile: VirtualFile,
    lineOf: (BshFrameInfo) -> Int,
    source: BshValueSource,
) : XExecutionStack("BeanShell") {

    private val frames = frames.map { BshStackFrame(it, scriptFile, lineOf(it), source) }

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(frames.drop(firstFrameIndex), true)
    }
}

class BshStackFrame(
    private val info: BshFrameInfo,
    private val scriptFile: VirtualFile,
    private val line: Int,
    private val source: BshValueSource,
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
        for ((_, handle) in source.scopes(info.id)) {
            for (variable in source.variables(handle)) {
                children.add(variable.name, BshValue(variable, source))
            }
        }
        node.addChildren(children, true)
    }

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
        for (child in source.variables(variable.childHandle)) {
            children.add(child.name, BshValue(child, source))
        }
        node.addChildren(children, true)
    }
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
