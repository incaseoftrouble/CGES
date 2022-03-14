package com.cges.parity.si;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import owl.automaton.edge.Edge;

class Valuation<S> {
  private static final ValueVector DEFAULT = ValueVectors.top();
  private final Map<S, ValueVector> values;

  public Valuation() {
    values = new HashMap<>();
  }

  public Valuation(Valuation<S> valuation) {
    values = new HashMap<>(valuation.values);
  }

  public ValueVector get(S state) {
    ValueVector vector = values.get(state);
    return vector == null ? DEFAULT : vector;
  }

  public ValueVector get(Edge<S> edge) {
    ValueVector vector = get(edge.successor());
    return edge.hasAcceptanceSets()
        ? vector.add(edge.smallestAcceptanceSet())
        : vector;
  }

  public ValueVector set(S state, ValueVector value) {
    ValueVector oldValue = value.equals(DEFAULT)
        ? values.remove(state) : values.put(state, value);
    return oldValue == null ? DEFAULT : oldValue;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Valuation<?> valuation)) {
      return false;
    }
    return Objects.equals(values, valuation.values);
  }

  @Override
  public int hashCode() {
    return values.hashCode() * 31;
  }

  @Override
  public String toString() {
    return values.entrySet().stream()
        .map(entry -> entry.getKey() + ": " + entry.getValue())
        .collect(Collectors.joining("\n"));
  }

  public String toString(Collection<S> states) {
    return states.stream()
        .map(state -> state + ": " + get(state))
        .collect(Collectors.joining("\n"));
  }
}