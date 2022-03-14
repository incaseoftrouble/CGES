package com.cges.parity.oink;

import com.cges.algorithm.SuspectGame;

@SuppressWarnings("unchecked")
public record PriorityState<S>(Object automatonState, Object gameState, int priority) {
  public boolean isEve() {
    return gameState instanceof SuspectGame.EveState;
  }

  public SuspectGame.AdamState<S> adam() {
    return (SuspectGame.AdamState<S>) gameState;
  }

  public SuspectGame.EveState<S> eve() {
    return (SuspectGame.EveState<S>) gameState;
  }

  @Override
  public String toString() {
    return "[%s,%s,%d]".formatted(automatonState, gameState, priority);
  }
}
