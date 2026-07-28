package cz.loplex.intellij.bsh

import com.intellij.lang.Language

/**
 * The BeanShell scripting language.
 *
 * BeanShell is a small, embeddable Java source interpreter with a Java-like
 * syntax plus a number of loosely typed scripting conveniences. The grammar
 * modelled by this plugin follows the BeanShell 3.0 `bsh.jjt` grammar.
 */
object BshLanguage : Language("BeanShell") {
    private fun readResolve(): Any = BshLanguage

    override fun getDisplayName(): String = "BeanShell"

    override fun isCaseSensitive(): Boolean = true
}
