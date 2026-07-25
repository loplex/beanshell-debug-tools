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

/** Suspend state reported by the agent: one line and the visible variables. */
class BshSuspendContext(
    line: Int,
    variables: Map<String, String>,
    scriptFile: VirtualFile,
) : XSuspendContext() {
    private val stack = BshExecutionStack(line, variables, scriptFile)
    override fun getActiveExecutionStack(): XExecutionStack = stack
}

class BshExecutionStack(
    line: Int,
    variables: Map<String, String>,
    scriptFile: VirtualFile,
) : XExecutionStack("BeanShell") {
    private val frame = BshStackFrame(line, variables, scriptFile)
    override fun getTopFrame(): XStackFrame = frame
    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(if (firstFrameIndex == 0) listOf(frame) else emptyList(), true)
    }
}

class BshStackFrame(
    private val line: Int,
    private val variables: Map<String, String>,
    private val scriptFile: VirtualFile,
) : XStackFrame() {

    override fun getSourcePosition(): XSourcePosition? =
        XDebuggerUtil.getInstance().createPosition(scriptFile, line - 1)

    override fun computeChildren(node: XCompositeNode) {
        val children = XValueChildrenList()
        for ((name, value) in variables) children.add(name, BshValue(value))
        node.addChildren(children, true)
    }
}

class BshValue(private val value: String) : XValue() {
    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(AllIcons.Nodes.Variable, null, value, false)
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
