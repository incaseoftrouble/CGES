package com.cges.algorithm;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import java.util.stream.Stream;
import owl.ltl.Formula;

public class MockHistoryGame<S> implements HistoryGame<S> {
  record MockHistoryState<S>(S state) implements HistoryGame.HistoryState<S> {
    @Override
    public S state() {
      return state;
    }

    @Override
    public Formula goal(Agent agent) {
      return agent.goal();
    }

    @Override
    public String toString() {
      return state.toString();
    }
  }

  private final ConcurrentGame<S> game;

  public MockHistoryGame(ConcurrentGame<S> game) {
    this.game = game;
  }

  @Override
  public HistoryState<S> initialState() {
    return new MockHistoryState<>(this.game.initialState());
  }

  @Override
  public Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state) {
    return game.transitions(state.state()).map(transition -> transition.withDestination(new MockHistoryState<>(transition.destination())));
  }

  @Override
  public ConcurrentGame<S> concurrentGame() {
    return game;
  }
}
