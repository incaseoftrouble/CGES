package com.cges.model;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import owl.ltl.Formula;

public final class Agent {
  public enum Payoff {
    WINNING, LOSING, UNDEFINED;

    @Override
    public String toString() {
      return switch (this) {
        case WINNING -> "W";
        case LOSING -> "L";
        case UNDEFINED -> "?";
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
    this.actions = actions.stream().collect(Collectors.toMap(Action::name, a -> a));
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

  public Action action(String name) {
    return Objects.requireNonNull(actions.get(name));
  }

  public Payoff payoff() {
    return payoff;
  }

  @Override
  public String toString() {
    return "A[%s,%s]@{%s}".formatted(name, payoff, actions.values().stream().map(Action::name).sorted().collect(Collectors.joining(",")));
  }
}
