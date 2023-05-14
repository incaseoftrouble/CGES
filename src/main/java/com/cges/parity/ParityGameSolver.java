package com.cges.parity;

public interface ParityGameSolver<S> {
    Solution<S> solve(ParityGame<S> parityGame);
}
