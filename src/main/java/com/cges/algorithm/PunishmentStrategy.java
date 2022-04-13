package com.cges.algorithm;

import com.cges.graph.HistoryGame;
import com.cges.model.Move;
import com.cges.parity.PriorityState;
import java.util.Set;

public interface PunishmentStrategy<S> {
  Set<PriorityState<S>> states(HistoryGame.HistoryState<S> state, Move proposedMove);

  PriorityState<S> move(PriorityState<S> state);

  Set<PriorityState<S>> successors(PriorityState<S> state);
}
