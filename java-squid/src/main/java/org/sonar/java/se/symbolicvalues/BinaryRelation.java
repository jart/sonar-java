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
package org.sonar.java.se.symbolicvalues;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import org.sonar.java.se.symbolicvalues.RelationalSymbolicValue.Kind;
import org.sonar.plugins.java.api.tree.Tree;

import javax.annotation.CheckForNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public abstract class BinaryRelation {

  public static class TransitiveRelationExceededException extends RuntimeException {
    public TransitiveRelationExceededException() {
      super("Number of transitive relations exceeded!");
    }
  }

  protected final Kind kind;
  protected final SymbolicValue leftOp;
  protected final SymbolicValue rightOp;
  protected final List<Tree> expressions;
  private final int[] key;

  protected BinaryRelation(Kind kind, SymbolicValue v1, SymbolicValue v2, List<Tree> expressions) {
    this.kind = kind;
    leftOp = v1;
    rightOp = v2;
    this.expressions = expressions;
    if (leftOp.id() < rightOp.id()) {
      key = new int[] {leftOp.id(), rightOp.id()};
    } else {
      key = new int[] {rightOp.id(), leftOp.id()};
    }

  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, key[0], key[1]);
  }

  @Override
  public boolean equals(Object obj) {
    if (obj instanceof BinaryRelation) {
      return equalsRelation((BinaryRelation) obj);
    }
    return false;
  }

  private boolean equalsRelation(BinaryRelation rel) {
    if (kind.equals(rel.kind)) {
      return key[0] == rel.key[0] && key[1] == rel.key[1];
    }
    return false;
  }

  @Override
  public String toString() {
    StringBuilder buffer = new StringBuilder();
    buffer.append(leftOp);
    buffer.append(kind.operand);
    buffer.append(rightOp);
    return buffer.toString();
  }

  protected RelationState resolveState(Collection<BinaryRelation> knownRelations) {
    return resolveState(knownRelations, new HashSet<BinaryRelation>());
  }

  @CheckForNull
  protected RelationState resolveState(Collection<BinaryRelation> knownRelations, Set<BinaryRelation> usedRelations) {
    if (knownRelations.isEmpty()) {
      return RelationState.UNDETERMINED;
    }
    if (usedRelations.size() > 200) {
      throw new TransitiveRelationExceededException();
    }
    for (BinaryRelation relation : knownRelations) {
      RelationState result = relation.implies(this);
      if (result.isDetermined()) {
        result.cause = relation;
        return result;
      }
      usedRelations.add(relation);
      usedRelations.add(relation.symmetric());
    }
    Collection<BinaryRelation> transitiveReduction = transitiveReduction(knownRelations, usedRelations);
    if (transitiveReduction.isEmpty()) {
      // If no new combination, try with the symmetric (because transitive reduction only checks the operand on the left side.
      transitiveReduction = symmetric().transitiveReduction(knownRelations, usedRelations);
    }
    return resolveState(transitiveReduction, usedRelations);
  }

  /**
   * Returns a new list of relations, built by combining one of the known relations that can be combined transitively with the receiver
   * 
   * @param knownRelations the list of relations known so far
   * @param usedRelations the list of relation that have been used in past recursive checks
   * @return a list of relations containing at least one new relation; empty if no new combination was found.
   */
  private Collection<BinaryRelation> transitiveReduction(Collection<BinaryRelation> knownRelations, Set<BinaryRelation> usedRelations) {
    boolean changed = false;
    List<BinaryRelation> result = new ArrayList<>();
    for (BinaryRelation relation : knownRelations) {
      boolean used = false;
      if (leftOp.equals(relation.leftOp)) {
        used = result.addAll(relation.combinedRelations(knownRelations, usedRelations));
      } else if (leftOp.equals(relation.rightOp)) {
        used = result.addAll(relation.symmetric().combinedRelations(knownRelations, usedRelations));
      }
      if (used) {
        changed = true;
      } else {
        result.add(relation);
      }
    }
    return changed ? result : Collections.<BinaryRelation>emptyList();
  }

  private Collection<BinaryRelation> combinedRelations(Collection<BinaryRelation> relations, Set<BinaryRelation> usedRelations) {
    List<BinaryRelation> result = new ArrayList<>();
    for (BinaryRelation relation : relations) {
      if (!this.equals(relation)) {
        BinaryRelation combined = combineUnordered(relation);
        if (combined != null && !usedRelations.contains(combined)) {
          //add expressions to the combined relation
          combined.expressions.addAll(this.expressions);
          result.add(combined);
        }
      }
    }
    return result;
  }

  /**
   * Create a new relation, if any, that is a transitive combination of the receiver with the supplied relation.
   * @param relation another SymbolicValueRelation
   * @return a SymbolicValueRelation or null if the receiver and the supplied relation cannot be combined
   */
  @VisibleForTesting
  @CheckForNull
  BinaryRelation combineUnordered(BinaryRelation relation) {
    BinaryRelation combined = null;
    if (rightOp.equals(relation.leftOp)) {
      combined = relation.combineOrdered(this);
    } else if (rightOp.equals(relation.rightOp)) {
      combined = relation.symmetric().combineOrdered(this);
    }
    return combined;
  }

  /**
   * Create a new relation, if any, that is a transitive combination of the receiver with the supplied relation.
   * Schema aSb & bRc => aTc
   * where aRb is the relation described by the receiver, bSc that of the supplied relation, aTc is the returned relation.
   * 
   * @param relation another SymbolicValueRelation that can be combined transitively with the receiver.
   * @return a SymbolicValueRelation or null if the receiver and the supplied relation cannot be combined
   */
  @CheckForNull
  private BinaryRelation combineOrdered(BinaryRelation relation) {
    Preconditions.checkArgument(leftOp.equals(relation.rightOp), "Transitive condition not matched!");
    if (rightOp.equals(relation.leftOp)) {
      return conjunction(relation.symmetric());
    }
    return combinedAfter(relation);
  }

  /**
   * Returns a new relation resulting of the conjunction of the receiver with the supplied relation.
   * Schema aRb & aSb => aTb
   * where aRb is the relation described by the receiver, bSc that of the supplied relation, aTc is the returned relation.
   * 
   * @param relation another relation bearing on the same operands as those of the receiver
   * @return the resulting relation or null if the conjunction of both relations does not bring any new information
   */

  @CheckForNull
  protected BinaryRelation conjunction(BinaryRelation relation) {
    Preconditions.checkArgument(leftOp.equals(relation.leftOp) && rightOp.equals(relation.rightOp), "Conjunction condition not matched!");
    return null;
  }

  /**
   * @return a new relation, the negation of the receiver
   */
  public abstract BinaryRelation inverse();

  /**
   * @return a new relation, equivalent to the receiver with inverted parameters
   */
  protected abstract BinaryRelation symmetric();

  /**
   * @param relation a relation between symbolic values
   * @return a RelationState<ul>
   * <li>FULFILLED  if the receiver implies that the supplied relation is true</li>
   * <li>UNFULFILLED if the receiver implies that the supplied relation is false</li>
   * <li>UNDETERMINED  otherwise</li>
   * </ul>
   * @see RelationState
   */
  protected RelationState implies(BinaryRelation relation) {
    if (leftOp.equals(relation.leftOp) && rightOp.equals(relation.rightOp)) {
      return relation.isImpliedBy(this);
    } else if (leftOp.equals(relation.rightOp) && rightOp.equals(relation.leftOp)) {
      return relation.symmetric().isImpliedBy(this);
    }
    return RelationState.UNDETERMINED;
  }

  protected abstract RelationState isImpliedBy(BinaryRelation relation);

  protected abstract RelationState impliesEqual();

  protected abstract RelationState impliesNotEqual();

  protected abstract RelationState impliesMethodEquals();

  protected abstract RelationState impliesNotMethodEquals();

  protected abstract RelationState impliesGreaterThan();

  protected abstract RelationState impliesGreaterThanOrEqual();

  protected abstract RelationState impliesLessThan();

  protected abstract RelationState impliesLessThanOrEqual();

  /**
   * Returns a new relation resulting of the transitive combination of the receiver with the supplied relation.
   * Schema aRb & bSc => aTc
   * where aRb is the relation described by the receiver, bSc that of the supplied relation, aTc is the returned relation.
   * 
   * @param relation another relation whose left operand is the same as the receiver's right operand
   * @return the resulting relation or null if the conjunction of both relations does not bring any new information
   */
  @CheckForNull
  protected abstract BinaryRelation combinedAfter(BinaryRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithEqual(EqualRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithNotEqual(NotEqualRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithMethodEquals(MethodEqualsRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithNotMethodEquals(NotMethodEqualsRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithGreaterThan(GreaterThanRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithGreaterThanOrEqual(GreaterThanOrEqualRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithLessThan(LessThanRelation relation);

  @CheckForNull
  protected abstract BinaryRelation combinedWithLessThanOrEqual(LessThanOrEqualRelation relation);
}
