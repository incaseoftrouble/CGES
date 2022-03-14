package com.cges.parser;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.cges.output.DotFormatted;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import de.tum.in.naturals.Indices;
import de.tum.in.naturals.set.NatBitSets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.factories.ValuationSetFactory;

public class ModuleGame<S> implements ConcurrentGame<ModuleState<S>> {
  private final String name;
  private final ValuationSetFactory factory;
  private final Set<Agent> agents;
  private final Map<Agent, Integer> agentIndices;
  private final List<Module<S>> modules;
  private final ModuleState<S> initialState;

  public ModuleGame(String name, ValuationSetFactory factory, Collection<Module<S>> modules) {
    this.name = name;
    this.factory = factory;

    this.modules = List.copyOf(modules);
    this.agents = modules.stream().map(Module::agent).collect(Collectors.toUnmodifiableSet());

    Map<Agent, Integer> agentIndices = new HashMap<>();
    Indices.forEachIndexed(agents, (index, agent) -> agentIndices.put(agent, index));
    this.agentIndices = Map.copyOf(agentIndices);

    initialState = new ModuleState<>(Lists.transform(this.modules, Module::initialState), this.agentIndices);
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<String> atomicPropositions() {
    return factory.atomicPropositions();
  }

  @Override
  public Set<Agent> agents() {
    return agents;
  }

  @Override
  public ModuleState<S> initialState() {
    return initialState;
  }

  @Override
  public Stream<ModuleState<S>> states() {
    return Sets.cartesianProduct(modules.stream().map(Module::states).toList()).stream()
        .map(product -> new ModuleState<>(product, agentIndices));
  }

  @Override
  public Stream<Transition<ModuleState<S>>> transitions(ModuleState<S> state) {
    BitSet labels = labelSet(state);
    List<List<Map.Entry<Action, S>>> agentTransitions = new ArrayList<>(agents.size());
    Indices.forEachIndexed(state.states(), (index, agentState) ->
        agentTransitions.add(List.copyOf(modules.get(index).successors(agentState, labels).entrySet())));
    return Lists.cartesianProduct(agentTransitions).stream()
        .map(transition -> new Transition<>(new EntryMove<>(transition, agentIndices),
            new ModuleState<>(Lists.transform(transition, Map.Entry::getValue), agentIndices)));
  }

  @Override
  public Set<String> labels(ModuleState<S> state) {
    return NatBitSets.asSet(labelSet(state)).intStream().mapToObj(factory.atomicPropositions()::get).collect(Collectors.toSet());
  }

  private BitSet labelSet(ModuleState<S> state) {
    BitSet labels = new BitSet();
    Indices.forEachIndexed(state.states(), (index, agentState) -> labels.or(modules.get(index).labels(agentState)));
    return labels;
  }

  private record EntryMove<S>(List<Map.Entry<Action, S>> transition, Map<Agent, Integer> agentIndices)
      implements Move, DotFormatted {
    @Override
    public Action action(Agent agent) {
      return transition.get(agentIndices.get(agent)).getKey();
    }

    @Override
    public String toString() {
      return agentIndices.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(transition::get)
          .map(Map.Entry::getKey)
          .map(Action::name)
          .collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public String dotString() {
      return transition.stream().map(Map.Entry::getKey).map(Action::name).collect(Collectors.joining(""));
    }
  }
}
