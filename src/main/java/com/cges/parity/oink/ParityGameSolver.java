package com.cges.parity.oink;

public interface ParityGameSolver<S> {
  ParityGame.Solution<S> solve(ParityGame<S> parityGame);
}
