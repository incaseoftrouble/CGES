package com.cges.algorithm;

import com.cges.model.AcceptingLasso;
import com.cges.model.EquilibriumStrategy;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.google.common.collect.Iterators;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class StrategyMapper {
  public interface PunishmentStrategy {

  }

  private StrategyMapper() {}

  public static <S> EquilibriumStrategy<S> createStrategy(SuspectGame<S> suspectGame,
      SuspectSolver.SuspectSolution<S> suspectSolution, AcceptingLasso<S> lasso) {
    Iterator<RunGraph.RunState<S>> iterator = lasso.states(true).iterator();
    var current = iterator.next();
    assert iterator.hasNext();

    // Construct the sequence of moves to obtain the lasso
    Map<RunGraph.RunState<S>, Move> runGraphMoves = new HashMap<>();
    while (iterator.hasNext()) {
      var next = iterator.next();
      // TODO Bit ugly, could store this information when constructing the lasso
      Move move = Iterators.getOnlyElement(suspectGame.historyGame().transitions(current.eveState().historyState())
          .filter(t -> t.destination().equals(next.eveState().historyState()))
          .map(Transition::move)
          .iterator());
      runGraphMoves.put(current, move);

      var eveSuccessors = suspectGame.successors(new SuspectGame.AdamState<S>(current.eveState(), move));
      assert eveSuccessors.contains(next.eveState());

      // For each deviation, provide a proof that we can punish someone
      // TODO What if the deviation is also a winning state?
      Set<SuspectGame.EveState<S>> deviations = eveSuccessors.stream()
          .filter(state -> !next.eveState().equals(state))
          .collect(Collectors.toSet());
      current = next;
    }

    return new EquilibriumStrategy<>(lasso, Map.copyOf(runGraphMoves), Map.of());
  }
}
