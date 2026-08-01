package cz.loplex.intellij.bsh.debug

/** How execution should proceed after the current step. */
enum class BshStepMode { RUN, INTO, OVER, OUT }

/**
 * Decides whether a reported statement should pause the session, using the
 * BeanShell call depth (number of active user-method frames) reported by the
 * agent. Depth only needs to be a value that grows by a fixed amount per nested
 * method call, which is exactly what the agent computes.
 */
object BshStepLogic {
    fun shouldPause(mode: BshStepMode, stepDepth: Int, currentDepth: Int, atBreakpoint: Boolean): Boolean {
        return atBreakpoint || when (mode) {
            BshStepMode.RUN -> false
            BshStepMode.INTO -> true                     // next statement, wherever it is
            BshStepMode.OVER -> currentDepth <= stepDepth // skip descents into called methods
            BshStepMode.OUT -> currentDepth < stepDepth   // only after returning to the caller
        }
    }
}
