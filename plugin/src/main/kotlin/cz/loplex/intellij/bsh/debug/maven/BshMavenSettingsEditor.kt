package cz.loplex.intellij.bsh.debug.maven

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
import cz.loplex.intellij.bsh.debug.BshInstrumentationMode
import cz.loplex.intellij.bsh.run.addWrappedComment
import javax.swing.JComponent

/**
 * The *BeanShell* tab of a [BshMavenRunConfiguration], holding the one setting Maven's own editor
 * knows nothing about.
 *
 * It exists because the choice has to be visible. The inline-script session is started for the user
 * automatically when the pom contains BeanShell, so without a control here the mechanism would be
 * decided behind their back — and the two mechanisms differ in what the debugger can do, not just in
 * how it works. Maven's own settings are untouched: this is added as a second tab beside them, not a
 * replacement for them.
 */
class BshMavenSettingsEditor : SettingsEditor<BshMavenRunConfiguration>() {

    private val instrumentation = ComboBox(BshInstrumentationMode.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }

    private val panel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Debug instrumentation:", instrumentation)
        .addWrappedComment(
            """
            Only affects Debug, and only a pom.xml that contains an inline BeanShell script.
            The agent instruments the interpreter inside the Maven plugin's own classloader,
            leaving the pom untouched; it reports the whole call stack, expands nested values
            and evaluates watch expressions.
            Rewriting hands Maven a core extension that swaps the script for an instrumented
            copy — no agent needed, but a single frame and no expanding or Evaluate.
            """,
        )
        .panel

    override fun resetEditorFrom(configuration: BshMavenRunConfiguration) {
        instrumentation.selectedItem = configuration.instrumentation
    }

    override fun applyEditorTo(configuration: BshMavenRunConfiguration) {
        configuration.instrumentation =
            instrumentation.selectedItem as? BshInstrumentationMode ?: BshInstrumentationMode.DEFAULT
    }

    override fun createEditor(): JComponent = panel
}
