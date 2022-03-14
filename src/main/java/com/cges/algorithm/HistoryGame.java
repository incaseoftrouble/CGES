package com.cges.algorithm;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import java.util.stream.Stream;
import owl.ltl.Formula;

public interface HistoryGame<S> {
  HistoryState<S> initialState();

  Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state);

  ConcurrentGame<S> concurrentGame();

  interface HistoryState<S> {
    S state();

    Formula goal(Agent agent);
  }
}
