package cz.loplex.intellij.bsh

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XValueChildrenList
import cz.loplex.intellij.bsh.debug.BshEvalResult
import cz.loplex.intellij.bsh.debug.BshFrameInfo
import cz.loplex.intellij.bsh.debug.BshStackFrame
import cz.loplex.intellij.bsh.debug.BshValue
import cz.loplex.intellij.bsh.debug.BshValueSource
import cz.loplex.intellij.bsh.debug.BshVariable
import javax.swing.Icon

/**
 * [BshStackFrame.computeChildren] flattens two scopes ("Locals"/"Global") that BeanShell's own
 * closure semantics make overlap, and now groups what is left of each under its own label. Both the
 * dedup and the grouping are exercised here against a scripted [BshValueSource], since neither needs
 * a live agent -- only the shape of the resulting [XValueChildrenList].
 */
class BshDebugFramesTest : BasePlatformTestCase() {

    private class FakeSource(
        private val scopes: List<Pair<String, List<BshVariable>>>,
        override val supportsEvaluation: Boolean = false,
    ) : BshValueSource {
        override fun scopes(threadId: Int, frameId: Int): List<Pair<String, Int>> =
            scopes.mapIndexed { index, (name, _) -> name to (index + 1) }

        override fun variables(threadId: Int, handle: Int): List<BshVariable> =
            scopes.getOrNull(handle - 1)?.second.orEmpty()

        override fun evaluate(threadId: Int, frameId: Int, expression: String): BshEvalResult? = null

        override fun setVariable(
            threadId: Int,
            frameId: Int,
            containerHandle: Int,
            name: String,
            expression: String,
        ): BshEvalResult? = null
    }

    private class RecordingNode : XCompositeNode {
        var children: XValueChildrenList? = null
        override fun addChildren(children: XValueChildrenList, last: Boolean) {
            this.children = children
        }
        // XCompositeNode.tooManyChildren(Int) is deprecated in the platform SDK, but the newer
        // overload is a default method that delegates to this one -- implementers still have to
        // override it.
        @Suppress("OVERRIDE_DEPRECATION")
        override fun tooManyChildren(remaining: Int) {}
        override fun setAlreadySorted(alreadySorted: Boolean) {}
        override fun setErrorMessage(errorMessage: String) {}
        override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {}
        override fun setMessage(
            message: String,
            icon: Icon?,
            attributes: SimpleTextAttributes,
            link: XDebuggerTreeNodeHyperlink?,
        ) {}
    }

    private fun variable(name: String, type: String = "int", value: String = "1") =
        BshVariable(name, value, type, /* childHandle = */ 0)

    private fun childNamesOf(node: RecordingNode): List<String> {
        val list = node.children!!
        return (0 until list.size()).map { list.getName(it) }
    }

    private fun computeChildrenOf(source: BshValueSource): XValueChildrenList {
        val scriptFile = myFixture.configureByText("a.bsh", "x = 1;").virtualFile
        val frame = BshStackFrame(BshFrameInfo(0, "factorial", "a.bsh", 1), scriptFile, 1, source, /* threadId = */ 1)
        val node = RecordingNode()
        frame.computeChildren(node)
        return node.children!!
    }

    fun testVariableSharedByLocalsAndGlobalIsShownOnlyUnderLocals() {
        val source = FakeSource(
            listOf(
                "Locals" to listOf(variable("n"), variable("sub"), variable("result")),
                // A method's namespace closes over the enclosing one, so Global always repeats
                // whatever Locals' own parent-chain walk already reached -- here, "n" a second time.
                "Global" to listOf(variable("numbers", "ArrayList"), variable("n")),
            ),
        )

        val children = computeChildrenOf(source)

        assertEquals("nothing sits ungrouped outside a scope", 0, children.size())
        val groups = children.topGroups
        assertEquals(listOf("Locals", "Global"), groups.map { it.getName() })

        val localsNode = RecordingNode().also { groups[0].computeChildren(it) }
        assertEquals(listOf("n", "sub", "result"), childNamesOf(localsNode))

        val globalNode = RecordingNode().also { groups[1].computeChildren(it) }
        assertEquals(
            "Locals already reported n; Global must not repeat it",
            listOf("numbers"),
            childNamesOf(globalNode),
        )
    }

    fun testScopeGroupsAreAutoExpanded() {
        val source = FakeSource(listOf("Locals" to listOf(variable("n"))))
        val group = computeChildrenOf(source).topGroups.single()
        assertTrue("otherwise every stop hides its own locals behind an extra click", group.isAutoExpand())
    }

    fun testScopeFullyShadowedByAnEarlierOneContributesNoGroupAtAll() {
        val source = FakeSource(
            listOf(
                "Locals" to listOf(variable("n")),
                "Global" to listOf(variable("n")), // fully shadowed: nothing left for this scope to show
            ),
        )

        val groups = computeChildrenOf(source).topGroups
        assertEquals("an empty Global group would just be a dead click", listOf("Locals"), groups.map { it.getName() })
    }

    private fun evaluableValue(name: String, type: String, value: String): BshValue =
        BshValue(
            variable(name, type, value),
            FakeSource(emptyList(), supportsEvaluation = true),
            /* threadId = */ 1,
            /* frameId = */ 0,
            /* containerHandle = */ 1,
        )

    fun testModifierEscapesAQuoteInAStringLiteral() {
        val modifier = evaluableValue("s", "String", "a\"b").getModifier()!!
        assertEquals("\"a\\\"b\"", modifier.initialValueEditorText)
    }

    fun testModifierEscapesABackslashInAStringLiteral() {
        val modifier = evaluableValue("s", "String", "a\\b").getModifier()!!
        assertEquals("\"a\\\\b\"", modifier.initialValueEditorText)
    }

    fun testModifierEscapesNewlineAndTabInAStringLiteral() {
        val modifier = evaluableValue("s", "String", "a\nb\tc").getModifier()!!
        assertEquals("\"a\\nb\\tc\"", modifier.initialValueEditorText)
    }

    fun testModifierPrefillsAPlainIntLiteralAsIs() {
        val modifier = evaluableValue("n", "int", "42").getModifier()!!
        assertEquals("42", modifier.initialValueEditorText)
    }

    fun testModifierLeavesANarrowingFloatLiteralBlank() {
        // "1.5" reads back as a double literal, which may be refused as a narrowing assignment to a
        // float -- an empty editor is safer than one that fails the moment it is submitted unchanged.
        val modifier = evaluableValue("n", "float", "1.5").getModifier()!!
        assertNull(modifier.initialValueEditorText)
    }

    fun testNoModifierWithoutAgentEvaluationSupport() {
        val value = BshValue(variable("n"), FakeSource(emptyList()), 1, 0, /* containerHandle = */ 1)
        assertNull(value.getModifier())
    }

    fun testNoModifierWithoutAContainerToWriteBackInto() {
        val value = BshValue(
            variable("n"),
            FakeSource(emptyList(), supportsEvaluation = true),
            1,
            0,
            /* containerHandle = NO_HANDLE, an evaluation result has nothing to be assigned into */ 0,
        )
        assertNull(value.getModifier())
    }
}
