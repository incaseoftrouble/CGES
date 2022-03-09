package com.cges.parser;

import com.cges.model.Agent;
import com.cges.output.DotFormatted;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ModuleState<S>(List<S> states, Map<Agent, Integer> agents) implements DotFormatted {
  public S state(Agent agent) {
    return states.get(agents.get(agent));
  }

  @Override
  public String toString() {
    return states.stream().map(Object::toString).collect(Collectors.joining(",", "[", "]"));
  }

  @Override
  public String dotString() {
    return states.stream().map(DotFormatted::toString).collect(Collectors.joining(","));
  }
}
