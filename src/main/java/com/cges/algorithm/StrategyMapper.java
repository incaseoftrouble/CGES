package com.cges.algorithm;

import com.cges.algorithm.SuspectGame.EveState;
import com.cges.model.AcceptingLasso;
import com.cges.model.Agent;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.model.Transition;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public final class StrategyMapper {
  private StrategyMapper() {}

  public static <S> EquilibriumStrategy<S>
  createStrategy(SuspectGame<S> suspectGame, SuspectSolver.SuspectSolution<S> suspectSolution, AcceptingLasso<S> lasso) {
    Iterator<RunGraph.RunState<S>> iterator = lasso.states(true).iterator();
    var current = iterator.next();
    assert current.historyState().equals(suspectGame.initialState().historyState());
    assert iterator.hasNext();

    Set<Agent> agents = suspectGame.historyGame().concurrentGame().agents();

    // Construct the sequence of moves to obtain the lasso
    Map<RunGraph.RunState<S>, Move> runGraphMoves = new HashMap<>();
    Map<RunGraph.RunState<S>, SuspectSolver.SuspectStrategy<S>> punishmentStrategy = new HashMap<>();
    while (iterator.hasNext()) {
      var next = iterator.next();
      // TODO Bit ugly, could maybe store this information when constructing the lasso
      Move move = suspectGame.historyGame().transitions(current.historyState())
          .filter(t -> t.destination().equals(next.historyState()))
          .map(Transition::move)
          .iterator().next();
      runGraphMoves.put(current, move);

      EveState<S> eveState = new EveState<>(current.historyState(), agents);
      assert suspectSolution.isWinning(eveState);
      // For each deviation, provide a proof that we can punish someone
      punishmentStrategy.put(current, suspectSolution.strategy(eveState));
      current = next;
    }
    return new EquilibriumStrategy<>(lasso, Map.copyOf(runGraphMoves), Map.copyOf(punishmentStrategy));
  }
}
