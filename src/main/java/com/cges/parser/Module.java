package com.cges.parser;

import com.cges.model.Action;
import com.cges.model.Agent;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import owl.collections.ValuationSet;

public class Module<S> {
  private final Agent agent;
  private final S initialState;
  private final Map<S, BitSet> labels;
  private final Map<S, Map<Action, Map<S, ValuationSet>>> transitions;

  Module(Agent agent, S initialState,
      Map<S, BitSet> labels,
      Map<S, Map<ModuleTransition<S>, ValuationSet>> transitions) {
    this.agent = agent;
    this.initialState = initialState;
    this.labels = Map.copyOf(labels);
    this.transitions = transitions.entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, stateTransitions -> {
      Map<Action, Map<S, ValuationSet>> actionMap = new HashMap<>();
      for (var entry : stateTransitions.getValue().entrySet()) {
        ModuleTransition<S> transition = entry.getKey();
        ValuationSet oldVs = actionMap.computeIfAbsent(transition.action(), k -> new HashMap<>())
            .put(transition.destination(), entry.getValue());
        assert oldVs == null;
      }
      return Map.copyOf(actionMap);
    }));
  }

  public S initialState() {
    return initialState;
  }

  public Set<S> states() {
    return labels.keySet();
  }

  public S successor(S state, Action action, BitSet valuation) {
    return transitions.get(state).get(action).entrySet().stream()
        .filter(entry -> entry.getValue().contains(valuation))
        .findAny()
        .orElseThrow()
        .getKey();
  }

  public Set<S> successors(S state, Action action) {
    return transitions.get(state).get(action).keySet();
  }

  public Map<Action, S> successors(S state, BitSet valuation) {
    Map<Action, S> successors = new HashMap<>();
    transitions.get(state).forEach((action, actionTransitions) -> actionTransitions.entrySet().stream()
        .filter(e -> e.getValue().contains(valuation))
        .findAny().map(Map.Entry::getKey)
        .ifPresent(successor -> successors.put(action, successor)));
    return successors;
  }

  public Set<Action> actions(S state) {
    return transitions.get(state).keySet();
  }

  public BitSet labels(S state) {
    return labels.get(state);
  }

  public Agent agent() {
    return agent;
  }
}
