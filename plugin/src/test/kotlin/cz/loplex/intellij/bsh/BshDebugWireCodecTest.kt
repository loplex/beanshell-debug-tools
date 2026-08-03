package cz.loplex.intellij.bsh

import cz.loplex.intellij.bsh.debug.BshDebugWireCodec
import cz.loplex.intellij.bsh.debug.BshFrameInfo
import cz.loplex.intellij.bsh.debug.resolveFrameLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class BshDebugWireCodecTest {

    private fun encode(write: (DataOutputStream) -> Unit): DataInputStream {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use(write)
        return DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun readStoppedDecodesThreadAndFrames() {
        val input = encode { out ->
            out.writeInt(7) // threadId
            out.writeUTF("bsh-7") // threadName
            out.writeInt(30) // line
            out.writeInt(2) // depth
            out.writeInt(2) // frameCount
            out.writeUTF("factorial")
            out.writeUTF("showcase.bsh")
            out.writeInt(30)
            out.writeUTF("global")
            out.writeUTF("showcase.bsh")
            out.writeInt(49)
        }

        val report = BshDebugWireCodec.readStopped(input)

        assertEquals(7, report.threadId)
        assertEquals("bsh-7", report.threadName)
        assertEquals(30, report.line)
        assertEquals(2, report.depth)
        assertEquals(listOf(BshFrameInfo(0, "factorial", "showcase.bsh", 30), BshFrameInfo(1, "global", "showcase.bsh", 49)), report.frames)
    }

    @Test
    fun readScopesDecodesNameHandlePairsInOrder() {
        val input = encode { out ->
            out.writeInt(2)
            out.writeUTF("Locals")
            out.writeInt(5)
            out.writeUTF("Global")
            out.writeInt(6)
        }

        assertEquals(listOf("Locals" to 5, "Global" to 6), BshDebugWireCodec.readScopes(input))
    }

    @Test
    fun readVariablesDecodesEveryField() {
        val input = encode { out ->
            out.writeInt(1)
            out.writeUTF("numbers")
            out.writeUTF("{ArrayList} [1]")
            out.writeUTF("ArrayList")
            out.writeInt(42)
        }

        val variables = BshDebugWireCodec.readVariables(input)

        assertEquals(1, variables.size)
        assertEquals("numbers", variables[0].name)
        assertEquals("{ArrayList} [1]", variables[0].value)
        assertEquals("ArrayList", variables[0].type)
        assertEquals(42, variables[0].childHandle)
    }

    @Test
    fun readEvalResultDecodesFailureAndSuccessTheSameWay() {
        val failure = encode { out ->
            out.writeBoolean(false)
            out.writeUTF("undefined variable: x")
            out.writeUTF("")
            out.writeInt(0)
        }
        val result = BshDebugWireCodec.readEvalResult(failure)
        assertEquals(false, result.ok)
        assertEquals("undefined variable: x", result.value)
    }

    @Test
    fun resolveFrameLineTrustsTheInnermostFrameUnconditionally() {
        // id 0 is never checked against the source file, unlike every outer frame below.
        val frame = BshFrameInfo(id = 0, name = "factorial", sourceFile = "elsewhere.bsh", line = 30)
        assertEquals(30, resolveFrameLine(frame, "showcase.bsh", "/proj/showcase.bsh", lineMapper = null))
    }

    @Test
    fun resolveFrameLineRejectsAnOuterFrameFromAnotherFile() {
        val frame = BshFrameInfo(id = 1, name = "caller", sourceFile = "other.bsh", line = 10)
        assertEquals(-1, resolveFrameLine(frame, "showcase.bsh", "/proj/showcase.bsh", lineMapper = null))
    }

    @Test
    fun resolveFrameLineAcceptsAnOuterFrameFromTheSameFile() {
        val frame = BshFrameInfo(id = 1, name = "caller", sourceFile = "showcase.bsh", line = 10)
        assertEquals(10, resolveFrameLine(frame, "showcase.bsh", "/proj/showcase.bsh", lineMapper = null))
    }

    @Test
    fun resolveFrameLineDefersToTheLineMapperWhenOneIsGiven() {
        val frame = BshFrameInfo(id = 1, name = "caller", sourceFile = "inline evaluation of: ``print(x);''", line = 1)
        val mapper: (String, Int) -> Int = { source, line -> if (source.startsWith("inline")) 27 + line else -1 }
        assertEquals(28, resolveFrameLine(frame, "pom.xml", "/proj/pom.xml", lineMapper = mapper))
    }
}
