#!/usr/bin/env kotlin

/*
 * Instruments a BeanShell script: at the start of every line where it is safe,
 * it prepends this statement:
 *   cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(LINE_NUMBER, this.namespace);
 * and writes the enriched script to stdout.
 * A line qualifies only if inserting the statement there keeps the script
 * parseable AND the resulting parse tree is the original with exactly one
 * statement node spliced in as a sibling (a "pure insertion"). That rejects
 * positions such as the body of a brace-less if/while/for/do or a label target,
 * where the following statement would detach.
 *
 * Run (reads the script from stdin, writes the instrumented script to stdout):
 *   ./bshInstrumenter.main.kts < script.bsh
 *
 * A Kotlin script is loaded by its own classloader, so bsh's package-private
 * SimpleNode/Node are unreachable directly, so they are used via reflection
 */

@file:DependsOn("org.apache-extras.beanshell:bsh:2.0b6")
@file:DependsOn("org.jetbrains:annotations:26.1.0")

/** returns prefix to be used to instrument lines with (when possible) */
fun statementForLine(ln: Int) = "cz.loplex.intellij.bsh.debug.agent.BshDebugAgent.step(${ln+1}, this.namespace); "

fun interface Invokable<T> {
    operator fun invoke(vararg args: Any?): T
}

/** Wraps an object and provides access to its private methods */
open class ObjectWrapper(protected val obj: Any) {
    protected fun <T> method(name: String, vararg parameterTypes: Class<*>): Invokable<T> {
        val m = obj.javaClass.getMethod(name, *parameterTypes).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return Invokable { args -> m(obj, *args) as T }
    }
}

/** Wraps package-private `bsh.SimpleNode` */
class NodeWrapper(node: Any, val depth: Int = 0) : ObjectWrapper(node) {
    val name get() = obj.toString()

    private val jjtGetNumChildren = method<Int>("jjtGetNumChildren")
    private val jjtGetChild = method<Any>("jjtGetChild", Int::class.java)

    fun children() = sequence {
        val childrenCount = jjtGetNumChildren()
        for (i in 0 until childrenCount) {
            yield(NodeWrapper(jjtGetChild(i), depth + 1))
        }
    }

    fun traverse(visitor: (NodeWrapper)->Unit) {
        visitor(this)
        children().forEach { child -> child.traverse(visitor) }
    }
}

/** Parse the whole script passed as lines list; serialize every node to "indent + type name" (no source positions). */
fun serialize(srcLines: List<String>): List<String> {
    val parser = bsh.Parser(srcLines.joinToString("\n").reader())
    parser.setRetainComments(true)
    return buildList {
        @Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")
        while (!parser.Line()) {
            val rootNode = parser.popNode() ?: continue
            NodeWrapper(rootNode).traverse { node ->
                val indent = " ".repeat(node.depth)
                add(indent + node.name)
            }
        }
    }
}

/** Returns how many first elements have given two Iterables equal */
fun <A, B> commonElements(aList: Iterable<A>, bList: Iterable<B>): Int =
    (aList zip bList).takeWhile { (a, b) -> a == b }.count()

/** True if cand is orig with a single contiguous run of lines inserted. */
fun isPureInsertion(orig: List<String>, cand: List<String>): Boolean {
    val commonFromHead = commonElements(orig, cand)
    val commonFromTail = commonElements(orig.asReversed(), cand.asReversed())
    return commonFromHead + commonFromTail >= orig.size
}

/** Take lines of original .bsh script and return instrumented ones */
fun instrumentLines(lines: List<String>) : List<String> {
    val origSemanticTree = runCatching { serialize(lines) }
        .onFailure { System.err.println("Input script does not parse as valid BeanShell"); }
        .getOrThrow()

    return lines.mapIndexed { index, origLine ->
        val instrumentPrefix = statementForLine(index)
        val candidate = lines.toMutableList().also {
            it[index] = instrumentPrefix + it[index]
        }.toList()
        val candidateSemanticTree = runCatching { serialize(candidate) }.getOrDefault(emptyList())
        val canInstrument = isPureInsertion(origSemanticTree, candidateSemanticTree)
        (if (canInstrument) instrumentPrefix else "") + origLine
    }
}

val inputLines = System.`in`.bufferedReader().readLines()
val outputLines = instrumentLines(inputLines)
outputLines.forEach(::println)
