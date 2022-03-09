package com.cges.model;

import com.cges.algorithm.RunGraph;
import com.cges.algorithm.SuspectGame;
import com.cges.algorithm.SuspectParityGame;
import java.util.Map;

public record EquilibriumStrategy<S>(AcceptingLasso<S> lasso, Map<RunGraph.RunState<S>, Move> moves,
                                     Map<SuspectGame.EveState<S>, PunishmentStrategy<S>> punishmentStrategies) {
  public record PunishmentStrategy<S>(Map<SuspectParityGame.PriorityState<S>, Move> strategy) {}
}
