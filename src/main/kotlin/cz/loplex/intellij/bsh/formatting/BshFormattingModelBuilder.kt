package cz.loplex.intellij.bsh.formatting

import com.intellij.formatting.FormattingContext
import com.intellij.formatting.FormattingModel
import com.intellij.formatting.FormattingModelBuilder
import com.intellij.formatting.FormattingModelProvider
import com.intellij.formatting.SpacingBuilder
import com.intellij.psi.codeStyle.CodeStyleSettings
import cz.loplex.intellij.bsh.BshLanguage
import cz.loplex.intellij.bsh.psi.BshTokenTypes

class BshFormattingModelBuilder : FormattingModelBuilder {

    override fun createModel(formattingContext: FormattingContext): FormattingModel {
        val settings = formattingContext.codeStyleSettings
        val block = BshBlock(formattingContext.node, null, spacingBuilder(settings))
        return FormattingModelProvider.createFormattingModelForPsiFile(
            formattingContext.containingFile,
            block,
            settings,
        )
    }

    private fun spacingBuilder(settings: CodeStyleSettings): SpacingBuilder =
        SpacingBuilder(settings, BshLanguage)
            .before(BshTokenTypes.COMMA).spaces(0)
            .after(BshTokenTypes.COMMA).spaces(1)
            .before(BshTokenTypes.SEMICOLON).spaces(0)
}
