package cz.loplex.intellij.bsh.injection

import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import cz.loplex.intellij.bsh.BshLanguage

/**
 * Injects the BeanShell language into inline scripts inside XML, through two
 * independent mechanisms:
 *
 *  1. **Curated Maven list** — scripts held in the `<configuration>` of specific
 *     Maven plugins (see [MavenBeanshellScripts]); active only in `pom.xml`.
 *  2. **Language comment** — a `<!--language=BeanShell-->` (or `lang=bsh`) comment
 *     immediately before any XML element injects BeanShell into that element, in
 *     any XML file.
 *
 * Inside the injected fragment the whole BeanShell tool-chain (highlighting,
 * parsing, completion, rename, inspections, …) works. No dependency on the Maven
 * plugin is required.
 */
class BshMavenInjector : MultiHostInjector {

    override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)

    override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
        val xmlText = context as? XmlText ?: return
        // XmlText's implementation is a PsiLanguageInjectionHost, though the API type is not.
        val host = context as? PsiLanguageInjectionHost ?: return
        if (!host.isValidHost || xmlText.text.isBlank()) return

        val tag = xmlText.parentTag ?: return
        val inject = hasLanguageComment(tag) ||
            (xmlText.containingFile?.name == "pom.xml" && matchesMavenList(tag))
        if (!inject) return

        val range = ElementManipulators.getValueTextRange(host)
        if (range.isEmpty) return

        registrar.startInjecting(BshLanguage)
            .addPlace(null, null, host, range)
            .doneInjecting()
    }

    private fun matchesMavenList(tag: XmlTag): Boolean {
        val plugin = enclosingPlugin(tag) ?: return false
        val artifactId = childValue(plugin, "artifactId") ?: return false
        val property = MavenBeanshellScripts.propertiesFor(artifactId)
            .firstOrNull { it.tag == tag.name } ?: return false
        return if (property.directChildOfConfiguration) {
            tag.parentTag?.name == "configuration"
        } else {
            insideConfiguration(tag)
        }
    }

    private fun hasLanguageComment(tag: XmlTag): Boolean {
        var sibling = tag.prevSibling
        while (sibling != null) {
            when {
                sibling is PsiWhiteSpace -> {}
                sibling is XmlText && sibling.getText().isBlank() -> {}
                sibling is XmlComment -> return LANGUAGE_COMMENT.containsMatchIn(sibling.text)
                else -> return false
            }
            sibling = sibling.prevSibling
        }
        return false
    }

    private fun enclosingPlugin(tag: XmlTag): XmlTag? {
        var current: XmlTag? = tag
        while (current != null) {
            if (current.name == "plugin") return current
            current = current.parentTag
        }
        return null
    }

    private fun insideConfiguration(tag: XmlTag): Boolean {
        var current: XmlTag? = tag.parentTag
        while (current != null) {
            if (current.name == "configuration") return true
            current = current.parentTag
        }
        return false
    }

    private fun childValue(tag: XmlTag, name: String): String? =
        tag.findFirstSubTag(name)?.value?.trimmedText

    companion object {
        private val LANGUAGE_COMMENT =
            Regex("(?i)(?:language|lang)\\s*=\\s*(?:beanshell|bsh)")
    }
}
