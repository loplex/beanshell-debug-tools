package cz.loplex.intellij.bsh.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import cz.loplex.intellij.bsh.BshFileType
import cz.loplex.intellij.bsh.BshLanguage

class BshFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, BshLanguage) {
    override fun getFileType(): FileType = BshFileType

    override fun toString(): String = "BeanShell File"
}
