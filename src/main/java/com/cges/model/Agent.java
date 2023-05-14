package com.cges.model;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import owl.ltl.Formula;

public final class Agent implements Comparable<Agent> {
    public enum Payoff {
        WINNING,
        LOSING,
        UNDEFINED;

        @Override
        public String toString() {
            return switch (this) {
                case WINNING -> "W";
                case LOSING -> "L";
                case UNDEFINED -> "?";
            };
        }

        public static Payoff parse(String string) {
            return switch (string) {
                case "1", "true" -> Agent.Payoff.WINNING;
                case "0", "false" -> Agent.Payoff.LOSING;
                case "?" -> Agent.Payoff.UNDEFINED;
                default -> throw new IllegalArgumentException("Unsupported payoff string " + string);
            };
        }
    }

    private final String name;
    private final Formula goal;
    private final Payoff payoff;
    private final Map<String, Action> actions;

    public Agent(String name, Formula goal, Payoff payoff, Collection<Action> actions) {
        this.name = name;
        this.goal = goal;
        this.payoff = payoff;
        this.actions = actions.stream().collect(Collectors.toUnmodifiableMap(Action::name, a -> a));
    }

    public String name() {
        return name;
    }

    public Formula goal() {
        return goal;
    }

    public Collection<Action> actions() {
        return actions.values();
    }

    @Override
    public int compareTo(Agent o) {
        return name.compareTo(o.name);
    }

    public Action action(String name) {
        return Objects.requireNonNull(actions.get(name));
    }

    public boolean hasAction(String name) {
        return actions.containsKey(name);
    }

    public Payoff payoff() {
        return payoff;
    }

    @Override
    public String toString() {
        return "A[%s,%s]@{%s}"
                .formatted(
                        name,
                        payoff,
                        actions.values().stream().map(Action::name).sorted().collect(Collectors.joining(",")));
    }

    @Override
    public boolean equals(Object o) {
        assert o instanceof Agent that
                && (!name.equals(that.name)
                        || (actions.equals(that.actions) && payoff.equals(that.payoff) && goal.equals(that.goal)));
        if (this == o) {
            return true;
        }
        Agent that = (Agent) o;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
