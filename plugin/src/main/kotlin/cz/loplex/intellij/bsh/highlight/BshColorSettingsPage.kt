package cz.loplex.intellij.bsh.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import cz.loplex.intellij.bsh.BshIcons
import javax.swing.Icon

class BshColorSettingsPage : ColorSettingsPage {
    override fun getIcon(): Icon = BshIcons.FILE

    override fun getHighlighter(): SyntaxHighlighter = BshSyntaxHighlighter()

    override fun getDemoText(): String = DEMO_TEXT

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey>? = null

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> = DESCRIPTORS

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "BeanShell"
}

private val DESCRIPTORS = arrayOf(
    AttributesDescriptor("Keyword", BshColors.KEYWORD),
    AttributesDescriptor("Identifier", BshColors.IDENTIFIER),
    AttributesDescriptor("Number", BshColors.NUMBER),
    AttributesDescriptor("String", BshColors.STRING),
    AttributesDescriptor("Character", BshColors.CHARACTER),
    AttributesDescriptor("Comments//Line comment", BshColors.LINE_COMMENT),
    AttributesDescriptor("Comments//Block comment", BshColors.BLOCK_COMMENT),
    AttributesDescriptor("Comments//Doc comment", BshColors.DOC_COMMENT),
    AttributesDescriptor("Operator sign", BshColors.OPERATOR),
    AttributesDescriptor("Parentheses", BshColors.PARENTHESES),
    AttributesDescriptor("Braces", BshColors.BRACES),
    AttributesDescriptor("Brackets", BshColors.BRACKETS),
    AttributesDescriptor("Semicolon", BshColors.SEMICOLON),
    AttributesDescriptor("Comma", BshColors.COMMA),
    AttributesDescriptor("Dot", BshColors.DOT),
    AttributesDescriptor("Bad character", BshColors.BAD_CHARACTER),
)

private val DEMO_TEXT = """
    /**
     * Sample BeanShell script.
     */
    import javax.swing.*;

    // loosely typed variable
    greeting = "Hello, BeanShell";
    count = 0x2A;              // 42
    ratio = 3.14f @pow 2;      // word operator

    invoke(String name) {
        print("Hi " + name);
        return name != null ? name : "world";
    }

    for (int i = 0; i < 3; i++) {
        invoke(greeting + i);
    }
""".trimIndent()
