package com.cges.algorithm;

import com.cges.algorithm.RunGraph.RunState;
import com.cges.model.AcceptingLasso;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import owl.automaton.algorithm.SccDecomposition;

public final class RunGraphSccSolver {
  private RunGraphSccSolver() {}

  public static <S> Optional<AcceptingLasso<S>> solve(RunGraph<S> graph) {
    // TODO This is a bit messy, to obtain minimal lasso probably should run a dijkstra on the graph and for each encountered accepting
    //  state run a BFS searching for a loop back to itself in lockstep

    Set<RunState<S>> acceptingStates = graph.states().stream().filter(RunState::accepting).collect(Collectors.toSet());
    List<Set<RunState<S>>> sccs = SccDecomposition.of(graph.initialStates(), graph::successors).sccsWithoutTransient()
        .stream()
        .filter(scc -> !Sets.intersection(scc, acceptingStates).isEmpty())
        .toList();
    Set<RunState<S>> statesInAcceptingSccs = sccs.stream().flatMap(Collection::stream).collect(Collectors.toSet());

    List<RunState<S>> path = new ArrayList<>();
    {
      Set<RunState<S>> states = new HashSet<>(graph.initialStates());
      Queue<RunState<S>> queue = new ArrayDeque<>(states);
      Map<RunState<S>, RunState<S>> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunState<S> current = queue.poll();
        if (statesInAcceptingSccs.contains(current)) {
          while (current != null) {
            path.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunState<S> successor : graph.successors(current)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }
    if (path.isEmpty()) {
      return Optional.empty();
    }
    RunState<S> recurrentState = path.get(0);
    Set<RunState<S>> scc = sccs.stream().filter(c -> c.contains(recurrentState)).findAny().orElseThrow();

    List<RunState<S>> sccPath = new ArrayList<>();
    {
      Set<RunState<S>> states = new HashSet<>(List.of(recurrentState));
      Queue<RunState<S>> queue = new ArrayDeque<>(states);
      Map<RunState<S>, RunState<S>> predecessor = new HashMap<>();

      while (!queue.isEmpty()) {
        RunState<S> current = queue.poll();
        if (current.accepting()) {
          while (!current.equals(recurrentState)) {
            sccPath.add(current);
            current = predecessor.get(current);
          }
          break;
        }
        for (RunState<S> successor : Sets.intersection(graph.successors(current), scc)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    RunState<S> acceptingState = sccPath.isEmpty() ? recurrentState : sccPath.get(0);
    List<RunState<S>> sccRecurrentPath = new ArrayList<>();
    {
      Set<RunState<S>> states = new HashSet<>(graph.successors(acceptingState));
      Queue<RunState<S>> queue = new ArrayDeque<>(states);
      Map<RunState<S>, RunState<S>> predecessor = new HashMap<>();
      for (RunState<S> state : states) {
        predecessor.put(state, acceptingState);
      }

      while (!queue.isEmpty()) {
        RunState<S> current = queue.poll();
        if (current.equals(acceptingState)) {
          do {
            sccRecurrentPath.add(current);
            current = predecessor.get(current);
          } while (!current.equals(acceptingState));
          break;
        }
        for (RunState<S> successor : Sets.intersection(graph.successors(current), scc)) {
          if (states.add(successor)) {
            predecessor.put(successor, current);
            queue.add(successor);
          }
        }
      }
    }

    List<RunState<S>> loop = new ArrayList<>();
    loop.addAll(sccRecurrentPath);
    loop.addAll(sccPath);
    loop.addAll(path);
    return Optional.of(new AcceptingLasso<>(Lists.reverse(loop)));
  }
}
