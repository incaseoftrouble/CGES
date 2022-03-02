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

public final class RunGraphSccSolver {
  private RunGraphSccSolver() {}

  public static List<RunGraph.State> solve(RunGraph graph) {
    // TODO This is a bit messy, to obtain minimal lasso probably should run a dijkstra on the graph and for each encountered accepting
    //  state run a BFS searching for a loop back to itself in lockstep

    Set<RunGraph.State> acceptingStates = graph.states().stream().filter(RunGraph.State::accepting).collect(Collectors.toSet());
    List<Set<RunGraph.State>> sccs = SccDecomposition.of(graph.initialStates(), graph::successors).sccsWithoutTransient()
        .stream()
        .filter(scc -> !Sets.intersection(scc, acceptingStates).isEmpty())
        .toList();
    Set<RunGraph.State> statesInAcceptingSccs = sccs.stream().flatMap(Collection::stream).collect(Collectors.toSet());

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
      return List.of();
    }
    RunGraph.State recurrentState = path.get(0);
    Set<RunGraph.State> scc = sccs.stream().filter(c -> c.contains(recurrentState)).findAny().orElseThrow();

    List<RunGraph.State> sccPath = new ArrayList<>();
    {
      Set<RunGraph.State> states = new HashSet<>(List.of(recurrentState));
      Queue<RunGraph.State> queue = new ArrayDeque<>(states);
      Map<RunGraph.State, RunGraph.State> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunGraph.State current = queue.poll();
        if (current.accepting()) {
          while (!current.equals(recurrentState)) {
            sccPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunGraph.State successor : Sets.intersection(graph.successors(current), scc)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    RunGraph.State acceptingState = sccPath.isEmpty() ? recurrentState : sccPath.get(0);
    List<RunGraph.State> sccRecurrentPath = new ArrayList<>();
    {
      Set<RunGraph.State> states = new HashSet<>(graph.successors(acceptingState));
      Queue<RunGraph.State> queue = new ArrayDeque<>(states);
      Map<RunGraph.State, RunGraph.State> predecessor = new HashMap<>();
      for (RunGraph.State state : states) {
        predecessor.put(state, acceptingState);
      }

      while (!queue.isEmpty()) {
        RunGraph.State current = queue.poll();
        if (current.equals(acceptingState)) {
          do {
            sccRecurrentPath.add(current);
            current = predecessor.get(current);
          } while (!current.equals(acceptingState));
          break;
        }
        for (RunGraph.State successor : Sets.intersection(graph.successors(current), scc)) {
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
}
