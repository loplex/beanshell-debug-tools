package cz.loplex.intellij.bsh

import com.intellij.openapi.application.PathManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import cz.loplex.intellij.bsh.debug.BshDebugInstrumenter
import cz.loplex.intellij.bsh.debug.agent.BshDebugAgent
import cz.loplex.intellij.bsh.psi.BshFile
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

class BshDebugInstrumenterTest : BasePlatformTestCase() {

    private fun instrument(text: String): String =
        BshDebugInstrumenter.instrument(myFixture.configureByText("a.bsh", text) as BshFile)

    fun testInstrumentedScriptReparsesWithoutErrors() {
        val instrumented = instrument(
            "x = 1;\nif (x > 0) {\n    print(x);\n}\nfor (int i = 0; i < 3; i++) { print(i); }",
        )
        val reparsed = myFixture.configureByText("b.bsh", instrumented)
        assertEmpty(PsiTreeUtil.collectElementsOfType(reparsed, PsiErrorElement::class.java))
        assertTrue(instrumented.contains("BshDebugAgent.step("))
    }

    fun testHooksArePlacedAtStatementLines() {
        val instrumented = instrument("x = 1;\ny = 2;")
        assertTrue(instrumented.contains(".step(1, this.namespace)"))
        assertTrue(instrumented.contains(".step(2, this.namespace)"))
    }

    fun testHookBeforeTrailingReturnExpression() {
        // A script ending in a bare expression (its return value, as an enforcer <condition> does)
        // must still be instrumented and reparse cleanly.
        val instrumented = instrument("x = 1;\nx > 0")
        val reparsed = myFixture.configureByText("c.bsh", instrumented)
        assertEmpty(PsiTreeUtil.collectElementsOfType(reparsed, PsiErrorElement::class.java))
        assertTrue(instrumented.contains(".step(1, this.namespace)"))
        assertTrue("hook before the trailing return expression", instrumented.contains(".step(2, this.namespace)"))
    }

    private data class Frame(val line: Int, val depth: Int, val vars: Map<String, String>)

    /** End-to-end: instrumented script runs on real BeanShell and drives the agent over a socket. */
    fun testInstrumentedScriptDrivesAgentOverSocket() {
        val instrumented = instrument("int f(a) { return a + 1; }\nx = 10;\ny = f(x);\nprint(\"R=\" + y);")
        val scriptFile = File.createTempFile("bshdbg", ".bsh").apply { writeText(instrumented); deleteOnExit() }

        val agentCp = PathManager.getJarPathForClass(BshDebugAgent::class.java)
        val bshCp = PathManager.getJarPathForClass(Class.forName("bsh.Interpreter"))
        val classpath = agentCp + File.pathSeparator + bshCp
        val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath

        val output = ArrayList<String>()
        val frames = ArrayList<Frame>()
        ServerSocket(0).use { server ->
            server.soTimeout = 30_000
            val process = ProcessBuilder(
                java, "-D${BshDebugAgent.PORT_PROPERTY}=${server.localPort}",
                "-cp", classpath, "bsh.Interpreter", scriptFile.absolutePath,
            ).redirectErrorStream(true).start()
            val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.add(it) } }
                .apply { isDaemon = true; start() }

            server.accept().use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val out = socket.getOutputStream()
                try {
                    while (true) {
                        val line = input.readInt()
                        val depth = input.readInt()
                        val count = input.readInt()
                        val vars = LinkedHashMap<String, String>()
                        repeat(count) { vars[input.readUTF()] = input.readUTF() }
                        frames.add(Frame(line, depth, vars))
                        out.write(1) // resume
                        out.flush()
                    }
                } catch (_: EOFException) {
                    // script finished and closed the connection
                }
            }
            process.waitFor(30, TimeUnit.SECONDS)
            reader.join(5_000)
        }

        assertTrue("user output preserved", output.contains("R=11"))
        assertTrue("statement lines are reported", frames.map { it.line }.containsAll(listOf(2, 3, 4)))
        assertTrue("call depth increases inside the method", frames.maxOf { it.depth } > frames.minOf { it.depth })
        assertEquals("variable captured", "11", frames.first { it.line == 4 }.vars["y"])
    }
}
