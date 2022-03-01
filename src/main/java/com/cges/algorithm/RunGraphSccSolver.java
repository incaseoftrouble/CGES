package com.cges.algorithm;

import com.cges.model.RunGraph;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import owl.automaton.algorithm.SccDecomposition;

public class RunGraphSccSolver {
  public static List<RunGraph.State> solve(RunGraph graph) {
    Set<RunGraph.State> acceptingStates = graph.states().stream().filter(RunGraph.State::accepting).collect(Collectors.toSet());
    List<SccPair> sccs = SccDecomposition.of(graph.initialStates(), graph::successors).sccsWithoutTransient()
        .stream()
        .map(scc -> new SccPair(scc, Set.copyOf(Sets.intersection(scc, acceptingStates))))
        .filter(pair -> !pair.accepting().isEmpty())
        .toList();
    Set<RunGraph.State> statesInAcceptingSccs = sccs.stream().map(SccPair::scc).flatMap(Collection::stream).collect(Collectors.toSet());

    List<RunGraph.State> path = new ArrayList<>();
    {
      Set<RunGraph.State> states = new HashSet<>(graph.initialStates());
      Queue<RunGraph.State> queue = new ArrayDeque<>(states);
      Map<RunGraph.State, RunGraph.State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunGraph.State current = queue.poll();
        if (statesInAcceptingSccs.contains(current)) {
          while (current != null) {
            path.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunGraph.State successor : graph.successors(current)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }
    if (path.isEmpty()) {
      return path;
    }
    RunGraph.State recurrentState = path.get(0);
    SccPair scc = sccs.stream().filter(pair -> pair.scc().contains(recurrentState)).findAny().orElseThrow();

    List<RunGraph.State> sccPath = new ArrayList<>();
    {
      Set<RunGraph.State> states = new HashSet<>(List.of(recurrentState));
      Queue<RunGraph.State> queue = new ArrayDeque<>(states);
      Map<RunGraph.State, RunGraph.State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunGraph.State current = queue.poll();
        if (current.accepting()) {
          while (current != null) {
            sccPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunGraph.State successor : Sets.intersection(graph.successors(current), scc.scc())) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    RunGraph.State acceptingState = sccPath.get(0);
    List<RunGraph.State> sccRecurrentPath = new ArrayList<>();
    {
      Set<RunGraph.State> states = new HashSet<>(List.of(acceptingState));
      Queue<RunGraph.State> queue = new ArrayDeque<>(states);
      Map<RunGraph.State, RunGraph.State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunGraph.State current = queue.poll();
        if (current.equals(acceptingState)) {
          while (current != null) {
            sccRecurrentPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunGraph.State successor : Sets.intersection(graph.successors(current), scc.scc())) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    List<RunGraph.State> loop = new ArrayList<>();
    loop.addAll(sccRecurrentPath);
    loop.addAll(sccPath);
    loop.addAll(path);
    return Lists.reverse(loop);
  }

  private record SccPair(Set<RunGraph.State> scc, Set<RunGraph.State> accepting) {}
}
