package cz.loplex.intellij.bsh.run

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.FormBuilder
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
            .addTooltip(
                "Only affects Debug. The agent leaves the script on disk untouched and reports the " +
                    "whole call stack; rewriting needs no agent jar and no JVM flag, but shows one " +
                    "frame, cannot expand nested values, and cannot evaluate expressions",
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
