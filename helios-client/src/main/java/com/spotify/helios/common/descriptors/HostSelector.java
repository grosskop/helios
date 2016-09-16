/*
 * Copyright (c) 2015 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.helios.common.descriptors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@JsonIgnoreProperties(ignoreUnknown = true)
public class HostSelector extends Descriptor {

  // Use java.util.function.BiPredicate when available (java 8)
  private interface BiPredicate<T, U> {
    boolean test(T t, U u);
  }

  private static final BiPredicate<String, Object> IN_BIPREDICATE =
      new BiPredicate<String, Object>() {
        @Override
        public boolean test(final String a, final Object b) {
          @SuppressWarnings("unchecked") final Iterable<String> iterable = (Iterable<String>) b;
          return Iterables.contains(iterable, a);
        }
      };

  public enum Operator {
    EQUALS("=", new BiPredicate<String, Object>() {
      @Override
      public boolean test(final String a, final Object b) {
        return Objects.equals(a, b);
      }
    }),
    NOT_EQUALS("!=", new BiPredicate<String, Object>() {
      @Override
      public boolean test(final String a, final Object b) {
        return !Objects.equals(a, b);
      }
    }),
    IN("in", IN_BIPREDICATE),
    NOT_IN("notin", new BiPredicate<String, Object>() {
      @Override
      public boolean test(final String a, final Object b) {
        return !IN_BIPREDICATE.test(a, b);
      }
    });

    final String operatorName;
    final BiPredicate<String, Object> predicate;

    Operator(final String operatorName,
             final BiPredicate<String, Object> predicate) {
      this.operatorName = operatorName;
      this.predicate = predicate;
    }
  }

  private static final String LABEL_PATTERN = "[\\p{Alnum}\\._-]+";
  private static final String OPERAND_PATTERN = "[\\p{Alnum}\\._-]+|\\([\\p{Alnum}\\.\\s,_-]*\\)";
  private static final Pattern PATTERN = Pattern.compile(
      format("^(%s)\\s*(!=|=|in|notin)\\s*(%s)$", LABEL_PATTERN, OPERAND_PATTERN));

  private final String label;
  private final Operator operator;
  private final Object operand;

  public static HostSelector parse(final String str) {
    checkNotNull(str);

    final Matcher matcher = PATTERN.matcher(str);
    if (matcher.matches()) {
      final String label = matcher.group(1);
      final String opStr = matcher.group(2);
      final String value = matcher.group(3);

      Operator operator = null;
      for (final Operator op : Operator.values()) {
        if (op.operatorName.equals(opStr)) {
          operator = op;
        }
      }

      if (operator == null) {
        throw new IllegalArgumentException(format("Unknown operator '%s'", opStr));
      }

      if (operator == Operator.IN || operator == Operator.NOT_IN) {
        final List<String> parts = Lists.newArrayList(Sets.newHashSet(
          Arrays.asList(value.replaceAll("\\(|\\)| ", "").split(","))));
        return new HostSelector(label, operator, parts);
      } else {
        return new HostSelector(label, operator, value);
      }
    } else {
      return null;
    }
  }

  /**
   * Tests if two collections of HostSelectors are logically equal. This method can only offer an
   * attempt at answering the question and might return false negatives.
   *
   * <p>It first sorts the two collections to put the lists into an order, and then tests if each
   * item on the first list {@link #isLogicallyEqual(HostSelector, HostSelector) isLogicallyEqual}
   * to the one in the second list.
   */
  public static boolean isLogicallyEqual(Collection<HostSelector> a, Collection<HostSelector> b) {
    if (a.size() != b.size()) {
      return false;
    }

    final ArrayList<HostSelector> first = Lists.newArrayList(a);
    Collections.sort(first, ORDERING_COMPARATOR);

    final ArrayList<HostSelector> second = Lists.newArrayList(b);
    Collections.sort(second, ORDERING_COMPARATOR);

    for (Iterator<HostSelector> it1 = first.iterator(), it2 = second.iterator();
         it1.hasNext() && it2.hasNext();) {

      if (!isLogicallyEqual(it1.next(), it2.next())) {
        return false;
      }
    }
    return true;
  }

  private static final Comparator<HostSelector> ORDERING_COMPARATOR =
      new Comparator<HostSelector>() {

        @Override
        public int compare(final HostSelector a, final HostSelector b) {
          return ComparisonChain.start()
              .compare(a.label, b.label)
              .compare(a.operator, b.operator)
              .result();
        }
      };

  /**
   * Test if two HostSelectors are logical equals. HostSelectors are logically equal if either the
   * {@link #equals(Object)} test passes or if they match the same set of values but expressed
   * differently.
   *
   * <p>An example of two logically equal HostSelectors that are expressed differently would be "foo
   * = a" and "foo in (a)".</p>
   */
  public static boolean isLogicallyEqual(HostSelector a, HostSelector b) {
    if (a.equals(b)) {
      return true;
    }

    if (!a.label.equals(b.label)) {
      return false;
    }

    return isInAndEqualsWithSameValue(a, b) || isInAndEqualsWithSameValue(b, a);
  }

  private static boolean isInAndEqualsWithSameValue(HostSelector a, HostSelector b) {
    return a.operator == Operator.IN &&
           b.operator == Operator.EQUALS &&
           a.operand instanceof List &&
           ((List) a.operand).size() == 1 &&
           ((List) a.operand).get(0).equals(b.operand);
  }

  public HostSelector(
      @JsonProperty("label") final String label,
      @JsonProperty("operator") final Operator operator,
      @JsonProperty("operand") final Object operand) {
    this.label = label;
    this.operator = operator;
    this.operand = operand;
  }

  public String getLabel() {
    return label;
  }

  public Operator getOperator() {
    return operator;
  }

  public Object getOperand() {
    return operand;
  }

  /**
   * Check if the given value matches the host selectors.
   *
   * @param value Label value to test against.
   * @return True iff the value matches the host selector condition.
   */
  public boolean matches(@Nullable final String value) {
    return operator.predicate.test(value, operand);
  }

  /**
   * Return a human-readable string representation of the host selector. E.g. "A = B".
   *
   * @return A human-readable representation of the host selector.
   */
  public String toPrettyString() {
    return format("%s %s %s", label, operator.operatorName, operand.toString());
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    final HostSelector that = (HostSelector) o;

    if (label != null ? !label.equals(that.label) : that.label != null) {
      return false;
    }
    if (operand != null ? !operand.equals(that.operand) : that.operand != null) {
      return false;
    }
    if (operator != that.operator) {
      return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int result = label != null ? label.hashCode() : 0;
    result = 31 * result + (operator != null ? operator.hashCode() : 0);
    result = 31 * result + (operand != null ? operand.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return "HostSelector{" +
           "label='" + label + '\'' +
           ", operator=" + operator +
           ", operand=" + operand +
           '}';
  }
}
