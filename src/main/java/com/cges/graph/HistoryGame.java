package com.cges.graph;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.Transition;
import java.util.Optional;
import java.util.stream.Stream;
import owl.ltl.Formula;

public interface HistoryGame<S> {
    HistoryState<S> initialState();

    Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state);

    default Optional<Transition<HistoryState<S>>> transition(HistoryState<S> state, Move move) {
        assert transitions(state).filter(t -> t.move().equals(move)).count() <= 1;
        return transitions(state).filter(t -> t.move().equals(move)).findAny();
    }

    ConcurrentGame<S> concurrentGame();

    interface HistoryState<S> {
        S state();

        Formula goal(Agent agent);

        HistoryGame<S> game();
    }
}
