package com.cges.output;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.PayoffAssignment;
import java.util.Comparator;
import java.util.stream.Collectors;

public final class Formatter {
  private Formatter() {}

  public static String format(PayoffAssignment payoff, ConcurrentGame<?> game) {
    return game.agents().stream()
        .sorted(Comparator.comparing(Agent::name))
        .map(a -> "%s:%s".formatted(a.name(), payoff.map(a)))
        .collect(Collectors.joining(",", "[", "]"));
  }
}
