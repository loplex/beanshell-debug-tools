package cz.loplex.intellij.bsh.injection

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import cz.loplex.intellij.bsh.BshLanguage

/**
 * Injects the BeanShell language into inline scripts placed in a Maven plugin
 * `<configuration>`, e.g.
 *
 * ```xml
 * <plugin>
 *   <configuration>
 *     <source>print("hello from bsh");</source>
 *   </configuration>
 * </plugin>
 * ```
 *
 * The whole BeanShell tool-chain (highlighting, parsing, completion, rename,
 * inspections, …) then works inside the injected fragment.
 *
 * A host qualifies when it lives in a `pom.xml`, sits under a `<configuration>`
 * element and its tag name looks like a script property. No dependency on the
 * Maven plugin is required.
 */
class BshMavenInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> =
        listOf(XmlText::class.java, XmlAttributeValue::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val host = context as? PsiLanguageInjectionHost ?: return
        if (!host.isValidHost) return
        if (context.containingFile?.name != "pom.xml") return
        if (context.text.isBlank()) return

        val tag = enclosingTag(context) ?: return
        if (!isScriptTag(tag) || !insideConfiguration(tag)) return

        val range = ElementManipulators.getValueTextRange(host)
        if (range.isEmpty) return

        registrar.startInjecting(BshLanguage)
            .addPlace(null, null, host, range)
            .doneInjecting()
    }

    private fun enclosingTag(context: PsiElement): XmlTag? = when (context) {
        is XmlText -> context.parentTag
        is XmlAttributeValue -> (context.parent?.parent as? XmlTag)
        else -> null
    }

    private fun isScriptTag(tag: XmlTag): Boolean {
        val name = tag.name.lowercase()
        return name in SCRIPT_TAGS || name.contains("beanshell") || name.contains("bsh") || name.contains("script")
    }

    private fun insideConfiguration(tag: XmlTag): Boolean {
        var current: XmlTag? = tag
        while (current != null) {
            if (current.name == "configuration") return true
            current = current.parentTag
        }
        return false
    }

    companion object {
        private val SCRIPT_TAGS = setOf(
            "source", "script", "beanshell", "bsh", "expression", "evaluate", "eval", "condition",
        )
    }
}
