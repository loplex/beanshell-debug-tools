package cz.loplex.intellij.bsh.psi

import com.intellij.psi.tree.IElementType
import cz.loplex.intellij.bsh.BshLanguage
import org.jetbrains.annotations.NonNls

/** Parser (AST) element type for the BeanShell language. */
class BshElementType(@NonNls debugName: String) : IElementType(debugName, BshLanguage)
