package com.cges.parser;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.cges.output.DotFormatted;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ExplicitGame<S> implements ConcurrentGame<S> {
  private final String name;
  private final Set<Agent> agents;
  private final Map<String, Agent> agentsByName;
  private final SetMultimap<S, Transition<S>> transitions;
  private final S initialState;
  private final Set<S> states;
  private final List<String> atomicPropositions;
  private final Function<S, Set<String>> labels;

  public ExplicitGame(String name, Collection<Agent> agents, List<String> atomicPropositions,
      S initialState, Set<S> states, SetMultimap<S, Transition<S>> transitions, Function<S, Set<String>> labels) {
    this.name = name;
    assert states.containsAll(transitions.keys());
    assert transitions.values().stream().map(Transition::destination).allMatch(states::contains);
    assert states.contains(initialState);
    assert states.stream().map(labels).flatMap(Collection::stream).allMatch(Set.copyOf(atomicPropositions)::contains);

    this.agents = Set.copyOf(agents);
    this.atomicPropositions = List.copyOf(atomicPropositions);
    this.initialState = initialState;
    this.states = Set.copyOf(states);
    this.transitions = ImmutableSetMultimap.copyOf(transitions);
    this.labels = labels;
    this.agentsByName = agents.stream().collect(Collectors.toMap(Agent::name, a -> a));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public S initialState() {
    return initialState;
  }

  @Override
  public List<String> atomicPropositions() {
    return atomicPropositions;
  }

  @Override
  public Set<Agent> agents() {
    return Set.copyOf(agents);
  }

  @Override
  public Agent agent(String name) {
    return Objects.requireNonNull(agentsByName.get(name));
  }

  @Override
  public Stream<S> states() {
    return states.stream();
  }

  @Override
  public Stream<Transition<S>> transitions(S gameState) {
    return transitions.get(gameState).stream();
  }

  @Override
  public Set<String> labels(S state) {
    return labels.apply(state);
  }

  public static final class MapMove implements Move, DotFormatted {
    private final Map<Agent, Action> actions;
    private final int hashCode;

    public MapMove(Map<Agent, Action> actions) {
      this.actions = Map.copyOf(actions);
      this.hashCode = this.actions.hashCode();
    }

    @Override
    public Action action(Agent agent) {
      return actions.get(agent);
    }

    // TODO Can replace the map with a list and fix an ordering of the agents
    @Override
    public String toString() {
      return actions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(Action::name)
          .collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public String dotString() {
      return actions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(Action::name)
          .collect(Collectors.joining());
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj
          || (obj instanceof MapMove that
          && hashCode == that.hashCode
          && actions.equals(that.actions));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
