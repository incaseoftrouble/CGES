package com.cges.graph;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import java.util.stream.Stream;
import owl.ltl.Formula;

public record MockHistoryGame<S>(ConcurrentGame<S> concurrentGame) implements HistoryGame<S> {
    record MockHistoryState<S>(S state, MockHistoryGame<S> game) implements HistoryGame.HistoryState<S> {
        @Override
        public Formula goal(Agent agent) {
            return agent.goal();
        }

        @Override
        public String toString() {
            return state.toString();
        }
    }

    @Override
    public HistoryState<S> initialState() {
        return new MockHistoryState<>(this.concurrentGame.initialState(), this);
    }

    @Override
    public Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state) {
        return concurrentGame.transitions(state.state()).stream()
                .map(Transition.transform(s -> new MockHistoryState<>(s, this)));
    }
}
