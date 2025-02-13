// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.intentions

import com.intellij.openapi.editor.Editor
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.classic.intentions.SelfTargetingIntention
import org.jetbrains.kotlin.idea.search.usagesSearch.descriptor
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.psiUtil.getChildOfType

class ConvertVarargParameterToArrayIntention : SelfTargetingIntention<KtParameter>(
    KtParameter::class.java,
    KotlinBundle.lazyMessage("convert.to.array.parameter"),
) {
    override fun isApplicableTo(element: KtParameter, caretOffset: Int): Boolean {
        if (element.getChildOfType<KtTypeReference>() == null) return false
        return element.isVarArg
    }

    override fun applyTo(element: KtParameter, editor: Editor?) {
        val typeReference = element.getChildOfType<KtTypeReference>() ?: return
        val type = element.descriptor?.type ?: return
        val newType = if (KotlinBuiltIns.isPrimitiveArray(type)) type.toString() else "Array<${typeReference.text}>"

        typeReference.replace(KtPsiFactory(element).createType(newType))
        element.removeModifier(KtTokens.VARARG_KEYWORD)
    }

}