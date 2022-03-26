package com.cges.parser;

import com.cges.model.Action;
import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Move;
import com.cges.model.Transition;
import com.cges.output.DotFormatted;
import com.google.common.collect.Lists;
import de.tum.in.naturals.Indices;
import de.tum.in.naturals.set.NatBitSets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import owl.ltl.LabelledFormula;

public class ModuleGame<S> implements ConcurrentGame<ModuleState<S>> {
  private static <S> BitSet label(ModuleState<S> state, List<Module<S>> modules) {
    BitSet set = new BitSet();
    Indices.forEachIndexed(state.states(), (index, agentState) -> set.or(modules.get(index).labels(agentState)));
    return set;
  }


  private final String name;
  private final List<String> propositions;
  private final Set<Agent> agents;
  private final Map<Agent, Integer> agentIndices;
  private final List<Module<S>> modules;
  private final LabelledFormula goal;
  private final ModuleState<S> initialState;
  private final Set<ModuleState<S>> states;
  private final Map<ModuleState<S>, Set<Transition<ModuleState<S>>>> transitions;

  public ModuleGame(String name, List<String> propositions, Collection<Module<S>> modules, LabelledFormula goal) {
    this.name = name;
    this.propositions = List.copyOf(propositions);

    this.modules = List.copyOf(modules);
    this.goal = goal;
    this.agents = this.modules.stream().map(Module::agent).collect(Collectors.toUnmodifiableSet());
    Map<Agent, Integer> agentIndices = new HashMap<>();
    Indices.forEachIndexed(this.modules, (index, module) -> agentIndices.put(module.agent(), index));

    assert Set.copyOf(agentIndices.values()).size() == agents.size();
    this.agentIndices = Map.copyOf(agentIndices);

    initialState = new ModuleState<>(List.copyOf(Lists.transform(this.modules, Module::initialState)), this.agentIndices);

    Map<ModuleState<S>, Set<Transition<ModuleState<S>>>> transitions = new HashMap<>();
    Set<ModuleState<S>> states = new HashSet<>(List.of(initialState));
    Queue<ModuleState<S>> queue = new ArrayDeque<>(states);
    while (!queue.isEmpty()) {
      ModuleState<S> state = queue.poll();
      var labels = label(state, this.modules);
      List<List<Map.Entry<Action, S>>> agentTransitions = new ArrayList<>(agents.size());
      ListIterator<S> iterator = state.states().listIterator();
      while (iterator.hasNext()) {
        int index = iterator.nextIndex();
        S agentState = iterator.next();
        agentTransitions.add(List.copyOf(this.modules.get(index).successors(agentState, labels).entrySet()));
      }
      var stateTransitions = Lists.cartesianProduct(agentTransitions).stream()
          .map(transition -> new Transition<>(
              new ModuleMove(Lists.transform(transition, Map.Entry::getKey), this.agentIndices),
              new ModuleState<>(Lists.transform(transition, Map.Entry::getValue), this.agentIndices)))
          .collect(Collectors.toSet());
      transitions.put(state, stateTransitions);
      for (var transition : stateTransitions) {
        var successor = transition.destination();
        if (states.add(successor)) {
          queue.add(successor);
        }
      }
    }
    this.states = states;
    this.transitions = Map.copyOf(transitions);
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
  public Set<ModuleState<S>> states() {
    return Set.copyOf(states);
  }

  @Override
  public Set<Transition<ModuleState<S>>> transitions(ModuleState<S> state) {
    assert states.contains(state);
    return transitions.getOrDefault(state, Set.of());
  }

  @Override
  public Set<String> labels(ModuleState<S> state) {
    return NatBitSets.asSet(label(state, modules)).intStream().mapToObj(propositions::get).collect(Collectors.toSet());
  }

  @Override
  public LabelledFormula goal() {
    return goal;
  }

  public Collection<Module<S>> modules() {
    return modules;
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
