package com.cges.parity.si;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.Multimap;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import owl.automaton.edge.Edge;

public final class Strategies {
  private Strategies() {
    // static utility
  }

  public static <S> Strategy<S> of(Map<S, Edge<S>> map) {
    return new MapStrategy<>(map);
  }

  public static <S> Strategy<S> of(Multimap<S, Edge<S>> map) {
    return new MultiMapStrategy<>(map);
  }

  public static <S> Strategy<S> completeStrategy(Strategy<S> strategy,
      Function<S, Set<Edge<S>>> successors) {
    return new CompleteStrategy<>(strategy, successors);
  }

  private static final class CompleteStrategy<S> implements Strategy<S> {
    private final Strategy<S> strategy;
    private final Function<S, Set<Edge<S>>> successors;

    public CompleteStrategy(Strategy<S> strategy, Function<S, Set<Edge<S>>> successors) {
      this.strategy = strategy;
      this.successors = successors;
    }

    @Override
    public Set<Edge<S>> apply(S state) {
      Set<Edge<S>> edges = strategy.apply(state);
      return edges.isEmpty() ? successors.apply(state) : edges;
    }

    @Override
    public String toString() {
      return "Complete:{" + strategy + '}';
    }
  }

  private static final class MapStrategy<S> implements Strategy<S> {
    private final Map<S, Edge<S>> map;

    public MapStrategy(Map<S, Edge<S>> map) {
      this.map = Map.copyOf(map);
    }

    @Override
    public Set<Edge<S>> apply(S state) {
      return Set.of(map.get(state));
    }

    @Override
    public Optional<Edge<S>> uniqueSuccessor(S state) {
      return Optional.ofNullable(map.get(state));
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(map.size() * 100);
      map.forEach((state, edge) -> builder.append(state).append(": ").append(edge).append('\n'));
      return builder.toString();
    }
  }

  private static class MultiMapStrategy<S> implements Strategy<S> {
    private final ImmutableSetMultimap<S, Edge<S>> map;

    public MultiMapStrategy(Multimap<S, Edge<S>> map) {
      this.map = ImmutableSetMultimap.copyOf(map);
    }

    @Override
    public Set<Edge<S>> apply(S state) {
      return map.get(state);
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder(map.size() * 100);
      for (Map.Entry<S, Collection<Edge<S>>> entry : map.asMap().entrySet()) {
        builder.append(entry.getKey()).append(":\n");
        for (Edge<S> edge : entry.getValue()) {
          builder.append("  ").append(edge).append('\n');
        }
      }
      return builder.toString();
    }
  }
}
