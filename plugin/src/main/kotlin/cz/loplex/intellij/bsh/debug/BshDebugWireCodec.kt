package cz.loplex.intellij.bsh.debug

import java.io.DataInputStream

/**
 * Decodes what the agent sends (see `BshDebugProtocol.kt` for the framing), split out of
 * [BshDebugProcess] so the decoding can be pinned down by a test without a live socket.
 */
internal object BshDebugWireCodec {

    /** One [EVT_STOPPED] report, before [BshDebugProcess] turns it into a dispatch decision. */
    data class StoppedReport(
        val threadId: Int,
        val threadName: String,
        val line: Int,
        val depth: Int,
        val frames: List<BshFrameInfo>,
    )

    fun readStopped(input: DataInputStream): StoppedReport {
        val threadId = input.readInt()
        val threadName = input.readUTF()
        val line = input.readInt()
        val depth = input.readInt()
        val frames = (0 until input.readInt()).map { index ->
            BshFrameInfo(index, input.readUTF(), input.readUTF(), input.readInt())
        }
        return StoppedReport(threadId, threadName, line, depth, frames)
    }

    fun readScopes(input: DataInputStream): List<Pair<String, Int>> =
        (0 until input.readInt()).map { input.readUTF() to input.readInt() }

    fun readVariables(input: DataInputStream): List<BshVariable> =
        (0 until input.readInt()).map {
            BshVariable(input.readUTF(), input.readUTF(), input.readUTF(), input.readInt())
        }

    fun readEvalResult(input: DataInputStream): BshEvalResult =
        BshEvalResult(input.readBoolean(), input.readUTF(), input.readUTF(), input.readInt())
}

/**
 * Where a frame the agent reported sits in the file being debugged, or -1 when it sits elsewhere.
 *
 * With a [lineMapper] the decision is entirely its own -- it knows the reported source names and
 * answers -1 for anything foreign, so the injected-pom case needs no name matching here.
 *
 * Without one, the innermost frame ([BshFrameInfo.id] 0) is taken on trust -- the agent's own source
 * filter, or a rewritten script, already guarantees it is ours -- while outer frames must be shown to
 * be in this file: a `source()`d script or a frame entered from Java has no position in it.
 */
internal fun resolveFrameLine(
    frame: BshFrameInfo,
    sourceFileName: String,
    sourceFilePath: String,
    lineMapper: ((sourceFile: String, line: Int) -> Int)?,
): Int {
    lineMapper?.let { return it(frame.sourceFile, frame.line) }
    return when {
        frame.id == 0 -> frame.line
        frame.line >= 1 && isInSourceFile(frame.sourceFile, sourceFileName, sourceFilePath) -> frame.line
        else -> -1
    }
}

private fun isInSourceFile(reported: String, sourceFileName: String, sourceFilePath: String): Boolean =
    reported.isNotEmpty() && (reported.endsWith(sourceFileName) || sourceFilePath.endsWith(reported))
