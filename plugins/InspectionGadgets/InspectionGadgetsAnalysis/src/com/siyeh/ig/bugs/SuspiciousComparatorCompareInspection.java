/*
 * Copyright 2007-2016 Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.bugs;

import com.intellij.codeInspection.dataFlow.JavaMethodContractUtil;
import com.intellij.codeInspection.dataFlow.MethodContract;
import com.intellij.codeInspection.dataFlow.interpreter.RunnerResult;
import com.intellij.codeInspection.dataFlow.interpreter.StandardDataFlowInterpreter;
import com.intellij.codeInspection.dataFlow.java.ControlFlowAnalyzer;
import com.intellij.codeInspection.dataFlow.java.JavaDfaListener;
import com.intellij.codeInspection.dataFlow.jvm.JvmDfaMemoryStateImpl;
import com.intellij.codeInspection.dataFlow.jvm.descriptors.PlainDescriptor;
import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow;
import com.intellij.codeInspection.dataFlow.memory.DfaMemoryState;
import com.intellij.codeInspection.dataFlow.rangeSet.LongRangeSet;
import com.intellij.codeInspection.dataFlow.types.DfIntType;
import com.intellij.codeInspection.dataFlow.value.DfaValue;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ControlFlowUtils;
import com.siyeh.ig.psiutils.ExpressionUtils;
import com.siyeh.ig.psiutils.MethodUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class SuspiciousComparatorCompareInspection extends BaseInspection {

  @NotNull
  @Override
  public String getShortName() {
    return "ComparatorMethodParameterNotUsed";
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return (String)infos[0];
  }

  @Override
  public boolean isEnabledByDefault() {
    return true;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SuspiciousComparatorCompareVisitor();
  }

  private static class SuspiciousComparatorCompareVisitor extends BaseInspectionVisitor {

    @Override
    public void visitMethod(@NotNull PsiMethod method) {
      super.visitMethod(method);
      if (!MethodUtils.isComparatorCompare(method) || ControlFlowUtils.methodAlwaysThrowsException(method)) {
        return;
      }
      check(method);
    }

    @Override
    public void visitLambdaExpression(@NotNull PsiLambdaExpression lambda) {
      super.visitLambdaExpression(lambda);
      final PsiClass functionalInterface = LambdaUtil.resolveFunctionalInterfaceClass(lambda);
      if (functionalInterface == null || !CommonClassNames.JAVA_UTIL_COMPARATOR.equals(functionalInterface.getQualifiedName()) ||
          ControlFlowUtils.lambdaExpressionAlwaysThrowsException(lambda)) {
        return;
      }
      check(lambda);
    }

    private void check(PsiParameterListOwner owner) {
      PsiParameterList parameterList = owner.getParameterList();
      PsiElement body = owner.getBody();
      if (body == null || parameterList.getParametersCount() != 2) return;
      // comparator like "(a, b) -> 0" fulfills the comparator contract, so no need to warn its parameters are not used
      if (body instanceof PsiExpression && ExpressionUtils.isZero((PsiExpression)body)) return;
      if (body instanceof PsiCodeBlock) {
        PsiStatement statement = ControlFlowUtils.getOnlyStatementInBlock((PsiCodeBlock)body);
        if (statement instanceof PsiReturnStatement && ExpressionUtils.isZero(((PsiReturnStatement)statement).getReturnValue())) return;
      }
      PsiMethodCallExpression soleCall = ObjectUtils.tryCast(LambdaUtil.extractSingleExpressionFromBody(body), PsiMethodCallExpression.class);
      if (soleCall != null) {
        MethodContract contract = ContainerUtil.getOnlyItem(JavaMethodContractUtil.getMethodCallContracts(soleCall));
        if (contract != null && contract.isTrivial() && contract.getReturnValue().isFail()) return;
      }
      PsiParameter[] parameters = parameterList.getParameters();
      checkParameterList(parameters, body);
      checkReflexivity(owner, parameters, body);
    }

    private void checkParameterList(PsiParameter[] parameters, PsiElement context) {
      final ParameterAccessVisitor visitor = new ParameterAccessVisitor(parameters);
      context.accept(visitor);
      for (PsiParameter unusedParameter : visitor.getUnusedParameters()) {
        registerVariableError(unusedParameter, InspectionGadgetsBundle.message(
          "suspicious.comparator.compare.descriptor.parameter.not.used"));
      }
    }

    private void checkReflexivity(PsiParameterListOwner owner, PsiParameter[] parameters, PsiElement body) {
      DfaValueFactory factory = new DfaValueFactory(owner.getProject());
      ControlFlow flow = ControlFlowAnalyzer.buildFlow(body, factory, true);
      if (flow == null) return;
      DfaMemoryState state = new JvmDfaMemoryStateImpl(factory);
      DfaVariableValue var1 = PlainDescriptor.createVariableValue(factory, parameters[0]);
      DfaVariableValue var2 = PlainDescriptor.createVariableValue(factory, parameters[1]);
      state.applyCondition(var1.eq(var2));
      var interceptor = new ComparatorListener(owner);
      if (new StandardDataFlowInterpreter(flow, interceptor).interpret(state) != RunnerResult.OK) return;
      if (interceptor.myRange.contains(0) || interceptor.myContexts.isEmpty()) return;
      PsiElement context = null;
      if (interceptor.myContexts.size() == 1) {
        context = interceptor.myContexts.iterator().next();
      }
      else {
        PsiElement commonParent = PsiTreeUtil.findCommonParent(interceptor.myContexts.toArray(PsiElement.EMPTY_ARRAY));
        if (commonParent instanceof PsiExpression) {
          context = commonParent;
        } else {
          PsiParameterListOwner parent = PsiTreeUtil.getParentOfType(body, PsiMethod.class, PsiLambdaExpression.class);
          if (parent instanceof PsiMethod) {
            context = ((PsiMethod)parent).getNameIdentifier();
          }
          else if (parent instanceof PsiLambdaExpression) {
            context = parent.getParameterList();
          }
        }
      }
      registerError(context != null ? context : body,
                    InspectionGadgetsBundle.message("suspicious.comparator.compare.descriptor.non.reflexive"));
    }

    private static final class ComparatorListener implements JavaDfaListener {
      private final PsiParameterListOwner myOwner;
      private final Set<PsiElement> myContexts = new HashSet<>();
      LongRangeSet myRange = LongRangeSet.empty();

      private ComparatorListener(PsiParameterListOwner owner) {
        myOwner = owner;
      }

      @Override
      public void beforeValueReturn(@NotNull DfaValue value,
                                    @Nullable PsiExpression expression,
                                    @NotNull PsiElement owner,
                                    @NotNull DfaMemoryState state) {
        if (owner != myOwner || expression == null) return;
        myContexts.add(expression);
        myRange = myRange.join(DfIntType.extractRange(state.getDfType(value)));
      }
    }

    private static final class ParameterAccessVisitor extends JavaRecursiveElementWalkingVisitor {

      private final Set<PsiParameter> parameters;

      private ParameterAccessVisitor(PsiParameter @NotNull [] parameters) {
        this.parameters = ContainerUtil.set(parameters);
      }

      @Override
      public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
        super.visitReferenceExpression(expression);
        if (expression.getQualifierExpression() != null) {
          // optimization
          // references to parameters are never qualified
          return;
        }
        final PsiElement target = expression.resolve();
        if (!(target instanceof PsiParameter)) {
          return;
        }
        final PsiParameter parameter = (PsiParameter)target;
        parameters.remove(parameter);
        if (parameters.isEmpty()) {
          stopWalking();
        }
      }

      private Collection<PsiParameter> getUnusedParameters() {
        return Collections.unmodifiableSet(parameters);
      }
    }
  }
}