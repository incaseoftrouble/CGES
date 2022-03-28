package com.cges.algorithm;

import com.cges.graph.HistoryGame;
import com.cges.parity.PriorityState;
import java.util.Set;

public interface PunishmentStrategy<S> {
  PriorityState<S> initialState(HistoryGame.HistoryState<S> state);

  PriorityState<S> move(PriorityState<S> state);

  Set<PriorityState<S>> successors(PriorityState<S> state);
}
