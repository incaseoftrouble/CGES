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

public class ModuleGame<S> implements ConcurrentGame<ModuleState<S>> {
  private final String name;
  private final List<String> propositions;
  private final Set<Agent> agents;
  private final Map<Agent, Integer> agentIndices;
  private final List<Module<S>> modules;
  private final ModuleState<S> initialState;
  private final Map<ModuleState<S>, BitSet> labelCache;

  public ModuleGame(String name, List<String> propositions, Collection<Module<S>> modules) {
    this.name = name;
    this.propositions = List.copyOf(propositions);

    this.modules = List.copyOf(modules);
    this.agents = this.modules.stream().map(Module::agent).collect(Collectors.toUnmodifiableSet());
    Map<Agent, Integer> agentIndices = new HashMap<>();
    Indices.forEachIndexed(this.modules, (index, module) -> agentIndices.put(module.agent(), index));

    assert Set.copyOf(agentIndices.values()).size() == agents.size();
    this.agentIndices = Map.copyOf(agentIndices);

    initialState = new ModuleState<>(List.copyOf(Lists.transform(this.modules, Module::initialState)), this.agentIndices);
    this.labelCache = new HashMap<>();
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public List<String> atomicPropositions() {
    return propositions;
  }

  @Override
  public Set<Agent> agents() {
    return Set.copyOf(agents);
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

  private BitSet label(ModuleState<S> state) {
    return labelCache.computeIfAbsent(state, s -> {
      BitSet set = new BitSet();
      Indices.forEachIndexed(state.states(), (index, agentState) -> set.or(this.modules.get(index).labels(agentState)));
      return set;
    });
  }

  @Override
  public Stream<Transition<ModuleState<S>>> transitions(ModuleState<S> state) {
    List<List<Map.Entry<Action, S>>> agentTransitions = new ArrayList<>(agents.size());
    var labels = label(state);
    Indices.forEachIndexed(state.states(), (index, agentState) ->
        agentTransitions.add(List.copyOf(this.modules.get(index).successors(agentState, labels).entrySet())));
    return Lists.cartesianProduct(agentTransitions).stream()
        .map(transition -> new Transition<>(
            new ModuleMove(Lists.transform(transition, Map.Entry::getKey), this.agentIndices),
            new ModuleState<>(Lists.transform(transition, Map.Entry::getValue), this.agentIndices)));
  }

  @Override
  public Set<String> labels(ModuleState<S> state) {
    return NatBitSets.asSet(label(state)).intStream().mapToObj(propositions::get).collect(Collectors.toSet());
  }

  private static final class ModuleMove implements Move, DotFormatted {
    private final List<Action> transition;
    private final Map<Agent, Integer> agentIndices;
    private final int hashCode;

    private ModuleMove(List<Action> transition, Map<Agent, Integer> agentIndices) {
      this.transition = List.copyOf(transition);
      this.hashCode = transition.hashCode();
      this.agentIndices = Map.copyOf(agentIndices);
    }

    @Override
    public Action action(Agent agent) {
      return transition.get(agentIndices.get(agent));
    }

    @Override
    public String toString() {
      return agentIndices.entrySet().stream()
          .sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(transition::get)
          .map(Action::name)
          .collect(Collectors.joining(",", "[", "]"));
    }

    @Override
    public String dotString() {
      return transition.stream().map(Action::name).collect(Collectors.joining(""));
    }

    @Override
    public boolean equals(Object obj) {
      assert (obj instanceof ModuleMove that && agentIndices.equals(that.agentIndices));
      if (this == obj) {
        return true;
      }
      ModuleMove that = (ModuleMove) obj;
      return hashCode == that.hashCode && transition.equals(that.transition);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
