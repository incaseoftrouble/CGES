package com.cges.parity;

import java.util.Map;
import java.util.Set;

public record Solution<S>(Set<S> oddWinning, Map<S, S> oddStrategy) {
    public Player winner(S state) {
        return oddWinning.contains(state) ? Player.ODD : Player.EVEN;
    }
}
