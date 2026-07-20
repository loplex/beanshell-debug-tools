package cz.loplex.intellij.bsh

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.InjectionTestFixture

class BshInjectionTest : BasePlatformTestCase() {

    private val pomPrefix = "<project><build><plugins><plugin><configuration>"
    private val pomSuffix = "</configuration></plugin></plugins></build></project>"

    fun testBeanShellInjectedIntoScriptProperty() {
        myFixture.configureByText("pom.xml", "$pomPrefix<source>print(<caret>1);</source>$pomSuffix")
        InjectionTestFixture(myFixture).assertInjectedLangAtCaret("BeanShell")
    }

    fun testNoInjectionForNonScriptProperty() {
        myFixture.configureByText("pom.xml", "$pomPrefix<finalName>my<caret>app</finalName>$pomSuffix")
        InjectionTestFixture(myFixture).assertInjectedLangAtCaret(null)
    }

    fun testNoInjectionOutsidePom() {
        myFixture.configureByText("beans.xml", "<config><source>print(<caret>1);</source></config>")
        InjectionTestFixture(myFixture).assertInjectedLangAtCaret(null)
    }
}
