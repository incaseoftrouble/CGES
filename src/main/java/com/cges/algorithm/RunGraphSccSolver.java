package com.cges.algorithm;

import com.cges.graph.RunGraph;
import com.cges.graph.RunGraph.RunState;
import com.google.common.collect.Lists;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import owl.automaton.algorithm.SccDecomposition;

public final class RunGraphSccSolver {
  private RunGraphSccSolver() {}

  public static <S> List<RunState<S>> searchShort(RunGraph<S> graph) {
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

      for (RunState<S> successor : graph.successors(current)) {
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
}
