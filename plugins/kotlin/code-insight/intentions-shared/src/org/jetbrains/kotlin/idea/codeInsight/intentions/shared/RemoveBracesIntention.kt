// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.codeInsight.intentions.shared

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingIntention
import org.jetbrains.kotlin.idea.codeinsight.utils.getControlFlowElementDescription
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.startOffset

internal class RemoveBracesIntention : SelfTargetingIntention<KtElement>(KtElement::class.java, KotlinBundle.lazyMessage("remove.braces")) {
    private fun KtElement.findChildBlock() = when (this) {
        is KtBlockExpression -> this
        is KtLoopExpression -> body as? KtBlockExpression
        is KtWhenEntry -> expression as? KtBlockExpression
        else -> null
    }

    override fun isApplicableTo(element: KtElement, caretOffset: Int): Boolean {
        val block = element.findChildBlock() ?: return false
        if (!isApplicableTo(block)) return false
        when (val container = block.parent) {
            is KtContainerNode -> {
                val description = container.getControlFlowElementDescription() ?: return false
                setTextGetter(KotlinBundle.lazyMessage("remove.braces.from.0.statement", description))
            }
            is KtWhenEntry -> {
                setTextGetter(KotlinBundle.lazyMessage("remove.braces.from.when.entry"))
            }
        }
        return true
    }

    override fun applyTo(element: KtElement, editor: Editor?) {
        val block = element.findChildBlock() ?: return
        removeBraces(element, block, editor)
    }

    companion object {
        fun isApplicableTo(block: KtBlockExpression): Boolean {
            val singleStatement = block.statements.singleOrNull() ?: return false
            if (singleStatement is KtLambdaExpression && singleStatement.functionLiteral.arrow == null) return false
            when (val container = block.parent) {
                is KtContainerNode -> {
                    if (singleStatement is KtProperty || singleStatement is KtClass) return false
                    if (singleStatement is KtIfExpression) {
                        val elseExpression = (container.parent as? KtIfExpression)?.`else`
                        if (elseExpression != null && elseExpression != block) return false
                    }
                    return true
                }
                is KtWhenEntry -> {
                    return singleStatement !is KtNamedDeclaration
                }
                else -> return false
            }
        }

        fun removeBraces(element: KtElement, block: KtBlockExpression, editor: Editor? = null) {
            val factory = KtPsiFactory(element)
            val statement = block.statements.single()
            val caretOnAfterStatement = if (editor != null) editor.caretModel.offset >= statement.endOffset else false

            val container = block.parent
            val construct = container.parent as KtExpression
            handleComments(construct, block)

            val newElement = block.replace(statement.copy())
            editor?.caretModel?.moveToOffset(if (caretOnAfterStatement) newElement.endOffset else newElement.startOffset)

            if (construct is KtDoWhileExpression) {
                newElement.parent!!.addAfter(factory.createNewLine(), newElement)
            } else if (editor != null) {
                val document = editor.document
                val line = document.getLineNumber(newElement.startOffset)
                val rightMargin = editor.settings.getRightMargin(editor.project)
                if (document.getLineEndOffset(line) - document.getLineStartOffset(line) >= rightMargin) {
                    newElement.parent.addBefore(factory.createNewLine(), newElement)
                }
            }

            if (construct is KtIfExpression &&
                container.node.elementType == KtNodeTypes.ELSE &&
                construct.parent is KtExpression &&
                construct.parent !is KtStatementExpression
            ) {
                val replaced = construct.replace(factory.createExpressionByPattern("($0)", construct))
                (replaced.children[0] as? KtIfExpression)?.`else`?.let {
                    editor?.caretModel?.moveToOffset(if (caretOnAfterStatement) it.endOffset else it.startOffset)
                }
            }
        }

        private fun handleComments(construct: KtExpression, block: KtBlockExpression) {
            var sibling = block.firstChild?.nextSibling

            while (sibling != null) {
                if (sibling is PsiComment) {
                    //cleans up extra whitespace
                    val psiFactory = KtPsiFactory(construct)
                    if (construct.prevSibling is PsiWhiteSpace) {
                        construct.prevSibling!!.replace(psiFactory.createNewLine())
                    }
                    val commentElement = construct.parent.addBefore(sibling, construct.prevSibling)
                    construct.parent.addBefore(psiFactory.createNewLine(), commentElement)
                }
                sibling = sibling.nextSibling
            }
        }
    }

    override fun skipProcessingFurtherElementsAfter(element: PsiElement) = element is KtBlockExpression && element.parent !is KtWhenEntry
}
