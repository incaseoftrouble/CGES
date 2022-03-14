package com.cges.algorithm;

import static com.google.common.base.Preconditions.checkArgument;

import com.cges.model.Agent;
import com.cges.model.ConcurrentGame;
import com.cges.model.Transition;
import com.google.common.collect.Lists;
import de.tum.in.naturals.Indices;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import owl.factories.EquivalenceClassFactory;
import owl.factories.jbdd.JBddSupplier;
import owl.ltl.EquivalenceClass;
import owl.ltl.Formula;

public class FormulaHistoryGame<S> implements HistoryGame<S> {
  static final class ListHistoryState<S> implements HistoryState<S> {
    private final S state;
    private final List<EquivalenceClass> agentGoals;
    private final Map<Agent, Integer> agentIndices;

    ListHistoryState(S state, List<EquivalenceClass> agentGoals, Map<Agent, Integer> agentIndices) {
      this.state = state;
      this.agentGoals = List.copyOf(agentGoals);
      this.agentIndices = Map.copyOf(agentIndices);
    }

    @Override
    public Formula goal(Agent agent) {
      return agentGoals.get(agentIndices.get(agent)).canonicalRepresentative();
    }

    @Override
    public S state() {
      return state;
    }

    public List<EquivalenceClass> agentGoals() {
      return agentGoals;
    }

    @Override
    public boolean equals(Object obj) {
      return obj == this
          || (obj instanceof ListHistoryState<?> that
          && Objects.equals(this.state, that.state)
          && Objects.equals(this.agentGoals, that.agentGoals));
    }

    @Override
    public int hashCode() {
      return state.hashCode() ^ agentGoals.hashCode();
    }

    @Override
    public String toString() {
      return state + "@" + agentIndices.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(Agent::name)))
          .map(Map.Entry::getValue)
          .map(agentGoals::get)
          .map(EquivalenceClass::canonicalRepresentative)
          .map(Objects::toString)
          .collect(Collectors.joining(",", "[", "]"));
    }
  }

  private final ConcurrentGame<S> game;
  private final Map<String, Integer> propositionIndices;
  private final Map<Agent, Integer> agentIndices;
  private final HistoryState<S> initialState;

  public FormulaHistoryGame(ConcurrentGame<S> game) {
    this.game = game;
    EquivalenceClassFactory factory = JBddSupplier.async().getEquivalenceClassFactory(game.atomicPropositions());

    Map<String, Integer> propositionIndices = new HashMap<>();
    Indices.forEachIndexed(game.atomicPropositions(), (index, proposition) -> propositionIndices.put(proposition, index));
    this.propositionIndices = Map.copyOf(propositionIndices);
    Map<Agent, Integer> agentIndices = new HashMap<>();
    Indices.forEachIndexed(game.agents(), (index, agent) -> agentIndices.put(agent, index));
    this.agentIndices = Map.copyOf(agentIndices);

    this.initialState = new ListHistoryState<>(game.initialState(),
        game.agents().stream().map(Agent::goal).map(factory::of).toList(),
        agentIndices);
  }

  @Override
  public HistoryState<S> initialState() {
    return initialState;
  }

  @Override
  public Stream<Transition<HistoryState<S>>> transitions(HistoryState<S> state) {
    checkArgument(state instanceof FormulaHistoryGame.ListHistoryState<S>);
    ListHistoryState<S> history = (ListHistoryState<S>) state;
    Set<String> labels = game.labels(state.state());
    BitSet valuation = new BitSet();
    labels.stream().map(propositionIndices::get).forEach(valuation::set);
    List<EquivalenceClass> successorGoals = Lists.transform(history.agentGoals(), f -> f.unfold().temporalStep(valuation));
    return game.transitions(state.state())
        .map(transition -> transition.withDestination(new ListHistoryState<>(transition.destination(), successorGoals, agentIndices)));
  }

  @Override
  public ConcurrentGame<S> concurrentGame() {
    return game;
  }
}
