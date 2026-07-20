package cz.loplex.intellij.bsh.run

import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.RawCommandLineEditor
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class BshSettingsEditor : SettingsEditor<BshRunConfiguration>() {

    private val scriptPath = TextFieldWithBrowseButton()
    private val interpreterClasspath = TextFieldWithBrowseButton()
    private val jrePath = TextFieldWithBrowseButton()
    private val workingDirectory = TextFieldWithBrowseButton()
    private val programArguments = RawCommandLineEditor()

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
            .addTooltip("Path to a bsh jar or a directory that contains bsh.Interpreter")
            .addLabeledComponent("JRE:", jrePath)
            .addTooltip("Leave empty to use the IDE runtime")
            .panel
    }

    override fun resetEditorFrom(configuration: BshRunConfiguration) {
        scriptPath.text = configuration.scriptPath
        programArguments.text = configuration.programArguments
        workingDirectory.text = configuration.workingDirectory
        interpreterClasspath.text = configuration.interpreterClasspath
        jrePath.text = configuration.jrePath
    }

    override fun applyEditorTo(configuration: BshRunConfiguration) {
        configuration.scriptPath = scriptPath.text.trim()
        configuration.programArguments = programArguments.text.trim()
        configuration.workingDirectory = workingDirectory.text.trim()
        configuration.interpreterClasspath = interpreterClasspath.text.trim()
        configuration.jrePath = jrePath.text.trim()
    }

    override fun createEditor(): JComponent = panel
}
