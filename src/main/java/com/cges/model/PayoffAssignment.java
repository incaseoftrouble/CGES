package com.cges.model;

import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

public record PayoffAssignment(Set<Agent> winning) {
  public boolean isLoser(Agent agent) {
    return switch (agent.payoff()) {
      case WINNING -> false;
      case LOSING -> true;
      case UNDEFINED -> !winning.contains(agent);
    };
  }

  public boolean isWinner(Agent agent) {
    return switch (agent.payoff()) {
      case WINNING -> true;
      case LOSING -> false;
      case UNDEFINED -> winning.contains(agent);
    };
  }

  public Agent.Payoff map(Agent agent) {
    return switch (agent.payoff()) {
      case WINNING -> Agent.Payoff.WINNING;
      case LOSING -> Agent.Payoff.LOSING;
      case UNDEFINED -> winning.contains(agent) ? Agent.Payoff.WINNING : Agent.Payoff.LOSING;
    };
  }

  public String format(Set<Agent> agents) {
    return agents.stream()
        .sorted(Comparator.comparing(Agent::name))
        .map(a -> "%s:%s".formatted(a.name(), map(a)))
        .collect(Collectors.joining(",", "[", "]"));
  }
}
