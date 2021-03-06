/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.java.checks.helpers.MethodsHelper;
import org.sonar.java.checks.methods.AbstractMethodDetection;
import org.sonar.java.checks.methods.MethodMatcher;
import org.sonar.java.checks.methods.TypeCriteria;
import org.sonar.java.resolve.JavaSymbol;
import org.sonar.java.resolve.JavaType;
import org.sonar.java.tag.Tag;
import org.sonar.plugins.java.api.semantic.Type;
import org.sonar.plugins.java.api.tree.ExpressionTree;
import org.sonar.plugins.java.api.tree.MemberSelectExpressionTree;
import org.sonar.plugins.java.api.tree.MethodInvocationTree;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.Tree.Kind;
import org.sonar.squidbridge.annotations.ActivatedByDefault;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import javax.annotation.Nullable;

import java.text.MessageFormat;
import java.util.List;

@Rule(
  key = "S2175",
  name = "Inappropriate \"Collection\" calls should not be made",
  priority = Priority.CRITICAL,
  tags = {Tag.BUG})
@ActivatedByDefault
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.DATA_RELIABILITY)
@SqaleConstantRemediation("15min")
public class CollectionInappropriateCallsCheck extends AbstractMethodDetection {

  @Override
  protected List<MethodMatcher> getMethodInvocationMatchers() {
    return ImmutableList.of(
      collectionMethodInvocation("remove"),
      collectionMethodInvocation("contains")
    );
  }

  private static MethodMatcher collectionMethodInvocation(String methodName) {
    return MethodMatcher.create()
      .typeDefinition(TypeCriteria.subtypeOf("java.util.Collection"))
      .name(methodName)
      .addParameter("java.lang.Object");
  }

  @Override
  protected void onMethodInvocationFound(MethodInvocationTree tree) {
    ExpressionTree firstArgument = tree.arguments().get(0);
    Type argumentType = firstArgument.symbolType();
    Type collectionType = getMethodOwner(tree);
    // can be null when using raw types
    Type collectionParameterType = getTypeParameter(collectionType);

    // FIXME remove this variable when SONARJAVA-1298 is fixed
    boolean isCallToParametrizedMethod = isCallToParametrizedMethod(firstArgument);
    if (!isCallToParametrizedMethod && tree.methodSelect().is(Tree.Kind.MEMBER_SELECT)) {
      isCallToParametrizedMethod = isCallToParametrizedMethod(((MemberSelectExpressionTree) tree.methodSelect()).expression());
    }

    if (collectionParameterType != null && !collectionParameterType.isUnknown() && !isCallToParametrizedMethod && !isArgumentCompatible(argumentType, collectionParameterType)) {
      reportIssue(MethodsHelper.methodName(tree), MessageFormat.format("A \"{0}<{1}>\" cannot contain a \"{2}\"", collectionType, collectionParameterType, argumentType));
    }
  }

  private static boolean isCallToParametrizedMethod(ExpressionTree expressionTree) {
    if (expressionTree.is(Tree.Kind.METHOD_INVOCATION)) {
      return ((JavaSymbol.MethodJavaSymbol) ((MethodInvocationTree) expressionTree).symbol()).isParametrized();
    }
    return false;
  }

  private static Type getMethodOwner(MethodInvocationTree mit) {
    if (mit.methodSelect().is(Kind.MEMBER_SELECT)) {
      return ((MemberSelectExpressionTree) mit.methodSelect()).expression().symbolType();
    }
    return mit.symbol().owner().type();
  }

  @Nullable
  private static Type getTypeParameter(Type collectionType) {
    if (collectionType instanceof JavaType.ParametrizedTypeJavaType) {
      JavaType.ParametrizedTypeJavaType parametrizedType = (JavaType.ParametrizedTypeJavaType) collectionType;
      JavaType.TypeVariableJavaType first = Iterables.getFirst(parametrizedType.typeParameters(), null);
      if (first != null) {
        return parametrizedType.substitution(first);
      }
    }
    return null;
  }

  private static boolean isArgumentCompatible(Type argumentType, Type collectionParameterType) {
    return isSubtypeOf(argumentType, collectionParameterType)
      || isSubtypeOf(collectionParameterType, argumentType)
      || autoboxing(argumentType, collectionParameterType);
  }

  private static boolean isSubtypeOf(Type type, Type superType) {
    return type.isSubtypeOf(superType);
  }

  private static boolean autoboxing(Type argumentType, Type collectionParameterType) {
    return argumentType.isPrimitive()
      && ((JavaType) collectionParameterType).isPrimitiveWrapper()
      && isSubtypeOf(((JavaType) argumentType).primitiveWrapperType(), collectionParameterType);
  }
}
