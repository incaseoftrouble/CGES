package com.cges.parity.si;

import com.cges.parity.Player;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.automaton.edge.Edge;

public class StrategyIterationSolver<S> {
  private static final Player OWNER = Player.ODD;
  private final EdgeParityGame<S> game;
  private final Comparator<ValueVector> valuationComparator;
  private boolean chooseBestEdges = true;

  public StrategyIterationSolver(EdgeParityGame<S> game) {
    this.game = game;
    valuationComparator = ValueVectors.comparator(game.acceptance());
  }

  public EdgeParityGame.Solution<S> solve() {
    SetMultimap<S, Edge<S>> strategy = ImmutableSetMultimap.of();
    while (true) {
      Valuation<S> valuation = computeValuation(strategy::get);
      SetMultimap<S, Edge<S>> newStrategy = computeStrategy(valuation, strategy::get);

      if (strategy.equals(newStrategy)) {
        assert valuation.equals(computeValuation(newStrategy::get));
        Predicate<S> isWinning = state -> valuation.get(state).equals(ValueVectors.top());
        Strategy<S> winningStrategy = Strategies.completeStrategy(Strategies.of(strategy),
            s -> game.edges(s).collect(Collectors.toSet()));
        assert checkWinningStrategy(winningStrategy, valuation);
        return new EdgeParityGame.Solution<>(isWinning, winningStrategy);
      }
      strategy = newStrategy;
    }
  }

  private SetMultimap<S, Edge<S>> computeStrategy(Valuation<S> valuation, Strategy<S> oldStrategy) {
    // TODO We can compute whether the strategy changes in here

    Set<S> states = game.states(OWNER);
    SetMultimap<S, Edge<S>> strategy = HashMultimap.create(states.size(), 3);

    for (S state : states) {
      assert valuationComparator.compare(ValueVectors.zero(), valuation.get(state)) <= 0;
      ValueVector currentMaximum = valuation.get(state);

      Set<Edge<S>> maximalEdges = new HashSet<>();
      boolean improvement = false;

      for (Edge<S> edge : game.edges(state).toList()) {
        ValueVector value = valuation.get(edge);
        int compare = valuationComparator.compare(currentMaximum, value);
        if (compare < 0) {
          if (chooseBestEdges) {
            maximalEdges.clear();
            currentMaximum = value;
          }
          maximalEdges.add(edge);
          improvement = true;
        } else if (compare <= 0) {
          maximalEdges.add(edge);
        }
      }
      strategy.putAll(state, improvement
          ? maximalEdges
          : Sets.intersection(oldStrategy.apply(state), maximalEdges));

      // Check consistency
      ValueVector maximalValue = currentMaximum;
      // There is a witness for the successor value
      assert currentMaximum.equals(ValueVectors.zero()) || !strategy.get(state).isEmpty();
      // The maximal value indeed is the maximum over all edges
      assert maximalValue.equals(Stream.concat(game.edges(state).map(valuation::get),
          Stream.of(ValueVectors.zero())).max(valuationComparator).orElseThrow());
      // Strategy is unchanged iff no improvement is possible
      assert improvement == (valuationComparator.compare(valuation.get(state), maximalValue) < 0);
      assert !improvement || maximalEdges.equals(game.edges(state)
          .filter(edge -> valuation.get(edge).equals(maximalValue))
          .collect(Collectors.toSet()));
      // Never worsen value
      assert valuationComparator.compare(valuation.get(state), maximalValue) <= 0;
      assert maximalEdges.stream().allMatch(edge ->
          valuationComparator.compare(valuation.get(state), valuation.get(edge)) <= 0);
    }

    // Strategy is non-monotone
    assert strategy.entries().stream().allMatch(entry ->
        valuationComparator.compare(valuation.get(entry.getKey()),
            valuation.get(entry.getValue())) <= 0);

    return strategy;
  }

  private Valuation<S> computeValuation(Strategy<S> strategy) {
    Valuation<S> valuation = new Valuation<>();

    ImmutableSetMultimap.Builder<S, S> builder = ImmutableSetMultimap.builder();
    Set<S> states = game.states();

    for (S state : states) {
      if (game.owner(state) == OWNER) {
        for (Edge<S> edge : strategy.apply(state)) {
          builder.put(edge.successor(), state);
        }
      } else {
        game.edges(state).map(Edge::successor).forEach(successor -> builder.put(successor, state));
      }
    }
    ImmutableSetMultimap<S, S> predecessors = builder.build();

    Set<S> unstableStates = states;
    while (!unstableStates.isEmpty()) {
      Set<S> newUnstableStates = new HashSet<>(unstableStates.size());
      Valuation<S> newValuation = new Valuation<>(valuation);

      for (S state : unstableStates) {
        ValueVector newValue;
        if (game.owner(state) == OWNER) {
          Set<Edge<S>> strategyEdges = strategy.apply(state);
          assert game.edges(state).collect(Collectors.toSet()).containsAll(strategyEdges);

          Stream<ValueVector> values = Stream.concat(strategyEdges.stream().map(valuation::get),
              Stream.of(ValueVectors.zero()));
          newValue = values.max(valuationComparator).orElseThrow();
        } else {
          newValue = game.edges(state)
              .map(valuation::get)
              .min(valuationComparator)
              .orElseThrow();
        }

        newValuation.set(state, newValue);
        if (!newValue.equals(valuation.get(state))) {
          newUnstableStates.addAll(predecessors.get(state));
        }
      }

      unstableStates = newUnstableStates;
      valuation = newValuation;
    }

    // All own states have a valuation of at least zero (we could always immediately give up)
    assert game.states(OWNER).stream().map(valuation::get)
        .allMatch(v -> valuationComparator.compare(ValueVectors.zero(), v) <= 0);
    assert checkValuationIsFixedPoint(states, strategy, valuation);
    return valuation;
  }

  private boolean checkWinningStrategy(Strategy<S> strategy, Valuation<S> valuation) {
    // All winning states must have a strategy and all successors must be winning
    for (S state : game.states()) {
      if (valuation.get(state).equals(ValueVectors.top())) {
        Set<Edge<S>> edges = game.owner(state) == OWNER
            ? strategy.apply(state)
            : game.edges(state).collect(Collectors.toSet());
        assert !edges.isEmpty() : "Empty strategy!";
        for (Edge<S> edge : edges) {
          assert valuation.get(edge.successor()).equals(ValueVectors.top())
              : "Non-winning successor for " + game.owner(state);
        }
      }
    }
    return true;
  }

  private boolean checkValuationIsFixedPoint(Set<S> states, Strategy<S> strategy,
      Valuation<S> valuation) {
    Function<S, ValueVector> fixedPointEquation = state -> game.owner(state) == Player.EVEN
        ? game.edges(state).map(valuation::get).min(valuationComparator).orElseThrow()
        : Stream.concat(strategy.apply(state).stream().map(valuation::get),
            Stream.of(ValueVectors.zero())).max(valuationComparator).orElseThrow();
    return states.stream()
        .allMatch(state -> valuation.get(state).equals(fixedPointEquation.apply(state)));
  }
}
