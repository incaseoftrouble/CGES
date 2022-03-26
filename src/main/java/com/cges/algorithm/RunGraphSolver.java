package com.cges.algorithm;

import com.cges.graph.RunGraph;
import com.cges.graph.RunGraph.RunState;
import com.cges.model.AcceptingLasso;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.model.Transition;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

public final class RunGraphSolver {
  private RunGraphSolver() {}

  private static <S> boolean validate(EquilibriumStrategy<S> strategy, RunGraph<S> graph) {
    var lasso = strategy.lasso();
    assert graph.initialStates().contains(lasso.states(true).iterator().next());
    List<RunState<S>> loopStates = lasso.loopStates(true).toList();
    assert loopStates.size() >= 2;
    boolean isAccepting = false;
    for (int i = 0; i < loopStates.size() - 1; i++) {
      RunState<S> current = loopStates.get(i);
      RunState<S> successor = loopStates.get(i + 1);
      var historySuccessor = graph.suspectGame().historyGame()
          .transition(current.historyState(), strategy.moves().get(current))
          .map(Transition::destination)
          .orElseThrow();
      assert historySuccessor.equals(successor.historyState());
      var graphTransitions = graph.transitions(current).stream()
          .filter(t -> historySuccessor.equals(t.successor().historyState()))
          .filter(t -> successor.equals(t.successor()))
          .collect(Collectors.toSet());
      assert !graphTransitions.isEmpty();
      if (!isAccepting) {
        isAccepting = graphTransitions.stream().anyMatch(RunGraph.RunTransition::accepting);
      }
    }
    // TODO Validate punishment strategies?
    return true;
  }

  public static <S> Optional<EquilibriumStrategy<S>> solve(RunGraph<S> graph) {
    Set<RunState<S>> states = new HashSet<>(graph.initialStates());
    Queue<RunState<S>> queue = new ArrayDeque<>(states);
    while (!queue.isEmpty()) {
      graph.successors(queue.poll()).forEach(s -> {
        if (states.add(s)) {
          queue.add(s);
        }
      });
    }

    var path = RunGraphBmcSolver.search(graph);
    if (path.isEmpty()) {
      return Optional.empty();
    }

    AcceptingLasso<S> lasso = new AcceptingLasso<>(path);
    Iterator<RunState<S>> iterator = lasso.states(true).iterator();
    var current = iterator.next();
    var suspectGame = graph.suspectGame();
    assert current.historyState().equals(suspectGame.initialState().historyState());
    assert iterator.hasNext();

    // Construct the sequence of moves to obtain the lasso
    Map<RunState<S>, Move> runGraphMoves = new HashMap<>();
    Map<RunState<S>, DeviationSolver.PunishmentStrategy<S>> punishmentStrategy = new HashMap<>();
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
    var strategy = new EquilibriumStrategy<>(lasso, Map.copyOf(runGraphMoves), Map.copyOf(punishmentStrategy));
    assert validate(strategy, graph);
    return Optional.of(strategy);
  }
}
