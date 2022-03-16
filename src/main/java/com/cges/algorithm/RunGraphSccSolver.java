package com.cges.algorithm;

import com.cges.algorithm.RunGraph.RunState;
import com.cges.model.AcceptingLasso;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import owl.automaton.algorithm.SccDecomposition;

public final class RunGraphSccSolver {
  private RunGraphSccSolver() {}

  private static <S> List<RunState<S>> searchShortest(RunGraph<S> graph) {
    List<Set<RunState<S>>> decomposition = SccDecomposition.of(graph.initialStates(), graph::successors).sccsWithoutTransient();

    Map<RunState<S>, List<RunState<S>>> shortestAcceptingCycle = new HashMap<>();
    for (Set<RunState<S>> scc : decomposition) {
      for (RunState<S> root : scc) {
        Set<RunState<S>> states = graph.transitions(root).stream()
            .filter(t -> t.accepting() && scc.contains(t.successor()))
            .map(RunGraph.RunTransition::successor)
            .collect(Collectors.toSet());
        if (states.isEmpty()) {
          continue;
        }
        Map<RunState<S>, RunState<S>> predecessor = new HashMap<>();
        for (RunState<S> state : states) {
          predecessor.put(state, null);
        }

        Queue<RunState<S>> queue = new ArrayDeque<>(states);
        List<RunState<S>> path = new ArrayList<>();
        while (!queue.isEmpty()) {
          RunState<S> current = queue.poll();
          if (current.equals(root)) {
            RunState<S> pathState = current;
            while (pathState != null) {
              path.add(pathState);
              pathState = predecessor.get(pathState);
            }
            break;
          }
          for (RunState<S> successor : graph.successors(current)) {
            if (scc.contains(successor) && states.add(successor)) {
              predecessor.put(successor, current);
              queue.add(successor);
            }
          }
        }
        assert !path.isEmpty();
        shortestAcceptingCycle.put(root, path);
      }
    }
    if (shortestAcceptingCycle.isEmpty()) {
      return List.of();
    }

    Map<RunState<S>, List<RunState<S>>> shortestCycles = new HashMap<>();
    for (List<RunState<S>> cycle : shortestAcceptingCycle.values()) {
      int cycleSize = cycle.size();
      for (RunState<S> loopState : cycle) {
        List<RunState<S>> shortestCycle = shortestCycles.get(loopState);
        if (shortestCycle == null || cycleSize < shortestCycle.size()) {
          shortestCycles.put(loopState, cycle);
        }
      }
    }

    Object2IntMap<RunState<S>> minimalDistance = new Object2IntOpenHashMap<>();
    minimalDistance.defaultReturnValue(-1);
    graph.initialStates().forEach(initial -> minimalDistance.put(initial, 0));
    Queue<RunState<S>> queue = new PriorityQueue<>(Comparator.comparingInt(minimalDistance::getInt));
    queue.addAll(graph.initialStates());

    int shortestCycleTotalLength = Integer.MAX_VALUE;
    @Nullable
    List<RunState<S>> shortestCycle = null;
    @Nullable
    RunState<S> shortestCycleEntry = null;
    Map<RunState<S>, RunState<S>> predecessor = new HashMap<>();

    while (!queue.isEmpty()) {
      RunState<S> current = queue.poll();
      Set<RunState<S>> successors = graph.successors(current);
      int distance = minimalDistance.getInt(current);
      assert 0 <= distance && distance < Integer.MAX_VALUE;
      if (shortestCycleTotalLength <= distance) {
        break;
      }
      var cycle = shortestCycles.get(current);
      if (cycle != null) {
        int totalLength = cycle.size() + distance;
        if (totalLength < shortestCycleTotalLength) {
          shortestCycle = cycle;
          shortestCycleEntry = current;
          shortestCycleTotalLength = totalLength;
        }
      }

      for (RunState<S> successor : successors) {
        if (minimalDistance.putIfAbsent(successor, distance + 1) == -1) {
          predecessor.put(successor, current);
          queue.add(successor);
        }
      }
    }

    assert shortestCycle != null;
    List<RunState<S>> path = new ArrayList<>();
    int index = shortestCycle.indexOf(shortestCycleEntry);
    int cycleSize = shortestCycle.size();
    for (int i = 0; i < cycleSize; i++) {
      path.add(shortestCycle.get((index + i) % cycleSize));
    }
    path.add(shortestCycleEntry);
    Lists.reverse(path);
    RunState<S> transientState = predecessor.get(shortestCycleEntry);
    while (transientState != null) {
      path.add(transientState);
      transientState = predecessor.get(transientState);
    }
    return Lists.reverse(path);
  }

  public static <S> Optional<EquilibriumStrategy<S>> solve(RunGraph<S> graph) {
    var path = searchShortest(graph);
    if (path.isEmpty()) {
      return Optional.empty();
    }

    AcceptingLasso<S> lasso = new AcceptingLasso<>(path);
    assert graph.initialStates().contains(lasso.states(true).iterator().next());

    Iterator<RunState<S>> iterator = lasso.states(true).iterator();
    var current = iterator.next();
    var suspectGame = graph.suspectGame();
    assert current.historyState().equals(suspectGame.initialState().historyState());
    assert iterator.hasNext();

    // Construct the sequence of moves to obtain the lasso
    Map<RunGraph.RunState<S>, Move> runGraphMoves = new HashMap<>();
    Map<RunGraph.RunState<S>, DeviationSolver.PunishmentStrategy<S>> punishmentStrategy = new HashMap<>();
    while (iterator.hasNext()) {
      var next = iterator.next();
      // TODO Bit ugly, could maybe store this information when constructing the lasso
      Move move = suspectGame.historyGame().transitions(current.historyState())
          .filter(t -> t.destination().equals(next.historyState()))
          .map(Transition::move)
          .iterator().next();
      runGraphMoves.put(current, move);

      // For each deviation, provide a proof that we can punish someone
      punishmentStrategy.put(current, graph.deviationStrategy(current.historyState()));
      current = next;
    }
    return Optional.of(new EquilibriumStrategy<>(lasso, Map.copyOf(runGraphMoves), Map.copyOf(punishmentStrategy)));
  }
}
