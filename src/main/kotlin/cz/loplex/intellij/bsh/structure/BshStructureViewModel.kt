package cz.loplex.intellij.bsh.structure

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.psi.PsiFile
import cz.loplex.intellij.bsh.psi.BshElementTypes
import cz.loplex.intellij.bsh.psi.BshFile

class BshStructureViewModel(psiFile: PsiFile) :
    StructureViewModelBase(psiFile, BshStructureViewElement(psiFile)),
    StructureViewModel.ElementInfoProvider {

    override fun getSorters(): Array<Sorter> = arrayOf(Sorter.ALPHA_SORTER)

    override fun isAlwaysShowsPlus(element: StructureViewTreeElement): Boolean =
        element.value is BshFile || elementType(element) === BshElementTypes.CLASS_DECLARATION

    override fun isAlwaysLeaf(element: StructureViewTreeElement): Boolean =
        elementType(element) === BshElementTypes.VARIABLE_DECLARATOR

    private fun elementType(element: StructureViewTreeElement) =
        (element.value as? com.intellij.psi.PsiElement)?.node?.elementType
}
