package cz.loplex.intellij.bsh

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

/**
 * File type for BeanShell scripts. Registered for the `bsh` extension.
 */
object BshFileType : LanguageFileType(BshLanguage) {
    const val DEFAULT_EXTENSION: String = "bsh"

    override fun getName(): String = "BeanShell"

    override fun getDescription(): String = "BeanShell script"

    override fun getDefaultExtension(): String = DEFAULT_EXTENSION

    override fun getIcon(): Icon = BshIcons.FILE
}
