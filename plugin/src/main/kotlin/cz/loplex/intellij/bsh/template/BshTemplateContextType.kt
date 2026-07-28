package cz.loplex.intellij.bsh.template

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import cz.loplex.intellij.bsh.psi.BshFile

/** Enables BeanShell live templates inside `.bsh` files. */
class BshTemplateContextType : TemplateContextType("BeanShell") {
    override fun isInContext(templateActionContext: TemplateActionContext): Boolean =
        templateActionContext.file is BshFile
}
