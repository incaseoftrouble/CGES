package com.cges.parser;

import com.cges.model.Agent;
import com.cges.output.DotFormatted;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ModuleState<S> implements DotFormatted {
    public static final Object[] EMPTY = new Object[0];

    private final S[] states;
    private final Map<Agent, Integer> agentIndices;
    private final int hashCode;

    @SuppressWarnings("unchecked")
    public ModuleState(List<S> states, Map<Agent, Integer> agentIndices) {
        this.states = (S[]) states.toArray(EMPTY);
        this.agentIndices = Map.copyOf(agentIndices);
        this.hashCode = Arrays.hashCode(this.states);
    }

    public S state(Agent agent) {
        return states[agentIndices.get(agent)];
    }

    @Override
    public String toString() {
        return Arrays.stream(states).map(Object::toString).collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public String dotString() {
        return Arrays.stream(states).map(DotFormatted::toDotString).collect(Collectors.joining(","));
    }

    public List<S> states() {
        return Arrays.asList(states);
    }

    @Override
    public boolean equals(Object obj) {
        assert obj instanceof ModuleState<?> that && agentIndices.equals(that.agentIndices);
        if (this == obj) {
            return true;
        }
        ModuleState<?> that = (ModuleState<?>) obj;
        return hashCode == that.hashCode && Arrays.equals(states, that.states);
    }

    @Override
    public int hashCode() {
        return hashCode;
    }
}
