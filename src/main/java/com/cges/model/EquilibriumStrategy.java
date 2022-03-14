package com.cges.model;

import com.cges.algorithm.RunGraph;
import com.cges.algorithm.SuspectSolver;
import java.util.Map;
import java.util.stream.Collectors;

public record EquilibriumStrategy<S>(AcceptingLasso<S> lasso, Map<RunGraph.RunState<S>, Move> moves,
                                     Map<RunGraph.RunState<S>, SuspectSolver.SuspectStrategy<S>> punishmentStrategies) {
  @Override
  public String toString() {
    return lasso.states(false).map(state -> state.historyState().state() + "->" + moves.get(state)).collect(Collectors.joining(", "));
  }
}
