package cz.loplex.intellij.bsh

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.util.io.ByteSequence
import com.intellij.openapi.vfs.VirtualFile

/**
 * Recognises extensionless scripts as BeanShell from their header. A file that
 * begins with a shebang is treated as BeanShell when either:
 *
 *  - the shebang runs a BeanShell interpreter directly, e.g.
 *    `#!/usr/bin/bsh`, `#!/usr/bin/env beanshell`, or
 *    `#!/path/to/java bsh.Interpreter`; or
 *  - one of the first few lines invokes `bsh.Interpreter`, covering the common
 *    self-executing polyglot:
 *
 *    ```
 *    #!/bin/sh
 *    // The following hack allows java to reside anywhere in the PATH.
 *    //bin/true; exec java bsh.Interpreter "$0" "$@"
 *    ```
 */
class BshFileTypeDetector : FileTypeRegistry.FileTypeDetector {

    override fun detect(file: VirtualFile, firstBytes: ByteSequence, firstCharsIfText: CharSequence?): FileType? {
        val text = firstCharsIfText ?: return null
        val head = text.lineSequence().take(MAX_HEADER_LINES).toList()
        val first = head.firstOrNull()?.trim() ?: return null
        if (!first.startsWith("#!")) return null

        // `exec java bsh.Interpreter ...`, possibly a few lines below the shebang.
        if (head.any { INTERPRETER.containsMatchIn(it) }) return BshFileType
        // A shebang naming a BeanShell interpreter directly.
        if (INTERPRETER_NAME.containsMatchIn(first)) return BshFileType
        return null
    }

    override fun getDesiredContentPrefixLength(): Int = 256

    companion object {
        private const val MAX_HEADER_LINES = 6
        private val INTERPRETER = Regex("""\bbsh\.Interpreter\b""")
        private val INTERPRETER_NAME = Regex("""\b(?:beanshell|bsh)\b""", RegexOption.IGNORE_CASE)
    }
}
