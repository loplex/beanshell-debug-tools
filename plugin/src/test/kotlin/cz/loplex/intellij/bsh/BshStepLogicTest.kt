package cz.loplex.intellij.bsh

import cz.loplex.intellij.bsh.debug.BshStepLogic
import cz.loplex.intellij.bsh.debug.BshStepMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BshStepLogicTest {

    @Test
    fun runStopsOnlyOnBreakpoints() {
        assertFalse(BshStepLogic.shouldPause(BshStepMode.RUN, 5, 3, atBreakpoint = false))
        assertTrue(BshStepLogic.shouldPause(BshStepMode.RUN, 5, 3, atBreakpoint = true))
    }

    @Test
    fun stepIntoStopsAtNextStatement() {
        assertTrue(BshStepLogic.shouldPause(BshStepMode.INTO, 5, 99, atBreakpoint = false))
        assertTrue(BshStepLogic.shouldPause(BshStepMode.INTO, 5, 1, atBreakpoint = false))
    }

    @Test
    fun stepOverSkipsDeeperFrames() {
        assertTrue(BshStepLogic.shouldPause(BshStepMode.OVER, 5, 5, atBreakpoint = false)) // same frame
        assertTrue(BshStepLogic.shouldPause(BshStepMode.OVER, 5, 4, atBreakpoint = false)) // returned to caller
        assertFalse(BshStepLogic.shouldPause(BshStepMode.OVER, 5, 6, atBreakpoint = false)) // inside a callee
    }

    @Test
    fun stepOutStopsOnlyAfterReturning() {
        assertFalse(BshStepLogic.shouldPause(BshStepMode.OUT, 5, 5, atBreakpoint = false))
        assertFalse(BshStepLogic.shouldPause(BshStepMode.OUT, 5, 6, atBreakpoint = false))
        assertTrue(BshStepLogic.shouldPause(BshStepMode.OUT, 5, 4, atBreakpoint = false))
    }
}
