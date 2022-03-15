package com.cges.algorithm;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import de.tum.in.naturals.Indices;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.factories.EquivalenceClassFactory;
import owl.factories.jbdd.JBddSupplier;
import owl.ltl.EquivalenceClass;
import owl.ltl.Formula;

public class FormulaHistoryGame<S> implements HistoryGame<S> {
  static final class ListHistoryState<S> implements HistoryState<S> {
    private static final EquivalenceClass[] EMPTY = new EquivalenceClass[0];

    private final S state;
    private final EquivalenceClass[] agentGoals;
    private final Map<Agent, Integer> agentIndices;
    private final int hashCode;

    ListHistoryState(S state, List<EquivalenceClass> agentGoals, Map<Agent, Integer> agentIndices) {
      this.state = state;
      this.agentGoals = agentGoals.toArray(EMPTY);
      this.agentIndices = Map.copyOf(agentIndices);
      this.hashCode = this.state.hashCode() ^ Arrays.hashCode(this.agentGoals);
    }

    @Override
    public Formula goal(Agent agent) {
      return agentGoals[agentIndices.get(agent)].canonicalRepresentative();
    }

    @Override
    public S state() {
      return state;
    }

    public List<EquivalenceClass> agentGoals() {
      return Arrays.asList(agentGoals);
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof ListHistoryState<?> that
          && hashCode == that.hashCode
          && Arrays.equals(agentGoals, that.agentGoals)
          && state.equals(that.state));
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public String toString() {
      return state + "@" + agentIndices.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(i -> agentGoals[i])
          .map(EquivalenceClass::canonicalRepresentative)
          .map(Objects::toString)
          .collect(Collectors.joining(",", "[", "]"));
    }
  }

  private final ConcurrentGame<S> game;
  private final ListHistoryState<S> initialState;
  private final SetMultimap<HistoryState<S>, Transition<HistoryState<S>>> transitions;

  public FormulaHistoryGame(ConcurrentGame<S> game) {
    this.game = game;
    EquivalenceClassFactory factory = JBddSupplier.async().getEquivalenceClassFactory(game.atomicPropositions());

    Map<String, Integer> propositionIndices = new HashMap<>();
    Indices.forEachIndexed(game.atomicPropositions(), (index, proposition) -> propositionIndices.put(proposition, index));
    Map<Agent, Integer> agentIndices = new HashMap<>();
    Indices.forEachIndexed(game.agents(), (index, agent) -> agentIndices.put(agent, index));
    Map<Agent, Integer> indices = Map.copyOf(agentIndices);

    this.initialState = new ListHistoryState<>(game.initialState(),
        game.agents().stream().map(Agent::goal).map(factory::of).map(EquivalenceClass::unfold).toList(), indices);

    ImmutableSetMultimap.Builder<HistoryState<S>, Transition<HistoryState<S>>> transitions = ImmutableSetMultimap.builder();
    Set<ListHistoryState<S>> states = new HashSet<>(List.of(initialState));
    Queue<ListHistoryState<S>> queue = new ArrayDeque<>(states);
    Map<S, BitSet> labelCache = new HashMap<>();
    while (!queue.isEmpty()) {
      ListHistoryState<S> state = queue.poll();

      BitSet valuation = labelCache.computeIfAbsent(state.state(), s -> {
        BitSet set = new BitSet();
        game.labels(s).stream().map(propositionIndices::get).forEach(set::set);
        return set;
      });
      List<EquivalenceClass> successorGoals = state.agentGoals().stream()
          .map(goal -> goal.temporalStep(valuation).unfold())
          .toList();
      var successors = game.transitions(state.state())
          .map(transition -> transition.withDestination((HistoryState<S>)
              new ListHistoryState<>(transition.destination(), successorGoals, indices)))
          .collect(Collectors.toSet());
      transitions.putAll(state, successors);
      for (Transition<HistoryState<S>> transition : successors) {
        var successor = (ListHistoryState<S>) transition.destination();
        if (states.add(successor)) {
          queue.add(successor);
        }
      }
    }
    this.transitions = transitions.build();
  }

  @Override
  public ListHistoryState<S> initialState() {
    return initialState;
  }

  @Override
  public Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state) {
    return transitions.get(state).stream();
  }

  @Override
  public ConcurrentGame<S> concurrentGame() {
    return game;
  }
}
