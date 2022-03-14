package com.cges.parity.si;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import owl.automaton.edge.Edge;

@FunctionalInterface
public interface Strategy<S> extends Function<S, Set<Edge<S>>> {
  default Optional<Edge<S>> uniqueSuccessor(S state) {
    Set<Edge<S>> successors = apply(state);
    if (successors.isEmpty()) {
      return Optional.empty();
    }
    if (successors.size() > 1) {
      throw new IllegalArgumentException("Non-deterministic strategy");
    }
    return Optional.of(successors.iterator().next());
  }

  default Set<S> successors(S state) {
    return apply(state).stream().map(Edge::successor).collect(Collectors.toSet());
  }

  default Function<S, Set<S>> asSuccessorFunction() {
    return this::successors;
  }
}
