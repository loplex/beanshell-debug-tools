package cz.loplex.intellij.bsh.psi

/**
 * Composite AST node types produced by [cz.loplex.intellij.bsh.parser.BshParser].
 *
 * The set mirrors the productions of the BeanShell 2.0b6 `bsh.jjt` grammar.
 */
object BshElementTypes {
    // Declarations
    @JvmField val PACKAGE_DECLARATION = BshElementType("PACKAGE_DECLARATION")
    @JvmField val IMPORT_DECLARATION = BshElementType("IMPORT_DECLARATION")
    @JvmField val CLASS_DECLARATION = BshElementType("CLASS_DECLARATION")
    @JvmField val METHOD_DECLARATION = BshElementType("METHOD_DECLARATION")
    @JvmField val FORMAL_PARAMETERS = BshElementType("FORMAL_PARAMETERS")
    @JvmField val FORMAL_PARAMETER = BshElementType("FORMAL_PARAMETER")
    @JvmField val TYPED_VARIABLE_DECLARATION = BshElementType("TYPED_VARIABLE_DECLARATION")
    @JvmField val VARIABLE_DECLARATOR = BshElementType("VARIABLE_DECLARATOR")
    @JvmField val MODIFIERS = BshElementType("MODIFIERS")
    @JvmField val NAME_LIST = BshElementType("NAME_LIST")

    // Types
    @JvmField val TYPE = BshElementType("TYPE")
    @JvmField val PRIMITIVE_TYPE = BshElementType("PRIMITIVE_TYPE")
    @JvmField val AMBIGUOUS_NAME = BshElementType("AMBIGUOUS_NAME")

    // Statements
    @JvmField val BLOCK = BshElementType("BLOCK")
    @JvmField val LABELED_STATEMENT = BshElementType("LABELED_STATEMENT")
    @JvmField val EMPTY_STATEMENT = BshElementType("EMPTY_STATEMENT")
    @JvmField val SWITCH_STATEMENT = BshElementType("SWITCH_STATEMENT")
    @JvmField val SWITCH_LABEL = BshElementType("SWITCH_LABEL")
    @JvmField val IF_STATEMENT = BshElementType("IF_STATEMENT")
    @JvmField val WHILE_STATEMENT = BshElementType("WHILE_STATEMENT")
    @JvmField val FOR_STATEMENT = BshElementType("FOR_STATEMENT")
    @JvmField val ENHANCED_FOR_STATEMENT = BshElementType("ENHANCED_FOR_STATEMENT")
    @JvmField val RETURN_STATEMENT = BshElementType("RETURN_STATEMENT")
    @JvmField val SYNCHRONIZED_STATEMENT = BshElementType("SYNCHRONIZED_STATEMENT")
    @JvmField val THROW_STATEMENT = BshElementType("THROW_STATEMENT")
    @JvmField val TRY_STATEMENT = BshElementType("TRY_STATEMENT")
    @JvmField val STATEMENT_EXPRESSION_LIST = BshElementType("STATEMENT_EXPRESSION_LIST")

    // Expressions
    @JvmField val ASSIGNMENT = BshElementType("ASSIGNMENT")
    @JvmField val TERNARY_EXPRESSION = BshElementType("TERNARY_EXPRESSION")
    @JvmField val BINARY_EXPRESSION = BshElementType("BINARY_EXPRESSION")
    @JvmField val UNARY_EXPRESSION = BshElementType("UNARY_EXPRESSION")
    @JvmField val CAST_EXPRESSION = BshElementType("CAST_EXPRESSION")
    @JvmField val PRIMARY_EXPRESSION = BshElementType("PRIMARY_EXPRESSION")
    @JvmField val METHOD_INVOCATION = BshElementType("METHOD_INVOCATION")
    @JvmField val ALLOCATION_EXPRESSION = BshElementType("ALLOCATION_EXPRESSION")
    @JvmField val ARRAY_DIMENSIONS = BshElementType("ARRAY_DIMENSIONS")
    @JvmField val ARRAY_INITIALIZER = BshElementType("ARRAY_INITIALIZER")
    @JvmField val ARGUMENTS = BshElementType("ARGUMENTS")
    @JvmField val LITERAL = BshElementType("LITERAL")
}
