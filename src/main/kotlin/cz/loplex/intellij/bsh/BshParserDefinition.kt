package cz.loplex.intellij.bsh

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.intellij.extapi.psi.ASTWrapperPsiElement
import cz.loplex.intellij.bsh.lexer.BshLexer
import cz.loplex.intellij.bsh.parser.BshParser
import cz.loplex.intellij.bsh.psi.BshAmbiguousName
import cz.loplex.intellij.bsh.psi.BshClassDeclaration
import cz.loplex.intellij.bsh.psi.BshElementTypes
import cz.loplex.intellij.bsh.psi.BshFile
import cz.loplex.intellij.bsh.psi.BshFormalParameter
import cz.loplex.intellij.bsh.psi.BshMethodDeclaration
import cz.loplex.intellij.bsh.psi.BshTokenTypes
import cz.loplex.intellij.bsh.psi.BshVariableDeclarator

class BshParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = BshLexer()

    override fun createParser(project: Project?): PsiParser = BshParser()

    override fun getFileNodeType(): IFileElementType = FILE

    override fun getCommentTokens(): TokenSet = BshTokenTypes.COMMENTS

    override fun getStringLiteralElements(): TokenSet = BshTokenTypes.STRING_LITERALS

    override fun getWhitespaceTokens(): TokenSet = BshTokenTypes.WHITESPACES

    override fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        BshElementTypes.CLASS_DECLARATION -> BshClassDeclaration(node)
        BshElementTypes.METHOD_DECLARATION -> BshMethodDeclaration(node)
        BshElementTypes.VARIABLE_DECLARATOR -> BshVariableDeclarator(node)
        BshElementTypes.FORMAL_PARAMETER -> BshFormalParameter(node)
        BshElementTypes.AMBIGUOUS_NAME -> BshAmbiguousName(node)
        else -> ASTWrapperPsiElement(node)
    }

    override fun createFile(viewProvider: FileViewProvider): PsiFile = BshFile(viewProvider)

    companion object {
        val FILE = IFileElementType(BshLanguage)
    }
}
