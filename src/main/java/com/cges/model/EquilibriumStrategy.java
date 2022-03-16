package com.cges.model;

import com.cges.algorithm.RunGraph;
import com.cges.algorithm.DeviationSolver;
import java.util.Map;
import java.util.stream.Collectors;

public record EquilibriumStrategy<S>(AcceptingLasso<S> lasso, Map<RunGraph.RunState<S>, Move> moves,
                                     Map<RunGraph.RunState<S>, DeviationSolver.PunishmentStrategy<S>> punishmentStrategies) {
  @Override
  public String toString() {
    return "TRANSIENT" + lasso.transientStates().map(state -> state + "->" + moves.get(state)).collect(Collectors.joining("\n", "\n", "\n"))
        + "LOOP" + lasso.loopStates(false).map(state -> state + "->" + moves.get(state)).collect(Collectors.joining("\n", "\n", ""));
  }
}
