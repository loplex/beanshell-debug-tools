package cz.loplex.intellij.bsh.debug

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import cz.loplex.intellij.bsh.BshFileType

/** Allows line breakpoints to be placed in BeanShell files. */
class BshLineBreakpointType :
    XLineBreakpointType<XBreakpointProperties<*>>(ID, "BeanShell Breakpoint") {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        file.fileType == BshFileType

    companion object {
        const val ID = "bsh-line"
    }
}
