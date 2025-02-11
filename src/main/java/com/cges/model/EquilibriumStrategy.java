package com.cges.model;

import com.cges.algorithm.PunishmentStrategy;
import com.cges.graph.RunGraph;
import java.util.Map;
import java.util.stream.Collectors;

public record EquilibriumStrategy<S>(AcceptingLasso<S> lasso, Map<RunGraph.RunState<S>, Move> moves,
                PunishmentStrategy<S> punishmentStrategy) {
    @Override
    public String toString() {
        return "TRANSIENT"
                        + lasso.transientStates().map(state -> state + "->" + moves.get(state))
                                        .collect(Collectors.joining("\n", "\n", "\n"))
                        + "LOOP" + lasso.loopStates(false).map(state -> state + "->" + moves.get(state))
                                        .collect(Collectors.joining("\n", "\n", ""));
    }
}
