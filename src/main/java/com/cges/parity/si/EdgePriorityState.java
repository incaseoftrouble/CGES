package com.cges.parity.si;

import com.cges.algorithm.SuspectGame;

@SuppressWarnings("unchecked")
public record EdgePriorityState<S>(Object automatonState, Object gameState) {
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
    return "[%s,%s]".formatted(automatonState, gameState);
  }
}
