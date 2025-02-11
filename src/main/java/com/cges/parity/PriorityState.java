package com.cges.parity;

import com.cges.graph.SuspectGame;
import it.unimi.dsi.fastutil.HashCommon;
import java.util.Objects;

@SuppressWarnings("unchecked")
public final class PriorityState<S> {
    private final Object automatonState;
    private final Object gameState;
    private final int priority;
    private final int hashCode;

    public PriorityState(Object automatonState, Object gameState, int priority) {
        this.automatonState = automatonState;
        this.gameState = gameState;
        this.priority = priority;
        this.hashCode = HashCommon.murmurHash3(priority) + Objects.hashCode(gameState)
                        + Objects.hashCode(automatonState);
    }

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

    @Override
    public boolean equals(Object o) {
        return this == o || (o instanceof PriorityState<?> that && hashCode == that.hashCode
                        && priority == that.priority && Objects.equals(gameState, that.gameState)
                        && Objects.equals(automatonState, that.automatonState));
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    public Object automatonState() {
        return automatonState;
    }

    public Object gameState() {
        return gameState;
    }

    public int priority() {
        return priority;
    }
}
