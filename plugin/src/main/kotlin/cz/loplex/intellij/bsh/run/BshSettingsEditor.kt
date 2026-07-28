package cz.loplex.intellij.bsh.run

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import cz.loplex.intellij.bsh.debug.BshInstrumentationMode
import javax.swing.JComponent
import javax.swing.JPanel

class BshSettingsEditor : SettingsEditor<BshRunConfiguration>() {

    private val scriptPath = TextFieldWithBrowseButton()
    private val interpreterClasspath = TextFieldWithBrowseButton()
    private val jrePath = TextFieldWithBrowseButton()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val programArguments = RawCommandLineEditor()
    private val instrumentation = ComboBox(BshInstrumentationMode.values()).apply {
        renderer = SimpleListCellRenderer.create("") { it.label }
    }

    private val panel: JPanel

    init {
        scriptPath.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFileDescriptor("bsh").withTitle("Select BeanShell Script")
            )
        )
        interpreterClasspath.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptor(true, true, true, true, false, false)
                    .withTitle("Select bsh Jar or Classes Directory")
            )
        )
        jrePath.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select JRE Home")
            )
        )
        workingDirectory.addBrowseFolderListener(
            TextBrowseFolderListener(
                FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Select Working Directory")
            )
        )

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Script:", scriptPath)
            .addLabeledComponent("Program arguments:", programArguments)
            .addLabeledComponent("Working directory:", workingDirectory)
            .addLabeledComponent("BeanShell classpath:", interpreterClasspath)
            .addTooltip("Leave empty to use the bundled BeanShell; or point to a bsh jar / classes directory")
            .addLabeledComponent("JRE:", jrePath)
            .addTooltip("Leave empty to use the IDE runtime")
            .addLabeledComponent("Debug instrumentation:", instrumentation)
            .addWrappedComment(
                """
                Only affects Debug.
                The agent leaves the script on disk untouched, reports the whole call
                stack, expands nested values and evaluates watch expressions.
                Rewriting needs no agent and no JVM flag, but shows a single frame
                and can do none of the three.
                """,
            )
            .panel
    }

    override fun resetEditorFrom(configuration: BshRunConfiguration) {
        scriptPath.text = configuration.scriptPath
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
        interpreterClasspath.text = configuration.interpreterClasspath
        jrePath.text = configuration.jrePath
        instrumentation.selectedItem = configuration.instrumentation
    }

    override fun applyEditorTo(configuration: BshRunConfiguration) {
        configuration.scriptPath = scriptPath.text.trim()
        configuration.programArguments = programArguments.text.trim()
        configuration.workingDirectory = workingDirectory.text.trim()
        configuration.interpreterClasspath = interpreterClasspath.text.trim()
        configuration.jrePath = jrePath.text.trim()
        configuration.instrumentation =
            instrumentation.selectedItem as? BshInstrumentationMode ?: BshInstrumentationMode.DEFAULT
    }

    override fun createEditor(): JComponent = panel
}

/**
 * Adds a comment under the last labeled component, broken at the newlines in [text].
 *
 * The line breaks are `<br>` in a Swing HTML label, which is the only way to make them certain.
 * Two things that look like they would work do not: one long `addTooltip` renders as a single row
 * whose preferred width is the whole sentence, and `FormBuilder` gives it no width to wrap against,
 * so the dialog stretches to fit it; and *several* `addTooltip` calls, one per line, do not lay out
 * as separate rows either. A label whose text begins with `<html>` is rendered by Swing's HTML view,
 * where `<br>` is a hard break with nothing left to interpretation.
 *
 * Styled to match `addTooltip`'s own comment rows (smaller, dimmer) so it does not read as a
 * different kind of text.
 */
internal fun FormBuilder.addWrappedComment(text: String): FormBuilder {
    val lines = text.trimIndent().lines().filter { it.isNotBlank() }
    val label = JBLabel("<html>${lines.joinToString("<br>")}</html>").apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        fontColor = UIUtil.FontColor.BRIGHTER
    }
    return addComponentToRightColumn(label, 0)
}
