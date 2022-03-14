package com.cges.parity.si;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Collectors;
import javax.annotation.Nonnegative;
import owl.automaton.acceptance.ParityAcceptance;

public final class ValueVectors {
  private static final int CACHE_SIZE = 64;
  private static final ValueVector[] singleCache = new ValueVector[CACHE_SIZE];
  private static final Bottom bottom = new Bottom();
  private static final Top top = new Top();
  private static final ValueVector zero = new Dense(new int[0]);

  static {
    Arrays.setAll(ValueVectors.singleCache, i -> {
      int[] vector = new int[i + 1];
      vector[i] = 1;
      return new Dense(vector);
    });
  }

  private ValueVectors() {
    // Empty
  }

  @SuppressWarnings({"ObjectEquality", "AccessingNonPublicFieldOfAnotherObject"})
  public static Comparator<ValueVector> comparator(ParityAcceptance acceptance) {
    return (one, other) -> {
      if (one.equals(other)) {
        return 0;
      }
      if (one == bottom || other == top) {
        return -1;
      }
      if (one == top || other == bottom) {
        return 1;
      }

      int[] oneVector = ((Dense) one).vector;
      int[] otherVector = ((Dense) other).vector;
      int oneLength = oneVector.length;
      int otherLength = otherVector.length;

      if (acceptance.parity().max()) {
        if (oneLength < otherLength) {
          // Highest priority is length - 1!
          return acceptance.isAccepting(otherLength) ? 1 : -1;
        }
        if (oneLength > otherLength) {
          return acceptance.isAccepting(oneLength) ? -1 : 1;
        }
        assert oneLength != 0;

        int k = oneLength;
        do {
          k -= 1;
        } while (oneVector[k] == otherVector[k]);

        int compare = Integer.compare(oneVector[k], otherVector[k]);
        assert compare != 0;

        return acceptance.isAccepting(k) == (compare < 0) ? -1 : 1;
      }

      int k = 0;
      while (k < Math.min(oneLength, otherLength) && oneVector[k] == otherVector[k]) {
        k += 1;
      }

      if (k == oneLength) {
        int otherSmallest = k;
        while (otherSmallest < otherLength && otherVector[otherSmallest] == 0) {
          otherSmallest += 1;
        }
        return acceptance.isAccepting(otherSmallest) ? -1 : 1;
      }
      if (k == otherLength) {
        int oneSmallest = k;
        while (oneSmallest < oneLength && oneVector[oneSmallest] == 0) {
          oneSmallest += 1;
        }
        return acceptance.isAccepting(oneSmallest) ? 1 : -1;
      }


      int compare = Integer.compare(oneVector[k], otherVector[k]);
      assert compare != 0 : one + " should be different from " + other;

      return acceptance.isAccepting(k) == (compare < 0) ? -1 : 1;
    };
  }

  public static ValueVector bottom() {
    return bottom;
  }

  public static ValueVector top() {
    return top;
  }

  public static ValueVector zero() {
    return zero;
  }

  public static ValueVector single(@Nonnegative int priority) {
    if (priority < CACHE_SIZE) {
      return singleCache[priority];
    }
    int[] vector = new int[priority + 1];
    vector[priority] = 1;
    return new Dense(vector);
  }

  public static ValueVector value(int[] vector) {
    int largest = vector.length;
    do {
      largest -= 1;
    } while (largest >= 0 && vector[largest] == 0);
    if (largest == -1) {
      return zero();
    }
    return new Dense(Arrays.copyOf(vector, largest + 1));
  }

  private static class Top implements ValueVector {
    Top() {
      // Empty
    }

    @Override
    public ValueVector add(int priority) {
      return this;
    }

    @Override
    public ValueVector add(ValueVector value) {
      assert !(value instanceof Bottom);
      return this;
    }

    @Override
    public int get(int priority) {
      throw new IllegalArgumentException();
    }

    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    @Override
    public int hashCode() {
      return Top.class.hashCode();
    }

    @Override
    public String toString() {
      return "TOP";
    }
  }

  private static class Bottom implements ValueVector {
    Bottom() {
      // Empty
    }

    @Override
    public ValueVector add(int priority) {
      return this;
    }

    @Override
    public ValueVector add(ValueVector value) {
      assert !(value instanceof Top);
      return this;
    }

    @Override
    public int get(int priority) {
      throw new IllegalArgumentException();
    }

    @Override
    public boolean equals(Object o) {
      return this == o;
    }

    @Override
    public int hashCode() {
      return Bottom.class.hashCode();
    }

    @Override
    public String toString() {
      return "BOT";
    }
  }

  static class Dense implements ValueVector {
    private final int[] vector;

    @SuppressWarnings({"PMD.ArrayIsStoredDirectly", "AssignmentOrReturnOfFieldWithMutableType"})
    Dense(int[] vector) {
      assert vector.length == 0 || vector[vector.length - 1] != 0;
      this.vector = vector;
    }

    @Override
    public ValueVector add(int priority) {
      if (vector.length == 0) {
        return single(priority);
      }
      int[] vector = Arrays.copyOf(this.vector, Math.max(priority + 1, this.vector.length));
      vector[priority] += 1;
      return new Dense(vector);
    }

    @Override
    public ValueVector add(ValueVector value) {
      if (vector.length == 0
          || value instanceof Top
          || value instanceof Bottom) {
        return value;
      }
      Dense other = (Dense) value;
      if (other.vector.length == 0) {
        return this;
      }
      int[] vector = Arrays.copyOf(this.vector, Math.max(this.vector.length, other.vector.length));
      for (int i = 0; i < other.vector.length; i++) {
        vector[i] += other.vector[i];
      }
      return new Dense(vector);
    }

    @Override
    public int get(int priority) {
      return priority < vector.length ? vector[priority] : 0;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Dense)) {
        return false;
      }
      Dense other = (Dense) o;
      return Arrays.equals(vector, other.vector);
    }

    @Override
    public int hashCode() {
      return Arrays.hashCode(vector);
    }

    @Override
    public String toString() {
      String string = Arrays.stream(vector)
          .mapToObj(String::valueOf)
          .collect(Collectors.joining(","));
      return '(' + string + ')';
    }
  }
}
